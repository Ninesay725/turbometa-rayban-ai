# Task 7 report — B2 stability (RTMP, WebSocket cleanup, capture budget, mic re-check, hygiene)

**Status:** DONE_WITH_CONCERNS (one deliberate in-scope addition beyond the brief: redacting the stream key from two `RTMPStreamingService` log lines — see Deviations)

**Branch:** `android-v2` · **Base:** `242e62c` · **Commit:** `0aaa883`

---

## 1. What was implemented, per brief step

### 7.1 Strings (both locales)
Appended the 8 `rtmp_*` strings to `app/src/main/res/values/strings.xml` and their translations to
`values-zh-rCN/strings.xml`, verbatim from the brief. No pre-existing `rtmp_*` key collided (the
only two prior `rtmp_` hits in each file are unrelated).

### 7.2 / 7.3 `RtmpUrlSplitter` (TDD)
Created `app/src/test/java/com/smartview/glassai/utils/RtmpUrlSplitterTest.kt` (3 tests) first,
watched it fail, then created `app/src/main/java/com/smartview/glassai/utils/RtmpUrlSplitter.kt`
exactly as specified. `split()` treats the last path segment as the key only when the path has ≥ 2
segments; `join()` trims and skips the separator for an empty key.

### 7.4 `APIKeyManager` — stream key, bitrate, one-time migration
- New keys `rtmp_stream_key`, `rtmp_bitrate`, `rtmp_split_migrated_v2`; `DEFAULT_RTMP_BITRATE = 2_000_000`.
- `init { migrateLegacyKey(); migrateRtmpUrl() }`.
- `migrateRtmpUrl()` + `@VisibleForTesting internal fun rerunRtmpMigrationForTests()`.
- Public API added: `getRtmpStreamKey()`, `saveRtmpStreamKey()`, `deleteRtmpStreamKey()`,
  `getRtmpBitrate()`, `saveRtmpBitrate()`.
- Added `import androidx.annotation.VisibleForTesting`.

### 7.5 `RTMPStreamingService`
1. `onDisconnectRtmp()` now guards on the `@Volatile isStreaming` **flag**, not on state — a user
   Stop and the `onConnectionFailedRtmp → Error → stopStreaming()` path both have `isStreaming == false`
   by the time `RtmpClient.disconnect()` invokes the callback synchronously, so only a genuine
   server-side drop of a live stream reaches `Disconnected`.
2. `initEncoder()` builds into a local `codec`, assigns `encoder` only after `configure()`/`start()`
   succeed, and `runCatching { codec?.release() }` + `encoder = null` on the failure path.
3. `startEncoderOutputProcessing()` records the first exception in `failure` (only while
   `isStreaming`), exits the loop, then logs, sets `StreamingState.Error("Encoder failed: …")` and
   calls `stopStreaming()` — no more full-speed spin on a codec stuck in the error state.
4. Added `stopLock` (with the lock-order comment) and the single-thread `disconnectExecutor`
   (`rtmp-disconnect`), plus `Executors` / `RejectedExecutionException` imports.
5. `stopStreaming()` is wrapped in `synchronized(stopLock)`, swaps `rtmpClient` to null before
   disconnecting (exactly one `disconnect()` per client), submits the disconnect to
   `disconnectExecutor` and falls back to an inline disconnect only on `RejectedExecutionException`
   (i.e. after `release()`). `release()` now also `disconnectExecutor.shutdown()`.
6. Deleted the unused `feedFrame(i420Data: ByteArray, …)` overload (Phase A review Minor #14); the
   `ByteBuffer` overload is untouched.

### 7.6 `RTMPStreamingViewModel` (full replacement)
Server URL + separate `streamKey` StateFlow, persisted bitrate seeded from
`APIKeyManager.DEFAULT_RTMP_BITRATE`, `updateRtmpUrl` / `updateStreamKey` / `updateBitrate` all
persisting, private `fullRtmpUrl()` = `RtmpUrlSplitter.join(server, key)` used only at
`rtmpService.startStreaming(...)`, `FIRST_FRAME_TIMEOUT_MS = 10_000L` armed on
`DatStreamState.STREAMING` and cancelled on the first decoded frame or on service `Streaming`,
`DEFAULT_RTMP_URL = "rtmp://localhost/live"` (key-free), localized `Disconnected` /
first-frame-timeout / connect-failed messages, `firstFrameJob` added to `cancelCameraJobs()`,
`WearablesViewModel.videoQualityFromSetting(...)` reused instead of the inlined `when`, and the
`Disconnected` branch double-checks `_uiState.value == UIState.Streaming`.

### 7.7 `RTMPStreamingScreen`
`streamKey` collected; `RTMPSettingsDialog` replaced with the server-URL + password-masked stream-key
+ bitrate-radio version driven by a single `onSave(url, key, bitrate)`; all dialog labels localized;
error card now uses `stringResource(R.string.error)` and `stringResource(R.string.close)`; the dead
`val cameraState by viewModel.cameraState.collectAsState()` line removed (ledger T5); added
`import androidx.compose.ui.text.input.PasswordVisualTransformation`. The URL line at the bottom
renders `rtmpUrl`, which is now the **server URL only** — the key is never rendered anywhere.

### 7.8 Shared `OkHttpClient` + weak-ref listeners + disconnect cleanup
`OmniRealtimeService` and `GeminiLiveService` both:
- replaced their per-instance `OkHttpClient.Builder()` with `private val client: OkHttpClient get() = HttpClients.websocket`
  (Task 6's process-wide client, which already carries `readTimeout(0)` + `pingInterval(30 s)`),
- replaced the anonymous `WebSocketListener` with a `private class SocketListener` holding a
  `java.lang.ref.WeakReference` to the service,
- `handleMessage` / `handleServerEvent` widened from `private` to `internal`,
- `disconnect()` now swaps `webSocket` to null first, then `close(1000, …)` **and** `cancel()` (both
  in `runCatching`), clears `pendingImageFrame`, clears `audioQueue` under its monitor, cleans up
  Bluetooth audio, and cancels the scope,
- dropped the now-unused `java.util.concurrent.TimeUnit` import.

OpenClaw's `lanHttpClient()` (Task 3) was **not** touched — the brief's step 7.8 does not mention it,
and it keeps its `Proxy.NO_PROXY` / 10 s connect / 0 read configuration.

### 7.9 Aggregate capture budget (TDD)
Appended `captureGivesUpAfterTheTotalBudgetAndReleasesEverything` to `GlassesPhotoCapturerTest`,
watched it fail on `totalBudgetMs` / `Timeout`, then added `PhotoCaptureOutcome.Timeout`,
`DEFAULT_TOTAL_BUDGET_MS = 15_000L`, the `totalBudgetMs` constructor parameter, and wrapped
`borrowCameraAndCapture()` in `withTimeoutOrNull(totalBudgetMs)`. The `finally` in `capture()` still
runs `stopCamera` + `release`, which the test asserts. Consumers updated:
`QuickVisionService` → `failAndFinish("error")`; `SessionFrameProvider.snapshot()` →
`SnapshotResult.StreamFailed("Capture timed out")`.

### 7.10 `clearStopping(outgoing, fromJob)` + rewritten `awaitStarted` test
`clearStopping` takes an optional `fromJob` and only cancels `stoppingJob` when it is a *different*
job; `stopSession()` passes `coroutineContext[Job]` so the stopping job finishes on its own instead
of cancelling itself. `kotlin.coroutines.coroutineContext` resolved without a new import (the
`kotlinx.coroutines.Job` import was already present). The tautological
`awaitStartedResolvesTrueOnStartedAndFalseOnStopped` test was replaced with the brief's version,
which now exercises a *real* STOPPED transition on a fresh session rather than the `session == null`
early return.

### 7.11 `micGranted` re-check on `ON_RESUME`
`LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` re-reads `RECORD_AUDIO` in `LiveAIScreen`, with
`androidx.lifecycle.Lifecycle` and `androidx.lifecycle.compose.LifecycleEventEffect` imports
(`lifecycle-runtime-compose` was already a dependency).

### 7.12 `MainActivity` hygiene
`initializeSDK()` → `startWearablesMonitoring()` (definition + both call sites);
`sdkInitialized` → `monitoringStarted`; `Manifest.permission.INTERNET` removed from `PERMISSIONS`
(it remains declared in `AndroidManifest.xml`, where it belongs — it is a normal, install-time
permission and requesting it at runtime was a no-op).

---

## 2. Lock order (RTMPStreamingService)

**`stopLock` → `encoderLock`, never the reverse.** Recorded verbatim in a comment above `stopLock`:

- `stopStreaming()` is the only place that holds both: it takes `stopLock` first, then enters the
  `encoderLock.withLock { … }` teardown section.
- Nothing that runs *under* `encoderLock` ever takes `stopLock`: `feedFrame()` never calls
  `stopStreaming()`, and the output loop's failure branch calls `stopStreaming()` **after** leaving
  its `encoderLock.withLock` block (the `failure?.let { … }` sits outside the `while` loop).
- The blocking part of the disconnect is handed to `disconnectExecutor` rather than performed while
  either lock is held, so `stopStreaming()` never waits on socket I/O.
- Worst-case wait for `stopLock`'s holder to acquire `encoderLock` is one `dequeueOutputBuffer`
  timeout (10 ms), so no unbounded block on Main.

`encoderLock` remains `ReentrantLock(true)` (fair) from Phase A, unchanged.

---

## 3. Migration behavior (`rtmp_url` → server + encrypted key)

- **Idempotent:** gated on the boolean `rtmp_split_migrated_v2`. The flag is written in the *same*
  `SharedPreferences.Editor` batch as the split, so a successful run can never re-split. A run with
  no stored `rtmp_url` (fresh install) still sets the flag and touches nothing else.
- **Non-destructive:** it only splits when `rtmp_url` is non-blank **and** `rtmp_stream_key` is still
  blank, so a user who already has a key set keeps it.
- **Single-segment safe:** `rtmp://host/live` → server `rtmp://host/live`, key `""` (not written);
  the app then behaves exactly as before for that user.
- **Never logs the key:** the only log line is `"Migrated rtmp_url into server URL + stream key"`.
- **Failure-safe:** the whole body is in `try/catch`; a throw is logged (message only) and the flag
  stays unset so the next launch retries.
- **Test hook:** `rerunRtmpMigrationForTests()` removes the flag and re-runs, letting Task 9 exercise
  the 1.5.0 → 2.0.0 upgrade on a device without reinstalling. It is `internal` + `@VisibleForTesting`.

---

## 4. TDD evidence

| Step | RED | GREEN |
|---|---|---|
| 7.2/7.3 `RtmpUrlSplitterTest` | `BUILD FAILED` — `Unresolved reference 'RtmpUrlSplitter'` in `:app:compileDebugUnitTestKotlin` | `BUILD SUCCESSFUL`, `tests="3" failures="0"` |
| 7.9 capture budget | `BUILD FAILED` — `GlassesPhotoCapturerTest.kt:217:13 No parameter with name 'totalBudgetMs' found.` and `:223:42 Unresolved reference 'Timeout'.` | `BUILD SUCCESSFUL`, `GlassesPhotoCapturerTest tests="9" failures="0"` |

---

## 5. Build / test results

Final run of
`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin`:

- **BUILD SUCCESSFUL** (exit 0), all four tasks.
- **143 unit tests, 0 failures, 0 errors, 0 skipped** across 13 test classes
  (139 baseline + 3 `RtmpUrlSplitterTest` + 1 capturer budget test).
- Every `<system-out>` / `<system-err>` in the JUnit XML is empty.
- **Zero `e:` lines.** All `w:` lines are pre-existing deprecation warnings unrelated to this task
  (`QuickVisionService` Locale/override, `QuickVisionScreen` icons, `APIKeyManager`
  `EncryptedSharedPreferences`/`MasterKey`, `RTMPStreamingService` `COLOR_FormatYUV420Planar`).
  No new warning originates from any line this task added.

---

## 6. Files changed (18 files, +511 / −227)

Main:
- `android/app/src/main/java/com/smartview/glassai/MainActivity.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesFrameProvider.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt`
- `android/app/src/main/java/com/smartview/glassai/services/GeminiLiveService.kt`
- `android/app/src/main/java/com/smartview/glassai/services/OmniRealtimeService.kt`
- `android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt`
- `android/app/src/main/java/com/smartview/glassai/services/RTMPStreamingService.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt`
- `android/app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt`
- `android/app/src/main/java/com/smartview/glassai/utils/RtmpUrlSplitter.kt` *(new)*
- `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/res/values-zh-rCN/strings.xml`

Test:
- `android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt`
- `android/app/src/test/java/com/smartview/glassai/utils/RtmpUrlSplitterTest.kt` *(new)*

---

## 7. Deviations from the brief

1. **Stream key redacted from two `RTMPStreamingService` log lines (addition).** The brief moves the
   key into encrypted storage and states in the ViewModel that the full URL must never be logged,
   but `RTMPStreamingService.startStreaming()` logged `"Starting RTMP streaming to: $rtmpUrl"` and
   `onConnectionStartedRtmp()` logged `"RTMP connection started: $rtmpUrl"` — both with the key
   appended. Both now log `RtmpUrlSplitter.split(rtmpUrl).first` (server portion only), with a
   comment, plus a `RtmpUrlSplitter` import. Without this the encrypted-at-rest key would still
   leak to logcat on every broadcast, defeating the item. Behavior is otherwise unchanged.
2. **`MainActivity` comment kept at two lines.** The brief says to update the comment inside
   `startWearablesMonitoring()` to `// Start observing Wearables state once the Bluetooth runtime
   permissions are granted.` — that exact sentence was already the comment's second line. I kept the
   preceding line `// Wearables.initialize() already ran in TurboMetaApplication.onCreate().`
   because it is still true and is precisely what justifies the `initializeSDK` → `startWearablesMonitoring`
   rename. Flag it if you want the single-line form.
3. **`request.timeoutMs` not wired into the capturer.** My task context flagged that Task 4's router
   parses `timeoutMs` without honoring it, "if the brief says so". Step 7.9 only adds the `Timeout`
   branch to `SessionFrameProvider.snapshot()`; it does not pass a per-request budget into
   `GlassesPhotoCapturer`. Left as-is — see Concerns.
4. **`FunASRService` untouched.** My context said the brief "may" add a `stop()` re-entrancy guard /
   scope cancel as hygiene. It does not, so nothing was changed there.
5. **`OpenClaw lanHttpClient()` untouched** (step 7.8 does not list it).
6. **No `client.dispatcher.executorService.shutdown()` / `connectionPool.evictAll()`.** My context
   mentioned these; the brief deliberately does not use them, and they would be wrong now that the
   client is process-wide and shared with Fun-ASR. The brief's `socket.cancel()` achieves the
   per-session release. Followed the brief.

---

## 8. Concerns

1. **`timeoutMs` still ignored on the OpenClaw `camera.snap` path.** A caller asking for a 3 s
   snapshot can still wait up to the capturer's 15 s aggregate budget (the parameter *is* honored for
   `awaitLiveFrame` in the busy/long-lived-owner branches, just not for the full borrow-and-capture
   path). Worth a follow-up if Phase B's acceptance cares.
2. **Aggregate budget vs. per-step budgets.** `DEFAULT_TOTAL_BUDGET_MS = 15_000L` is shorter than
   `sessionTimeout (12 s) + streamTimeout (12 s) + fallback (2 s)`, which is the point — but it means
   a slow-but-eventually-successful capture that used to return `Captured` at ~20 s now returns
   `Timeout`. That is the intended trade-off (ledger T5), just noting the user-visible change.
3. **`onDisconnectRtmp` is now asynchronous relative to `stopStreaming()`.** Because the disconnect
   moved to the `rtmp-disconnect` thread, the callback fires *after* `stopStreaming()` has already
   set `Idle`/kept `Error`. That is exactly what the `isStreaming` guard is for, but it does mean the
   only path that can now produce `Disconnected` is a true mid-stream server drop — verify on device
   (Task 9) that a real drop still surfaces the card.
4. **`RTMPStreamingService.encoderInputBuffers`** is an unused field (pre-existing, not in the
   brief's list). Left alone.
5. **Unit coverage for the RTMP service itself is still zero.** The `onDisconnectRtmp` flag guard,
   the `stopLock` single-disconnect and the executor teardown are reviewed-by-construction only;
   they depend on `MediaCodec` / `RtmpClient`, so they are Task 9 device-test territory.
6. **Migration is exercised only by construction + the `rerunRtmpMigrationForTests()` hook.**
   `APIKeyManager` needs `EncryptedSharedPreferences` (Android), so there is no JVM unit test for
   `migrateRtmpUrl()`; `RtmpUrlSplitter` — the part that can be tested on the JVM — is covered by 3
   tests. Task 9 should call `rerunRtmpMigrationForTests()` on device.

---

## Fix round 1

**Base:** `0aaa883` · **Commit:** `77bb737`

### Finding fixed — full-speed output loop reachable on a synchronous connect failure

`RTMPStreamingService.kt`, `startStreaming()` (was `:199-205`):

1. **Reorder `isStreaming` around `connect()` + bail on a torn-down encoder/client
   (`RTMPStreamingService.kt:237, 241, 248-251`).** `isStreaming = true` now runs *before*
   `rtmpClient?.connect(rtmpUrl)`, not after. `RtmpClient.connect()` (rtmp 2.2.6) calls
   `onConnectionFailedRtmp(reason)` SYNCHRONOUSLY on a malformed URL; that handler sets `Error`
   and calls `stopStreaming()`, which — now observing `isStreaming == true` — clears it back to
   `false`, releases/nulls `encoder`, and nulls `rtmpClient`, all before `connect()` returns.
   Immediately after `connect()` returns, `if (!canStartOutputLoop(encoder, rtmpClient)) { isStreaming
   = false; return@withContext false }` catches exactly that case and skips
   `startEncoderOutputProcessing()` entirely — no thread starts spinning, and `_state` is left
   untouched so the `Error(reason)` that `onConnectionFailedRtmp` already published survives (the
   old code overwrote nothing here, but *did* start the loop, which is what spun the IO thread and
   left `isStreaming()` wedged `true`). Extracted the boolean into a pure companion function,
   `internal fun canStartOutputLoop(encoder: Any?, client: Any?): Boolean = encoder != null &&
   client != null` (`RTMPStreamingService.kt:51-63`), specifically so item 1 has JVM coverage
   despite the surrounding code needing `MediaCodec`/`RtmpClient` (Android runtime).
2. **Fence stale callbacks with a generation counter (`RTMPStreamingService.kt:110-115`,
   `178-230`).** Added `private val connectionGeneration = AtomicLong(0)`. Each `startStreaming()`
   call takes `val myGeneration = connectionGeneration.incrementAndGet()` before building the
   `ConnectCheckerRtmp`, and every one of its seven overrides now starts with `if (myGeneration !=
   connectionGeneration.get()) return`. A checker bound to a client that a later
   start/stop cycle has since replaced or retired sees the mismatch and no-ops instead of mutating
   `_state`/stats on behalf of a client nobody holds a reference to. Went with the generation
   counter over `if (client !== rtmpClient) return` because the checker is built as a constructor
   argument to the very `RtmpClient` it would need to compare against — no reference to that
   instance exists yet at the point the object expression is written, so the counter was the only
   one of the two options in the ruling that actually compiles without restructuring the
   constructor call.
3. **Output loop failure branch skips the error card after a concurrent Stop
   (`RTMPStreamingService.kt:294-302`).** `_state.value = Error(...)` is now inside `if
   (isStreaming) { ... }`; `stopStreaming()` still runs unconditionally right after, matching the
   existing flow. If a user Stop completed (flipping `isStreaming` to `false` and the state to
   `Idle`) between the output loop's `catch` block recording `failure` and this line running, the
   card is skipped and the second `stopStreaming()` call is a harmless no-op (encoder/client
   already null, state already not `Error` so it stays `Idle`).
4. **`APIKeyManager.kt` defensive accessors (`:279-310`).** `saveRtmpStreamKey`,
   `deleteRtmpStreamKey`, `getRtmpBitrate`, `saveRtmpBitrate` are now each wrapped in the same
   try/catch-and-log pattern `getRtmpStreamKey()` already used: reads fall back to
   `DEFAULT_RTMP_BITRATE`, writes log `e.message` and swallow, and no path logs the key value
   itself (only whether an operation failed).

### Test evidence

- Added `android/app/src/test/java/com/smartview/glassai/services/RTMPStreamingServiceTest.kt` (4
  tests) exercising `canStartOutputLoop` directly: both non-null → `true`; encoder null, client
  null, and both null → `false` in each case. `RTMPStreamingService` itself is not otherwise
  unit-testable (needs `MediaCodec`/`RtmpClient`, i.e. Android runtime) — items 2 and 3 above are
  therefore verified by inspection/reasoning (traced above), the same status the original report
  gave the rest of this file's concurrency logic.
- `APIKeyManager`'s new try/catch wrapping also has no JVM test, for the same reason
  `getRtmpStreamKey()` didn't: `EncryptedSharedPreferences` needs Android. Verified by inspection
  (mirrors the already-reviewed pattern byte-for-byte).

### Verification output

- `./gradlew :app:testDebugUnitTest`: **BUILD SUCCESSFUL**. 147 tests total (143 baseline + 4 new
  `RTMPStreamingServiceTest`), 0 failures, 0 errors, 0 skipped, across 14 test classes. Every
  `<system-out>`/`<system-err>` in the JUnit XML is empty (38-byte empty-tag pairs throughout).
- `./gradlew :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin`: **BUILD
  SUCCESSFUL**. Only the same pre-existing deprecation warnings as the base report (`COLOR_FormatYUV420Planar`,
  `EncryptedSharedPreferences`/`MasterKey`) — zero `e:` lines, no warning on any line this round
  touched.
- Commit `77bb737` on `android-v2`, 3 files changed (+117/−9): `RTMPStreamingService.kt`,
  `APIKeyManager.kt`, and the new `RTMPStreamingServiceTest.kt`.

### Concerns

1. Items 2 and 3 remain reviewed-by-construction only (same gap the base report flagged for the
   rest of `RTMPStreamingService`) — Task 9's device pass is the place to actually exercise a
   generation-stale callback and a Stop/codec-error race, neither of which is reachable from a JVM
   unit test.
2. The malformed-URL repro described in the finding (`rtmp://192.168.1.10` with an empty key) was
   traced by hand against the new code path, not run against a real `pedroSG94` `RtmpClient` —
   there is no device or emulator in this environment to confirm `onConnectionFailedRtmp` still
   fires synchronously exactly as documented for rtmp 2.2.6. Worth a quick on-device check in
   Task 9 given how central that assumption is to items 1 and the ruling's rationale.
3. Did not change anything outside the four ruling items — the pre-existing `encoderInputBuffers`
   unused field and the other base-report concerns are untouched, as instructed.

---

## Fix round 2

**Base:** `77bb737` · **Commit:** `c553809`

### Finding fixed — RTMP callbacks from a client retired by `stopStreaming()` were not fenced

`connectionGeneration` (`RTMPStreamingService.kt:118`, was `:115`) was bumped only in
`startStreaming()` (`:180`). `stopStreaming()` (`:491-551`, was `:488-548`) never touched it, so a
client retired purely by a Stop with no subsequent Start kept its generation valid forever. All
seven `ConnectCheckerRtmp` overrides fence on `myGeneration != connectionGeneration.get()`, which
correctly rejects a callback from a client superseded by a *later* `startStreaming()` — but did
nothing for one retired only by `stopStreaming()`. `onDisconnectRtmp` has its own `isStreaming`
guard and was already safe either way, but `onConnectionSuccessRtmp` (sets `Streaming`) and
`onConnectionFailedRtmp` / `onAuthErrorRtmp` (set `Error`) had no secondary guard: a callback from
the retired client's underlying connection thread, already in flight when `stopStreaming()` ran,
could still land afterward and mutate `_state` from `Idle` back to `Streaming` or `Error`.

**Fix** — `RTMPStreamingService.kt:512-520`: inside `stopStreaming()`'s `synchronized(stopLock)`
section, immediately before the client is captured into the local `client` val and `rtmpClient` is
set to `null` (i.e. before the disconnect is submitted to `disconnectExecutor`), added:

```kotlin
connectionGeneration.incrementAndGet()
```

This runs after the encoder teardown and before the client swap, matching the ruling ("inside
`stopLock`", "BEFORE `rtmpClient` is nulled/swapped", "before the async `disconnect()` is
submitted"). `startStreaming()`'s own `incrementAndGet()` at `:180` is untouched, so a subsequent
Start still gets its own fresh generation — a stop-then-start sequence produces two increments,
which the ruling calls out as harmless, and is: `startStreaming()` always reads whatever value
`incrementAndGet()` returns *at that call*, and the seven callback guards only ever compare against
that one captured `myGeneration`, never against a specific prior value.

Also updated the KDoc on `connectionGeneration` (`:110-118`) to state it advances on both start
(once per client created) and stop (once per client retired), and rewrote the inline comment inside
`stopStreaming()` to name the specific gap being closed (`onConnectionSuccessRtmp` /
`onConnectionFailedRtmp` / `onAuthErrorRtmp` had no guard against a post-retirement callback;
`onDisconnectRtmp`'s `isStreaming` guard already covered its case).

### Inspection rationale — trace: stop → late success callback → now ignored

1. `startStreaming()` runs, builds `RtmpClient` A with `myGeneration = G` (`connectionGeneration`
   now `G`), sets `isStreaming = true`, calls `A.connect(...)`.
2. `A`'s internal connection thread receives a server response and is about to invoke
   `onConnectionSuccessRtmp()` on client A's checker — this call is already in flight (queued on
   whatever thread `RtmpClient` uses for socket callbacks), racing the next step.
3. Concurrently, the user calls `stopStreaming()`. It takes `stopLock`, sets `isStreaming = false`,
   tears down the encoder, then (round-2 fix) executes `connectionGeneration.incrementAndGet()` —
   `connectionGeneration` is now `G+1` — then swaps `rtmpClient` to `null` and submits `A.disconnect()`
   to `disconnectExecutor`.
4. The in-flight `onConnectionSuccessRtmp()` from step 2 now runs. It checks
   `myGeneration (G) != connectionGeneration.get() (G+1)` → `true` → returns immediately. `_state` is
   never set to `Streaming`, and `startTime` is never touched. Before this fix, the check was
   `G != G` → `false`, so the guard did not fire and `_state` would have been clobbered back to
   `Streaming` (or, for `onConnectionFailedRtmp` / `onAuthErrorRtmp`, to `Error`) moments after the
   user had already stopped and the UI had settled on `Idle`.
5. `onDisconnectRtmp()` was never the problem case: it is additionally guarded by
   `if (isStreaming) _state.value = Disconnected`, and `isStreaming` was already `false` by the time
   any callback from the retiring client's disconnect could run — that guard predates this round and
   is unchanged.

No JVM-testable helper was extracted for the increment itself (`connectionGeneration.incrementAndGet()`
is a one-line, already-atomic operation with no branching to unit-test — pulling it into a named
function would only rename a call, not add coverage) — per the ruling's own fallback, this is
verified by inspection, the same status fix-round-1 gave items 2 and 3 of this file's concurrency
logic (both remain Task 9 device-test territory, since exercising a real in-flight `RtmpClient`
callback racing `stopStreaming()` needs `MediaCodec`/`RtmpClient`, i.e. an Android runtime).

### Verification output

- `./gradlew :app:testDebugUnitTest`: **BUILD SUCCESSFUL**. 147 tests, 0 failures, 0 errors, 0
  skipped, across the same 14 test classes as fix round 1 (per-class counts unchanged —
  `RTMPStreamingServiceTest` still 4, exercising only `canStartOutputLoop`, which this round did not
  touch). Every `<system-out>`/`<system-err>` in every `TEST-*.xml` under
  `app/build/test-results/testDebugUnitTest/` is an empty `<![CDATA[]]>` pair.
- `./gradlew :app:assembleDebug :app:assembleRelease`: **BUILD SUCCESSFUL**. Only the pre-existing
  `RTMPStreamingService.kt:285` `COLOR_FormatYUV420Planar` deprecation warning appears (in both the
  debug and release Kotlin compile steps); zero `e:` lines; no warning on any line this round
  touched.
- Commit `c553809` on `android-v2`, 1 file changed (+18/−5): `RTMPStreamingService.kt` only.

### Concerns

1. Same as fix-round-1 concern 1: this fix, like the generation counter itself, is verified by
   inspection/tracing rather than a JVM unit test, because reproducing it needs a real `RtmpClient`
   callback racing a real `stopStreaming()` call — Task 9's device pass is the place to confirm a
   late success/failure callback after Stop is actually swallowed on hardware.
2. Did not touch anything outside the single increment + the two comment updates the ruling called
   for — `onDisconnectRtmp`'s pre-existing `isStreaming` guard, the lock ordering, and every other
   concern from the base report and fix-round-1 are unchanged.
