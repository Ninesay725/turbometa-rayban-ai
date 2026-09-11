# Task 1 report — Phase A follow-ups Phase B builds on

Branch `android-v2`, base HEAD `173262e`, commit **`32d6990`**
`refactor(android): FrameConversions, manager latestFrame/publishFrame, injectable WearablesViewModel with DatRegistrationGateway, resetForTests hook, JVM ViewModel tests`

Status: **DONE_WITH_CONCERNS** (one unavoidable deviation in `TestBitmaps`, plus two intentional
micro-behaviour changes in the frame consumers that the brief's own replacement code prescribes).

---

## 1. What was implemented

All 18 brief steps, in order, TDD (RED → implement → GREEN) for each of the three test groups.

1. **`glasses/FrameConversions.kt` (new)** — the single place that turns DAT 0.9.0 I420 frames and
   `PhotoData` into app images. Replaces three duplicated `convertI420toNV21` / YUV→JPEG→Bitmap
   copies that lived in `WearablesViewModel`, `RTMPStreamingViewModel` and `QuickVisionService`.
2. **`GlassesSessionManager`** — `latestFrame: StateFlow<Bitmap?>` published by the current camera
   owner via `publishFrame(owner, frame)` and cleared in `stopCamera()`, `stopSession()` and
   `teardownAfterDeviceStop()`; `cameraOwner` made `@Volatile` (publishFrame is the one method that
   may run off Main); `lastSessionError: StateFlow<DeviceSessionError?>` written before every
   `_sessionError.tryEmit()` (three sites); `@VisibleForTesting internal fun resetForTests()`.
3. **`DatGateway.kt` / `WearablesDatAdapter.kt`** — new `sealed class CameraPermissionCheck`,
   `interface DatRegistrationGateway`, and the real `class WearablesRegistrationGateway(context)`
   over the `Wearables` statics.
4. **`WearablesViewModel.kt`** — full replacement with the injectable `internal constructor` plus
   the `Application`-only public constructor; dead `onFrameReceived` / `onPhotoTaken` callbacks
   removed; the two hard-coded English strings localized; `CREATE_FAILED` now keeps the specific
   DAT reason via `lastSessionError`; frame publisher checks `isActive`; frames go through
   `FrameConversions` and into `sessionManager.publishFrame(OWNER, bitmap)`; `setError()` also
   emits on the new `errorEvents` SharedFlow; `OWNER` made public.
5. **`RTMPStreamingViewModel` / `QuickVisionService`** — their private conversion functions now
   delegate to `FrameConversions`; five now-unused `android.graphics` / `java.io` imports dropped
   from each.
6. **Strings** — `glasses_permission_check_failed` added to `values/strings.xml` and
   `values-zh-rCN/strings.xml`.
7. **Tests** — new `FrameConversionsTest` (4), 5 new `GlassesSessionManagerTest` cases, 2 new
   `GlassesPhotoCapturerTest` cases (which finally exercise the previously unused
   `FakeGlassesCamera.startError` / `.errors` surfaces, ledger T3), new `WearablesViewModelTest`
   (10), new helpers `TestBitmaps` and `FakeRegistrationGateway`, `FakeGlassesSession.nextStartError`.
8. **Instrumented tearDown** — `GlassesSessionManagerInstrumentedTest.tearDown()` now ends with
   `manager.resetForTests()`.

---

## 2. Exact public API produced

### `com.smartview.glassai.glasses.FrameConversions` (object)

```kotlin
const val PREVIEW_JPEG_QUALITY = 50
const val CAPTURE_JPEG_QUALITY = 85
fun copyI420(buffer: ByteBuffer): ByteArray
fun copyI420(frame: VideoFrame): ByteArray?
fun i420ToNv21(input: ByteArray, width: Int, height: Int): ByteArray
fun i420ToJpeg(i420: ByteArray, width: Int, height: Int, quality: Int): ByteArray
fun i420ToBitmap(i420: ByteArray, width: Int, height: Int, quality: Int): Bitmap?
fun frameToBitmap(frame: VideoFrame, quality: Int): Bitmap?
fun decodePhoto(photo: PhotoData): Bitmap?
```

### `GlassesSessionManager` (additions)

```kotlin
val latestFrame: StateFlow<Bitmap?>
val lastSessionError: StateFlow<DeviceSessionError?>
fun publishFrame(owner: String, frame: Bitmap)          // the only off-Main-safe method
@VisibleForTesting internal fun resetForTests()          // main thread only
```

### `com.smartview.glassai.glasses` (new types)

```kotlin
sealed class CameraPermissionCheck {
    object Granted : CameraPermissionCheck()
    object Denied : CameraPermissionCheck()
    data class Failed(val description: String) : CameraPermissionCheck()
}

interface DatRegistrationGateway {
    val registrationState: Flow<RegistrationState>
    val registrationErrors: Flow<RegistrationError>
    val devices: Flow<Set<DeviceIdentifier>>
    fun startRegistration(activity: Activity)
    fun startUnregistration(activity: Activity)
    fun openFirmwareUpdate(activity: Activity): String?
    fun openDATGlassesAppUpdate(activity: Activity): String?
    suspend fun checkCameraPermission(): CameraPermissionCheck
}

class WearablesRegistrationGateway(private val context: Context) : DatRegistrationGateway
```

### `WearablesViewModel`

```kotlin
class WearablesViewModel internal constructor(
    application: Application,
    private val sessionManager: GlassesSessionManager,
    private val registration: DatRegistrationGateway,
    private val strings: (Int) -> String,
    private val videoQuality: () -> VideoQuality,
    private val frameDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application)

    companion object {
        const val OWNER = "WearablesViewModel"
        fun videoQualityFromSetting(setting: String): VideoQuality
    }

    val errorEvents: SharedFlow<String>   // one-shot, extraBufferCapacity = 8
}
```

Test-side helpers (JVM):
`object TestBitmaps { fun stub(): Bitmap }`,
`class FakeRegistrationGateway : DatRegistrationGateway`,
`FakeGlassesSession.nextStartError: StreamError?`.

Every name in the brief's **Interfaces** block is produced verbatim.

---

## 3. TDD evidence

All commands run from Git Bash in `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android`.

### RED 1 — `FrameConversionsTest` before `FrameConversions.kt`

```
./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.FrameConversionsTest"
EXIT=1
e: .../FrameConversionsTest.kt:23:20 Unresolved reference 'FrameConversions'.
e: .../FrameConversionsTest.kt:35:9  Unresolved reference 'FrameConversions'.
e: .../FrameConversionsTest.kt:42:20 Unresolved reference 'FrameConversions'.
> Execution failed for task ':app:compileDebugUnitTestKotlin'.
BUILD FAILED in 4s
```

### GREEN 1

```
./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.FrameConversionsTest"
EXIT=0  BUILD SUCCESSFUL in 5s
TEST-...FrameConversionsTest.xml -> tests="4" skipped="0" failures="0" errors="0"
```

### RED 2 — manager tests before `latestFrame` / `publishFrame` / `resetForTests`

```
./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.GlassesSessionManagerTest"
EXIT=1
e: GlassesSessionManagerTest.kt:478:17 Unresolved reference 'publishFrame'.
e: GlassesSessionManagerTest.kt:480:35 Unresolved reference 'latestFrame'.
   ... (10 such lines) ...
e: GlassesSessionManagerTest.kt:530:17 Unresolved reference 'resetForTests'.
e: TestBitmaps.kt:12:21 Unresolved reference 'sun'.            <-- see Deviation D1
BUILD FAILED in 1s
```

### GREEN 2

```
./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.GlassesSessionManagerTest"
EXIT=0  BUILD SUCCESSFUL in 4s
TEST-...GlassesSessionManagerTest.xml -> tests="30" skipped="0" failures="0" errors="0"
```

### RED 3 — `WearablesViewModelTest` before the injectable constructor

```
./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.viewmodels.WearablesViewModelTest"
EXIT=1
e: WearablesViewModelTest.kt:74:9  No parameter with name 'sessionManager' found.
e: WearablesViewModelTest.kt:75:9  No parameter with name 'registration' found.
e: WearablesViewModelTest.kt:76:9  No parameter with name 'strings' found.
e: WearablesViewModelTest.kt:77:9  No parameter with name 'videoQuality' found.
e: WearablesViewModelTest.kt:78:9  No parameter with name 'frameDispatcher' found.
e: WearablesViewModelTest.kt:95:41 Cannot access 'val OWNER: String': it is private ...
e: WearablesViewModelTest.kt:225:16 Unresolved reference 'errorEvents'.
BUILD FAILED in 13s
```

### GREEN 3 — whole suite

```
./gradlew :app:testDebugUnitTest
EXIT=0  BUILD SUCCESSFUL in 7s
```

---

## 4. Build / test results

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL**, **57 tests, 0 failures, 0 errors, 0 skipped**, every class's `<system-err>` empty (`<![CDATA[]]>` only) |
| `./gradlew :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL in 1m 08s** |

Unit-test breakdown (brief minimum in brackets):

| Class | Tests |
|---|---|
| `glasses.FrameConversionsTest` | 4 (new, min 4) |
| `glasses.GlassesErrorMessagesTest` | 5 (unchanged) |
| `glasses.GlassesPhotoCapturerTest` | 8 (6 + 2 new) |
| `glasses.GlassesSessionManagerTest` | 30 (25 + 5 new) |
| `viewmodels.WearablesViewModelTest` | 10 (new, min 10) |
| **Total** | **57** (was 36) |

Warnings: 19 `w:` lines in the assemble run — all pre-existing and all in files this task did not
touch except `QuickVisionService.kt` lines 121/347 (the `Locale(String,String)` constructor and the
deprecated `TextToSpeech.onError` override, both unrelated to the frame code and present before this
task). **Zero warnings reference `FrameConversions.kt`, `GlassesSessionManager.kt`,
`WearablesViewModel.kt`, `RTMPStreamingViewModel.kt`, `DatGateway.kt`, `WearablesDatAdapter.kt`,
`TestBitmaps.kt` or `FakeRegistrationGateway.kt`.**

The instrumented suite was not executed (no glasses / emulator run required for this task), but it
compiles against the changed manager (`compileDebugAndroidTestKotlin` green), which is what the
brief asked for.

---

## 5. Files changed

Created:
- `android/app/src/main/java/com/smartview/glassai/glasses/FrameConversions.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/FrameConversionsTest.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/TestBitmaps.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/FakeRegistrationGateway.kt`
- `android/app/src/test/java/com/smartview/glassai/viewmodels/WearablesViewModelTest.kt`

Modified:
- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt`
- `android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt`
- `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt`
- `android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt`
- `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-zh-rCN/strings.xml`
- `android/app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt`
- `android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt`

17 files, +822 / −243.

---

## 6. Deviations

**D1 (required) — `TestBitmaps.stub()` reaches `sun.misc.Unsafe` reflectively.**
The brief's version names the type in source (`sun.misc.Unsafe::class.java`). That does not compile:
the unit-test compile classpath is the mockable `android.jar`, which has no `sun.misc` package, so
Kotlin reports `Unresolved reference 'sun'` (see RED 2 output above). Rewritten as a pure
`Class.forName("sun.misc.Unsafe")` + `getMethod("allocateInstance", Class::class.java)` lookup —
same runtime behaviour, same contract ("never call any method on the result"), and the reason is
documented in the file's KDoc. The five manager tests that consume it pass.

**D2 (brief-prescribed) — RTMP preview no longer blanks on a decode failure.**
Old `RTMPStreamingViewModel.updatePreview()` did `_previewFrame.value = bitmap` unconditionally, so
a `BitmapFactory.decodeByteArray` returning `null` cleared the preview. The brief's replacement is
`if (bitmap != null) _previewFrame.value = bitmap`, which keeps the last good frame instead. The
task context asked me to preserve observable behaviour where the three copies differ subtly, but the
brief's replacement code is explicit here, so I followed the brief and record the difference. Same
JPEG quality (50), same NV21 layout, same "log and drop" on exception.

**D3 (brief-prescribed) — `WearablesViewModel` frame path likewise.**
Old `handleVideoFrame()` assigned `_currentFrame.value = newBitmap` even when the decode returned
`null`; the new one is `... ?: return`, so a failed decode keeps the previous frame. Quality is
unchanged (50 = `PREVIEW_JPEG_QUALITY`). `QuickVisionService` is byte-for-byte equivalent
(quality 85 = `CAPTURE_JPEG_QUALITY`, identical `decodePhoto`).

**D4 (additive) — `i420ToNv21` now validates its input.**
The three old copies would have thrown `ArrayIndexOutOfBoundsException` on a short buffer; the shared
one throws `IllegalArgumentException` from `require(...)` (a brief-specified test asserts this). Both
are caught by the `catch (e: Exception)` in `i420ToBitmap` / `frameToBitmap`, so every app caller
still just logs and drops the frame — no observable change.

No Phase A names drifted; every other brief anchor matched the code exactly.

---

## 7. Self-review

- **Completeness** — all 18 steps done; every test named in the brief exists and passes; the
  strings landed in both locales; the instrumented `tearDown()` calls `resetForTests()`.
- **Interfaces block verbatim** — verified by grep against the produced sources (§2). Names,
  parameter names, parameter order, nullability and the `internal` / `const` / `@VisibleForTesting`
  modifiers all match.
- **No manager calls off Main** — the only manager call from a non-Main context in the new
  `WearablesViewModel` is `sessionManager.publishFrame(OWNER, bitmap)` inside
  `CoroutineScope.handleVideoFrame`, which runs on `frameDispatcher`. That is the sanctioned
  exception: `publishFrame` touches only a `MutableStateFlow` and the now-`@Volatile` `cameraOwner`.
  `takePhoto()`'s `withContext(frameDispatcher) { ... }` block contains no manager call.
  `GlassesPhotoCapturer` is unchanged in this respect.
- **Three frame consumers** — quality constants preserved per caller (50 / 50 / 85), NV21
  interleave order (V then U) identical, defensive `ByteBuffer` copy with position restore
  identical. Differences are only D2/D3/D4 above.
- **Strings in both locales** — `glasses_permission_check_failed` present in `values` and
  `values-zh-rCN` with the same `%1$s` placeholder; the `Denied` branch reuses the existing
  `camera_permission_denied`, which is already localized in both files.
- **Dead code removed** — `onFrameReceived` / `onPhotoTaken` had no callers anywhere in the app
  (verified by grep before deletion).

---

## 8. Concerns / notes for later tasks

1. **`resetForTests()` does not clear `lastSessionError`** (the brief's body does not list it).
   It is harmless today because `lastSessionError` is only read immediately after a `CREATE_FAILED`
   return, and it is always written on that same path. If a later task starts reading it
   speculatively, add `_lastSessionError.value = null` to the reset.
2. **`latestFrame` holds a strong reference to a `Bitmap`** until the camera stops. That is
   intentional (Task 4's `camera.snap` reads it), but it means one preview-sized bitmap stays
   retained for as long as the camera is lent out.
3. **`latestFrame` is written from the frame worker, `publishFrame`'s owner check is not atomic**
   with `stopCamera()` on Main. A frame decoded just before `stopCamera()` can win the race and
   re-populate `latestFrame` after it was cleared. `WearablesViewModel.handleVideoFrame` narrows the
   window with its `isActive` check (the job is cancelled by `cancelStreamJobs()` before
   `stopCamera()`), but Task 4 should treat a stale `latestFrame` as possible and, if it matters,
   pair the snap with an `activeDevice`/`sessionState` check.
4. **`WearablesViewModelTest` constructs a bare `android.app.Application()`** — fine under
   `isReturnDefaultValues`, but the ViewModel must never call anything on it in the injectable path
   (it does not: `getString` goes through the `strings` lambda). A future edit that calls
   `getApplication<Application>().something` would break these tests silently.
5. The double-lossy `camera.snap` trade-off while Live AI streams (preview JPEG q50, re-encoded by
   the snap) is documented in the `handleVideoFrame` KDoc, as the brief requires — worth confirming
   in Task 4's acceptance.
