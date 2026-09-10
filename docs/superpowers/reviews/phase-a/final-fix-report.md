# Phase A final-review fix wave — report

Branch `android-v2`, base HEAD `e3cd466`. One commit, all 8 items.
Items 1 / 2 / 3a / 6 were already in the working tree when this wave resumed (a prior implementer was
interrupted by a host restart); they are marked **inherited — verified** below: I read the diff,
checked it against the review's evidence, and re-ran the full verification over it.

All paths are relative to `android/app/src`.

---

## The 8 items

### 1. `teardownAfterDeviceStop()` stops the lent Camera — *inherited from the interrupted implementer, verified*

`main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt:361-370`

```kotlin
private fun teardownAfterDeviceStop() {
    displayAttacher.detach()
    runCatching { camera?.stop() }
        .onFailure { Log.w(TAG, "camera.stop() after device stop failed", it) }
    camera = null
    cameraOwner = null
```

`stop()` is called **before** the references are nulled, guarded with `runCatching` (stopping an
already-stopped capability must not take the manager down), and the stale "the SDK already stopped
every capability" KDoc was replaced with the real reason (the borrower's `stopCamera()` is a no-op
once `cameraOwner` is null, so the SDK `Camera` and its `MediaCodec` decoder would only be freed by
GC). Matches review Important #1 exactly.

**Evidence** — new unit test `deviceStoppingSessionStopsTheLentCamera`
(`test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt:345-357`) asserts
`lent.stopCalls == 1` and `currentCameraOwner == null` after `emitStoppedByDevice()`. Green (see
Verification). The pre-existing `deviceStoppingSessionClearsStateAndNextAcquireRecreates` only
checked `currentCameraOwner`, so this is genuinely new coverage.

### 2. Photo decoded off the main thread — *inherited, verified*

`main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt:175`

```kotlin
val image = withContext(frameDispatcher) { decodePhoto(result.photo) }
```

`capture()` is main-thread-only by contract and `QuickVisionService.scope` is `Dispatchers.Main`, so
a multi-MB HEIC `BitmapFactory.decodeByteArray` used to run on the UI thread. The class KDoc now says
"frames *and* the captured photo" are decoded on `frameDispatcher`. The `kotlinx.coroutines.withContext`
import was added. The JVM tests use `UnconfinedTestDispatcher`, so the 6 capturer tests are
unaffected — confirmed green.

### 3a. Service keeps a connection failure visible — *inherited, verified*

`main/java/com/smartview/glassai/services/RTMPStreamingService.kt:464-466`

```kotlin
if (_state.value !is StreamingState.Error) {
    _state.value = StreamingState.Idle
}
```

`onConnectionFailedRtmp()` sets `Error` and then calls `stopStreaming()` on the RTMP thread;
`StateFlow` conflates, so the Main collector used to see only `Idle`. This is the service half of
review Important #3.

### 3b. ViewModel no longer downgrades `UIState.Error` — **new in this wave**

`main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt:120-129`

```kotlin
is RTMPStreamingService.StreamingState.Idle -> {
    // Never downgrade a visible error: ...
    if (_uiState.value != UIState.Idle && _uiState.value !is UIState.Error) {
        _uiState.value = UIState.Idle
    }
}
```

**`startStreaming()` verified to clear a previous error already** (`:166-178`): the early-return
guard only covers `Streaming`/`Connecting`, so a run started from `Error` falls through to
`_uiState.value = UIState.Connecting`, which replaces the error before anything connects. No extra
`clearError()` call was needed; a comment was added at `:177` so the dependency is explicit and
survives future edits. The Stop button's `clearError()` path (`:433` — `stopStreaming()` already had
the same `!is UIState.Error` guard from Task 5) is unchanged.

Net effect of 3a + 3b: a wrong URL or stream key now shows "Connecting" then the RTMP error text and
*stays* there, instead of blinking back to Idle. Start and Stop both still reach Idle.

### 4. D1 + D2 — MockDeviceKit screen rehydrates and localizes

**D1** `debug/java/com/smartview/glassai/debug/MockDeviceKitViewModel.kt:56-74`

```kotlin
init {
    if (mockDeviceKit.isEnabled) {
        val existing = mockDeviceKit.pairedDevices.filterIsInstance<MockGlasses>().map(::infoFor)
        if (existing.isNotEmpty()) { _uiState.update { it.copy(pairedDevices = existing) }; Log.d(...) }
    }
}

private fun infoFor(device: MockGlasses) = MockDeviceInfo(
    device = device,
    deviceId = device.deviceIdentifier.identifier,
)
```

Member names confirmed by `javap` on the cached 0.9.0 artifact
(`~/.gradle/caches/.../mwdat-mockdevice-0.9.0.aar`), not from memory:

```
public final java.util.Collection<com.meta.wearable.dat.mockdevice.api.MockDevice> getPairedDevices();
public interface MockDevice { public abstract DeviceIdentifier getDeviceIdentifier(); ... }
public final class DeviceIdentifier { public final java.lang.String getIdentifier(); }
```

`pairGlasses()` (`:90`) now builds its `MockDeviceInfo` through the same `infoFor()`, so a
freshly-paired card and a rehydrated card carry the same identity. That replaced
`UUID.randomUUID().toString()`: a random UUID could not survive re-entry and told the tester nothing,
while the SDK's own `DeviceIdentifier` matches what Home shows as the active device.

**D2** `debug/java/com/smartview/glassai/debug/MockDeviceKitScreen.kt:214-219` — the card title is now
`stringResource(R.string.mock_device_name)`; `MockDeviceInfo.deviceName` (captured once at pair time
from the **Application** context) was deleted, and the ViewModel's `R` / `java.util.UUID` imports went
with it. The VM now keeps only the stable, non-localized `deviceId`.

**On-device evidence** (Pixel_5 API 31, debug APK from this build):

```
09-10 21:25:04.636 D MockDeviceKitViewModel: Paired mock Ray-Ban Meta 027891b763e08bfda996b0851604d412
09-10 21:25:06.751 D MockDeviceKitViewModel: Paired mock Ray-Ban Meta 97460f3af637f001be006230cd314b86
   -> screen header "2 paired", two controllable cards
[back to Settings, re-open MockDeviceKit]
09-10 21:25:27.823 D MockDeviceKitViewModel: Rehydrated 2 already-paired mock device(s)
   -> screen header still "2 paired", cards present  (before this fix: "0 paired", empty list)
```

The two identifiers are distinct, which also settles the only risk in using them as the UI list key
(`unpairDevice`/`execute` match on `deviceId`).

### 5. `CAMERA` moved to the debug manifest

- `main/AndroidManifest.xml` — the `uses-permission android.permission.CAMERA` and
  `uses-feature android.hardware.camera` lines (formerly `:12-14`) are gone.
- `debug/AndroidManifest.xml` — **new file**, only those two elements plus a comment explaining that
  the phone camera exists solely as a MockDeviceKit feed source. Confirmed not `.gitignore`d
  (`git check-ignore` exit 1).

**Evidence** — `build-tools/36.0.0/aapt dump permissions` on the universal APKs of this build:

```
=== RELEASE ===                                === DEBUG ===
package: com.smartview.glassai                 package: com.smartview.glassai
uses-permission: android.permission.INTERNET   uses-permission: android.permission.CAMERA
... (no CAMERA anywhere)                       uses-permission: android.permission.INTERNET
```

`aapt dump badging`: release has no camera `uses-feature`; debug has
`uses-feature-not-required: name='android.hardware.camera'`. Cross-checked live:
`adb shell pm grant com.smartview.glassai android.permission.CAMERA` succeeded on the debug APK
(the grant fails outright for a permission the package does not declare).

### 6. `startMonitoring()` device flow logs failures — *inherited, verified*

`main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt:138-146` — `.catch { Log.e(TAG,
"device flow failed", it) }` sits between `activeDeviceInfoFlow()` and `collect`, with a comment
naming the `SupervisorJob` swallow it prevents. The `kotlinx.coroutines.flow.catch` import was added.

### 7. Adapter resolves a metadata-less id to `null`

`main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt:96-99`

```kotlin
.combine(Wearables.devices) { id, devices -> id?.takeIf { it in devices } }
```

(`Wearables.devices` is `StateFlow<Set<DeviceIdentifier>>` — `javap`-checked — so `in` is a set
membership test.) On unpair / Bluetooth-off the selector can still hold an id whose metadata entry
is already gone; the flow now emits `null` ("no device") instead of falling into the
`unknownDevice(id)` branch, which produced the transient `Active device: <raw id> (UNKNOWN)` card
(D3 / review Minor #11). `unknownDevice()` itself is kept — it still covers the ordering case the
`flatMapLatest` comment describes (id present in `devices`, metadata entry not yet published).

### 8. Sealed `CaptureError` `when` loses its `else`

`main/java/com/smartview/glassai/glasses/GlassesErrorMessages.kt:58-63` — the
`else -> R.string.dat_error_unknown` line was removed, so the compiler now enforces exhaustiveness
for the sealed type. The object-level `@Suppress("REDUNDANT_ELSE_IN_WHEN")` stays: the three SDK
*enum* `when`s (`DeviceSessionError`, `StreamError`, `RegistrationError`) still keep their `else`
deliberately. The KDoc (`:11-19`) was corrected to say which types are enums and which are sealed
(`CameraError` was already `else`-free). `GlassesErrorMessagesTest` compiles and passes unchanged —
its hard-coded 4-case list is now merely redundant, not wrong, so I left it alone per the brief.

---

## Verification

All commands run from Git Bash in `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android`; never
two Gradle invocations at once; `local.properties` was never read or printed; no system-level
commands were run.

### Unit tests — `./gradlew :app:testDebugUnitTest`

```
BUILD SUCCESSFUL in 10s
```

Parsed from `app/build/test-results/testDebugUnitTest/*.xml`:

```
com.smartview.glassai.glasses.GlassesErrorMessagesTest:    tests=5  failures=0 errors=0
com.smartview.glassai.glasses.GlassesPhotoCapturerTest:    tests=6  failures=0 errors=0
com.smartview.glassai.glasses.GlassesSessionManagerTest:   tests=25 failures=0 errors=0
TOTAL tests=36 failures=0 errors=0 skipped=0
system-err non-empty: NONE
```

36 tests as expected (25 manager = 24 plus the new `deviceStoppingSessionStopsTheLentCamera`). The
new test was written by the prior implementer alongside the fix, so no RED-first run is claimed here.

One transient failure on the way there, worth recording: my first D2 edit removed
`MockDeviceInfo.deviceName`'s producer but not the field itself, and `:app:compileDebugKotlin` failed
with `MockDeviceKitViewModel.kt:74:9 No value passed for parameter 'deviceName'` — fixed by deleting
the field, after which the run above is the real result.

### Assemble — `./gradlew :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest`

```
> Task :app:compileDebugAndroidTestKotlin
> Task :app:assembleDebug
> Task :app:assembleRelease
BUILD SUCCESSFUL in 1m 3s
111 actionable tasks: 45 executed, 15 from cache, 51 up-to-date
```

`:app:compileDebugAndroidTestKotlin` green (the brief's third command, folded into the same
invocation so two Gradle runs never overlap). Warnings across the entire log, deduplicated — exactly
one, and it is pre-existing and untouched by this wave:

```
w: RTMPStreamingService.kt:206:54 'static field COLOR_FormatYUV420Planar: Int' is deprecated.
```

No warning from any file this wave touched. R8 minification of the release variant succeeded with
the `CaptureError` `when` now exhaustive.

### Instrumented — Pixel_5 (API 31, x86_64, google_apis), `emulator-5554`

Boot: the brief's headless command (`-avd Pixel_5 -no-window -no-audio -no-boot-anim`) **fails on
this host** — the emulator dies at startup with `[Vulkan Loader] ERROR: vkGetDeviceQueue: Invalid
device` and `exit code 127`, before adb ever sees a device. Adding `-gpu swiftshader_indirect` did
not help (same crash). Task 9's known-good command works, so that is what booted the device:

```bash
"C:/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_5 \
  -no-snapshot-load -no-boot-anim -camera-back virtualscene -camera-front emulated
# poll until: adb shell getprop sys.boot_completed == 1   (booted after ~25 s)
adb shell input keyevent 82
```

Then task-9-report.md's exact commands:

```bash
adb install -r -t app/build/outputs/apk/debug/app-universal-debug.apk               # Success
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk # Success
adb shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT       # ok
adb shell pm grant com.smartview.glassai android.permission.CAMERA                  # ok
adb shell am instrument -w -r -e class com.smartview.glassai.glasses.GlassesSessionManagerInstrumentedTest \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner
```

```
INSTRUMENTATION_RESULT: stream=

Time: 5.943

OK (6 tests)

INSTRUMENTATION_CODE: -1
```

**6/6 on the first run**; the known DAT SDK native race (`CHECK_EQ` in `VideoDecoder`) did **not**
reproduce, so no second run was needed. The emulator is **left running** as `emulator-5554`.

### APK permission check

Covered under item 5 above: the release universal APK declares no `android.permission.CAMERA` and no
camera `uses-feature`; the debug universal APK declares both, and `pm grant` of `CAMERA` succeeded
against the installed debug build.

---

## Concerns

1. **`-no-window` cannot boot an emulator on this host.** Both `-no-window` attempts died with a
   Vulkan/gfxstream crash (exit 127) before adb ever saw a device; the windowed Task 9 command worked
   first try. Anyone scripting this later should use the Task 9 command, or investigate the host's
   Vulkan ICD. Recorded here so the next wave does not lose the same ten minutes.
2. **D2 could not be exercised end-to-end through the in-app language switch, and the switch itself
   looks broken on API 31.** I set App Language to Chinese (both through the picker and by writing
   `language_prefs.xml` directly and restarting the process), and the Settings row correctly read the
   Chinese label — but every other label stayed English, on a fresh process too. `values-zh-rCN` *is*
   in the APK (`aapt dump --values resources` shows `mock_device_name` = the Chinese string, and the
   APK's locale list includes `zh-CN`), so the resources are fine;
   `AppCompatDelegate.setApplicationLocales()` is simply not taking effect on API 31 in this build.
   That is pre-existing and outside this wave's scope, but it means D2 is verified **structurally**
   (the name is no longer captured once at pair time from the Application context; it is resolved
   per-composition against `LocalConfiguration`) rather than observed flipping languages on screen.
   Worth a Phase B item: the app-language switch needs a look on API < 33.
3. **Rehydrated cards restart with power/don/fold toggles off.** `MockDevice` exposes no getters for
   those states (`javap`: only `powerOn/powerOff/don/doff` and `fold/unfold`), so a rehydrated card
   shows the device as off even if it is powered on and worn. Toggling still works — the SDK calls are
   idempotent — but the toggle positions are a guess until the tester touches them. Documented in the
   `init` comment. Carrying the states across navigation would need a process-scoped holder; out of
   scope here.
4. **`deviceId` semantics changed for debug tooling.** Cards now show the SDK `DeviceIdentifier`
   instead of a random UUID. Verified unique across two pairings on device, and it is what the SDK
   itself keys on, but anyone who had memorised the old UUID-shaped ids will see a different string.
5. **Review Important #4 (no JVM coverage for the two ViewModels or the adapter) is untouched**, as
   the brief scoped it out. Items 3b and 7 both landed in exactly that untested code, so they rest on
   reading plus the instrumented suite, not on unit tests. It remains the right first Phase B task.
