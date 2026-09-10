# TurboMeta Android — Current-State Architecture and Feature Map

Repo: `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/` (Kotlin + Jetpack Compose, package `com.smartview.glassai`).
All paths below are relative to `android/app/src/main/java/com/smartview/glassai/` unless prefixed. Line numbers are from the working tree at commit `78ea2f3` (clean).

Snapshot facts:
- App version: `versionCode = 4`, `versionName = "1.5.0"` (`android/app/build.gradle.kts:15-16`). Settings "About" hardcodes `"1.5.0"` (`ui/screens/SettingsScreen.kt:423`). `android/README.md:3` still says "Version 1.4.0"; `android/CHANGELOG.md` only has a 1.0.0 entry referencing DAT v0.3.0 (`android/CHANGELOG.md:3,13`).
- DAT SDK pinned at `mwdat = "0.4.0"` (`android/gradle/libs.versions.toml:4`); artifacts `mwdat-core`, `mwdat-camera` (`toml:24-25`), `mwdat-mockdevice` declared but commented out in the app (`build.gradle.kts:86`).
- Source: 14 Kotlin files under `ui/screens`, 8 ViewModels/services, ~14k lines total (`SettingsScreen.kt` 1315, `HomeScreen.kt` 715, `LiveAIScreen.kt` 671, `RecordsScreen.kt` 660, `QuickVisionScreen.kt` 653).
- No test source sets exist (`android/app/src/` contains only `main`).
- Last Android-touching commits: `cacf273` (2026-03-11, DAT 0.4.0 upgrade), `863221a` (config refactor), `dcb6047` (2026-01-30, HFP Bluetooth mic). iOS v2.0.0 (`cd26fb2`) added OpenClaw (`CameraAccess/Services/OpenClaw/*`), Fun-ASR, DAT 0.5.0 — none of that exists on Android.

---

## 1. Navigation graph and screens

### 1.1 Graph (`ui/navigation/Navigation.kt`)

Routes are a sealed class `Screen(val route: String)` (`Navigation.kt:25-38`):

| Screen object | route | Composable | Reachable from |
|---|---|---|---|
| `Home` | `"home"` | `HomeScreen` (`:111-134`) | start destination (`:108`), bottom nav |
| `LiveAI` | `"live_ai"` | `LiveAIScreen` (`:136-144`) | Home card |
| `LeanEat` | `"lean_eat"` | `LeanEatScreen` (`:146-157`) | Home card |
| `Vision` | `"vision"` | `VisionScreen` (`:159-170`) | **nothing navigates here** — Home's `onNavigateToVision` goes to `quick_vision` (`:121-123`). Dead route. |
| `QuickVision` | `"quick_vision"` | `QuickVisionScreen` (`:172-179`) | Home card |
| `Settings` | `"settings"` | `SettingsScreen` (`:181-196`) | bottom nav, Home API-key dialog |
| `Records` | `"records"` | `RecordsScreen` (`:198-204`) | bottom nav, Settings |
| `Gallery` | `"gallery"` | `GalleryScreen` (`:206-212`) | bottom nav |
| `LiveStream` | `"live_stream"` | `SimpleLiveStreamScreen` (`:214-221`) | Home wide card |
| `RTMPStream` | `"rtmp_stream"` | `RTMPStreamingScreen` (`:223-229`) | Home wide card |
| `QuickVisionMode` | `"quick_vision_mode"` | `QuickVisionModeScreen` (`:231-237`) | Settings |
| `LiveAIMode` | `"live_ai_mode"` | `LiveAIModeScreen` (`:239-245`) | Settings |

Bottom bar (`BottomNavItem`, `Navigation.kt:40-49`): Home / Records / Gallery / Settings; shown only when `currentRoute` is one of those four (`:70`). Tab navigation uses `popUpTo(startDestination){saveState}` + `launchSingleTop` + `restoreState` (`:87-93`).

`TurboMetaNavigation(wearablesViewModel: WearablesViewModel, onRequestWearablesPermission: suspend (Permission) -> PermissionStatus)` (`:53-56`) — the single activity-scoped `WearablesViewModel` is threaded into Home, LiveAI, QuickVision, LiveStream, and (frame-only) LeanEat/Vision. LeanEat/Vision receive `currentFrame` and `onTakePhoto = { wearablesViewModel.takePhoto() }` (`:147-156`, `:160-169`).

Entry: `MainActivity` (`AppCompatActivity`, `MainActivity.kt:29`) → `TurboMetaTheme { Surface { TurboMetaNavigation(...) } }` (`:89-101`). Theme is light-only (`ui/theme/Theme.kt:54-55`).

### 1.2 Screens

**HomeScreen** (`ui/screens/HomeScreen.kt`) — VM: `WearablesViewModel` (+ `APIKeyManager` directly `:57`).
- Device card `DeviceStatusCard` (`:578-715`): maps `WearablesViewModel.ConnectionState` to UI; Registered and Connected both shown as "Connected" (`:584-593`); connect button → `wearablesViewModel.startDeviceSearch()` (`:256`), tap when connected → disconnect dialog → `wearablesViewModel.disconnect()` (`:257`).
- Feature grid (`:264-384`): Live AI (`:275-297`), Quick Vision (`:299-321`), LeanEat (`:329-336`, navigates with **no device/key/permission check**), WordLearn placeholder (`:338-346`), Live Stream wide (`:350-365`), RTMP wide (`:368-383`).
- Gate logic for Live AI / Quick Vision: `hasActiveDevice` (`:284`, `:308`) → `apiKeyManager.getAPIKey()` (`:289`, `:313` — this is the **Vision-provider** key, not the Live AI provider key) → `checkCameraPermissionAndNavigate` (`:71-98`) which calls `Wearables.checkPermissionStatus(Permission.CAMERA)` (`:76`) and, if not `Granted`, `onRequestWearablesPermission(permission)` (`:86`).
- Dialogs: API-key-required (`:101-142`, opens `https://bailian.console.aliyun.com/?apiKey=1` `:124`), device-required (`:145-181`), camera-permission-denied (`:184-212`).

**LiveAIScreen** (`ui/screens/LiveAIScreen.kt`) — VMs: `OmniRealtimeViewModel` (`viewModel()` `:50`) + `WearablesViewModel`.
- On enter: `wearablesViewModel.startStream()` then `viewModel.connect()` (`:77-84`); auto `startRecording()` once connected (`:87-91`); every new `currentFrame` → `viewModel.updateVideoFrame(frame)` (`:94-98`); on dispose `stopStream()` then `disconnect()` (`:101-108`).
- Background = live camera bitmap with 40% black overlay (`:119-131`); top bar status via `getStatusText` (`:576-595`); message list + streaming bubble + speaking indicator (`:226-258`); `ControlPanel` with connect / end / record FAB / stream indicator (`:438-525`).
- `AudioSourceToggle` (`:631-671`) — two `FilterChip`s with **hardcoded Chinese labels** `"手机麦克风"` / `"眼镜麦克风"` (`:646`, `:660`); glasses chip enabled only when `isBluetoothScoAvailable`.
- Parameter `onRequestWearablesPermission` (`:52`) is accepted but never used.

**QuickVisionScreen** (`ui/screens/QuickVisionScreen.kt`) — no ViewModel; uses `WearablesViewModel` + instantiates `VisionAPIService`, `QuickVisionStorage`, `QuickVisionModeManager`, `APIProviderManager`, `APIKeyManager` inline (`:84-88`) and its own `TextToSpeech` (`:103-125`).
- `performQuickVision()` (`:152-269`): device check → TTS "analyzing" → `startStream()` and poll `streamState` up to 50×100 ms (`:179-197`) → `delay(500)` → `clearCapturedPhoto()`; `takePhoto()`; poll `capturedPhoto` up to 30×100 ms (`:205-213`), fallback to `currentFrame` (`:216`) → `stopStream()` (`:231`) → `visionService.quickVision(photo, outputLanguage)` (`:237`) → `quickVisionStorage.saveRecord(...)` (`:249-255`) → TTS result.
- Auto-runs when `hasActiveDevice` becomes true (`:279-285`); dispose stops TTS/stream (`:288-295`).

**LeanEatScreen** (`ui/screens/LeanEatScreen.kt`) — VM `LeanEatViewModel`. `LaunchedEffect(currentFrame)` copies any incoming frame into `viewModel.setCapturedImage` (`:46-48`); states Idle/Capturing/Analyzing/Result/Error (`:85-116`). "Take Photo" calls `wearablesViewModel.takePhoto()` which is a no-op unless already streaming (`viewmodels/WearablesViewModel.kt:373-376`) and nothing on this screen starts a stream → button effectively dead.

**VisionScreen** (`ui/screens/VisionScreen.kt`) — VM `VisionViewModel`; same frame/capture pattern (`:44-46`); custom prompt + 6 default prompts (`viewmodels/VisionViewModel.kt:55-62`). Unreachable route (see 1.1).

**SettingsScreen** (`ui/screens/SettingsScreen.kt`, 1315 lines) — VM `SettingsViewModel` (`:60`). Sections:
- Vision API Provider (`:203-273`): provider, Alibaba endpoint, current key, vision model.
- Live AI (`:276-313`): Live AI mode settings nav, provider, Google key.
- Quick Vision (`:316-366`): mode settings nav, wake-word toggle (`SettingsToggleItem` `:328-337`), Picovoice key, battery-optimization intent (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` `:361`).
- AI Settings (`:369-396`): app language, output language, video quality.
- Data (`:399-416`): records nav, clear all.
- About (`:419-462`): version, GitHub, releases, Buy Me a Coffee.
- Wake-word service control lives in the screen: `toggleWakeWordService` (`:133-168`) checks Picovoice key, requests `RECORD_AUDIO` via `rememberLauncherForActivityResult` (`:111-130`), starts/stops `PorcupineWakeWordService` with `ACTION_START`/`ACTION_STOP`. Running state detected with deprecated `ActivityManager.getRunningServices` (`:1306-1315`).
- Dialogs (all private composables in this file): `ProviderSelectionDialog` (`:691-733`), `ApiKeyDialog` (`:736-824`), `PicovoiceKeyDialog` (`:827-904`), `VisionModelSelectionDialog` (`:908-1098`, OpenRouter search + vision-only filter), `LanguageSelectionDialog` (`:1101-1149`), `QualitySelectionDialog` (`:1152-1200`), `AppLanguageSelectionDialog` (`:1203-1251`).

**RecordsScreen** (`ui/screens/RecordsScreen.kt`) — VM `RecordsViewModel`. Two tabs `RecordsTab.LIVE_AI` / `QUICK_VISION` (`:98-111`); in-screen detail views `ConversationDetailScreen` (`:411-513`) and `QuickVisionDetailScreen` (`:517-660`) (not separate nav routes); thumbnails loaded with `BitmapFactory.decodeFile` inside `remember` (`:322-331`).

**GalleryScreen** (`ui/screens/GalleryScreen.kt`) — placeholder empty state only, hardcoded English string `"Photos taken with Live AI will appear here"` (`:71`). No ViewModel.

**SimpleLiveStreamScreen** (`ui/screens/SimpleLiveStreamScreen.kt`) — full-screen preview of `wearablesViewModel.currentFrame`; `startStream()` on enter (`:46-48`), `stopStream()` on dispose (`:51-55`); hardcoded English "screen-record to go live" tips (`:163-194`).

**RTMPStreamingScreen** (`ui/screens/RTMPStreamingScreen.kt`) — VM `RTMPStreamingViewModel` (`:46`). Preview from `viewModel.previewFrame`; start/stop button (`:284-309`); stats overlay (`:201-226`); `RTMPSettingsDialog` (`:328-421`) with URL field and bitrate radio list 500k/1M/2M/4M/6M (`:338-344`). `cameraState` is collected (`:52`) but never used. Hardcoded English strings throughout. Stops streaming on dispose (`:60-64`).

**QuickVisionModeScreen / LiveAIModeScreen** (`ui/screens/ModeSettingsScreen.kt:35-133`, `:141-254`) — bind directly to `QuickVisionModeManager` / `LiveAIModeManager` singletons (`:39`, `:145`); mode list, translate target language list (only for TRANSLATE), custom prompt editor (only for CUSTOM) saved on every keystroke (`:117-120`, `:231-234`).

Shared components: `ui/components/CommonComponents.kt` — `GradientButton`, `FeatureCard`, `StatusBadge`, `SectionHeader`, `TurboMetaTopBar`, `LoadingIndicator`, `ErrorMessage`, `SuccessMessage`, `EmptyState`, `ConfirmDialog`, `NutritionBar`, `HealthScoreCircle` (`:29-392`). Theme tokens in `ui/theme/Color.kt`, `Theme.kt` (`AppSpacing`, `AppRadius` `:78-92`), `Type.kt`.

---

## 2. Services and managers

### 2.1 ViewModels

| Class | File | Scope / threading | Responsibility |
|---|---|---|---|
| `WearablesViewModel : AndroidViewModel` | `viewmodels/WearablesViewModel.kt` | `viewModelScope` (Main.immediate). Frame decode runs **on main** inside `videoJob = viewModelScope.launch { session.videoStream.collect { handleVideoFrame(it) } }` (`:293-298`, `:411-441`). | All DAT integration for UI screens (see §4). Exposes `connectionState`, `registrationState`, `streamState`, `currentFrame`, `capturedPhoto`, `devices`, `hasActiveDevice`, `errorMessage`, `isStreaming`, `batteryLevel` (never set) (`:71-99`). |
| `OmniRealtimeViewModel` | `viewmodels/OmniRealtimeViewModel.kt` | `viewModelScope` | Owns `OmniRealtimeService` or `GeminiLiveService` per `LiveAIProvider` (`:111-128`); re-initializes on provider change (`:95-107`); builds `ConversationMessage` list; saves `ConversationRecord` on disconnect/onCleared (`:390-402`, `:422`); owns a `BluetoothAudioManager` (`:40`) and exposes `currentAudioSource`, `isBluetoothScoConnected`, `isBluetoothScoAvailable` (`:86-88`). |
| `RTMPStreamingViewModel` | `viewmodels/RTMPStreamingViewModel.kt` | `viewModelScope`; frame handling on main (`:204-209`) | DAT stream → `RTMPStreamingService.feedFrame` + JPEG preview; RTMP URL persisted via `APIKeyManager.saveRtmpUrl` (`:125-130`); bitrate default 2 Mbps (`:65`); `DEFAULT_RTMP_URL = "rtmp://localhost/live/stream"` (`:39`). |
| `SettingsViewModel` | `viewmodels/SettingsViewModel.kt` | `viewModelScope` | Facade over `APIKeyManager`, `APIProviderManager`, `ConversationStorage`, `LanguageManager`; many dialog-visibility StateFlows (`:92-124`); `EditingKeyType { ALIBABA_BEIJING, ALIBABA_SINGAPORE, OPENROUTER, GOOGLE }` (`:135-140`). |
| `RecordsViewModel` | `viewmodels/RecordsViewModel.kt` | `viewModelScope` (storage I/O is synchronous prefs reads on main) | Loads/deletes conversations and Quick Vision records; `RecordsTab` enum (`:15-18`). |
| `VisionViewModel` | `viewmodels/VisionViewModel.kt` | `viewModelScope` | Custom-prompt image analysis via `VisionAPIService`; save to MediaStore `Pictures/TurboMeta` (`:137-172`). |
| `LeanEatViewModel` | `viewmodels/LeanEatViewModel.kt` | `viewModelScope` | Food analysis via `LeanEatService(apiKey)` using `apiKeyManager.getAPIKey()` (`:54`, `:74`) — i.e. the current Vision-provider key regardless of provider. |

### 2.2 Network/AI services

**`OmniRealtimeService`** (`services/OmniRealtimeService.kt`) — Alibaba Qwen Omni Realtime over OkHttp WebSocket.
- Ctor `(apiKey, model = "qwen3-omni-flash-realtime", outputLanguage = "zh-CN", endpoint = AlibabaEndpoint.BEIJING, context: Context? = null)` (`:32-38`).
- Endpoints: `wss://dashscope.aliyuncs.com/api-ws/v1/realtime` / `wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime` (`:41-42`); URL `"$websocketURL?model=$model"` with `Authorization: Bearer` header (`:116-121`).
- Scope: `CoroutineScope(Dispatchers.IO + SupervisorJob())`, cancelled on `disconnect()` and recreated on next `connect()` (`:86`, `:110-114`, `:157`).
- Session config sent on open (`:260-286`): `{"type":"session.update","session":{"modalities":["text","audio"],"voice":"Cherry","input_audio_format":"pcm16","output_audio_format":"pcm16","smooth_output":true,"instructions":<LiveAIModeManager.getSystemPrompt() or built-in prompt>,"turn_detection":{"type":"server_vad","threshold":0.5,"silence_duration_ms":800}}}`.
- Outbound: `input_audio_buffer.append` `{audio: base64}` per `AudioRecord` read (`:325-334`); every 500 ms also `input_image_buffer.append` `{image: base64 JPEG q60}` of the latest frame (`:99-101`, `:336-361`).
- Inbound events handled (`:363-412`): `session.created|updated`, `input_audio_buffer.speech_started` (stops playback), `input_audio_buffer.speech_stopped`, `response.audio_transcript.delta|done`, `conversation.item.input_audio_transcription.completed` (`transcript`), `response.audio.delta` (base64 PCM16 → `AudioTrack`), `response.audio.done`, `error.error.message`.
- Audio: capture `AudioRecord(source, sampleRate, CHANNEL_IN_MONO, PCM_16BIT)` (`:184-193`) where sampleRate = 16000 for Bluetooth mic / 24000 phone (`:163-168`), source `VOICE_COMMUNICATION` vs `MIC` (`:173-178`); playback `AudioTrack` 24 kHz mono, `USAGE_VOICE_COMMUNICATION` when BT mic else `USAGE_MEDIA` (`:424-459`). `convertPcm24ToPcm16` (`:500-526`) is dead code.
- Callbacks: `onTranscriptDelta`, `onTranscriptDone`, `onUserTranscript`, `onSpeechStarted`, `onSpeechStopped`, `onError` (`:71-76`).

**`GeminiLiveService`** (`services/GeminiLiveService.kt`) — Google Gemini Live BidiGenerateContent WebSocket.
- Ctor `(apiKey, model = "gemini-2.0-flash-exp", outputLanguage = "zh-CN", context: Context? = null)` (`:29-34`); URL `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey` (`:37`, `:117` — API key in query string).
- OkHttp client with `pingInterval(30s)` (`:102-105`); scope `Dispatchers.IO + SupervisorJob` (`:80`).
- Setup message (`:185-216`): `{"setup":{"model":"models/<model>","generation_config":{"response_modalities":["AUDIO"],"speech_config":{"voice_config":{"prebuilt_voice_config":{"voice_name":"Aoede"}}}},"system_instruction":{"parts":[{"text":<prompt>}]}}}`. Prompt comes only from the built-in `getLiveAIPrompt(outputLanguage)` (`:189`) — **does not use `LiveAIModeManager`** (unlike Omni).
- Outbound: `{"realtime_input":{"media_chunks":[{"mime_type":"audio/pcm;rate=16000","data":base64}]}}` (`:344-361`); image sent once on first audio chunk (`:363-370`) or via `sendImageInput` (`:373-397`, mime `image/jpeg`).
- Inbound (`:401-502`): `setupComplete`, `serverContent.modelTurn.parts[].text|inlineData(audio)`, `turnComplete`, `interrupted`, `inputTranscription.text`, `outputTranscription.text`, `toolCall` (logged only), `error.message`.
- Audio: input fixed 16 kHz (`:40`, `:168-171`), output 24 kHz (`:41`); buffers 2 chunks before starting playback (`:88`, `:506-527`).
- Extra callbacks `onConnected`, `onFirstAudioSent` (`:69-70`).
- `OmniRealtimeViewModel` constructs it **without context** (`viewmodels/OmniRealtimeViewModel.kt:182`), so its internal `bluetoothAudioManager` is null and `switchAudioSource` inside the service only changes the `AudioRecord` source (`:318-338`).

**`VisionAPIService`** (`services/VisionAPIService.kt`) — OpenAI-compatible chat completions with image.
- Ctor `(apiKeyManager, providerManager, context: Context? = null)` (`:28-32`). Base URLs `:37-39`; provider/endpoint/model resolved live from `APIProviderManager` StateFlows (`:56-78`).
- `suspend fun analyzeImage(image: Bitmap, prompt: String): Result<String>` on `Dispatchers.IO` (`:82-133`): POST `$baseURL/chat/completions`, headers `Authorization: Bearer`, `Content-Type`, plus for OpenRouter `HTTP-Referer: https://turbometa.app`, `X-Title: TurboMeta` (`:100-104`). Body `{"model":..,"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,.."}},{"type":"text","text":prompt}]}],"max_tokens":2000}` (`:210-236`); JPEG q80 (`:203-208`); parses `choices[0].message.content` (`:238-252`).
- `suspend fun quickVision(image, language = "zh-CN")` (`:138-150`) uses `QuickVisionModeManager.getPrompt()` when context present, else built-in per-language prompt (`:155-199`).
- `sealed class VisionAPIError { InvalidImage, EmptyResponse, InvalidResponse, NoAPIKey, APIError(message) }` (`:255-261`).
- Unused constants `DEFAULT_ALIBABA_MODEL = "qwen-vl-plus"`, `DEFAULT_OPENROUTER_MODEL = "google/gemini-3-flash-preview"` (`:42-43`) disagree with `APIProviderManager` defaults.

**`LeanEatService`** (`services/LeanEatService.kt`) — `class LeanEatService(private val apiKey: String)` (`:18`); hardcoded Beijing Dashscope URL `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` and model `qwen-vl-plus` (`:21-22`); Chinese JSON-schema prompt (`:24-57`); `suspend fun analyzeFood(image): Result<FoodNutritionResponse>` on IO (`:68-100`); lenient JSON extraction `{...}` (`:157-166`). Ignores provider/endpoint selection.

**`RTMPStreamingService`** (`services/RTMPStreamingService.kt`) — RootEncoder `com.pedro.rtmp.rtmp.RtmpClient` + `ConnectCheckerRtmp` (`:8-9`, `:124-159`).
- Scope `Dispatchers.Default + SupervisorJob` (`:54`); `startStreaming(rtmpUrl, width, height, bitrate = 2_000_000)` on IO (`:97-178`).
- Encoder: `MediaCodec` `video/avc`, `COLOR_FormatYUV420Planar` (I420 direct), `KEY_FRAME_RATE 24`, `KEY_I_FRAME_INTERVAL 1`, `KEY_LATENCY 0` (`:183-210`). Output loop on IO polls `dequeueOutputBuffer(…, 10000)` (`:215-248`); SPS/PPS parsed from Annex-B codec-config and pushed via `rtmpClient.setVideoInfo(sps, pps, null)` (`:253-288`); frames via `rtmpClient.sendVideo(ByteBuffer, bufferInfo)` (`:301`).
- `feedFrame(buffer: ByteBuffer, width, height, timestampUs)` (`:346-399`): copies the DAT buffer, validates `w*h*3/2`, smooths timestamps to exactly 24 fps (`baseTimestampUs + frameIndex * 41666`) (`:376-384`), counts drops. An older `feedFrame(ByteArray, …)` overload (`:316-330`) is unused.
- No audio is sent (video-only RTMP).
- `StreamingState { Idle, Connecting, Streaming, Error(message), Disconnected }` (`:46-52`); `StreamingStats(framesSent, bitrate, fps, connectionTime)` (`:83-88`).

### 2.3 Android `Service`s (foreground)

**`PorcupineWakeWordService`** (`services/PorcupineWakeWordService.kt`) — manifest `foregroundServiceType="microphone"` (`AndroidManifest.xml:69-72`).
- Picovoice `PorcupineManager.Builder().setAccessKey(key).setKeyword(Porcupine.BuiltInKeyword.JARVIS).setSensitivity(0.7f).build(this, callback)` (`:148-152`); Porcupine records from the **phone mic** itself (no BT SCO handling).
- Access key in plain `SharedPreferences("porcupine_prefs")["porcupine_access_key"]` (`:48-62`) — comment admits it should be encrypted (`:46-47`).
- Debounce 10 s + `isProcessing` flag reset by receiving `QuickVisionService.ACTION_QUICK_VISION_STATUS` = `"finished"`/`"error"` (`:52`, `:79-88`, `:183-210`).
- On detection: broadcasts `com.smartview.glassai.WAKE_WORD_DETECTED` (extra `keyword_index`) then `startForegroundService(QuickVisionService, ACTION_CAPTURE_AND_ANALYZE)` (`:202-225`).
- `START_STICKY` (`:111`); channel `porcupine_wake_word_channel` IMPORTANCE_LOW (`:227-241`); notification with "Stop" action (`:243-267`).

**`QuickVisionService`** (`services/QuickVisionService.kt`) — manifest `foregroundServiceType="microphone"` (`AndroidManifest.xml:74-78`) although it uses the glasses camera + TTS, not the mic.
- Scope `CoroutineScope(Dispatchers.Main + SupervisorJob())` (`:77`); its own `TextToSpeech` (`:111`, `:114-146`) with output-locale mapping (`:121-130`) and system-locale status phrases hardcoded in zh/ja/ko/en (`:399-444`).
- Flow `captureAndAnalyze()` (`:185-322`): wait TTS ≤2 s → speak "looking" → `deviceSelector.activeDevice(Wearables.devices).first()` (`:210`) → `Wearables.startStreamSession(this, deviceSelector, StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))` (`:224-228`) → collect `session.state` until `STREAMING` (`:234-241`) and grab first frame from `session.videoStream` (`:243-251`, JPEG q85 `:324-347`) with an 8 s busy-wait (`:254-258`) → `session.close()` (`:264`) → `visionService.quickVision(image, language)` (`:275`) → `quickVisionStorage.saveRecord(...)` (`:285-291`) → broadcast + TTS → `finishService()` (`:475-481`).
- Broadcast contract: `ACTION_QUICK_VISION_STATUS = "com.smartview.glassai.QUICK_VISION_STATUS"` with `EXTRA_STATUS = "status"` ∈ {`started`, `streaming`, `analyzing`, `complete`, `error`, `finished`}; `ACTION_ANALYSIS_COMPLETE = "com.smartview.glassai.ANALYSIS_COMPLETE"` with `EXTRA_RESULT = "analysis_result"` / `EXTRA_ERROR = "analysis_error"` (`:66-74`, `:446-469`). All `setPackage(packageName)`.
- Channel `quick_vision_channel` IMPORTANCE_HIGH, vibrates (`:490-506`); `START_NOT_STICKY` (`:160`).

### 2.4 Managers

**`APIProviderManager`** (`managers/APIProviderManager.kt`, singleton via `getInstance(context)` `:250-254`).
- `enum AlibabaEndpoint(id) { BEIJING("beijing"), SINGAPORE("singapore") }` with `baseURL` (`https://dashscope.aliyuncs.com/compatible-mode/v1` / `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`) and `websocketURL` (`wss://dashscope[-intl].aliyuncs.com/api-ws/v1/realtime`) (`:23-56`).
- `enum APIProvider(id) { ALIBABA("alibaba"), OPENROUTER("openrouter") }`; `baseURL(endpoint)`; `defaultModel` = `qwen-vl-flash` / `google/gemini-2.0-flash-001`; help URLs (`:60-103`).
- `enum LiveAIProvider(id) { ALIBABA("alibaba"), GOOGLE("google") }`; `defaultModel` = `qwen3-omni-flash-realtime` / `gemini-2.0-flash-exp`; `websocketURL(endpoint)` (`:107-147`).
- `data class OpenRouterModel(id, name, description?, @SerializedName("context_length") contextLength?, pricing?: Pricing(prompt, completion), architecture?: Architecture(modality?, tokenizer?, @SerializedName("instruct_type") instructType?))` with `isVisionCapable` heuristic (modality contains "image"/"multimodal" or id contains vision/vl/gpt-4o/claude-3/gemini) (`:151-196`); `OpenRouterModelsResponse(data)` (`:198-200`).
- `AlibabaVisionModel.availableModels`: `qwen-vl-flash`, `qwen-vl-plus`, `qwen-vl-max`, `qwen2.5-vl-72b-instruct` (`:204-233`).
- Static accessors `staticCurrentProvider`, `staticAlibabaEndpoint`, `staticLiveAIProvider`, `staticCurrentModel`, `staticBaseURL` read a static `prefs` reference set in `init` (`:256-285`, `:294-297`) — null (defaults) until the first `getInstance`.
- `suspend fun fetchOpenRouterModels(apiKeyManager)` GET `https://openrouter.ai/api/v1/models` with `Authorization: Bearer`, `X-Title: TurboMeta`, on IO; sorts vision-capable first (`:417-463`). OkHttp 30 s timeouts (`:289-292`).

**`APIKeyManager`** (`utils/APIKeyManager.kt`) — see §3.

**`LanguageManager`** (`managers/LanguageManager.kt`, `object`) — `enum AppLanguage(code, displayName, nativeName) { SYSTEM("system"), CHINESE("zh-CN"), ENGLISH("en") }` (`:15-25`); applies via `AppCompatDelegate.setApplicationLocales` (`:53-61`); `init(context)` called in `MainActivity.onCreate` (`MainActivity.kt:84`).

**`QuickVisionModeManager`** / **`LiveAIModeManager`** (`managers/QuickVisionModeManager.kt`, `managers/LiveAIModeManager.kt`) — singletons holding `currentMode`, `customPrompt`, `translateTargetLanguage` StateFlows backed by prefs; `getPrompt()` / `getSystemPrompt()` resolve `R.string.prompt_*` resources and substitute `{LANGUAGE}` for translate mode (`QuickVisionModeManager.kt:93-119`, `LiveAIModeManager.kt:93-119`). `supportedLanguages` = 10 (zh-CN, en-US, ja-JP, ko-KR, fr-FR, de-DE, es-ES, it-IT, pt-BR, ru-RU) (`:33-44` in both). `LiveAIMode.autoSendImageOnSpeech()` is `true` for every mode (`models/LiveAIMode.kt:58-64`) and is never consulted by the services.

**`BluetoothAudioManager`** — see §5.

### 2.5 Models (`models/`)
- `QuickVisionRecord(id, timestamp, thumbnailPath, prompt, result, mode: QuickVisionMode = STANDARD, visionModel = "qwen-vl-plus")` (`QuickVisionRecord.kt:9-17`).
- `ConversationRecord(id, timestamp, messages: List<ConversationMessage>, aiModel = "qwen3-omni-flash-realtime", language = "zh-CN")`; `ConversationMessage(id, role: MessageRole, content, timestamp)`; `enum MessageRole { USER, ASSISTANT }` (`ConversationModels.kt:6-64`).
- `FoodNutritionResponse(foods, totalCalories, totalProtein, totalFat, totalCarbs, healthScore, suggestions)`, `FoodItem(name, portion, calories, protein, fat, carbs, fiber?, sugar?, healthRating = "良好")` — Chinese rating strings drive colors (`FoodNutritionModels.kt:9-63`).
- `enum QuickVisionMode(id) { STANDARD, HEALTH, BLIND, READING, TRANSLATE, ENCYCLOPEDIA, CUSTOM }` ids `standard|health|blind|reading|translate|encyclopedia|custom` (`QuickVisionMode.kt:10-17`).
- `enum LiveAIMode(id) { STANDARD, MUSEUM, BLIND, READING, TRANSLATE, CUSTOM }` (`LiveAIMode.kt:10-16`).
- `enum AIModel(id, displayName)`: `qwen3-omni-flash-realtime`, `qwen3-omni-standard-realtime`, `gemini-2.0-flash-exp` (`utils/APIKeyManager.kt:228-234`); `enum OutputLanguage(code…)`: zh-CN, en-US, ja-JP, ko-KR, es-ES, fr-FR (`:237-244`); `enum StreamQuality(id, displayNameResId, descriptionResId) { LOW, MEDIUM, HIGH }` (`:247-251`).

---

## 3. Settings and persistence (every key and default)

No DataStore or Room is used anywhere (`androidx-datastore-preferences` is a declared dependency at `build.gradle.kts:112` but never imported; Room is in the catalog but not applied). All persistence is `SharedPreferences` + Gson JSON + files.

### 3.1 `APIKeyManager` — `EncryptedSharedPreferences` file `"turbometa_secure_prefs"` (`utils/APIKeyManager.kt:21`, `:46-56`; MasterKey AES256_GCM, key scheme AES256_SIV, value scheme AES256_GCM)

| Key | Type | Default | Accessor | Notes |
|---|---|---|---|---|
| `alibaba-beijing-api-key` | String | null | `getAPIKey(APIProvider.ALIBABA, AlibabaEndpoint.BEIJING)` (`:94-102`) | migrated from legacy `qwen_api_key` on init (`:64-78`) |
| `alibaba-singapore-api-key` | String | null | same with `SINGAPORE` | |
| `openrouter-api-key` | String | null | `getAPIKey(APIProvider.OPENROUTER)` | |
| `google-api-key` | String | null | `getGoogleAPIKey()` (`:132-139`) | Live AI (Gemini) only |
| `qwen_api_key` | String | — | legacy; removed after migration | |
| `ai_model` | String | `"qwen3-omni-flash-realtime"` | `getAIModel()` (`:195-197`) | **dead**: nothing calls `saveAIModel/getAIModel`; Live AI model actually lives in `api_provider_prefs.liveai_model` |
| `output_language` | String | `"zh-CN"` | `getOutputLanguage()` (`:204-206`) | TTS + prompt language |
| `video_quality` | String | `"MEDIUM"` | `getVideoQuality()` (`:213-215`) | `"LOW"|"MEDIUM"|"HIGH"` → `VideoQuality` |
| `rtmp_url` | String | null | `getRtmpUrl()` (`:222-224`) | VM falls back to `rtmp://localhost/live/stream` (`viewmodels/RTMPStreamingViewModel.kt:39`) |

Provider-agnostic overloads `saveAPIKey(key)` / `getAPIKey()` / `hasAPIKey()` resolve via `APIProviderManager.staticCurrentProvider` + `staticAlibabaEndpoint` (`:157-171`).

### 3.2 `APIProviderManager` — `"api_provider_prefs"` (`managers/APIProviderManager.kt:240`)

| Key | Default | Setter |
|---|---|---|
| `api_provider` | `"alibaba"` | `setCurrentProvider` (`:339-348`, resets model to provider default) |
| `selected_vision_model` | provider default (`qwen-vl-flash`) | `setSelectedModel` (`:350-353`) |
| `alibaba_endpoint` | `"beijing"` | `setAlibabaEndpoint` (`:355-358`) |
| `liveai_provider` | `"alibaba"` | `setLiveAIProvider` (`:360-368`, resets model) |
| `liveai_model` | `qwen3-omni-flash-realtime` | `setLiveAIModel` (`:370-373`) |

### 3.3 `LanguageManager` — `"language_prefs"` (`managers/LanguageManager.kt:28`): `app_language` default `"system"` (`:29`, `:35`). `SettingsViewModel.selectAppLanguage` also rewrites `output_language` to `zh-CN`/`en-US` (`viewmodels/SettingsViewModel.kt:366-385`).

### 3.4 `QuickVisionModeManager` — `"quick_vision_prefs"` (`:18`): `quick_vision_mode` default `"standard"` (`:19`, `:63`); `quick_vision_custom_prompt` default `R.string.quickvision_custom_default` (`:20`, `:68`); `quick_vision_translate_target_language` default `"zh-CN"` (`:21`, `:72`).

### 3.5 `LiveAIModeManager` — `"live_ai_prefs"` (`:18`): `live_ai_mode` default `"standard"`; `live_ai_custom_prompt` default `R.string.liveai_custom_default`; `live_ai_translate_target_language` default `"zh-CN"` (`:19-21`, `:63-72`).

### 3.6 `PorcupineWakeWordService` — plain `"porcupine_prefs"`: `porcupine_access_key` (`services/PorcupineWakeWordService.kt:48-62`).

### 3.7 `ConversationStorage` — `"turbometa_conversations"` key `saved_conversations` = Gson `List<ConversationRecord>`, capped at 100, newest first (`data/ConversationStorage.kt:17-19`, `:31-57`). Singleton `getInstance` (`:24-28`).

### 3.8 `QuickVisionStorage` — `"turbometa_quick_vision"` key `saved_records` = Gson `List<QuickVisionRecord>`, cap 100 (`data/QuickVisionStorage.kt:30-33`); thumbnails at `filesDir/quick_vision_thumbnails/<uuid>.jpg`, scaled to 480 px wide, JPEG q85 (`:162-188`); deleting a record deletes its file (`:117-134`).

### 3.9 Other files: `FileProvider` authority `${applicationId}.fileprovider` with `cache-path images/` and `files-path photos/` (`AndroidManifest.xml:58-66`, `res/xml/file_paths.xml`) — not referenced by any Kotlin code. `android:allowBackup="true"` (`AndroidManifest.xml:25`) so the encrypted prefs file is included in auto-backup (it will be unreadable on restore to another device).

---

## 4. DAT SDK usage inventory (complete) and 0.4.0 → 0.9.0 migration notes

### 4.1 Every `import com.meta.wearable…` line

| File:line | Import |
|---|---|
| `MainActivity.kt:16` | `com.meta.wearable.dat.core.Wearables` |
| `MainActivity.kt:17` | `com.meta.wearable.dat.core.types.Permission` |
| `MainActivity.kt:18` | `com.meta.wearable.dat.core.types.PermissionStatus` |
| `services/QuickVisionService.kt:23` | `com.meta.wearable.dat.camera.StreamSession` |
| `services/QuickVisionService.kt:24` | `com.meta.wearable.dat.camera.startStreamSession` |
| `services/QuickVisionService.kt:25` | `com.meta.wearable.dat.camera.types.StreamConfiguration` |
| `services/QuickVisionService.kt:26` | `com.meta.wearable.dat.camera.types.StreamSessionState` |
| `services/QuickVisionService.kt:27` | `com.meta.wearable.dat.camera.types.VideoFrame` |
| `services/QuickVisionService.kt:28` | `com.meta.wearable.dat.camera.types.VideoQuality` |
| `services/QuickVisionService.kt:29` | `com.meta.wearable.dat.core.Wearables` |
| `services/QuickVisionService.kt:30` | `com.meta.wearable.dat.core.selectors.AutoDeviceSelector` |
| `ui/navigation/Navigation.kt:18` | `com.meta.wearable.dat.core.types.Permission` |
| `ui/navigation/Navigation.kt:19` | `com.meta.wearable.dat.core.types.PermissionStatus` |
| `ui/screens/HomeScreen.kt:34` | `com.meta.wearable.dat.core.Wearables` |
| `ui/screens/HomeScreen.kt:35` | `com.meta.wearable.dat.core.types.Permission` |
| `ui/screens/HomeScreen.kt:36` | `com.meta.wearable.dat.core.types.PermissionStatus` |
| `ui/screens/LiveAIScreen.kt:36` | `com.meta.wearable.dat.core.types.Permission` |
| `ui/screens/LiveAIScreen.kt:37` | `com.meta.wearable.dat.core.types.PermissionStatus` |
| `ui/screens/RTMPStreamingScreen.kt:36` | `com.meta.wearable.dat.camera.types.StreamSessionState` (only for the unused `cameraState` at `:52`) |
| `viewmodels/RTMPStreamingViewModel.kt:12` | `com.meta.wearable.dat.camera.StreamSession` |
| `viewmodels/RTMPStreamingViewModel.kt:13` | `com.meta.wearable.dat.camera.startStreamSession` |
| `viewmodels/RTMPStreamingViewModel.kt:14` | `com.meta.wearable.dat.camera.types.StreamConfiguration` |
| `viewmodels/RTMPStreamingViewModel.kt:15` | `com.meta.wearable.dat.camera.types.StreamSessionState` |
| `viewmodels/RTMPStreamingViewModel.kt:16` | `com.meta.wearable.dat.camera.types.VideoFrame` |
| `viewmodels/RTMPStreamingViewModel.kt:17` | `com.meta.wearable.dat.camera.types.VideoQuality` |
| `viewmodels/RTMPStreamingViewModel.kt:18` | `com.meta.wearable.dat.core.Wearables` |
| `viewmodels/RTMPStreamingViewModel.kt:19` | `com.meta.wearable.dat.core.selectors.AutoDeviceSelector` |
| `viewmodels/RTMPStreamingViewModel.kt:20` | `com.meta.wearable.dat.core.selectors.DeviceSelector` |
| `viewmodels/WearablesViewModel.kt:12` | `com.meta.wearable.dat.camera.StreamSession` |
| `viewmodels/WearablesViewModel.kt:13` | `com.meta.wearable.dat.camera.startStreamSession` |
| `viewmodels/WearablesViewModel.kt:14` | `com.meta.wearable.dat.camera.types.StreamConfiguration` |
| `viewmodels/WearablesViewModel.kt:15` | `com.meta.wearable.dat.camera.types.StreamSessionState` |
| `viewmodels/WearablesViewModel.kt:16` | `com.meta.wearable.dat.camera.types.VideoFrame` |
| `viewmodels/WearablesViewModel.kt:17` | `com.meta.wearable.dat.camera.types.VideoQuality` |
| `viewmodels/WearablesViewModel.kt:18` | `com.meta.wearable.dat.core.Wearables` |
| `viewmodels/WearablesViewModel.kt:19` | `com.meta.wearable.dat.core.selectors.AutoDeviceSelector` |
| `viewmodels/WearablesViewModel.kt:21` | `com.meta.wearable.dat.core.selectors.DeviceSelector` |
| `viewmodels/WearablesViewModel.kt:22` | `com.meta.wearable.dat.core.types.DeviceIdentifier` |
| `viewmodels/WearablesViewModel.kt:23` | `com.meta.wearable.dat.core.types.Permission` |
| `viewmodels/WearablesViewModel.kt:24` | `com.meta.wearable.dat.core.types.PermissionStatus` |
| `viewmodels/WearablesViewModel.kt:25` | `com.meta.wearable.dat.core.types.RegistrationState` |
| `viewmodels/WearablesViewModel.kt:385-386` | fully-qualified `com.meta.wearable.dat.camera.types.PhotoData.Bitmap` / `PhotoData.HEIC` |

Files touching DAT: `MainActivity.kt`, `viewmodels/WearablesViewModel.kt`, `viewmodels/RTMPStreamingViewModel.kt`, `services/QuickVisionService.kt`, `ui/screens/HomeScreen.kt`, `ui/screens/RTMPStreamingScreen.kt`, `ui/navigation/Navigation.kt`, `ui/screens/LiveAIScreen.kt` (type-only). `AndroidManifest.xml` + `proguard-rules.pro` for config.

### 4.2 Every call site, with exact API used

**Initialization / registration / devices**
- `Wearables.initialize(this)` — `MainActivity.kt:123`, inside `initializeSDK()` (`:118-127`), gated by `sdkInitialized` flag and by **all four** runtime permissions being granted (`PERMISSIONS = [BLUETOOTH, BLUETOOTH_CONNECT, INTERNET, RECORD_AUDIO]` `:33-38`; launcher `:48-57`; `checkAndRequestPermissions` `:104-116`). If the user denies RECORD_AUDIO, the SDK is never initialized and `wearablesViewModel.setError(R.string.permission_all_required)` is shown (`:55`). Return value ignored.
- `wearablesViewModel.startMonitoring()` — `MainActivity.kt:126`, right after initialize.
- `Wearables.registrationState.collect { state -> … }` — `WearablesViewModel.kt:144-166`; `when` over sealed cases `RegistrationState.Registered`, `.Unavailable`, `.Available`, `.Registering`, `.Unregistering` (`:148-164`). Initial value constructed as `RegistrationState.Unavailable()` (`:74`). `isRegistered` getter `is RegistrationState.Registered` (`:471-472`).
- `Wearables.devices.collect { deviceSet -> _devices.value = deviceSet.toList() }` — `WearablesViewModel.kt:171-174` (`Set<DeviceIdentifier>`).
- `deviceSelector.activeDevice(Wearables.devices).collect { device -> … }` — `WearablesViewModel.kt:126-139` (Flow form; sets `hasActiveDevice`, `ConnectionState.Registered(device.toString())`).
- `deviceSelector.activeDevice(Wearables.devices).first() != null` — `services/QuickVisionService.kt:210`.
- `val deviceSelector: DeviceSelector = AutoDeviceSelector()` — `WearablesViewModel.kt:102`; `private val deviceSelector = AutoDeviceSelector()` — `QuickVisionService.kt:91`; `RTMPStreamingViewModel.kt:70`. Three independent selectors.
- `Wearables.startRegistration(getApplication())` — `WearablesViewModel.kt:192` (passes `Application`); `Wearables.startUnregistration(getApplication())` — `:197`. Invoked from `startDeviceSearch()` (`:178-182`) and `disconnect()` (`:200-207`).

**Permissions**
- `Wearables.RequestPermissionContract()` registered via `registerForActivityResult` — `MainActivity.kt:60-66`; result handled as `result.getOrDefault(PermissionStatus.Denied)` (`:63`) and resumed into `suspend fun requestWearablesPermission(permission: Permission): PermissionStatus` (`:69-77`, Mutex-serialized). Passed down as `onRequestWearablesPermission` through `Navigation.kt:55` to `HomeScreen.kt:46` and `LiveAIScreen.kt:52`.
- `Wearables.checkPermissionStatus(Permission.CAMERA)` (suspend, returns `DatResult`) — `HomeScreen.kt:76` (`result.getOrNull() == PermissionStatus.Granted` `:78-79`); `WearablesViewModel.kt:213` (`result.onFailure { error, _ -> … error.description }` `:215-218`, `getOrNull()` `:220`); `WearablesViewModel.kt:245` (`checkCameraPermission()`); `WearablesViewModel.navigateToStreaming` (`:210-237`, unused by UI).
- `PermissionStatus.Granted` / `PermissionStatus.Denied` comparisons — `HomeScreen.kt:79,90,91`; `WearablesViewModel.kt:221,229,232,246`; `MainActivity.kt:63`.

**Streaming (3 independent implementations of the same pattern)**
- `Wearables.startStreamSession(getApplication(), deviceSelector, StreamConfiguration(videoQuality = videoQuality, 24))` — `WearablesViewModel.kt:284-288`; quality from `APIKeyManager.getVideoQuality()` mapped `"LOW"|"HIGH"|else→MEDIUM` (`:274-280`).
- `Wearables.startStreamSession(this@QuickVisionService, deviceSelector, StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))` — `QuickVisionService.kt:224-228`.
- `Wearables.startStreamSession(getApplication(), deviceSelector, StreamConfiguration(videoQuality = videoQuality, 24))` — `RTMPStreamingViewModel.kt:176-180`.
- `private var streamSession: StreamSession?` — `WearablesViewModel.kt:106`, `QuickVisionService.kt:92`, `RTMPStreamingViewModel.kt:73`.
- `session.videoStream.collect { videoFrame -> … }` — `WearablesViewModel.kt:295-297`, `QuickVisionService.kt:244-250`, `RTMPStreamingViewModel.kt:206-208`.
- `session.state.collect { … }` with `StreamSessionState.STREAMING / STOPPED / STARTING / else` — `WearablesViewModel.kt:303-333`; `QuickVisionService.kt:235-240` (`== StreamSessionState.STREAMING`); `RTMPStreamingViewModel.kt:184-200`; `_cameraState: MutableStateFlow<StreamSessionState?>` `RTMPStreamingViewModel.kt:62-63`.
- `streamSession.close()` / `session.close()` / `oldSession.close()` — `WearablesViewModel.kt:265, 353`; `QuickVisionService.kt:180, 264`; `RTMPStreamingViewModel.kt:161, 317`.
- `VideoFrame.buffer` (`ByteBuffer`), `.width`, `.height` — `WearablesViewModel.kt:413-429`; `QuickVisionService.kt:326-338`; `RTMPStreamingViewModel.kt:231-256, 264-277`. Frames are treated as raw I420 (`convertI420toNV21` duplicated at `WearablesViewModel.kt:444-456`, `QuickVisionService.kt:349-361`, `RTMPStreamingViewModel.kt:288-300`), re-encoded to JPEG (q50 preview / q85 capture) and decoded to `Bitmap`.
- `streamSession?.capturePhoto()?.onSuccess { photoData -> … }?.onFailure { … }` — `WearablesViewModel.kt:382-399`; `PhotoData.Bitmap.bitmap` and `PhotoData.HEIC.data: ByteBuffer` → `BitmapFactory.decodeByteArray` (`:384-391`, no EXIF orientation handling).
- `VideoQuality.LOW/MEDIUM/HIGH` — `WearablesViewModel.kt:277-279`, `RTMPStreamingViewModel.kt:168-170`, `QuickVisionService.kt:227`.
- `DeviceIdentifier` — only as the element type of `_devices` (`WearablesViewModel.kt:89-90`).

**Manifest / build**
- `<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="0" />` — `AndroidManifest.xml:34-36` (developer-mode). **No `CLIENT_TOKEN`**, no `DAM_ENABLED`, no `CRASH_REPORTING_OPT_OUT`.
- Callback intent filter `android:scheme="turbometa"` — `AndroidManifest.xml:49-54`.
- ProGuard keeps `com.meta.**`, `com.facebook.**`, protobuf, Tink — `proguard-rules.pro:5-17`.
- GitHub Packages maven repo with `github_username`/`github_token` from `local.properties` or `GITHUB_TOKEN` — `settings.gradle.kts:33-39`.

### 4.3 What breaks or changes going 0.4.0 → 0.9.0 (from upstream `CHANGELOG.md` at `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/CHANGELOG.md` and the 0.9.0 `samples/CameraAccess`)

| Current usage | 0.9.0 replacement | Source |
|---|---|---|
| `Wearables.startStreamSession(ctx, selector, config)` (3 sites) | Removed in 0.7.0. `Wearables.createSession(selector): DatResult<DeviceSession>` → `session.start()` → `session.addCamera(StreamConfiguration(videoQuality=…, frameRate=24)): DatResult<Camera>` (0.9.0; `addStream` removed) → `camera.stream: Stream` → `stream.start(): DatResult` → `stream.videoStream`, `stream.state: StateFlow<StreamState>`, `stream.errorStream`. Teardown `camera.stop()`/`camera.close()` then `session.stop()`; `session.removeCamera()` to detach. | CHANGELOG 0.7.0 "Changed", 0.9.0 "Added/Removed"; sample `camera/CameraViewModel.kt:156-171, 270-305, 308-313, 400-420` |
| `StreamSession` type | `Stream` (child of `Camera`) | CHANGELOG 0.7.0 |
| `StreamSessionState.{STARTING,STREAMING,STOPPED}` | `StreamState.{STOPPED, STARTING, STARTED, STREAMING, PAUSED (0.8.0), STOPPING, CLOSED}`; sample treats STOPPED **and** CLOSED as terminal (`CameraViewModel.kt:325`) | 0.7.0/0.8.0; docs integration guide |
| `StreamConfiguration(videoQuality = x, 24)` positional | `StreamConfiguration(videoQuality = x, frameRate = 24, compressVideo = false)` — keep `compressVideo=false` to continue receiving raw I420; `true` gives HEVC (`VideoFrame.isCompressed`, `isCodecConfig`) which the RTMP path could pass through instead of re-encoding | 0.6.0, 0.7.0 |
| `RegistrationState` sealed class (`Unavailable()`, `is …`) | plain enum `RegistrationState.{AVAILABLE, REGISTERED, REGISTERING, UNAVAILABLE, UNREGISTERING}`; errors via `Wearables.registrationErrorStream: Flow<RegistrationError>` | 0.7.0 |
| `deviceSelector.activeDevice(Wearables.devices)` (Flow) | `deviceSelector.activeDeviceFlow()` (flow) or `activeDevice(): DeviceIdentifier?` | 0.6.0; sample `wearables/WearablesViewModel.kt:58` |
| `Wearables.startRegistration(getApplication())` | signature takes `Activity` since 0.4.0 per CHANGELOG ("accept an Activity instead of a Context") — sample passes the Activity (`WearablesViewModel.kt:106-112`). Verify the current call compiles; plan to pass `MainActivity`. | 0.4.0 |
| `Wearables.initialize(this)` result ignored | now Java-visible `DatResult`; sample calls it from the permissions callback (`MainActivity.kt:52-59`); getting-started skill shows `.onFailure { error, _ -> }` | 0.9.0 |
| `capturePhoto().onFailure { }` single-param lambda (`WearablesViewModel.kt:396`) | `DatResult<PhotoData, CaptureError>.onFailure { error, _ -> }` (two params) since 0.5.0; `CaptureError.{DeviceDisconnected, NotStreaming, CaptureInProgress, CaptureFailed}` | 0.5.0 |
| HEIC decode without orientation | sample reads EXIF `TAG_ORIENTATION` via `androidx.exifinterface` and rotates (`CameraViewModel.kt:545-600`) — the app already declares `exifinterface` but never imports it | sample |
| Frame timestamps from `System.nanoTime()` (`RTMPStreamingViewModel.kt:243-248`) | `VideoFrame.presentationTimeUs` (added 0.4.0) | 0.4.0 |
| `Wearables.checkPermissionStatus`, `RequestPermissionContract`, `Permission.CAMERA`, `PermissionStatus.Granted/Denied` | unchanged | sample `MainActivity.kt:52-81` |
| `Wearables.devices`, `Wearables.registrationState` | unchanged | |
| Connection state inferred from stream (`Registered`→`Connected` on STREAMING, `WearablesViewModel.kt:311-314`) | can use `DeviceSession.state` (`DeviceSessionState.{IDLE,STARTING,STARTED,PAUSED,STOPPING,STOPPED}`), `Wearables.devicesMetadata[id]` → `Device.linkState: LinkState.{CONNECTING,CONNECTED,DISCONNECTED}` (0.5.0), `Device.compatibility` | 0.5.0–0.7.0; sample `WearablesViewModel.kt:79-104` |
| Manifest | add `com.meta.wearable.mwdat.CLIENT_TOKEN` (can be `"0"` in dev mode); optional `CRASH_REPORTING_OPT_OUT`; sample uses `manifestPlaceholders` `mwdat_application_id` / `mwdat_client_token` | docs integration guide; getting-started skill |
| Foreground service while streaming | sample runs a `foregroundServiceType="connectedDevice"` service with `FOREGROUND_SERVICE_CONNECTED_DEVICE` + `WAKE_LOCK` (`samples/CameraAccess/app/src/main/AndroidManifest.xml`) | sample |
| Frame processing thread | sample collects `videoStream` on `Dispatchers.Default.limitedParallelism(1)` (`CameraViewModel.kt:94, 317`) | sample |
| Mock testing | `MockDeviceKit.getInstance(ctx).enable(MockDeviceKitConfig(initiallyRegistered=…))`, `pairGlasses(GlassesModel.RAYBAN_META)`, `device.services.camera.setCameraFeed(uri)`, `permissions.set(...)`; `mwdat-mockdevice` currently commented out | 0.6.0–0.8.0; mockdevice-testing skill |
| Runtime requirements | 0.9.0 needs Meta AI app V282 and Ray-Ban Meta firmware V126 (0.4.0 was V254 / V20) | docs "Version Dependencies" |
| Enum cases removed in 0.9.0 (`StreamError.THERMAL_EMERGENCY`, `RegistrationError.INCOMPATIBLE_SDK_LEVEL`, `DeviceSessionError.{DEVICE_POWERED_OFF, NOT_INITIALIZED}`) | not referenced by the app | 0.9.0 |
| New capabilities available after migration | `mwdat-display` (Ray-Ban Display UI: `session.addDisplay`, `Display.sendContent {}`), `Wearables.getDeviceState(id)` (thermal), `Wearables.openFirmwareUpdate(activity)`, `DeviceSession.errors`, `DeviceType.META_GLASSES` | 0.7.0–0.9.0 |

Also: the 0.9.0 sample targets AGP 8.11.1 / Kotlin 2.2.21 / Compose BOM 2026.05.01 / lifecycle 2.10.0 (`samples/CameraAccess/gradle/libs.versions.toml`); the app is on AGP 8.6.0 / Kotlin 2.0.0 / BOM 2024.12.01 — check the 0.9.0 AAR's minimum Kotlin metadata before bumping only `mwdat`.

---

## 5. Glasses audio path (`managers/BluetoothAudioManager.kt`)

`class BluetoothAudioManager(private val context: Context)` (`:26`), instantiated **three ways**: `OmniRealtimeViewModel` (`viewmodels/OmniRealtimeViewModel.kt:40`), inside `OmniRealtimeService.init` when context is non-null (`services/OmniRealtimeService.kt:92-97`), and inside `GeminiLiveService.init` (`services/GeminiLiveService.kt:95-100`, but the VM passes no context so it stays null).

- `enum AudioSource { PHONE_MIC, BLUETOOTH_MIC }` (`:35-38`); StateFlows `currentAudioSource`, `isBluetoothAvailable`, `isBluetoothScoConnected`, alias `isBluetoothScoAvailable` (`:47-58`).
- Profile: `BluetoothAdapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)` (`:123`) — HFP only; availability = `bluetoothHeadset.connectedDevices.isNotEmpty()` and `BLUETOOTH_CONNECT` granted (`:133-158`). `isBluetoothScoAvailable()` additionally requires `audioManager.isBluetoothScoAvailableOffCall` (`:163-165`).
- SCO on: `audioManager.mode = MODE_IN_COMMUNICATION; audioManager.startBluetoothSco(); audioManager.isBluetoothScoOn = true` (`:195-209`). SCO off: reverse + `MODE_NORMAL` (`:214-229`). These `startBluetoothSco`/`isBluetoothScoOn` APIs are deprecated from API 31 (app minSdk is 31) in favour of `AudioManager.setCommunicationDevice(AudioDeviceInfo)`.
- State tracking: `BroadcastReceiver` on `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED` (`:60-91`, registered `RECEIVER_NOT_EXPORTED` on 33+ `:114-119`); on `SCO_AUDIO_STATE_CONNECTED` sets `currentAudioSource = BLUETOOTH_MIC`; on `DISCONNECTED` auto-falls back to `PHONE_MIC` (`:72-81`).
- `switchAudioSource(source)` (`:171-190`) starts/stops SCO but does **not wait** for `SCO_AUDIO_STATE_CONNECTED`; `OmniRealtimeService.switchAudioSource` (`:234-254`) immediately stops and restarts `AudioRecord` with the new source/sample rate, so the first buffers after switching may still come from the phone mic.
- Capture config when `BLUETOOTH_MIC`: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, 16 000 Hz, mono, PCM16 (`OmniRealtimeService.kt:163-193`; `GeminiLiveService.kt:176-181, 263-271`). Phone mic path in Omni uses 24 000 Hz; neither service tells Dashscope the input sample rate (only `input_audio_format: pcm16`).
- Playback while BT mic is active: `AudioTrack` with `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH` so output follows the SCO link (`OmniRealtimeService.kt:433-443`; `GeminiLiveService.kt:542-552`); otherwise `USAGE_MEDIA` (A2DP if connected — the code never touches A2DP explicitly).
- `cleanup()` (`:234-244`) stops SCO, unregisters the receiver, closes the profile proxy. Services call it in `disconnect()` (`OmniRealtimeService.kt:156`, `GeminiLiveService.kt:161`); the ViewModel-owned instance is **never cleaned up** (`OmniRealtimeViewModel.onCleared` `:420-425`) → leaked receiver.
- Wake word (`PorcupineWakeWordService`) and `QuickVisionService` TTS do not use this manager; Porcupine listens on the phone mic only.

For OpenClaw ASR: reuse this manager for HFP routing (16 kHz PCM16 mono from `VOICE_COMMUNICATION` matches typical ASR input), but it needs (a) waiting for `SCO_AUDIO_STATE_CONNECTED` before opening `AudioRecord`, (b) a single shared instance, (c) migration to `setCommunicationDevice`, (d) `RECORD_AUDIO` runtime permission (already requested at launch by `MainActivity`).

---

## 6. Dependencies (`android/gradle/libs.versions.toml`)

| Alias | Coordinates | Version | Used? |
|---|---|---|---|
| `mwdat-core` / `mwdat-camera` | `com.meta.wearable:mwdat-core`, `mwdat-camera` | 0.4.0 (`:4`) | yes (`build.gradle.kts:84-85`) |
| `mwdat-mockdevice` | `com.meta.wearable:mwdat-mockdevice` | 0.4.0 | declared, commented out (`:86`) |
| `androidx-core-ktx` | `androidx.core:core-ktx` | 1.13.0 | yes |
| `androidx-appcompat` | `androidx.appcompat:appcompat` | 1.7.0 | yes (`AppCompatActivity`, `AppCompatDelegate`) |
| `androidx-activity-compose` | `androidx.activity:activity-compose` | 1.9.0 | yes |
| `androidx-exifinterface` | `androidx.exifinterface:exifinterface` | 1.3.7 | declared, **never imported** |
| Compose BOM | `androidx.compose:compose-bom` | 2024.12.01 | yes; `ui`, `ui-graphics`, `ui-tooling(-preview)`, `material3`, `material-icons-extended` |
| Lifecycle | `lifecycle-runtime-ktx`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` | 2.6.2 | yes |
| Navigation | `androidx.navigation:navigation-compose` | 2.7.7 | yes |
| Room | `room-runtime`, `room-ktx`, `room-compiler` | 2.6.1 | catalog only, **not applied** |
| DataStore | `androidx.datastore:datastore-preferences` | 1.0.0 | applied (`:112`), **never imported** |
| Security | `androidx.security:security-crypto` | 1.1.0-alpha06 | yes (`EncryptedSharedPreferences`) |
| OkHttp | `com.squareup.okhttp3:okhttp` | 4.12.0 | yes (REST + WebSocket) |
| OkHttp logging | `logging-interceptor` | 4.12.0 | applied, **never imported** |
| Gson | `com.google.code.gson:gson` | 2.10.1 | yes (all JSON; no kotlinx-serialization) |
| Coil | `io.coil-kt:coil-compose` | 2.6.0 | applied, **never imported** |
| kotlinx-collections-immutable | | 0.3.7 | applied, **never imported** |
| Picovoice | `ai.picovoice:porcupine-android` | 3.0.3 | yes |
| RootEncoder | `com.github.pedroSG94.rtmp-rtsp-stream-client-java:rtplibrary` (JitPack) | 2.2.6 | yes (`RtmpClient` only) |
| kotlinx-coroutines | not declared; transitively via lifecycle/compose | — | yes everywhere |
| Ktor / Retrofit / kotlinx-serialization | not used | — | — |

Plugins: AGP 8.6.0, Kotlin 2.0.0, `org.jetbrains.kotlin.plugin.compose` 2.0.0 (`toml:79-82`). Gradle wrapper 8.7 (`gradle/wrapper/gradle-wrapper.properties:3`). `gradle.properties`: config cache, build cache, parallel, non-transitive R, `-Xmx2048m` (`:11-29`).

---

## 7. Build configuration (`android/app/build.gradle.kts`)

- `namespace`/`applicationId` `com.smartview.glassai`; `compileSdk = 35`, `minSdk = 31`, `targetSdk = 34` (`:8-14`). README claims Android 8.0/API 26 (`android/README.md:198-200`) — wrong.
- `versionCode 4`, `versionName "1.5.0"` (`:15-16`).
- Signing: a `release` config that points at `~/.android/debug.keystore` with password `android` / alias `androiddebugkey` hardcoded (`:24-32`) and is applied to the release build type (`:38`). Not a distributable signing setup.
- Release: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt` + `proguard-rules.pro` (`:34-44`). Rules keep DAT/Facebook/protobuf/Tink/Gson/OkHttp/models (`proguard-rules.pro`).
- Java/Kotlin target 1.8 (`:46-53`); `buildFeatures { compose = true; buildConfig = true }` (`:55-58`); `BuildConfig` never referenced.
- Lint: `checkReleaseBuilds = false`, `abortOnError = false` (`:66-69`).
- ABI splits: `arm64-v8a`, `armeabi-v7a` + universal APK (`:71-79`).
- `settings.gradle.kts`: `FAIL_ON_PROJECT_REPOS`; google, mavenCentral, GitHub Packages (`maven.pkg.github.com/facebook/meta-wearables-dat-android`, creds `github_username` default `x-access-token`, `github_token` or `GITHUB_TOKEN`), JitPack (`:28-43`); `rootProject.name = "TurboMeta"` (`:45`).
- Manifest permissions: INTERNET, BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, MODIFY_AUDIO_SETTINGS, RECORD_AUDIO, WRITE_EXTERNAL_STORAGE (≤28), FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, WAKE_LOCK, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (`AndroidManifest.xml:6-21`). No CAMERA, no BLUETOOTH_SCAN, no FOREGROUND_SERVICE_CONNECTED_DEVICE. POST_NOTIFICATIONS is declared but never requested at runtime (needed on 33+ for the two foreground-service notifications to be visible).
- Application class `TurboMetaApplication` only stores a static `instance` (`TurboMetaApplication.kt:5-15`).
- Resources: `values/strings.xml` and `values-zh-rCN/strings.xml` (385 lines each; zh file lacks `feature_quickvision_*` in the same position but defines them at `:316-317`), `values/colors.xml`, `themes.xml` (`Theme.AppCompat.DayNight.NoActionBar`, transparent bars), mipmaps only, `xml/file_paths.xml`. No drawables, no `values-night`.
- No unit/instrumentation test directories; no CI config under `android/`.

---

## 8. Code-quality observations relevant to upcoming work

Migration-critical:
1. DAT init is gated on **RECORD_AUDIO** (and the other three) all being granted (`MainActivity.kt:33-38, 48-57, 104-116`). Denying mic (needed only for Live AI/wake word) blocks glasses registration entirely. The 0.9.0 sample requests RECORD_AUDIO lazily (`samples/CameraAccess/.../MainActivity.kt:83-106`).
2. Stream lifecycle logic is copy-pasted three times (`WearablesViewModel.kt:253-368`, `QuickVisionService.kt:220-265`, `RTMPStreamingViewModel.kt:155-210, 305-326`), including the I420→NV21 converter. Each creates its own `AutoDeviceSelector` and its own session, and there is no guard against two of them streaming at once (e.g. wake-word `QuickVisionService` firing while `LiveAIScreen` holds the UI stream). Consolidate into one session owner before/while migrating to `createSession/addCamera`.
3. Per-frame decode (I420 → NV21 → JPEG → Bitmap) runs on the main thread in both `WearablesViewModel` (`:293-298, 411-441`) and `RTMPStreamingViewModel` (`:204-209, 262-286`); `QuickVisionService` runs its whole pipeline on `Dispatchers.Main` with `delay`-polling loops (`:77, 254-258`). `QuickVisionScreen` also polls with `delay(100)` loops (`:185-188, 210-213`).
4. `RegistrationState` sealed-class pattern matching (`WearablesViewModel.kt:74, 147-165, 472`) and `capturePhoto().onFailure { }` single-arg lambda (`:396`) will not compile against 0.9.0.
5. `Wearables.startRegistration(getApplication())` passes an `Application` where the API (per 0.4.0 changelog and 0.9.0 sample) wants an `Activity`; the ViewModel has no Activity reference — needs an Activity-forwarding pattern like the sample's `startRegistration(activity)`.
6. `WearablesViewModel.batteryLevel` (`:86-87`) is never populated; `navigateToStreaming`/`navigateToDeviceSelection`/`isStreaming` (`:210-241`) are unused.

Correctness / UX bugs:
7. Home gates Live AI on the **Vision-provider** key (`HomeScreen.kt:289`) but Live AI uses the Live-AI-provider key (`OmniRealtimeViewModel.kt:113`); with Gemini selected and only a Google key saved, Home blocks entry.
8. `LeanEatService` always hits Beijing Dashscope with `qwen-vl-plus` (`:21-22`) using whatever the current vision-provider key is (`LeanEatViewModel.kt:54`); OpenRouter/Singapore users send the wrong key to the wrong host.
9. LeanEat/Vision "Take Photo" can never succeed because no stream is started on those screens and `takePhoto()` requires `StreamState.Streaming` (`WearablesViewModel.kt:373-376`; `Navigation.kt:146-170`). `Screen.Vision` is unreachable.
10. `GeminiLiveService` ignores `LiveAIModeManager` (`:189`) — mode/custom prompts only apply to Qwen.
11. `OmniRealtimeViewModel` and `OmniRealtimeService` each own a `BluetoothAudioManager` (two SCO receivers); the VM's is never `cleanup()`ed (`:420-425`). `switchAudioSource` triggers both (`:346, 350-351`).
12. Omni phone-mic capture is 24 kHz while BT-mic capture is 16 kHz, with no sample-rate field in `session.update` (`OmniRealtimeService.kt:163-168, 267-282`) — verify Dashscope's expected input rate.
13. `GeminiLiveService` puts the API key in the WebSocket URL query (`:117`).
14. `PorcupineWakeWordService` stores the Picovoice key in plain prefs (`:48-62`); `SettingsScreen` detects the service with deprecated `getRunningServices` (`:1306-1315`).
15. Both foreground services declare `foregroundServiceType="microphone"`; `QuickVisionService` does not record audio, and on Android 14 (targetSdk 34) starting a `microphone`-type FGS from the background (wake-word path) is restricted — a `connectedDevice` type with `FOREGROUND_SERVICE_CONNECTED_DEVICE` (as in the 0.9.0 sample) is the safer fit for the glasses stream.
16. `POST_NOTIFICATIONS` never requested at runtime; on API 33+ the FGS notifications may be suppressed.
17. `allowBackup="true"` with `EncryptedSharedPreferences` (`AndroidManifest.xml:25`).

Structure / hygiene:
18. `SettingsScreen.kt` is 1315 lines with seven private dialog composables and service-control logic inline; `HomeScreen.kt` (715) contains its own `FeatureCard`/`FeatureCardWide`/`DeviceStatusCard` duplicating `ui/components/CommonComponents.kt`'s `FeatureCard`.
19. Hardcoded UI strings: Chinese chips in `LiveAIScreen.kt:646,660`; English in `SimpleLiveStreamScreen.kt:163-194`, `RTMPStreamingScreen.kt` (all labels), `GalleryScreen.kt:71`, `HomeScreen.kt:484` ("Coming Soon"); `SettingsViewModel` messages (`:190, 208, 227, 249, 263…`); status phrases in `QuickVisionService.kt:399-444`; `AlibabaEndpoint.displayName` etc. are Chinese literals (`APIProviderManager.kt:27-31, 64-68, 111-115`).
20. Unused dependencies (Coil, DataStore, exifinterface, collections-immutable, OkHttp logging) and unused constants (`VisionAPIService.kt:42-43`, `APIKeyManager.ai_model`, `RTMPStreamingService.feedFrame(ByteArray)` `:316-330`, `OmniRealtimeService.convertPcm24ToPcm16` `:500-526`).
21. `APIProviderManager` static accessors depend on a static `prefs` field initialised only after `getInstance` (`:256-297`) — `APIKeyManager.getInstance` alone yields provider defaults.
22. Release build is signed with the debug keystore and hardcoded password (`build.gradle.kts:24-32`).
23. Version drift: `build.gradle.kts` 1.5.0, Settings hardcodes "1.5.0", README 1.4.0, CHANGELOG 1.0.0/DAT 0.3.0.
24. Parity gap vs iOS v2.0.0 (`cd26fb2`): no OpenClaw node/ASR/command router/device identity, no Fun-ASR service, no Ray-Ban Display support, no TTS service abstraction (TTS is instantiated ad hoc in `QuickVisionScreen.kt:103-125` and `QuickVisionService.kt:111`), no Keychain-equivalent for the RTMP URL (stored in encrypted prefs, fine).

---

## 9. Quick reference — broadcast/intent contracts and string keys

- Wake word: `com.smartview.glassai.WAKE_WORD_DETECTED` (extra `keyword_index`), service actions `com.smartview.glassai.START_WAKE_WORD` / `STOP_WAKE_WORD` (`PorcupineWakeWordService.kt:39-44`).
- Quick Vision service actions: `com.smartview.glassai.CAPTURE_AND_ANALYZE`, `com.smartview.glassai.STOP_QUICK_VISION`; broadcasts `com.smartview.glassai.QUICK_VISION_STATUS` (`status`), `com.smartview.glassai.ANALYSIS_COMPLETE` (`analysis_result` | `analysis_error`) (`QuickVisionService.kt:66-74`).
- Deep-link scheme for Meta AI callback: `turbometa` (`AndroidManifest.xml:53`).
- Prompt string resources: `prompt_quickvision_{standard,health,blind,reading,translate,encyclopedia}`, `prompt_liveai_{standard,museum,blind,reading,translate}` (`{LANGUAGE}` placeholder), defaults `quickvision_custom_default`, `liveai_custom_default` (`res/values/strings.xml:368-384`, zh: `values-zh-rCN/strings.xml:368-384`).
- Bottom-nav labels: `R.string.home`, `records`, `gallery`, `settings` (`Navigation.kt:45-48`).
