# Phase A final review — `android-v2` 1eb6d8a..e3cd466 (DAT SDK 0.9.0 migration)

Reviewer: senior code review (read-only; no Gradle/adb run; build and test evidence quoted from the ledger and task reports).
Scope: the 39-file code package plus cross-file reads of the current tree (`glasses/*`, `WearablesViewModel`, `QuickVisionService`, `RTMPStreamingViewModel`/`Service`, screens, tests, manifest, build files), the 0.9.0 CHANGELOG and the CameraAccess sample (`CameraViewModel.kt`, `WearablesViewModel.kt`).

---

## Strengths

- **The architecture is right and it is the only sane shape for 0.9.0.** `GlassesSessionManager` is genuinely the single creator of `DeviceSession`; the three borrowers plus the capturer all go through `acquire/ensureSessionStarted/addCamera/stopCamera/release`. The stopping-session gate (`stoppingSession` + `awaitPreviousSessionStopped`, bounded at 5 s, plus the one-shot `SESSION_ALREADY_EXISTS` retry) is exactly the mitigation the 0.9.0 contract needs (`stop()` only reaches STOPPING; a second `createSession` fails with `SESSION_ALREADY_EXISTS`). I traced the leave-and-re-enter, device-fold, and disconnect-mid-start sequences by hand and the state machine holds: one session at a time, owners survive a device-initiated stop, `ensureSession()` is gated, `clearStopping` is identity-checked so the `stoppingJob` and `awaitPreviousSessionStopped` cannot double-clear.
- **Lifecycle contract vs the sample is honoured.** Subscribe before `start()` (both for the session and for the outgoing session's STOPPED), STOPPED treated as terminal, `Camera.stop()` before the next `addCamera`, `stream.state` replayed-STOPPED handled with `hasBeenActive`, `VideoFrame.buffer` copied inside `collect {}` on a single-slot `Dispatchers.Default` worker in all three frame paths. `DisplayAttacher` is called on STARTED with the native session and `detach()` before stop and on device stop, which is what Phase C needs.
- **The gateway seams paid for themselves.** `DatGateway.kt` is thin (no leaking of `DatResult`), the fakes are small and honest (`stopAsync` reproduces the real STOPPING-then-STOPPED behaviour), and 24 + 6 JVM tests exercise the actual invariants: ref-count, gate, retry, camera lending, device-stop teardown, error forwarding, attacher hook, capturer retry-on-vanish. The instrumented suite drives the real SDK through the same manager (logcat in the Task 9 report shows the manager's transitions on real DAT objects), and it caught a real SDK bug.
- **Error handling is unusually complete for a migration.** Every `DatResult` failure I could find is surfaced somewhere visible: `createSession` (via `sessionError` → toast + `StreamState.Error`), `addCamera` (`CameraError.Sdk`), `stream.start()`, `capturePhoto`, `registrationErrorStream`, `openFirmwareUpdate`/`openDATGlassesAppUpdate` (SDK-localized text), `Wearables.initialize` (logged). `GlassesErrorMessages` covers every 0.9.0 enum case in zh and en and the exhaustiveness test will fail the build the day Meta adds a case. String parity between `values` and `values-zh-rCN` is exact (396/396 keys).
- **The RTMP encoder fix is correct.** One fair `ReentrantLock` shared by `feedFrame`, the output loop and `stopStreaming()`; `isStreaming`/`encoder` are `@Volatile`; no nested locking so no deadlock; fairness prevents the output loop from starving `stopStreaming()`. This is spec §6 B2 delivered early.
- **Registration crash fixed properly** (`Activity` parameters, `LocalActivity` with a graceful null path), `Wearables.initialize` moved to `Application.onCreate` and verified to work when the Bluetooth grant arrives later (Task 10 item 15).
- **Process discipline.** Every task has a review, every ruling is recorded, the Task 9 report documents an SDK race with a native stack and a reproduction rate rather than hand-waving, and the Task 10 report distinguishes a host-thrashing ANR from an app defect with evidence. The wrapper jar is byte-identical to the sample's; `.gitignore` un-ignores `app/src/release/` so the release stub is tracked; `local.properties` defaults to `"0"`; README documents the two keys.

---

## Issues

### Critical (Must Fix)

None found. I looked specifically for: double session creation, ref-count leaks across the four call sites, off-main manager calls, buffer use after `collect {}` returns, encoder-lock deadlock, release-variant compile without mockdevice, manifest placeholder defaults, and anything that would crash on a fresh clone. All hold.

### Important (Should Fix)

1. **`GlassesSessionManager.teardownAfterDeviceStop()` drops the lent `Camera` without calling `stop()`** — `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt:351-358`.
   When the device ends the session (fold, tap-and-hold, thermal, Bluetooth loss) the manager nulls `camera`/`cameraOwner` and the comment says "the SDK already stopped every capability". The sample does not rely on that: `CameraViewModel.clearStreamResources()` calls `camera.close()` on every stream termination regardless of cause, and its comment says only `stop()` detaches the capability. Downstream, `WearablesViewModel.stopStream()` → `sessionManager.stopCamera(OWNER)` is then a silent no-op because `cameraOwner` is already null, so the SDK `Camera` (which owns a `MediaCodec` decoder in the non-compressed path) is released only by GC. Fix: `runCatching { camera?.stop() }.onFailure { Log.w(...) }` before nulling in `teardownAfterDeviceStop()`; add a unit test `deviceStoppingSessionStopsTheLentCamera` asserting `cameras[0].stopCalls == 1` (the existing `deviceStoppingSessionClearsStateAndNextAcquireRecreates` only checks `currentCameraOwner`).

2. **`GlassesPhotoCapturer.captureFrom()` decodes the captured HEIC on the main thread** — `GlassesPhotoCapturer.kt:169-177` (`decodePhoto(result.photo)` at :171).
   `capture()` is documented main-only and `QuickVisionService.scope` is `Dispatchers.Main`, so `BitmapFactory.decodeByteArray` of a full-resolution HEIC (multi-MB on real glasses) runs on the UI thread while `MainActivity` may be in the foreground. `WearablesViewModel.takePhoto()` already does this correctly with `withContext(Dispatchers.Default)`; the capturer took care to decode *frames* on `frameDispatcher` but not the photo. Given that incident I1 showed the main thread is already the bottleneck during teardown, this is the cheapest ANR-risk removal on the branch: `val image = withContext(frameDispatcher) { decodePhoto(result.photo) }`. The JVM tests use `UnconfinedTestDispatcher` and are unaffected.

3. **RTMP connection failures are still swallowed** — `RTMPStreamingService.kt:146-150` (`onConnectionFailedRtmp` sets `Error` then calls `stopStreaming()`), `:460` (`stopStreaming()` unconditionally sets `_state = Idle`), and `RTMPStreamingViewModel.kt:120-124` (service `Idle` → `UIState.Idle` unconditionally).
   Pre-existing (same lines at 1eb6d8a), but the Task 5 ruling "stopStreaming() preserves an existing UIState.Error" only covers the *ViewModel's* `stopStreaming()`; the service's own state flow goes `Error → Idle` on the RTMP thread, and because `StateFlow` conflates, the Main collector frequently sees only `Idle`. Net effect: a wrong URL/stream key shows a brief "Connecting" then silently returns to Idle. Fix (3 lines): in the service, `if (_state.value !is StreamingState.Error) _state.value = Idle` at the end of `stopStreaming()`, and in the VM collector do not downgrade `UIState.Error` on service `Idle` (the explicit Stop button already calls `clearError()` first). Acceptable to fold into Phase B B2, but it is small enough to do now.

4. **Test gap: the two ViewModels and the adapter have no JVM coverage, and the capturer's stream-failure paths are untested.**
   `WearablesViewModel` owns the most intricate app-level logic on the branch (session → camera → stream state mapping, `Paused`, `hasBeenActive`, ghost-frame window, error precedence, `disconnect()`), and `RTMPStreamingViewModel` owns the encoder hand-off, yet neither can be constructed with the fakes because both reach `GlassesSessionManager.getInstance(app)` through a `lazy` and `WearablesViewModel` also calls `Wearables.*` statics for registration. `FakeGlassesCamera.startError`/`errors`/`frames` exist but no test uses them, so `PhotoCaptureOutcome.StreamStartFailed` and `StreamTimeout`-after-`streamErrors` are unverified. Phase B (OpenClaw's frame provider) will modify exactly this code. Fix in Phase B before touching the VMs: add a secondary/internal constructor taking a `GlassesSessionManager` (the manager's constructor is already `internal`, so a same-module test can build it with `FakeDatSessionFactory`), move the four `Wearables.*` registration calls behind a tiny `DatRegistrationGateway`, then add tests for: STREAMING/PAUSED/STOPPED mapping, `stream.start()` failure → `StreamState.Error` and `release`, `disconnect()` ordering, and the capturer's `StreamStartFailed`.

### Minor (Nice to Have)

5. **Specific DAT error can be masked by the generic session message** — `WearablesViewModel.kt:348-363` vs the `sessionError` collector at `:224-228`. On `CREATE_FAILED` the collector sets e.g. "No compatible glasses are connected" and `startJob` then sets "Could not start the glasses session"; which one survives depends on whether `ensureSessionStarted` suspended before failing (inline vs queued resumption under `Main.immediate`). Fix: `setError` only if `_errorMessage.value == null`, and build `StreamState.Error` from `_errorMessage.value ?: message`. Same for `RTMPStreamingViewModel.failCamera`.

6. **Ghost frame after `stopStream()`** — `WearablesViewModel.kt:466-471` vs `handleVideoFrame` `:568` on the worker (ledger Task 6). A frame mid-conversion when `videoJob.cancel()` runs still publishes to `_currentFrame` after Main nulled it. Cheapest fix: capture `val job = coroutineContext[Job]` in the collector and `if (job?.isActive == true) _currentFrame.value = newBitmap`, or a generation counter. Same window exists in `RTMPStreamingViewModel.updatePreview`.

7. **`CAMERA` permission lives in the main manifest but is only used by MockDeviceKit** — `AndroidManifest.xml:13-14`. Release APKs now declare a camera permission the app never exercises (Play listing, user trust). Spec §5.2 put it in the main manifest, so this is a spec nit: move both lines to `app/src/debug/AndroidManifest.xml` (manifest merger handles it; `uses-feature required=false` stays with it).

8. **`GlassesSessionManager.getInstance(context)` ignores `context`** — `GlassesSessionManager.kt:61-75`. Either drop the parameter or use it (e.g., to assert `Wearables` was initialized). As-is it suggests a dependency that does not exist.

9. **`startMonitoring()`'s device collector has no `catch`** — `GlassesSessionManager.kt:133-140`. If `activeDeviceFlow()` throws (SDK not initialized, permission revoked mid-flight) the `SupervisorJob` swallows it and `activeDevice` stays `null` forever with no log. Add `.catch { Log.e(TAG, "device flow failed", it) }` (and consider `retryWhen` with backoff).

10. **`DisplayAttacher.maybeAttach` fires on every STARTED, including PAUSED → STARTED resume** — `GlassesSessionManager.kt:336`. Fine for `None`, but Phase C's attacher must be idempotent and must also react to `activeDevice` arriving *after* STARTED (at STARTED time `_activeDevice.value` can still be `unknownDevice(id)` with `isDisplayCapable=false`). Document this on the interface now so Phase C does not guess.

11. **Transient `Active device: <raw id> (UNKNOWN)` on unpair/disable (D3)** — `WearablesDatAdapter.kt:94-108`. One-line fix in the `combine`: `{ id, devices -> id?.takeIf { it in devices } }` so a selector id whose metadata has already been removed resolves to `null` instead of `unknownDevice`.

12. **`GlassesErrorMessages.resId(CaptureError)` has an `else` on a sealed class** — `GlassesErrorMessages.kt:56-62` (ledger Task 6). Removing it makes the compiler enforce exhaustiveness for the sealed type; keep `@Suppress("REDUNDANT_ELSE_IN_WHEN")` only for the three enums. The test's hard-coded 4-case list then becomes redundant.

13. **Toast block duplicated in four screens** (`HomeScreen.kt≈67-72`, `LiveAIScreen.kt≈81-87`, `QuickVisionScreen.kt≈72-85`, `SimpleLiveStreamScreen.kt≈44-50`) plus the `lastGlassesError` snapshot workaround in `QuickVisionScreen`. The root cause is that `errorMessage` is used both as a one-shot event (toast + `clearError()`) and as state (Quick Vision's inline card). Phase B: hoist one `WearablesErrorToast(wearablesViewModel)` above the `NavHost` in `TurboMetaNavigation` (removes the double-toast during transitions) and add a `SharedFlow<String>` for one-shot events, leaving `errorMessage` as state. `QuickVisionScreen.kt≈209` can then read `(streamState as? StreamState.Error)?.message` directly.

14. **Dead code and stale names**: `WearablesViewModel.onFrameReceived`/`onPhotoTaken` (`:148-149`) have no consumers and `onFrameReceived` would now fire on the worker thread; `MainActivity.initializeSDK()`/`sdkInitialized` (`:132-140`) no longer initialize anything (rename `startWearablesMonitoring()`); `MainActivity.PERMISSIONS` requests `INTERNET` at runtime (`:35-39`, a normal permission — no-op); `RTMPStreamingService.feedFrame(ByteArray, …)` (`:329-345`) is unused; `RTMPStreamingScreen.kt:51` collects `cameraState` for nothing; `WearablesViewModel.kt:286,:298` hard-coded English.

15. **`convertI420toNV21` + YUV→JPEG→Bitmap is triplicated** (`WearablesViewModel.kt:545-588`, `RTMPStreamingViewModel.kt:360-390`, `QuickVisionService.kt:333-370`). Pre-existing, but OpenClaw's `camera.snap` will be the fourth copy. Extract `glasses/FrameConversions.kt` (`VideoFrame.copyI420()`, `i420ToBitmap(bytes, w, h, quality)`) in Phase B before that happens.

16. **`QuickVisionService.getLocalizedString`** (`:408-454`) is a hand-rolled 4-language table with a single `R.string` case bolted on. Phase D's TTS unification is the natural moment to move these to resources.

17. **`videoJob` comment overstates "subscribe BEFORE start()"** — `WearablesViewModel.kt:384-390`. `launch(frameDispatcher)` is dispatched, so the collector attaches a few ms after `startStream()`; harmless for a preview, but fix the comment (or `start = CoroutineStart.UNDISPATCHED` if the first frames matter for OpenClaw).

18. **Main-thread contract is convention-only.** A debug-only guard at the top of the five public mutators (`if (BuildConfig.DEBUG) check(Looper.getMainLooper() == Looper.myLooper())`) would turn a future off-main call from a silent state corruption into a crash in development. JVM tests are unaffected (`isReturnDefaultValues` makes both loopers `null`).

19. **Instrumented suite coverage**: no test for PAUSED/resume or for a device-initiated stop (fold) through the manager, both of which Task 10 exercised only manually. Cheap to add with `device.services.captouch.tap()` and `device.fold()`.

---

## Ledger Triage

Legend: **MUST-FIX-BEFORE-MERGE** = land on `android-v2` before the branch is merged to `main` (do it at the start of Phase B if it is cheap); **PHASE-B** = schedule in Phase B; **DROP** = not worth doing.

| Ledger line | Ruling | Reason |
|---|---|---|
| T1 minor: targetSdk 36 enforced edge-to-edge; `Theme.kt:61-62` deprecated `statusBarColor`/`navigationBarColor` | PHASE-B | Real layout risk under targetSdk 36 on API 35+ devices; verify on the product owner's phone in B3 and switch to `enableEdgeToEdge` insets. |
| T1 minor: Compose BOM deprecations (`Icons.Filled.MenuBook/VolumeUp/VolumeDown`, `TabRow`) | DROP | Warnings only; migrate opportunistically when those screens are next touched. |
| T2 minor: `MainActivity.initializeSDK()/sdkInitialized` stale naming | PHASE-B | Trivial rename; bundle with the Phase B hygiene commit. |
| T3 minor: `stopSession()` overwrites an earlier parked `stoppingSession` | DROP | Verified unreachable: the only way to have a new session while one is parked is the 5 s timeout path, which already cleared it. |
| T3 minor: tautological branches in `awaitStartedResolvesTrueOnStartedAndFalseOnStopped`; `assertNull(nativeSession)` | PHASE-B | Rewrite the false branch to resolve on a real STOPPED (create, `async { awaitStarted }`, `emitStoppedByDevice()`); drop the null assertion. |
| T3 minor: `sessionError` replay=0/buffer=16, `tryEmit` result discarded | DROP | Ordering is safe by construction: the only collector (`WearablesViewModel.startMonitoring`) subscribes before any screen can call `startStream()`, and the service path carries failures in the capturer's return value. Add one KDoc sentence saying so. |
| T3 minor: `clearStopping()` cancels the job it runs inside | PHASE-B | Works today; make it non-self-referential (`if (stoppingJob !== coroutineContext[Job])`) before Phase C adds work after it. |
| T3 minor: unused fake surface (`startError`, `frames`, `errors`) | PHASE-B | Use them for the missing `StreamStartFailed`/stream-error capturer tests (Important #4). |
| T4 minor: `disconnect()` stops the session unconditionally | DROP | Justified: `disconnect()` also unregisters from Meta AI, so no session could survive; RTMP self-heals via stream STOPPED → `stopStreaming()`, the capturer retries once. Document the intent in KDoc. |
| T4 minor: hard-coded English `WearablesViewModel.kt:286,:298` | PHASE-B | Two strings; `camera_permission_denied` already exists. |
| T4 minor: early-error paths / dead `hasBeenActive=false` / `onCleared` no-op cancels | DROP | Early-error paths were fixed in the Task 4 fix round; the rest is cosmetic. |
| T4 minor (→T6): `startStream()` does not `clearError()` | DROP | Already delivered: `clearError()` is now the first thing `startStream()` does (`:329`). |
| T5 minor (→T6): `cameraErrorMessage` duplicated | DROP | Already delivered: both are one-line delegates to `GlassesErrorMessages.of`. |
| T5 minor (→T9): frame-fallback success branch untested on JVM | DROP | Covered on device by `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable`. |
| T5 minor: aggregate capture latency unbounded (~36 s worst case) | PHASE-B | Wrap `borrowCameraAndCapture()` in a single `withTimeoutOrNull(TOTAL_BUDGET_MS)`; the wake-word user should hear a failure within ~15 s. |
| T5 minor: RTMP can stick in Connecting with no stream budget | PHASE-B | B2 scope: add a first-frame timeout in `RTMPStreamingViewModel`. |
| T5 minor: RTMP frame path on main | DROP | Delivered in Task 6 (`frameDispatcher`). |
| T5 minor: dead `cameraState` collection `RTMPStreamingScreen.kt:51` | DROP | Cosmetic; remove when the screen is next edited. |
| T5 minor: `getLocalizedString` half-migrated | PHASE-B | Fold into Phase D's TTS unification (Minor #16). |
| T5 minor: `convertI420toNV21` triplicated | PHASE-B | Extract before OpenClaw adds a fourth copy (Minor #15). |
| T5 minor: Stop button `clearError()` dead; `stopStreaming()` leaves Error on dispose | DROP | Error state is recoverable via Start; a fresh VM is created on re-entry. |
| T6 minor: ghost frame after `stopStream` | PHASE-B | Minor #6; a few lines. |
| T6 minor: `isProcessingFrame` unreachable under `limitedParallelism(1)` | DROP | Plan-mandated and harmless; the real back-pressure is the SDK's. Fix the comment. |
| T6 minor: `else` in `resId(CaptureError)`; hard-coded 4 cases | PHASE-B | Minor #12; one-line deletion plus test simplification. |
| T6 minor: toast block duplicated ×4; double toast during nav | PHASE-B | Minor #13; hoist above the `NavHost`. |
| T6 minor: `videoJob` no longer subscribes synchronously | DROP | Harmless for preview; fix the comment (Minor #17). |
| T6 minor: encoderLock serializes input/output queues (≤ ~20 ms/frame) | PHASE-B | Measure on hardware in B2; narrow the lock around `dequeueOutputBuffer` only if drop rate rises. |
| T6 minor (pre-existing): RTMP double disconnect, output loop busy-spin, `initEncoder` leak, dead `feedFrame(ByteArray)`, `disconnect()` on Main | PHASE-B | Exactly B2's remit. |
| T6 minor: `QuickVisionScreen.kt:209` read `StreamState.Error.message` directly | DROP | The snapshot works and was manually verified; superseded by the Phase B toast refactor. |
| T7 minor: `POST_NOTIFICATIONS` dialog after monitoring starts | DROP | Ordering is plan-mandated and harmless. |
| T7 minor: `micGranted` seeded once, not re-checked after granting in Settings | PHASE-B | Users who deny then grant in system Settings must leave and re-enter Live AI; re-check on `ON_RESUME` with `LifecycleEventEffect`. |
| T8 minor: paired devices not rehydrated (became D1) | see D1 | — |
| T8 minor: `clearError()` dead; unpair without powerOff; toggles not gated; SAF URIs non-persistable | DROP | Debug-only; `GetContent` grants last for the process lifetime, which is all the mock needs. |
| T9 parked (1): no Gradle `connectedDebugAndroidTest` report (winnat 9577-9676 reservation) | DROP (as a code item) | Environmental; the `am instrument` evidence (3× API 31, 2× API 36) is sound. The user should run `net stop winnat && net start winnat` from an admin shell once; CI machines are unaffected. |
| T9 parked (2): singleton has no per-test reset hook | PHASE-B | Add `@VisibleForTesting internal fun resetForTests()` (or an `installForTests(instance)`) when the ViewModel tests from Important #4 are written. |
| T9 SDK finding: `VideoDecoder.activateDecoder()`/`MediaCodec.reset()` vs `Camera.stop()` native abort | PHASE-B | Report upstream now with the Task 9 stack; verify on hardware with a rapid open/close of Live Stream (< 300 ms after STREAMING). See Recommendations for the defensive design if it reproduces. |
| T10 D1 (medium): MockDeviceKit screen loses paired-device cards on re-entry | MUST-FIX-BEFORE-MERGE | Debug-only but it is the verification tool Phases B and C depend on, and it currently forces a two-task workaround. ~10 lines: seed `pairedDevices` from `mockDeviceKit.pairedDevices.filterIsInstance<MockGlasses>()` in `init` (`MockDeviceKitViewModel.kt:52-53`), or scope the VM to the Activity. Do it first thing in Phase B. |
| T10 I1: ANR during stream teardown under host memory pressure | PHASE-B | Not reproducible on a healthy host and the sample also tears down on Main; but measure `camera.stop()`/`session.stop()` wall time on hardware. If > 16 ms, move the SDK stop calls to a background dispatcher inside the manager (state bookkeeping stays on Main). |
| T10 D2 (low): `mock_device_name` ignores in-app language switch | PHASE-B | Fold into the D1 fix (`stringResource` in the composable). |
| T10 D3 (low): transient `Active device: <raw id> (UNKNOWN)` | PHASE-B | One-line `takeIf { it in devices }` in the adapter (Minor #11). |

---

## Recommendations

1. **On the Task 9 SDK race: keep the 2 s settle in the test, do not add speculative delays to production code, and report upstream now.** The abort is inside the SDK's transport thread; the app calls `Camera.stop()` exactly as the official sample does. A settle in a test that documents the window (~300 ms after STREAMING) is the right call today. If a hardware repro with rapid open/close of Live Stream succeeds in Phase B, the defensive measure belongs *inside the manager*, not in callers: record `streamingSince` per lent camera (the manager already has the camera; subscribe to its `streamState` when lending), and in `stopCamera()`/`stopSession()` route the actual `camera.stop()` (and the subsequent `session.stop()`) through the existing async stop path (`stoppingJob`) with `delay(SETTLE - elapsed)` when the stream has been STREAMING for less than ~400 ms. Bookkeeping (`camera = null`, `cameraOwner = null`, `session = null`, STOPPING) stays synchronous so callers' contracts do not change; a re-`addCamera` during the deferral is refused by the existing `stoppingSession` gate. Waiting for STREAMING before stopping a STARTING camera (the alternative named in the brief) would *not* cover the observed race, which happens *after* STREAMING.

2. **Phase B (OpenClaw) needs a frame source that is not a ViewModel.** `camera.snap` arrives in `OpenClawNodeService`; today decoded frames exist only in `WearablesViewModel._currentFrame` and the manager lends the raw `GlassesCamera` to exactly one owner. Two workable designs: (a) `GlassesPhotoCapturer` as-is when nobody holds the camera (it already returns `CameraBusy` cleanly), plus (b) a `latestFrame: StateFlow<Bitmap?>` on the manager that the current camera owner publishes into (the VM already decodes; make it `sessionManager.publishFrame(bitmap)`), so a snap during Live AI returns the last decoded frame instead of `CameraBusy`. Pick (b) for parity with iOS's "current frame" semantics; implement `GlassesFrameProvider` (spec §6 B1) over it. Extract the YUV conversion (Minor #15) first.

3. **Phase C (Display) prerequisites in the manager**: make `maybeAttach` idempotent and device-update-aware (Minor #10); expose `CameraState` through `GlassesCamera` (or track "camera stopping" in the manager) so a same-session camera hand-off while a Display owner keeps the session alive cannot hit `CAPABILITY_ALREADY_ADDED`; wire the real attacher via `getInstance` (already the single construction point).

4. **Testing strategy for Phase B**: make the two ViewModels constructible with fakes (Important #4) before OpenClaw touches them; add PAUSED/fold instrumented cases; add `resetForTests()` to the singleton. Keep `am instrument` as the documented fallback runner on this host until winnat is restarted.

5. **Plan-level observations** (not implementation defects): the plan's "toast + `clearError()` in every screen" design created the Quick Vision race that needed the `lastGlassesError` snapshot — a one-shot event channel should have been specified; the `isProcessingFrame` drop policy was specified on a dispatcher where it cannot trigger; fixing exact test counts (23/34/35) turned every coverage improvement into a controller ruling — future plans should state a *minimum*; `CAMERA` belongs in the debug manifest.

---

## Assessment

**Ready to merge?** With fixes

**Reasoning:** The session manager, borrower migration, lifecycle contract and error surfacing are correct and well-tested, and nothing on the branch is broken or lossy; but two small correctness items (`Camera.stop()` on device-initiated stop, HEIC decode on the main thread) and the D1 MockDeviceKit rehydration should land before this is treated as the foundation for Phases B-E, and the RTMP error-swallowing and ViewModel test gap should be scheduled as the first Phase B tasks.
