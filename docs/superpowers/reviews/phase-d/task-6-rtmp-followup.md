# Phase D Task 6 — RTMP auth callback follow-up

Status: parent reports the final integrated build and JVM suites passed, including all 13 new callback tests; the same-APK 56-test platform rerun is in progress. No Gradle, adb, test execution or commits performed by this worker.

## Confirmed carry-over

Read Phase B `sdd-ledger.md:83`: `onAuthErrorRtmp` only published Error, never stopped resources, and left `isStreaming = true`, preventing Start until leaving the screen. Final review I6(b) identified the missing production callback/generation/Error-through-stop seam; I6(c) called out retry coverage. Existing RTMP JVM tests covered only the output-loop null-resource guard.

## Changes

- `android/app/src/main/java/com/smartview/glassai/services/RtmpConnectionState.kt` — small production class containing the actual `ConnectCheckerRtmp` implementation, generation fencing, existing streaming flag/start admission, and Error-preserving stop transition. Its listener injects only statistics and resource teardown. Authentication and connection failures share `Error -> stop`; stop clears the streaming flag and retires the generation before teardown. Current-generation checks and state changes share the service's stop lock.
- `android/app/src/main/java/com/smartview/glassai/services/RTMPStreamingService.kt` — passes those actual callbacks to `RtmpClient`; delegates stopping to the shared transition and retains its existing codec/client teardown body and disconnect executor. Start admission occurs before encoder allocation, with the non-suspending setup serialized under `stopLock` on IO; rejected concurrent starts allocate nothing and initialization failure also calls stop. The lock order remains `stopLock -> encoderLock`; the output-loop failure branch still stops after leaving `encoderLock`. The synchronous-connect-failure output-loop guard remains. URL logging and raw exception/server-reason reporting were removed from this service; failure messages are fixed safe text.
- `android/app/src/test/java/com/smartview/glassai/services/RtmpConnectionStateTest.kt` — 13 JVM tests using the real production callback/state class and fake resource stops, with no Android codec/socket mocks or copied lifecycle logic.
- `android/app/src/test/java/com/smartview/glassai/services/RTMPStreamingServiceTest.kt` — documentation updated to point to the callback tests; existing four guard tests retained unchanged.

No RTMP ViewModel, navigation, camera/session manager, resource strings or dependency files were edited.

## Authored coverage

1. Current connection/auth/bitrate callbacks and safe URL handling.
2. Duplicate start refused while active.
3. Connection failure publishes a safe Error before teardown and preserves it through disconnect.
4. Authentication failure publishes Error before teardown and preserves it through disconnect.
5. Auth failure clears start admission; explicit retry connects and ignores retired callbacks.
6. Connection failure clears start admission; explicit retry connects.
7. User Stop remains Idle; all seven retired callbacks are harmless without restart.
8. Replaced callbacks cannot mutate or stop the newer streaming attempt.
9. Repeated stop preserves Error until explicit retry.
10. A callback queued behind user Stop cannot publish a late Error (bounded executor/latch test).
11. Concurrent connection/auth failures invoke resource teardown once for that attempt.
12. Synchronous connection failure leaves the existing output-loop guard closed.
13. Current unexpected disconnect retains the existing Disconnected behavior.

Fake stops only release fake resources and record observations; the production class performs generation changes, start admission, serialization and Error preservation. These tests do not claim coverage of actual `MediaCodec` release or socket disconnect I/O.

## Verification and handoff

- Static review and scoped `git diff --check` completed; no whitespace errors reported.
- Authored: 13 new JVM tests. Run by this worker: **0**. Parent reports **all 13 callback tests PASS** in the final integrated run.
- Parent final run: **1m 4s; 522 debug / 510 release JVM tests, 0 failures, 0 errors, 0 skipped; both APKs and androidTest build passed**. These results are parent-reported, not independently rerun by this worker.
- Parent compile finding: the computed private Boolean property `isStreaming` generated a JVM `isStreaming()` getter that clashed with the existing public `fun isStreaming()`. Parent renamed the private property and its five references to `streamingActive`, preserving the public method. A read-only source check confirmed that correction; the subsequent parent build passed as recorded above. This worker made no additional source changes.
- The final **same-APK 56-test platform rerun is running**; no result is claimed yet. The earlier 56-test green run predates this patch. No further source wiring is required; production constructs the same callback implementation exercised by the passing JVM tests.
- Lock-wait limitation: a Main-thread Stop can wait for the IO-thread start section, including `MediaCodec` initialization and the library's `connect()` setup, to finish. This bounded scope serializes setup instead of introducing asynchronous cancellation/state machinery. No new inline disconnect path was added; the existing executor and its pre-existing post-release rejection fallback are unchanged.
- No physical hardware or live RTMP authentication-server result is claimed.

## Scoped independent re-review — 2026-09-11

Reviewed the actual `RtmpConnectionState`, service wiring/setup/teardown and all 13 new regression cases after the parent's ready notice. **No new material issue found within this auth-failure/stop/retry follow-up.** This is source/API inspection, not a passing test/build claim. No application/test edits, Gradle, adb or commits were performed by the reviewer.

- **Real API/JVM boundary:** inspected cached pinned RTMP **2.2.6** class signatures with `javap`. `com.pedro.rtmp.utils.ConnectCheckerRtmp` is a public interface with exactly seven methods: `onConnectionStartedRtmp(String)`, `onConnectionSuccessRtmp()`, `onConnectionFailedRtmp(String)`, `onNewBitrateRtmp(long)`, `onDisconnectRtmp()`, `onAuthErrorRtmp()`, `onAuthSuccessRtmp()`, all returning void. The interface itself has no Android parameter types. `RtmpClient` accepts this exact interface in its constructor; its Android `MediaCodec.BufferInfo` dependency belongs to video sending, outside these callback fixtures. The tests exercise the same callback object production passes to `RtmpClient`, without instantiating codecs or a network client.
- **Admission before resources:** `RTMPStreamingService.startStreaming` holds `stopLock`, calls `beginConnection` first, and returns immediately on rejected admission. Encoder allocation is afterward; initialization failure retires/stops before returning. This resolves the already-reported rejected-start codec issue. The whole non-suspending setup is serialized with Stop, and the null-resource guard remains after a potentially synchronous `connect` failure.
- **Generation and error preservation:** callback generation checks and all callback mutations share `stopLock`; auth/connection failure set safe Error text then reenter `stop` on the same reentrant monitor. Stop clears streaming/admission and increments generation before invoking teardown. Disconnect callbacks raised by that teardown are retired and cannot replace Error with Disconnected/Idle. An explicit new start advances generation and replaces Error with Connecting; retired callbacks cannot alter or stop it. A callback waiting behind user Stop checks generation only after acquiring the lock, so it cannot publish a late error from a pre-Stop check.
- **Lock ordering:** the callback transition enters `stopLock`; service teardown reenters it and then acquires `encoderLock`. Feed/output codec work holds only `encoderLock`; the output failure handler calls Stop after leaving that lock. Inspected the pinned video-send path: `sendVideo` delegates to packet creation/queueing, and the frame-created handler queues or counts a dropped frame rather than invoking a connection failure inline under the encoder lock. Normal disconnect is queued on the existing executor and callbacks use the retired generation. No new `encoderLock -> stopLock` path was found in the modified callback/setup integration.
- **Test scope:** all 13 cases use the actual API/state implementation, including auth/connection failure before teardown, explicit retry, all seven retired callbacks, waiting-behind-Stop and competing-failure serialization. These are meaningful callback/state regressions; they do not prove physical codec cleanup, socket I/O or platform timing. Reviewer executed **0 tests**; the parent owns the pending integrated run.

The reported Main-thread Stop wait for IO setup remains an acknowledged limitation, with no measured timing guarantee from this review. Existing unexpected-disconnect semantics and other RTMP legacy behavior were deliberately outside this follow-up; this review does not certify them.

### Supplement — synchronous video-send callback/lock audit

The parent specifically requested the indirect path under `encoderLock`. Rechecked pinned **2.2.6** `RtmpClient`, `RtmpSender` and `H264Packet` bytecode; the synchronous call chain is:

`RtmpClient.sendVideo -> RtmpSender.sendVideoFrame -> H264Packet.createFlvVideoPacket -> VideoPacketCallback.onVideoFrameCreated -> RtmpSender.onVideoFrameCreated -> BlockingQueue.add`.

`RtmpSender` constructs `H264Packet` with itself cast to `VideoPacketCallback`. H264Packet's two synchronous packet emissions (configuration and video data) call that packet callback, **not `ConnectCheckerRtmp`**. The sender's packet handler only adds to its 60-entry LinkedBlockingQueue; queue-full IllegalStateException logs/counts a dropped frame and returns. It does not notify connection/auth/bitrate callbacks or wait for the socket worker.

The actual socket-send loop is separately submitted by `RtmpSender.start()` to `Executors.newSingleThreadExecutor()` via `ExecutorService.execute`; its worker polls the queue and owns the inspected `onConnectionFailedRtmp` call. Therefore this pinned `sendVideo` path does not synchronously acquire the application's `stopLock` while the encoder thread holds `encoderLock`. A failure arriving from the sender worker may wait for encoder release during teardown, but the producer's queue operation does not wait for that worker, so the examined path does not form a reverse-lock cycle. A thrown packet/codec exception still reaches the service's catch and invokes Stop only after leaving `encoderLock`.

No lock restructuring is requested from this audit. Also confirmed the parent's private getter rename is present (`streamingActive`) while public `isStreaming()` remains. Only this report was appended; no app/test edits or runners.
