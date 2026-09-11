# Phase D Task 3 — live translation protocol

Implementation handoff, 2026-09-11. Owned changes only: `services/LiveTranslateService.kt`, `services/LiveTranslateProtocol.kt`, their two JVM test files, and this report. Shared models, playback, routing/camera ownership, UI and resources remain with their assigned workers.

## Public integration

`com.smartview.glassai.services.LiveTranslateService()` has a public no-argument constructor. The class, three flow properties and four operations are open for test seams. It uses the shared `translation` model types:

```kotlin
val state: StateFlow<TranslateConnectionState>
val text: StateFlow<TranslationText>
val error: StateFlow<String?>
fun connect(apiKey: String, endpoint: String, settings: TranslateSettings, source: PcmAudioSource)
fun sendImage(jpeg: ByteArray): Boolean
fun disconnect()
fun close()
```

Public operations are Main-confined. Constructor injections are `client: WebSocket.Factory` (accepts OkHttpClient), feature-owned `playback: Pcm16Playback`, `dispatcher`, `configurationTimeoutMs` and monotonic `nowMs`. Production reuses `HttpClients.websocket`; no new client, dependency, credential store or toolchain setting.

Popper received the contract and confirmed the concrete no-arg adapter matches. UI creates a service/source per explicit Start, starts image work only on READY, and releases owned stream/images/SCO on ERROR/STOP. No new resource keys needed from this task.

## Readiness and retirement

- READY requires the current socket attempt's `session.updated` to match core PCM labels/modalities and voice when audio is enabled. Optional source/target/VAD/model echoes may be omitted; every present requested field is checked for contradictions. An echoed model must be the exact old alias or its documented `2025-09-22` snapshot; echoed sample rate must be 16,000 and cloning cannot be enabled. Missing core configuration fields fail closed; `session.created` alone never starts capture.
- Parent's optional-ACK ruling is incorporated. Reopened the [primary server-event reference](https://help.aliyun.com/zh/model-studio/live-translator-server-events): the current 3.5 reference explicitly returns source transcription config only when an ASR model was configured and marks translation optional. We configure no separate ASR model, so requiring that echo would be an unjustified blocker. Optional nested echoes and VAD details are checked when present; the successful current `session.updated` acknowledges the request when optional echoes are absent. This is a compatibility policy, not captured proof of the exact old-model ACK shape. Core format/modality/active-voice echoes remain required.
- Only after validation does `source.start` run. READY follows only a true return before the deadline. Source failure/throw, mismatched ACK or configuration timeout stop/cancel capture, socket and playback before ERROR is published.
- Connection plus configuration wait defaults to 10 seconds; injected deadlines must be 1–10,000 ms. The elapsed deadline is checked before and after synchronous source startup as well as by the waiting timer. The existing synchronous `PcmAudioSource.start` contract is retained; it is not a separately preemptible operation.
- Each attempt owns its job, queues and atomic retirement flag. Retired ACKs, errors, text and capture callbacks cannot revive or modify a successor. Reconnect retires first; disconnect permits reuse; close is terminal. No retry or automatic model/profile fallback.
- Immediate stop uses close plus cancel and clears queues/playback. It does not claim cloud `session.finish` acknowledgement, trailing utterance completion or audible drain on user cancellation.

## Protocol and bounds

- Exact approved `qwen3-livetranslate-flash-realtime` query model; historical iOS `session.update`, `pcm16` input / `pcm24` output labels, 16 kHz mono PCM16 capture and 24 kHz mono PCM16 playback. `pcm24` is not decoded as 24-bit samples.
- Raw PCM is Base64 in `input_audio_buffer.append`; input preserves a trailing odd byte until the next chunk. Decoded audio is passed to the shared playback consumer, which owns output alignment and platform writes.
- `response.text.text` and `response.audio_transcript.text` replace the current `text` + revisable `stash` snapshot. Legacy `.delta` and audio `.text` carrying `delta` append; `.done` accepts documented `transcript` and legacy `text`. Response/item identity fences reject retired content; successful final fields are not fabricated from `response.done`. Failed/incomplete/cancelled response status revokes finality and reports ERROR.
- Text is bounded to 8,000 UTF-16 units including an ellipsis, cutting at a complete code point. No-ID legacy streams cannot provide the same identity guarantees as explicit response IDs. The retired identity cache is bounded to 64; once explicit `response.created` lifecycle is observed, foreign content IDs cannot switch the current response.
- Incoming queue: 32 events, each <=131,072 characters. Input/output: 8 chunks each, each <=32,000 raw bytes. Outgoing socket queue guarded at 1 MiB including the proposed ASCII JSON payload. Overflow fails visibly rather than silently dropping speech. Error text is fixed/sanitized; no provider bodies, exception messages, speech, image data or headers are logged.
- Images require enabled + READY + at least one successful audio append, JPEG boundary markers, <=500,000 bytes and >=500 ms since the last accepted image. Rate/socket-pressure skips return false. UI owns actual decode, dimensions/downscaling and encoding; the service marker check is not a claim of full JPEG validation. Image work does not queue in this service.

## Verification evidence

**Authored, not executed by this worker:** 10 protocol test methods and 14 service test methods (24 total; coverage is the acceptance criterion, not a fixed count). Fixtures cover baseline payload/configuration mismatches and omitted optional ACK echoes; revised stash and final aliases; response/item fencing; Unicode text bound; missing ACK and no-open timeouts; late ACK after timeout and stop/restart; start=false and source-start elapsed deadline; close/reconnect/capture callback fencing; image gates/size/pacing/pressure; PCM alignment and Base64 output; bounded queue failure; sanitized errors; text-only output; failed response finality; retired response audio. One real local MockWebServer WebSocket fixture checks the exact request/query/header/configuration, an ACK omitting optional ASR/padding echoes, PCM append and final transcript. Other transport tests use an injected standard `WebSocket.Factory` fake and virtual time.

**Actually performed:** plan/research/interface reads; manual source and cross-worker API review; owned-file trailing-whitespace scan (zero findings); read-only workspace diff check (no whitespace errors). Tests were authored before their corresponding implementation. No RED/GREEN execution is claimed; parent is the sole Gradle/build/adb runner.

**Parent-run RED evidence inspected:** `testDebugUnitTest` XML reports service tests 14 run / 1 failure / 0 errors, and protocol tests 9 run / 0 failures / 0 errors. The sole service failure is `mockWebServerExchangesExactConfigurationRawPcmAndFinalTranscript`: expected `session.update`, observed `session.updated` at line 338 in that compiled fixture. The server queued a mutable JsonObject and then changed the same object's type to build its ACK. The fixture now calls `event.deepCopy()` and changes only the ACK copy; the queued request and assertion stay intact. This is a test-fixture correction, with no production behavior change. The parent's XML predates the tenth optional-ACK protocol test and this copy correction. The other 22 tests passed in that run; the latest 24-test source set and corrected fixture still await the parent's rerun. This worker ran no Gradle or adb.

**Pending:** parent rerun of the latest production/test sources and remaining integrated checks; authenticated exact-old-model endpoint/session acceptance; actual old-model transcript/audio/image events; target/voice combinations, microphone/SCO and glasses playback/camera behavior. Current mixed-version Alibaba docs are not proof that the literal historical wire profile is accepted by the current account/region. No silent 3.5 upgrade was introduced. See `docs/superpowers/research/phase-d-translate-continuation.md` for primary links and conflicting provider examples.
