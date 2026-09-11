# Task 5 report — `FunASRService` (DashScope Fun-ASR realtime speech-to-text)

Branch `android-v2`, base HEAD `5dc880e` (Task 4 / plan docs), commit **`2e458c2`**
`feat(android): FunASRService — DashScope fun-asr-realtime over WebSocket (run-task/finish-task, PCM16 16 kHz, region-aware endpoint) with a PcmAudioSource seam`

Status: **DONE**. All 6 brief steps done in order, TDD (RED → implement → GREEN). Zero deviations
from the brief's code — both production files and the test file were created verbatim from the
brief's Step 5.1/5.3/5.4 listings.

---

## 1. What was implemented

1. **Step 5.1 — `test/.../FunASRServiceTest.kt`** (new): 8 `@Test` methods, verbatim from the
   brief. Exercises the endpoint table, `resolvedUrl()` override precedence, the full run-task →
   task-started → binary PCM → result-generated (partial/final) → stop → finish-task → task-finished
   sequence, server-initiated `task-finished`/`task-failed`, transport-failure handling (with and
   without a prior `stop()`), and `switchAudioSource`.
2. **Step 5.2 — RED** confirmed: `compileDebugUnitTestKotlin` failed with ~24 "Unresolved
   reference" errors for `FunASRService`/`PcmAudioSource` members (`isListening`, `onStarted`,
   `start`, `stop`, `switchAudioSource`, etc.).
3. **Step 5.3 — `main/.../PcmAudioSource.kt`** (new): the `PcmAudioSource` interface (the JVM-testable
   capture seam) and `AudioRecordPcmSource`, the real `AudioRecord`-backed implementation (16 kHz /
   mono / PCM16, buffer size `max(minBufferSize, 3200)`, a `SupervisorJob() + Dispatchers.IO` read
   loop, `false`/no-throw return if `AudioRecord` fails to initialize or `RECORD_AUDIO` isn't
   granted). Verbatim from the brief.
4. **Step 5.4 — `main/.../FunASRService.kt`** (new): `SpeechRecognizerSession` (the ViewModel-facing
   contract) and `FunASRService`, the DashScope Fun-ASR realtime client over OkHttp's `WebSocket`.
   Verbatim from the brief.
5. **Step 5.5 — GREEN**: 8/8 in the new class on the first attempt — no production code needed
   adjustment after the brief's listings were typed in.
6. **Step 5.6 — Build + commit**: `assembleDebug`/`assembleRelease` green, single commit with the
   brief's exact message.

No `strings.xml` change was made or needed: the three English literals in
`FunASRService.kt` (`"Microphone unavailable"`, `"Connection failed"`, `"ASR task failed"`) are
values delivered through `onError` — same category as Task 3's wire-level `error.message` strings —
and are not resource-bound UI text. Any user-facing localization of ASR error state is the
downstream UI task's concern (Task 6+), same precedent as Task 3's report §"no strings.xml change".

---

## 2. Exact public API produced

### `com.smartview.glassai.services.PcmAudioSource` (`PcmAudioSource.kt`)

```kotlin
interface PcmAudioSource {
    fun start(onChunk: (ByteArray) -> Unit): Boolean
    fun stop()
}

class AudioRecordPcmSource(private val audioSource: Int) : PcmAudioSource {
    companion object {
        const val SAMPLE_RATE = 16_000
    }
    override fun start(onChunk: (ByteArray) -> Unit): Boolean   // false if AudioRecord can't init / RECORD_AUDIO denied
    override fun stop()
}
```

### `com.smartview.glassai.services.SpeechRecognizerSession` / `FunASRService` (`FunASRService.kt`)

```kotlin
interface SpeechRecognizerSession {
    var onStarted: (() -> Unit)?
    var onPartialResult: ((String) -> Unit)?
    var onFinalResult: ((String) -> Unit)?
    var onError: ((String) -> Unit)?
    var onFinished: (() -> Unit)?
    fun start()
    fun stop()
    fun switchAudioSource(source: BluetoothAudioManager.AudioSource)
}

class FunASRService(
    private val apiKey: String,
    private val endpoint: AlibabaEndpoint,
    private val httpClient: OkHttpClient,
    private val audioSourceFactory: (BluetoothAudioManager.AudioSource) -> PcmAudioSource,
    initialAudioSource: BluetoothAudioManager.AudioSource = BluetoothAudioManager.AudioSource.PHONE_MIC,
    private val endpointUrlOverride: String? = null,
) : SpeechRecognizerSession {
    val isListening: StateFlow<Boolean>
    @VisibleForTesting internal fun resolvedUrl(): String   // endpointUrlOverride ?: endpointUrl(endpoint)

    companion object {
        const val MODEL = "fun-asr-realtime"
        const val SAMPLE_RATE = 16_000
        fun endpointUrl(endpoint: AlibabaEndpoint): String
        fun recorderSourceFor(source: BluetoothAudioManager.AudioSource): Int   // MediaRecorder.AudioSource.MIC / VOICE_COMMUNICATION
        fun defaultAudioSourceFactory(): (BluetoothAudioManager.AudioSource) -> PcmAudioSource   // AudioRecordPcmSource in production
    }
}
```

Every member of the brief's **Interfaces** block is present with the stated signature, verbatim.
Consumers:

- **Task 6** wires this up as the production `SpeechRecognizerSession` for `OpenClawViewModel`,
  constructing `FunASRService(apiKey, endpoint, httpClient, FunASRService.defaultAudioSourceFactory())`
  with the app's `AlibabaEndpoint` and Alibaba API key from `APIKeyManager`, and drives it via
  `start()`/`stop()`/`switchAudioSource(...)` and the five callbacks (or a fake implementing
  `SpeechRecognizerSession` in `OpenClawViewModelTest`, per the brief's doc comment).

---

## 3. Protocol conformance (iOS `OpenClawASRService` → Android `FunASRService`)

| iOS message / field (research §4) | Where implemented | Test that pins it |
|---|---|---|
| `wss://dashscope.aliyuncs.com/api-ws/v1/inference` (Beijing) | `FunASRService.endpointUrl(BEIJING)` | `endpointsFollowTheAlibabaRegion` |
| `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference` (Singapore — Android-only region selection, spec §6 B1 / decision 3) | `FunASRService.endpointUrl(SINGAPORE)` | `endpointsFollowTheAlibabaRegion` |
| `resolvedUrl()` = override else region endpoint | `resolvedUrl()` | `resolvedUrlFollowsTheRegionUnlessOverridden` |
| `Authorization: Bearer <alibaba key>` | `start()` → `Request.Builder().addHeader("Authorization", "Bearer $apiKey")` | `fullSessionRunTaskAudioResultsFinishTask` (`request.getHeader("Authorization")`) |
| `run-task` header `{action, task_id (32 hex), streaming:"duplex"}` | `runTaskFrame()` header, `newTaskId()` (`UUID` with dashes stripped, lowercased) | `fullSessionRunTaskAudioResultsFinishTask` (32-char lowercase-hex assertion) |
| `run-task` payload `{task_group:"audio", task:"asr", function:"recognition", model:"fun-asr-realtime", parameters{format:"pcm", sample_rate:16000, vocabulary_id:"", disfluency_removal_enabled:false}, input:{}}` | `runTaskFrame()` payload | `fullSessionRunTaskAudioResultsFinishTask` (every field asserted) |
| Binary PCM16 LE mono 16 kHz frames | `startAudioLocked()` → `socket?.send(chunk.toByteString())` | `fullSessionRunTaskAudioResultsFinishTask` (320-byte binary frame observed on the mock gateway) |
| `task-started` → start mic (only after, not before) | `handleMessage` `"task-started"` branch → `startAudioLocked()` inside the lock | `fullSessionRunTaskAudioResultsFinishTask` (`audio.startCalls == 0` before, `== 1` and a PCM frame after) |
| `result-generated`: `payload.output.sentence.{text, end_time}`; `end_time > 0` = final, else partial | `handleMessage` `"result-generated"` branch | `fullSessionRunTaskAudioResultsFinishTask` (`end_time: null` → partial "你好"; `end_time: 1234` → final "你好世界") |
| `task-finished` (server- or client-driven) → stop mic, close socket | `handleMessage` `"task-finished"` branch: `stopAudioLocked()`, `onFinished`, `closeSocket()` | `taskFinishedFromTheServerStopsTheMicAndReportsFinished` (no prior `stop()`), `fullSessionRunTaskAudioResultsFinishTask` (after `stop()`) |
| `task-failed { header.error_message }` | `handleMessage` `"task-failed"` branch → `onError(message)` | `taskFailedReportsTheServerMessage` (`"bad model"` round-trips, mic never started) |
| `finish-task` sent on `stop()`, header `{action:"finish-task", task_id, streaming:"duplex"}`, payload `{input:{}}` | `stop()` → `finishTaskFrame()` | `fullSessionRunTaskAudioResultsFinishTask` (`finish` frame asserted field-for-field) |
| Transport failure surfaces `onError` unless a `stop()` was already in flight | `Listener.onFailure` — `wasStopping` gate | `transportFailureReportsAnErrorUnlessStopping` (error surfaces), `transportFailureAfterStopIsSilent` (silent) |
| `switchAudioSource` restarts capture from the new source while the task is live | `switchAudioSource()` | `switchingTheAudioSourceRestartsCaptureFromTheNewSource` (stop→start with the new `AudioSource`, `createdFor` order asserted) |
| `okio` 3.x binary-frame encoding | `chunk.toByteString()` (`import okio.ByteString.Companion.toByteString`) — **not** the ERROR-level-deprecated `ByteString.of(array, offset, count)` shim | grep-verified in `FunASRService.kt`; exercised by every test that reads `binaryFrames` |

Region-aware endpoint selection is the one deliberate Android-side difference from iOS (iOS is
Beijing-only); it's spec-mandated (§6 B1 / decision 3) and the brief documents that
`fun-asr-realtime` availability on the Singapore/intl endpoint is unverified without a DashScope
key — carried forward unchanged in the doc comment and not something this task could verify on
this host.

---

## 4. TDD evidence

**RED** — `./gradlew :app:testDebugUnitTest --tests "…FunASRServiceTest"` before
`PcmAudioSource.kt`/`FunASRService.kt` existed:

```
e: …/FunASRServiceTest.kt:121:29 Unresolved reference 'isListening'.
e: …/FunASRServiceTest.kt:129:17 Unresolved reference 'onStarted'.
e: …/FunASRServiceTest.kt:131:17 Unresolved reference 'start'.
… (24 unresolved-reference errors total)
BUILD FAILED in 3s
```

**GREEN** — same command after adding both production files: `BUILD SUCCESSFUL in 21s`,
`TEST-…FunASRServiceTest.xml: tests="8" skipped="0" failures="0" errors="0"`, empty
`<system-err>`/`<system-out>`. No production edit was needed beyond typing in the brief's listing —
all 8 tests passed on the first compile.

**Timing note (not a bug, verified empirically):** 3 of the 8 tests
(`taskFailedReportsTheServerMessage`, `taskFinishedFromTheServerStopsTheMicAndReportsFinished`,
`fullSessionRunTaskAudioResultsFinishTask`) report ~5.0–5.3 s each in the JUnit XML. I instrumented
`tearDown()` temporarily (`System.currentTimeMillis()` around `server.shutdown()`, reverted
immediately after — the committed test file is the brief's file byte-for-byte) and confirmed the
entire ~5 s is inside `MockWebServer.shutdown()` itself, not in `FunASRService`: these three tests
are exactly the ones where the *client* closes the WebSocket gracefully (via `closeSocket()` →
`socket.close(1000, "done")`) while the brief's test `WebSocketListener` never acks the close from
the server side (it only overrides `onOpen`/`onMessage`, not `onClosing`); `MockWebServer.shutdown()`
then blocks on an internal `awaitTermination(5, SECONDS)` for that still-"open" connection before
giving up. The two other tests that also end in a client-side close finish fast because they call
`server.shutdown()` themselves *before* the close reaches the (now-gone) server, so there's nothing
to await. This is inherent to the brief's test fixture (an unmodified `WebSocketListener` never
completes the close handshake) and outside `FunASRService`'s control; it costs ~15 s of wall time
across the suite but causes no flakiness or failures (verified with a second full run below).

---

## 5. Build results

- `./gradlew :app:testDebugUnitTest`: **BUILD SUCCESSFUL**. **121 tests, 0 failures, 0 errors**
  across all test-result XML files (113 baseline + 8 new `FunASRServiceTest`). Every file's
  `<system-err>`/`<system-out>` confirmed empty programmatically (not by eye).
- `./gradlew :app:assembleDebug :app:assembleRelease`: **BUILD SUCCESSFUL** (1 m 17 s;
  `compileReleaseKotlin`, `dexBuilderDebug`, `minifyReleaseWithR8` executed). No R8 missing-class/
  missing-rule output. Note: like Task 3's `OpenClawNodeService`, `packageRelease` stayed
  UP-TO-DATE / R8 will shrink `FunASRService`/`PcmAudioSource` out of the release dex since nothing
  references them yet — expected until Task 6 wires the service into `OpenClawViewModel`.
- **Warnings**: a forced non-incremental compile
  (`:app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun -Pkotlin.incremental=false`)
  emits **24 `w:` lines, none from `services/FunASRService.kt` or `services/PcmAudioSource.kt`** —
  all are the same pre-existing deprecations already recorded in earlier task reports
  (`BluetoothAudioManager`, `QuickVisionService`, `RTMPStreamingService`, `ModeSettingsScreen`,
  `QuickVisionScreen`, `RecordsScreen`, `Theme.kt`, `APIKeyManager`). This task's two production
  files compile warning-free.

---

## 6. Files changed

Created (3 files, 622 insertions, 0 deletions — commit `2e458c2`):

- `android/app/src/main/java/com/smartview/glassai/services/PcmAudioSource.kt`
- `android/app/src/main/java/com/smartview/glassai/services/FunASRService.kt`
- `android/app/src/test/java/com/smartview/glassai/services/FunASRServiceTest.kt`

No existing file was modified. No Gradle change was needed (Task 2 already declared OkHttp, Gson,
kotlinx-coroutines and MockWebServer). `android/local.properties` was never read or printed.

---

## 7. Deviations

**None from the brief.** Both production files and the test file are the brief's Step 5.1/5.3/5.4
listings verbatim — no adaptation was required to make the tests pass. The only experiment run was
the temporary `tearDown()` timing instrumentation described in §4, which was reverted before the
final green run and the commit, leaving the test file byte-identical to the brief.

**Test count**: the brief said "at least 8 tests pass"; the brief's test file contains exactly
**8** `@Test` methods, all green. Suite total after this task: **121** (113 baseline + 8).

---

## 8. Self-review

- **Protocol JSON matches iOS field-for-field**: confirmed against
  `docs/superpowers/research/ios-openclaw.md` §4 and the task instructions' inline summary — every
  `run-task`/`finish-task` header and payload field name, nesting, and literal value
  (`task_group`, `task`, `function`, `model`, `parameters.{format,sample_rate,vocabulary_id,
  disfluency_removal_enabled}`, `input:{}`) matches; event names (`task-started`,
  `result-generated`, `task-finished`, `task-failed`) and the `payload.output.sentence.{text,
  end_time}` / `header.error_message` field paths match. See table in §3.
- **Endpoint selection**: `FunASRService.endpointUrl(AlibabaEndpoint)` switches on the app's
  `AlibabaEndpoint` enum (Beijing/Singapore) rather than being hard-coded to Beijing as iOS is —
  this is the intentional Android-only spec deviation (§6 B1 / decision 3), documented in the
  class doc comment, not an accidental one.
- **`toByteString`**: `startAudioLocked()` sends `chunk.toByteString()` via
  `import okio.ByteString.Companion.toByteString` — grepped the file to confirm no
  `ByteString.of(...)` call exists anywhere in either new file.
- **Interface for audio**: `PcmAudioSource` is a plain interface with no Android framework types in
  its signature (`ByteArray`, `Boolean`, `Unit` only); `AudioRecordPcmSource` — the only type that
  touches `android.media.AudioRecord` — is never constructed by any JVM test (`FunASRServiceTest`
  only ever supplies `FakeAudio`/`audioSourceFactory`), so the whole `FunASRService` unit-test
  surface runs without Robolectric or an Android runtime.
- **No key logged**: audited all `Log.*` calls in both files (listed in the report body above) —
  none interpolate `apiKey`; the only string built from user-controlled data that could carry a key
  (`resolvedUrl()`) is never passed to `Log`.
- **Interfaces block verbatim**: `PcmAudioSource`, `AudioRecordPcmSource`, `SpeechRecognizerSession`,
  and `FunASRService`'s constructor/companion all match the brief's Interfaces block signature for
  signature (see §2).
- **Strings**: no `values/strings.xml` / `values-zh-rCN/strings.xml` change was made or needed — no
  user-facing string was added; the three `onError` literals are wire/internal messages in the same
  category iOS keeps in English and Task 3 established as not requiring localization at this layer.
- **Threading**: OkHttp `Listener` callbacks run on OkHttp's internal call threads; every mutable
  field (`webSocket`, `audio`, `currentAudioSource`, `taskId`, `taskStarted`, `stopping`) is read/
  written only inside `synchronized(lock)`; the audio capture loop itself is `AudioRecordPcmSource`'s
  own `Dispatchers.IO` coroutine (per the brief), and the scheduled post-`stop()` socket close uses
  `CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `delay(CLOSE_DELAY_MS)`. Callbacks to the
  consumer (`onStarted`/`onPartialResult`/`onFinalResult`/`onError`/`onFinished`) are plain
  function-reference callbacks invoked directly from the OkHttp/lock context, and `isListening` is
  a `StateFlow<Boolean>` — both match the brief's Interfaces block exactly; Task 6 is responsible
  for hopping to `Dispatchers.Main` before touching Compose state, same as the `OpenClawNodeService`
  precedent.
- **Git**: single commit on `android-v2`, brief's exact message, no attribution line, tree clean
  before and after (`git status --porcelain` empty post-commit).

---

## 9. Concerns

1. **MockWebServer's `shutdown()` tail latency (~5 s × 3 tests, ~15 s total).** Documented in §4 as
   a fixture artifact, not a `FunASRService` defect — but it does make this test class the slowest
   in the suite. Not fixed because the brief's test file must stay verbatim (Task 6 depends on this
   task's API, and the brief gave the test byte-for-byte); a future cleanup could have the shared
   `listener` object ack `onClosing` by calling `webSocket.close(code, reason)` itself, which would
   let those three tests finish in well under a second — worth a follow-up if suite runtime ever
   becomes a concern, but out of scope here.
2. **Singapore/`fun-asr-realtime` availability is still unverified** (brief's own caveat, carried
   into the class doc comment) — no DashScope key exists on this host to check it. Task 9 (or
   whichever task reaches the owner's phone) should confirm the intl endpoint actually serves this
   model; until then, a `task-failed` from Singapore surfaces through `onError` rather than being
   silently retried against Beijing, so the failure mode is at least visible rather than silent.
3. **`AudioRecordPcmSource` itself is not exercised by any test** (by design — it needs a real
   `AudioRecord`/`Context`, which is why the seam exists). It is reasoned about only in code review;
   if Task 6 or a later instrumented-test pass wants device coverage, an `androidTest` exercising
   `AudioRecordPcmSource.start()`/`stop()` on a real or emulator microphone would be the natural
   place, mirroring Task 3's note about `OpenClawNodeService.getInstance`/`nodeIdFor`.
4. **`defaultAudioSourceFactory()` is not called by any test** (only the brief's `FakeAudio`-backed
   factory is exercised) — it exists purely as the production wiring point for Task 6 and is
   reasoned about by inspection (it just closes over `recorderSourceFor` and constructs
   `AudioRecordPcmSource`).

---

## Fix round 1

Branch `android-v2`, base HEAD `2e458c2` (Task 5), fix commit **`1c84af2`**
`test(android): FunASRService tests ack the client close and wait on latches instead of sleeping`

Status: **DONE**. Both Important findings from the review are fixed. Test-only change — no
production code touched (confirmed by `git diff` reviewed before commit: only
`FunASRServiceTest.kt` in the diff, 13 insertions / 4 deletions). All 8 tests' intent preserved
(same assertions, same event sequencing, same public API exercised).

### What changed

File: `android/app/src/test/java/com/smartview/glassai/services/FunASRServiceTest.kt`
(package has no `openclaw` subpackage — the brief's path in the review was slightly off; verified
against the actual file location).

1. **Finding 1 — close handshake (line ~53).** Added `onClosing` to the shared server-side
   `listener` object so it acks the client's close frame instead of relying on the no-op default:
   ```kotlin
   override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
   ```
   This is the exact fix the reviewer specified, mirroring production `FunASRService.Listener.onClosing`
   (`FunASRService.kt:223-225`), which was already correct and untouched.

2. **Finding 2a — `transportFailureAfterStopIsSilent` (was line 118, now ~121-127).** Replaced
   `Thread.sleep(700)` with a `CountDownLatch(1)` awaited via `settled.await(700, TimeUnit.MILLISECONDS)`.
   Investigated whether a positive signal exists first: once `service.stop()` sets `stopping = true`,
   neither `Listener.onClosed` nor `Listener.onFailure` invokes any `SpeechRecognizerSession`
   callback on that path (`finish()` only clears internal state — `onFinished`/`onError` are invoked
   only from the `handleMessage` `task-finished`/`task-failed` branches, not from the raw transport
   callbacks). Per the review's own fallback guidance ("if no such signal exists ... keep a single
   bounded latch wait with a short timeout and document why in a comment"), kept a single bounded
   wait — now expressed as a latch await (never counted down) rather than an unconditional
   `Thread.sleep` — with a comment explaining why no deterministic signal is available. Bound kept
   at 700 ms (> `CLOSE_DELAY_MS` = 500 ms), same margin as before. Assertion unchanged
   (`errors.isEmpty()`).

3. **Finding 2b — `fullSessionRunTaskAudioResultsFinishTask` (was lines 194-195).** Replaced the
   `Thread.sleep(20)` poll loop (`while (finals.isEmpty() && ...) Thread.sleep(20)` bounded at 5 s)
   with a `CountDownLatch(1) finalReceived`, counted down inside `service.onFinalResult = { finals
   += it; finalReceived.countDown() }`, awaited via `assertTrue(finalReceived.await(5,
   TimeUnit.SECONDS))` — the same latch pattern the other six tests already use. Assertions on
   `partials`/`finals` unchanged; ordering guarantee (`partials` populated before `finals`) still
   holds because both events arrive on the same WebSocket connection and are processed in the order
   received.

### Before / after durations (8 tests, `TEST-com.smartview.glassai.services.FunASRServiceTest.xml`)

| Test | Before (report §4/§9) | After (measured) |
|---|---|---|
| `endpointsFollowTheAlibabaRegion` | ~0.00 s | 0.001–0.002 s |
| `resolvedUrlFollowsTheRegionUnlessOverridden` | ~0.00 s | 0.001–0.002 s |
| `transportFailureReportsAnErrorUnlessStopping` | not called out (fast) | 0.301–0.338 s |
| `transportFailureAfterStopIsSilent` | not called out (fast, ends via own `server.shutdown()` before the stall) | 0.715–0.720 s (bounded by the documented 700 ms latch wait) |
| `taskFinishedFromTheServerStopsTheMicAndReportsFinished` | ~5.0–5.3 s (close-handshake stall) | 0.319–0.324 s |
| `fullSessionRunTaskAudioResultsFinishTask` | ~5.0–5.3 s (close-handshake stall, plus was also polling) | 0.007 s |
| `taskFailedReportsTheServerMessage` | ~5.0–5.3 s (close-handshake stall) | 0.006–0.007 s |
| `switchingTheAudioSourceRestartsCaptureFromTheNewSource` | not called out (fast) | 0.018–0.019 s |
| **Suite total** | **~16–17 s** (three ~5 s tests + fast ones) | **1.38–1.41 s** |

Measured across two consecutive `--rerun` invocations of the class in isolation; durations stable
within a few ms/test run-to-run. The three tests the reviewer flagged as ~5 s each are now all well
under 1 s (two under 10 ms, one at ~0.32 s spent in the real `task-started`/`task-finished`
round trip over the loopback MockWebServer connection).

### Verification

- `./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.services.FunASRServiceTest'`
  (run twice, second with `--rerun`): **BUILD SUCCESSFUL**, `tests="8" skipped="0" failures="0"
  errors="0"`, empty `<system-out>`/`<system-err>`, suite `time="1.407"` then `time="1.382"`.
- `./gradlew :app:testDebugUnitTest` (full suite, `--rerun`): **BUILD SUCCESSFUL**. Aggregated all
  `TEST-*.xml` files programmatically: **121 tests, 0 failures, 0 errors**, no file has non-empty
  `<system-out>`/`<system-err>`.
- `git diff` reviewed before commit: only `FunASRServiceTest.kt` changed (13 insertions, 4
  deletions); no production file touched.
- `android/local.properties` was never read or printed during this fix round.
- Commit `1c84af2` on `android-v2`, message exactly
  `test(android): FunASRService tests ack the client close and wait on latches instead of sleeping`,
  no attribution line.

### Concerns

1. **`transportFailureAfterStopIsSilent` still spends a fixed 700 ms** — this is inherent, not a
   shortcut: there is no `SpeechRecognizerSession` callback reachable from a post-`stop()`
   `onClosed`/`onFailure`, so a positive-signal latch isn't possible without changing production
   code (out of scope for this fix round). The 700 ms is now a documented, bounded `CountDownLatch`
   wait instead of a bare `Thread.sleep`, per the review's explicit fallback instruction, but the
   wall-clock cost itself is unchanged for this one test.
2. **Path discrepancy**: the review's file reference used
   `.../services/openclaw/FunASRServiceTest.kt`; the actual (and only) file is
   `android/app/src/test/java/com/smartview/glassai/services/FunASRServiceTest.kt` (no `openclaw`
   package segment exists in this codebase). Verified there is only one `FunASRServiceTest.kt` in
   the tree before editing.
