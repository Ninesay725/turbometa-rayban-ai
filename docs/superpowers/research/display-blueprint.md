# Meta Ray-Ban Display support for TurboMeta Android — implementation blueprint

Target: `android/` app (`com.smartview.glassai`, Kotlin + Compose, currently DAT **0.4.0**) gains the `mwdat-display` capability (DAT **0.9.0**) so AI results are rendered on Meta Ray-Ban Display glasses while the camera keeps streaming.

Path abbreviations used in citations:

| Abbrev | Absolute path |
|---|---|
| `APP/` | `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/` |
| `ANDROID/` | `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/` |
| `IOS/` | `D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/` |
| `DA/` | `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/samples/DisplayAccess/` |
| `DA-SRC/` | `DA/app/src/main/java/com/meta/wearable/dat/externalsampleapps/displayaccess/` |
| `CA-SRC/` | `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/` |
| `CHANGELOG` | `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/CHANGELOG.md` |
| `AGENTS` | `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/AGENTS.md` |
| `SKILL` | `C:/Users/Lee_L/.claude/plugins/cache/mwdat-android-marketplace/mwdat-android/0.9.0/skills/display-access/SKILL.md` |
| `DOCS` | `C:/Users/Lee_L/AppData/Local/Temp/claude/D--Coding-Workspaces-Android-turbometa-rayban-ai/d61ceabb-428f-4332-b75a-ae4701539675/scratchpad/dat-llms-full.txt` (v0.9 docs export) |
| `REF` | Android API reference pages under `https://wearables.developer.meta.com/docs/reference/android/dat/latest/` (fetched live 2026-09-10) |

---

## 0. Executive summary

1. **Display is only available on DAT ≥ 0.7.0** (`CHANGELOG:67-77`). TurboMeta pins `mwdat = "0.4.0"` (`ANDROID/gradle/libs.versions.toml:4`). The Display work therefore starts with a mandatory **SDK migration 0.4.0 → 0.9.0** that rewrites the camera path (`Wearables.startStreamSession` was removed in 0.7.0, `CHANGELOG:103`; `addStream` removed in 0.9.0, `CHANGELOG:28`). Section 2.2 lists every call site.
2. The display model is: `Wearables.createSession(selector)` → `session.start()` → wait `DeviceSessionState.STARTED` → `session.addDisplay(DisplayConfiguration())` → wait `DisplayState.STARTED` → `display.sendContent { flexBox { ... } }`; each send replaces the whole 600×600 screen (`DOCS:1236-1289`, `DA-SRC/display/DisplayViewModel.kt:209-404`).
3. **Camera and Display can be attached to the same `DeviceSession`**: the only documented restriction is *one capability per type per session* (`REF DeviceSession`: `addCamera` "limited to one per session", `addDisplay` "limited to one per session"; `DeviceSessionError.CAPABILITY_ALREADY_ADDED` = "A capability of this type has already been added"). No doc, sample, or changelog says camera and display are mutually exclusive. But **only one session per device may exist** (`DOCS:72`), so TurboMeta must move to a single process-wide session owner shared by `WearablesViewModel` and `QuickVisionService` (today each creates its own `StreamSession`: `APP/viewmodels/WearablesViewModel.kt:284-288`, `APP/services/QuickVisionService.kt:224-228`). Bluetooth bandwidth contention between video frames and display payloads is undocumented and must be measured on hardware (Section 6).
4. **MockDeviceKit cannot simulate a display device**: `GlassesModel` = `RAYBAN_META, OAKLEY_META_HSTN, OAKLEY_META_VANGUARD, RAYBAN_META_OPTICS, META_GLASSES` and the reference explicitly says display glasses "are not included here — when display glasses mock support is added, a separate model enum and pairing method will be introduced" (`REF GlassesModel`). Display rendering can only be verified on real Meta Ray-Ban Display hardware (Section 7).
5. Hardware/software prerequisites for Display (Section 1): Meta Ray-Ban Display glasses, firmware **V125** for 0.9.0 (`DOCS:3590`), Meta AI app **V282** for 0.9.0 (`DOCS:3586`), Developer Mode, and the **Device Access Toolkit Wearables App installed on the glasses** via the Meta AI app (`DOCS:665-676`); Android transfer of that app fails silently if phone Wi-Fi is off (`DOCS:3562`).
6. Proposed design (Section 8): `GlassesSessionManager` (single `DeviceSession`, owns `Camera` and `Display`), `GlassesDisplayManager` (card model → `sendContent`, conflated/coalesced sends, pagination), a `DisplayCard` sealed model with five cards (Status/L0 menu, Live AI caption, Quick Vision result, LeanEat nutrition, OpenClaw reply), hook points in `OmniRealtimeViewModel`, `QuickVisionService`, `LeanEatViewModel`, plus settings/strings/UI additions.

---

## 1. Prerequisites specific to Display

### 1.1 Hardware / firmware / companion app

| Item | Requirement | Source |
|---|---|---|
| Glasses | **Meta Ray-Ban Display** — the only `DeviceType` with `isDisplayCapable == true` (`DeviceType.META_RAYBAN_DISPLAY`; "Only META_RAYBAN_DISPLAY devices include an integrated display") | `REF DeviceType`; `DOCS:656-657` |
| Glasses firmware | **V125** for SDK 0.9.0 (V125 also for 0.7.0/0.8.0; V21 for 0.4.0–0.6.0) | `DOCS:3582-3592` (0.9.0 table: "Meta Ray-Ban Display glasses \| V125") |
| Meta AI app (Android) | **V282** for SDK 0.9.0 (V275 for 0.8.0, V272 for 0.7.0) | `DOCS:3586`, `DOCS:3598`, `DOCS:3610` |
| Phone OS | Android 10+ (same as Meta AI app); Android Studio Flamingo+ | `DOCS:13-15` |
| Pairing | Glasses paired with the phone in the Meta AI app | `DOCS:656-657` |
| Input | Users interact through captouch gestures and EMG gestures on the **Meta Neural Band**; tap events are delivered as callbacks; **back gesture = two-finger tap on the temple** ends the display session | `DOCS:650`, `DOCS:1546`, `DOCS:1567-1569` |

### 1.2 "Device Access Toolkit app must be installed on the glasses" (DOCS:661-676)

Verbatim procedure from the Display overview:

> The Device Access Toolkit needs to be installed on your Meta Ray-Ban Display glasses for the display functionality to work.
> To install DAT:
> 1. Update Meta AI app to v272 or later.
> 2. Update your glasses firmware to v125 or later via the Meta AI app.
> 3. Put on your glasses. This is required before proceeding.
> 4. Enable Dev Mode in the Meta AI app: Go to Settings → App Info, then tap App Version 5 times. If Dev Mode is already enabled, Press the install button to install DAT on your glasses. On iOS, a Wi-Fi prompt should appear after a few seconds. App installation takes ~5–10 seconds. Please keep Meta AI app open during this time.

Known issues that affect this step (`DOCS:3558-3562`):
- Installation fails if glasses battery < 10% → charge above 10%.
- Installation "may fail intermittently while setting or resetting device connections" — no fix.
- **Android:** "Device Access Toolkit Wearables App transfer may fail silently if Wi-Fi is off" → enable phone Wi-Fi during install.
- With multiple devices connected, registering an app installs the DAT Wearables App on only one device; others need manual install from the Meta AI app (`DOCS:3571`).

Runtime: content is sent "over Bluetooth" (`DOCS:680`); no Wi-Fi/LAN is needed for `sendContent`. Remote `image(uri=...)`/`VideoSource.Url` are fetched by the glasses ("The wearable device fetches and plays the video from the given URL", `REF VideoSource.Url`) — whether that goes via the phone's connection is undocumented; prefer `image(bitmap=...)` for locally-produced content (Section 4.4).

The SDK surfaces a stale glasses-side app as `DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` ("Your glasses need an update. Go to App Connections page in Meta AI to update.") and an unreachable one as `DeviceSessionError.DWA_UNAVAILABLE` ("The DAT Wearables App on the glasses did not become reachable after session start.") (`REF DeviceSessionError`). Recovery UI: `Wearables.openDATGlassesAppUpdate(activity)` (`DA-SRC/wearables/WearablesViewModel.kt:92-96`).

### 1.3 Developer Mode & credentials

- Enable Developer Mode: Meta AI app → Settings → App Info → tap App version 5× → toggle Developer Mode (`DOCS:35-39`). Only one 3rd-party app can stay registered at a time in Developer Mode (`DOCS:1976`).
- In Developer Mode, `APPLICATION_ID`/`CLIENT_TOKEN` may be `0`/empty (`DOCS:375`). TurboMeta currently ships `APPLICATION_ID = "0"` and **no `CLIENT_TOKEN` meta-data** (`ANDROID/app/src/main/AndroidManifest.xml:33-36`); the samples always declare both (`DA/app/src/main/AndroidManifest.xml:26-31`). Add the token entry (Section 2.3).
- Distribution to testers requires firmware ≥ v125 and a Wearables Developer Center release channel (`DOCS:3568`, `DOCS:1972-1980`).

### 1.4 DAM / DWA

- 0.7.0 introduced the "Device Access Toolkit App Model (DAM)", opt-in via `com.meta.wearable.mwdat.DAM_ENABLED`, "required for the new Display capability" (`CHANGELOG:77`).
- 0.9.0: "DAM is now always enabled, so the `com.meta.wearable.mwdat.DAM_ENABLED` manifest metadata key is ignored" (`CHANGELOG:29`). `AGENTS:957` still says to set it — that line is stale for 0.9.0; the 0.9.0 DisplayAccess sample manifest does not declare it (`DA/app/src/main/AndroidManifest.xml`). Do not add it.
- "DWA" (DAT Wearables App) is the glasses-side app; the sample names its connection state `DwaConnectionState` (`DA-SRC/display/DisplayUIState.kt:14-19`).

---

## 2. Dependency, build, and manifest changes

### 2.1 Version catalog and Gradle

Current TurboMeta (`ANDROID/gradle/libs.versions.toml:2-5,24-26`; `ANDROID/app/build.gradle.kts:8-53,82-86`):
- `agp = 8.6.0`, `kotlin = 2.0.0`, `mwdat = 0.4.0`, `composeBom = 2024.12.01`, `compileSdk = 35`, `targetSdk = 34`, `minSdk = 31`, Java/Kotlin JVM target **1.8**.
- Libraries: `mwdat-core`, `mwdat-camera`, `mwdat-mockdevice` (mockdevice commented out in deps).

DisplayAccess 0.9.0 sample (`DA/gradle/libs.versions.toml:1-24`; `DA/app/build.gradle.kts:26-72`): `agp = 8.11.1`, `kotlin = 2.2.21`, `mwdat = 0.9.0`, `composeBom = 2026.05.01`, `compileSdk = 36`, `minSdk = 31`, JVM 17, deps `mwdat-core` + `mwdat-display`.

Required edits:

```toml
# ANDROID/gradle/libs.versions.toml
[versions]
mwdat = "0.9.0"          # was 0.4.0  (line 4)
# strongly recommended to match the 0.9.0 samples:
agp = "8.11.1"           # was 8.6.0
kotlin = "2.2.21"        # was 2.0.0  (0.9.0 AARs are built with Kotlin 2.2.x)
composeBom = "2026.05.01"

[libraries]
mwdat-display = { group = "com.meta.wearable", name = "mwdat-display", version.ref = "mwdat" }   # add (DA/gradle/libs.versions.toml:19)
```

```kotlin
// ANDROID/app/build.gradle.kts
android {
    compileSdk = 36                      // sample value (DA/app/build.gradle.kts:28)
    defaultConfig {
        manifestPlaceholders["mwdat_application_id"] =
            providers.gradleProperty("mwdat_application_id").orNull
                ?: localProperties.getProperty("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] =
            providers.gradleProperty("mwdat_client_token").orNull
                ?: localProperties.getProperty("mwdat_client_token", "0")
        // pattern copied from DA/app/build.gradle.kts:18-24,37-42 (needs `import java.util.Properties`)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }   // DA/app/build.gradle.kts:52-60 uses JVM 17
}
dependencies {
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)   // add
}
```

The Maven repository block already exists (`ANDROID/settings.gradle.kts:33-39`) and matches the sample (`DA/settings.gradle.kts:41-47`); it reads `github_token` from `local.properties`/`GITHUB_TOKEN`. `local.properties` already exists at `ANDROID/local.properties` (not read; contains credentials).

ProGuard: existing `-keep class com.meta.** { *; }` / `-dontwarn com.meta.**` (`ANDROID/app/proguard-rules.pro:5-9`) already covers `mwdat-display`.

### 2.2 Mandatory API migration 0.4.0 → 0.9.0 (blocking for Display)

Every DAT call in TurboMeta and its 0.9.0 replacement:

| File:line (current) | 0.4.0 API | 0.9.0 replacement | Source |
|---|---|---|---|
| `APP/viewmodels/WearablesViewModel.kt:12-15` imports `StreamSession`, `startStreamSession`, `StreamSessionState` | removed | `com.meta.wearable.dat.camera.Camera`, `Stream`, `addCamera`, `types.StreamState`; `com.meta.wearable.dat.core.session.DeviceSession`, `DeviceSessionState` | `CHANGELOG:12,28,97-103`; `CA-SRC/camera/CameraViewModel.kt:30-41` |
| `WearablesViewModel.kt:74` `RegistrationState.Unavailable()`; `:148-163` `is RegistrationState.Registered` etc. | sealed class | plain enum `RegistrationState.UNAVAILABLE/AVAILABLE/REGISTERING/UNREGISTERING/REGISTERED`; errors now on `Wearables.registrationErrorStream: Flow<RegistrationError>` | `CHANGELOG:105,81`; `DA-SRC/wearables/WearablesRepository.kt:36-38,56-62` |
| `WearablesViewModel.kt:126` `deviceSelector.activeDevice(Wearables.devices)` | | `deviceSelector.activeDeviceFlow()` (and `activeDevice()` returns `DeviceIdentifier` directly) | `CHANGELOG:139,49`; `CA-SRC/wearables/WearablesViewModel.kt:57-61` |
| `WearablesViewModel.kt:192,197` `Wearables.startRegistration(getApplication())` | | takes `Activity` (since 0.4.0, `CHANGELOG:191`); pass the Activity from UI like `DA-SRC/wearables/WearablesViewModel.kt:70-84` | |
| `WearablesViewModel.kt:284-288` `Wearables.startStreamSession(ctx, selector, StreamConfiguration(videoQuality, 24))` | removed | `Wearables.createSession(selector).onSuccess { session.start() }` → on `STARTED`: `session.addCamera(StreamConfiguration(videoQuality = q, frameRate = 24)).onSuccess { camera -> camera.stream.start() }` | `CA-SRC/camera/CameraViewModel.kt:156-171,270-305` |
| `WearablesViewModel.kt:295` `session.videoStream`; `:303` `session.state` | | `camera.stream.videoStream`; `camera.stream.state: StateFlow<StreamState>`; `camera.stream.errorStream` | `CA-SRC/camera/CameraViewModel.kt:315-340` |
| `WearablesViewModel.kt:307-330` `StreamSessionState.STREAMING/STOPPED/STARTING` | | `StreamState.STOPPED → STARTING → STARTED → STREAMING → (PAUSED) → STOPPING → STOPPED → CLOSED` | `DOCS:586`; skill camera-streaming §"Observe stream state" |
| `WearablesViewModel.kt:353` `streamSession?.close()` | | `camera.stop()` (detaches; required before the next `addCamera`, else "capability already active") then `session.stop()` when the device interaction ends | `CA-SRC/camera/CameraViewModel.kt:308-313,400-420` |
| `WearablesViewModel.kt:382` `streamSession?.capturePhoto()` | | `camera.stream.capturePhoto()`; `PhotoData.Bitmap`/`PhotoData.HEIC` unchanged | `CA-SRC/camera/CameraViewModel.kt:433-460,537-541` |
| `WearablesViewModel.kt:411-441` I420→NV21 conversion of `videoFrame.buffer` | still valid | `StreamConfiguration.compressVideo` defaults to false → uncompressed frames; `VideoFrame.isCompressed`/`isCodecConfig` exist if HEVC is chosen later | `CHANGELOG:122,92` |
| `APP/services/QuickVisionService.kt:23-30,91-92,210,224-228,235-249,264` | same removed APIs | must use the shared session (Section 8.2) — a second `createSession` on the same device is invalid (`DeviceSessionError.SESSION_ALREADY_EXISTS`, "Only one session can run on a device at a time" `DOCS:72`) | `REF DeviceSessionError` |
| `APP/MainActivity.kt:123` `Wearables.initialize(this)` | | now returns `DatResult<Unit, WearablesError>`; check `.onFailure` | `REF Wearables` |
| `APP/MainActivity.kt:60-66` `Wearables.RequestPermissionContract()` returning `DatResult<PermissionStatus, PermissionError>` | unchanged | unchanged (`REF Wearables` inner class) | |
| `APP/viewmodels/RTMPStreamingViewModel.kt`, `services/RTMPStreamingService.kt` | grep for `startStreamSession` before migrating (not read in this pass) | same replacement | |

New 0.9.0 core APIs the Display feature relies on: `Wearables.openFirmwareUpdate(activity): DatResult<Unit, NavigationError>`, `Wearables.openDATGlassesAppUpdate(activity): DatResult<Unit, NavigationError>`, `Wearables.devicesMetadata: Map<DeviceIdentifier, StateFlow<Device>>` (indexed `[id]?.collect`), `Device.isDisplayCapable()`, `Device.compatibility: DeviceCompatibility` (`DEVICE_UPDATE_REQUIRED` used by the sample), `Device.linkState: LinkState` (`CONNECTING/CONNECTED/DISCONNECTED`), `DeviceSession.errors: SharedFlow<DeviceSessionError>` (`REF Wearables`, `REF Device`, `CHANGELOG:78-90,161`).

### 2.3 Manifest

Current `ANDROID/app/src/main/AndroidManifest.xml:5-21` already declares `INTERNET`, `BLUETOOTH`, `BLUETOOTH_CONNECT` (plus audio/FGS permissions); the deep-link scheme `turbometa` is declared (`:48-54`). Changes:

```xml
<!-- add next to APPLICATION_ID (AndroidManifest.xml:33-36); samples always declare both -->
<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="${mwdat_application_id}" />
<meta-data android:name="com.meta.wearable.mwdat.CLIENT_TOKEN"   android:value="${mwdat_client_token}" />
```

No additional permission is needed for Display (the DisplayAccess sample requests only `BLUETOOTH`, `BLUETOOTH_CONNECT`, `INTERNET`: `DA/app/src/main/AndroidManifest.xml:11-13`, `DA-SRC/MainActivity.kt:28`). Optional: `<meta-data android:name="com.meta.wearable.mwdat.CRASH_REPORTING_OPT_OUT" android:value="true"/>` disables SDK crash reporting (`CHANGELOG:16`). Do **not** add `DAM_ENABLED` (Section 1.4).

### 2.4 Imports for display code

```kotlin
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration   // package per REF; sample omits the arg
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.types.DisplayError
import com.meta.wearable.dat.display.types.VideoCodec
import com.meta.wearable.dat.display.types.VideoPlayerState
import com.meta.wearable.dat.display.types.VideoPlayerError
import com.meta.wearable.dat.display.types.VideoSource
import com.meta.wearable.dat.display.views.*   // Alignment, ButtonGroupAlignment, ButtonStyle, ContentScope, CornerRadius,
                                              // Direction, FlexBoxBackground, FlexBoxScope, IconName, IconStyle, ImageSize,
                                              // TextColor, TextStyle, VideoPlayer
```
(`DA-SRC/display/DisplayViewModel.kt:25-43`, `DOCS:1227-1232`.) Note `com.meta.wearable.dat.display.views.Alignment` clashes with `androidx.compose.ui.Alignment` — keep display builders in non-Compose files or alias the import.

---

## 3. Session → Display flow (exact sequence from the sample)

### 3.1 Step list

1. **Runtime permissions → initialize → observe.** `MainActivity.onStart` launches `RequestMultiplePermissions(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET)`; on all granted: `Wearables.initialize(this)` then `wearablesViewModel.startObserving()` (`DA-SRC/MainActivity.kt:27-51`). The repository collects `Wearables.registrationState`, `Wearables.registrationErrorStream` (logs `error.getLocalizedDescription(context)`), and `Wearables.devices` (`DA-SRC/wearables/WearablesRepository.kt:51-66`).
2. **Registration.** `Wearables.startRegistration(activity)` when not `REGISTERED`; `startUnregistration(activity)` to disconnect (`WearablesRepository.kt:89-103`). Wait for `RegistrationState.REGISTERED` before creating a session (`SKILL:51`).
3. **Device list with metadata.** For every id in `Wearables.devices`, launch `Wearables.devicesMetadata[id]?.collect { device -> ... }`, remove entries for ids that disappeared (`WearablesRepository.kt:68-87`). UI row shows `device.name`, `device.deviceType.description`, status; row is clickable only when `device.linkState == LinkState.CONNECTED && device.isDisplayCapable()` (`DA-SRC/ui/ConnectScreen.kt:382-407`). `isFirmwareUpdateRequired = any device.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED` (`DA-SRC/wearables/WearablesViewModel.kt:55-67`).
4. **Select device → `prepareDisplayConnection(deviceId)`** (`DA-SRC/display/DisplayViewModel.kt:289-317`): if the display is already attached for that id → "Display ready"; if a session is active for that id → `attachDisplay()`; otherwise set `pendingDeviceId` and `startSession(deviceId)`. Selecting a different device first tears everything down (`resetConnectionForNewDevice`, `:458-474`).
5. **Create + start session** (`DisplayViewModel.kt:209-287`): `Wearables.createSession(SpecificDeviceSelector(deviceId)).fold(onSuccess = { session -> observe session.state; observe session.errors; session.start() }, onFailure = { error, _ -> ... })`. Session state handling:
   - `DeviceSessionState.STARTED` → `connectionState = CONNECTED, isSessionActive = true`; if `consumePendingDeviceId(deviceId)` → `attachDisplay()` (`:229-243`, `:682-690`).
   - `DeviceSessionState.STOPPED` → `cleanupDisplay()`; reset flags; `selectedDeviceId = null` (`:244-257`).
   - other states ignored (`:258`).
   `createSession` failure: `isDatAppUpdateRequired = (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED)` (`:272-285`).
6. **Attach display** (`attachDisplay`, `:319-382`): `session.addDisplay().fold(onSuccess = { display -> observe display.state }, onFailure = { error, _ -> "Failed: ${error.description}" })`. The sample calls `addDisplay()` with the default; the docs form is `session.addDisplay(DisplayConfiguration())` (`DOCS:1248`). Signature: `fun DeviceSession.addDisplay(config: DisplayConfiguration = DisplayConfiguration()): DatResult<Display, DeviceSessionError>` (`REF DeviceSession`). `DisplayConfiguration()` has no parameters — "Currently empty — reserved for future display configuration options (e.g., default duration, content scaling)" (`REF DisplayConfiguration`).
7. **Display state** (`:343-371`): `isPreparingDisplay = state != STARTED && state != STOPPED`; `STARTED` → "Display ready" (enable user content); `STOPPED` after having started → "Display session stopped". `DisplayState` constants: `STARTING`, `STARTED` ("ready to receive content via sendContent"), `STOPPING`, `STOPPED` ("either because it hasn't been started or there are no active devices connected"), `CLOSED` ("terminal state and cannot be restarted") (`REF DisplayState`). Transitions: `STOPPED → STARTING → STARTED` on connection; `STARTED → STOPPING → STOPPED` on disconnect; any → `STOPPING → STOPPED → CLOSED` on `stop()/close()` (`REF Display.state`).
8. **Send content** (`sendContent`, `:386-404`): runs on `Dispatchers.IO`; `display.sendContent(content)` returns `DatResult<Boolean, DisplayError>`; `onFailure` → "Send failed: ${error.description}". The whole ViewModel is annotated `@SuppressLint("AutoCloseableUse", "NavigatorCoroutineLaunchWithoutExceptionHandler")` (`:55-58`) because `Display` is `Closeable`.
9. **Stop / cleanup** (`stopSession`, `:433-456`; `detachDisplay`, `:421-431`; `cleanupDisplay`, `:476-481`): cancel the video job → `session.removeDisplay()` → cancel display-state job → `display = null` → cancel session state/error jobs → `session.stop()` → `session = null`. `onCleared()` calls `stopSession()` (`:677-680`). Docs order: `session.removeDisplay(); session.stop()` (`DOCS:1287-1289`).

### 3.2 Error handling matrix

| Where | Error | Sample reaction | Recovery action |
|---|---|---|---|
| `createSession` failure or `session.errors` emission | `DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` | set `isDatAppUpdateRequired = true`, show snackbar `error.description` (`DisplayViewModel.kt:280-284`, `483-496`); ConnectScreen shows `UpdateActionsCard` with "Update app on glasses" (`ConnectScreen.kt:113-121,196-208`) | `Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ -> toast(error.description) }` (`DA-SRC/wearables/WearablesViewModel.kt:92-96`) |
| device metadata | `DeviceCompatibility.DEVICE_UPDATE_REQUIRED` | `isFirmwareUpdateRequired`; row status "Update required" (`ConnectScreen.kt:385-388`) | `Wearables.openFirmwareUpdate(activity)` (`WearablesViewModel.kt:86-90`) |
| `session.errors` (any) | `NO_ELIGIBLE_DEVICE`, `DEVICE_DISCONNECTED`, `SESSION_ENDED_BY_DEVICE`, `SESSION_ALREADY_EXISTS`, `THERMAL_CRITICAL/EMERGENCY`, `PEAK_POWER_SHUTDOWN`, `BATTERY_CRITICAL`, `DWA_UNAVAILABLE`, `CAPABILITY_DENIED`, `SESSION_IDLE`, `SESSION_ALREADY_STOPPED`, `CAPABILITY_ALREADY_ADDED`, `CAPABILITY_NOT_FOUND`, `UNEXPECTED_ERROR` (`REF DeviceSessionError`) | `handleSessionError` resets to DISCONNECTED and shows `error.description` (`:483-496`) | treat as terminal; wait for user/device availability; recreate session ("Do not reuse terminally stopped sessions", dat-conventions skill) |
| `addDisplay` failure | `DeviceSessionError` (e.g. `SESSION_IDLE` before STARTED, `CAPABILITY_ALREADY_ADDED`) | snackbar (`:373-380`) | only call after `STARTED`; `removeDisplay()` before re-adding ("One display per session", `DOCS:733`) |
| `sendContent` failure | `DisplayError.DEVICE_DISCONNECTED`, `INVALID_SESSION_STATE` ("not in the required state"), `RENDERING_FAILED`, `UNEXPECTED_ERROR` (`REF DisplayError`; `CAPABILITY_DENIED` was removed in 0.9.0, `CHANGELOG:32`, though `DOCS:1630` still lists it) | snackbar (`:398-401`) | `INVALID_SESSION_STATE` → wait for `DisplayState.STARTED` and resend last card |
| glasses back gesture at L0 | ends the display session (`DOCS:736`, `1569`) | observe `DisplayState.STOPPED` / `DeviceSessionState.STOPPED` / `SESSION_ENDED_BY_DEVICE` | reflect in phone UI; do not auto-restart |
| display sleep | dims at 20 s, sleeps at 25 s; session **not** ended (`DOCS:1291`) | — | resend fresh content on next event |

---

## 4. Content model (Display Views DSL)

### 4.1 Root: `ContentScope`

`Display.sendContent(content: ContentScope.() -> Unit): DatResult<Boolean, DisplayError>` (suspend) (`REF Display`). `ContentScope` exposes exactly two builders (`REF ContentScope`):

```kotlin
fun flexBox(
    direction: Direction = Direction.COLUMN, gap: Int = 0,
    alignment: Alignment = Alignment.START, crossAlignment: Alignment = Alignment.START,
    wrap: Boolean = false,
    padding: Int = 0, paddingTop: Int? = null, paddingBottom: Int? = null, paddingStart: Int? = null, paddingEnd: Int? = null,
    background: FlexBoxBackground = FlexBoxBackground.NONE,
    onClick: (() -> Unit)? = null,
    block: FlexBoxScope.() -> Unit)

fun video(player: VideoPlayer, block: VideoScope.() -> Unit = {})
```

Rules: exactly **one root** per send (`flexBox` for UI or `video` for video); never nest `video` inside `flexBox` (`SKILL:196`). `flexBox`/`video` return `Unit` since 0.9.0 (`CHANGELOG:23`). The docs' `paddingAll = 16` (`DOCS:1429,1442`) is **not** the parameter name — the reference and the compiling sample use `padding = 24` (`DA-SRC/display/DisplayViewModel.kt:505`).

### 4.2 Children: `FlexBoxScope` (full signatures from `REF FlexBoxScope`)

```kotlin
fun flexBox(direction: Direction = COLUMN, gap: Int = 0, alignment: Alignment = START, crossAlignment: Alignment = START,
            wrap: Boolean = false, padding: Int = 0, paddingTop/Bottom/Start/End: Int? = null,
            background: FlexBoxBackground = NONE, onClick: (() -> Unit)? = null,
            flexGrow: Float = 0f, flexShrink: Float = 0f, alignSelf: Alignment? = null, block: FlexBoxScope.() -> Unit)

fun text(content: String, style: TextStyle = TextStyle.BODY, color: TextColor = TextColor.PRIMARY,
         flexGrow: Float = 0f, flexShrink: Float = 0f, alignSelf: Alignment? = null)

fun image(uri: String? = null, bitmap: Bitmap? /* = null — the reference page renders no default but both
          image(uri = ...) and image(bitmap = ...) compile in the sample :671-673 */,
          sizePreset: ImageSize = ImageSize.FILL, cornerRadius: CornerRadius = CornerRadius.NONE,
          flexGrow: Float = 0f, flexShrink: Float = 0f, alignSelf: Alignment? = null)

fun icon(name: IconName, style: IconStyle = IconStyle.FILLED, flexGrow: Float = 0f, flexShrink: Float = 0f, alignSelf: Alignment? = null)

fun button(label: String, style: ButtonStyle = ButtonStyle.PRIMARY, iconName: IconName? = null, onClick: (() -> Unit)? = null,
           flexGrow: Float = 0f, flexShrink: Float = 0f, alignSelf: Alignment? = null)

fun buttonGroup(alignment: ButtonGroupAlignment = ButtonGroupAlignment.CENTER,
                flexGrow: Float = 0f, flexShrink: Float = 0f, alignSelf: Alignment? = null, block: ButtonGroupScope.() -> Unit)
```

`ButtonGroupScope.button(label: String, style: ButtonStyle = PRIMARY, iconName: IconName? = null, onClick: (() -> Unit)? = null)` — "The underlying WDS ButtonGroup arranges them horizontally, keeps each unfocused icon button collapsed to its icon, and widens the focused button to reveal its label" (`REF ButtonGroupScope`). Added in 0.9.0 (`CHANGELOG:13`). The sample puts a `buttonGroup` inside a `flexBox(direction = ROW, gap = 8, alignment = CENTER, crossAlignment = CENTER)` (`DisplayViewModel.kt:510-527`, `590-634`).

Flex modifiers (`flexGrow`, `flexShrink`, `alignSelf`) are constructor params; to flex a non-flexBox child, wrap it (`DOCS:1411-1424`). The sample uses `flexGrow = 1f` (thumbnail) vs `flexGrow = 7f` (text column) in a ROW (`DisplayViewModel.kt:651-660`).

### 4.3 Enums (`DOCS:1305-1405`, `REF` pages, `CHANGELOG:75`)

| Enum | Values |
|---|---|
| `Direction` | `COLUMN`, `ROW`, `COLUMN_REVERSE`, `ROW_REVERSE` |
| `Alignment` | `START`, `CENTER`, `END`, `STRETCH` (main axis via `alignment`, cross axis via `crossAlignment`) |
| `FlexBoxBackground` | `NONE`, `CARD` ("card-style background surface") |
| `TextStyle` | `HEADING` (large bold), `BODY`, `META` (small captions) |
| `TextColor` | `PRIMARY` (high contrast), `SECONDARY` (lower contrast) |
| `ImageSize` | `ICON` (small inline), `FILL` |
| `CornerRadius` | `NONE`, `SMALL`, `MEDIUM` |
| `ButtonStyle` | `PRIMARY` (filled, high emphasis), `SECONDARY` (filled, medium), `OUTLINE` (low) |
| `ButtonGroupAlignment` | `START`, `CENTER`, `END` |
| `IconStyle` | `FILLED`, `OUTLINE` |
| `IconName` | 100+ glyphs, full catalog at `DOCS:1693-1810`. Useful for TurboMeta: `SMART_GLASSES`, `META_AI`, `STAR_CIRCLE_TRIANGLE_AI`, `MAGIC_WAND`, `SPEECH_BUBBLE`, `THREE_DOT_SPEECH_BUBBLE`, `SPEECH_BUBBLE_OFF`, `EYE`, `FOUR_CORNER_FRAME`, `VIDEO_CAMERA`, `VIDEO_CAMERA_OFF`, `FORK_KNIFE`, `PIZZA_SLICE`, `COFFEE_CUP`, `HEART`, `CHECKMARK`, `CHECKMARK_CIRCLE`, `X`, `EXCLAMATION_TRIANGLE`, `EXCLAMATION_CIRCLE`, `I_CIRCLE`, `ARROW_LEFT`, `ARROW_RIGHT`, `CARET_UP/DOWN/LEFT/RIGHT`, `TRIANGLE_LEFT_VERTICAL_LINE`, `TRIANGLE_RIGHT_VERTICAL_LINE`, `TWO_ARROWS_CLOCKWISE`, `SPEAKER_WITH_THREE_ARCS`, `SPEAKER_OFF`, `GEAR`, `CODE`, `GLOBE_WESTERN_HEMISPHERE`, `CLOCK`, `LIGHT_BULB`, `PERSON`, `HOUSE`. Never pass raw strings (`SKILL:232`). |
| `VideoCodec` | `MP4` |
| `VideoPlayerState` | `IDLE`, `STARTING` (buffering), `PLAYING`, `PAUSE`, `ENDED` (`DOCS:1601-1609`) |
| `VideoPlayerError` | `NOT_BOUND`, `INVALID_URL`, `INVALID_DIMENSIONS`, `ALREADY_PLAYING`, `STREAM_REJECTED`, `PLAYBACK_FAILED`, `UNEXPECTED_ERROR` (`DOCS:1664-1682`) |
| `VideoSource` | `VideoSource.Url(url: String)` only (`REF VideoSource`) |

### 4.4 Layout constraints & performance rules

- **Resolution 600×600**; images larger than that give no benefit and "introduce lag due to Bluetooth bandwidth constraints" (`DOCS:1365`, `731`). Scale bitmaps to ≤ 600 px on the long side before `image(bitmap=)`; thumbnails ≈ 200–300 px.
- **Dark ground**: the glasses use an additive waveguide — dark backgrounds are transparent, light is opaque (`DOCS:3`, WebApps note; the DAT DSL manages colors itself, you only choose `FlexBoxBackground.CARD/NONE` and `TextColor`).
- **Replace semantics**: every `sendContent` replaces the entire display; no partial updates (`DOCS:1297`, `733-734`). `Display.clearDisplay(): DatResult<Boolean, DisplayError>` clears without stopping (`REF Display`, `CHANGELOG:50`).
- **Vertical scroll only**; horizontal scrolling unsupported (`DOCS:1297`). Views are presented one at a time.
- **Text**: no numeric character limit is documented anywhere in the 0.9 docs/reference (searched `DOCS` and live docs). Guidance is "Use clear, readable text… keep text concise", `HEADING` for key info (`DOCS:739`). Long AI answers should be paginated by the app (Section 8.5) rather than dumped into one scrolling `text`.
- **Send rate**: no numeric limit documented. "Simple views (text, small images) transition quickly. Complex views with large images may introduce lag" (`DOCS:730`). Coalesce streaming transcript updates (Section 8.4).
- **Video**: MP4 only, ≤ 400 px per side and ≤ 70 000 total pixels, `https` only, one video at a time per display session; play only after the send succeeds; `player.close()` ends playback and the player cannot be restarted (`DOCS:1575-1613`, `REF VideoPlayer`).
- **Root view / L0**: designate one root view; back gesture from L0 ends the session (`DOCS:736`).
- **State lives on the phone** — glasses keep no app state (`DOCS:735`).
- **Sleep**: display dims at 20 s and sleeps at 25 s of inactivity; send fresh content on the next event (`DOCS:1291`, `738`).

### 4.5 Tap/click routing

`button(onClick)`, `ButtonGroupScope.button(onClick)`, and `flexBox(onClick)` callbacks are invoked **on the phone** when the user taps on the glasses (captouch/Neural Band) (`DOCS:1546-1565`, `CHANGELOG:15`, `SKILL:198`). The sample's callbacks immediately call another `send…` method that issues a fresh `sendContent` (`DisplayViewModel.kt:518-526`, `598-632`, `649`). Keep callbacks fast; hop to the ViewModel/manager and do the work there. The dispatch thread is not documented — treat callbacks as arbitrary-thread and only touch `StateFlow`s / launch coroutines from them.

---

## 5. DisplayAccess sample ViewModel/UI state machine (copy this)

### 5.1 State types (`DA-SRC/display/DisplayUIState.kt:14-37`)

```kotlin
/** Connection state for the DWA service on glasses. */
enum class DwaConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class DisplayUIState(
    // Session
    val isSessionActive: Boolean = false,
    val isDisplayAttached: Boolean = false,
    val selectedDeviceId: DeviceIdentifier? = null,
    val connectionState: DwaConnectionState = DwaConnectionState.DISCONNECTED,
    val isPreparingDisplay: Boolean = false,
    // UI
    val isStartingSession: Boolean = false,
    val isStoppingSession: Boolean = false,
    val isDatAppUpdateRequired: Boolean = false,
    val snackbarMessage: String? = null,
    // Display capability
    val displayState: DisplayState? = null,
)
```

Wearables side (`DA-SRC/wearables/WearablesUiState.kt:15-29`): `registrationState`, `devices: Set<DeviceIdentifier>`, `devicesMetadata: Map<DeviceIdentifier, Device>`, `isFirmwareUpdateRequired`, derived `isRegistered`, `hasConnectedDevice`.

Private VM fields guarded by `sessionLock` (`DisplayViewModel.kt:179-205`): `session: DeviceSession?`, `display: Display?`, `sessionStateJob`, `sessionErrorJob`, `displayStateJob`, `pendingDeviceId`, plus `tutorialVideoStateJob`; three `CoroutineExceptionHandler`s (session observer, display observer, sendContent) (`:185-197`); `dispatcher = Dispatchers.IO` for sends (`:179`).

### 5.2 Transition table

| Trigger | From | To (fields) | Code |
|---|---|---|---|
| `prepareDisplayConnection(id)` (row tap) | DISCONNECTED | `connectionState=CONNECTING, isStartingSession=true, isPreparingDisplay=true, selectedDeviceId=id, pendingDeviceId=id` | `:289-317`, `:211-218` |
| `createSession` fails | CONNECTING | DISCONNECTED; `isDatAppUpdateRequired = (error == DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED)` | `:272-285` |
| `session.state == STARTED` | CONNECTING | `CONNECTED, isSessionActive=true, isStartingSession=false, isDatAppUpdateRequired=false`; then `attachDisplay()` if pending | `:229-243` |
| `addDisplay` ok | CONNECTED | `isDisplayAttached=true, isPreparingDisplay=true` | `:333-341` |
| `display.state` | — | `displayState=state; isPreparingDisplay = state !in {STARTED, STOPPED}`; STARTED → `isPreparingDisplay=false` ("Display ready"); STOPPED after started → "Display session stopped" | `:343-370` |
| `addDisplay` fails | CONNECTED | `isPreparingDisplay=false` + snackbar | `:373-380` |
| `session.errors` emits | any | DISCONNECTED, `isSessionActive=false, isStartingSession=false, isPreparingDisplay=false, selectedDeviceId=null, isDatAppUpdateRequired=(error==DAT_APP…)` | `:483-496` |
| `session.state == STOPPED` | any | DISCONNECTED, `isSessionActive=false, isDisplayAttached=false, isPreparingDisplay=false, selectedDeviceId=null`, `cleanupDisplay()` | `:244-257` |
| `stopSession()` (user Disconnect / onCleared) | any | `isStoppingSession=true` → detach display → cancel jobs → `session.stop()` → DISCONNECTED | `:433-456`, `:677-680` |
| select a different device | any | `resetConnectionForNewDevice()` = full teardown then restart | `:290-292`, `:458-474` |

### 5.3 UI gating (`DA-SRC/ui/AppScaffold.kt`)

- `isCapabilityReady = displayState.displayState == DisplayState.STARTED` (`:74`).
- Auto-navigate to the samples list the moment `isSessionActive` flips false→true (`DisposableEffect`, `:94-101`).
- "Try it" enabled only when `displayState.isSessionActive && isCapabilityReady` (`:142`); Unregister first calls `displayViewModel.stopSession()` then `startUnregistration(activity)` (`:128-131`).
- ConnectScreen row status text: "Update required" > (selected && preparing → "Preparing") > "Connected" > "Disconnected" (`ConnectScreen.kt:386-401`); string resources at `DA/app/src/main/res/values/strings.xml:10-34`.

---

## 6. Can Camera and Display coexist on one DeviceSession?

Evidence:
- Capabilities are attached independently to a `DeviceSession`: `addCamera(streamConfiguration: StreamConfiguration = StreamConfiguration()): DatResult<Camera, DeviceSessionError>` "limited to one per session"; `addDisplay(config: DisplayConfiguration = DisplayConfiguration()): DatResult<Display, DeviceSessionError>` "limited to one per session" (`REF DeviceSession`). `DeviceSessionError.CAPABILITY_ALREADY_ADDED` = "A capability of this type has already been added to the session" (`REF DeviceSessionError`) — the restriction is per **type**.
- Architecture note: "One display per session. Only one display capability can be attached to a session at a time" (`DOCS:733`) — again scoped to display.
- Conventions: "Do not assume a session implies streaming or display access; capabilities are attached separately" (`AGENTS:100`, dat-conventions skill).
- `session.stop()` "cascades stop to all attached capabilities" (`REF DeviceSession`) — plural, implying multiple.
- Neither sample attaches both (DisplayAccess has no camera dependency, `DA/app/build.gradle.kts:70-71`; iOS DisplayAccess has no `MWDATCamera` usage). No changelog entry forbids or confirms simultaneous use.
- Per-device constraint: "Only one session can run on a device at a time, and certain features are unavailable while your session is active" (`DOCS:72`); `DeviceSessionError.SESSION_ALREADY_EXISTS` (`REF DeviceSessionError`).

Conclusion: **API-wise yes** — one `DeviceSession` with `addCamera(...)` and `addDisplay(...)` attached. TurboMeta must therefore consolidate session ownership (Section 8.2). **Unverified on hardware**: Bluetooth Classic bandwidth is already the bottleneck for video (adaptive ladder `DOCS:620-622`); large `image(bitmap)` payloads while streaming will compete. Mitigations: text-only cards during streaming, tiny thumbnails (≤ 240 px, JPEG-quality ~60 before decode to Bitmap), coalesce sends, and reduce `VideoQuality` to `LOW` when display is active. Validate: (a) `addDisplay` after `addCamera` and vice-versa succeed; (b) frame rate before/after a card send; (c) `DisplayError.RENDERING_FAILED` frequency with images while streaming.

---

## 7. MockDeviceKit and Display

- `GlassesModel` (0.9.0): `RAYBAN_META`, `OAKLEY_META_HSTN`, `OAKLEY_META_VANGUARD`, `RAYBAN_META_OPTICS`, `META_GLASSES` (`REF GlassesModel`; `CHANGELOG:45`). The reference states: "Display-capable glasses (e.g., Meta Ray-Ban Display) are not included here — when display glasses mock support is added, a separate model enum and pairing method will be introduced."
- Mock type names reflect this: `MockDisplaylessGlasses` was renamed `MockGlasses` in 0.8.0 (`CHANGELOG:55`); services exposed: `services.camera` (`setCameraFeed(Uri|CameraFacing)`, `setCapturedImage(Uri)`) and `services.captouch` (`tap()`, `tapAndHold()`) (`CA-SRC/mockdevicekit/MockDeviceKitViewModel.kt:147,158,171,186,204`). No `services.display`.
- `MockDeviceKitInterface`: `enable(config: MockDeviceKitConfig = MockDeviceKitConfig())`, `disable()`, `isEnabled`, `pairedDevices`, `permissions`, `pairGlasses(model): DatResult<MockGlasses, MockDeviceKitError>`, `unpairDevice(device)` (`REF MockDeviceKitInterface`).
- Consequence: a mock device reports `deviceType.isDisplayCapable == false`, so `addDisplay` cannot be exercised; **display content cannot be tested without hardware**. What can be tested with MDK: registration, device list, the shared-session refactor, camera streaming/capture, and the `isDisplayCapable == false` branch (display UI hidden). Instrumentation base class pattern: `DOCS:2429-2470`; `CA-SRC/../InstrumentationTest.kt:511` (`pairGlasses(GlassesModel.RAYBAN_META)`).
- Recommended substitute: a debug-only Compose "Glasses preview" screen that renders the `DisplayCard` model in a 600×600 dark box (Section 8.7), and unit tests on the pure card→text/pagination logic.

---

## 8. Proposed TurboMeta Android design

### 8.1 Package layout (new files)

```
APP/glasses/GlassesSessionManager.kt      // single DeviceSession owner: session + camera + display lifecycles
APP/glasses/GlassesDisplayManager.kt      // DisplayCard → sendContent; coalescing; pagination; L0 menu callbacks
APP/glasses/DisplayCard.kt                // sealed card model (pure Kotlin, testable)
APP/glasses/DisplayCards.kt               // ContentScope builders per card (the only file importing dat.display.views)
APP/glasses/GlassesDisplayUiState.kt      // enum + data class mirrored from the sample
APP/viewmodels/GlassesDisplayViewModel.kt // thin VM exposing state for Home/Settings
APP/ui/screens/GlassesDisplayPreviewScreen.kt (debug)  // phone-side 600x600 preview of a DisplayCard
```

### 8.2 `GlassesSessionManager` — one session, two capabilities

Why: today `WearablesViewModel.startStream()` (`APP/viewmodels/WearablesViewModel.kt:284-288`) and `QuickVisionService.captureAndAnalyze()` (`APP/services/QuickVisionService.kt:224-228`) each create their own stream session; in 0.9.0 the device allows one session (`DOCS:72`) and the display must hang off that same session. The manager is a process singleton like `WearablesRepository.getInstance(context)` (`DA-SRC/wearables/WearablesRepository.kt:105-118`) so a foreground `Service` and ViewModels share it.

```kotlin
package com.smartview.glassai.glasses

class GlassesSessionManager private constructor(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    @GuardedBy("lock") private var session: DeviceSession? = null
    @GuardedBy("lock") private var camera: Camera? = null
    @GuardedBy("lock") private var display: Display? = null
    private var sessionStateJob: Job? = null; private var sessionErrorJob: Job? = null; private var displayStateJob: Job? = null

    val deviceSelector: DeviceSelector = AutoDeviceSelector()          // keep AutoDeviceSelector (WearablesViewModel.kt:102)

    private val _sessionState = MutableStateFlow(DeviceSessionState.STOPPED)
    val sessionState: StateFlow<DeviceSessionState> = _sessionState.asStateFlow()
    private val _displayState = MutableStateFlow<DisplayState?>(null)      // null = no Display attached
    val displayState: StateFlow<DisplayState?> = _displayState.asStateFlow()
    private val _sessionError = MutableSharedFlow<DeviceSessionError>(extraBufferCapacity = 8)
    val sessionError: SharedFlow<DeviceSessionError> = _sessionError
    private val _activeDevice = MutableStateFlow<Device?>(null)          // metadata of selector's active device
    val activeDevice: StateFlow<Device?> = _activeDevice.asStateFlow()
    val isDisplayCapableDevice: StateFlow<Boolean> = _activeDevice.map { it?.isDisplayCapable() == true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    var displayEnabledByUser: Boolean = true   // read from APIKeyManager (8.6) at startup

    /** Idempotent. Creates + starts the session if none is active; attaches Display once STARTED when eligible. */
    fun ensureSession(): Boolean {
        synchronized(lock) { if (session != null) return true }
        val result = Wearables.createSession(deviceSelector)
        return result.fold(
            onSuccess = { s ->
                synchronized(lock) { session = s }
                sessionStateJob = scope.launch {
                    s.state.collect { st ->
                        _sessionState.value = st
                        when (st) {
                            DeviceSessionState.STARTED -> maybeAttachDisplay()
                            DeviceSessionState.STOPPED -> teardown()
                            else -> Unit
                        }
                    }
                }
                sessionErrorJob = scope.launch { s.errors.collect { _sessionError.emit(it) } }
                s.start()                                  // returns Unit; failures arrive via s.errors (SKILL:302)
                true
            },
            onFailure = { error, _ -> scope.launch { _sessionError.emit(error) }; false },
        )
    }

    private fun maybeAttachDisplay() {
        val s = synchronized(lock) { session } ?: return
        if (!displayEnabledByUser || !isDisplayCapableDevice.value) return
        if (synchronized(lock) { display } != null) return
        s.addDisplay(DisplayConfiguration()).fold(          // REF DeviceSession; sample DisplayViewModel.kt:330-381
            onSuccess = { d ->
                synchronized(lock) { display = d }
                displayStateJob = scope.launch { d.state.collect { _displayState.value = it } }
            },
            onFailure = { error, _ -> scope.launch { _sessionError.emit(error) } },
        )
    }

    /** Camera on the same session. Callers wait for STARTED (or call from a STARTED collector). */
    fun addCamera(config: StreamConfiguration): DatResult<Camera, DeviceSessionError>? {
        val s = synchronized(lock) { session } ?: return null
        return s.addCamera(config).onSuccess { c -> synchronized(lock) { camera = c } }
    }
    fun stopCamera() { synchronized(lock) { camera }?.stop(); synchronized(lock) { camera = null } }  // CA CameraViewModel.kt:413-418
    fun currentDisplay(): Display? = synchronized(lock) { display }
    fun isDisplayReady(): Boolean = _displayState.value == DisplayState.STARTED

    fun stopSession() {
        val (s, d) = synchronized(lock) { session to display }
        displayStateJob?.cancel(); displayStateJob = null
        if (d != null) s?.removeDisplay()                    // order per DOCS:1287-1289 and sample :443-446
        stopCamera()
        sessionStateJob?.cancel(); sessionErrorJob?.cancel()
        s?.stop()
        teardown()
    }
    private fun teardown() { synchronized(lock) { session = null; camera = null; display = null }; _displayState.value = null; _sessionState.value = DeviceSessionState.STOPPED }

    companion object { @Volatile private var instance: GlassesSessionManager? = null
        fun getInstance(context: Context) = instance ?: synchronized(this) { instance ?: GlassesSessionManager(context.applicationContext).also { instance = it } } }
}
```

Notes:
- `AutoDeviceSelector` default filter already excludes incompatible devices (`CHANGELOG:185`); to restrict a **display-only** session use `AutoDeviceSelector(filter = { it.isDisplayCapable() })` (`SKILL:61`). TurboMeta should keep the unfiltered selector (camera features must still work on Ray-Ban Meta) and gate display on `isDisplayCapableDevice`.
- `_activeDevice` is fed by collecting `deviceSelector.activeDeviceFlow()` and then `Wearables.devicesMetadata[id]?.collect` (pattern `DA-SRC/wearables/WearablesRepository.kt:78-83`).
- `DisplayState.STOPPED` while the session is still `STARTED` (glasses disconnected) leaves the `Display` attached; the reference says it may go `STOPPED → STARTING → STARTED` again on reconnection. Keep it; only drop it on session `STOPPED`.
- Session lifetime policy (open question, Section 9): (A) session lives while the user is inside a feature screen (mirrors today's `startStream()/stopStream()` in `LiveAIScreen.kt:77-108`), or (B) a persistent "Glasses display" session so the L0 menu card is always visible. (B) blocks Meta AI features for the whole time (`DOCS:72`).

Migration of existing callers:
- `WearablesViewModel.startStream()` → `sessionManager.ensureSession()`, then on `sessionState == STARTED`: `sessionManager.addCamera(StreamConfiguration(videoQuality = q, frameRate = 24))?.onSuccess { camera -> collect camera.stream.videoStream / state / errorStream; camera.stream.start() }`. `stopStream()` → `sessionManager.stopCamera()` (keep the session if display should stay up; `stopSession()` on `disconnect()` at `WearablesViewModel.kt:200-207`).
- `QuickVisionService` → replace lines 224-264 with `ensureSession()` + `addCamera(MEDIUM,24)` + first-frame capture + `stopCamera()`; leave the session running so the result card can be shown; stop it in `finishService()` only if no other feature owns it (reference count in the manager).

### 8.3 `GlassesDisplayUiState` (mirrors the sample)

```kotlin
enum class GlassesDisplayStatus { UNSUPPORTED, DISABLED, IDLE, SESSION_STARTING, SESSION_ACTIVE, DISPLAY_PREPARING, DISPLAY_READY, UPDATE_REQUIRED, ERROR }

data class GlassesDisplayUiState(
    val status: GlassesDisplayStatus = GlassesDisplayStatus.IDLE,
    val deviceName: String? = null,
    val isDisplayCapable: Boolean = false,
    val isFirmwareUpdateRequired: Boolean = false,      // Device.compatibility == DEVICE_UPDATE_REQUIRED
    val isDatAppUpdateRequired: Boolean = false,        // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED seen
    val displayState: DisplayState? = null,
    val lastError: String? = null,
)
```
Derivation: `UNSUPPORTED` when `!isDisplayCapable`; `DISABLED` when the setting is off; `SESSION_STARTING` when `sessionState == STARTING`; `DISPLAY_PREPARING` when `displayState !in {STARTED, STOPPED, null}` (sample `:351-353`); `DISPLAY_READY` when `displayState == STARTED`; `UPDATE_REQUIRED` when either update flag is set.

### 8.4 `GlassesDisplayManager` — card model, coalescing, pagination

```kotlin
sealed interface DisplayCard {
    data class Status(val deviceName: String, val liveAiReady: Boolean, val quickVisionReady: Boolean, val openClawConnected: Boolean) : DisplayCard   // L0 root/menu
    data class LiveAI(val phase: LiveAIPhase, val userText: String?, val assistantText: String, val isStreaming: Boolean) : DisplayCard
    data class QuickVision(val modeName: String, val resultText: String, val page: Int = 0) : DisplayCard
    data class LeanEat(val response: FoodNutritionResponse, val page: Int = 0) : DisplayCard
    data class OpenClaw(val userText: String?, val replyText: String, val isFinal: Boolean, val page: Int = 0) : DisplayCard
    data class Notice(val title: String, val body: String, val icon: IconName = IconName.I_CIRCLE) : DisplayCard    // "Looking…", errors
}
enum class LiveAIPhase { CONNECTING, LISTENING, PROCESSING, SPEAKING, IDLE }

class GlassesDisplayManager(private val sessions: GlassesSessionManager, private val strings: (Int) -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)      // sample sends on Dispatchers.IO (:179,387)
    private val pending = MutableStateFlow<DisplayCard?>(null)                 // conflated: only the latest card is sent
    private var lastSent: DisplayCard? = null
    var onAction: ((DisplayAction) -> Unit)? = null                             // tap routing back to app (8.5)

    init {
        scope.launch {                                                          // serial sender; drops intermediate cards
            pending.filterNotNull().collectLatest { card -> send(card) }
        }
        scope.launch {                                                          // resend last card when display becomes ready
            sessions.displayState.collect { st -> if (st == DisplayState.STARTED) lastSent?.let { pending.value = it } }
        }
    }

    fun show(card: DisplayCard) { pending.value = card }
    fun clear() { scope.launch { sessions.currentDisplay()?.clearDisplay() } }

    private suspend fun send(card: DisplayCard) {
        val display = sessions.currentDisplay() ?: return
        if (!sessions.isDisplayReady()) return                                  // INVALID_SESSION_STATE otherwise
        display.sendContent { render(card, strings, ::dispatch) }.fold(
            onSuccess = { lastSent = card },
            onFailure = { error, _ -> Log.w(TAG, "sendContent failed: ${error.description}") },
        )
    }
    private fun dispatch(action: DisplayAction) { onAction?.invoke(action) }
}
```

Streaming throttle: Live AI transcript deltas arrive per token (`APP/viewmodels/OmniRealtimeViewModel.kt:139-141`); wrap `show()` for `LiveAI` cards in `sample(600 ms)`-style coalescing (e.g. `pending` conflation plus `debounce` on the producer side) and always push the final card from `onTranscriptDone` (`:143-149`).

### 8.5 Card builders (`DisplayCards.kt`) — sample `sendContent` code

All builders use only the documented DSL (Section 4). `S(R.string.x)` = string lookup.

**(a) Status / L0 menu card** — the root view users return to; buttons launch features from the glasses (callbacks run on the phone):

```kotlin
fun ContentScope.statusCard(c: DisplayCard.Status, s: (Int) -> String, on: (DisplayAction) -> Unit) {
    flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 24, background = FlexBoxBackground.CARD, gap = 8) {
            flexBox(direction = Direction.ROW, gap = 12, crossAlignment = Alignment.CENTER) {
                icon(name = IconName.SMART_GLASSES, style = IconStyle.FILLED)
                text("TurboMeta", style = TextStyle.HEADING)
            }
            text(c.deviceName, style = TextStyle.META, color = TextColor.SECONDARY)
            text(if (c.liveAiReady) s(R.string.display_status_ready) else s(R.string.display_status_needs_api_key),
                 style = TextStyle.BODY, color = TextColor.SECONDARY)
        }
        flexBox(direction = Direction.ROW, gap = 8, alignment = Alignment.CENTER, crossAlignment = Alignment.CENTER) {
            buttonGroup {
                button(s(R.string.feature_liveai_title), iconName = IconName.THREE_DOT_SPEECH_BUBBLE, onClick = { on(DisplayAction.StartLiveAI) })
                button(s(R.string.feature_quickvision_title), iconName = IconName.EYE, onClick = { on(DisplayAction.StartQuickVision) })
                button(s(R.string.lean_eat), iconName = IconName.FORK_KNIFE, onClick = { on(DisplayAction.StartLeanEat) })
                if (c.openClawConnected) button("OpenClaw", iconName = IconName.CODE, style = ButtonStyle.SECONDARY, onClick = { on(DisplayAction.OpenClawSnap) })
            }
        }
    }
}
```

**(b) Live AI reply / caption card** (fed from `OmniRealtimeViewModel` callbacks; `phase` maps from `ViewState` at `OmniRealtimeViewModel.kt:51-59`):

```kotlin
fun ContentScope.liveAiCard(c: DisplayCard.LiveAI, s: (Int) -> String, on: (DisplayAction) -> Unit) {
    val (statusIcon, statusText) = when (c.phase) {
        LiveAIPhase.LISTENING  -> IconName.SPEAKER_WITH_THREE_ARCS to s(R.string.liveai_listening)   // "Listening…" / 正在听…
        LiveAIPhase.PROCESSING -> IconName.THREE_DOTS_HORIZONTAL   to s(R.string.processing)         // 处理中…
        LiveAIPhase.SPEAKING   -> IconName.THREE_DOT_SPEECH_BUBBLE to s(R.string.liveai_speaking)    // "AI Speaking…" / AI 回复中…
        LiveAIPhase.CONNECTING -> IconName.TWO_ARROWS_CLOCKWISE    to s(R.string.connecting)
        LiveAIPhase.IDLE       -> IconName.SMART_GLASSES           to s(R.string.liveai_connected)
    }
    flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(direction = Direction.ROW, gap = 8, crossAlignment = Alignment.CENTER, paddingStart = 24, paddingEnd = 24) {
            icon(name = statusIcon, style = IconStyle.OUTLINE)
            text(statusText, style = TextStyle.META, color = TextColor.SECONDARY)
        }
        flexBox(padding = 24, background = FlexBoxBackground.CARD, gap = 8) {
            c.userText?.takeIf { it.isNotBlank() }?.let { text(it.take(120), style = TextStyle.META, color = TextColor.SECONDARY) }
            text(if (c.assistantText.isBlank()) "…" else c.assistantText.takeLast(320), style = TextStyle.BODY)
        }
        flexBox(direction = Direction.ROW, gap = 8, alignment = Alignment.CENTER) {
            buttonGroup {
                button(s(R.string.end), style = ButtonStyle.OUTLINE, iconName = IconName.X, onClick = { on(DisplayAction.EndLiveAI) })
            }
        }
    }
}
```
Hook points: `OmniRealtimeViewModel.initializeOmniService` / `initializeGeminiService` callbacks `onTranscriptDelta` (`:139-141`, `:183-185`), `onTranscriptDone` (`:143-149`, `:187-193`), `onUserTranscript` (`:151-156`, `:195-200`), `onSpeechStarted/Stopped` (`:158-164`, `:202-208`); `isSpeaking` collector (`:242-249`). `DisplayAction.EndLiveAI` → `OmniRealtimeViewModel.disconnect()` (`:295-306`) and navigate back (the screen's `DisposableEffect` already stops the stream, `LiveAIScreen.kt:100-108`).

**(c) Quick Vision result card** (fed from `QuickVisionService`):

```kotlin
fun ContentScope.quickVisionCard(c: DisplayCard.QuickVision, s: (Int) -> String, on: (DisplayAction) -> Unit) {
    val pages = paginate(c.resultText, maxChars = 280)          // Section 8.5 helper
    val page = c.page.coerceIn(0, pages.lastIndex)
    flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 24, background = FlexBoxBackground.CARD, gap = 8) {
            flexBox(direction = Direction.ROW, gap = 8, crossAlignment = Alignment.CENTER) {
                icon(name = IconName.EYE, style = IconStyle.FILLED)
                text(s(R.string.feature_quickvision_title), style = TextStyle.HEADING)   // "Quick Vision" / 快速识图
            }
            text("${c.modeName} · ${page + 1}/${pages.size}", style = TextStyle.META, color = TextColor.SECONDARY)
            text(pages[page], style = TextStyle.BODY)
        }
        flexBox(direction = Direction.ROW, gap = 8, alignment = Alignment.CENTER, crossAlignment = Alignment.CENTER) {
            buttonGroup {
                if (page > 0) button(s(R.string.display_prev), iconName = IconName.TRIANGLE_LEFT_VERTICAL_LINE,
                                     onClick = { on(DisplayAction.Page(c.copy(page = page - 1))) })
                if (page < pages.lastIndex) button(s(R.string.display_next), iconName = IconName.TRIANGLE_RIGHT_VERTICAL_LINE,
                                                   onClick = { on(DisplayAction.Page(c.copy(page = page + 1))) })
                button(s(R.string.display_again), style = ButtonStyle.SECONDARY, iconName = IconName.TWO_ARROWS_CLOCKWISE,
                       onClick = { on(DisplayAction.StartQuickVision) })
                button(s(R.string.display_done), iconName = IconName.CHECKMARK, onClick = { on(DisplayAction.BackToMenu) })
            }
        }
    }
}
```
Hook points in `QuickVisionService`: after `speak(lookingText)` (`:205-207`) show `Notice("Quick Vision", "正在识别"/"Looking")`; at `broadcastStatus("analyzing")` (`:271`) show `Notice(..., "正在分析…")`; in `onSuccess` next to `broadcastResult(description)` (`:294-296`) show `QuickVision(modeName = modeManager.currentMode.value.getDisplayName(this), resultText = description)`; in `onFailure` (`:298-304`) show `Notice(error)`. `DisplayAction.StartQuickVision` → `startForegroundService(Intent(ctx, QuickVisionService::class.java).apply { action = ACTION_CAPTURE_AND_ANALYZE })` (same as `PorcupineWakeWordService.kt:216-221`). The result is also broadcast to `QuickVisionScreen` (`ui/screens/QuickVisionScreen.kt:242`) unchanged.

**(d) LeanEat nutrition card** (`FoodNutritionResponse` fields at `APP/models/FoodNutritionModels.kt:9-33`; `FoodItem` at `:35-63`):

```kotlin
fun ContentScope.leanEatCard(c: DisplayCard.LeanEat, s: (Int) -> String, on: (DisplayAction) -> Unit) {
    val r = c.response
    flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 24, background = FlexBoxBackground.CARD, gap = 8) {
            flexBox(direction = Direction.ROW, gap = 8, crossAlignment = Alignment.CENTER) {
                icon(name = IconName.FORK_KNIFE, style = IconStyle.FILLED)
                text(s(R.string.lean_eat), style = TextStyle.HEADING)                                   // "LeanEat"
            }
            text("${s(R.string.leaneat_health_score)} ${r.healthScore} · ${r.healthScoreText}",          // 健康评分 82 · 优秀
                 style = TextStyle.META, color = TextColor.SECONDARY)
            text("${r.totalCalories} ${s(R.string.leaneat_kcal)}", style = TextStyle.HEADING)
            text("${s(R.string.leaneat_protein)} ${"%.0f".format(r.totalProtein)}g · ${s(R.string.leaneat_fat)} ${"%.0f".format(r.totalFat)}g · ${s(R.string.leaneat_carbs)} ${"%.0f".format(r.totalCarbs)}g",
                 style = TextStyle.BODY)
        }
        if (c.page == 0) {
            flexBox(padding = 24, background = FlexBoxBackground.CARD, gap = 4) {
                r.foods.take(3).forEach { f -> text("${f.name} · ${f.portion} · ${f.calories} ${s(R.string.leaneat_kcal)}", style = TextStyle.BODY) }
                if (r.foods.size > 3) text("+${r.foods.size - 3}", style = TextStyle.META, color = TextColor.SECONDARY)
            }
        } else {
            flexBox(padding = 24, background = FlexBoxBackground.CARD, gap = 4) {
                text(s(R.string.leaneat_suggestions), style = TextStyle.META, color = TextColor.SECONDARY)               // 建议
                r.suggestions.take(3).forEach { text("• $it", style = TextStyle.BODY) }
            }
        }
        flexBox(direction = Direction.ROW, gap = 8, alignment = Alignment.CENTER, crossAlignment = Alignment.CENTER) {
            buttonGroup {
                button(if (c.page == 0) s(R.string.leaneat_suggestions) else s(R.string.leaneat_food_details),
                       iconName = if (c.page == 0) IconName.LIGHT_BULB else IconName.THREE_HORIZONTAL_LINES,
                       onClick = { on(DisplayAction.Page(c.copy(page = 1 - c.page))) })
                button(s(R.string.display_done), iconName = IconName.CHECKMARK, onClick = { on(DisplayAction.BackToMenu) })
            }
        }
    }
}
```
Hook point: `LeanEatViewModel.analyzeFood` `onSuccess` (`APP/viewmodels/LeanEatViewModel.kt:91-94`) → `displayManager.show(DisplayCard.LeanEat(response))`; `ViewState.Analyzing` (`:84`) → `Notice("LeanEat", s(R.string.leaneat_analyzing))`. `LeanEatScreen` obtains frames from `wearablesViewModel.currentFrame` and `takePhoto()` (`ui/navigation/Navigation.kt:146-157`), so the session is already up when the card is sent.

**(e) OpenClaw chat reply card** (OpenClaw is not yet ported to Android; the iOS protocol: request `method: "chat.send"` with `params { sessionKey, message, idempotencyKey, attachments[{type:"image", mimeType:"image/jpeg", content:<base64>}] }` (`IOS/Services/OpenClaw/OpenClawNodeService.swift:104-135`); reply events `method == "chat"` with `payload.state` (`"final"` when complete) and `payload.message.content[].text` (`:391-403`); the view accumulates partial text as `pendingResponse` and commits on `[[FINAL]]` (`IOS/Views/OpenClawChatView.swift:255-268`); node commands `camera.snap`, `camera.list`, `device.status`, `device.info` (`IOS/Services/OpenClaw/OpenClawCommandRouter.swift:23-31`).)

```kotlin
fun ContentScope.openClawCard(c: DisplayCard.OpenClaw, s: (Int) -> String, on: (DisplayAction) -> Unit) {
    val pages = paginate(c.replyText, maxChars = 280)
    val page = c.page.coerceIn(0, pages.lastIndex)
    flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 24, background = FlexBoxBackground.CARD, gap = 8) {
            flexBox(direction = Direction.ROW, gap = 8, crossAlignment = Alignment.CENTER) {
                icon(name = IconName.CODE, style = IconStyle.FILLED)
                text("OpenClaw", style = TextStyle.HEADING)
                if (!c.isFinal) icon(name = IconName.THREE_DOTS_HORIZONTAL, style = IconStyle.OUTLINE)
            }
            c.userText?.let { text(it.take(120), style = TextStyle.META, color = TextColor.SECONDARY) }
            text(if (pages[page].isBlank()) "…" else pages[page], style = TextStyle.BODY)
            if (pages.size > 1) text("${page + 1}/${pages.size}", style = TextStyle.META, color = TextColor.SECONDARY)
        }
        flexBox(direction = Direction.ROW, gap = 8, alignment = Alignment.CENTER, crossAlignment = Alignment.CENTER) {
            buttonGroup {
                if (page > 0) button(s(R.string.display_prev), iconName = IconName.TRIANGLE_LEFT_VERTICAL_LINE, onClick = { on(DisplayAction.Page(c.copy(page = page - 1))) })
                if (page < pages.lastIndex) button(s(R.string.display_next), iconName = IconName.TRIANGLE_RIGHT_VERTICAL_LINE, onClick = { on(DisplayAction.Page(c.copy(page = page + 1))) })
                button(s(R.string.display_snap), style = ButtonStyle.SECONDARY, iconName = IconName.VIDEO_CAMERA, onClick = { on(DisplayAction.OpenClawSnap) })   // capture + chat.send with attachment
                button(s(R.string.display_done), iconName = IconName.CHECKMARK, onClick = { on(DisplayAction.BackToMenu) })
            }
        }
    }
}
```
While `isFinal == false`, coalesce partial events (Section 8.4) and only show the last ~320 chars; on final, reset `page = 0`.

**Pagination helper (pure Kotlin, unit-testable):**

```kotlin
fun paginate(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val out = mutableListOf<String>(); var start = 0
    while (start < text.length) {
        var end = minOf(start + maxChars, text.length)
        if (end < text.length) {
            val cut = text.lastIndexOfAny(charArrayOf('。', '！', '？', '.', '!', '?', '\n', '，', ',', ' '), end - 1)
            if (cut > start + maxChars / 2) end = cut + 1
        }
        out += text.substring(start, end).trim(); start = end
    }
    return out
}
```
`maxChars = 280` is a heuristic for BODY text on a 600×600 panel (docs give no limit; vertical scroll still works if it overflows).

**`DisplayAction` routing** (from `GlassesDisplayManager.onAction`, wired in `MainActivity`/a small `GlassesActionRouter`): `StartLiveAI` → navigate to `Screen.LiveAI` (`Navigation.kt:136-144`) after the same checks as `HomeScreen.kt:283-295`; `StartQuickVision` → start `QuickVisionService`; `StartLeanEat` → navigate `Screen.LeanEat`; `EndLiveAI` → `OmniRealtimeViewModel.disconnect()`; `Page(card)` → `displayManager.show(card)`; `BackToMenu` → `displayManager.show(Status(...))`; `OpenClawSnap` → capture via `camera.stream.capturePhoto()` and `chat.send`.

### 8.6 Settings, strings, phone UI

Settings key (`APP/utils/APIKeyManager.kt:30-34` pattern):
```kotlin
private const val KEY_GLASSES_DISPLAY_ENABLED = "glasses_display_enabled"   // Boolean, default true
fun setGlassesDisplayEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean(KEY_GLASSES_DISPLAY_ENABLED, enabled).apply()
fun isGlassesDisplayEnabled(): Boolean = sharedPreferences.getBoolean(KEY_GLASSES_DISPLAY_ENABLED, true)
```

New string resources (`ANDROID/app/src/main/res/values/strings.xml` and `values-zh-rCN/strings.xml`, 385 lines each; existing keys reused above: `feature_liveai_title`, `feature_quickvision_title` (快速识图), `lean_eat`, `liveai_listening` (正在听…), `liveai_speaking` (AI 回复中…), `processing` (处理中…), `connecting` (连接中…), `liveai_connected`, `end`, `leaneat_*` (`values/strings.xml:80-92`), `settings_device` (设备), `settings_sdk_version` (SDK 版本)):

| key | en | zh-CN |
|---|---|---|
| `display_section` | Glasses Display | 眼镜显示 |
| `display_enabled` | Show results on glasses display | 在眼镜屏幕上显示结果 |
| `display_enabled_desc` | Meta Ray-Ban Display only | 仅支持 Meta Ray-Ban Display |
| `display_status_unsupported` | Not supported by this device | 当前设备不支持显示 |
| `display_status_preparing` | Preparing display… | 正在准备显示… |
| `display_status_ready` | Display ready | 显示已就绪 |
| `display_status_needs_api_key` | Set an API key in Settings | 请先在设置中配置 API Key |
| `display_update_firmware` | Update firmware | 更新眼镜固件 |
| `display_update_dat_app` | Update app on glasses | 更新眼镜上的应用 |
| `display_compat_dat_app_message` | The app on your glasses needs an update before the display can start. | 眼镜上的应用需要更新后才能使用显示功能。 |
| `display_prev` / `display_next` / `display_done` / `display_again` / `display_snap` | Prev / Next / Done / Again / Snap | 上一页 / 下一页 / 完成 / 再来一次 / 拍照 |

Phone UI:
- `HomeScreen.DeviceStatusCard` (`APP/ui/screens/HomeScreen.kt:253-259`): add a display row bound to `GlassesDisplayViewModel.uiState.status` (Preparing / Ready / Unsupported) and the two update buttons when `isFirmwareUpdateRequired || isDatAppUpdateRequired` (port `UpdateActionsCard`, `DA-SRC/ui/ConnectScreen.kt:144-230`). `Wearables.openFirmwareUpdate/openDATGlassesAppUpdate` need an `Activity` — pass it from `MainActivity` like `onRequestWearablesPermission` (`Navigation.kt:53-56`).
- `SettingsScreen`: new `SettingsSection(title = stringResource(R.string.display_section))` after the Quick Vision section (`SettingsScreen.kt:316`) with the toggle; bump `settings_sdk_version` display (Settings currently shows app version "1.5.0" at `SettingsScreen.kt:423`; iOS 2.0.0 shows SDK 0.5.0 at `IOS/Views/SettingsView.swift`).
- `MainActivity.initializeSDK()` (`APP/MainActivity.kt:118-127`): `Wearables.initialize(this).onFailure { ... }`; construct `GlassesSessionManager.getInstance(this)` + `GlassesDisplayManager` and register `onAction`.

### 8.7 Testing plan

1. **Unit** (JVM): `paginate`, `DisplayCard` derivation from `FoodNutritionResponse`/transcripts, `GlassesDisplayStatus` derivation.
2. **Instrumentation with MockDeviceKit** (`mwdat-mockdevice` 0.9.0, `MockDeviceKitConfig(initiallyRegistered = true)`, `pairGlasses(GlassesModel.RAYBAN_META)`, `device.powerOn(); device.don()`; base class `DOCS:2429-2470`): verify shared-session refactor — `ensureSession()` → `STARTED`, `addCamera` → frames, `QuickVisionService` capture through the shared session, `isDisplayCapableDevice == false` hides display UI and never calls `addDisplay`.
3. **Debug preview screen**: render `DisplayCard` on the phone (600×600 black box, HEADING≈28sp/BODY≈20sp/META≈16sp approximations) to iterate layouts; toggled from Settings in debug builds.
4. **Hardware checklist** (Meta Ray-Ban Display, FW ≥ V125, Meta AI ≥ V282, DAT Wearables App installed, Developer Mode): registration; `addDisplay` after `STARTED`; `DisplayState.STARTED`; each card renders; tap routing for each button; camera + display simultaneously (frame rate impact, `RENDERING_FAILED` rate); display sleep/wake resend; back gesture at L0 → observe which state/error fires; `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` path with an outdated glasses app; hinge close → `STOPPED` → recovery by new session.

### 8.8 Delivery order

1. SDK 0.9.0 + toolchain upgrade; migrate `WearablesViewModel`, `QuickVisionService`, RTMP code to `DeviceSession`/`addCamera` (Section 2.2). Ship as an internal build validated with MockDeviceKit and Ray-Ban Meta hardware.
2. `GlassesSessionManager` shared session; feature screens switch to it.
3. `GlassesDisplayManager` + `DisplayCard` + Status/Notice cards; Home/Settings UI; update-required flows.
4. Live AI, Quick Vision, LeanEat cards.
5. OpenClaw port (WebSocket gateway, `chat.send`, node commands) and its card.
6. Version bump to 2.0.0 / SDK 0.9.0 in Settings and READMEs (`README.md:174` currently states Android lacks Display/OpenClaw).

---

## 9. Risks, unknowns, open decisions

Risks / unknowns:
- **Toolchain**: 0.9.0 samples use Kotlin 2.2.21 / AGP 8.11.1 / compileSdk 36 / JVM 17 (`DA/gradle/libs.versions.toml:2-3`, `DA/app/build.gradle.kts:28,52-60`); TurboMeta is on Kotlin 2.0.0 / AGP 8.6.0 / JVM 1.8 (`ANDROID/gradle/libs.versions.toml:2-3`, `ANDROID/app/build.gradle.kts:46-53`). Expect Kotlin-metadata and class-file-version errors unless upgraded; upgrading also forces Compose BOM/lifecycle bumps and may affect the RTMP (`rtplibrary 2.2.6`) and Porcupine deps.
- **`image()` signature**: the reference page shows `bitmap: Bitmap?` without a default while the sample calls `image(uri = …)` without `bitmap` (`DA-SRC/display/DisplayViewModel.kt:671-673`); confirm against the 0.9.0 AAR (`CHANGELOG:22` calls the change binary-breaking).
- **`onClick` type**: reference renders `() -> Unit? = null`; the sample passes plain lambdas; treat as `(() -> Unit)?`.
- **Bluetooth bandwidth with camera + display** — undocumented; measure.
- **Callback thread** for `onClick` — undocumented; assume non-main.
- **Back-gesture semantics** at L0 ("ends the display session", `DOCS:736`) — whether the `DeviceSession` also stops is not stated; handle both `DisplayState.STOPPED` and `DeviceSessionState.STOPPED`/`SESSION_ENDED_BY_DEVICE`.
- **Remote `image(uri)`/video fetch path** (glasses vs phone network) — undocumented; use bitmaps for local content.
- **No display mock**: all rendering verification requires hardware; the docs promise a future display mock enum (`REF GlassesModel`).
- `DOCS:1630` still lists `DisplayError.CAPABILITY_DENIED` although 0.9.0 removed it (`CHANGELOG:32`); use an `else` branch in `when`.
- `AGENTS:957` (`DAM_ENABLED`) is stale for 0.9.0 (`CHANGELOG:29`).
- Text/character and send-rate limits are undocumented; pagination/coalescing values in Section 8 are heuristics.

Decisions for the product owner:
1. Session lifetime: per-feature (A) vs persistent "glasses display" session with an always-on L0 menu (B). (B) blocks Meta AI/other apps' device sessions while active (`DOCS:72`).
2. Device selection: keep `AutoDeviceSelector` (display auto-attached when the active device is display-capable) vs a device picker with `SpecificDeviceSelector` like the sample (`DisplayViewModel.kt:220`).
3. Whether Quick Vision results should be shown on the glasses when triggered by the wake word while the phone app is backgrounded (needs the shared session to be created from the foreground service).
4. Whether to lower `VideoQuality` automatically to `LOW` while a display card is on screen.
5. OpenClaw Android port scope (node mode with Ed25519 identity as on iOS, or chat-only) before its card is built.
6. Whether display support ships only in a 2.0.0 Android release together with the SDK 0.9.0 migration (recommended) or the migration ships first as 1.6.0.
