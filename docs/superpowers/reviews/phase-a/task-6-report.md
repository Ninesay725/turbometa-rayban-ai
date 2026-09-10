# Task 6 report — Frame pipeline off the main thread, `Paused` UI state, zh/en error mapping (+ addendum)

**Status:** DONE_WITH_CONCERNS
**Branch:** `android-v2`
**Commit:** `99e36ee` — `feat(android): decode frames off the main thread with frame dropping, Paused stream state, zh/en messages for every DAT error`
Working tree clean after the commit.

---

## 1. What was implemented

All ten brief steps (6.1 → 6.10) plus the three addendum steps (6.A1 → 6.A3), in order, using the
brief's code/strings verbatim.

| Step | File | Change |
|---|---|---|
| 6.1 | `android/app/src/main/res/values/strings.xml` | 36 new strings + section comment appended before `</resources>` (blank separator line, CRLF preserved) |
| 6.1 | `android/app/src/main/res/values-zh-rCN/strings.xml` | the same 36 keys with the brief's Simplified-Chinese text |
| 6.2 | `android/app/src/test/java/com/smartview/glassai/glasses/GlassesErrorMessagesTest.kt` | **new**, verbatim from the brief (5 tests) — RED first |
| 6.3 | `android/app/src/main/java/com/smartview/glassai/glasses/GlassesErrorMessages.kt` | **new** (88 lines), verbatim from the brief |
| 6.4 | — | GREEN: 5/5 |
| 6.5 | `.../viewmodels/WearablesViewModel.kt` | imports, 2 fields, background frame collector, `Paused` branch, 5 error sites, `cameraErrorMessage`, **+ `clearError()` pointer** |
| 6.6 | `.../viewmodels/RTMPStreamingViewModel.kt` | imports, 2 fields, background frame collector, 2 error sites, `cameraErrorMessage` |
| 6.7 | `.../ui/screens/LiveAIScreen.kt` | `getStatusText` `Paused` glyph |
| 6.8 | `.../ui/screens/SimpleLiveStreamScreen.kt` | `isPaused`, paused overlay, 4 imports |
| 6.A1 | `LiveAIScreen.kt`, `QuickVisionScreen.kt`, `SimpleLiveStreamScreen.kt`, `HomeScreen.kt` | the error-toast block, one per screen |
| 6.9 | — | `assembleDebug` + `testDebugUnitTest` (below) |
| 6.10 / 6.A3 | — | one commit with the brief's exact message, including the two addendum-only files |

### 1.1 The two binding review pointers

**Task 5 review pointer — the duplicated `cameraErrorMessage`.** The private `when` over `CameraError`
was deleted from **both** ViewModels and replaced with the shared mapping:

```kotlin
    private fun cameraErrorMessage(error: CameraError): String =
        GlassesErrorMessages.of(getApplication(), error)
```

(`WearablesViewModel.kt:495-496`, `RTMPStreamingViewModel.kt:283-284`.) `GlassesErrorMessages.resId(CameraError)`
now also routes `CameraError.Sdk` through `resId(DeviceSessionError)`, so `Sdk` errors get the app's own
zh/en text instead of the SDK's `getLocalizedDescription` — a behaviour improvement, not just deduplication.
`grep -n "is CameraError.CameraBusy" app/src/main` returns exactly one hit, in `GlassesErrorMessages.kt`.

**Task 4 review pointer — stale `errorMessage` across a later successful start.** `startStream()` now clears
the error in its synchronous "Reset state" block, before `startJob` is launched and therefore before
`sessionManager.acquire(OWNER)` (`WearablesViewModel.kt:326-331`):

```kotlin
        // Reset state. clearError() runs before acquire() so a stale message from an earlier
        // failed attempt cannot survive a later successful start; each failure path below sets
        // its own message afterwards.
        clearError()
        _currentFrame.value = null
        _streamState.value = StreamState.Waiting
```

No failure path clears the error: `CREATE_FAILED`, `NOT_STARTED`, `CameraResult.Failed` and the
`stream.start()` rejection all call `setError(message)` *after* this point, so their message survives.

### 1.2 Threading

Only the frame collectors moved. Every `GlassesSessionManager` / `GlassesCamera` call
(`acquire`, `release`, `ensureSessionStarted`, `addCamera`, `stopCamera`, `startStream`, `capturePhoto`,
`stopSession`) still runs on `viewModelScope`'s default `Dispatchers.Main.immediate`:

* `WearablesViewModel`: `startJob`, `streamStateJob`, `streamErrorJob`, `takePhoto`'s launch and the five
  `startMonitoring` collectors are all plain `viewModelScope.launch {}`; only `videoJob` takes
  `frameDispatcher`, and its body calls nothing but `handleVideoFrame` (buffer copy → I420→NV21→JPEG→Bitmap →
  `_currentFrame.value` → `onFrameReceived?.invoke`).
* `RTMPStreamingViewModel`: same shape; `videoJob`'s body calls `handleVideoFrame`, which touches only
  `rtmpService.feedFrame`, `updatePreview` and `connectRtmp()` — and `connectRtmp()` immediately re-enters
  `viewModelScope.launch {}` (Main) before touching anything else.
* The `sessionError` `SharedFlow` (replay 0) is still collected in `startMonitoring()` on Main, unchanged.

`onFrameReceived` is now invoked on a background thread; `grep -rn onFrameReceived app/src` shows the property
and its single invocation inside `WearablesViewModel` and no external assignment, so nothing observes it.

---

## 2. TDD evidence for `GlassesErrorMessagesTest`

### RED — Step 6.2

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesErrorMessagesTest' 2>&1 | grep -E "^e: |BUILD"
```

```
e: .../app/src/test/java/com/smartview/glassai/glasses/GlassesErrorMessagesTest.kt:24:46 Cannot infer type for type parameter 'R'. Specify it explicitly.
e: .../app/src/test/java/com/smartview/glassai/glasses/GlassesErrorMessagesTest.kt:24:52 Unresolved reference 'GlassesErrorMessages'.
e: .../GlassesErrorMessagesTest.kt:32:45 Unresolved reference 'GlassesErrorMessages'.
e: .../GlassesErrorMessagesTest.kt:40:51 Unresolved reference 'GlassesErrorMessages'.
e: .../GlassesErrorMessagesTest.kt:54:31 Unresolved reference 'GlassesErrorMessages'.
e: .../GlassesErrorMessagesTest.kt:62:20 Unresolved reference 'GlassesErrorMessages'.
e: .../GlassesErrorMessagesTest.kt:64:13 Unresolved reference 'GlassesErrorMessages'.
e: .../GlassesErrorMessagesTest.kt:65:13 Unresolved reference 'GlassesErrorMessages'.
BUILD FAILED in 7s
```

23 error lines in total: 8 x `Unresolved reference 'GlassesErrorMessages'` plus 15 knock-on
`Cannot infer type for type parameter` errors on the `map`/`forEach`/`toSet` calls that depend on them.
Exactly the brief's expected signature — every error names the **test** file, i.e. the main source set
compiled and the test source set was reached.

### GREEN — Step 6.4

```bash
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesErrorMessagesTest'
```

```
BUILD SUCCESSFUL in 2s
```

`TEST-com.smartview.glassai.glasses.GlassesErrorMessagesTest.xml`: `tests="5" skipped="0" failures="0" errors="0"`.
No `w:` lines from `GlassesErrorMessages.kt` — the `@Suppress("REDUNDANT_ELSE_IN_WHEN")` is doing its job
(K2 accepts that diagnostic name), and `Dispatchers.Default.limitedParallelism(1)` produced no
`ExperimentalCoroutinesApi` opt-in warning on kotlinx-coroutines 1.10.2, so no `@OptIn` was needed.

---

## 3. Verification (Step 6.9)

### 3.1 Unit tests — 35 tests, 0 failures, empty system-err

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 9s
```

| Result file | tests |
|---|---|
| `TEST-com.smartview.glassai.glasses.GlassesSessionManagerTest.xml` | `tests="24" skipped="0" failures="0" errors="0"` |
| `TEST-com.smartview.glassai.glasses.GlassesPhotoCapturerTest.xml` | `tests="6" skipped="0" failures="0" errors="0"` |
| `TEST-com.smartview.glassai.glasses.GlassesErrorMessagesTest.xml` | `tests="5" skipped="0" failures="0" errors="0"` |

**`total tests=35 skipped=0 failures=0 errors=0`; non-empty `<system-err>`: NONE** — 35 = 24 + 6 + 5, as the
assignment specifies (`--rerun-tasks`, so nothing was UP-TO-DATE).

### 3.2 `assembleDebug` — BUILD SUCCESSFUL, warning delta zero

```bash
./gradlew :app:assembleDebug --rerun-tasks
BUILD SUCCESSFUL in 10s
```

`grep -c '^e: '` = **0**. `grep -c '^w: '` = **24**, identical to the Task 5 baseline, distributed as:

```
     11 APIKeyManager.kt
      2 BluetoothAudioManager.kt
      2 ModeSettingsScreen.kt
      3 QuickVisionScreen.kt
      2 QuickVisionService.kt
      1 RTMPStreamingService.kt
      1 RecordsScreen.kt
      2 Theme.kt
```

`grep '^w: ' | grep -E 'GlassesErrorMessages|WearablesViewModel|RTMPStreamingViewModel|LiveAIScreen|SimpleLiveStreamScreen|HomeScreen'`
returns **nothing**. The 3 `QuickVisionScreen.kt` warnings are the pre-existing deprecated
`UtteranceProgressListener.onError` override (line 147) and the two deprecated `Icons.Filled.VolumeUp/VolumeDown`
icons (line 510) already listed in the Task 5 report; my only change to that file is the import + toast block
at lines 5 and 72-79.

Gradle runs were strictly serialised (never two at once); `android/local.properties` was never read or printed.

---

## 4. SDK symbol verification (`javap` on the cached 0.9.0 AARs)

Extracted `classes.jar` from
`~/.gradle/caches/modules-2/files-2.1/com.meta.wearable/mwdat-core/0.9.0/.../mwdat-core-0.9.0.aar` and
`.../mwdat-camera/0.9.0/.../mwdat-camera-0.9.0.aar`.

| Type | 0.9.0 constants (javap) | Brief's `when` | Match |
|---|---|---|---|
| `DeviceSessionError` | `CAPABILITY_DENIED, NO_ELIGIBLE_DEVICE, SESSION_ALREADY_STOPPED, SESSION_IDLE, CAPABILITY_ALREADY_ADDED, CAPABILITY_NOT_FOUND, DEVICE_DISCONNECTED, SESSION_ENDED_BY_DEVICE, SESSION_ALREADY_EXISTS, THERMAL_CRITICAL, THERMAL_EMERGENCY, PEAK_POWER_SHUTDOWN, BATTERY_CRITICAL, DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, DWA_UNAVAILABLE, UNEXPECTED_ERROR` (16) | 16 branches, same names | exact |
| `StreamError` | `STREAM_ERROR, CRITICAL_STREAM_ERROR, HINGE_CLOSED, PERMISSIONS_DENIED, THERMAL_HOT, BATTERY_LOW, PEAK_POWER_LIMIT, TIMEOUT` (8) | 8 branches, same names | exact |
| `RegistrationError` | `ALREADY_REGISTERED, ALREADY_UNREGISTERED, FAILED_TO_REGISTER, FAILED_TO_UNREGISTER, META_AI_NOT_INSTALLED, UNKNOWN` (6) | 6 branches, same names | exact |
| `CaptureError` | interface with four Kotlin objects (each has an `INSTANCE` field): `DeviceDisconnected, NotStreaming, CaptureInProgress, CaptureFailed` | 4 branches | exact |
| `StreamState` | `STARTING, STARTED, STREAMING, STOPPING, STOPPED, PAUSED, CLOSED` (7) | `attachCamera`'s `when` still covers all 7 with no `else` | ok |
| `CameraError` (app type, `GlassesSessionState.kt:30-39`) | `CameraBusy(String), NoSession, SessionNotStarted, Sdk(DeviceSessionError)` | 4 branches, no `else` | exact |

The constants the assignment warned about are confirmed **absent** in 0.9.0 —
`StreamError.THERMAL_EMERGENCY`, `DeviceSessionError.DEVICE_POWERED_OFF`, `DeviceSessionError.NOT_INITIALIZED`
and `RegistrationError.INCOMPATIBLE_SDK_LEVEL` appear in no javap output — and the brief's `when`s do not
mention them. Note that `DeviceSessionError.THERMAL_EMERGENCY` (distinct from the removed `StreamError` one)
**does** still exist in 0.9.0 and the brief correctly lists it.

**No enum branch was dropped or added.** Every `resId` `when` is exhaustive over the real 0.9.0 constant list,
which is why `assertFalse(ids.contains(R.string.dat_error_unknown))` passes for all three enums.

---

## 5. Files changed

```
 .../glassai/glasses/GlassesErrorMessages.kt        | 88 ++++++++++++++++++++++   (new)
 .../glassai/glasses/GlassesErrorMessagesTest.kt    | 68 +++++++++++++++++        (new, test)
 .../com/smartview/glassai/ui/screens/HomeScreen.kt |  8 ++
 .../smartview/glassai/ui/screens/LiveAIScreen.kt   | 11 +++
 .../glassai/ui/screens/QuickVisionScreen.kt        |  9 +++
 .../glassai/ui/screens/SimpleLiveStreamScreen.kt   | 37 +++++++++
 .../glassai/viewmodels/RTMPStreamingViewModel.kt   | 37 +++++----
 .../glassai/viewmodels/WearablesViewModel.kt       | 57 +++++++++-----
 android/app/src/main/res/values-zh-rCN/strings.xml | 38 ++++++++++
 android/app/src/main/res/values/strings.xml        | 38 ++++++++++
 10 files changed, 359 insertions(+), 32 deletions(-)
```

Nothing else was touched: `QuickVisionService.kt`, `GlassesPhotoCapturer.kt`, `GlassesSessionManager.kt`,
`DatGateway.kt`, `WearablesDatAdapter.kt`, `FakeDat.kt`, `RTMPStreamingScreen.kt`, `Navigation.kt`,
`MainActivity.kt` and every Gradle/manifest file are unchanged (`git status` after the commit is clean and
`git show --stat` lists exactly these ten).

CRLF was preserved in every file that already had it (`WearablesViewModel.kt`, all four screens, both
`strings.xml`); `RTMPStreamingViewModel.kt` and the two new files are LF, matching how Task 5 created its own
new files. `core.autocrlf=true`, so the committed blobs are LF-normalised either way.

---

## 6. Deviations from the brief

1. **`RTMPStreamingViewModel` field placement — one extra blank line.** The brief says to put the two new
   fields "next to `private var camera: GlassesCamera? = null`". In that file `camera` is immediately followed
   by the five `private var …Job: Job? = null` declarations under a shared `// Borrowed camera + jobs` comment,
   so inserting the fields there split the job block. I added a single blank line between
   `isProcessingFrame` and `private var startJob` so the job declarations stay a contiguous group.
   Whitespace only; no code difference.
2. **`WearablesViewModel.startStream()` gained a `clearError()` call** that the brief does not list — this is
   the Task 4 review pointer that binds this task (see §1.1), not a free choice.
3. **`GlassesErrorMessages` is reached at two sites the brief's replacement table does not enumerate**: the two
   `cameraErrorMessage` bodies (Steps 6.5f / 6.6d, which the brief *does* specify) now reach
   `resId(DeviceSessionError)` through `CameraError.Sdk`. That is the brief's own `resId(CameraError)` code;
   noted only because it changes `Sdk`-error text from the SDK's string to the app's.

**No enum constant was dropped or added** (see §4), so neither ambiguity-resolution rule was triggered.

---

## 7. Self-review

* **Completeness** — all 10 brief steps and all 3 addendum steps executed in order; both binding pointers
  applied; both artifacts (`GlassesErrorMessages.kt`, `GlassesErrorMessagesTest.kt`) exist and are committed.
* **Strings in both locales** —
  `diff <(grep -o 'name="[a-z_]*"' values/strings.xml | sort) <(… values-zh-rCN/strings.xml | sort)` →
  **KEY SETS IDENTICAL**. 36 new keys per locale (2 `stream_paused_*` + 34 `dat_*`), all with the brief's exact
  text; no key collided with an existing one; `mergeDebugResources`/`processDebugResources` succeeded in the
  full `--rerun-tasks` build, so both locales parse.
* **No manager calls off the main thread** — audited every `launch(frameDispatcher)` body (§1.2); neither
  reaches `sessionManager` or `GlassesCamera`. The `sessionError` `SharedFlow` (replay 0) collector is
  untouched and still subscribes on Main inside `startMonitoring()`.
* **Frame-buffer safety** — no `conflate()`/`buffer()` anywhere in either collector; `videoFrame.buffer` is
  read only inside `collect {}`, and both `handleVideoFrame` implementations copy the bytes as their first
  statement (`WearablesViewModel.kt:551-559`, `RTMPStreamingViewModel.kt:305` via `copyFrame`). Compressed and
  codec-config frames are still dropped before the `AtomicBoolean` is taken, so a skipped codec-config frame
  never occupies the guard.
* **Drop policy correctness** — `compareAndSet(false, true)` … `try/finally { set(false) }`; the flag is
  released even if `handleVideoFrame` throws or the coroutine is cancelled inside it, so a cancelled+restarted
  stream cannot deadlock the collector at `isProcessingFrame == true`. Since both collectors run on a
  single-threaded dispatcher the guard can only ever be contended by a re-entrant emission, which is exactly
  the "previous frame still converting" case the spec wants dropped.
* **`Paused` rendering** — `DatStreamState.PAUSED` is the only source of `StreamState.Paused`; the branch sets
  `hasBeenActive = true` and does **not** call `stopStream()`/`stopSession()`. `LiveAIScreen` renders
  `⏸ <stream_paused_subtitle>`, `SimpleLiveStreamScreen` renders the dimmed overlay over the retained last
  frame (the overlay is inserted after the `currentFrame?.let{}?:run{}` block and before the UI overlay, so
  the frame stays visible underneath). `attachCamera`'s `when` over `DatStreamState` still has no `else`, so a
  future SDK state is a compile error.
* **Toast idempotence** — `LaunchedEffect(wearablesErrorMessage)` re-runs only when the message value changes,
  and `clearError()` immediately drives it back to `null`; a recomposition with an unchanged key does not
  re-fire, so no toast loop.
* **No changes outside the task's files** — `git status --short` before the commit listed exactly the 8 brief
  files + the 2 addendum-only screens; `git show --stat` confirms 10.
* **Contract preserved** — the only signature change is `cameraErrorMessage` (private in both ViewModels).
  `StreamState.Paused` was already declared in Task 4, so no screen's `when` broke; the full `assembleDebug`
  compiles every screen.
* **YAGNI** — no extra helper, overload, string, dependency or test beyond the brief's five; the test count is
  exactly 35 as specified.
* **Commit** — brief's exact message text, on `android-v2`, one commit, no attribution lines.

---

## 8. Concerns

1. **`QuickVisionScreen`'s own failure branch now usually sees a cleared `errorMessage`.** Task 4 fix round 1
   made `QuickVisionScreen.kt:~198` read `wearablesViewModel.errorMessage.value ?: streamFailedText` when the
   stream wait fails. The addendum's toast `LaunchedEffect` fires on the recomposition triggered by that same
   emission and calls `clearError()`, so by the time the polling loop exits and reads `.value` the message is
   usually already `null` and the on-screen text falls back to the generic `stream_failed`. The specific
   localized error is still shown to the user — as a toast — so nothing is lost, but the two mechanisms now
   overlap and the screen's inline text is effectively back to the generic string. The controller ruled that
   the toast block goes in "exactly as written", so I did not change either side. If this matters, the fix is
   to have `QuickVisionScreen` capture `wearablesErrorMessage` into a local before the toast clears it.
2. **`startStream()`'s new `clearError()` also clears errors that did not come from streaming.** It runs on
   every start attempt, so a registration/session error raised by `startMonitoring()`'s collectors a moment
   earlier is wiped when the user immediately starts a stream. That is the intended "no stale error survives a
   successful start" behaviour, but it is broader than "the previous stream attempt's error".
3. **`rtmpService.feedFrame(ByteBuffer, …)` blocks up to 10 ms in `dequeueInputBuffer(10000)`** and now does so
   on the single-threaded `frameDispatcher` instead of the main thread. This is the point of the task
   (main-thread jank is gone), but note that parallelism 1 means an encoder stall also stalls the preview
   conversion for that frame — by design, since the `AtomicBoolean` drops the frames that arrive meanwhile.
4. **`videoWidth`/`videoHeight`/`frameTimestampBase` in `RTMPStreamingViewModel` are now written from the frame
   worker and reset from the main thread** (`stopStreaming()`), without synchronisation. They are plain
   `Int`/`Long` fields; the worst case is one frame computing a stale timestamp base immediately after a
   stop/start. Same hazard class as before the change (the fields were already touched from two coroutines),
   just on different threads now. Worth a `@Volatile` if a later task revisits this file.
5. **`WearablesViewModel.onFrameReceived` is now invoked on a background thread.** No caller assigns it today
   (`grep -rn onFrameReceived app/src` → only the declaration and the invocation), but a future caller that
   touches Compose/View state from it would crash. The property has no KDoc saying so; adding one was outside
   this task's file list.
6. **No runtime verification on hardware or emulator.** Everything here is compile-level plus the 35 JVM unit
   tests. In particular the addendum's Step 6.A2 manual check (Home → Quick Vision with no device: toast shows
   and does not repeat) and the `Paused` overlay (needs a real cap-touch tap on the glasses) have **not** been
   exercised; `StreamState.Paused` has no unit-test coverage because `WearablesViewModel` is an
   `AndroidViewModel` with no test double in this suite.
7. **`Dispatchers.Default.limitedParallelism(1)` is created per ViewModel instance.** Two ViewModels alive at
   once (Home + RTMP screen) each hold a separate parallelism-1 view over the shared `Dispatchers.Default`
   pool; they do not share a worker, which is what the brief's "exactly like the 0.9.0 sample" wording asks
   for. It is never closed — `limitedParallelism` views are not closeable and hold no dedicated thread, so
   there is nothing to leak.

---

## Fix round 1

**Scope:** the two Important defects from the task review. Nothing else was touched — `GlassesErrorMessages.kt`,
both `strings.xml`, `LiveAIScreen.kt`, `SimpleLiveStreamScreen.kt`, `HomeScreen.kt`, `WearablesViewModel.kt` and
`GlassesSessionManager.kt` are byte-identical to `99e36ee`.

### Finding 1 — Quick Vision's inline error text was permanently generic

`android/app/src/main/java/com/smartview/glassai/ui/screens/QuickVisionScreen.kt`

| Line | Change |
|---|---|
| `:74-78` | 5-line comment explaining why the ViewModel's `errorMessage` is unreadable from the polling loop |
| `:79` | **new** `var lastGlassesError by remember { mutableStateOf<String?>(null) }` |
| `:82` | **new** `lastGlassesError = message` — written *before* `Toast.makeText(...)` and `wearablesViewModel.clearError()` |
| `:176` | **new** `lastGlassesError = null` in `performQuickVision()`'s reset block (right after `errorMessage = null`), so an error from an earlier run is never reused |
| `:209` | `errorMessage = wearablesViewModel.errorMessage.value ?: streamFailedText` → `errorMessage = lastGlassesError ?: streamFailedText` |

The toast itself is unchanged (same key, same text, same `clearError()`, still fires once per distinct message).
The other three screens' toast blocks are untouched.

### Finding 2 — unsynchronized encoder feed vs. teardown

`android/app/src/main/java/com/smartview/glassai/services/RTMPStreamingService.kt`

| Line | Change |
|---|---|
| `:21-22` | **new** imports `java.util.concurrent.locks.ReentrantLock`, `kotlin.concurrent.withLock` |
| `:67-74` | comment + **new** `private val encoderLock = ReentrantLock(true)` |
| `:75` | `@Volatile` on `private var encoder: MediaCodec?` |
| `:83` | `@Volatile` on `private var isStreaming` |
| `:231` | `encoderJob` output loop: the whole `try { … } catch { … }` inside `while (isStreaming)` wrapped in `encoderLock.withLock { }` (guards `dequeueOutputBuffer` / `getOutputBuffer` / `releaseOutputBuffer` / `outputFormat`) |
| `:330` | `feedFrame(ByteArray, …)`: whole body — guard + `try` — wrapped in `encoderLock.withLock { }` |
| `:362` | `feedFrame(ByteBuffer, …)`: whole body wrapped in `encoderLock.withLock { }` (this is the overload the frame worker calls) |
| `:431` | `stopStreaming()`: `encoder?.stop()` / `encoder?.release()` / `encoder = null` (with their existing try/catch) wrapped in `encoderLock.withLock { }` |

`android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt:99-105` — `@Volatile`
on `videoWidth`, `videoHeight`, `frameTimestampBase` (the folded-in minor), with a one-line comment.

**Lock design (two sentences).** A single `ReentrantLock` guards every `MediaCodec` call in the service —
both `feedFrame` overloads (check-then-queue as one atomic region, so `isStreaming`/`encoder` cannot change
between the guard and `queueInputBuffer`), the `encoderJob` output loop's dequeue→process→release region, and
`stopStreaming()`'s `stop()`/`release()`/`= null` teardown — so `release()` can never land while another thread
is inside a codec call, while `@Volatile` on `encoder`/`isStreaming` makes the teardown's writes immediately
visible to the frame worker so it stops feeding at once. The lock is **fair** (`ReentrantLock(true)`) rather
than a plain `synchronized`, because the output loop re-acquires immediately after releasing and would spend
~100% of its time holding an unfair monitor (its `dequeueOutputBuffer(10000)` blocks up to 10 ms per
iteration), which could let it barge indefinitely ahead of a waiting `feedFrame()` or `stopStreaming()`;
reentrancy is preserved either way, which matters because `onConnectionFailedRtmp` → `stopStreaming()` may in
principle be delivered on the thread already inside `sendH264Data`.

No restructuring: no method was extracted, split, renamed or reordered; the diff is the four `withLock`
wrappers (plus re-indentation), three annotations, one field, two imports and comments.

### Verification

Git Bash, from `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android`, one Gradle run at a time,
`--rerun-tasks` so nothing was UP-TO-DATE. `android/local.properties` was never read or printed.

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 9s
```

| Result file | |
|---|---|
| `TEST-…GlassesSessionManagerTest.xml` | `tests="24" skipped="0" failures="0" errors="0"` |
| `TEST-…GlassesPhotoCapturerTest.xml` | `tests="6" skipped="0" failures="0" errors="0"` |
| `TEST-…GlassesErrorMessagesTest.xml` | `tests="5" skipped="0" failures="0" errors="0"` |

`total tests=35 skipped=0 failures=0 errors=0 ; non-empty system-err=0` (every `<system-err>` is an empty
`<![CDATA[]]>`).

```bash
./gradlew :app:assembleDebug --rerun-tasks
BUILD SUCCESSFUL in 10s
```

`grep -c '^e: '` = **0**. `grep -c '^w: '` = **24 before, 24 after** — identical count *and* identical
per-file distribution to the `99e36ee` baseline:

```
     11 APIKeyManager.kt          2 QuickVisionService.kt
      2 BluetoothAudioManager.kt  1 RTMPStreamingService.kt
      2 ModeSettingsScreen.kt     1 RecordsScreen.kt
      3 QuickVisionScreen.kt      2 Theme.kt
```

The one `RTMPStreamingService.kt` warning is the pre-existing
`COLOR_FormatYUV420Planar is deprecated` at `:202` (inside `initEncoder`, untouched); the three
`QuickVisionScreen.kt` warnings are the same pre-existing deprecations, now at `:154` and `:518` instead of
`:147`/`:510` because my block added 7 lines above them. **No new warning from any file I touched**, and
neither `RTMPStreamingViewModel.kt` nor the new `withLock`/`@Volatile` lines produce one.

### Concerns

1. **The output loop now holds `encoderLock` for most of its wall time.** `dequeueOutputBuffer(bufferInfo, 10000)`
   blocks up to 10 ms per iteration and the lock now spans it, so `feedFrame` can wait up to ~one output-loop
   iteration (≈10 ms) before it reaches its own `dequeueInputBuffer(10000)`. The fair lock bounds this to one
   iteration rather than leaving it open-ended, and the frame worker already drops frames that arrive while one
   is in flight, so the expected effect is a slightly higher drop rate under load rather than a stall — but the
   input and output codec queues are independent in `MediaCodec`, and this fix does serialise them. Only real
   RTMP hardware testing can quantify it; if it shows up, the narrower fix is to lock only the
   `dequeueOutputBuffer` call itself and re-check `encoder != null` after it.
2. **`stopStreaming()` can now block its caller for up to one output-loop iteration (~10 ms).** It is called from
   Main (`RTMPStreamingViewModel`), from `Dispatchers.IO` (`startStreaming`'s catch) and from the RTMP client's
   own thread (`onConnectionFailedRtmp`). ~10 ms on Main during a stop is well under a jank-visible block but it
   is a new (bounded) main-thread wait that did not exist before.
3. **No deadlock cycle found, but it rests on one assumption:** that `RtmpClient.sendVideo` — called from
   `sendH264Data` while the lock is held — does not block on a full queue that only the thread calling
   `stopStreaming()` can drain. In `com.github.pedroSG94.rtmp-rtsp-stream-client-java` the sender enqueues and
   discards when full rather than blocking, and `ReentrantLock` is reentrant if the failure callback is ever
   delivered synchronously on the same thread. Not verified against the actual artifact.
4. **The `lastGlassesError` snapshot assumes the toast effect runs before the polling loop's next tick.** It
   does — recomposition and `LaunchedEffect` run on Main within a frame, and the loop sleeps 100 ms between
   checks — but if the branch is ever reached without the effect having fired, the inline text falls back to the
   generic `stream_failed`, exactly as it does today. The controller's ruling specified
   `lastGlassesError ?: streamFailedText`, so I did not add `?: wearablesViewModel.errorMessage.value` as a
   second fallback.
5. **Still no runtime verification.** Both fixes are compile-level plus the 35 JVM unit tests; neither the
   Quick Vision failure path nor the encoder teardown race has automated coverage (`RTMPStreamingService` needs
   a real `MediaCodec`, `QuickVisionScreen` needs a Compose UI test — neither exists in this suite). Concerns
   2–7 from the original report are unchanged.
