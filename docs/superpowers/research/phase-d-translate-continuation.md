# Phase D translation continuation — bounded research

Checked 2026-09-11. Research only: no app edits, Gradle, adb, credential reads or authenticated cloud calls. Scope is spec §9 item 3, not the camera/TTS items. Public availability is distinct from a successful call with this account/region.

## Model decision and evidence boundary

Keep **`qwen3-livetranslate-flash-realtime`**, exactly as approved and used by iOS. Alibaba's current guide explicitly lists it as still served, equivalent to snapshot `qwen3-livetranslate-flash-realtime-2025-09-22`; it separately recommends `qwen3.5-livetranslate-flash-realtime`. Its old-model note retains 18 languages. Do not silently change the model, expand to 60 languages, add cloning, or substitute the newer default voice. [Current guide, old-model section](https://help.aliyun.com/zh/model-studio/qwen3-5-livetranslate-flash-realtime)

Alibaba's rate table also lists the old alias/snapshot in Beijing and Singapore. This confirms catalog presence, not account entitlement or legacy endpoint connectivity. [Rate limits](https://help.aliyun.com/zh/model-studio/rate-limit)

**Protocol caveat:** today's client/server pages describe Qwen 3.5. Search-indexed historical Japanese client documentation (Jan 22, 2026) names Qwen 3 and `pcm16` input/`pcm24` output; direct client-page fetch failed, and the directly opened Japanese server page is now updated to Qwen 3.5. Treat that historical result as corroboration of the iOS baseline, not current wire-contract proof. [Historical client URL](https://www.alibabacloud.com/help/ja/model-studio/live-translator-client-events), [current client reference](https://help.aliyun.com/zh/model-studio/live-translator-client-events)

## Exact iOS parity inventory

Read [spec](D:/Coding/Workspaces/Android/turbometa-rayban-ai/docs/superpowers/specs/2026-09-10-android-v2-design.md:155), [models](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Models/LiveTranslateModels.swift), [service](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Services/LiveTranslateService.swift), [ViewModel](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/ViewModels/LiveTranslateViewModel.swift), [settings](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Views/LiveTranslateSettingsView.swift) and [page](D:/Coding/Workspaces/Android/turbometa-rayban-ai/CameraAccess/Views/LiveTranslateView.swift).

The 18 exact language values, in iOS declaration order; “target” below means the **existing iOS audio-output target picker**, not a claim that text-only translation is impossible for the other seven:

| Value | Language | iOS audio target |
| --- | --- | --- |
| `en` | English | yes |
| `zh` | Chinese | yes |
| `ja` | Japanese | yes |
| `ko` | Korean | yes |
| `fr` | French | yes |
| `de` | German | yes |
| `ru` | Russian | yes |
| `es` | Spanish | yes |
| `pt` | Portuguese | yes |
| `it` | Italian | yes |
| `yue` | Cantonese | yes |
| `id` | Indonesian | no |
| `vi` | Vietnamese | no |
| `th` | Thai | no |
| `ar` | Arabic | no |
| `hi` | Hindi | no |
| `el` | Greek | no |
| `tr` | Turkish | no |

All 18 appear as sources. iOS always filters the target picker to 11, even when audio is off; swapping requires both sides to support audio. Its “input-only” comment is a UI restriction, not sufficient API evidence. The current guide's larger language/output table describes the newer model; do not apply that table to Qwen 3 automatically.

| Exact voice value | iOS allowed targets | Alibaba old-model voice description |
| --- | --- | --- |
| `Cherry` | `zh,en,fr,de,ru,it,es,pt,ja,ko` | Mandarin/multilingual; old-model default |
| `Nofish` | same ten | Mandarin/multilingual |
| `Jada` | `zh` | Shanghainese |
| `Dylan` | `zh` | Beijing Chinese |
| `Sunny` | `zh` | Sichuanese |
| `Peter` | `zh` | Tianjin Chinese |
| `Kiki` | `yue` | Cantonese |
| `Eric` | `zh` | Sichuanese |

The eight names and dialect restrictions are explicitly separated from Qwen 3.5's voices in Alibaba's catalog. `Ethan` is not one of these eight despite appearing in a generic server example. [Old-model voice table](https://help.aliyun.com/zh/model-studio/omni-voice-list)

| Persisted key | iOS default |
| --- | --- |
| `translate_source_language` | `en` |
| `translate_target_language` | `zh` |
| `translate_voice` | `Cherry` |
| `translate_audio_enabled` | `true` |
| `translate_image_enhance` | `false` |
| `translate_use_phone_mic` | `false` |

iOS settings disable incompatible voices but do not repair a previously selected voice when the target changes. Android should validate loaded/changed combinations atomically (e.g. Cantonese → `Kiki`; other supported targets → compatible voice). This is a correctness refinement, not a new voice/model. History is RAM-only, newest-first, max 50, recorded on stop; `currentOriginal` remains an empty placeholder. Do not claim original-text parity or persist conversation content merely because TranslateRecord is Codable.

## Exact baseline payload and transport comparison

iOS uses `Authorization: Bearer <configured key>` and appends `?model=qwen3-livetranslate-flash-realtime` to its provider's realtime endpoint. Its Alibaba endpoints are `wss://dashscope.aliyuncs.com/api-ws/v1/realtime` (Beijing) and `wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime` (Singapore). Select an Alibaba credential/region explicitly: iOS's generic `staticLiveAIProvider` getter could otherwise point translation at a different provider.

This is the **actual iOS default request**, transcribed as a baseline fixture, not asserted to be accepted unchanged by today's old-model server:

```json
{"event_id":"translate_<unique>","type":"session.update","session":{
  "modalities":["text","audio"],"voice":"Cherry",
  "input_audio_format":"pcm16","output_audio_format":"pcm24",
  "input_audio_transcription":{"language":"en"},
  "translation":{"language":"zh"},
  "turn_detection":{"type":"server_vad","threshold":0.5,
    "prefix_padding_ms":300,"silence_duration_ms":500}
}}
```

Audio off changes modalities to `["text"]`. Capture is mono signed PCM16 little-endian at **16,000 Hz**; playback decodes signed PCM16 at **24,000 Hz**. `pcm24` in this historical configuration denotes the output-rate convention; do not convert returned samples as 24-bit integers. iOS explicitly consumes two bytes per output sample.

Qwen's own versioned demo confirms the exact old model, legacy Beijing URL and 16k/24k PCM16, but sends `output_audio_format:"pcm16"`, `translation.source_language`, and a different incremental event than iOS. It therefore supplies historical implementation evidence and a **conflict**, not a reason to replace fields blindly. [Qwen demo at cfa54d3](https://huggingface.co/spaces/Qwen/Qwen3-Livetranslate-Demo/blob/cfa54d31cf7ce11fa988a90e38639e4e0e09a9d1/app.py)

Current client docs use `input_audio_format:"pcm"`, `sample_rate:16000`, `output_audio_format:"pcm"`. Source language is `input_audio_transcription.language`; the newer ASR/default and VAD/manual options must not be assumed identical on Qwen 3. Current connection examples use workspace-scoped `*.cn-beijing.maas.aliyuncs.com` / `*.ap-southeast-1.maas.aliyuncs.com` URLs. Legacy URL/account and accepted format/VAD fields require an exact-model smoke test; no endpoint migration is authorized by this note. [Client reference](https://help.aliyun.com/zh/model-studio/live-translator-client-events), [connection guide](https://help.aliyun.com/zh/model-studio/qwen3-5-livetranslate-flash-realtime)

## Events and parser requirements

| Direction/event | Payload or handling |
| --- | --- |
| client `input_audio_buffer.append` | `event_id`, `audio`: Base64 raw PCM bytes, no WAV header |
| client `input_image_buffer.append` | `event_id`, `image`: Base64 JPEG, no data-URL prefix |
| server `session.created` | initial configuration; not acknowledgment of our requested settings |
| server `session.updated` | validate acknowledged configuration before enabling capture |
| server `response.text.text` | text-only interim `text` + revisable `stash` |
| server `response.audio_transcript.text` | audio-mode interim `text` + revisable `stash` |
| server `response.text.done` | final `text` |
| server `response.audio_transcript.done` | final **`transcript`** |
| server `response.audio.delta` / `.done` | Base64 `delta` / generation end, not device playback completion |
| server `response.created` / `.done` | response identity/status; do not treat incomplete/failed as successful final |
| server `error` | structured `error.type/code/message/param` |

The receive fields above are current reference semantics, with old-model behavior still to be captured. Correlate by response/item IDs; interim stash is replaceable, never blindly append every provisional string. Optional source ASR uses `conversation.item.input_audio_transcription.text/completed/failed` (`text/stash`, then `transcript`). [Server reference](https://help.aliyun.com/zh/model-studio/live-translator-server-events)

iOS instead reads `delta` on `response.audio_transcript.text`, reads `text` on its `.done`, and lacks `response.text.text` handling. The versioned Qwen demo also reads final `transcript`. These are concrete parser-risk fixtures; porting iOS's callbacks verbatim would miss documented results. Ignore unrelated lifecycle events safely rather than borrowing Omni's `.delta` parser.

## Image feeding and resource lifetime

iOS's recording timer sends at most one frame/500 ms, JPEG quality 0.6, rejecting >500×1024 bytes. It does not explicitly gate the first image on a successful audio append. Its page starts the glasses stream whenever image enhancement is enabled and stops on exit.

Current client limits: JPG/JPEG, ≤500 KB before Base64, ≤2 fps, recommended 480p/720p and at most 1080p; at least one audio append must precede an image. Use ≤500,000 bytes conservatively. These are current shared-document limits; old-model image acceptance remains a live-check item, although its catalog billing includes images. [Image event](https://help.aliyun.com/zh/model-studio/live-translator-client-events)

Android continuation: gate on configured + recording + first accepted audio send; coalesce to the newest frame, encode off Main, bound dimensions/bytes, and skip absent/stale frames. Hold a distinct camera owner only while visual enhancement needs frames; release only that owner. `SessionFrameProvider.snapshot()` can borrow `OpenClawSnap` when no camera is held, so do not call it every 500 ms as an implicit streaming loop. Reuse encoding logic with the translation-owned stream/latest-frame lifecycle.

## Android reuse and cancellation decisions

Read `services/{OmniRealtimeService,GeminiLiveService,PcmAudioSource,HttpClients}.kt`, `managers/BluetoothAudioManager.kt`, `glasses/{GlassesFrameProvider,GlassesSessionManager}.kt`; existing mode-manager translate keys are unrelated, not an implementation of these six preferences.

- Reuse `HttpClients.websocket` (shared OkHttp, 30-second ping, no read timeout), existing Gson/Base64 dependencies and state-flow UI patterns. Build a separate translation protocol service; no Omni prompt or Gemini wire messages.
- Prefer injectable `PcmAudioSource`: already 16k mono PCM16, copied chunks and start Boolean. Its `stop()` cancels the reader/releases AudioRecord while retaining the reusable scope. Coordinate start/stop on one owner; do not report recording=true after start=false.
- Omni's phone capture is 24k (Bluetooth 16k); that is unsuitable unchanged. Gemini's capture/output rates already match 16k/24k, but its `setup`/`realtime_input`/`serverContent` protocol does not.
- Reuse AudioTrack configuration patterns for 24k mono PCM16, with a bounded serialized playback queue. Keep partial two-byte samples across chunks; finish generation and finish audible playback are separate events. Do not reuse Omni's unused 24-bit conversion helper.
- Mic routing uses `BluetoothAudioManager` plus MIC or VOICE_COMMUNICATION. A requested Bluetooth route is not a successful SCO connection; wait for actual route/start outcome and show failure or the actual phone route. Do not promise a glasses microphone merely from the preference.
- Omni/Gemini reset cancelled scopes on reconnect and close+cancel sockets, but weak listeners lack socket-generation fencing and connect guards only cover already-connected state. Reuse the client, not those race assumptions: fence callbacks/queued audio/image jobs by attempt identity, including connecting→stop→restart.
- iOS `disconnect()` immediately closes and stops capture/playback; it sends no finish. Current docs require `session.finish` → `session.finished` before orderly close. Treat graceful stop (bounded drain/finish) separately from abort (immediate mute/cancel/release); old-model finish support needs verification. No documented `response.cancel` was found in the translation client reference. [Finish event](https://help.aliyun.com/zh/model-studio/live-translator-client-events)
- Disconnect/error/navigation STOP must stop capture/images, clear playback and release owned camera/SCO resources exactly once. Recreate per-connection jobs on reuse; ignore late errors/finals from retired sessions. Do not copy Omni's speech-start playback interruption into simultaneous translation without checking its semantics.

## Continuation gates and focused fixtures

1. Pure models/settings: exact 18/8 values, six defaults, corrupt-value fallback, target/voice compatibility, atomic swap, RAM history policy.
2. Protocol: record iOS baseline JSON and distinct current-doc candidate; test actual field names, text-only interim updates, stash revision, final transcript, interleaved IDs, malformed events and failed responses. Do not mark an unverified candidate as supported.
3. Lifecycle/audio: fake source/socket/output; connecting cancellation, start failure, late callbacks, reconnect reuse, bounded queue, PCM frame alignment and independent output mute. Image tests cover first-audio gate, 500 ms pacing, byte/dimension caps and owner cleanup.
4. Later authorized live check: selected Alibaba region, exact old alias, session acknowledgment, accepted PCM labels/VAD, 16k fixture→24k output, both modalities, Cantonese/Kiki, image append, finish/trailing utterance and reconnect. No credentials, microphone audio or images were sent during this research.
5. Unproven assumptions to keep visible: literal iOS wire compatibility, all 18 as spoken targets, newest ASR/manual/cloning options on the old model, universal legacy endpoint access, and error-free microphone/camera reuse. Spec's model/counts are supported; these broader assumptions are not established by that spec or today's mixed-version docs.
