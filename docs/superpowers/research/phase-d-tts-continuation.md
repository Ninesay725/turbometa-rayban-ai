# Phase D TTS continuation — implementation facts

Checked 2026-09-11 against current source and primary documentation. Only this note was written; no app edits, Gradle/adb, tests, live API requests, credentials, preferences, or notification/log data were accessed.
Approved scope: [spec §9 item 2](D:/Coding/Workspaces/Android/turbometa-rayban-ai/docs/superpowers/specs/2026-09-10-android-v2-design.md:160): `qwen3-tts-flash` HTTP SSE, PCM16/24 kHz via AudioTrack, Cherry/Ethan by language, system TTS for OpenRouter/missing Alibaba key, unified Quick Vision status/result speech.

## Verified HTTP contract

- Model: exactly `qwen3-tts-flash`. Alibaba documents complete-text HTTP requests with incremental audio output. Its `*-realtime` WSS models use a separate protocol. [Non-real-time guide](https://www.alibabacloud.com/help/en/model-studio/non-realtime-tts-user-guide)
- Beijing: `POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`. [China API reference](https://help.aliyun.com/zh/model-studio/qwen-tts-api)
- Singapore: `POST https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`. Keys are region-specific. [International API reference](https://www.alibabacloud.com/help/en/model-studio/qwen-tts-api)
- Required streaming example headers: `Authorization: Bearer <regional-key>`, `Content-Type: application/json`, `X-DashScope-SSE: enable`. The documented HTTP body is:

```json
{"model":"qwen3-tts-flash","input":{"text":"你好。","voice":"Cherry","language_type":"Chinese"}}
```

- `input.text` and `input.voice` are required; `language_type` defaults to `Auto`. The base-model HTTP example has no `stream`, `format`, `sample_rate`, session, or instruction fields. Input limit: 600 characters for this model. [China API reference](https://help.aliyun.com/zh/model-studio/qwen-tts-api)
- English can use `Ethan` with `English`; both Cherry and Ethan are listed under **non-real-time** voices for this exact model and support additional languages. The app's binary voice policy is a product choice. [Voice list](https://www.alibabacloud.com/help/en/model-studio/qwen-tts-voice-list)
- Intermediate `output.audio.data` contains Base64 audio segments. The guide explicitly describes PCM; its Python/Java playback examples specify signed PCM16, 24,000 Hz, mono, little-endian, two bytes/frame. No WAV header is needed for chunk playback. [Guide, Streaming output](https://www.alibabacloud.com/help/en/model-studio/non-realtime-tts-user-guide)

## Events, end, errors, cancellation

Synthetic SSE fixtures using documented JSON fields and the local iOS `data:` framing:

```text
data: {"output":{"finish_reason":null,"audio":{"data":"<base64-pcm>"}}}

data: {"output":{"finish_reason":"stop","audio":{"data":"","url":"<complete-audio-url>"}}}

```

- The documented generation terminator is `output.finish_reason == "stop"`; while generating it is null. The final chunk has empty audio data and the complete-file URL. Intermediate chunks carry audio data. The final URL need not be downloaded for streaming playback. [International API reference](https://www.alibabacloud.com/help/en/model-studio/qwen-tts-api)
- Error object fields documented: `status_code`, `request_id`, `code`, `message`. HTTP examples include 400/401/404/500; common platform errors also cover permission/quota/rate limits. Check HTTP status independently; the displayed response schema is SDK-shaped, so do not require a JSON `status_code` field in every raw SSE chunk. [API reference](https://www.alibabacloud.com/help/en/model-studio/qwen-tts-api), [platform errors](https://www.alibabacloud.com/help/en/model-studio/error-code)
- Documentation gaps: checked HTTP pages do not specify literal `event:` names, a required `[DONE]` sentinel, exact midstream-error framing, or a server-side HTTP cancellation command/acknowledgement. Focused searches returned WSS examples; those provide no evidence for this endpoint. Actual raw HTTP framing remains a fixture-verification item.
- Local iOS handles `data:` JSON and `[DONE]`/EOF; it does not inspect `finish_reason`, structured API errors, or named SSE events. This is source behavior, not a stronger provider guarantee.
- Proposed Android handling: parse complete SSE records, ignore metadata/comments, accept documented audio/stop fields, reject malformed JSON/Base64 and explicit errors, bound body/record buffering. Premature EOF and zero-audio completion need explicit outcomes, not an unbounded wait.
- Proposed cancellation: invalidate the utterance generation, cancel its coroutine and OkHttp Call, close its response, stop its playback writer, discard queued PCM, and stop system fallback. Late chunks/callbacks cannot complete or stop a newer utterance. Cancellation must not trigger fallback; no WSS `response.cancel`/session events belong here.
- Network completion and audible completion are separate. Count accepted frames and wait for playback progress with a deadline. AudioTrack flush discards queued data only when paused/stopped; writes can queue audio before it is heard. [AudioTrack reference](https://developer.android.com/reference/android/media/AudioTrack)

## Exact iOS reference and port boundaries

- [TTSService.swift:16](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/TTSService.swift:16): hardcoded Beijing URL and model; no selected-region URL mapping.
- [speak:129](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/TTSService.swift:129): cancel/stop previous task; OpenRouter or missing Alibaba key uses AVSpeechSynthesizer; cloud failure also falls back when the task is not cancelled.
- [request:189](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/TTSService.swift:189): exact body/headers above, 30-second request timeout, URLSession byte streaming, requires HTTP 200.
- [decode:246](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/TTSService.swift:246): reads each data line; nonempty `output.audio.data` is Base64-decoded and queued. Invalid/empty chunks are skipped. `noAudioData` is declared but never thrown.
- [playback:318](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/TTSService.swift:318): PCM16 converted to Float32 for a 24 kHz mono AVAudioPlayerNode. [completion:352](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/TTSService.swift:352) polls `isPlaying`; there is no per-buffer completion callback or deadline. Android needs its own bounded drain completion.
- [stop:179](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/TTSService.swift:179) cancels the task/reset engine; system synthesizer cancellation is checked in the fallback polling loop. Provider-fallback tasks assign `isSpeaking = false` after their await without an utterance identity check. Preserve intended latest-speech behavior with identity fencing in Android.
- [iOS LanguageManager:129](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Managers/LanguageManager.swift:129) maps app Chinese → Cherry/Chinese, otherwise Ethan/English. [iOS APIKeyManager:108](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Utils/APIKeyManager.swift:108) resolves an omitted region from current settings: its key choice can therefore disagree with TTS's fixed Beijing URL.
- iOS consumers include [QuickVisionIntent:257](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Intents/QuickVisionIntent.swift:257) and [QuickVisionView:11](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Views/QuickVisionView.swift:11). These are reference behavior only; no iOS changes are planned.

## Android reuse seams and call-site continuation

| Existing source | Reuse / boundary |
| --- | --- |
| [APIProviderManager.kt:299](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/managers/APIProviderManager.kt:299) | `currentProvider` and `alibabaEndpoint` StateFlows; `getCurrentAPIKey` at 396. Snapshot provider/region/key together for each utterance. Existing `baseURL` is chat-compatible and `websocketURL` is realtime; map the two HTTP TTS URLs separately. |
| [APIKeyManager.kt:139](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt:139) | `getAPIKey(APIProvider.ALIBABA, endpoint)` already supports region-separated storage; no new credential store. `getOutputLanguage` at 249 is separate from app language. |
| [LanguageManager.kt:66](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/managers/LanguageManager.kt:66) | `isChinese(context)` reflects app/system choice; existing result-language preference includes more than Chinese/English. Preserve caller locale intent when choosing voice and `language_type`. |
| [VisionAPIService.kt:46](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/VisionAPIService.kt:46) | Existing OkHttp/Gson dependencies and provider wiring; request at 110 blocks then reads the whole body. It exposes no cancellable streaming reader to reuse directly. |
| [OmniRealtimeService.kt:437](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/OmniRealtimeService.kt:437), [GeminiLiveService.kt:547](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/GeminiLiveService.kt:547) | Private 24 kHz mono PCM16 MODE_STREAM AudioTrack setup. Useful format/attribute examples; queues are mutable lists, write return values are ignored, and 100 ms queue emptiness drives speaking state. They are not reusable drain/cancellation contracts. |
| [PcmAudioSource.kt:14](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/PcmAudioSource.kt:14) | `start(onChunk)/stop` is **16 kHz microphone capture** for [FunASRService.kt:162](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/FunASRService.kt:162). Keep it input-only; TTS needs an output playback seam and no microphone permission/camera claim. |
| [BluetoothAudioManager.kt:205](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/managers/BluetoothAudioManager.kt:205) | SCO uses MODE_IN_COMMUNICATION; `scoRequested` ensures failed/pending connection cleanup restores MODE_NORMAL at 227. Preserve this Phase B fix; TTS must not globally reset another feature's microphone/SCO owner. |
| [QuickVisionRunCoordinator.kt:15](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionRunCoordinator.kt:15) | Reruns cancel/join before cleanup/reacquisition. `awaitQuickVisionSpeech` at 62 provides DONE/ERROR/STOPPED/REJECTED/TIMED_OUT, 60-second bound, and unconditional stop cleanup. Retain this contract for cloud and system speech. |

Proposed bounded migration (no code written):

1. Add `services/TTSService.kt` as shared speech logic with injectable HTTP/PCM playback boundaries, Main-owned utterance identity, worker streaming/write work, bounded buffering, and explicit completion/stop. Reuse installed OkHttp/Gson/coroutines and platform TextToSpeech; no Alibaba SDK dependency is needed.
2. [QuickVisionService.kt:114](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt:114): replace local engine setup/readiness; migrate `speak` at 355 and `speakAndWait` at 363. Calls are looking at 204, result at 311, analysis failure at 315, and `failAndDwell` at 338. Destruction at 166 must cancel its own speech. Keep rerun ordering, display dwell/claims, and completion broadcasts.
3. [QuickVisionScreen.kt:103](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/ui/screens/QuickVisionScreen.kt:103): replace its independent TextToSpeech setup, `speak` at 128, `stopSpeaking` at 146, result/replay calls at 260/499, and disposal at 289. Screen exit/STOP must cancel its owned jobs; a late analysis result cannot restart speech after exit. Marshal speaking-state callbacks to Main.
4. Preserve language semantics explicitly: service statuses currently use system locale, results use output locale; the screen currently uses output language for both. iOS instead uses app language. Carry the caller's intended language into the new helper; avoid silently reducing existing ja/ko/es/fr output choices to English. Cherry/Ethan selection alone does not determine `language_type`.
5. Bound each cloud request to the documented 600-character limit; longer result handling needs ordered segmentation without truncation. Retain the existing total speech timeout. Define partial-audio failure behavior before implementation: iOS retries the whole text through system speech, which can repeat an already heard prefix; cancelled speech never falls back.
6. Add focused parser/transport/playback fixtures for split SSE records, audio+stop, no sentinel, malformed/empty/error/EOF, cancellation during read/write/drain, partial writes, superseded completion, and region/key pairing. Preserve [QuickVisionRunCoordinatorTest.kt:120](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/test/java/com/smartview/glassai/services/QuickVisionRunCoordinatorTest.kt:120) rerun-during-speech and outcome tests. Real AudioTrack/system-TTS checks belong in instrumentation, not mocked Android stubs.

## Required device checks — all pending

- Authorized synthetic text against each configured region: confirm raw HTTP SSE envelope/end/error behavior and PCM format; validate Cherry/Chinese and Ethan/English, pronunciation, latency, long-text boundaries, complete final syllable, and no replay from the final URL.
- Android phone speaker and Ray-Ban Display Bluetooth output: media route versus active HFP/SCO, volume controls, ducking/audio focus, pause/interruption, headset disconnect/reconnect, and restoration of pre-existing routing after stop/error.
- Quick Vision while camera/Display is active: no camera interruption, normal result/dwell release, Again during playback, rapid stop/start, app background, screen exit, service destruction, and late network/system callbacks. New speech must survive old-owner cleanup.
- OpenRouter, missing/wrong-region key, offline/timeout, rejected system language, unavailable system TTS engine, provider error before/after first chunk: bounded completion, expected fallback, no stuck speaking state, no post-cancel audio.
- Recheck microphone/SCO start failure and early exit from Phase B, plus concurrent Live AI/OpenClaw/media use. Verify synthesized speech does not leave call-volume mode, reopen recording, or revive a retired DAT session.

Verification status: source inspection and primary-document lookup only. No test, emulator, live Alibaba, real-app, or glasses behavior was executed for this note.
