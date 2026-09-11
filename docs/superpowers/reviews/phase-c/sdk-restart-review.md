# DAT 0.9.0 immediate session restart hang — artifact review

The captured failure points to SDK/MockDeviceKit transport and health-channel synchronization, with no adapter misuse demonstrated on this path. **A later internal stop acknowledgement exists (`SESSION_STOP_RESPONSE`), but the inspected public DeviceSession API exposes no separate waitable acknowledgement or completion state beyond STOPPED. Public STOPPED is published before SDK teardown finishes.** The exact native mutex ownership cycle cannot be proved from this one dump.

This was a bounded read-only diagnostic. Only this report was written. No app/test code, saved trace or log was changed; no Gradle, adb, device/MCP command, commit or access to `local.properties` occurred. The controller's test version with two explicit pre-stop 2-second settles has not been rerun according to the supplied task context; this report does not treat it as a fix or a passing result.

## Evidence and failure sequence

Inputs: [hang trace](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/build/codex-verification/phase-c-hang-trace.txt), [session log](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/build/codex-verification/phase-c-session-log.txt), [instrumentation output](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/build/codex-verification/phase-c-instrumented.txt), the current instrumented test, adapter/manager source, and cached DAT 0.9.0 class bytecode inspected with `javap -p -c` using read-only jar URLs.

The trace is for PID 4403 on the Android 12 x86_64 emulator, captured at 12:09:09 UTC. Its relevant preceding log sequence is:

| UTC time | Observation |
|---|---|
| 12:06:07.724 | Acquire `InstrumentedTest`, session-only. |
| 12:06:07.731 | Session STARTED; release happens in the same millisecond. |
| 12:06:07.732–.735 | Stop requested; outgoing session reports STOPPED; `session.stop()` returns after 3 ms. |
| 12:06:07.735–.736 | Acquire `display-feature`; replacement session reaches IDLE then STARTING. No create failure or SESSION_ALREADY_EXISTS is logged. |
| 12:06:27.743 | About 20 seconds later, teardown invokes stop. There was no intervening STARTED for the replacement. |
| 12:06:27.744 | STOPPED is reported, but the `session.stop() took …` completion line never appears. The later dump locates the blocked call. |

The saved instrumentation output reports five completed tests, then the start of `acquireAndStartFromSessionOnlyOwnerAfterStoppingReachesStarted`, without its final result. The controller reports the 20-second failure; the log corroborates the timing. A completed JUnit failure summary is not present because teardown did not finish.

## Threads 1, 19 and 50

| Thread | Captured hold/wait relationship | Source |
|---|---|---|
| 1, `main` | Holds DeviceSession lock `0x01ddfcba`; waits for health-manager lock `0x0e7519e5` in `DeviceHealthManager.removeListener`, called by `DeviceSession.beginStopLocked → stop → SdkGlassesSession.stop → GlassesSessionManager.stopSession → instrumented tearDown`. | [Trace line 225](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/build/codex-verification/phase-c-hang-trace.txt:225) |
| 50, `IOScheduler-duplex-read-95` | Holds health lock `0x0e7519e5` and SessionManager lock `0x01c52374`; waits for SessionChannel lock `0x0edf2a86`, owned by thread 19. Its receive callback runs `deactivate → onDeactivated → rebindHealthChannel → rebindChannel → createChannel → getOrCreateSharedDwaChannel`. It originates in `FakeLinkedDeviceImpl.processQueuedMessages` through `Connection.onReceivedNative`. | [Trace line 853](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/build/codex-verification/phase-c-hang-trace.txt:853) |
| 19, `DefaultDispatcher-worker-1` | Holds SessionChannel lock `0x0edf2a86` while opening a channel for `SessionManager.startSession`. It is blocked in a native recursive-mutex acquisition in `datax::LocalChannel::open`, reached through `Connection.openChannel`. | [Native stack line 475](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/build/codex-verification/phase-c-hang-trace.txt:475), [managed stack line 565](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/build/codex-verification/phase-c-hang-trace.txt:565) |

The proven chain is **main → health lock/thread 50 → channel lock/thread 19 → native mutex**. The dump does not identify that native mutex's owner. Thread 50's native receive callback makes an SDK/MDK native/Java lock-order cycle plausible, but identifying it as the native owner would exceed the evidence. Main is blocked inside SDK synchronization, not waiting on an app coroutine mutex or performing a display render.

The teardown timeout is after the synchronous `manager.stopSession()` call ([test line 107](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt:107)); it is never reached while that call is blocked. The earlier 20-second suspend timeout therefore does not bound this SDK teardown hang.

## What STOPPED and the acknowledgement mean in this artifact

These are bytecode offsets within methods, not source line numbers:

1. `DeviceSession.stop()` enters its lock, calls `beginStopLocked()` at offset 119, releases the lock, then calls `finishStop()` at 137.
2. `beginStopLocked()` sets STOPPING, stops heartbeat monitoring, captures/clears capabilities, cancels its state/error collectors and clears its session handle. It invokes the internal `onStopped` callback at 140, publishes **STOPPED at 153**, then calls `healthManager.removeListener` at **180** and cancels its coroutine scope at 194. Thus STOPPED can be observed while `stop()` is still blocked removing the listener—as this dump shows.
3. Only afterwards does `finishStop()` close captured capabilities and call `SessionHandle.stop()` at 91. The internal `SessionManager.stopSession()` removes the channel from its lookup before calling `requestStopSession` at 95.
4. `SessionChannel.requestStopSession` sends a `SESSION_STOP_REQUEST` via `LocalChannel.send` at 91 and returns at 94. There is no response wait in that successful send path. Even a returned public `stop()` is therefore not a remote acknowledgement barrier.
5. **An internal response really exists.** `SessionChannel.handleLocalChannelReceived` checks `SESSION_STOP_RESPONSE` at offset 730. The switch mapping identifies its success branch as `SessionStopResponse.Status.SUCCESS`; that branch updates the internal DATSession to STOPPED at 813 and calls `deactivate()` at 817. This is distinct from the earlier public DeviceSession STOPPED publication. The local state monitor was already cancelled, and there is no new public state representing completion of this response's processing.
6. `deactivate()` calls `tryDeactivate()` and then its `onDeactivated` callback. The callback `SessionManager$startSession$2$2$3` removes a channel only if the stored instance still matches, then calls `rebindHealthChannel` at 99 **even when the identity did not match**. `DeviceHealthManager.rebindChannel` calls `createChannel` at 74 while retaining its own lock; the latter enters the session/channel lookup seen in thread 50. `SessionChannel.getChannel` holds its lock across native `Connection.openChannel` at 254, matching thread 19.

The captured receive stack does not include a decoded message/status. Both a successful stop response and other terminal receive paths can reach `deactivate`; this review does not claim to identify the exact packet thread 50 received.

The inspected public DeviceSession lifecycle surface provides `state`, `errors`, `start(): Unit` and `stop(): Unit`; its state enum is IDLE, STARTING, STARTED, PAUSED, STOPPING, STOPPED. No public `close`, `awaitClosed`, stop future/job, transport-ready-to-restart signal, or separate ack callback was found. Internal protocol classes and Kotlin-internal, JVM-mangled methods are not a supported app completion contract. Internal stop telemetry is also marked successful before health-listener removal, so it is not a stronger barrier in this implementation.

## Adapter assessment and the changed test

[SdkGlassesSession](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt:82) forwards the SDK state/errors unchanged and calls `session.start()` / `session.stop()` directly. [The manager](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt:345) creates a fresh SDK session, subscribes before starting, and observes the outgoing session's STOPPED. In this sequential test, the replacement acquisition occurs after the preceding `release()` and its synchronous stop return; the log confirms that return. The manager's fallback that creates after a previous-stop timeout is **not** the path shown here.

The fixture pairs `GlassesModel.RAYBAN_META`, and this test acquires only session ownership with no camera claim. `display-feature` is an owner label, not evidence of an attached display. No display attachment or camera-start call is implicated in this failing session-only sequence. Prior camera tests ran in the process, so this single capture cannot exclude residual SDK/MDK process state or establish reproduction on real glasses.

The current [restart test](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt:392) waits `STREAM_SETTLE_MS = 2_000` after STARTED **before stopping each of its two sessions**. It still attempts replacement immediately after the first stop returns. Those pre-stop delays change the stress being exercised; they neither await the internal stop response nor prove transport readiness after STOPPED. The test comment's stronger claim about unfinished startup is a hypothesis consistent with the timing, not something this trace alone establishes.

**Ruling:** retain the rapid-restart failure as an unresolved SDK/MDK issue. The available public API offers no stronger acknowledgement the adapter simply forgot to await. The evidence supports SDK-side restart/deactivation/health-rebind coordination as the investigation target; it does not justify a speculative adapter change, a fixed production delay, or treating the unrun settled-session test as closure. Changing the caller thread would not remove the demonstrated internal wait chain. The bounded review leaves native lock ownership, exact incoming packet, clean-process reproducibility and physical-device applicability unresolved.

## Artifact provenance

Disassembly used the existing extracted jar at `C:/Users/Lee_L/.gradle/caches/8.14.1/transforms/34bc9b58065b2815f588de21f8876a89/transformed/mwdat-core-0.9.0/jars/classes.jar`. Its SHA-256 is `702149B2EAD7074EC73EDE63FFD17551F2662669A3CF5144FA8F089FF2DC79D5`, verified equal to the `classes.jar` entry read directly from the cached `com.meta.wearable:mwdat-core:0.9.0` AAR. That AAR's SHA-256 is `B6595CE7C7F746750BA649B6C3C94F7ADEB95961C073760999143C93E2DDB983`; its cache artifact directory is `77967dd1eb1c1088e909307e57424633ff5aeea2`. No SDK archive was extracted or changed during this review. Method names and lock relationships match the preserved runtime trace; this was not a new execution of the failing test.
