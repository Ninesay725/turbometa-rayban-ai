# TurboMeta feature-parity gap report: iOS v2.0.0 (DAT 0.5.0) vs Android v1.5.0 (DAT 0.4.0)

Repo: `D:/Coding/Workspaces/Android/turbometa-rayban-ai`
iOS app: `CameraAccess/` (SwiftUI). iOS v2.0.0 = commit `cd26fb2` (parent `8df5edc`).
Android app: `android/` (Kotlin + Compose, package `com.smartview.glassai`), `versionName = "1.5.0"` (`android/app/build.gradle.kts:16`), `minSdk = 31`, `targetSdk = 34` (`build.gradle.kts:13-14`), `mwdat = "0.4.0"` (`android/gradle/libs.versions.toml:4`).

All line numbers below are 1-based in the named file. Chinese UI strings are kept as they appear in source.

---

## 0. Executive summary

* iOS v2.0.0 added (commit `cd26fb2`): the whole `Services/OpenClaw/` package (5 files, 1438 lines), `OpenClawChatView`, `OpenClawSettingsView`, an OpenClaw home card, a Settings "Integrations" section, Fun-ASR speech-to-text, Ed25519 device identity, DAT 0.5.0 (Meta Ray-Ban Display), RTMP stream-key migration to Keychain, and a batch of stability fixes. Android has none of the v2.0.0 items.
* Beyond v2.0.0, Android was already missing several v1.x iOS features: **Live Translate** (service, models, view, settings; 18 languages / 8 voices), **cloud TTS (qwen3-tts-flash)**, **Siri/App Intents + App Shortcuts** (platform-specific; Android needs an App Shortcuts / App Actions / Tile equivalent), **background Live AI trigger (`LiveAIManager`)**, the **camera hub (`StreamView`)** with stream timer + SDK `capturePhoto` + photo preview/share, **RTMP platform presets + separate stream-key field**, **MockDeviceKit debug UI**, and a **Quick Vision history screen in Settings**.
* Android has several things iOS lacks (reverse gaps, listed in §9 for completeness): Porcupine "Jarvis" wake word + foreground `QuickVisionService`, Bluetooth HFP mic toggle in Live AI, a working "Output Language" setting with ja/ko prompts, a richer Alibaba vision-model list, About links, clear-all records, save-to-gallery.
* Gaps that belong to the other research tracks are flagged **[OpenClaw track]** and **[Display track]** in the matrix (§4) so the remaining "other" gaps are visible in §5.
* Docs are stale on both sides: `android/README.md:3` says "Version 1.4.0"; `android/README.md:198` and root READMEs say Android 8.0 / API 26 while `minSdk = 31`; the root roadmap still lists "Android Quick Vision support" as planned (`README_EN.md:314`) although Android ships it.

---

## 1. iOS inventory (CameraAccess/, v2.0.0)

### 1.1 App entry & configuration
| File | What it does |
|---|---|
| `CameraAccess/TurboMetaApp.swift` | `@main`; `Wearables.configure()` at init (36-41); creates `WearablesViewModel`; hosts `MainAppView` + hidden `RegistrationView` (51-71). DEBUG `DebugMenuViewModel`/`MockDeviceKitView` overlay exists but is **commented out** (60-68, "Bug 图标已隐藏"). |
| `CameraAccess/Info.plist` | `MWDAT` dict: `AppLinkURLScheme turbometa://`, `MetaAppID $(META_APP_ID)`, `ClientToken $(CLIENT_TOKEN)`, `TeamID $(DEVELOPMENT_TEAM)` (40-50); URL scheme `turbometa` (21-33); `NSSiriUsageDescription` (62-63); `UIBackgroundModes` bluetooth-central/peripheral, external-accessory, **audio** (71-77); `NSAllowsLocalNetworking` (51-54, needed for OpenClaw LAN ws://). |
| `CameraAccess/TurboMeta.entitlements` | empty dict. |

### 1.2 Views (28 root files + 5 Components + 3 MockDeviceKit)
| View | Purpose / notable behaviour |
|---|---|
| `Views/MainAppView.swift` | Root router: registered (or mock device) → `PermissionsRequestView` once → `MainTabView`; else `HomeScreenView` (35-63). On tab appear wires `QuickVisionManager.setStreamViewModel`, creates `OpenClawCommandRouter` and `OpenClawNodeService.shared.setCommandRouter`, auto-connects if `openclaw_enabled` (46-58). |
| `Views/MainTabView.swift` | 4 tabs: Home (`TurboMetaHomeView`), Records, Gallery, Settings (20-48). API key read from Keychain (15-17). |
| `Views/TurboMetaHomeView.swift` | Feature grid: Live AI, Quick Vision, Live Translate, **OpenClaw** (85-92, subtitle shows connected state), RTMP (wide, badge `home.experimental`, 96-104), Live Stream (wide, 107-114), LeanEat (wide → `StreamView`, 117-124, 141-143). Auto-connect OpenClaw if a token is saved (160-164). Listens for `.liveAITriggered` and opens Live AI (166-169). |
| `Views/HomeScreenView.swift` | Pre-registration welcome; "连接 Ray-Ban Meta" → `viewModel.connectGlasses()` (77-78); success toast. Hard-coded Chinese strings (43-63, 71, 88, 123-126). |
| `Views/RegistrationView.swift` | `EmptyView` with `.onOpenURL` → `Wearables.shared.handleUrl(url)` when query has `metaWearablesAction` (8-30). |
| `Views/PermissionsRequestView.swift` | Requests microphone + photo-library(add) via `PermissionsManager`; "前往设置" / "继续使用（功能受限）" (46-56, 69-92). |
| `Views/SettingsView.swift` (1237 lines) | Main settings + 8 sub-views (see §6 for field-level detail). Version "2.0.0" / SDK "0.5.0" rows (337-338). |
| `Views/LiveAIView.swift` | Full-screen Live AI: auto start stream + connect on appear (95-118), auto-record when connected (131-135), eye toggle hides conversation (158-167), single Stop button (222-238), device-not-connected view (252-292). |
| `Views/OmniRealtimeView.swift` | Older manual Live AI UI (start/stop recording buttons) reached from `StreamView` brain button; defines shared `MessageBubble` (210-238). Hard-coded Chinese. |
| `Views/LiveAISettingsView.swift` | Mode list (6), target-language list when `.translate`, custom-prompt `TextEditor` when `.custom` (8-113). |
| `Views/QuickVisionView.swift` | Auto-runs `performQuickVision()` on appear after waiting ≤2 s for device (81-99); preview (photo > captured > frame), result card with TTS replay (198-206), "Stop Speaking" (268-283), Siri tips (289-323). |
| `Views/QuickVisionSettingsView.swift` | Mode list (7), target language, custom prompt, **History** row with count (83-101) → `QuickVisionHistoryView` (list, swipe delete, trash-all with confirm, 140-224) → `QuickVisionRecordDetailView` (276-339). |
| `Views/LiveTranslateView.swift` | Live translate UI: connection dot, source/target language buttons + swap (124-178), streaming + final translation card + last history item (182-243), big record button, clear (247-290), optional dimmed video background when image-enhance on (294-323). |
| `Views/LiveTranslateSettingsView.swift` | Source language list (all 18), target list (11 audio-capable), voice list (8; disabled if voice does not support target), **Use iPhone Microphone** toggle, Audio Output toggle, Visual Enhancement toggle, Clear History (16-147). |
| `Views/OpenClawChatView.swift` | Chat with OpenClaw: message bubbles (with image), pending streaming response, mic button → `OpenClawASRService` (partial/final transcript, send/cancel), "Snap & Send" (starts stream if needed, waits ≤5 s for a frame, sends, stops stream) (284-310), text input toggle, gear → `OpenClawSettingsView`. |
| `Views/OpenClawSettingsView.swift` | Status row (+ pairing hint), Host/Port/`SecureField` token (50-68), Connect/Disconnect (76-99), Capabilities info "Node ID rayban-node", "Commands camera.snap, device.status, device.info" (102-109). |
| `Views/RTMPStreamingView.swift` | Video preview, status pill, stats FPS/Frames/Time/Data (153-164), platform chip row (171-185), URL `TextField` + `SecureField` stream key (190-215), Start/Stop, tap to hide UI, 24 fps frame timer (264-271); `RTMPSettingsView`: bitrate 1/2/3/4 Mbps (372-377), platform presets, experimental note. |
| `Views/SimpleLiveStreamView.swift` | Full-screen preview with screen-recording tips ("1. 打开抖音/快手等直播平台" …) (75-89); hard-coded Chinese. |
| `Views/StreamView.swift` | Camera hub used as the LeanEat entry: auto start stream (69-81), controls: Stop, **timer** (1/5/10/15 min cycling, 199-205), **capturePhoto** (208-210), brain → `OmniRealtimeView` (213-215); photo preview sheet → AI Recognition / LeanEat sheets (88-134). |
| `Views/NonStreamView.swift` | Meta sample pre-stream screen with "Disconnect" menu (31-37) and `GettingStartedSheetView` tips (106-150). **Only referenced by `StreamSessionView`**. |
| `Views/StreamSessionView.swift` | Sample container switching `StreamView`/`NonStreamView`. **Not instantiated anywhere in TurboMeta** (legacy). |
| `Views/PhotoPreviewView.swift` | Photo with drag-to-dismiss; buttons AI Recognition, Nutrition Analysis, Share (`UIActivityViewController`) (43-96, 145-166). |
| `Views/VisionRecognitionView.swift` | Photo + editable prompt + 6 quick prompts + Analyze + result with Copy (reached from `StreamView` preview). |
| `Views/LeanEatView.swift` | Nutrition result: health-score ring, totals grid, per-food cards with rating badge, suggestions; auto-analyze on `.task` (53-58). Hard-coded Chinese. |
| `Views/RecordsView.swift` | 5 record tabs: Live AI (list + detail + swipe delete, 104-165), 实时翻译 placeholder "功能即将上线" (248-269), LeanEat placeholder (273-294), WordLearn placeholder (298-319), Quick Vision list + detail (323-378). Note: `.swipeActions` is applied inside `LazyVStack` (141-147), which SwiftUI ignores → delete likely non-functional. |
| `Views/ConversationDetailView.swift` | Message list for a `ConversationRecord` with date/count footer. |
| `Views/GalleryView.swift` | 3-column grid + `PhotoDetailView` with share; `loadPhotos()` is a TODO that always returns `[]` (70-74) → always empty. |
| `Views/DebugMenuView.swift` | DEBUG ladybug overlay button (disabled, see TurboMetaApp). |
| `Views/MockDeviceKit/MockDeviceKitView.swift` | DEBUG: "Pair RayBan Meta" (max 3, 49-51) + cards. |
| `Views/MockDeviceKit/MockDeviceCardView.swift` | DEBUG: Unpair, Power On/Off, Don/Doff, Unfold/Fold, Select video/image via `MediaPickerView` (54-117). |
| `Views/MockDeviceKit/MockDeviceKitButton.swift` | Button style for mock UI. |
| `Views/Components/CardView.swift`, `CircleButton.swift`, `CustomButton.swift`, `MediaPickerView.swift` (UIImagePicker bridge for mock media), `StatusText.swift` | Reusable components. |

### 1.3 Services (11 root + 5 OpenClaw)
| Service | What it does (key APIs / JSON) |
|---|---|
| `Services/TTSService.swift` | Cloud TTS: `POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` with header `X-DashScope-SSE: enable` (16, 194-198); body `{"model":"qwen3-tts-flash","input":{"text","voice","language_type"}}` (107-116, 200-209); voice = `LanguageManager.staticTtsVoice` ("Cherry" zh / "Ethan" en), `language_type` = "Chinese"/"English" (19-27); parses SSE `data:` lines → `output.audio.data` base64 PCM16 24 kHz → `AVAudioEngine`/`AVAudioPlayerNode` Float32 (246-274, 286-350). Falls back to `AVSpeechSynthesizer` when provider is OpenRouter, no Alibaba key, or API error (129-176, 366-410). `prepareAudioSession()` pre-configures session before stream stop (121-124). Errors `TTSError` (415-436). |
| `Services/LiveTranslateService.swift` | WebSocket to `APIProviderManager.staticLiveAIWebsocketURL` + `?model=qwen3-livetranslate-flash-realtime` (20-24, 124-144). `session.update` payload: `modalities` ["text"(,"audio")], `voice`, `input_audio_format":"pcm16"`, `output_audio_format":"pcm24"`, `input_audio_transcription.language` = source, `translation.language` = target, `turn_detection` server_vad threshold 0.5 / prefix 300 / silence 500 (175-206). `startRecording(usePhoneMic:)` sets `.playAndRecord` with `[.defaultToSpeaker]` (phone) or `[.allowBluetooth,.defaultToSpeaker]` (glasses) (210-272); resamples to 16 kHz PCM16 (283-363). `sendImageFrame` `input_image_buffer.append` JPEG 0.6, ≤500 KB, ≥0.5 s apart (367-395). Server events `response.audio_transcript.text` (delta), `.done`, `response.text.done`, `response.audio.delta/done`, `error` (460-529). |
| `Services/OmniRealtimeService.swift` | Qwen Omni realtime WS (`qwen3-omni-flash-realtime`, 47). `session.update`: modalities text+audio, `voice` Cherry/Ethan by language (186), pcm16 in / pcm24 out, `smooth_output`, `instructions` = `LiveAIModeManager.staticSystemPrompt` (187), server_vad 0.5 / 800 ms (184-208). Callbacks incl. `onSpeechStarted`, `onFirstAudioSent`, `onUserTranscript` (71-80). `sendImageAppend` (328). |
| `Services/GeminiLiveService.swift` | Gemini Live WS `BidiGenerateContent`; `setup` with `response_modalities ["AUDIO"]`, voice `Aoede` (170-174), `system_instruction` = `LiveAIModeManager.staticSystemPrompt` (163); 16 kHz in / 24 kHz out; `sendImageInput` (350). |
| `Services/QuickVisionService.swift` | OpenAI-compatible `POST {baseURL}/chat/completions` with `image_url` (JPEG 0.7 data URL) + text prompt (91-127); headers from `VisionAPIConfig.headers` (OpenRouter adds `HTTP-Referer`, `X-Title`) (140-143); 60 s timeout; accepts `message.content` or `delta.content` (193-204); `QuickVisionError` (210-237). |
| `Services/VisionAPIService.swift` | Same request shape for free-form recognition; default prompt "图中描绘的是什么景象?" (77). |
| `Services/VisionAPIConfig.swift` | Static proxies to `APIProviderManager`; constants; provider-specific headers (49-62). |
| `Services/LeanEatService.swift` | Hard-coded Beijing `https://dashscope.aliyuncs.com/compatible-mode/v1`, model `qwen3-vl-plus` (11-12) — ignores provider/endpoint/model settings. Chinese nutritionist prompt requiring pure JSON with **snake_case** keys `total_calories`, `total_protein`, `total_fat`, `total_carbs`, `health_score`, `health_rating` (70-104). Extracts `{…}` substring then decodes (169-190). |
| `Services/ConversationStorage.swift` | UserDefaults key `savedConversations`, max 100 (11-13). |
| `Services/QuickVisionStorage.swift` | UserDefaults key `quickVisionRecords`, max 100 (12-13); thumbnails embedded as JPEG data. |
| `Services/RTMPStreamingService.swift` | HaishinKit `RTMPConnection`/`RTMPStream`; default 2 Mbps, 24 fps, H.264 Main AutoLevel, keyframe 1 s (44-45, 235-241); `parseRTMPUrl` splits last path component as stream key (308-330); `feedFrame(UIImage, timestamp)` → `CMSampleBuffer` (172-196, 352-423); `NSLock` + detached shutdown task (v2.0.0 thread-safety fix, 49, 128-169). Stats `bytesSent` always 0 (341). |
| `Services/OpenClaw/OpenClawModels.swift` | Frame structs (`OpenClawRequestFrame` etc.), `OpenClawConnectParams`, `OpenClawNodeInvokeRequest{id,command,params,timeoutMs}`, `OpenClawNodeInvokeResult{id,nodeId,ok,payload,error}`, `CameraSnapParams` defaults `maxWidth 1600`, `quality 0.8`, `format "jpg"` (82-92), `AnyCodableValue` JSON wrapper (96-168). |
| `Services/OpenClaw/OpenClawDeviceIdentity.swift` | Ed25519 (`Curve25519.Signing.PrivateKey`) stored in Keychain service `com.smartview.glassai.openclaw.device`, account `ed25519_private_key`, `kSecAttrAccessibleAfterFirstUnlock` (70-129). `deviceId` = SHA256(pubkey raw) hex; `publicKeyBase64Url`. `sign(...)` signs `"v3|deviceId|clientId|clientMode|role|scopes(,)|signedAtMs|token|nonce|platform|deviceFamily"` and returns base64url (18-53); `normalizeForAuth` lower-cases and keeps `[a-z0-9._-]` (57-65). |
| `Services/OpenClaw/OpenClawCommandRouter.swift` | Dispatches `camera.snap` (start stream if needed ≤5 s, grab `currentVideoFrame`, resize to `maxWidth`, JPEG `quality`, returns `{format:"jpg", base64, width, height}`) (41-106), `camera.list` (`{cameras:[{id:"rayban-main",name:"Ray-Ban Meta Camera",facing:"front",available:true}]}`) (110-129), `device.status` (`deviceConnected,isStreaming,streamStatus,hasVideoFrame`) (133-153), `device.info` (`deviceType,appName,appVersion:"1.5.0"(stale),sdkVersion:"0.5.0",platform:"iOS",osVersion`) (157-172); error codes `UNKNOWN_COMMAND`, `NOT_READY`, `STREAM_FAILED`, `NO_FRAME`, `ENCODE_FAILED`. |
| `Services/OpenClaw/OpenClawASRService.swift` | Alibaba Fun-ASR realtime: `wss://dashscope.aliyuncs.com/api-ws/v1/inference`, model `fun-asr-realtime` (15-16); `run-task` header `{action:"run-task",task_id,streaming:"duplex"}`, payload `{task_group:"audio",task:"asr",function:"recognition",model,parameters:{format:"pcm",sample_rate:16000,vocabulary_id:"",disfluency_removal_enabled:false},input:{}}` (100-126); raw PCM16 16 kHz binary frames; events `task-started` (start mic), `result-generated` → `payload.output.sentence.text` final when `end_time>0` else partial, `task-finished`, `task-failed` (166-217); `finish-task` on stop (128-144). |
| `Services/OpenClaw/OpenClawNodeService.swift` | Singleton; `@Published connectionState` (`.disconnected/.connecting/.waitingForPairing/.connected/.error`), `isEnabled`, `gatewayHost` (default 127.0.0.1), `gatewayPort` (default 18789) persisted in UserDefaults `openclaw_enabled/openclaw_host/openclaw_port` (41-44, 216-220); gateway token in Keychain `com.smartview.glassai.openclaw`/`gateway_token` (60-61, 155-182). Connects `ws://host:port?token=…` with proxy bypass and 16 MB max message (186-214). Handshake: on `{type:"event",event:"connect.challenge",payload:{nonce}}` sends `connect` req with `minProtocol/maxProtocol 3`, `client{id:"openclaw-ios",displayName:"Ray-Ban Meta Glasses",version:"2.0.0",mode:"node",platform:"ios",modelIdentifier}`, `role:"operator"`, `scopes["operator.read","operator.write"]`, `caps["camera"]`, `commands["camera.snap","camera.list","device.status","device.info"]`, `auth{token}`, `device{id,publicKey,signature,signedAt,nonce}` (306-370). Handles `node.invoke.request`/`node.invoke` (params or `paramsjson`, `timeoutms`) → router → `node.invoke.result` with `payloadjson` (384-508). `chat` events with `payload.state=="final"` → `onChatEvent("[[FINAL]]"+text)` (391-403). `sendChatMessage(text,image)` → `chat.send {sessionKey:"turbometa-chat", message, idempotencyKey, attachments:[{type:"image",mimeType:"image/jpeg",content:base64}]}` (104-136). `tick` every 15 s (522-536); reconnect backoff `min(2^n,30)` s, max 5 attempts (540-578); `NOT_PAIRED` → `.waitingForPairing` (441-445). |

### 1.4 Managers (5)
| Manager | Notes |
|---|---|
| `Managers/APIProviderManager.swift` | `AlibabaEndpoint` beijing/singapore with REST + WS URLs (11-35); `APIProvider` alibaba/openrouter, defaults `qwen3-vl-plus` / `google/gemini-3-flash-preview` (39-78); `LiveAIProvider` alibaba/google, defaults `qwen3-omni-flash-realtime` / `gemini-2.0-flash-exp` (82-113); `OpenRouterModel` w/ `isVisionCapable`, `priceDisplay` (117-176); UserDefaults keys `api_provider`, `selected_vision_model`, `alibaba_endpoint`, `liveai_provider`, `liveai_model` (185-191); `fetchOpenRouterModels()` GET `https://openrouter.ai/api/v1/models` (303-346); nonisolated static accessors (365-409). |
| `Managers/LanguageManager.swift` | `AppLanguage` system/zh-Hans/en; key `app_language` (9-29, 35); swaps `Bundle` (54-78); `isChinese`, `apiLanguageCode` "Chinese"/"English" (105-107), `ttsVoice` "Cherry"/"Ethan" (110-112); `String.localized` extension (140-151). |
| `Managers/LiveAIManager.swift` | Background Live AI session for Intents: waits for device, starts stream, configures `.playAndRecord`/`.voiceChat` with `.mixWithOthers` (157-172), instantiates Omni/Gemini service, records, 0.1 s frame timer, saves conversation on stop (61-152, 354-414). Errors `LiveAIError` (437-455). |
| `Managers/LiveAIModeManager.swift` | Keys `liveAIMode`, `liveAICustomPrompt`, `liveAITranslateTargetLanguage` (13-15); 10 target languages (37-48); translate prompt replaces `{LANGUAGE}` (97-101). Default target = `LanguageManager.staticApiLanguageCode` ("Chinese"/"English") which is **not** a valid code in the list → falls back to "中文" (63-67, 98). |
| `Managers/QuickVisionModeManager.swift` | Same shape with keys `quickVisionMode`, `quickVisionCustomPrompt`, `quickVisionTranslateTargetLanguage` (13-15). |

### 1.5 Intents (2 files, App Intents / Siri)
| File | Contents |
|---|---|
| `Intents/LiveAIIntent.swift` | `LiveAIIntent` (title "实时对话", `openAppWhenRun = true`, posts `.liveAITriggered`) (11-24); `StopLiveAIIntent` (`openAppWhenRun = false`, calls `LiveAIManager.shared.stopSession()`) (28-45). |
| `Intents/QuickVisionIntent.swift` | 6 intents, all `openAppWhenRun = false`: `QuickVisionIntent` (optional `customPrompt` param), `QuickVisionHealthIntent`, `QuickVisionBlindIntent`, `QuickVisionReadingIntent`, `QuickVisionTranslateIntent`, `QuickVisionEncyclopediaIntent` (21-116). `TurboMetaShortcuts: AppShortcutsProvider` with 8 `AppShortcut`s and Chinese Siri phrases such as "用 \(.applicationName) 识图", "\(.applicationName) 这个食物健康吗", "\(.applicationName) 停止实时对话" (134-235). `QuickVisionManager` singleton: `performQuickVisionWithMode` = speak "正在识别" (hard-coded Chinese, 312) → start stream ≤5 s → 0.5 s settle → `capturePhoto()` ≤3 s (fallback to video frame) → `tts.prepareAudioSession()` → stop stream → `QuickVisionService.analyzeImage` → save `QuickVisionRecord` → `tts.speak(result)` (285-410). |

### 1.6 Models (5)
| File | Contents |
|---|---|
| `Models/ConversationRecord.swift` | `ConversationRecord{id,timestamp,messages,aiModel,language}` + Codable `ConversationMessage`. |
| `Models/FoodNutritionModel.swift` | `FoodNutritionResponse` (snake_case CodingKeys, 19-27), `FoodItem` (`health_rating`), emoji/colour helpers. |
| `Models/LiveAIModels.swift` | `LiveAIMode` standard/museum/blind/reading/translate/custom (10-16) with localized names/prompts. |
| `Models/LiveTranslateModels.swift` | `TranslateLanguage` 18 codes (en,zh,ja,ko,fr,de,ru,es,pt,it,yue + input-only id,vi,th,ar,hi,el,tr) (10-29), flags, `supportsAudioOutput`; `TranslateVoice` Cherry,Nofish,Jada,Dylan,Sunny,Peter,Kiki,Eric (104-112) with per-voice language support (143-155); `TranslateRecord`; client/server event enums (192-213). |
| `Models/QuickVisionModels.swift` | `QuickVisionMode` standard/health/blind/reading/translate/encyclopedia/custom (11-18); `QuickVisionRecord` with 100×100 JPEG(0.5) thumbnail (125-132). |

### 1.7 ViewModels (9 + 2 MockDeviceKit)
| File | Notes |
|---|---|
| `ViewModels/StreamSessionViewModel.swift` | Single `StreamSession` (`VideoCodec.raw`, resolution from UserDefaults `video_quality` low/medium/high, 24 fps) (75-92); publishes frames/state/errors/photos; `capturePhoto(format:.jpeg)` (223-225); `StreamTimeLimit` timer auto-stop (212-249); localized error mapping incl. `.hingesClosed`, `.thermalCritical` (267-288). |
| `ViewModels/WearablesViewModel.swift` | Registration stream, device stream, `hasMockDevice` (27, DEBUG), per-device `addCompatibilityListener` → alert on `.deviceUpdateRequired` (80-102), `connectGlasses()` / `disconnectGlasses()` (104-125). |
| `ViewModels/OmniRealtimeViewModel.swift` | Provider switch (alibaba/google); image sent on `onSpeechStarted` only after `onFirstAudioSent`+1 s (68-89); saves conversation on disconnect (257-280). Defines `ConversationMessage` (345-355). |
| `ViewModels/LiveTranslateViewModel.swift` | Persists `translate_source_language` (default en), `translate_target_language` (default zh), `translate_voice` (Cherry), `translate_audio_enabled` (true), `translate_image_enhance` (false), `translate_use_phone_mic` (false) (28-68, 79-93); 0.5 s image timer; in-memory history ≤50 (161-164); `swapLanguages` guarded by `supportsAudioOutput` (170-185). |
| `ViewModels/RTMPStreamingViewModel.swift` | `StreamingPlatform` custom/youtube/twitch/bilibili/douyin/tiktok/facebook with default URLs (`rtmp://a.rtmp.youtube.com/live2`, `rtmp://live.twitch.tv/app`, `rtmp://live-push.bilivideo.com/live-bvc`, `rtmp://push-rtmp-l6.douyincdn.com/third`, `rtmp://push.tiktokv.com/live`, `rtmps://live-api-s.facebook.com:443/rtmp`) and SF icons (67-111); hard-coded 504×504 (165-166); saves `rtmp_url`, `rtmp_platform`, `rtmp_bitrate` to UserDefaults and **stream key to Keychain** `com.smartview.glassai.rtmp`/`stream_key`, migrating legacy UserDefaults `rtmp_stream_key` (289-352). |
| `ViewModels/LeanEatViewModel.swift`, `VisionRecognitionViewModel.swift` (6 quick prompts, 54-61), `DebugMenuViewModel.swift` (DEBUG), `MockDeviceKit/MockDeviceKitViewModel.swift` (pair/unpair), `MockDeviceKit/MockDeviceViewModel.swift` (powerOn/Off, don/doff, fold/unfold, `setCameraFeed(fileURL:)`, `setCapturedImage(fileURL:)`) | |

### 1.8 Utilities
| File | Notes |
|---|---|
| `Utils/APIKeyManager.swift` | Keychain service `com.smartview.glassai.apikey`; accounts `alibaba-beijing-api-key`, `alibaba-singapore-api-key`, `openrouter-api-key`, `google-api-key`; migrates legacy `qwen-api-key`/`alibaba-api-key` (13-46). |
| `Utils/PermissionsManager.swift` | Mic + Photos(add-only) request/check, `openSettings()`. |
| `Utils/TimeUtils.swift` | `StreamTimeLimit` 1/5/10/15 min/noLimit (18-78). |
| `Utilities/DesignSystem.swift` | `AppColors`, `AppTypography`, `AppSpacing`, `AppCornerRadius`, `AppShadow`. |

### 1.9 Localization (iOS)
`en.lproj/Localizable.strings` and `zh-Hans.lproj/Localizable.strings`: 370 unique keys each, key sets identical (verified by diff). Two keys are defined twice (`home.translate.title`, `home.translate.subtitle` at lines 26-27 and 425-426; last wins → subtitle "18 Languages"/"18种语言互译"). Key groups: `openclaw.*` + `settings.integrations` + `home.openclaw.*` (428-450), `livetranslate.*` (348-427), `rtmp.*` (323-346), `quickvision.siri.*` (60-64), `prompt.*` (250-259, 316-321), `liveai.mode.*`, `quickvision.mode.*`, `settings.*`. Many views still hard-code Chinese (HomeScreenView, LeanEatView, RecordsView tab titles/empty states, GalleryView, ConversationDetailView, OmniRealtimeView, SimpleLiveStreamView, PermissionsRequestView, VisionRecognitionView, `LanguageSettingsView` 909-919, `qualityDisplayName` 438-445, `AlibabaEndpoint.displayName`, `APIProvider.displayName`).

---

## 2. Android inventory (android/, v1.5.0)

### 2.1 Entry, manifest, build
| File | Notes |
|---|---|
| `android/app/src/main/AndroidManifest.xml` | Permissions INTERNET, BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, MODIFY_AUDIO_SETTINGS, RECORD_AUDIO, WRITE_EXTERNAL_STORAGE(≤28), FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, WAKE_LOCK, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (6-21). `com.meta.wearable.mwdat.APPLICATION_ID` = `"0"` placeholder (34-36). Deep link scheme `turbometa` (48-54). FileProvider (57-66, `res/xml/file_paths.xml`). Services `PorcupineWakeWordService` and `QuickVisionService`, both `foregroundServiceType="microphone"` (68-78). |
| `android/app/build.gradle.kts` | `minSdk 31`, `targetSdk 34`, `versionName "1.5.0"` (13-16); `mwdat-core`, `mwdat-camera`; `mwdat-mockdevice` **commented out** (86); `picovoice-porcupine 3.0.3` (129); `rtplibrary 2.2.6` (pedroSG94) (132); ABI splits. |
| `android/gradle/libs.versions.toml` | `mwdat = "0.4.0"` (4), room declared but unused. |
| `MainActivity.kt` | Requests runtime BLUETOOTH, BLUETOOTH_CONNECT, INTERNET, RECORD_AUDIO (33-38); on grant `Wearables.initialize(this)` + `wearablesViewModel.startMonitoring()` (118-127); `Wearables.RequestPermissionContract()` launcher + mutex-serialized `requestWearablesPermission` (60-77); `LanguageManager.init` (84). No rationale UI; `POST_NOTIFICATIONS` never requested at runtime. |
| `TurboMetaApplication.kt` | Holds `instance`. |
| `ui/navigation/Navigation.kt` | Routes `home, live_ai, lean_eat, vision, quick_vision, settings, records, gallery, live_stream, rtmp_stream, quick_vision_mode, live_ai_mode` (25-38); bottom nav Home/Records/Gallery/Settings (40-49). `onNavigateToVision` actually navigates to `QuickVision` (121-123); `Screen.Vision` composable (159-170) is **unreachable** (dead route). |

### 2.2 Screens (13)
| Screen | Notes |
|---|---|
| `ui/screens/HomeScreen.kt` | Header, `DeviceStatusCard` (Connect / Connected badge / tap → disconnect confirm) (578-715), cards: Live AI (gates: device → API key → camera permission, 275-297), Quick Vision (299-321), LeanEat (no gate, 329-336), WordLearn placeholder "Coming Soon" hard-coded (338-346, 484), Live Stream wide (350-365), RTMP wide (368-383). Dialogs: API key required (opens `https://bailian.console.aliyun.com/?apiKey=1`), device required, camera permission denied (100-212). |
| `ui/screens/LiveAIScreen.kt` | Auto start stream + connect (77-84), auto record on connect (87-91), frames → VM (94-98), `AudioSourceToggle` FilterChips "手机麦克风"/"眼镜麦克风" (hard-coded Chinese, 631-671), messages + streaming bubble + speaking indicator, `ControlPanel` with Connect / End / record FAB / stream status (438-525). |
| `ui/screens/QuickVisionScreen.kt` | In-app flow with platform `TextToSpeech` (103-125): speak "Analyzing" → `startStream()` ≤5 s → 0.5 s → `takePhoto()` ≤3 s (fallback frame) → `stopStream()` → `visionService.quickVision` → save record → speak (152-269); auto-start when device present (279-285); replay/stop-speaking (497-505, 602-618); Jarvis tip card (624-650). |
| `ui/screens/ModeSettingsScreen.kt` | `QuickVisionModeScreen` (35-133) and `LiveAIModeScreen` (141-254): mode list, target language (translate), custom prompt (custom). |
| `ui/screens/SettingsScreen.kt` | See §6. Version "1.5.0" (423). |
| `ui/screens/RecordsScreen.kt` | Two tabs Live AI / Quick Vision (98-111), cards with delete icon + confirm, `ConversationDetailScreen` (411-513), `QuickVisionDetailScreen` (517-660). |
| `ui/screens/GalleryScreen.kt` | Static empty state; hard-coded "Photos taken with Live AI will appear here" (71). |
| `ui/screens/RTMPStreamingScreen.kt` | Preview, status pill, stats FPS/Frames/Bitrate (201-226), URL (incl. key) shown truncated (274-279), start/stop circle; `RTMPSettingsDialog` URL field + bitrate 500k/1M/2M/4M/6M (328-421). All strings hard-coded English. |
| `ui/screens/SimpleLiveStreamScreen.kt` | Preview + hard-coded English tips (163-194). |
| `ui/screens/LeanEatScreen.kt` | Idle → Capturing → Analyzing → Result/Error; `LaunchedEffect(currentFrame)` sets image (46-48); result shows `HealthScoreCircle`, `NutritionBar`s, foods, suggestions, Save/New Photo. **Never calls `startStream()`**; `onTakePhoto` → `WearablesViewModel.takePhoto()` which returns null when not streaming (`WearablesViewModel.kt:373-377`). |
| `ui/screens/VisionScreen.kt` | Free-form prompt + 6 default prompts + Save/Reanalyze/New Photo. Unreachable (see Navigation). |
| `ui/components/CommonComponents.kt` | `GradientButton`, `FeatureCard`, `StatusBadge`, `SectionHeader`, `TurboMetaTopBar`, `LoadingIndicator`, `ErrorMessage`, `SuccessMessage`, `EmptyState`, `ConfirmDialog`, `NutritionBar`, `HealthScoreCircle`. |
| `ui/theme/*` | Colours (`LiveAIColor`, `QuickVisionColor`, `LeanEatColor`, `WordLearnColor`, `LiveStreamColor`, Health*), typography, `AppSpacing`, `AppRadius`. |

### 2.3 Services (7)
| Service | Notes |
|---|---|
| `services/OmniRealtimeService.kt` | OkHttp WS; model default `qwen3-omni-flash-realtime` (34); endpoint-aware URL (48-52); `session.update`: modalities text+audio, `voice` hard-coded `"Cherry"` (271), pcm16 in / **pcm16 out** (273), `smooth_output`, `instructions` from `LiveAIModeManager.getSystemPrompt()` if context else language prompt (260-286); server_vad 0.5/800. Sends `input_image_buffer.append` every 500 ms while audio is flowing (101, 336-341). `BluetoothAudioManager` HFP mic switching (88-97, 234). |
| `services/GeminiLiveService.kt` | `setup` with `response_modalities ["AUDIO"]`, voice `Aoede`, `system_instruction` = `getLiveAIPrompt(outputLanguage)` — **ignores `LiveAIModeManager`** (185-216); image sent once on first audio (363-370); `sendImageInput` (373-397). |
| `services/VisionAPIService.kt` | Provider/endpoint-aware `chat/completions` (82-133), `max_tokens 2000`; `quickVision()` uses `QuickVisionModeManager.getPrompt()` (138-150) or 4-language fallback prompt (155-199). Unused consts `DEFAULT_OPENROUTER_MODEL = "google/gemini-3-flash-preview"` (43). |
| `services/LeanEatService.kt` | Hard-coded Beijing URL and model `qwen-vl-plus` (21-22); Chinese prompt with **camelCase** keys `totalCalories`, `healthRating` … (24-57); `max_tokens 2000` (131). |
| `services/QuickVisionService.kt` | Foreground service (`ACTION_CAPTURE_AND_ANALYZE`, `ACTION_STOP`, broadcasts `ACTION_ANALYSIS_COMPLETE`, `ACTION_QUICK_VISION_STATUS` with statuses started/streaming/analyzing/complete/error/finished) (66-74, 185-322); platform `TextToSpeech`, status phrases localized zh/ja/ko/en by system locale, result spoken in output-language locale (114-146, 399-444); grabs **first video frame** at `VideoQuality.MEDIUM` (227, 243-251), no `capturePhoto`. |
| `services/PorcupineWakeWordService.kt` | Foreground mic service; Picovoice `PorcupineManager` built-in keyword `JARVIS`, sensitivity 0.7 (148-152); access key in plain `porcupine_prefs`/`porcupine_access_key` (48-49); 10 s debounce + `isProcessing` reset by QuickVision status broadcast (52, 79-88, 183-210); triggers `QuickVisionService` (212-225); notification with Stop action. |
| `services/RTMPStreamingService.kt` | `MediaCodec` H.264 (`COLOR_FormatYUV420Planar`, 24 fps, I-frame 1 s, `KEY_LATENCY 0`) (183-210); pedroSG94 `RtmpClient` with `ConnectCheckerRtmp` (124-159); SPS/PPS extraction (253-288); `feedFrame(ByteBuffer,…)` with timestamp smoothing + drop counting (346-399); stats `framesSent,bitrate,fps,connectionTime`. |

### 2.4 Managers (5)
| Manager | Notes |
|---|---|
| `managers/APIProviderManager.kt` | 1:1 port of iOS enums (23-147) but defaults `qwen-vl-flash` / `google/gemini-2.0-flash-001` (83-87); `AlibabaVisionModel.availableModels` = `qwen-vl-flash`, `qwen-vl-plus`, `qwen-vl-max`, `qwen2.5-vl-72b-instruct` (204-233); prefs `api_provider_prefs` with same keys as iOS (240-245); `fetchOpenRouterModels` (417-463). |
| `managers/BluetoothAudioManager.kt` | HFP/SCO management: `AudioSource.PHONE_MIC/BLUETOOTH_MIC`, SCO broadcast receiver, `switchAudioSource`, `startBluetoothSco` (`MODE_IN_COMMUNICATION`) (35-38, 171-209). **iOS has no equivalent** (uses `.allowBluetooth` automatically). |
| `managers/LanguageManager.kt` | `AppLanguage` SYSTEM/CHINESE("zh-CN")/ENGLISH; `AppCompatDelegate.setApplicationLocales` (live switch, no restart) (15-25, 53-61); prefs `language_prefs`/`app_language`. |
| `managers/LiveAIModeManager.kt` | prefs `live_ai_prefs` keys `live_ai_mode`, `live_ai_custom_prompt`, `live_ai_translate_target_language` (18-21); same 10 languages (33-44); default target `zh-CN` (71-73). |
| `managers/QuickVisionModeManager.kt` | prefs `quick_vision_prefs` keys `quick_vision_mode`, `quick_vision_custom_prompt`, `quick_vision_translate_target_language` (18-21). |

### 2.5 ViewModels (7)
| VM | Notes |
|---|---|
| `viewmodels/WearablesViewModel.kt` | `ConnectionState` Disconnected/Searching/Connecting/Registered/Connected/Error (53-60); `StreamState` (63-68); `Wearables.startStreamSession(app, AutoDeviceSelector, StreamConfiguration(videoQuality, 24))` per start (253-337); `takePhoto()` via `capturePhoto()` (`PhotoData.Bitmap`/`HEIC`) only when streaming (373-405); I420→NV21→JPEG(50) preview conversion (411-456). No compatibility listener. |
| `viewmodels/OmniRealtimeViewModel.kt` | Provider observation + service re-init (93-128); `ViewState` Idle/Connecting/Connected/Recording/Processing/Speaking/Error; `switchAudioSource` (342-353); saves `ConversationRecord` with selected `liveAIModel` and output language (390-402). |
| `viewmodels/SettingsViewModel.kt` | All settings state; `EditingKeyType` ALIBABA_BEIJING/ALIBABA_SINGAPORE/OPENROUTER/GOOGLE (135-140); `selectAppLanguage` auto-syncs `output_language` (366-385); `deleteAllConversations` (480-491); Live-AI `showModelDialog`/`AIModel` picker exists in VM (321-323) but has **no UI** in `SettingsScreen`. |
| `viewmodels/RecordsViewModel.kt` | `RecordsTab` LIVE_AI/QUICK_VISION (15-18); delete single/all (both stores) (104-161). |
| `viewmodels/RTMPStreamingViewModel.kt` | Own `StreamSession` (not the shared one) (155-210); `DEFAULT_RTMP_URL = "rtmp://localhost/live/stream"` (39); URL persisted via `APIKeyManager.saveRtmpUrl` (125-130); bitrate **not persisted** (132-134); first-frame dimensions used for encoder (228-240). |
| `viewmodels/LeanEatViewModel.kt`, `VisionViewModel.kt` | Analyze/retake/save-to-gallery (`MediaStore` Pictures/TurboMeta) (115-150 / 137-172); `VisionViewModel.DEFAULT_PROMPTS` 6 bilingual prompts (55-62). |

### 2.6 Data / Models / Utils
| File | Notes |
|---|---|
| `data/ConversationStorage.kt` | prefs `turbometa_conversations`/`saved_conversations`, max 100 (17-19). |
| `data/QuickVisionStorage.kt` | prefs `turbometa_quick_vision`/`saved_records`, thumbnails in `filesDir/quick_vision_thumbnails` (480 px JPEG 85) (30-33, 162-188). |
| `models/ConversationModels.kt` | `ConversationRecord{id:String,timestamp:Long,messages,aiModel,language}`, `ConversationMessage`, `MessageRole` (6-64). |
| `models/FoodNutritionModels.kt` | camelCase `FoodNutritionResponse`, `FoodItem` (9-63). |
| `models/LiveAIMode.kt`, `models/QuickVisionMode.kt` | Same 6/7 modes as iOS with `R.string` prompts (10-71 / 10-64). |
| `models/QuickVisionRecord.kt` | `{id,timestamp,thumbnailPath,prompt,result,mode,visionModel}` (9-17). |
| `utils/APIKeyManager.kt` | `EncryptedSharedPreferences` `turbometa_secure_prefs` (21); keys `alibaba-beijing-api-key`, `alibaba-singapore-api-key`, `openrouter-api-key`, `google-api-key`, legacy `qwen_api_key` (24-29); also settings `ai_model`, `output_language` (default `zh-CN`), `video_quality` (default `MEDIUM`), `rtmp_url` (31-34, 190-224); enums `AIModel` (qwen3-omni-flash-realtime, qwen3-omni-standard-realtime, gemini-2.0-flash-exp) (228-234), `OutputLanguage` zh-CN,en-US,ja-JP,ko-KR,es-ES,fr-FR (237-244), `StreamQuality` LOW/MEDIUM/HIGH (247-251). |

### 2.7 Localization (Android)
`res/values/strings.xml` and `res/values-zh-rCN/strings.xml`: 324 keys each, key sets identical. No `openclaw_*`, no `livetranslate_*`, no `rtmp_*` keys; `feature_translate_subtitle` = "Coming Soon" (38); `stream_timer`/`stream_1min`… exist but are unused (247-252); `gallery_share` unused (159). Hard-coded strings: `LiveAIScreen.kt:646,659` (Chinese chips), `RTMPStreamingScreen.kt` (all), `SimpleLiveStreamScreen.kt:91,144,163-194`, `GalleryScreen.kt:71`, `HomeScreen.kt:484`, `SettingsViewModel.kt` toast messages (682, 700, 719, 741, 755, 775, 825, 846, 876, 906, 955, 977), `AlibabaEndpoint.displayName` Chinese used in toasts.

---

## 3. What changed in iOS v2.0.0 (commit `cd26fb2`) that Android does not have

From `git show --stat cd26fb2` (42 files, +3662/-132):
1. New `Services/OpenClaw/*` (5 files), `Views/OpenClawChatView.swift`, `Views/OpenClawSettingsView.swift`; home card + Settings "Integrations" section; 22 new `openclaw.*`/`home.openclaw.*`/`settings.integrations` strings in both `.lproj`.
2. `MainAppView.swift` wires `OpenClawCommandRouter` + auto-connect (46-58).
3. DAT SDK 0.4.0 → 0.5.0 (project.pbxproj) for Meta Ray-Ban Display; `Info.plist` MetaAppID/ClientToken moved to build variables (later commit `78ea2f3`).
4. `RTMPStreamingViewModel`: stream key moved from UserDefaults `rtmp_stream_key` to Keychain with migration (289-352); `RTMPStreamingService`: `NSLock`, detached shutdown task, `@unchecked Sendable` (40-49, 128-169).
5. `StreamSessionViewModel`: frame-skip (`isProcessingFrame`) to reduce memory pressure (66, 112-126).
6. WebSocket services: `URLSession.invalidateAndCancel()` on disconnect; session config sent from `didOpen` delegate instead of immediately (e.g. `LiveTranslateService.swift:610-616`, `OmniRealtimeService`, `GeminiLiveService`).
7. `TTSService`: single long-lived `AVAudioEngine` instead of re-creating per utterance (39-62, 225-236).
8. `WearablesViewModel`: device compatibility listeners (80-102).
9. Settings About rows 2.0.0 / 0.5.0.
10. README/README_EN: OpenClaw setup, Tailscale remote access, Meta developer registration.

Android's own `RTMPStreamingService` is a different implementation (MediaCodec + RtmpClient) so items 4b and 5-7 are not directly portable; items 1-3, 4a, 8, 9 are real gaps.

---

## 4. Feature-parity matrix

Legend: **missing** = absent on Android; **partial** = present but materially different; **parity** = equivalent; **platform-N/A** = no meaningful Android equivalent; **reverse** = Android has it, iOS does not. Track tags: **[OpenClaw track]**, **[Display track]** = covered by the other research documents. Size: S ≤ 1 day, M = 2-5 days, L > 1 week.

| # | Feature | iOS status (file) | Android status (file) | Gap type | Porting notes | Size |
|---|---|---|---|---|---|---|
| 1 | OpenClaw Gateway WebSocket node client (handshake, invoke, chat.send, tick, reconnect) | `Services/OpenClaw/OpenClawNodeService.swift` (620 lines) | none | **missing** [OpenClaw track] | OkHttp `WebSocket` (already a dependency); replicate frame/JSON shapes in §1.3; UserDefaults → SharedPreferences `openclaw_enabled/host/port`; token in `EncryptedSharedPreferences`. Must bypass proxy for LAN (`OkHttpClient.Builder().proxy(Proxy.NO_PROXY)`), allow cleartext `ws://` (`android:usesCleartextTraffic` or network-security-config for LAN). | L |
| 2 | Ed25519 device identity + v3 signature | `OpenClawDeviceIdentity.swift` | none | **missing** [OpenClaw track] | minSdk 31 lacks `java.security` Ed25519 (API 33+); use Tink or BouncyCastle `Ed25519Signer`; store raw private key in `EncryptedSharedPreferences`; base64url without padding. | M |
| 3 | Command router `camera.snap/camera.list/device.status/device.info` | `OpenClawCommandRouter.swift` | none | **missing** [OpenClaw track] | Needs access to a shared stream/frame source (Android currently has per-screen sessions; `WearablesViewModel` is Activity-scoped). Consider a process-wide `StreamController` singleton. Fix stale `appVersion "1.5.0"` when porting. | M |
| 4 | OpenClaw chat UI (text / snap & send / voice) | `Views/OpenClawChatView.swift` | none | **missing** [OpenClaw track] | Compose screen + route `openclaw_chat`; needs #1, #3, #5. | M |
| 5 | Fun-ASR realtime speech-to-text | `OpenClawASRService.swift` | none | **missing** [OpenClaw track] | OkHttp WS to `wss://dashscope.aliyuncs.com/api-ws/v1/inference`; `AudioRecord` 16 kHz mono PCM16 sent as binary frames; JSON run-task/finish-task per §1.3. Reusable later for other features. | M |
| 6 | OpenClaw settings (host/port/token, status, capabilities) + Settings "Integrations" row + home card with live status | `OpenClawSettingsView.swift`, `SettingsView.swift:308-333`, `TurboMetaHomeView.swift:85-92` | none | **missing** [OpenClaw track] | Add `openclaw_*` strings (22 keys) to `values` + `values-zh-rCN`. | S |
| 7 | Auto-connect OpenClaw on launch when token saved | `MainAppView.swift:54-57`, `TurboMetaHomeView.swift:160-164` | none | **missing** [OpenClaw track] | Trigger from `MainActivity`/Application after SDK init. | S |
| 8 | DAT SDK version / Meta Ray-Ban Display support | DAT 0.5.0 (`README_EN.md:233`, Settings row) | DAT 0.4.0 (`libs.versions.toml:4`) | **partial** [Display track] | Android upstream is 0.9.0 with breaking changes: `DeviceSession`/`createSession`, `addCamera()`/`Camera.stream` (0.9.0), `RegistrationState` enum, `Stream`/`StreamState`, `MockDeviceKit.pairGlasses(GlassesModel)`, Display capability (`mwdat-display`), DAM always on (`C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/CHANGELOG.md` §0.7.0-0.9.0). Every DAT call site (`WearablesViewModel`, `RTMPStreamingViewModel`, `QuickVisionService`) must be rewritten. Also `APPLICATION_ID` meta-data is `"0"` (`AndroidManifest.xml:36`). | L |
| 9 | Device compatibility / firmware-update alert | `WearablesViewModel.swift:80-102` (`addCompatibilityListener`, `.deviceUpdateRequired`) | none | **missing** [Display track] | DAT 0.7+ Android: `Wearables.openFirmwareUpdate(activity)`, `openDATGlassesAppUpdate(activity)`, `DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` (CHANGELOG §0.7.0). | S |
| 10 | Stream error mapping incl. hinges closed / thermal | `StreamSessionViewModel.swift:267-288` | generic `StreamState.Error` only | **partial** [Display track] | Map `StreamError`/`DeviceSessionError` cases (`THERMAL_CRITICAL`, `BATTERY_LOW`, …) to localized strings after the 0.9.0 upgrade. | S |
| 11 | **Live Translate** service (qwen3-livetranslate-flash-realtime) | `Services/LiveTranslateService.swift` | none; home card says "Coming Soon" (`strings.xml:38`, no card actually rendered — `HomeScreen.kt` shows WordLearn placeholder instead) | **missing** | Port §1.3 protocol with OkHttp WS + `AudioRecord` (16 kHz PCM16) + `AudioTrack` (24 kHz PCM16 — note iOS requests `pcm24`; Android Omni uses `pcm16`, verify what livetranslate accepts). Phone-mic vs glasses-mic toggle maps to `BluetoothAudioManager.switchAudioSource`. | L |
| 12 | Live Translate models (18 languages, 8 voices w/ per-voice language support, records, events) | `Models/LiveTranslateModels.swift` | none | **missing** | Enum port; add ~60 `livetranslate.*` strings (see `Localizable.strings:348-427`). | S |
| 13 | Live Translate UI + settings (source/target/swap, voice, use-phone-mic, audio output, visual enhancement, history ≤50, clear) | `Views/LiveTranslateView.swift`, `Views/LiveTranslateSettingsView.swift`, `ViewModels/LiveTranslateViewModel.swift` | none | **missing** | Compose screen `live_translate` + settings route; persist keys `translate_source_language`(en), `translate_target_language`(zh), `translate_voice`(Cherry), `translate_audio_enabled`(true), `translate_image_enhance`(false), `translate_use_phone_mic`(false). Home card replaces the current WordLearn placeholder slot to match iOS layout (iOS has no WordLearn card). | M |
| 14 | Translation records tab | placeholder "功能即将上线" (`RecordsView.swift:248-269`) | none (string `records_translate` exists) | parity (both unimplemented) | — | — |
| 15 | **Cloud TTS** (qwen3-tts-flash SSE streaming, Cherry/Ethan by app language, fallback to system TTS) | `Services/TTSService.swift` | platform `android.speech.tts.TextToSpeech` only (`QuickVisionService.kt:111`, `QuickVisionScreen.kt:104`) | **partial** | Add `TTSService.kt`: OkHttp POST with `X-DashScope-SSE: enable`, parse `data:` SSE lines → `output.audio.data` base64 PCM16 → `AudioTrack` 24 kHz mono; keep platform TTS as fallback when provider is OpenRouter / no Alibaba key / HTTP error (iOS rule at `TTSService.swift:134-156`). Also make Android status phrases use it. | M |
| 16 | TTS "stop speaking" + replay buttons | `QuickVisionView.swift:198-206, 268-283` | `QuickVisionScreen.kt:497-505, 602-618` | parity | — | — |
| 17 | **Siri / App Intents / App Shortcuts** (8 shortcuts: Quick Vision ×6 modes incl. custom prompt param, Live AI start/stop; Action Button; lock-screen widget) | `Intents/QuickVisionIntent.swift`, `Intents/LiveAIIntent.swift`, `Info.plist:62-63` | none | **platform-N/A / missing equivalent** | Android equivalents to implement: (a) static App Shortcuts `res/xml/shortcuts.xml` + `<meta-data android:name="android.app.shortcuts">` for launcher long-press (Quick Vision per mode, Live AI); (b) Google Assistant App Actions via `shortcuts.xml` `<capability android:name="actions.intent.OPEN_APP_FEATURE">` / custom intents so "Hey Google, open Quick Vision in TurboMeta" works; (c) `TileService` quick-settings tile; (d) an `AppWidgetProvider` button widget; (e) expose `QuickVisionService.ACTION_CAPTURE_AND_ANALYZE` with an `EXTRA_MODE` (and optional `EXTRA_CUSTOM_PROMPT`) so Tasker/Bixby/automation apps can fire it (today the service is `exported="false"` and mode is not parameterized, `QuickVisionService.kt:66-74`). Android 14 restricts starting a mic FGS from background: an Activity trampoline or the existing Porcupine FGS is needed. | M |
| 18 | Background Live AI session (`LiveAIManager`) triggered by Siri | `Managers/LiveAIManager.swift` | none (Live AI only inside `LiveAIScreen`) | **missing** | Create `LiveAIService` foreground service (microphone type) mirroring `QuickVisionService`, hosting `OmniRealtimeService`/`GeminiLiveService` + its own stream; expose start/stop actions for shortcuts. | M |
| 19 | Quick Vision wake word ("Jarvis", Porcupine) + always-on foreground service | none (Siri instead) | `services/PorcupineWakeWordService.kt`, Settings toggle + Picovoice key (`SettingsScreen.kt:328-350`) | **reverse** (platform-N/A for iOS) | Keep. Note Picovoice key stored in plain prefs (`PorcupineWakeWordService.kt:48-49`); consider moving into `EncryptedSharedPreferences`. | — |
| 20 | Quick Vision: modes (7), prompts, custom prompt, translate target language | `QuickVisionModeManager.swift`, `QuickVisionSettingsView.swift` | `QuickVisionModeManager.kt`, `ModeSettingsScreen.kt:35-133` | parity | Prompt text identical. iOS default target language is buggy ("Chinese"/"English" not in code list, `LiveAIModeManager.swift:63-67`, same in QuickVision manager); Android defaults `zh-CN`. | — |
| 21 | Quick Vision history screen inside Settings (list, swipe delete, clear all with confirm) | `QuickVisionSettingsView.swift:83-224` | Records tab only; `deleteAllQuickVisionRecords` exists in VM (`RecordsViewModel.kt:150-161`) but no UI; Settings "Clear All Records" clears conversations only (`SettingsViewModel.kt:480-491`) | **partial** | Add history row + clear-all in `QuickVisionModeScreen`, and make Settings clear-all cover both stores. | S |
| 22 | Quick Vision capture uses SDK `capturePhoto` (full-res) with frame fallback | `QuickVisionIntent.swift:346-368` | in-app screen uses `takePhoto()` (`QuickVisionScreen.kt:204-216`); background `QuickVisionService` uses first video frame at MEDIUM (`QuickVisionService.kt:227-251`) | **partial** | Use `capturePhoto()` in `QuickVisionService` too (0.9.0: `Camera.stream.capturePhoto`). | S |
| 23 | Live AI: modes/custom prompt/target language | `LiveAIModeManager.swift`, `LiveAISettingsView.swift` | `LiveAIModeManager.kt`, `ModeSettingsScreen.kt:141-254` | parity (Omni) / **partial** (Gemini) | `GeminiLiveService.kt:189` uses `getLiveAIPrompt(outputLanguage)` and ignores the mode manager; iOS Gemini uses `LiveAIModeManager.staticSystemPrompt` (`GeminiLiveService.swift:163`). Pass context + use `LiveAIModeManager.getSystemPrompt()`. | S |
| 24 | Live AI voice by language (Cherry zh / Ethan en) | `OmniRealtimeService.swift:186`, `LanguageManager.swift:110-112` | hard-coded `"Cherry"` (`OmniRealtimeService.kt:271`) | **partial** | Map from output language. | S |
| 25 | Live AI image send policy | send current frame on `speech_started`, gated until 1 s after first audio (`OmniRealtimeViewModel.swift:68-89`) | Omni: send every 500 ms while audio flows (`OmniRealtimeService.kt:336-341`); Gemini: once on first audio (`GeminiLiveService.kt:363-370`) | **partial** (design) | Align to iOS (`input_audio_buffer.speech_started` handler) to cut bandwidth/token cost; iOS `autoSendImageOnSpeech` flag exists on both. | S |
| 26 | Live AI UI: hide/show conversation toggle | `LiveAIView.swift:158-167` | none | **missing** | Add eye icon toggling the `LazyColumn`. | S |
| 27 | Live AI UI: manual connect/disconnect/record controls + audio-source chips | none (auto only, single Stop) | `LiveAIScreen.kt:438-525, 631-671` | **reverse** | Keep; localize the two Chinese chip labels. | — |
| 28 | Live AI: Bluetooth HFP mic selection (`BluetoothAudioManager`) | none (AVAudioSession `.allowBluetooth`) | `managers/BluetoothAudioManager.kt` | **reverse** | — | — |
| 29 | Live AI providers Alibaba Omni / Google Gemini, endpoint Beijing/Singapore, Google key | `SettingsView.swift:228-305, 1054-1237` | `SettingsScreen.kt:276-313` | parity | — | — |
| 30 | Live AI model picker | none (only default saved in `liveai_model`) | VM has `AIModel` + `showModelDialog` (`SettingsViewModel.kt:321-323`) but no row in `SettingsScreen` | parity (neither exposes) | Optional: expose on both. | S |
| 31 | Conversation records (list, detail, delete) | `RecordsView.swift:104-244`, `ConversationDetailView.swift` | `RecordsScreen.kt` | parity | iOS swipe-delete likely non-functional (swipeActions in LazyVStack, `RecordsView.swift:141-147`); Android has explicit delete + confirm. Android also shows model/language badges. | — |
| 32 | Records tabs LeanEat / WordLearn | placeholders (`RecordsView.swift:273-319`) | none | parity (unimplemented) | — | — |
| 33 | Gallery | empty (`GalleryView.swift:70-74` TODO) but detail/share view coded | static empty (`GalleryScreen.kt`) | parity (unimplemented) | If implemented later, Android already has `FileProvider` + `MediaStore` save; iOS has share sheet. | — |
| 34 | Camera hub (`StreamView`): auto stream, Stop, **stream time limit 1/5/10/15 min**, **SDK photo capture**, photo preview with Share / AI Recognition / Nutrition | `Views/StreamView.swift`, `PhotoPreviewView.swift`, `Utils/TimeUtils.swift` | none; `stream_timer`, `stream_1min`… strings exist unused (`strings.xml:247-252`); `gallery_share` unused | **missing** | Add `CameraScreen` (start stream, timer countdown auto-stop, capture → preview sheet with Share via `FileProvider` `shared_images`, "AI Recognition" → `VisionScreen`, "Nutrition" → `LeanEatScreen`). This also fixes #35/#36. | M |
| 35 | LeanEat entry flow | Home → `StreamView` → capture → Nutrition (`TurboMetaHomeView.swift:141-143`, `StreamView.swift:88-127`) | Home → `LeanEatScreen` directly with no device/API-key gate (`HomeScreen.kt:329-336`); screen never starts the stream so "Take Photo" is a no-op unless another screen left a stream running (`LeanEatScreen.kt:46-48`, `WearablesViewModel.kt:373-377`) | **partial (likely broken)** | Start stream on enter or route through #34; add gates like the Live AI card. Verify on device. | S |
| 36 | Free-form Vision Recognition (custom prompt + quick prompts + copy) | `VisionRecognitionView.swift` (from photo preview) | `VisionScreen.kt` exists but route unreachable (`Navigation.kt:121-123, 159-170`) | **partial (dead code)** | Wire from camera hub / photo preview. | S |
| 37 | LeanEat analysis service | `LeanEatService.swift` `qwen3-vl-plus`, snake_case JSON | `LeanEatService.kt` `qwen-vl-plus`, camelCase JSON | parity (both hard-code Beijing + model, ignore provider/endpoint/model settings) | Consider using `VisionAPIService` config on both; align model to `qwen3-vl-plus`. | S |
| 38 | LeanEat result UI (score ring, totals, foods w/ rating, suggestions) | `LeanEatView.swift` | `LeanEatScreen.kt:238-448` | parity | Android adds Save-to-gallery; iOS shows per-food macro row. | — |
| 39 | RTMP: platform presets (Custom/YouTube/Twitch/Bilibili/Douyin/TikTok/Facebook) with default URLs + icons, persisted `rtmp_platform` | `RTMPStreamingViewModel.swift:67-111, 140-145`, `RTMPStreamingView.swift:171-185, 404-424` | single URL field, default `rtmp://localhost/live/stream` (`RTMPStreamingViewModel.kt:39`) | **missing** | Add `StreamingPlatform` enum + chip row + settings list; URL = preset + "/" + key. | S |
| 40 | RTMP: separate stream key field (`SecureField`) stored in Keychain, migrated from UserDefaults (v2.0.0) | `RTMPStreamingView.swift:205-211`, `RTMPStreamingViewModel.swift:289-352` | full URL incl. key stored in `EncryptedSharedPreferences` `rtmp_url` and displayed on screen truncated (`RTMPStreamingScreen.kt:274-279`) | **partial** | Split into `rtmp_url` + `rtmp_stream_key` (encrypted), password-transform the key field, never render the key. | S |
| 41 | RTMP: bitrate persisted (`rtmp_bitrate`) | `RTMPStreamingViewModel.swift:289-316` | in-memory only (`RTMPStreamingViewModel.kt:132-134`) | **partial** | Persist. Bitrate option sets differ (iOS 1/2/3/4 Mbps; Android 0.5/1/2/4/6). | S |
| 42 | RTMP: localized UI (23 `rtmp.*` keys) | `Localizable.strings:323-346` | hard-coded English (`RTMPStreamingScreen.kt`) | **partial** | Add `rtmp_*` strings en/zh. | S |
| 43 | RTMP: stats overlay | FPS/Frames/Time/Data (Data always 0) | FPS/Frames/Bitrate | parity (different fields) | — | — |
| 44 | RTMP: encoder input | UIImage → CMSampleBuffer 504×504 hard-coded (`RTMPStreamingViewModel.swift:165-166`) | raw I420 → MediaCodec at true frame size with timestamp smoothing | **reverse (Android better)** | — | — |
| 45 | RTMPS (Facebook preset uses `rtmps://…:443`) | HaishinKit | pedroSG94 rtplibrary 2.2.6 | unknown | Verify `RtmpClient` TLS support before adding the Facebook preset. | S |
| 46 | Simple "screen-recording" live stream view | `SimpleLiveStreamView.swift` | `SimpleLiveStreamScreen.kt` | parity | Both hard-code tip strings (zh on iOS, en on Android). | — |
| 47 | Settings: App language (system/中文/English) | requires restart via `exit(0)` (`SettingsView.swift:1032-1047`) | live via `AppCompatDelegate` + auto-sync output language (`SettingsViewModel.kt:366-385`) | parity (Android better UX) | — | — |
| 48 | Settings: Output Language (6 codes) | `LanguageSettingsView` bound to local `@State selectedLanguage` — **never persisted or read** (`SettingsView.swift:30, 368-370, 877-926`) | persisted `output_language`, feeds prompts (zh/en/ja/ko) + TTS locale | **reverse (iOS bug)** | iOS should either persist and use it or remove the row. | S (iOS) |
| 49 | Settings: Vision provider + Alibaba region + per-provider key + "Get API Key" link | `SettingsView.swift:113-185, 469-687` | `SettingsScreen.kt:203-262, 736-824` | parity | Android key dialog shows/hides key text and deletes; iOS uses `SecureField`. | — |
| 50 | Settings: Alibaba vision model list | `qwen3-vl-plus`, `qwen3-vl-max` (`SettingsView.swift:719-722`), default `qwen3-vl-plus` | `qwen-vl-flash`, `qwen-vl-plus`, `qwen-vl-max`, `qwen2.5-vl-72b-instruct`, default `qwen-vl-flash` (`APIProviderManager.kt:83-87, 204-233`) | **partial** | Add `qwen3-vl-plus`/`qwen3-vl-max` to Android list (keep others); align defaults (iOS `google/gemini-3-flash-preview` vs Android `google/gemini-2.0-flash-001`). | S |
| 51 | Settings: OpenRouter model browser (fetch, search, vision-only filter, price) | `VisionModelSettingsView` (`SettingsView.swift:754-872`) | `VisionModelSelectionDialog` (`SettingsScreen.kt:908-1098`) | parity | — | — |
| 52 | Settings: Video quality low/medium/high | `SettingsView.swift:930-984` (`video_quality` lowercase) | `SettingsScreen.kt:390-395` (`video_quality` "LOW/MEDIUM/HIGH") | parity | — | — |
| 53 | Settings: Device section (connected / stream active) | `SettingsView.swift:50-91` | none (device card on Home instead, with Disconnect) | parity (different placement) | iOS "Disconnect" only exists in legacy `NonStreamView:31-37` which is unreachable → iOS has **no reachable unregister UI**. | — |
| 54 | Settings: About — SDK version row | `SettingsView.swift:338` | none | **missing** | Add row (value from BuildConfig/`libs.versions`). | S |
| 55 | Settings: About — GitHub / Download latest / Buy Me a Coffee links | none | `SettingsScreen.kt:429-461` | **reverse** | — | — |
| 56 | Settings: Data — records count row + Clear all | none | `SettingsScreen.kt:399-416` | **reverse** | — | — |
| 57 | Settings: Integrations (OpenClaw) | `SettingsView.swift:308-333` | none | **missing** [OpenClaw track] | see #6 | S |
| 58 | Settings: Translation settings entry | `SettingsView.swift:288-302` | none | **missing** | see #13 | S |
| 59 | Permissions onboarding screen (mic + photos, "go to settings", "continue limited") | `PermissionsRequestView.swift` | runtime permission dialogs only (`MainActivity.kt:104-116`); `POST_NOTIFICATIONS` never requested (needed for FGS notifications on API 33+) | **partial** | Add a Compose onboarding step; request `POST_NOTIFICATIONS`. | S |
| 60 | Pre-registration welcome + "Getting started" tips | `HomeScreenView.swift`, `NonStreamView.swift:106-150` | Home device card "Connect Glasses" (`HomeScreen.kt:578-715`) | parity-ish | Optional welcome screen. | S |
| 61 | Background modes: audio while backgrounded | `Info.plist:71-77` (`audio`) | FGS microphone services for Quick Vision only | **partial** | Live AI/Translate in background needs FGS (see #18). | — |
| 62 | **MockDeviceKit debug UI** (pair ≤3 mock Ray-Ban Meta, power/don/doff/fold, load video/image feed) | `Views/MockDeviceKit/*`, `ViewModels/MockDeviceKit/*`, `DebugMenuView` (currently disabled by comments `TurboMetaApp.swift:60-68`; `hasMockDevice` bypasses registration `MainAppView.swift:35`) | none; `mwdat-mockdevice` dependency commented out (`build.gradle.kts:86`) | **missing** | Add `debugImplementation(libs.mwdat.mockdevice)`; debug-only screen using `MockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META)` (0.9.0), `MockGlasses.powerOn/powerOff/don/doff/fold/unfold`, `services.camera.setCameraFeed(...)`/`setCapturedImage(...)`, `services.captouch.tap()` (see skill `mwdat-android:mockdevice-testing`). Requires #8 first. | M |
| 63 | Localization coverage | 370 keys en/zh-Hans; many hard-coded zh views | 324 keys en/zh-rCN; hard-coded en/zh in RTMP, SimpleLiveStream, Gallery, LiveAI chips, VM toasts | **partial** | Port missing key groups (`openclaw`, `livetranslate`, `rtmp`, `settings.sdkversion`, `settings.endpoint.*.desc`, `settings.model.qwen3vl*.desc`, `tts.*`, `error.*`); externalize the hard-coded strings listed in §2.7. | S-M |
| 64 | Security: API keys | Keychain | `EncryptedSharedPreferences` | parity | Picovoice key plain (see #19). | — |
| 65 | Deep link `turbometa://` handling | `RegistrationView.swift` (`metaWearablesAction` filter) | manifest intent-filter (`AndroidManifest.xml:48-54`); SDK handles internally | parity | — | — |
| 66 | Version rows / docs | 2.0.0 / 0.5.0 | 1.5.0; `android/README.md:3` says 1.4.0; README says API 26 vs `minSdk 31`; `android/CHANGELOG.md` stops at 1.0.0 | docs gap | Update `android/README.md`, `CHANGELOG.md`, root README Android sections and roadmap (`README_EN.md:314` "Android Quick Vision support" already done). | S |

---

## 5. Remaining "other" gaps (not covered by the OpenClaw or Display tracks), prioritized

Product-visible gaps first, then hygiene.

1. **Live Translate** (#11-#13, #58) — L+M+S. Largest functional gap; iOS home card, settings and records placeholder all reference it; Android only has an unused "Coming Soon" string.
2. **Cloud TTS (qwen3-tts-flash)** (#15) — M. Affects Quick Vision voice quality and reuse by Live Translate/OpenClaw; Android platform TTS may lack Chinese voices on some devices.
3. **Voice/automation entry points** replacing Siri (#17, #18) — M+M. App Shortcuts + Assistant App Actions + exported/parameterized `QuickVisionService` action; a `LiveAIService` FGS so Live AI can be started/stopped without the screen.
4. **Camera hub + photo preview + timer + share** (#34) which also repairs **LeanEat entry** (#35) and the **dead Vision screen** (#36) — M+S+S.
5. **RTMP parity**: presets (#39), separate encrypted stream key (#40), persisted bitrate (#41), localized strings (#42), RTMPS check (#45) — all S.
6. **Live AI polish**: mode-aware Gemini prompt (#23), voice-by-language (#24), image-on-speech policy (#25), hide-conversation toggle (#26) — all S.
7. **Quick Vision**: history + clear-all in settings (#21), `capturePhoto` in background service (#22) — S.
8. **Settings**: qwen3-vl models (#50), SDK version row (#54), permissions onboarding + POST_NOTIFICATIONS (#59) — S.
9. **MockDeviceKit debug screen** (#62) — M, after the SDK upgrade.
10. **Localization + docs** (#63, #66) — S-M.

iOS-side defects found in passing (not Android gaps, but worth a ticket): Output Language setting is dead (#48); translate-target default code mismatch (`LiveAIModeManager.swift:63-67`); "正在识别" spoken in Chinese regardless of language (`QuickVisionIntent.swift:312`); OpenClaw `device.info` reports `appVersion "1.5.0"` (`OpenClawCommandRouter.swift:165`); no reachable Disconnect/unregister UI (#53); records swipe-delete in `LazyVStack` (#31); duplicate string keys (§1.9).

---

## 6. Settings field-by-field comparison

| Setting | iOS (file:line, storage key, options) | Android (file:line, storage key, options) | Gap |
|---|---|---|---|
| App language | `SettingsView.swift:95-111` → `AppLanguageSettingsView` 988-1050; `app_language` = system / zh-Hans / en; restart required | `SettingsScreen.kt:371-376`; `language_prefs.app_language` = system / zh-CN / en; live; auto-syncs output language | parity |
| Vision API provider | 114-130 → `APIProviderSettingsView` 469-581; `api_provider` = alibaba / openrouter | 205-213; `api_provider_prefs.api_provider` | parity |
| Alibaba region | inside provider view 504-531; `alibaba_endpoint` = beijing / singapore | 216-227; same key | parity |
| Vision model | 132-149 → `VisionModelSettingsView` 691-873; `selected_vision_model`; Alibaba list qwen3-vl-plus / qwen3-vl-max; OpenRouter browser | 267-272 → `VisionModelSelectionDialog` 908-1098; same key; Alibaba list qwen-vl-flash / qwen-vl-plus / qwen-vl-max / qwen2.5-vl-72b-instruct | model list differs (#50) |
| API key (current provider/endpoint) | 169-185 → `APIKeySettingsView` 585-687 (Keychain accounts §1.8) | 231-262 → `ApiKeyDialog` 736-824 (`turbometa_secure_prefs` keys §2.6) | parity |
| Output language | 151-167 → `LanguageSettingsView` 877-926; **not persisted** (`@State selectedLanguage`, line 30); zh-CN,en-US,ja-JP,ko-KR,es-ES,fr-FR | 381-386 → `LanguageSelectionDialog`; `turbometa_secure_prefs.output_language` default zh-CN; same 6 codes; used by services | iOS dead setting (#48) |
| Video quality | 187-203 → 930-984; `video_quality` low/medium/high | 390-395; `video_quality` LOW/MEDIUM/HIGH | parity |
| Quick Vision settings | 206-222 → `QuickVisionSettingsView` (mode, target language, custom prompt, **history**) ; keys `quickVisionMode`, `quickVisionCustomPrompt`, `quickVisionTranslateTargetLanguage` | 318-323 → `QuickVisionModeScreen` (mode, target language, custom prompt); keys `quick_vision_prefs.quick_vision_mode/…` | history missing (#21) |
| Wake word toggle / Picovoice key / Background running | none | 328-365; `porcupine_prefs.porcupine_access_key` | reverse |
| Live AI provider | 230-246 → `LiveAIProviderSettingsView` 1054-1141; `liveai_provider` alibaba / google | 287-295; same key | parity |
| Google API key | 249-267 (only when google) → `GoogleAPIKeySettingsView` 1145-1237; Keychain `google-api-key` | 298-312 (only when google); `google-api-key` | parity |
| Live AI model | not exposed (`liveai_model` default only) | not exposed (VM only) | parity |
| Live AI mode / custom prompt / target language | 270-286 → `LiveAISettingsView`; keys `liveAIMode`, `liveAICustomPrompt`, `liveAITranslateTargetLanguage` | 278-283 → `LiveAIModeScreen`; keys `live_ai_prefs.live_ai_mode/…` | parity |
| Translation settings | 289-302 → `LiveTranslateSettingsView` (keys `translate_*`) | none | missing (#13) |
| OpenClaw | 308-333 → `OpenClawSettingsView` (keys `openclaw_enabled/host/port`, Keychain token) | none | missing (#6) |
| Records / clear all | none in settings (Records tab) | 400-415 | reverse |
| About | 336-341: Version 2.0.0, SDK 0.5.0 | 420-461: Version 1.5.0, GitHub, Download, Buy Me a Coffee | SDK row missing (#54); links reverse |
| Device status | 50-91 (connected, online, stream active) | Home card (`HomeScreen.kt:578-715`) with Connect/Disconnect | placement differs |

---

## 7. Provider / model / endpoint comparison

| Item | iOS | Android |
|---|---|---|
| Alibaba REST | `https://dashscope.aliyuncs.com/compatible-mode/v1` / `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` (`APIProviderManager.swift:22-27`) | same (`APIProviderManager.kt:39-43`) |
| Alibaba WS | `wss://dashscope(-intl).aliyuncs.com/api-ws/v1/realtime` | same |
| OpenRouter | `https://openrouter.ai/api/v1`; headers `HTTP-Referer: https://turbometa.app`, `X-Title: TurboMeta` | same (`VisionAPIService.kt:106-109`) |
| Gemini Live WS | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` | same |
| Vision default model | alibaba `qwen3-vl-plus`; openrouter `google/gemini-3-flash-preview` | alibaba `qwen-vl-flash`; openrouter `google/gemini-2.0-flash-001` |
| Live AI default model | `qwen3-omni-flash-realtime`; `gemini-2.0-flash-exp` | same |
| Omni voice | Cherry / Ethan by app language | Cherry fixed |
| Omni audio out | pcm24 | pcm16 |
| Gemini voice | Aoede | Aoede |
| TTS | `qwen3-tts-flash` cloud + `AVSpeechSynthesizer` fallback | platform TTS only |
| ASR (OpenClaw) | `fun-asr-realtime` | none |
| Live translate | `qwen3-livetranslate-flash-realtime` | none |
| LeanEat | `qwen3-vl-plus` Beijing fixed | `qwen-vl-plus` Beijing fixed |
| API key help URLs | `https://help.aliyun.com/zh/model-studio/get-api-key`, `https://openrouter.ai/keys`, `https://aistudio.google.com/apikey` | same + Picovoice `https://console.picovoice.ai/` |

---

## 8. Localization comparison (key groups)

| Group | iOS keys (en/zh-Hans) | Android keys (values/values-zh-rCN) |
|---|---|---|
| App/general | `app.*`, `ok/cancel/close/done/error/loading/save/delete/retry/back` | `app_name`, `ok/close/back/cancel/delete/save/retry/loading` |
| Home | `home.liveai/quickvision/translate/leaneat/wordlearn/livestream/rtmp.*`, `home.experimental`, `home.comingsoon`, `home.openclaw.*` | `feature_*_title/subtitle` (no openclaw, `feature_rtmp_subtitle` embeds "(Experimental)"), no `home_comingsoon` (hard-coded) |
| Live AI | `liveai.*` (incl. `liveai.device.*`) + modes + prompts | `liveai_*` + modes + prompts (`prompt_liveai_*`) |
| Quick Vision | `quickvision.*` incl. `quickvision.siri.*`, `quickvision.history.*`, `quickvision.records.*` | `quickvision_*`, `picovoice_*`, `wakeword_*`, `quick_vision*` |
| Live Translate | `livetranslate.*` (~60 keys incl. 18 `lang.*`, 8 `voice.*` + `.desc`) | none |
| OpenClaw | `openclaw.*` (20), `settings.integrations`, `home.openclaw.*` | none |
| RTMP | `rtmp.*` (23) | none |
| Settings | `settings.*` incl. `settings.sdkversion`, `settings.endpoint.*.desc`, `settings.model.qwen3vl*.desc`, `settings.applanguage.restart.*`, `settings.liveai.*.desc` | `settings_*`, `api*`, `apikey_*`, `provider_*`, `endpoint_*`, `applanguage_*`, `github_*`, `download_*`, `support_*`, `buy_me_coffee*`, `data`, `clear_*` |
| Stream/camera | `stream.*` (title/stop/photo/timer/ending/connecting/waiting) | `stream_*` (live/no_video/start/stop/capture/timer/unlimited/1-15min) unused |
| Photo | `photo.share/ai/nutrition/save/saved` | `gallery_share` (unused) |
| Errors/TTS/units | `error.*`, `tts.*`, `unit.*` | `permission_*`, `camera_permission_denied`, dialogs |

---

## 9. Android-only features (reverse gaps) for context
* Porcupine "Jarvis" wake word + always-on FGS with notification Stop action (`PorcupineWakeWordService.kt`).
* Background `QuickVisionService` FGS with status broadcasts (`QuickVisionService.kt`).
* Bluetooth HFP mic switching in Live AI (`BluetoothAudioManager.kt`, `LiveAIScreen.kt:631-671`).
* Working Output Language with ja/ko prompts (`VisionAPIService.kt:155-199`, `OmniRealtimeService.kt:291-323`).
* Vision model list with `qwen-vl-flash`/`qwen2.5-vl-72b-instruct`.
* Home device card with explicit Connect / Disconnect + confirm (`HomeScreen.kt:578-715`).
* Save image to gallery in Vision/LeanEat (`LeanEatViewModel.kt:115-150`).
* About links, records count, Clear-all, battery-optimization shortcut.
* True-resolution RTMP encoding with timestamp smoothing.

---

## 10. README-derived items not visible in code
* `README_EN.md:171` / `README.md:174`: Android "does not yet include v2.0 features (OpenClaw, Meta Ray-Ban Display)" — consistent with code.
* `README_EN.md:196-226` core feature list: Quick Vision (Siri/Shortcuts/Action Button/TTS), Live AI, LeanEat, Real-time Photography (auto-start, nutrition or AI recognition after capture, preview), Live Streaming. The "Real-time Photography" item maps to `StreamView` (#34) — absent on Android.
* `README_EN.md:354-452` Quick Vision tutorial: Siri phrase, Action Button, Lock-screen Shortcuts widget — Android analogue must be documented once #17 is built.
* `README_EN.md:521-585` OpenClaw setup: gateway `bind: "lan"`, `nodes.allowCommands: ["camera.snap","camera.clip","camera.list","device.status","device.info"]` (note `camera.clip` is allowed by config but not implemented by iOS), `openclaw devices approve`, Tailscale IP `100.x.x.x:18789`.
* `README_EN.md:307-314` roadmap still lists "Real-time translation feature" and "Android Quick Vision support" as planned; both exist on their platforms.
* `android/README.md`: version 1.4.0 header (3), API 26 requirement (198) vs `minSdk 31`; Gemini Live "not fully tested" (79-86); Picovoice setup (49-77).
* `android/CHANGELOG.md`: only 1.0.0 (DAT 0.3.0), lists "直播推流功能尚未完成 / 实时翻译功能开发中 / WordLearn 功能开发中" (259-262).
