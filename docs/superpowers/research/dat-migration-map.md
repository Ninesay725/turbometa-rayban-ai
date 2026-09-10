# DAT Android 0.4.0 -> 0.9.0 migration map for `android/` (com.smartview.glassai)

Repo: `D:/Coding/Workspaces/Android/turbometa-rayban-ai` (paths below are relative to `android/` unless stated).
Prepared 2026-09-10 from: upstream CHANGELOG.md (0.5.0..0.9.0), the 0.9.0 CameraAccess/DisplayAccess samples, the 0.9.0 plugin skills, the docs export (`dat-llms-full.txt`), the live 0.9 API reference pages (wearables.developer.meta.com/docs/reference/android/dat/latest/*), and `javap` dumps of the **0.4.0 AARs that are already in the Gradle cache** (`~/.gradle/caches/modules-2/files-2.1/com.meta.wearable/{mwdat-core,mwdat-camera}/0.4.0`) for the authoritative "before" signatures.

---

## 0. TL;DR

1. Every streaming call site (`WearablesViewModel.startStream/stopStream/takePhoto`, `QuickVisionService.captureAndAnalyze`, `RTMPStreamingViewModel.startCameraStream/stopStreaming`) must move from the one-shot `Wearables.startStreamSession(context, selector, config): StreamSession` to the explicit 0.9.0 lifecycle: `Wearables.createSession(selector): DatResult<DeviceSession, DeviceSessionError>` -> `session.start()` -> wait `session.state == DeviceSessionState.STARTED` -> `session.addCamera(StreamConfiguration): DatResult<Camera, DeviceSessionError>` -> `camera.stream.start(): DatResult<Unit, StreamError>` -> collect `camera.stream.videoStream` / `camera.stream.state` -> `camera.stop()` (+ `session.stop()`).
2. `RegistrationState` is now a plain enum (`UNAVAILABLE, AVAILABLE, UNREGISTERING, REGISTERED, REGISTERING`); errors moved to `Wearables.registrationErrorStream: Flow<RegistrationError>`. `WearablesViewModel.kt:74,147-165,472` change.
3. `DeviceSelector.activeDevice(Wearables.devices)` (Flow) is gone; use `deviceSelector.activeDeviceFlow(): Flow<DeviceIdentifier?>` or sync `activeDevice(): DeviceIdentifier?` (`WearablesViewModel.kt:126`, `QuickVisionService.kt:210`).
4. Latent bug independent of 0.9.0: `Wearables.startRegistration(getApplication())` / `startUnregistration(getApplication())` (`WearablesViewModel.kt:192,197`) compile via a Kotlin intersection type but the compiled class (`app/build/tmp/kotlin-classes/debug/com/smartview/glassai/viewmodels/WearablesViewModel.class`) contains `checkcast android/app/Activity` on an `Application` -> `ClassCastException` at runtime. 0.4.0 and 0.9.0 both require `Activity`. Fix as part of this migration by threading an `Activity` from Compose (`LocalActivity.current`).
5. `capturePhoto()` changes from `kotlin.Result<PhotoData>` to `DatResult<PhotoData, CaptureError>` (`WearablesViewModel.kt:382-399`).
6. `StreamSessionState` -> `StreamState` (adds `PAUSED`); rename imports in `WearablesViewModel.kt:15`, `QuickVisionService.kt:26`, `RTMPStreamingViewModel.kt:15`, `RTMPStreamingScreen.kt:36`. Watch out: the app's own nested `WearablesViewModel.StreamState` sealed class (`WearablesViewModel.kt:63-68`) collides with the SDK's `com.meta.wearable.dat.camera.types.StreamState` -> import with an alias.
7. Manifest/Gradle: add `com.meta.wearable.mwdat.CLIENT_TOKEN`, switch both meta-data values to `${mwdat_application_id}` / `${mwdat_client_token}` manifest placeholders fed from `local.properties` (mirrors iOS commit 78ea2f3 which moved `MetaAppID`/`ClientToken` in `CameraAccess/Info.plist` to `$(META_APP_ID)`/`$(CLIENT_TOKEN)` build settings). No `DAM_ENABLED` entry exists in the app manifest, so nothing to delete. Optional `CRASH_REPORTING_OPT_OUT`.
8. 0.9.0 sample toolchain: AGP 8.11.1, Kotlin 2.2.21, compileSdk/targetSdk 36, minSdk 31, JDK/JVM 17, Gradle 8.14.1. App: AGP 8.6.0, Kotlin 2.0.0, compileSdk 35, targetSdk 34, minSdk 31, Java 1.8, Gradle 8.7. minSdk is already satisfied.
9. Only one active `DeviceSession` per device is allowed (`DeviceSessionError.SESSION_ALREADY_EXISTS`). Three components in this app independently create camera sessions; they must share or serialize sessions (see section 7).

---

## 1. Upstream CHANGELOG: every `[API]` change from 0.5.0 to 0.9.0

Source: `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/CHANGELOG.md`. Legend: **HIT** = this app references it; **n/a** = not referenced by this app.

### 0.5.0 (2026-03-11)
| Change | Relevance |
|---|---|
| `StreamSession.capturePhoto()` now returns `DatResult<PhotoData, CaptureError>` instead of `Result<PhotoData>` | **HIT** `WearablesViewModel.kt:382-399` |
| New sealed `CaptureError` (`DeviceDisconnected`, `NotStreaming`, `CaptureInProgress`, `CaptureFailed`) | **HIT** (handle in `takePhoto`) |
| `Device.linkState: LinkState` (`CONNECTING`, `CONNECTED`, `DISCONNECTED`) replaces boolean `available` | n/a (app never reads device metadata); the docs-export snippet `metadata.available` (`dat-llms-full.txt:1900-1905`) is stale |
| High resolution 720x1280 requestable | informational |

### 0.6.0 (2026-04-15)
| Change | Relevance |
|---|---|
| Session-based device management: `Wearables.createSession(deviceSelector)`, `Session` with `start/stop`, state/errors; camera streaming becomes a `Capability` (`Stream`) via `Session.addStream(config)` / `removeStream` | **HIT** (all 3 stream call sites) -- superseded again in 0.7.0/0.9.0 |
| Removed old session model API incl. old `DeviceSession` class | n/a |
| `DeviceMetadata` renamed to `Device` | n/a directly; matters for the `AutoDeviceSelector` filter type |
| `DeviceSelector.activeDevice` returns `DeviceIdentifier` directly; `activeDeviceFlow` for Flow | **HIT** `WearablesViewModel.kt:126`, `QuickVisionService.kt:210` |
| `StreamConfiguration.compressVideo` + `VideoFrame.isCompressed` | **HIT** (defensive guard in frame handlers) |
| MockDeviceKit: `MockDeviceKitConfig(initiallyRegistered, initialPermissionsGranted)`, `MockPermissions.set/setRequestResult`, `MockDeviceKitInterface.enable(config)/disable/isEnabled/permissions`; `reset` removed; `MockCameraKit.setCameraFeed(CameraFacing)`; `MockDisplaylessGlassesServices`; `getCameraKit` removed | test path (section 11) |
| `ClassCastException` fix when `APPLICATION_ID` meta-data is not parsed as String | **HIT**: the app's manifest uses `android:value="0"` (`AndroidManifest.xml:36`) -- fixed upstream, but move to placeholders anyway |

### 0.7.0 (2026-05-14)
| Change | Relevance |
|---|---|
| `Session` -> `DeviceSession`; `SessionError` -> `DeviceSessionError`; `SessionState` -> `DeviceSessionState` (`IDLE, STARTING, STARTED, PAUSED, STOPPING, STOPPED`); `StreamSession` -> `Stream`; `StreamSessionState` -> `StreamState`; `Wearables.startStreamSession(...)` **removed**; `Wearables.getDeviceSessionState()` removed | **HIT** everywhere |
| `RegistrationState` reshaped from sealed class (`Available, Registered, Registering, Unavailable, Unregistering`) to enum (`AVAILABLE, REGISTERED, REGISTERING, UNAVAILABLE, UNREGISTERING`); error payload moved to `Wearables.registrationErrorStream` | **HIT** `WearablesViewModel.kt:74,147-165,472` |
| `Stream.start()` explicit + `Stream.errorStream` | **HIT** |
| `VideoFrame.isCodecConfig` (constructor changed) | **HIT** (guard) |
| `DeviceSession.errors: SharedFlow<DeviceSessionError>`; new error cases `BATTERY_CRITICAL, PEAK_POWER_SHUTDOWN, THERMAL_CRITICAL, THERMAL_EMERGENCY, DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` | **HIT** (observe) |
| `StreamError` cases `BATTERY_LOW, CRITICAL_STREAM_ERROR, PEAK_POWER_LIMIT, THERMAL_EMERGENCY, THERMAL_HOT, TIMEOUT` | **HIT** (observe) |
| DAM (`DAM_ENABLED` meta-data opt-in), `Wearables.openDATGlassesAppUpdate(activity)`, `Wearables.openFirmwareUpdate(activity)`, `Wearables.getDeviceState(id): StateFlow<DeviceState>`, `ThermalLevel`, `Wearables.isDevMode`, `NavigationError` | optional |
| `DeviceType.isDisplayCapable`, `Device.isDisplayCapable()`, `SpecificDeviceSelector.activeDevice()` | optional |
| Display capability (`mwdat-display`) | n/a unless Display is added |
| MockDeviceKit captouch simulation (`services.captouch.tap()/tapAndHold()`) | test path |
| Fixes: `checkPermission` double-resume crash; `PermissionsSession` stale state after BT reconnect; **photo capture timeout added**; `VideoFrame` buffer lifecycle for codec-config frames; leaked `ServiceConnection` in `ACDCRegistrationService` | behavior (section 10) |

### 0.8.0 (2026-06-25)
| Change | Relevance |
|---|---|
| `Stream`/`Display` are `Closeable` with `stop()`; `Capability`/`BaseCapability` removed | **HIT** (`close()` still exists; delegates to `stop()`) |
| `StreamState.PAUSED` | **HIT** (`else ->` branches now receive it) |
| `AutoDeviceSelector.activeDevice()` sync | **HIT** `QuickVisionService.kt:210` |
| `DeviceType.META_GLASSES`; Meta Glasses support | informational |
| `Display.clearDisplay()` | n/a |
| MockDeviceKit: `pairGlasses(model): DatResult<MockGlasses, MockDeviceKitError>` replaces `pairRaybanMeta()`; `GlassesModel` enum; `MockDeviceKitError.NotEnabled`; `MockDisplaylessGlasses` -> `MockGlasses`, `MockDisplaylessGlassesServices` -> `MockGlassesServices`; `MockRaybanMeta` removed | test path |
| Fixes: D8 companion-object warnings; Play Store `fb` pseudo-locale warning | informational |

### 0.9.0 (2026-08-03)
| Change | Relevance |
|---|---|
| Consolidated `Camera` capability: `DeviceSession.addCamera(streamConfiguration): DatResult<Camera, DeviceSessionError>`; `Camera.stream: Stream`; `Camera.stop()`; `CameraState` (`STARTING, STARTED, STOPPING, STOPPED`); `DeviceSession.removeCamera()` | **HIT** (this is the API to target) |
| **Removed** `DeviceSession.addStream(...)` / `removeStream()`; `Stream` can no longer be added directly | **HIT** (do not target the 0.6-0.8 `addStream` API) |
| `DatResult` is now a Java-visible reference type instead of a Kotlin inline value class | source-compatible for Kotlin; Java callers now see un-mangled names |
| DAM always enabled; `com.meta.wearable.mwdat.DAM_ENABLED` ignored | app never declared it; nothing to do |
| Crash-reporting opt-out meta-data `com.meta.wearable.mwdat.CRASH_REPORTING_OPT_OUT` = `true` | optional |
| Removed enum cases: `StreamError.THERMAL_EMERGENCY`, `RegistrationError.INCOMPATIBLE_SDK_LEVEL`, `DisplayError.CAPABILITY_DENIED`, `DeviceSessionError.DEVICE_POWERED_OFF`, `DeviceSessionError.NOT_INITIALIZED` | n/a (not referenced) |
| Display: `buttonGroup`, `image(bitmap=...)` (binary-breaking signature), tap routing, `flexBox`/`video` no longer return `DisplayComponent`, `VideoScope` builder removed | n/a |
| Fixes: MockDevice phone-camera stream no longer stops after a few seconds; `mwdat-mockdevice` ships `-dontwarn` consumer ProGuard rules for apps that use mockdevice without camera | test path / R8 |

---

## 2. Authoritative type map: 0.4.0 (javap of cached AAR) -> 0.9.0 (reference + sample)

### 2.1 `com.meta.wearable.dat.core.Wearables`
| 0.4.0 (javap `mwdat-core-0.4.0-api.jar`) | 0.9.0 (reference `com_meta_wearable_dat_core_wearables`) |
|---|---|
| `fun initialize(context: Context): DatResult<Unit, WearablesError>` (compiled as `initialize-QAxQwbw` because `DatResult` was a value class) | `fun initialize(context: Context): DatResult<Unit, WearablesError>` (plain class). Errors: `WearablesError.NOT_INITIALIZED`, `ALREADY_INITIALIZED` |
| `val registrationState: StateFlow<RegistrationState>` (sealed class) | `val registrationState: StateFlow<RegistrationState>` (enum) |
| -- | `val registrationErrorStream: Flow<RegistrationError>` -- hot shared flow, **no replay** (subscribe before calling `startRegistration`) |
| `val devices: StateFlow<Set<DeviceIdentifier>>` | same |
| `val devicesMetadata: Map<DeviceIdentifier, StateFlow<DeviceMetadata>>` | `val devicesMetadata: Map<DeviceIdentifier, StateFlow<Device>>` (sample: `Wearables.devicesMetadata[deviceId]?.collect { metadata -> metadata.compatibility; metadata.name }` -- `samples/CameraAccess/.../wearables/WearablesViewModel.kt:93-98`) |
| `fun getDeviceSessionState(id): StateFlow<SessionState>` | removed (observe `DeviceSession.state`) |
| `fun startRegistration(activity: Activity)` / `fun startUnregistration(activity: Activity)` | same (`Activity`, not `Context`) |
| `suspend fun checkPermissionStatus(permission: Permission): DatResult<PermissionStatus, PermissionError>` | same |
| `class RequestPermissionContract : ActivityResultContract<Permission, DatResult<PermissionStatus, PermissionError>>` | same |
| -- | `fun createSession(deviceSelector: DeviceSelector): DatResult<DeviceSession, DeviceSessionError>` -- resolves eagerly against the current devices snapshot; `NO_ELIGIBLE_DEVICE` if no connected compatible device matches; `SESSION_ALREADY_EXISTS` if an active session exists for that device (singleton-per-device); returned session is `IDLE` until `start()` |
| -- | `fun openFirmwareUpdate(activity: Activity): DatResult<Unit, NavigationError>`; `fun openDATGlassesAppUpdate(activity: Activity): DatResult<Unit, NavigationError>`; `fun getDeviceState(id: DeviceIdentifier): StateFlow<DeviceState>` (`DeviceState(thermalLevel: ThermalLevel = ThermalLevel.UNKNOWN)`); `val isDevMode: Boolean`; `fun reset()` |

### 2.2 Registration / permission types
| 0.4.0 | 0.9.0 |
|---|---|
| `abstract class RegistrationState(val error: RegistrationError?)` with subclasses `Available`, `Registered`, `Registering`, `Unavailable`, `Unregistering` (data classes with optional `error`; no-arg ctors, e.g. `RegistrationState.Unavailable()`) | `enum class RegistrationState { UNAVAILABLE, AVAILABLE, UNREGISTERING, REGISTERED, REGISTERING }` (no `description`, no payload) |
| `enum RegistrationError { INCOMPATIBLE_SDK_LEVEL, ALREADY_REGISTERED, ALREADY_UNREGISTERED, FAILED_TO_REGISTER, FAILED_TO_UNREGISTER, META_AI_NOT_INSTALLED, UNKNOWN }` | `{ ALREADY_REGISTERED, ALREADY_UNREGISTERED, FAILED_TO_REGISTER, FAILED_TO_UNREGISTER, META_AI_NOT_INSTALLED, UNKNOWN }` (+ `description`, `getLocalizedDescription(context)`) |
| `enum Permission { CAMERA }` | `Permission.CAMERA` (and `Permission.MICROPHONE`, referenced by the `MockDeviceKitConfig` docs) |
| `interface PermissionStatus` with objects `Granted`, `Denied` | same (`PermissionStatus.Granted` / `PermissionStatus.Denied`) |
| `enum PermissionError { NO_DEVICE, NO_DEVICE_WITH_CONNECTION, META_AI_NOT_INSTALLED, CONNECTION_ERROR, REQUEST_IN_PROGRESS, REQUEST_TIMEOUT, INTERNAL_ERROR }` | same set |
| `enum WearablesError { NOT_INITIALIZED, ALREADY_INITIALIZED }` | same |

### 2.3 `DatResult<T, E : DatError>`
0.4.0: Kotlin inline value class (`Serializable`) with `onSuccess`, `onFailure((E, Throwable?) -> Unit)`, `onFailure((Throwable) -> Unit)`, `fold`, `getOrNull`, `getOrElse`, `getOrDefault`, `getOrThrow`, `errorOrNull`, `exceptionOrNull`, `map`, `recover`, `onSpecificError`, `toResult`.

0.9.0: **regular class** `class DatResult<T, E : DatError> : Serializable`, same member set: `inline fun onSuccess(action: (value: T) -> Unit): DatResult<T, E>`, `inline fun onFailure(action: (error: E, cause: Throwable?) -> Unit)`, `inline fun onFailure(action: (exception: Throwable) -> Unit)`, `fun <R> fold(onSuccess: (T) -> R, onFailure: (error: E, cause: Throwable?) -> R): R`, `getOrNull(): T?`, `getOrElse(onFailure: (Throwable) -> T): T`, `getOrDefault(defaultValue: T): T`, `getOrThrow(): T`, `errorOrNull(): E?`, `exceptionOrNull()`, `isSuccess`, `isFailure`, `map`, `recover`, `toResult(): Result<T>`, companion `success(value)`, `failure(error, cause)`, `wrap(result)`.

Note: a no-parameter lambda `onFailure { ... }` resolves to the single-arg `(Throwable) -> Unit` overload (implicit `it`), so it still compiles; prefer `onFailure { error, _ -> error.description }` to get the typed error.

### 2.4 Device selection and device metadata
| 0.4.0 | 0.9.0 |
|---|---|
| `interface DeviceSelector { fun activeDevice(devices: Flow<Set<DeviceIdentifier>>): Flow<DeviceIdentifier?> }` | `interface DeviceSelector { fun activeDevice(): DeviceIdentifier?; fun activeDeviceFlow(): Flow<DeviceIdentifier?> }` |
| `class AutoDeviceSelector(deviceRanking: Comparator<DeviceMetadata> = ..., filter: (DeviceMetadata) -> Boolean = ...)`; `AutoDeviceSelector()` | `class AutoDeviceSelector(deviceRanking: Comparator<Device> = ..., filter: (Device) -> Boolean = { true })` -- default already restricts to connected + compatible devices; `AutoDeviceSelector()` no-arg form used by the sample (`WearablesViewModel.kt:42`) |
| -- | `class SpecificDeviceSelector(selectedDevice: DeviceIdentifier)` with `activeDevice()` / `activeDeviceFlow()` |
| `data class DeviceMetadata(name: String, available: Boolean, deviceType: DeviceType, firmwareInfo: String?, compatibility: DeviceCompatibility)` | `data class Device(name: String, linkState: LinkState = DISCONNECTED, deviceType: DeviceType = UNKNOWN, firmwareInfo: String? = null, compatibility: DeviceCompatibility = COMPATIBLE)` + `fun isDisplayCapable(): Boolean` |
| `enum DeviceType { UNKNOWN, RAYBAN_META, OAKLEY_META_HSTN, OAKLEY_META_VANGUARD, META_RAYBAN_DISPLAY }` + `description` | `{ UNKNOWN, RAYBAN_META, OAKLEY_META_HSTN, OAKLEY_META_VANGUARD, META_RAYBAN_DISPLAY, RAYBAN_META_OPTICS, META_GLASSES }` + `val description: String`, `val isDisplayCapable: Boolean` |
| -- | `enum LinkState { DISCONNECTED, CONNECTING, CONNECTED }`; `enum DeviceCompatibility { UNDEFINED, COMPATIBLE, DEVICE_UPDATE_REQUIRED, SDK_UPDATE_REQUIRED }` |
| `class DeviceIdentifier(identifier: String)` | same |

### 2.5 Session (new in 0.6/0.7, current shape in 0.9.0)
`com.meta.wearable.dat.core.session.DeviceSession` (reference `com_meta_wearable_dat_core_session_devicesession`):
- `val state: StateFlow<DeviceSessionState>` -- `IDLE -> STARTING -> STARTED -> STOPPING -> STOPPED`, may go `PAUSED`.
- `val errors: SharedFlow<DeviceSessionError>`.
- `fun start()` -- sync fire-and-forget; no-op unless `IDLE`.
- `fun stop()` -- terminates the session and **all attached capabilities**; no-op if already stopped.
- Extension (in `com.meta.wearable.dat.camera`, import `com.meta.wearable.dat.camera.addCamera`): `fun DeviceSession.addCamera(streamConfiguration: StreamConfiguration = StreamConfiguration()): DatResult<Camera, DeviceSessionError>` -- one camera per session, **must be added after `STARTED`** (otherwise `SESSION_IDLE`; second add -> `CAPABILITY_ALREADY_ADDED`).
- `fun DeviceSession.removeCamera(): DatResult<Unit, DeviceSessionError>`.
- `enum DeviceSessionState { IDLE, STARTING, STARTED, PAUSED, STOPPING, STOPPED }` -- **`STOPPED` is terminal; the session cannot be restarted** (create a new one).
- `enum DeviceSessionError { CAPABILITY_DENIED, NO_ELIGIBLE_DEVICE, SESSION_ALREADY_STOPPED, SESSION_IDLE, CAPABILITY_ALREADY_ADDED, CAPABILITY_NOT_FOUND, DEVICE_DISCONNECTED, SESSION_ENDED_BY_DEVICE, SESSION_ALREADY_EXISTS, THERMAL_CRITICAL, THERMAL_EMERGENCY, PEAK_POWER_SHUTDOWN, BATTERY_CRITICAL, DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, DWA_UNAVAILABLE, UNEXPECTED_ERROR }` (+ `description`, `getLocalizedDescription(context)`).

### 2.6 Camera / Stream
| 0.4.0 (javap `mwdat-camera-0.4.0-api.jar`) | 0.9.0 |
|---|---|
| `fun Wearables.startStreamSession(context: Context, deviceSelector: DeviceSelector, streamConfiguration: StreamConfiguration = ...): StreamSession` (top-level in `com.meta.wearable.dat.camera.StreamSessionKt`) | **removed** |
| `interface StreamSession { val state: StateFlow<StreamSessionState>; val videoStream: Flow<VideoFrame>; suspend fun capturePhoto(): Result<PhotoData>; fun close() }` | `interface Camera : Closeable { val state: StateFlow<CameraState>; val stream: Stream; fun stop(); override fun close() /* = stop() */ }` and `interface Stream : Closeable { val state: StateFlow<StreamState>; val videoStream: Flow<VideoFrame>; val errorStream: Flow<StreamError>; fun start(): DatResult<Unit, StreamError>; fun stop(); override fun close(); suspend fun capturePhoto(): DatResult<PhotoData, CaptureError> }` |
| `enum StreamSessionState { STARTING, STARTED, STREAMING, STOPPING, STOPPED, CLOSED }` | `enum StreamState { STARTING, STARTED, STREAMING, STOPPING, STOPPED, PAUSED, CLOSED }` -- `PAUSED` = paused by device (single cap-touch tap), resumes automatically; `CLOSED` = final |
| -- | `enum CameraState { STARTING /* reserved, not emitted */, STARTED, STOPPING, STOPPED }` -- camera starts at `STARTED` once attached |
| `data class StreamConfiguration(videoQuality: VideoQuality, frameRate: Int)` (+ no-arg ctor) | `StreamConfiguration(videoQuality: VideoQuality = MEDIUM, frameRate: Int = 24, compressVideo: Boolean = false)`; frameRate accepted values `2, 7, 15, 24, 30`; `compressVideo=true` skips decoding and delivers HEVC buffers |
| `enum VideoQuality { HIGH, MEDIUM, LOW }` | same; sizes `HIGH 720x1280`, `MEDIUM 504x896`, `LOW 360x640` |
| `data class VideoFrame(buffer: ByteBuffer, width: Int, height: Int, presentationTimeUs: Long)` | `data class VideoFrame(buffer: ByteBuffer, width: Int, height: Int, presentationTimeUs: Long, isCompressed: Boolean = false, isCodecConfig: Boolean = false)` -- `isCompressed=false` => "decoded YUV pixel data" (pixel layout is not documented; the app treats it as I420 and the RTMP encoder is configured for `COLOR_FormatYUV420Planar`, `RTMPStreamingService.kt:188-196`) |
| `interface PhotoData` with `PhotoData.Bitmap(bitmap: android.graphics.Bitmap)` and `PhotoData.HEIC(data: ByteBuffer)` | same |
| -- | `interface CaptureError : DatError` with objects `DeviceDisconnected`, `NotStreaming`, `CaptureInProgress`, `CaptureFailed` |
| -- | `enum StreamError { STREAM_ERROR, CRITICAL_STREAM_ERROR, HINGE_CLOSED, PERMISSIONS_DENIED, THERMAL_HOT, BATTERY_LOW, PEAK_POWER_LIMIT, TIMEOUT }` |

Import changes:
```
- import com.meta.wearable.dat.camera.StreamSession
- import com.meta.wearable.dat.camera.startStreamSession
- import com.meta.wearable.dat.camera.types.StreamSessionState
+ import com.meta.wearable.dat.camera.Camera
+ import com.meta.wearable.dat.camera.Stream
+ import com.meta.wearable.dat.camera.addCamera          // extension on DeviceSession
+ import com.meta.wearable.dat.camera.types.StreamState
+ import com.meta.wearable.dat.camera.types.StreamError
+ import com.meta.wearable.dat.camera.types.CaptureError  // optional, for `when`
+ import com.meta.wearable.dat.core.session.DeviceSession
+ import com.meta.wearable.dat.core.session.DeviceSessionState
+ import com.meta.wearable.dat.core.types.DeviceSessionError
+ import com.meta.wearable.dat.core.types.RegistrationError
```

---

## 3. Inventory of DAT usages in the app (grep `com.meta.wearable` / `Wearables\.` under `app/src`)

| File | Lines | What |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | 34-36 | `<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="0"/>` (no `CLIENT_TOKEN`, no `DAM_ENABLED`) |
| `app/src/main/java/com/smartview/glassai/MainActivity.kt` | 16-18, 33-38, 43-77, 118-127 | imports; `PERMISSIONS`; `Wearables.RequestPermissionContract()` launcher + `requestWearablesPermission`; `Wearables.initialize(this)` |
| `.../viewmodels/WearablesViewModel.kt` | 12-25, 63-68, 74, 102-106, 126, 144-166, 171, 192, 197, 213-215, 245, 263-266, 284-334, 353, 382-399, 411-441, 472, 483 | the main integration (details in section 4) |
| `.../services/QuickVisionService.kt` | 23-30, 91-92, 175-183, 210, 224-251, 264, 324-347 | background wake-word capture flow |
| `.../viewmodels/RTMPStreamingViewModel.kt` | 12-20, 62-63, 70-73, 155-210, 228-260, 262-286, 305-326 | RTMP streaming |
| `.../ui/screens/RTMPStreamingScreen.kt` | 36, 52 | `import ...StreamSessionState`; `cameraState` collected (not otherwise used) |
| `.../ui/screens/HomeScreen.kt` | 34-36, 71-97, 168, 256-257 | `Wearables.checkPermissionStatus`; triggers `startDeviceSearch()` / `disconnect()` |
| `.../ui/navigation/Navigation.kt` | 18-19, 55 | type-only imports (`Permission`, `PermissionStatus`) |
| `.../ui/screens/LiveAIScreen.kt` | 36-37, 52 | type-only imports |
| `.../ui/screens/SimpleLiveStreamScreen.kt`, `QuickVisionScreen.kt`, `VisionScreen.kt`, `LeanEatScreen.kt` | (no SDK imports) | consume `WearablesViewModel.currentFrame/streamState/startStream/stopStream/takePhoto` |
| `gradle/libs.versions.toml` | 4, 24-26 | `mwdat = "0.4.0"`; `mwdat-core`, `mwdat-camera`, `mwdat-mockdevice` aliases |
| `app/build.gradle.kts` | 9-16, 46-53, 84-86 | compileSdk 35 / minSdk 31 / targetSdk 34; Java 1.8; `implementation(libs.mwdat.core)`, `implementation(libs.mwdat.camera)`, mockdevice commented out |
| `app/proguard-rules.pro` | 5-9 | `-keep class com.meta.** { *; }`, `-keep class com.facebook.** { *; }`, `-dontwarn` both |
| `settings.gradle.kts` | 33-39 | GitHub Packages Maven repo with `github_token` from `local.properties` / `GITHUB_TOKEN` (already correct for 0.9.0) |

No `androidTest`/`test` source sets exist (`app/src` contains only `main`). `TurboMetaApplication.kt` does not touch DAT.

---

## 4. Call-site migration table

Format: `file:line` | 0.4.0 code | 0.9.0 replacement | notes.

### 4.1 `MainActivity.kt`
| Site | 0.4.0 | 0.9.0 | Notes |
|---|---|---|---|
| `MainActivity.kt:16-18` | `import com.meta.wearable.dat.core.Wearables`, `...types.Permission`, `...types.PermissionStatus` | unchanged | |
| `MainActivity.kt:60-66` | `registerForActivityResult(Wearables.RequestPermissionContract()) { result -> result.getOrDefault(PermissionStatus.Denied) ... }` | unchanged | contract is still `ActivityResultContract<Permission, DatResult<PermissionStatus, PermissionError>>`; identical to sample `MainActivity.kt:64-69` |
| `MainActivity.kt:69-77` | `suspend fun requestWearablesPermission(permission: Permission): PermissionStatus` (Mutex + `suspendCancellableCoroutine`) | unchanged | identical to sample `MainActivity.kt:73-81` |
| `MainActivity.kt:123` | `Wearables.initialize(this)` (result discarded) | `Wearables.initialize(this).onFailure { error, _ -> wearablesViewModel.setError(error.getLocalizedDescription(this)) }` | 0.4.0 already returned `DatResult` (value class); the `sdkInitialized` flag (`:119-120`) avoids `ALREADY_INITIALIZED`. Must run before `wearablesViewModel.startMonitoring()` (`:126`) and before any `createSession` |
| `MainActivity.kt:33-38` | `PERMISSIONS = [BLUETOOTH, BLUETOOTH_CONNECT, INTERNET, RECORD_AUDIO]` | unchanged | sample requests `BLUETOOTH, BLUETOOTH_CONNECT, INTERNET` only (`MainActivity.kt:47`); add `CAMERA` only if you enable the MockDeviceKit phone-camera feed |

### 4.2 `viewmodels/WearablesViewModel.kt`
| Site | 0.4.0 | 0.9.0 | Notes |
|---|---|---|---|
| `:12` | `import com.meta.wearable.dat.camera.StreamSession` | `import com.meta.wearable.dat.camera.Camera` + `import com.meta.wearable.dat.camera.Stream` | |
| `:13` | `import com.meta.wearable.dat.camera.startStreamSession` | `import com.meta.wearable.dat.camera.addCamera` | extension on `DeviceSession` |
| `:15` | `import com.meta.wearable.dat.camera.types.StreamSessionState` | `import com.meta.wearable.dat.camera.types.StreamState as DatStreamState` | **alias required**: the file declares its own `sealed class StreamState` at `:63-68`, referenced from `LiveAIScreen.kt:178,266,581-582`, `SimpleLiveStreamScreen.kt:57`, `QuickVisionScreen.kt:179-190` |
| `:14,16,17,18,19,21-25` | `StreamConfiguration`, `VideoFrame`, `VideoQuality`, `Wearables`, `AutoDeviceSelector`, `DeviceSelector`, `DeviceIdentifier`, `Permission`, `PermissionStatus`, `RegistrationState` | unchanged imports; add `com.meta.wearable.dat.core.session.DeviceSession`, `...session.DeviceSessionState`, `...types.DeviceSessionError`, `...types.RegistrationError`, `com.meta.wearable.dat.camera.types.StreamError` | |
| `:74` | `MutableStateFlow<RegistrationState>(RegistrationState.Unavailable())` | `MutableStateFlow(RegistrationState.UNAVAILABLE)` | enum |
| `:102` | `val deviceSelector: DeviceSelector = AutoDeviceSelector()` | unchanged | 0.9.0 default filter = connected + compatible |
| `:106` | `private var streamSession: StreamSession? = null` | `private var session: DeviceSession? = null; private var camera: Camera? = null; private var stream: Stream? = null` | plus jobs: `sessionStateJob`, `sessionErrorJob`, `streamErrorJob` |
| `:126` | `deviceSelector.activeDevice(Wearables.devices).collect { device -> ... }` | `deviceSelector.activeDeviceFlow().collect { device -> ... }` | `Flow<DeviceIdentifier?>`; same null semantics |
| `:133` | `ConnectionState.Registered(device.toString())` | unchanged; optionally `Wearables.devicesMetadata[device]?.value?.name ?: device.identifier` | `Device.name` |
| `:144-166` | `Wearables.registrationState.collect { state -> when (state) { is RegistrationState.Registered -> ...; is RegistrationState.Unavailable -> ...; is RegistrationState.Available -> ...; is RegistrationState.Registering -> ...; is RegistrationState.Unregistering -> ... } }` | `when (state) { RegistrationState.REGISTERED -> ...; RegistrationState.UNAVAILABLE -> ...; RegistrationState.AVAILABLE -> ...; RegistrationState.REGISTERING -> ...; RegistrationState.UNREGISTERING -> ... }` | enum `when` is exhaustive without `else` |
| new (after `:167`) | -- | `viewModelScope.launch { Wearables.registrationErrorStream.collect { error -> setError(error.getLocalizedDescription(getApplication())) } }` | hot flow, no replay: start this collector in `startMonitoring()` before the user can tap Connect (pattern: `DisplayAccess/.../WearablesRepository.kt:58-62`) |
| `:171-174` | `Wearables.devices.collect { deviceSet -> _devices.value = deviceSet.toList() }` | unchanged | optional: per-device `Wearables.devicesMetadata[id]?.collect { it.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED }` -> offer `Wearables.openFirmwareUpdate(activity)` (sample `WearablesViewModel.kt:79-104,114-118`) |
| `:178-182` | `fun startDeviceSearch() { ...; startRegistration() }` | `fun startDeviceSearch(activity: Activity) { ...; startRegistration(activity) }` | callers: `HomeScreen.kt:168`, `HomeScreen.kt:256` |
| `:190-193` | `fun startRegistration() { Wearables.startRegistration(getApplication()) }` | `fun startRegistration(activity: Activity) { Wearables.startRegistration(activity) }` | **runtime bug today**: compiled bytecode is `getApplication()` -> `checkcast android/app/Activity` -> `invokevirtual Wearables.startRegistration(Landroid/app/Activity;)V` (see `app/build/tmp/kotlin-classes/debug/.../WearablesViewModel.class`), which throws `ClassCastException` (Application is not an Activity). Signature is `Activity` in both 0.4.0 (javap) and 0.9.0. The call was written for 0.3.0 (`Context`) in commit 863221a and the SDK was bumped to 0.4.0 in cacf273 |
| `:195-198` | `fun startUnregistration() { Wearables.startUnregistration(getApplication()) }` | `fun startUnregistration(activity: Activity) { Wearables.startUnregistration(activity) }` | same bug; caller `disconnect()` (`:200-207`) -> `HomeScreen.kt:257` |
| `:200-207` | `disconnect()`: `stopStream(); startUnregistration(); ...` | `disconnect(activity)`: `stopStream(); session?.stop(); startUnregistration(activity); ...` | |
| `:210-237` | `Wearables.checkPermissionStatus(permission)` -> `result.onFailure { error, _ -> ... return@launch }` / `result.getOrNull()` | unchanged | `onFailure` is `inline` in 0.9.0, so `return@launch` still works |
| `:244-247` | `checkCameraPermission()` | unchanged | |
| `:253-337` | `startStream()`: cancels jobs, `streamSession?.close()`, builds `StreamConfiguration(videoQuality = videoQuality, 24)`, `Wearables.startStreamSession(getApplication(), deviceSelector, config).also { streamSession = it }`, collects `session.videoStream` and `session.state` (`StreamSessionState.STREAMING/STOPPED/STARTING/else`) | rewrite (skeleton in section 5): `Wearables.createSession(deviceSelector)` -> observe `session.state`/`session.errors` -> `session.start()` -> on `DeviceSessionState.STARTED`: `session.addCamera(StreamConfiguration(videoQuality = videoQuality, frameRate = 24))` -> `camera.stream` -> subscribe `stream.videoStream`, `stream.state`, `stream.errorStream` **before** `stream.start()` -> `stream.start().onFailure { error, _ -> ... }` | `StreamConfiguration(videoQuality = ..., 24)` (named then positional) still compiles; prefer `frameRate = 24`. `StreamState.PAUSED` now reaches the `else ->` branch (`:327-330`) and maps to `Waiting` -- acceptable; consider a dedicated `Paused` UI ("Tap your glasses to resume", sample strings `paused_title/paused_subtitle`) |
| `:316-323` | `StreamSessionState.STOPPED -> if (prevState != null && prevState != STOPPED) stopStream()` | keep the "previous non-terminal state" guard; treat `StreamState.STOPPED` **and** `StreamState.CLOSED` as terminal (sample `CameraViewModel.kt:320-333` uses `hasBeenActive`) | `stream.state` is a `StateFlow` that replays `STOPPED` on subscribe before `start()` -- the existing guard already handles that |
| `:343-368` | `stopStream()`: `streamSession?.close()` | `camera?.stop()` (cascades to its `Stream` and detaches the capability -- required, otherwise the next `addCamera` returns `CAPABILITY_ALREADY_ADDED`), then `session?.stop()` (or keep the session alive across stream restarts, see section 7), null all refs, cancel `sessionStateJob/sessionErrorJob/streamErrorJob` too | `Stream.close()`/`Camera.close()` still exist and delegate to `stop()`, so `close()` compiles but stop **both** camera and session |
| `:374` | `if (_streamState.value != StreamState.Streaming)` | unchanged (app enum) | optionally also `stream?.state?.value == DatStreamState.STREAMING` |
| `:382-399` | `streamSession?.capturePhoto()?.onSuccess { photoData -> ... }?.onFailure { Log.e(...); _errorMessage.value = "Photo capture failed" }` (kotlin.Result) | `stream?.capturePhoto()?.onSuccess { photoData -> ... }?.onFailure { error, _ -> Log.e(TAG, "Photo capture failed: ${error.description}"); _errorMessage.value = error.getLocalizedDescription(getApplication()) }` | `DatResult<PhotoData, CaptureError>`; `when (error) { CaptureError.NotStreaming -> ...; CaptureError.CaptureInProgress -> ...; CaptureError.DeviceDisconnected -> ...; CaptureError.CaptureFailed -> ... }`. Only one capture in flight at a time. `PhotoData.Bitmap.bitmap` / `PhotoData.HEIC.data` unchanged (`:385-390`); the sample additionally applies EXIF orientation to HEIC (`CameraViewModel.kt:545-600`) |
| `:411-441` | `handleVideoFrame(videoFrame)`: `videoFrame.buffer/width/height`, I420 -> NV21 -> JPEG -> Bitmap | unchanged fields; add first line `if (videoFrame.isCompressed || videoFrame.isCodecConfig) return` | with default `compressVideo = false` frames are decoded YUV, as before |
| `:471-472` | `val isRegistered get() = _registrationState.value is RegistrationState.Registered` | `== RegistrationState.REGISTERED` | sample also counts `UNREGISTERING` as registered (`WearablesUiState.kt:34-36`) |
| `:478-491` | `onCleared()`: `stopStream()`; cancel `deviceSelectorJob` | also `session?.stop()`; cancel session/stream jobs | |

### 4.3 `services/QuickVisionService.kt`
| Site | 0.4.0 | 0.9.0 | Notes |
|---|---|---|---|
| `:23-26` | `import ...camera.StreamSession`, `...camera.startStreamSession`, `...camera.types.StreamConfiguration`, `...camera.types.StreamSessionState` | `Camera`, `Stream`, `addCamera`, `StreamConfiguration`, `StreamState` (+ `DeviceSession`, `DeviceSessionState`) | no local `StreamState` name clash in this file |
| `:91` | `private val deviceSelector = AutoDeviceSelector()` | unchanged | |
| `:92` | `private var streamSession: StreamSession? = null` | `private var session: DeviceSession? = null; private var camera: Camera? = null` | |
| `:175-183` | `cleanup()`: `streamSession?.close()` | `camera?.stop(); camera = null; session?.stop(); session = null` | |
| `:210` | `val hasDevice = deviceSelector.activeDevice(Wearables.devices).first() != null` | `val hasDevice = deviceSelector.activeDevice() != null` (sync) or `deviceSelector.activeDeviceFlow().first() != null` | `Wearables.initialize` must already have run in this process (it does: `MainActivity.initializeSDK`); if the service can start before the Activity, guard with `WearablesError.NOT_INITIALIZED` handling |
| `:224-228` | `Wearables.startStreamSession(this@QuickVisionService, deviceSelector, StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24)).also { streamSession = it }` | `val session = Wearables.createSession(deviceSelector).getOrElse { ... speak(no_device); finishService(); return@launch }`; `session.start()`; `withTimeoutOrNull(5_000) { session.state.first { it == DeviceSessionState.STARTED } }`; `val camera = session.addCamera(StreamConfiguration(VideoQuality.MEDIUM, 24)).getOrElse { ... }`; `camera.stream.start().onFailure { error, _ -> ... }` | handle `DeviceSessionError.SESSION_ALREADY_EXISTS` (another component holds the session) and `NO_ELIGIBLE_DEVICE` |
| `:234-241` | `session.state.collect { state -> if (state == StreamSessionState.STREAMING) isStreaming = true }` | `camera.stream.state.collect { state -> if (state == StreamState.STREAMING) isStreaming = true }` | |
| `:243-251` | `session.videoStream.collect { videoFrame -> ... }` | `camera.stream.videoStream.collect { videoFrame -> if (videoFrame.isCompressed || videoFrame.isCodecConfig) return@collect; ... }` | |
| `:254` | `val timeout = 8000L` (first frame) | consider 12-15 s: session start + camera attach now precede streaming | |
| `:264-265` | `session.close(); streamSession = null` | `camera.stop(); session.stop(); camera = null; session = null` | |
| `:324-347` | `convertVideoFrameToBitmap`: `buffer/width/height` | unchanged | |
| manifest `:75-78` | `foregroundServiceType="microphone"` | keep (service uses TTS only); the 0.9.0 sample keeps a stream alive in background with `foregroundServiceType="connectedDevice"` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` (`samples/CameraAccess/.../AndroidManifest.xml:22-23,74-78`, `StreamingService.kt:95-99`) -- optional hardening |

### 4.4 `viewmodels/RTMPStreamingViewModel.kt` and `ui/screens/RTMPStreamingScreen.kt`
| Site | 0.4.0 | 0.9.0 | Notes |
|---|---|---|---|
| `RTMPStreamingViewModel.kt:12-15` | `StreamSession`, `startStreamSession`, `StreamConfiguration`, `StreamSessionState` | `Camera`, `Stream`, `addCamera`, `StreamConfiguration`, `StreamState` (+ `DeviceSession`, `DeviceSessionState`) | |
| `:62-63` | `MutableStateFlow<StreamSessionState?>(null)` / `StateFlow<StreamSessionState?>` | `MutableStateFlow<StreamState?>(null)` | |
| `RTMPStreamingScreen.kt:36` | `import com.meta.wearable.dat.camera.types.StreamSessionState` | `import com.meta.wearable.dat.camera.types.StreamState` | `:52` (`val cameraState by viewModel.cameraState.collectAsState()`) is otherwise unused |
| `:70` | `AutoDeviceSelector()` | unchanged | |
| `:73` | `private var streamSession: StreamSession? = null` | `session: DeviceSession?`, `camera: Camera?` | |
| `:161-162` | `streamSession?.close(); streamSession = null` | `camera?.stop(); session?.stop(); ...` | |
| `:176-180` | `Wearables.startStreamSession(getApplication(), deviceSelector, StreamConfiguration(videoQuality = videoQuality, 24))` | same session/camera/stream sequence as 4.2 | |
| `:183-201` | `session.state.collect { StreamSessionState.STREAMING -> ...; STOPPED -> if (rtmpService.isStreaming()) stopStreaming(); else -> {} }` | `camera.stream.state.collect { StreamState.STREAMING -> ...; StreamState.STOPPED, StreamState.CLOSED -> ...; StreamState.PAUSED -> /* frames stop; keep RTMP connected or pause encoder */; else -> {} }` | initial replayed `STOPPED` must not call `stopStreaming()` (guarded today by `rtmpService.isStreaming()`) |
| `:204-209` | `session.videoStream.collect { handleVideoFrame(it) }` | `camera.stream.videoStream.collect { ... }` | |
| `:228-260` | `handleVideoFrame`: `videoFrame.width/height/buffer`, own timestamp base | unchanged; add `if (videoFrame.isCompressed || videoFrame.isCodecConfig) return`; `videoFrame.presentationTimeUs` exists in both versions if you want SDK timestamps | encoder expects I420 (`RTMPStreamingService.kt:188-196`) |
| `:305-326` | `stopStreaming()`: `streamSession?.close()` | `camera?.stop(); session?.stop()`; null refs | |

### 4.5 `ui/screens/HomeScreen.kt`
| Site | 0.4.0 | 0.9.0 | Notes |
|---|---|---|---|
| `:34-36` | imports `Wearables`, `Permission`, `PermissionStatus` | unchanged | |
| `:71-97` | `Wearables.checkPermissionStatus(permission)`; `result.getOrNull() == PermissionStatus.Granted`; `onRequestWearablesPermission(permission)` | unchanged | matches sample `CameraViewModel.kt:230-241` |
| `:168`, `:256` | `wearablesViewModel.startDeviceSearch()` | `wearablesViewModel.startDeviceSearch(activity)` with `val activity = LocalActivity.current` (androidx.activity-compose >= 1.10.0; app has 1.9.0 -> bump, or use `LocalContext.current as? Activity`) | sample: `HomeScreen.kt:51,113-114` uses `LocalActivity.current` and falls back to a Toast when null |
| `:257` | `wearablesViewModel.disconnect()` | `wearablesViewModel.disconnect(activity)` | |

### 4.6 Type-only sites (no change)
`Navigation.kt:18-19,55`, `LiveAIScreen.kt:36-37,52` (`suspend (Permission) -> PermissionStatus` lambdas).

### 4.7 Manifest / Gradle / ProGuard
| Site | 0.4.0 | 0.9.0 | Notes |
|---|---|---|---|
| `AndroidManifest.xml:34-36` | `<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="0" />` | `<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="${mwdat_application_id}" />` + `<meta-data android:name="com.meta.wearable.mwdat.CLIENT_TOKEN" android:value="${mwdat_client_token}" />` | Developer Mode: both `0` (or empty). Production: values from Wearables Developer Center. `CLIENT_TOKEN` is required for attestation outside Developer Mode (docs export `dat-llms-full.txt:365-376`) |
| `AndroidManifest.xml` (new, optional) | -- | `<meta-data android:name="com.meta.wearable.mwdat.CRASH_REPORTING_OPT_OUT" android:value="true" />`; `<meta-data android:name="com.meta.wearable.mwdat.ANALYTICS_OPT_OUT" android:value="true" />` | crash-reporting key is new in 0.9.0 (CHANGELOG); analytics key documented in the telemetry opt-out docs |
| `AndroidManifest.xml` | (no `DAM_ENABLED`) | nothing to remove | DAM is always on in 0.9.0 |
| `AndroidManifest.xml:49-54` | deep-link scheme `turbometa` | unchanged | required for the Meta AI callback |
| `AndroidManifest.xml` (optional) | -- | `<uses-permission android:name="android.permission.CAMERA" />` + `<uses-feature android:name="android.hardware.camera" android:required="false" />` | only for the MockDeviceKit phone-camera feed |
| `gradle/libs.versions.toml:4` | `mwdat = "0.4.0"` | `mwdat = "0.9.0"` | |
| `gradle/libs.versions.toml:24-26` | core/camera/mockdevice aliases | keep; add `mwdat-display = { group = "com.meta.wearable", name = "mwdat-display", version.ref = "mwdat" }` only if Display is adopted | |
| `app/build.gradle.kts:84-86` | `implementation(libs.mwdat.core)`, `implementation(libs.mwdat.camera)`, mockdevice commented out | keep; add `debugImplementation(libs.mwdat.mockdevice)` for the test path (sample uses `implementation`, `app/build.gradle.kts:74`) | 0.9.0 mockdevice ships `-dontwarn` consumer rules |
| `app/build.gradle.kts:11-22` (`defaultConfig`) | -- | `manifestPlaceholders["mwdat_application_id"] = providers.gradleProperty("mwdat_application_id").orNull ?: localProperties.getProperty("mwdat_application_id", "0")` and the same for `mwdat_client_token` (pattern: `samples/DisplayAccess/app/build.gradle.kts:18-43`; load `local.properties` via `rootProject.file("local.properties")`) | `android/local.properties` is git-ignored (`android/.gitignore:24`); document the two new keys next to `github_token` |
| `app/build.gradle.kts:9,14` | `compileSdk = 35`, `targetSdk = 34` | sample: `compileSdk = 36`, `targetSdk = 36` | see section 8 |
| `app/build.gradle.kts:46-53` | Java 1.8 / `kotlinOptions { jvmTarget = "1.8" }` | sample: `JavaVersion.VERSION_17` + `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }` | `kotlinOptions` is deprecated on Kotlin 2.2 |
| `app/proguard-rules.pro:5-9` | `-keep class com.meta.** { *; }` etc. | can stay (sample ships no custom rules and builds with `isMinifyEnabled = true`) | R8 issues were fixed upstream in 0.5.0/0.6.0 |

---

## 5. 0.9.0 skeleton for the shared "session + camera + stream" flow

Drop-in for `WearablesViewModel.startStream/stopStream/takePhoto`; reusable by the other two call sites.

```kotlin
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState

private var session: DeviceSession? = null
private var camera: Camera? = null
private var stream: Stream? = null
private var sessionStateJob: Job? = null
private var sessionErrorJob: Job? = null
private var streamStateJob: Job? = null
private var streamErrorJob: Job? = null
private var videoJob: Job? = null

fun startStream() {
    stopStream()                                  // idempotent teardown of any previous camera/session
    _currentFrame.value = null
    _streamState.value = StreamState.Waiting      // app's own sealed class

    val quality = when (APIKeyManager.getInstance(getApplication()).getVideoQuality()) {
        "LOW" -> VideoQuality.LOW; "HIGH" -> VideoQuality.HIGH; else -> VideoQuality.MEDIUM
    }

    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
            session = created
            sessionStateJob = viewModelScope.launch {
                created.state.collect { state ->
                    when (state) {
                        DeviceSessionState.STARTED -> if (camera == null) attachCamera(created, quality)
                        DeviceSessionState.PAUSED -> _streamState.value = StreamState.Waiting   // do NOT restart
                        DeviceSessionState.STOPPED -> stopStream()                               // terminal; recreate next time
                        else -> Unit
                    }
                }
            }
            sessionErrorJob = viewModelScope.launch {
                created.errors.collect { error ->                       // SharedFlow<DeviceSessionError>
                    setError(error.getLocalizedDescription(getApplication()))
                    // DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> offer Wearables.openDATGlassesAppUpdate(activity)
                }
            }
            created.start()                                             // fire-and-forget
        }
        .onFailure { error, _ ->                                        // NO_ELIGIBLE_DEVICE / SESSION_ALREADY_EXISTS / ...
            _streamState.value = StreamState.Error(error.getLocalizedDescription(getApplication()))
        }
}

private fun attachCamera(session: DeviceSession, quality: VideoQuality) {
    session.addCamera(StreamConfiguration(videoQuality = quality, frameRate = 24))   // compressVideo defaults to false -> decoded YUV
        .onSuccess { added ->
            camera = added
            val s = added.stream
            stream = s
            // Subscribe BEFORE start(): state is a StateFlow that replays STOPPED.
            videoJob = viewModelScope.launch(Dispatchers.Default) {
                s.videoStream.collect { frame ->
                    if (frame.isCompressed || frame.isCodecConfig) return@collect
                    handleVideoFrame(frame)
                }
            }
            streamStateJob = viewModelScope.launch {
                var hasBeenActive = false
                s.state.collect { state ->
                    when (state) {
                        DatStreamState.STREAMING -> { hasBeenActive = true; _streamState.value = StreamState.Streaming /* + Connected upgrade */ }
                        DatStreamState.STARTING, DatStreamState.STARTED, DatStreamState.STOPPING -> { hasBeenActive = true; _streamState.value = StreamState.Waiting }
                        DatStreamState.PAUSED -> { hasBeenActive = true; _streamState.value = StreamState.Waiting /* "tap glasses to resume" */ }
                        DatStreamState.STOPPED, DatStreamState.CLOSED ->
                            if (hasBeenActive) { hasBeenActive = false; stopStream() } else _streamState.value = StreamState.Stopped
                    }
                }
            }
            streamErrorJob = viewModelScope.launch {
                s.errorStream.collect { error -> setError(error.getLocalizedDescription(getApplication())) }  // HINGE_CLOSED, PERMISSIONS_DENIED, THERMAL_HOT, BATTERY_LOW, ...
            }
            s.start().onFailure { error, _ ->                            // DatResult<Unit, StreamError>
                setError(error.getLocalizedDescription(getApplication()))
                stopStream()
            }
        }
        .onFailure { error, _ -> setError(error.getLocalizedDescription(getApplication())) }   // CAPABILITY_DENIED (no camera permission), CAPABILITY_ALREADY_ADDED, SESSION_IDLE, ...
}

fun stopStream() {
    videoJob?.cancel(); videoJob = null
    streamStateJob?.cancel(); streamStateJob = null
    streamErrorJob?.cancel(); streamErrorJob = null
    camera?.stop()            // cascades to Stream; detaches the capability (required before a future addCamera)
    camera = null; stream = null
    sessionStateJob?.cancel(); sessionStateJob = null
    sessionErrorJob?.cancel(); sessionErrorJob = null
    session?.stop()           // STOPPED is terminal -> always recreate on next startStream()
    session = null
    _currentFrame.value = null
    _streamState.value = StreamState.Stopped
    // ... existing ConnectionState downgrade logic (WearablesViewModel.kt:360-365)
}

fun takePhoto() { viewModelScope.launch {
    val s = stream ?: return@launch
    if (s.state.value != DatStreamState.STREAMING) return@launch
    s.capturePhoto()                                                 // DatResult<PhotoData, CaptureError>
        .onSuccess { photoData -> /* PhotoData.Bitmap.bitmap / PhotoData.HEIC.data as today */ }
        .onFailure { error, _ -> _errorMessage.value = error.getLocalizedDescription(getApplication()) }
} }
```

Ordering facts this skeleton relies on: `createSession` is synchronous and returns `IDLE`; `addCamera` only succeeds after `STARTED` (reference: "Must be added after STARTED state"); `Stream.start()` is explicit; `Camera.stop()` "stops this camera and its child features, releasing resources and detaching from the parent session" and is a no-op if already stopped; `DeviceSession.stop()` "terminates the session and all attached capabilities"; a stopped session "cannot be restarted".

---

## 6. Behavior of the three app flows after migration

- **LiveAI / SimpleLiveStream / QuickVisionScreen** (`LiveAIScreen.kt:79,104`, `SimpleLiveStreamScreen.kt:47,53`, `QuickVisionScreen.kt:181-231`): they call `wearablesViewModel.startStream()/stopStream()` and poll `streamState` -- unchanged contract if the ViewModel keeps its own `StreamState` sealed class. `QuickVisionScreen.kt:185-188` waits up to 5 s for `Streaming`; the added session start may need a longer budget.
- **QuickVisionService** (wake word -> capture -> analyze, started from `PorcupineWakeWordService.kt:215`): needs its own session unless a shared holder is introduced. It can run while `MainActivity` is streaming; in 0.9.0 the second `createSession` for the same device returns `DeviceSessionError.SESSION_ALREADY_EXISTS` instead of silently competing.
- **RTMP**: identical frame path; `PAUSED` now visible.

---

## 7. Session ownership decision (required)

0.9.0 guarantees "singleton-per-device" sessions (`createSession` -> `SESSION_ALREADY_EXISTS`). Options:
1. **Serialize** (minimal): each component stops its session in `stopStream()`/`cleanup()`, and treats `SESSION_ALREADY_EXISTS` as "camera busy" (QuickVisionService: speak `getLocalizedString("error")`).
2. **Shared holder** (recommended): a process-wide `object DatSessionHolder` that owns `DeviceSession` + `Camera`, with `acquire(config): DatResult<Camera, DeviceSessionError>` / `release()`, used by `WearablesViewModel`, `QuickVisionService` and `RTMPStreamingViewModel`. Keeping the `DeviceSession` alive between streams (the sample's "stop preview keeps session" flow, `CameraViewModel.kt:307-313`, `InstrumentationTest.kt:137-156`) avoids the seconds of `STARTING` on every capture.

---

## 8. (a) Gradle / toolchain requirements

| Item | App today | 0.9.0 CameraAccess sample (`samples/CameraAccess/gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`) | Comment |
|---|---|---|---|
| `minSdk` | 31 (`app/build.gradle.kts:13`) | 31 | OK |
| `compileSdk` | 35 (`:9`) | 36 | the 0.9.0 AAR's transitive AndroidX versions are unknown (POM not cached locally); the sample README requires "Android SDK 36 or newer". Plan on `compileSdk = 36` |
| `targetSdk` | 34 (`:14`) | 36 | not required by the SDK; Play-policy driven |
| AGP | 8.6.0 (`libs.versions.toml:2`) | 8.11.1 | `compileSdk 36` needs AGP >= 8.9.1 (AGP 8.6 officially supports up to API 35). Sample: 8.11.1 |
| Kotlin | 2.0.0 (`:3`) | 2.2.21 | Kotlin can read metadata only one minor version ahead; a 2.0.0 compiler cannot consume a 2.2.x-compiled AAR ("module was compiled with an incompatible version of Kotlin"). Bump to 2.2.x (sample) |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose` 2.0.0 | 2.2.21 | follows Kotlin |
| Gradle wrapper | 8.7 (`gradle/wrapper/gradle-wrapper.properties`) | 8.14.1 | AGP 8.11 requires Gradle >= 8.13 |
| JDK / jvmTarget | Java 1.8 (`app/build.gradle.kts:46-53`) | 17 (`compileOptions` + `kotlin { compilerOptions { jvmTarget = JVM_17 } }`) | AGP 8.x already needs JDK 17 to run; the app's `sourceCompatibility 1.8` is only the bytecode level. Local JDK found: Amazon Corretto 21 |
| Compose BOM | 2024.12.01 | 2026.05.01 | optional; BOM 2026.x requires compileSdk 36 |
| lifecycle | 2.6.2 | 2.10.0 | 0.4.0 `mwdat-core` POM already pulls `androidx.lifecycle:lifecycle-service:2.9.4`, `androidx.security:security-crypto:1.1.0-alpha04`, `com.squareup.okhttp3:okhttp:4.10.0`, `com.google.protobuf:protobuf-javalite:3.25.5`, `com.facebook.soloader:soloader:0.12.1`, `androidx.exifinterface:exifinterface:1.3.7` -- expect equal-or-newer in 0.9.0 |
| activity-compose | 1.9.0 | 1.13.0 | `LocalActivity` (needed for the `Activity` fix) exists from 1.10.0 |
| exifinterface | 1.3.7 | 1.4.2 | |
| kotlinx-collections-immutable | 0.3.7 | 0.4.0 | |
| Maven repo | `https://maven.pkg.github.com/facebook/meta-wearables-dat-android` with `github_token` (`settings.gradle.kts:33-39`) | same | unchanged |
| Artifacts | `mwdat-core`, `mwdat-camera` | + `mwdat-mockdevice` (tests), `mwdat-display` (Display) | group `com.meta.wearable`, same version ref |
| `androidx.fragment` | via appcompat 1.7.0 (`MainActivity : AppCompatActivity`) | via ComponentActivity + transitive | sample test `RegistrationButtonTest.kt:24-31`: mwdat-core uses `FragmentActivity` for registration but does not bundle `androidx.fragment`; missing it => `NoClassDefFoundError` on Connect. AppCompat provides it -- keep it |

Minimum viable bump: `mwdat 0.9.0`, Kotlin >= 2.1 (2.2.21 recommended), AGP >= 8.9.1 (8.11.1), Gradle >= 8.13 (8.14.1), `compileSdk 36`, JVM 17, activity-compose >= 1.10.0. After the first sync, read the resolved POM at `~/.gradle/caches/modules-2/files-2.1/com.meta.wearable/mwdat-core/0.9.0/*/mwdat-core-0.9.0.pom` to confirm transitive minimums.

## 9. (b) Version dependencies (docs export `dat-llms-full.txt:3577-3658`)

| SDK | Meta AI app (Android/iOS) | Ray-Ban Meta | Meta glasses | Meta Ray-Ban Display | Oakley Meta HSTN | Oakley Meta Vanguard |
|---|---|---|---|---|---|---|
| **0.9.0** | V282 | V126 | V126 | V125 | V126 | V126 |
| 0.8.0 | V275 | V125 | V125 | V125 | V125 | V125 |
| 0.7.0 | V272 | V125 | -- | V125 | V125 | V125 |
| 0.6.0 | V254 | V22 | -- | V21 | V22 | V22 |
| 0.5.0 | V254 | V22 | -- | V21 | V22 | V22 |
| 0.4.0 (current) | V254 | V20 | -- | V21 | V20 | V22 |

Testers must update the Meta AI app to V282 and glasses firmware to V126 (Display V125). Developer Mode must be on in Meta AI for `0`/empty `APPLICATION_ID`/`CLIENT_TOKEN`; only one third-party app can stay registered at a time in Developer Mode (`dat-llms-full.txt:1974-1976`).

## 10. (c) Behavior changes that matter for this app

1. **Registration**: `RegistrationState` carries no error; failures arrive on `Wearables.registrationErrorStream` (hot, no replay) -- collect it from `startMonitoring()`. `startRegistration/startUnregistration` open an in-place dialog (0.4.0+) and need a real `Activity`.
2. **Explicit session lifecycle**: `createSession` is sync and eager against the current device set (`NO_ELIGIBLE_DEVICE` immediately if the glasses are not connected+compatible); `start()` is fire-and-forget; `addCamera` only after `STARTED`; `DeviceSessionState.STOPPED` is terminal; only one active session per device (`SESSION_ALREADY_EXISTS`).
3. **Pause semantics**: `DeviceSessionState.PAUSED` / `StreamState.PAUSED` (single cap-touch tap, system gesture, low power): streams stop delivering frames, the connection stays; "Your app should not attempt to restart a device session while it is paused" (`dat-llms-full.txt:1885-1891`). Tap again resumes; tap-and-hold stops the session. Folding hinges -> `StreamError.HINGE_CLOSED`, stream `STOPPED`, session `STOPPED`; unfolding does **not** restart the session.
4. **`Stream.start()` is explicit** and returns `DatResult<Unit, StreamError>`; subscribe to `state`/`videoStream`/`errorStream` before calling it; `state` replays `STOPPED` initially.
5. **`Camera.stop()` is required** to detach the capability; a stopped camera/stream cannot be reused; re-adding without stopping returns `CAPABILITY_ALREADY_ADDED` (sample comment `CameraViewModel.kt:413-416`).
6. **Photo capture**: `DatResult<PhotoData, CaptureError>`; only while `STREAMING`; one capture at a time (`CaptureInProgress`); a timeout was added in 0.7.0 so a stuck capture now fails instead of locking forever (which error case is surfaced on timeout is not documented -- treat any failure generically).
7. **Video frames**: default `compressVideo=false` still delivers decoded YUV (the app's I420->NV21 path stays). Guard `isCompressed`/`isCodecConfig`. The sample now streams `compressVideo = true` HEVC into `MediaCodec` for preview/recording (`CameraViewModel.kt:276-283`, `HevcDecoder.kt`) -- an optional later optimization (the RTMP path would still need HEVC->H.264 transcoding).
8. **DAM always on**: new session errors `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` (offer `Wearables.openDATGlassesAppUpdate(activity)`) and `DWA_UNAVAILABLE`; device compatibility `DEVICE_UPDATE_REQUIRED` (offer `Wearables.openFirmwareUpdate(activity)`).
9. **Thermal/battery**: `StreamError.THERMAL_HOT/BATTERY_LOW/PEAK_POWER_LIMIT/TIMEOUT`, `DeviceSessionError.THERMAL_CRITICAL/THERMAL_EMERGENCY/PEAK_POWER_SHUTDOWN/BATTERY_CRITICAL`; `Wearables.getDeviceState(id).thermalLevel` is always-on.
10. **Permissions**: `checkPermissionStatus` returns granted if any linked device has the permission; the `PermissionsSession` stale-state and double-resume crashes were fixed in 0.7.0.
11. **Device selector**: `AutoDeviceSelector` default filter already excludes disconnected/incompatible devices; `activeDevice()` is sync and eagerly cached.
12. **`DatResult` is a normal class** -- Java-visible; Kotlin source unchanged.

## 11. (d) MockDeviceKit 0.9.0 API surface (artifact `com.meta.wearable:mwdat-mockdevice:0.9.0`)

Imports: `com.meta.wearable.dat.mockdevice.MockDeviceKit`, `com.meta.wearable.dat.mockdevice.api.{MockDeviceKitInterface, MockDeviceKitConfig, MockDeviceKitError, GlassesModel, MockDevice, MockGlasses, MockGlassesServices}`, `com.meta.wearable.dat.mockdevice.api.camera.{MockCameraKit, CameraFacing}`, `com.meta.wearable.dat.mockdevice.api.captouch.MockCaptouchKit`, `com.meta.wearable.dat.mockdevice.api.permissions.MockPermissions`.

- `MockDeviceKit.getInstance(context: Context): MockDeviceKitInterface` -- process singleton; "Safe to call regardless of whether Wearables.initialize has been called -- MockDeviceKit will auto-initialize Wearables if needed."
- `MockDeviceKitInterface`: `fun enable(config: MockDeviceKitConfig = MockDeviceKitConfig())`, `fun disable()` (detaches fakes, unpairs all), `val isEnabled: Boolean`, `val pairedDevices: Collection<MockDevice>`, `val permissions: MockPermissions`, `fun pairGlasses(model: GlassesModel): DatResult<MockGlasses, MockDeviceKitError>`, `fun unpairDevice(device: MockDevice)`.
- `data class MockDeviceKitConfig(val initiallyRegistered: Boolean = true, val initialPermissionsGranted: Boolean = true)` -- `initialPermissionsGranted` covers `Permission.CAMERA` and `Permission.MICROPHONE`, only meaningful when `initiallyRegistered`.
- `enum GlassesModel { RAYBAN_META, OAKLEY_META_HSTN, OAKLEY_META_VANGUARD, RAYBAN_META_OPTICS, META_GLASSES }`; `MockDeviceKitError.NotEnabled` (data object).
- `MockGlasses : MockDevice`: `deviceIdentifier`, `services: MockGlassesServices`, `powerOn()`, `powerOff()`, `don()`, `doff()`, `unfold()`, `fold()`. Device must be powered on **and** donned (don auto-unfolds) before a session can stream; up to three mock devices.
- `MockGlassesServices`: `val camera: MockCameraKit`, `val captouch: MockCaptouchKit`.
- `MockCameraKit`: `setCameraFeed(fileUri: Uri)` (H.265 only, no auto-transcode on Android; `ffmpeg ... -c:v hevc_videotoolbox -tag:v hvc1 -vf "scale=540:960"`), `setCameraFeed(cameraFacing: CameraFacing)` (`FRONT`/`BACK`; needs runtime `android.permission.CAMERA`; mutually exclusive with the file feed; 0.9.0 fixed the feed stopping after a few seconds), `setCapturedImage(fileUri: Uri)` (returned by `Stream.capturePhoto()`, "post-rotated 90 degrees to match real device behavior"; with a phone-camera feed capture takes a live still instead).
- `MockCaptouchKit`: `tap()` (pause/resume toggle -> `StreamState.PAUSED`), `tapAndHold()` (stops the active session); both no-ops without an active session.
- `MockPermissions`: `set(permission: Permission, status: PermissionStatus)` (controls `checkPermissionStatus`), `setRequestResult(permission: Permission, result: PermissionStatus)` (controls `RequestPermissionContract`; default `Granted`).

Test-path recipe (sample `InstrumentationTest.kt:507-518`, docs `dat-llms-full.txt:2440-2506`):
```kotlin
val kit = MockDeviceKit.getInstance(targetContext)
kit.enable(MockDeviceKitConfig(initialPermissionsGranted = false))   // or kit.enable()
kit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)       // exercise the redirect
val device = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
device.powerOn(); device.don(); device.unfold()
device.services.camera.setCameraFeed(Uri.fromFile(File(cacheDir, "plant.mp4")))   // H.265
device.services.camera.setCapturedImage(Uri.fromFile(File(cacheDir, "plant.png")))
// ... drive UI; device.services.captouch.tap(); device.fold()
kit.disable()   // @After
```
Instrumentation prerequisites: `pm grant <pkg> android.permission.BLUETOOTH_CONNECT` (+ `CAMERA` for the phone feed); Gradle `androidTestImplementation` of `androidx.compose.ui:ui-test-junit4`, `androidx.test:rules`, `androidx.test.uiautomator` as in the sample (`libs.versions.toml:25-27`). For a debug-only in-app toggle, mirror `MockDeviceKitViewModel.kt` (enable/disable/pair/power/don/unfold/feed/captouch).

## 12. Risks / unknowns

- 0.9.0 POM (transitive AndroidX/OkHttp/protobuf versions, required compileSdk) is not cached locally; verify after first dependency resolution.
- The YUV pixel layout of decoded `VideoFrame.buffer` is not documented in 0.9; the app assumes I420. Verify on-device (frame size == `width*height*3/2`, which `RTMPStreamingService.kt:362-366` already logs).
- `AutoDeviceSelector()` no-arg constructor: used by the sample, but the reference page excerpt shows `deviceRanking: Comparator<Device>` without a visible default.
- Which `CaptureError` case is returned on the 0.7.0 capture timeout is undocumented.
- `RegistrationState` enum has no `description`; the sample treats `UNREGISTERING` as still registered.
- The existing `startRegistration(getApplication())` bug means the Connect button likely crashes on the current 0.4.0 build; the fix is part of this migration either way.
- `QuickVisionService` may be started while no `Activity` exists; `Wearables.initialize` currently runs only from `MainActivity.initializeSDK()` (`MainActivity.kt:118-127`). Consider initializing in `TurboMetaApplication.onCreate()` (the `getting-started` skill shows the `Application` pattern) so background flows never hit `WearablesError.NOT_INITIALIZED`.
- Kotlin 2.0.0 reading a Kotlin 2.2.21-compiled AAR is expected to fail (forward compatibility is one minor version).
