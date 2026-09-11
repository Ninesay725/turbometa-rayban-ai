# Phase D Task 1 — speech implementation

Status: parent verification is GREEN after the HTTP cancellation correction: all 32 Task 1 JVM tests pass in both debug and release, and all 4 speech instrumented tests pass on API 31. Parent reports full build 2 passed with debug 491 and release 479 tests. This worker authored the speech implementation/tests and inspected parent-produced results, but ran no Gradle, adb, compiler, unit/instrumented tests, or live cloud/device calls. No commits, dependency changes, credential-file reads, or changes outside the assigned speech files and this report. This closure update changes only this report; no further TTS edits are needed.

## Binding contracts

No shared signature changes were needed:

```kotlin
interface Pcm16Playback {
    suspend fun play(chunks: Flow<ByteArray>)
    fun stop()
}
class AudioTrackPcm16Playback(sampleRate: Int = 24_000) : Pcm16Playback
class TTSService(context: Context) {
    suspend fun speak(text: String, languageCode: String): Boolean
    fun stop()
    fun close()
}
```

Dalton can consume `AudioTrackPcm16Playback()` directly. Each feature owns its playback/TTS instance. `stop()` cancels current work; a later call may reuse the instance. `TTSService.close()` is idempotent and permanently rejects later speech with false. Caller cancellation, stop, and superseding speech propagate cancellation rather than triggering fallback. Ordinary speech failure/deadline returns false; true requires completed playback of all nonblank segments.

## Files created

Production, under `android/app/src/main/java/com/smartview/glassai/services/`:

- `Pcm16Playback.kt` — binding public interface and real Android AudioTrack adapter.
- `StreamingPcm16Playback.kt` — injectable nonblocking PCM writer, odd-byte carry, drain, replacement lifecycle.
- `SpeechProtocol.kt` — settings/language/transport seams, <=600-unit splitting, bounded SSE parser.
- `HttpCloudSpeech.kt` — exact HTTP request and cancellable OkHttp streaming transport.
- `TTSService.kt` — feature-owned orchestration and existing provider/regional-key wiring.
- `AndroidSystemSpeech.kt` — lazy real TextToSpeech initialization, callback completion, stop/shutdown.

Tests:

- `android/app/src/test/java/com/smartview/glassai/services/Pcm16PlaybackTest.kt`
- `android/app/src/test/java/com/smartview/glassai/services/SpeechProtocolTest.kt`
- `android/app/src/test/java/com/smartview/glassai/services/HttpCloudSpeechTest.kt`
- `android/app/src/test/java/com/smartview/glassai/services/TTSServiceTest.kt`
- `android/app/src/androidTest/java/com/smartview/glassai/services/SpeechPlaybackInstrumentedTest.kt`

Report: `docs/superpowers/reviews/phase-d/task-1-report.md`.

## Implementation decisions

- Settings are sampled on Main once per utterance: existing vision provider, selected Alibaba region, and that region's stored Alibaba key. OpenRouter/missing key uses system speech. The public constructor retains application context only.
- Requests use `qwen3-tts-flash`, the Beijing/Singapore native multimodal-generation HTTP endpoint, `X-DashScope-SSE: enable`, and only `model` plus `input.text/voice/language_type`. Chinese selects Cherry; other locales select Ethan. The ten documented language names are mapped; unknown language codes use Auto for cloud synthesis.
- Text splitting preserves original content and surrogate pairs, using at most 600 UTF-16 units per segment (a conservative character bound) and preferring nearby whitespace/punctuation. Requests run sequentially. A failed cloud segment is retried through system TTS, followed by system speech for remaining segments; earlier completed segments are not repeated. A partially heard failed segment can repeat its prefix, as with the iOS fallback policy.
- SSE handles multiline data records, LF/CRLF, comments/metadata, Base64 PCM, documented `finish_reason: stop`, and legacy `[DONE]` after audio. It rejects malformed/oversized/empty/incomplete/error responses without echoing provider messages. The final audio URL is not fetched. Null terminal audio is accepted after preceding audio.
- Each SSE line/accumulated data record is bounded at 262,144 units (line limit in bytes, accumulated text in UTF-16 units); Base64 payloads are decoded one record at a time. A rendezvous flow buffer provides transport-to-playback backpressure. Playback accepts chunks up to 262,144 bytes and retains only one odd carry byte in addition to the current chunk/device buffer.
- OkHttp has 15-second connect and 30-second read/call limits. A separate cancellation child cancels the actual Call while either headers or body reads are blocked; responses close in finally. No body/header/credential logging is added.
- PCM16 is mono/little-endian. Nonblocking writes handle partial/zero/error returns; stalled writes and final drain each have a five-second deadline. Drain observes accepted versus played frames. Track creation, writing, and release occur on IO; stop pauses/flushes that run's own track. Mutex serialization prevents a cancelled waiting replacement from bypassing older cleanup.
- AudioTrack's API 31 `setStartThresholdInFrames(1)` permits short speech to begin without filling the device buffer. Physical output still requires device verification. [Android AudioTrack reference](https://developer.android.com/reference/android/media/AudioTrack#setStartThresholdInFrames(int))
- TTS has a 60-second total speech deadline. System engine initialization is bounded at three seconds; speech completion uses utterance-ID-specific callbacks with a deadline. Its completion promise is detached, avoiding an unfinished child that could otherwise keep a timed-out scope alive. Owner checks prevent queued stop/finally callbacks from stopping a newer utterance.
- Output uses media/speech audio attributes. There is no mic/SCO/camera/session acquisition and no AudioManager mode reset. Existing Phase B capture/SCO fixes and C/E display ownership are untouched.

## Authored tests versus executed tests

**Authored by this worker: 32 JVM tests and 4 instrumented tests (36 total). Executed by this worker: zero. Parent verified: 32/32 JVM tests in each variant and 4/4 speech instrumented tests.** Initial protocol/playback/orchestration/HTTP tests were written before production files. Additional edge cases were authored during source review. Parent-run RED and subsequent GREEN evidence are recorded below.

| Class | Count | Behavior exercised |
| --- | ---: | --- |
| `SpeechProtocolTest` | 10 | Surrogate-safe segmentation, content preservation, multilingual mapping, multiline/CRLF/comment SSE, stop/no sentinel, final audio/null audio, legacy DONE, empty/incomplete/malformed/Base64/error/bounds handling. |
| `Pcm16PlaybackTest` | 8 | Real pure playback engine with fake track boundary: odd chunk carry, partial writes, accepted-versus-played drain, incomplete sample, write/drain deadlines, negative writes, oversized chunks, stop/caller cancellation, and three-generation replacement ordering. |
| `TTSServiceTest` | 10 | Region/provider mapping, one settings snapshot, long request order, system completion result, fallback from failed segment, cancellation without fallback, replacement waiting for retired cleanup, stop/close, total deadlines for both backends, and audible-drain gating. |
| `HttpCloudSpeechTest` | 4 | Actual OkHttp against MockWebServer: exact request/header/body, fragmented transfer, no final-URL fetch, HTTP errors, actual Call cancellation during header wait and after audio while body reading is blocked. |
| `SpeechPlaybackInstrumentedTest` | 4 | Real AudioTrack silence/odd boundaries, one-frame short playback, stop/reuse; real system TTS completion/close and cancellation during initialization or playback. |

The system-completion instrumented test independently checks native engine initialization and English voice availability; it skips only when that prerequisite is unavailable, then asserts the adapter's completion. These fixtures contain no cloud requests or settings changes. JVM tests invoke no AudioTrack/TextToSpeech hardware path and make no platform-audio claims.

Inspected parent-produced XML for all four JVM classes under `android/app/build/test-results/testDebugUnitTest/` and `testReleaseUnitTest/`: each variant totals 32 tests, 0 failures, 0 errors, and 0 skips. Inspected [parent instrumentation transcript](speech-preferences-instrumentation.txt): all four speech tests have successful completion status, including actual system-engine completion, one-frame playback, stop/reuse, and close cancellation; none was skipped. Parent identifies the runtime as API 31. The same transcript confirms `TranslatePreferencesInstrumentedTest` 7/7 passed, for `OK (11 tests)` overall; those seven preferences tests belong to the other task and are not included in this worker's authored count. Full-suite debug 491/release 479 and successful build 2 are parent-reported totals. Source-only checks by this worker are separate from these parent-executed runtime results.

## Integration and remaining concerns

- Parent owns QuickVisionService/QuickVisionScreen integration, locale selection, and cancellation on STOP/disposal. Preserve run coordinator cancellation/join and display dwell/claims. Await `speak` for result completion; cancellation must stay cancellation in callers.
- The shared playback signature is ready for translation's bounded output Flow. Translation remains responsible for closing its producer/queue and invoking stop on disconnect; streaming playback itself has no total session-duration cap.
- Raw live Alibaba error/sentinel details remain pending as recorded in the research note. HTTP fixture behavior does not prove model entitlement or regional account compatibility.
- Verify phone/Bluetooth output, active HFP/SCO versus media routing, audio focus/interruption, final syllable/drain, rapid replacement/stop, and simultaneous camera/Display/Live AI/OpenClaw use. The fixed media attributes follow the approved binding without adding a routing parameter; hardware evidence may motivate a separately coordinated extension.
- The existing 60-second completion bound can end unusually long speech with false. Cloud failures after some audio may repeat only the current segment's prefix. No provider/session retries or stale audio replays are scheduled after cancellation.

No real cloud speech, microphone coexistence, headset routing, or glasses behavior has been validated by this worker.

## Parent-run HTTP cancellation RED and scoped correction — 2026-09-11

- Inspected `android/app/build/test-results/testDebugUnitTest/TEST-com.smartview.glassai.services.HttpCloudSpeechTest.xml`, timestamp `2026-09-11T13:03:49.079Z`: 4 tests, 2 failures, 0 errors, 0 skips. The exact-request and HTTP-error tests passed. Both `cancellationWhileWaitingForResponseCancelsTheActualOkHttpCall` and `cancellationAfterAnAudioRecordInterruptsABlockedBodyRead` failed with `java.net.SocketException: Socket closed`.
- The first stack originates in OkHttp response-header reading through `call.execute()`; the second originates in the response-body read through `speechLine()`/`readSpeechSse()`. The cancellation child correctly closes the actual Call, but the resulting synchronous IOException escaped the flow producer as a failure before coroutine cancellation was observed.
- Changed only `HttpCloudSpeech.kt` and this report in this follow-up. An IOException catch inside the transport's coroutineScope checks `currentCoroutineContext().ensureActive()` before rethrowing the original error. Cancelled work therefore propagates cancellation; active requests retain their original I/O/protocol failure for existing fallback handling. The existing response/Call cleanup remains in place, with no shared-contract changes.
- The two existing, unchanged cancellation tests are the observed RED regressions for this correction; no new tests were required. Parent reports the other TTS tests passed in the same run. The parent rerun is now GREEN: inspected `HttpCloudSpeechTest` XML shows 4 tests with 0 failures/errors/skips in debug (`2026-09-11T13:06:40.242Z`) and release (`2026-09-11T13:06:45.912Z`). The earlier RED entry is historical evidence from the previous result file; the current files contain GREEN results. No Gradle/adb execution was performed by this worker.
