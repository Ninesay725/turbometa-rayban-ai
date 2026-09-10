# Task 5 report — Migrate `QuickVisionService` + `RTMPStreamingViewModel`

**Status: DONE**
**Commit: `8bc45a6`** — `feat(android): migrate QuickVisionService (via GlassesPhotoCapturer) and RTMP streaming to the shared session (capturePhoto, 12 s budgets, CameraBusy); assembleDebug green on DAT 0.9.0`
Branch `android-v2`, working tree clean after the commit.

---

## 1. What I implemented

All ten brief steps, in order.

| Step | Result |
|------|--------|
| 5.0 | Created `GlassesPhotoCapturerTest.kt` verbatim from the brief (5 tests). |
| 5.0a | RED confirmed: `BUILD FAILED` at `:app:compileDebugKotlin`; test source set never compiled. |
| 5.0b | Created `GlassesPhotoCapturer.kt` (+ one binding deviation, see §5). |
| 5.1 | `QuickVisionService` imports swapped (DAT stream-session APIs → `PhotoData`/glasses package); dropped `kotlinx.coroutines.flow.first`; added `kotlinx.coroutines.CancellationException`. |
| 5.2 | Fields replaced by `sessionManager` (lazy `GlassesSessionManager.getInstance(this)`) + `captureJob`; `private const val OWNER = "QuickVisionService"` added to the companion. |
| 5.3 | `cleanup()` rewritten (cancel job, `stopCamera(OWNER)`, `release(OWNER)`). |
| 5.4 | `captureAndAnalyze()` replaced by `captureAndAnalyze()` + `failAndFinish(key)` + `decodePhoto(PhotoData)`; `convertVideoFrameToBitmap`/`convertI420toNV21` untouched. |
| 5.5 | `"camera_busy" -> getString(R.string.glasses_camera_busy)` added before `else -> key`. |
| 5.6 | `RTMPStreamingViewModel.kt` rewritten in full (borrows the shared camera; defensive frame copy). |
| 5.7 | `import com.meta.wearable.dat.camera.types.StreamSessionState` deleted from `RTMPStreamingScreen.kt`. |
| 5.8 | `:app:assembleDebug` → `BUILD SUCCESSFUL in 52s`; APKs regenerated. |
| 5.9 | `:app:testDebugUnitTest` → 29 tests, 0 failures. |
| 5.10 | Committed with the brief's exact command and message. |

---

## 2. Public API produced

### `com.smartview.glassai.glasses.PhotoCaptureOutcome`
`android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt`

```kotlin
sealed class PhotoCaptureOutcome<out T> {
    data class Captured<T>(val image: T, val fromVideoFrame: Boolean) : PhotoCaptureOutcome<T>()
    object NoDevice : PhotoCaptureOutcome<Nothing>()
    object SessionFailed : PhotoCaptureOutcome<Nothing>()
    object SessionTimeout : PhotoCaptureOutcome<Nothing>()
    data class CameraUnavailable(val error: CameraError) : PhotoCaptureOutcome<Nothing>()
    data class StreamStartFailed(val error: StreamError) : PhotoCaptureOutcome<Nothing>()
    object StreamTimeout : PhotoCaptureOutcome<Nothing>()
    object NoImage : PhotoCaptureOutcome<Nothing>()
}
```

### `com.smartview.glassai.glasses.GlassesPhotoCapturer`

```kotlin
class GlassesPhotoCapturer<T : Any>(
    private val sessionManager: GlassesSessionManager,
    private val owner: String,
    private val config: StreamConfiguration,
    private val decodePhoto: (PhotoData) -> T?,
    private val decodeFrame: (VideoFrame) -> T?,
    private val frameDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deviceWaitMs: Long = DEFAULT_DEVICE_WAIT_MS,
    private val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS,
    private val streamTimeoutMs: Long = DEFAULT_STREAM_TIMEOUT_MS,
    private val fallbackFrameTimeoutMs: Long = DEFAULT_FALLBACK_FRAME_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_DEVICE_WAIT_MS = 3_000L
        const val DEFAULT_SESSION_TIMEOUT_MS = 12_000L
        const val DEFAULT_STREAM_TIMEOUT_MS = 12_000L
        const val DEFAULT_FALLBACK_FRAME_TIMEOUT_MS = 2_000L
    }

    suspend fun capture(): PhotoCaptureOutcome<T>   // main thread; always stopCamera + release in finally
}
```

Exactly the signature the Interfaces block promises Tasks 6–9. `capture()` is the only public member.

### `QuickVisionService` — unchanged public contract

`ACTION_CAPTURE_AND_ANALYZE = "com.smartview.glassai.CAPTURE_AND_ANALYZE"`,
`ACTION_STOP = "com.smartview.glassai.STOP_QUICK_VISION"`,
`ACTION_ANALYSIS_COMPLETE = "com.smartview.glassai.ANALYSIS_COMPLETE"`,
`ACTION_QUICK_VISION_STATUS = "com.smartview.glassai.QUICK_VISION_STATUS"`,
`EXTRA_RESULT = "analysis_result"`, `EXTRA_ERROR = "analysis_error"`, `EXTRA_STATUS = "status"`.
Broadcast statuses still `started | streaming | analyzing | complete | error | finished`. Verified against
`PorcupineWakeWordService.kt:79-88` (it resets `isProcessing` on `finished`/`error`): every failure path goes
`failAndFinish(key)` → `broadcastStatus("error")` → `finishService()` → `broadcastStatus("finished")`.
New private constant only: `private const val OWNER = "QuickVisionService"`.

### `RTMPStreamingViewModel` — unchanged except the promised type change

`uiState: StateFlow<UIState>`, `rtmpUrl`, `previewFrame`, `streamStats`, `bitrate`, `DEFAULT_RTMP_URL`,
`updateRtmpUrl`, `updateBitrate`, `startStreaming`, `stopStreaming`, `clearError` — all unchanged.
`cameraState: StateFlow<DatStreamState?>` (was `StateFlow<StreamSessionState?>`), as specified.
New private constants: `OWNER = "RTMPStreamingViewModel"`, `SESSION_START_TIMEOUT_MS = 12_000L`.
Confirmed `RTMPStreamingScreen.kt` uses only those members (lines 47–52, 61, 255, 286, 288, 319–320).

---

## 3. TDD evidence

### RED — Step 5.0a

```
$ ./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesPhotoCapturerTest' 2>&1 | grep -E "^e: |BUILD"
e: .../services/QuickVisionService.kt:23:37 Unresolved reference 'StreamSession'.
e: .../services/QuickVisionService.kt:24:37 Unresolved reference 'startStreamSession'.
e: .../services/QuickVisionService.kt:26:43 Unresolved reference 'StreamSessionState'.
... (16 errors in QuickVisionService.kt)
e: .../ui/screens/RTMPStreamingScreen.kt:36:43 Unresolved reference 'StreamSessionState'.
e: .../ui/screens/RTMPStreamingScreen.kt:52:21 Property delegate must have a 'getValue(...)' method...
... (4 errors in RTMPStreamingScreen.kt)
e: .../viewmodels/RTMPStreamingViewModel.kt:12:37 Unresolved reference 'StreamSession'.
... (20 errors in RTMPStreamingViewModel.kt)
Execution failed for task ':app:compileDebugKotlin'.
BUILD FAILED in 2s
```

Exactly the expected shape: the failure is at `:app:compileDebugKotlin` on the three unmigrated files;
the test source set is never compiled, so `GlassesPhotoCapturerTest` could not run.

### GREEN — Step 5.9

```
$ ./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesSessionManagerTest' \
                                   --tests 'com.smartview.glassai.glasses.GlassesPhotoCapturerTest'
BUILD SUCCESSFUL in 6s
```

Also re-verified unfiltered and with `--rerun-tasks` (so nothing was UP-TO-DATE):

```
$ ./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 11s
```

JUnit XML totals (`app/build/test-results/testDebugUnitTest/`):

```
TEST-com.smartview.glassai.glasses.GlassesPhotoCapturerTest.xml   tests="5"  skipped="0" failures="0" errors="0"
TEST-com.smartview.glassai.glasses.GlassesSessionManagerTest.xml  tests="24" skipped="0" failures="0" errors="0"
totals: tests=29 skipped=0 failures=0 errors=0
```

**29 tests (24 + 5), 0 failures** — the post-ruling count. `<system-err>` is empty (`<![CDATA[]]>`) in both
result files; no `android.util.Log not mocked` problems (`unitTests.isReturnDefaultValues = true` was already
in `app/build.gradle.kts` from Task 1 and needed no change). The brief's `PhotoData.HEIC` fallback
(`ByteBuffer.allocate(0)`) was **not** needed — `javap` confirms `PhotoData$HEIC(java.nio.ByteBuffer)`, so
`PhotoData.HEIC(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))` compiles as written.

### Build — Step 5.8

```
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 52s
```

`app/build/outputs/apk/debug/app-universal-debug.apk` (99 MB) plus both ABI splits regenerated.

**Warnings: 24 total, 0 from any Task 5 file.** All 24 are pre-existing `Deprecated in Java` /
deprecated-Compose-icon warnings in `BluetoothAudioManager`, `RTMPStreamingService`, `ModeSettingsScreen`,
`QuickVisionScreen`, `RecordsScreen`, `Theme`, `APIKeyManager`, plus two long-standing ones in
`QuickVisionService` that I did not touch (`Locale(String, String)` at line 125 and the
`UtteranceProgressListener.onError` override at line 395). `grep '^w: ' | grep -E
'GlassesPhotoCapturer|RTMPStreamingViewModel|RTMPStreamingScreen'` returns nothing.

---

## 4. Files changed

| File | Change |
|------|--------|
| `android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt` | **new** (168 lines) |
| `android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` | **new** (5 tests, verbatim from the brief) |
| `android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` | migrated (imports, fields, `cleanup`, `captureAndAnalyze` + 2 new helpers, `getLocalizedString`) |
| `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` | rewritten in full |
| `android/app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt` | one import deleted |

Commit stat: `5 files changed, 589 insertions(+), 181 deletions(-)`.

Nothing else touched — `git status` after the commit is clean; `WearablesViewModel.kt`, `HomeScreen.kt`,
`QuickVisionScreen.kt`, the Task 3 manager/gateway/adapter and `app/build.gradle.kts` are untouched.

---

## 5. Deviations from the brief (and why)

1. **`GlassesPhotoCapturer.borrowCameraAndCapture()` retries `ensureSessionStarted` + `addCamera` once on
   `NoSession` / `SessionNotStarted`** — required by the binding review pointer in my task assignment
   (`WearablesViewModel.disconnect()` calls `sessionManager.stopSession()` unconditionally, so a disconnect
   racing a wake-word capture leaves this service's fresh claim pointing at a stopped session). The brief's
   Step 5.0b code returns `CameraUnavailable` immediately in that case. Implemented as a
   `for (attempt in 1..BORROW_ATTEMPTS)` loop (`BORROW_ATTEMPTS = 2`, private):

   ```kotlin
   var borrowed: GlassesCamera? = null
   for (attempt in 1..BORROW_ATTEMPTS) {
       when (sessionManager.ensureSessionStarted(sessionTimeoutMs)) {
           SessionStartResult.STARTED -> Unit
           SessionStartResult.CREATE_FAILED -> return PhotoCaptureOutcome.SessionFailed
           SessionStartResult.NOT_STARTED -> return PhotoCaptureOutcome.SessionTimeout
       }
       when (val result = sessionManager.addCamera(owner, config)) {
           is CameraResult.Ready -> { borrowed = result.camera; break }
           is CameraResult.Failed -> {
               val sessionGone = result.error is CameraError.NoSession ||
                   result.error is CameraError.SessionNotStarted
               if (!sessionGone || attempt == BORROW_ATTEMPTS) {
                   Log.e(TAG, "addCamera refused: ${result.error}")
                   return PhotoCaptureOutcome.CameraUnavailable(result.error)
               }
               Log.w(TAG, "addCamera refused (${result.error}); session went away, retrying once")
           }
       }
   }
   val camera = borrowed ?: return PhotoCaptureOutcome.CameraUnavailable(CameraError.NoSession)
   ```

   `CameraBusy` and `Sdk` are **not** retried, so `cameraHeldByAnotherOwnerIsReportedAsBusyAndClaimReleased`
   still sees exactly one `addCamera` call and one `ensureSessionStarted`. Everything else in the file is
   byte-for-byte the brief's code (`capture()`'s device wait, the `finally` with `stopCamera` + `release`,
   the `coroutineScope`/`frameJob` fallback collector, `captureFrom`).

2. **`FakeDat.kt` was not modified**, although the brief's `git add` lists it (the assignment also mentioned
   "the fakes extension"). Task 3's fakes already expose everything the 5 new tests need:
   `FakeDatSessionFactory.nextCaptureResult` / `.createCalls` / `.last`, `FakeGlassesSession.cameras` /
   `.addCameraCalls` / `.stopCalls` / `.emitStarted()`, `FakeGlassesCamera.stateFlow` / `.startCalls` /
   `.stopCalls` / `.captureResult`, `FakeDatDeviceObserver.device`. The file was still passed to `git add`
   (a no-op for an unchanged tracked file) so the brief's command ran verbatim.

3. **Import placement in `QuickVisionService.kt`** follows the brief literally (the four
   `com.smartview.glassai.glasses.*` imports sit at lines 27–30, i.e. before `com.smartview.glassai.MainActivity`
   instead of alphabetically after `...data.QuickVisionStorage`). No linter enforces import order here
   (no ktlint/detekt; `android.lint` has `abortOnError = false`) and it produces no warning.

4. The brief's Interfaces block did not disagree with the Task 3/4 code anywhere else — every consumed
   symbol (`acquire`/`release`/`ensureSessionStarted`/`addCamera`/`stopCamera`/`activeDevice`,
   `GlassesCamera.streamState|videoFrames|streamErrors|startStream|capturePhoto|stop`, `PhotoCaptureResult`,
   `CameraResult`, `CameraError`, `SessionStartResult`, `RTMPStreamingService.feedFrame`) exists with the
   documented signature. No further deviations recorded.

---

## 6. Self-review findings

- **Threading.** Every `GlassesSessionManager` call is on the main thread: `capture()` runs in the service's
  `Dispatchers.Main + SupervisorJob()` scope; `cleanup()` runs on the service's main thread;
  `RTMPStreamingViewModel` calls them from `viewModelScope` (Main.immediate) and from UI callbacks. The only
  off-main work in the capturer is `launch(frameDispatcher)` (`Dispatchers.Default` in the app), which touches
  the camera flow and `decodeFrame` only — never the manager.
- **`sessionError` (replay 0).** The capturer never subscribes to it, matching the brief; no
  subscribe-after-acquire hazard was introduced.
- **Claim release.** `capture()` releases in `finally`, so cancellation from `cleanup()` still gives the camera
  and the owner claim back; `cleanup()` repeats `stopCamera`/`release` as an idempotent safety net for a
  capture that never reached `acquire`. `RTMPStreamingViewModel` releases in `failCamera()`, `stopStreaming()`
  and (via `stopStreaming`) `onCleared()`. `release()` on a non-owner is a no-op (`owners.remove` returns
  false), and `stopCamera()` on a non-holder only logs.
- **Frame-buffer copy.** `RTMPStreamingViewModel.handleVideoFrame` takes the `ByteArray` copy as its first
  statement (`copyFrame`) and feeds both `rtmpService.feedFrame(ByteBuffer.wrap(i420), …)` and
  `updatePreview(i420, …)` from that copy, per spec §5.8. Compressed / codec-config frames are skipped before
  the copy in the collector.
- **Exhaustive `when` on `DatStreamState`** in `attachCamera` covers all seven enum entries
  (STARTING/STARTED/STREAMING/STOPPING/PAUSED/STOPPED/CLOSED) with no `else`, so a future SDK state addition
  becomes a compile error rather than a silent fall-through.
- **`hasBeenActive` guard** prevents the initial `STOPPED` emission of a freshly borrowed camera's StateFlow
  from immediately calling `stopStreaming()`.
- **Localization.** `"camera_busy"` is the only `getLocalizedString` key routed through `R.string`
  (`glasses_camera_busy` exists in `values/strings.xml:394`); the other keys keep their hard-coded
  system-locale strings, unchanged.
- **Test-count discipline.** I did not add tests beyond the brief's five, so the acceptance count is exactly 29.

---

## 7. Concerns / notes for later tasks

1. **`VideoFrame` *does* have a JVM-usable public constructor.** The brief (line 172) says the decoded-frame
   fallback branch "needs a real `VideoFrame`, which has no public constructor usable on the JVM", and defers
   `Captured(image, fromVideoFrame = true)` to the Task 9 instrumented test. `javap` on the cached 0.9.0 AAR
   shows `public VideoFrame(java.nio.ByteBuffer, int, int, long, boolean, boolean)` (plus the synthetic
   defaults overload), so a JVM test *could* drive the fallback branch by emitting a hand-built frame into
   `FakeGlassesCamera.frames`. I deliberately did **not** add it, because the acceptance criterion for this
   task is exactly 29 tests. Task 9 may keep its instrumented coverage, or a follow-up may add the cheap JVM
   test — the fallback branch (`decodePhoto` returns null / `capturePhoto` fails, then the first decoded frame
   wins) is currently only exercised on the failure side (`NoImage`).
2. **RTMP frame handling still runs on the main thread.** `videoJob` collects on `viewModelScope`
   (Dispatchers.Main.immediate) and `handleVideoFrame` does the buffer copy, the MediaCodec `feedFrame` and a
   full I420→NV21→JPEG→Bitmap preview conversion there, at up to 24 fps. This is the brief's specified code
   and is unchanged in character from the pre-migration ViewModel (which also collected on `viewModelScope`),
   so it is not a regression — but it is the obvious jank source on the RTMP screen if a later task wants to
   move decoding to `Dispatchers.Default` the way `GlassesPhotoCapturer` does.
3. **`RTMPStreamingScreen.kt:51` collects `cameraState` but never uses it** (`val cameraState by
   viewModel.cameraState.collectAsState()` has no other reference in the file). Pre-existing; the brief only
   removed the import, and I left the line alone. Whoever owns the RTMP screen later can either surface the
   state in the UI or drop the line.
4. **`QuickVisionService.cleanup()` is called from `onDestroy()` before `scope.cancel()`**, so
   `captureJob?.cancel()` runs first and the capturer's `finally` executes on the main thread. If a capture is
   cancelled *while suspended inside* `ensureSessionStarted`, the `finally` still runs (cancellation is
   cooperative for the `finally` block, and the manager calls are non-suspending), so no claim is leaked. This
   is worth an explicit instrumented check in Task 9 (kill the service mid-capture, assert `ownerCount == 0`).
5. **A `stopStreaming()` triggered from inside `stateJob`'s collector cancels its own job** (`cancelCameraJobs`
   cancels `stateJob`). The rest of `stopStreaming()` still completes because cancellation only takes effect at
   the next suspension point, so the camera and the claim are given back. Same shape as the pre-migration code;
   flagged only so a reviewer does not read it as a bug.

---

## Fix round 1

**Status: DONE**
**Commit: `ec2cd52`** — `fix(android): keep RTMP stream errors visible after teardown; retry capture once when the session disappears during start`
Branch `android-v2`, working tree clean after the commit.

### What changed

**Finding 1 — stream errors erased a moment after they are shown.**

- `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt:404-411` —
  `stopStreaming()`'s tail now sets `Idle` only when the current state is not already `Error`:
  ```kotlin
  if (_uiState.value !is UIState.Error) {
      _uiState.value = UIState.Idle
  }
  ```
  So the STOPPED transition that `attachCamera`'s `stateJob` drives after a stream error
  (`:225-228`, `hasBeenActive` branch → `stopStreaming()`) no longer overwrites the `Error` state
  the `streamErrors` collector just set (`:238`).
- `android/app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt:284-293` — the
  Stop button's `onClick` now calls `viewModel.clearError()` immediately before
  `viewModel.stopStreaming()`, so an explicit user-initiated stop still reaches `Idle` (clearing the
  error first means `stopStreaming()`'s guard above sees a non-`Error` state and proceeds to `Idle`
  normally). `startStreaming()`'s own guard (`_uiState.value == Streaming || Connecting`) and
  `clearError()`'s existing no-op-unless-`Error` behavior were not touched.

**Finding 2 — the retry closed only half the disconnect race.**

- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt:92-133`
  (`borrowCameraAndCapture`) — `SessionStartResult.NOT_STARTED` is no longer an immediate
  `SessionTimeout`. It now checks `!sessionManager.hasSession` (public property, already exposed by
  `GlassesSessionManager`) to distinguish two causes of `NOT_STARTED`:
  - **Session disappeared mid-wait** (`hasSession == false`): a concurrent `stopSession()` — e.g.
    `WearablesViewModel.disconnect()` — drove `sessionState` to `STOPPED` while `awaitStarted` was
    waiting, so it resolved on `STOPPED` instead of timing out. This is retried once, exactly like
    the existing `CameraError.NoSession` / `SessionNotStarted` retry branch, inside the same
    `BORROW_ATTEMPTS = 2` loop (now labeled `attempts@` so the `NOT_STARTED` branch can
    `continue@attempts`). The very next `ensureSessionStarted()` call creates a fresh session.
  - **Genuine timeout** (`hasSession == true`, the session is still there, just stuck before
    `STARTED`): returns `SessionTimeout` immediately, same as before — retrying against the same
    stuck session would not help and would need another full `sessionTimeoutMs` wait, which is
    exactly the behavior the existing `sessionThatNeverStartsTimesOutAndReleasesClaim` test pins
    down (see below).
  - `CameraError.CameraBusy` / `CameraError.Sdk` (from `addCamera`) remain non-retried, unchanged.
  - No overall timeout was added, per the controller ruling — `BORROW_ATTEMPTS` still bounds the
    total attempts at 2.

### TDD evidence for the new test

Added `retriesOnceWhenSessionDisappearsDuringStart` to
`android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` (lines
143-166). It scripts the race with `manager.stopSession()` (the exact call
`WearablesViewModel.disconnect()` makes) fired while the capturer's first `ensureSessionStarted()`
is suspended in `awaitStarted`: with `UnconfinedTestDispatcher`, calling `stopSession()` drives
`_sessionState` from `STARTING` → `STOPPING` → (via the outgoing session's synchronous `stop()` and
`clearStopping()`) → `STOPPED` synchronously, which resolves `awaitStarted` on `STOPPED` and
returns `NOT_STARTED` for attempt 1 with `hasSession == false`. The retry then creates a second
session synchronously in the same call; the test drives it to `STARTED` with `factory.last
.emitStarted()` and to `STREAMING` via the camera's `stateFlow`, then asserts the capture succeeds.

**RED** (`GlassesPhotoCapturer.kt` still returning `SessionTimeout` unconditionally on
`NOT_STARTED`):

```
$ ./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesPhotoCapturerTest' --rerun-tasks
GlassesPhotoCapturerTest > retriesOnceWhenSessionDisappearsDuringStart FAILED
    java.lang.AssertionError at GlassesPhotoCapturerTest.kt:144

6 tests completed, 1 failed
BUILD FAILED in 9s
```

JUnit XML: `java.lang.AssertionError: expected:<2> but was:<1>` at
`GlassesPhotoCapturerTest.kt:144` — i.e. `assertEquals(2, factory.createCalls)` right after
`manager.stopSession()`, exactly the missing-retry symptom (only the first session was ever
created).

**GREEN** (after the `borrowCameraAndCapture` fix):

```
$ ./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesPhotoCapturerTest' --rerun-tasks
BUILD SUCCESSFUL in 9s
```

`TEST-com.smartview.glassai.glasses.GlassesPhotoCapturerTest.xml`: `tests="6" skipped="0"
failures="0" errors="0"`. The other 5 tests are byte-for-byte unchanged and still pass, including
`sessionThatNeverStartsTimesOutAndReleasesClaim` (confirms the `hasSession` gate correctly does
*not* retry a session that is still present but stuck).

### Verification

**1. `GlassesPhotoCapturerTest` only — 6/6:**
```
$ ./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesPhotoCapturerTest' --rerun-tasks
BUILD SUCCESSFUL in 9s
```
`TEST-com.smartview.glassai.glasses.GlassesPhotoCapturerTest.xml`: `tests="6" skipped="0"
failures="0" errors="0"`.

**2. Full unit test suite — 30 tests, 0 failures, empty system-err:**
```
$ ./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 9s
```
Aggregated across every `TEST-*.xml` in `app/build/test-results/testDebugUnitTest/`:
`total 30 failures 0 errors 0`; no file has a non-empty `<system-err>`.
(`GlassesSessionManagerTest` = 24, `GlassesPhotoCapturerTest` = 6.)

**3. `assembleDebug` — clean build, no new warnings from touched files:**
```
$ ./gradlew :app:assembleDebug --rerun-tasks
BUILD SUCCESSFUL in 10s
```
`grep '^w: ' | grep -E 'GlassesPhotoCapturer|RTMPStreamingViewModel|RTMPStreamingScreen'` on the
full recompile log returns nothing. The same 24 pre-existing warnings from Task 5's original report
are still the only warnings (`BluetoothAudioManager`, `QuickVisionService` x2,
`RTMPStreamingService`, `ModeSettingsScreen` x2, `QuickVisionScreen` x3, `RecordsScreen`, `Theme`
x2, `APIKeyManager` x9).

**4. Commit:**
```
$ git commit -m "fix(android): keep RTMP stream errors visible after teardown; retry capture once when the session disappears during start"
[android-v2 ec2cd52] fix(android): keep RTMP stream errors visible after teardown; retry capture once when the session disappears during start
 4 files changed, 54 insertions(+), 4 deletions(-)
```
`git status` after the commit: working tree clean.

### Files changed

| File | Change |
|------|--------|
| `android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt` | `borrowCameraAndCapture()`: `NOT_STARTED` retried once when `!sessionManager.hasSession` |
| `android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` | +1 test: `retriesOnceWhenSessionDisappearsDuringStart` (6 total, other 5 unchanged) |
| `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` | `stopStreaming()`: guard the `Idle` tail against an already-`Error` state |
| `android/app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt` | Stop button `onClick`: `clearError()` before `stopStreaming()` |

### Concerns / notes

1. **The Stop button's `clearError()` call is currently unreachable in practice.** The button is
   `enabled = uiState !is UIState.Error` (`RTMPStreamingScreen.kt:297`, untouched), and its
   `onClick` only reaches the `stopStreaming()` branch when `isStreaming || isConnecting`, both of
   which are false whenever `uiState is Error`. So today a user cannot actually click Stop while an
   error is showing — they must tap "Dismiss" (`clearError()`) first, which already sets `Idle`
   directly without going through `stopStreaming()`. I added the defensive `clearError()` call
   anyway (per the ruling's suggested implementation) so that if the button's `enabled`/guard logic
   ever changes to allow stopping directly from an error state, it still resolves to `Idle` rather
   than getting stuck on the old error. This does not change any currently-observable UI behavior.
2. **`RTMPStreamingViewModel` has no unit test coverage** (it is an `AndroidViewModel`); the fix
   was verified by code inspection of the exact collector/guard interaction described in the
   finding, not by an automated test. The task's verification section only specifies the capturer's
   6th test, so no ViewModel test was added.
3. **`hasSession` as the retry signal is a manager-internal detail leaking into the capturer.** It's
   already public (used elsewhere, e.g. `WearablesViewModel`), so no new surface was added, but a
   future refactor of `GlassesSessionManager`'s stop/teardown bookkeeping should keep in mind that
   `GlassesPhotoCapturer` now depends on `hasSession` being `false` exactly when the session that
   `ensureSessionStarted` was waiting on has been torn down concurrently (as opposed to merely
   stuck).
