# Phase B final-review fix wave — report

Branch: `android-v2` · Base: `f7b4953` · Commit: **`b09aade`**
`fix(android): phase-b final-review fixes — camera vs session claims, RTMP teardown, gateway hardening`

Source: `.superpowers/sdd/2026-09-10-android-v2-phase-b-openclaw/final-review-report.md` §Fix-Wave Prescription
steps 1-9, plus the Task 9 test-review item (deterministic subscription wait in the instrumented handshake test).

## Result in one line

**All nine prescription steps are implemented, 154 JVM tests pass on both variants (147 → 154, 7 new,
each shown RED then GREEN), all five instrumented classes are 21/21 green on the first `am instrument`
run, and the three device checks (D-6/D-7 RTMP, C1 snap-with-chat-claim, I2d fresh-grant) all PASS on
`emulator-5554`.**

---

## 1. Per-step changes and evidence

### Step 1 — `GlassesSessionManager`: camera intent + restartable monitoring

| What | Where |
|---|---|
| `private val cameraIntents = LinkedHashSet<String>()` | `GlassesSessionManager.kt:111` |
| `@Volatile private var cameraClaimCount = 0` | `:114` (declared `:113-114`) |
| `val hasCameraClaim get() = cameraClaimCount > 0 \|\| cameraOwner != null` | `:172` (KDoc `:163-171`) |
| `fun acquire(owner: String, forCamera: Boolean = false)` + intent bookkeeping | `:231`, `:233` |
| `release()` drops the intent | `:247` |
| `resetForTests()` clears intents and the count | `:418-419` |
| `job.invokeOnCompletion { if (deviceJob === job) deviceJob = null }` (I2a) | `:216` (comment `:210-215`) |

`acquire`'s log line now prints both sets, which is what the device evidence below quotes
(`acquire(OpenClawSnap, forCamera=true) owners=[OpenClawChat, OpenClawSnap] cameraIntents=[OpenClawSnap]`).

Volatile discipline is preserved: `hasCameraClaim` reads only `cameraClaimCount` (`@Volatile`) and
`cameraOwner` (`@Volatile`), so `SessionFrameProvider` may keep reading it off the main thread — the
old gate read `owners.size` (a plain `LinkedHashSet`) off Main, which this also fixes.

### Step 2 — borrowers declare intent; monitoring is re-armed and permission-gated

| What | Where |
|---|---|
| `sessionManager.acquire(OWNER, forCamera = true)` | `WearablesViewModel.kt:366` |
| `sessionManager.acquire(OWNER, forCamera = true)` | `RTMPStreamingViewModel.kt:245` |
| `sessionManager.acquire(owner, forCamera = true)` | `GlassesPhotoCapturer.kt:95` |
| `OpenClawViewModel.enterScreen()` unchanged (session-only) | `OpenClawViewModel.kt:189` |
| `WearablesViewModel.startMonitoring()` calls the manager's first (I2b) | `WearablesViewModel.kt:177-182` |
| `install()` starts monitoring only when `BLUETOOTH_CONNECT` is granted (I2c) | `OpenClawIntegration.kt:46-50`, `:60-62` |

`getInstance()` was deliberately **not** changed: it still starts monitoring when it creates the
singleton. `install()` simply does not touch the manager at all before the grant (the router's
`sessionManager` is a lazy provider), so no observer exists pre-grant. Evidence on device:
`I OpenClawIntegration: BLUETOOTH_CONNECT not granted yet; device monitoring starts after the grant`.

### Step 3 — `GlassesFrameProvider`: gate, single flight, one instance

| What | Where |
|---|---|
| Direction-2 gate is `manager.hasCameraClaim` | `GlassesFrameProvider.kt:184` |
| Companion `private val captureMutex = Mutex()` (I1) | `:107-114` |
| Permission check + `capture()` inside `captureMutex.withLock { … }`, with a re-check of `liveFrame()`/`hasCameraClaim` under the lock | `:191-220` |
| KDoc rewritten (camera claim vs session claim, C1 paragraph) | `:61-85` |
| `OpenClawIntegration.frameProvider(app)` lazy process singleton | `OpenClawIntegration.kt:32-42` |
| `OpenClawViewModel`'s public constructor uses it | `OpenClawViewModel.kt:66` |

### Step 4 — `RTMPStreamingViewModel`: C2

| What | Where |
|---|---|
| `@Volatile private var firstFrameSeen` (+ KDoc explaining D-6) | `:113-120` |
| Service `Error` branch: `UIState.Error` then `teardownCamera()` | `:148-155` |
| `startStreaming()` resets `videoWidth`/`videoHeight`/`frameTimestampBase`/`firstFrameSeen` | `:218-222` |
| `startCameraStream()` keeps the defensive `stopCamera` and warns if the camera was still held | `:231-239` |
| `armFirstFrameTimeout()` uses `!firstFrameSeen` | `:337` |
| `handleVideoFrame()` first-frame branch uses `firstFrameSeen` | `:378-379` |
| `failCamera()` goes through `teardownCamera()` | `:345-349` |
| `private fun teardownCamera()` (never writes `_uiState`) | `:432-447` |
| `stopStreaming()` = service stop + `teardownCamera()` + the existing Error-preserving state write | `:452-468` |
| `StreamingState.Disconnected` branch comment (Minor 5) | `:159-164` |

### Step 5 — `OpenClawNodeService`: I3, I5, Minor 8

| What | Where |
|---|---|
| `PING_INTERVAL_S = 20L` + KDoc | `:76-82` |
| `.pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)` | `:126` |
| `startConnection()` KDoc documents the `tickJob == null` invariant | `:231-238` |
| Identity resolution + signing wrapped in `runCatching` → `failWithTransport("IDENTITY: …")` | `:366-389` |
| Rejected `connect` (id == `pendingConnectId`, code ≠ `NOT_PAIRED`) → `failWithTransport` | `:434-444` |
| `private fun failWithTransport(detail)` (stops reconnect, cancels tick, closes 1000, `Error(Transport)`) | `:447-465` |
| `dispatchInvoke` rethrows `CancellationException` before the generic catch | `:556-560` |
| `@VisibleForTesting internal val chatSubscriptionCount` (Task 9 test-review item) | `:158-165` |

`NOT_PAIRED` handling is unchanged and now returns early, so `nonHelloResponsesDoNotChangeTheState`
(an `ok:false` for a *different* id) still passes untouched.

The `CancellationException` rethrow also fixes a latent bug: `withTimeoutOrNull`'s own
`TimeoutCancellationException` was being swallowed by the generic `catch (e: Exception)` and turned
into an `INTERNAL` error instead of a `TIMEOUT`.

### Step 6 — `BluetoothAudioManager`: I4

| What | Where |
|---|---|
| `private var scoRequested` + KDoc | `BluetoothAudioManager.kt:47-55` |
| `startBluetoothSco()` sets it before `mode = MODE_IN_COMMUNICATION` | `:212-215` |
| `stopBluetoothSco()` runs when `scoRequested \|\| isBluetoothScoOn` and clears both in `finally` | `:228-246` |

### Step 7 — hygiene

| What | Where |
|---|---|
| `save()` extracted; `saveAndConnect()` = `save()` + `connect(force = true)`; `saveAndLeave()` on both back affordances | `OpenClawSettingsScreen.kt:79-97`, `:106-110` |
| `InfoRow` value `Modifier.weight(2f)` + `TextAlign.End` (D-1) | `:221-232` |
| `R.string.version` deleted in both locales (450/450 parity re-verified, no en-only/zh-only keys) | `values/strings.xml`, `values-zh-rCN/strings.xml` |
| `rtmp_disconnected` XML comment + `StreamingState.Disconnected` KDoc | `strings.xml` (both), `RTMPStreamingService.kt:57-63` |
| Fun-ASR callbacks hopped onto `viewModelScope` | `OpenClawViewModel.kt:333-353` |

**Deviation (superset, deliberate):** the prescription names "the three recognizer callbacks
(`:329-341`)". `onFinished` sits in that range and mutates the same fields (`asr`, `_isListening`)
from the same OkHttp thread, so it was hopped too (`OpenClawViewModel.kt:353`). `stopListening()`
still calls `finishListening()` synchronously, so no behaviour depends on the hop's timing.

### Step 8 — instrumented additions

- `GlassesSessionManagerInstrumentedTest.openClawSnapWorksWhileTheChatHoldsASessionOnlyClaim`
  (`:284-320`): acquires `"OpenClawChat"` with no camera intent on Main, asserts `!hasCameraClaim`,
  runs a real `SessionFrameProvider.snapshot()` through `GlassesPhotoCapturer`, asserts `Ok` + JPEG
  magic, then `ownerCount == 1`, `hasSession`, `currentCameraOwner == null`, `!hasCameraClaim`.
  `CHAT_OWNER` is also released in `tearDown()`.
- Task 9 test-review item: `OpenClawNodeServiceInstrumentedTest.kt:181` replaces `Thread.sleep(200)`
  with `runBlocking { withTimeout(TIMEOUT_MS) { service.chatSubscriptionCount.first { it > 0 } } }`,
  backed by the new `@VisibleForTesting` accessor on the service.

### Step 9 — docs

- `android/README.md`: new item 8 in both the EN and ZH OpenClaw lists — the cleartext security note
  (why `cleartextTrafficPermitted="true"` is required for a user-typed LAN gateway, that every cloud
  endpoint is still `https`/`wss`, and "please do not 'fix' this").
- `android/CHANGELOG.md` (2.0.0 / 稳定性): ping keepalive + Transport error state, the C1 camera/session
  claim split + concurrent-snap serialisation, the RTMP teardown/no-ANR fix, the SCO mode restore and
  the settings save-on-back.

---

## 2. TDD — RED then GREEN for every new JVM test

Method: the API surface the new tests compile against (`acquire(owner, forCamera)`, `hasCameraClaim`,
the fake observer's `failOnCollect`, `ScriptedGateway.ConnectReply.REJECT`) was added first as a
**behaviour-preserving stub** (`hasCameraClaim` returned the old `ownerCount > 0` semantics, no mutex,
no `invokeOnCompletion`, no ping, no reject handling), so every RED is a real assertion failure rather
than a compile error.

### RED — `./gradlew :app:testDebugUnitTest` → `154 tests completed, 7 failed`

| Test | RED failure |
|---|---|
| `GlassesSessionManagerTest.cameraIntentFollowsAcquireAndRelease` (`:586`) | `java.lang.AssertionError` at `:586` (`assertFalse(hasCameraClaim)` after releasing the camera owner — the stub still counted the session-only owner) |
| `GlassesSessionManagerTest.sessionOnlyOwnerDoesNotCountAsCameraClaim` (`:610`) | `java.lang.AssertionError` at `:610` |
| `GlassesSessionManagerTest.startMonitoringRestartsAfterTheDeviceFlowCompletes` (`:632`) | `expected:<GlassesDeviceInfo(id=dev-1, …)> but was:<null>` |
| `SessionFrameProviderTest.sessionOnlyClaimFallsThroughToTheCapturer` (`:213`) | `expected Ok but was …SnapshotResult$NoFrame` |
| `SessionFrameProviderTest.concurrentSnapshotsCaptureOneAtATime` (`:235`) | `expected:<1> but was:<2>` (both captures ran at once) |
| `OpenClawNodeServiceTest.lanHttpClientBypassesProxiesAndNeverTimesOutReads` (`:473`) | `expected:<20000> but was:<0>` |
| `OpenClawNodeServiceTest.connectRejectedWithAnotherCodeBecomesATransportErrorAndStopsReconnecting` (`:491`) | `TimeoutCancellationException: Timed out waiting for 5000 ms` (the state never left `Connecting`) |

Two more tests changed without a RED, honestly recorded:

- `SessionFrameProviderTest.fallbackIsSkippedWhileAnotherOwnerHoldsAClaim` (`:194`) is the review's
  *rewrite* (`acquire("B", forCamera = true)`), not a new test; it passed under the stub and still
  passes — its value is that it now pins the camera-claim half of the gate rather than any claim.
- `OpenClawViewModelTest.releaseScoAfterAFailedArmStillCallsStopSco` (`:358`) is the characterization
  test the prescription asked for ("no JVM test possible for the manager; pin the VM side"). It was
  **green from the start** — the VM already calls `stopSco()` off its own `scoHeld` flag; the bug was
  entirely inside `BluetoothAudioManager`, which has no JVM seam. It is a regression guard, not a
  RED→GREEN proof. The manager half is covered only by inspection + the device checklist note.

### GREEN — after the production changes

`./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest` → **BUILD SUCCESSFUL**

| Variant | Tests | Failures | Errors | system-out/err |
|---|---|---|---|---|
| `testDebugUnitTest` | **154** | 0 | 0 | empty (scanned every XML) |
| `testReleaseUnitTest` | **154** | 0 | 0 | empty |

147 → 154 (+7 new tests; the prescription's floor is met on both variants).

---

## 3. Builds

`./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease
:app:assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 1m 10s** (122 tasks).

Warnings from compile: 6, all pre-existing and unchanged in kind —
`BluetoothAudioManager.kt:216/:237` (`AudioManager.startBluetoothSco()/stopBluetoothSco()` deprecated
since API 31; the same two call sites as before, only their line numbers moved),
`RTMPStreamingService.kt:291` (`COLOR_FormatYUV420Planar`), `QuickVisionScreen.kt:138/:502` ×3
(untouched file). **No new warning from any touched file.**

---

## 4. Instrumented suite — 21/21 on the first run

Environment: `emulator-5554` (`Pixel_5`, API 31), universal debug APK + androidTest APK reinstalled
from this build, `BLUETOOTH_CONNECT` / `CAMERA` / `RECORD_AUDIO` granted with `pm grant`,
`adb shell am instrument -w -r -e package com.smartview.glassai
com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner`.

Result: **`OK (21 tests)` — `Time: 17.898`**, one run, no re-run needed. No `CHECK_EQ` /
`VideoDecoder` native abort (logcat scanned for `CHECK_EQ|VideoDecoder|FATAL|beginning of crash`:
nothing).

| Class | Test | Result |
|---|---|---|
| `GlassesSessionManagerInstrumentedTest` | `mockDeviceRegistersAndBecomesTheActiveDevice` | PASS |
| | `sharedSessionStreamsAndCapturesAPhoto` | PASS |
| | `capturerTakesThePhotoThroughTheSharedSession` | PASS |
| | `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable` | PASS |
| | `capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams` | PASS |
| | `displayIsNeverAttachedForANonDisplayCapableDevice` | PASS |
| | `openClawFrameProviderSnapsThroughTheSharedSession` | PASS |
| | `captouchTapPausesAndResumesTheStreamWithoutTeardown` | PASS |
| | `foldingTheGlassesStopsTheSessionFromTheDeviceSide` | PASS |
| | **`openClawSnapWorksWhileTheChatHoldsASessionOnlyClaim`** *(new)* | **PASS** |
| `SessionFrameProviderEncodeInstrumentedTest` | `widerThanMaxWidthIsDownscaledKeepingTheAspectRatio` | PASS |
| | `narrowerThanMaxWidthIsNotUpscaled` | PASS |
| | `qualityIsClampedInsteadOfThrowing` | PASS |
| `OpenClawNodeServiceInstrumentedTest` | `handshakeOverCleartextWsReachesConnectedAndDeliversChat` *(sleep → subscriptionCount)* | PASS |
| | `nodeInvokeCameraSnapReturnsAJpegFromTheMockGlasses` | PASS |
| `AudioRecordPcmSourceInstrumentedTest` | `phoneMicDeliversPcm16ChunksUntilStopped` | PASS |
| | `voiceCommunicationSourceStartsWithoutSco` | PASS |
| `APIKeyManagerInstrumentedTest` | `legacyRtmpUrlIsSplitIntoServerAndStreamKeyOnce` | PASS |
| | `rtmpUrlWithoutAKeySegmentMigratesToNoKey` | PASS |
| | `openClawTokenSchemeAndPortAreNormalizedInTheEncryptedStore` | PASS |
| | `deviceSeedPersistsAndReproducesTheIdentity` | PASS |

---

## 5. Device checks

All three were driven on `emulator-5554` with the host stub gateway on port 18789
(`adb reverse tcp:18789 tcp:18789`; the reverse tunnel from the Task 9 session was gone and had to be
re-established). App process for the checks: **pid 20225**. Screenshots:
`.superpowers/sdd/2026-09-10-android-v2-phase-b-openclaw/final-fix-screens/`.

### (a) D-6 / D-7 — RTMP restart and Stop — **PASS**

Sequence: RTMP screen → Settings → server `rtmp://10.255.255.1/live` (unreachable) → Start → error
card → Close → Start again (×3) → Start → Stop inside the connect window.

| Moment | Evidence (logcat, device clock) |
|---|---|
| Attempt 1 Start | `04:56:55.772 RTMPStreamingVM: Video dimensions: 480x640` → `RTMPStreamingService: Starting RTMP streaming to: rtmp://10.255.255.1/live` |
| Attempt 1 failure | `04:57:00.917 E RTMPStreamingService: RTMP connection failed: Error configure stream, failed to connect to /10.255.255.1 (port 1935) … after 5000ms` |
| **C2 teardown on Error** | `04:57:00.918 stopCamera(RTMPStreamingViewModel)` → `04:57:00.927 release(RTMPStreamingViewModel) owners=[] cameraIntents=[]` → `stopSession` → `previous session reported STOPPED`, while the error card stayed on screen (`check-a-01-…png`). The camera no longer streams behind the card, and a gateway `camera.snap` 3 s later succeeded. |
| **Attempt 2 (the D-6 repro)** | Start `04:57:38.6` → `04:57:38.900 Camera streaming, waiting for first frame...` → `04:57:38.969 Video dimensions: 480x640` → `04:57:38.971 Starting RTMP streaming to: rtmp://…` — **`connectRtmp` reached 0.33 s after Start**, not a permanent "Connecting". Reproduced identically on attempts 3 and 4 (`04:58:13.058`, `04:59:26.756`). `check-a-02-…png` |
| **D-7 Stop** | Start `04:59:26.4`, Stop tapped 2 s later: `04:59:28.445 RTMPStreamingVM: Stopping streaming` → `RTMP streaming stopped` → `stopCamera` → `release … owners=[] cameraIntents=[]` → `stopSession` → `previous session reported STOPPED` at `04:59:28.462` — **17 ms of Main-thread work**, UI back to **Ready** (`check-a-03-…png`). |
| **ANR** | `adb logcat -b events \| grep am_anr` for `com.smartview.glassai`: the most recent is `09-11 03:40:47` (pid **13015**) — Task 9's original D-7, before this fix wave. Nothing for pid 20225 / after `04:50`. `/data/anr` newest file is `anr_2026-09-11-03-40-47-933`, i.e. the same Task 9 event. **No ANR in this session.** |

### (b) C1 on device — **PASS**

MockDeviceKit paired, powered, donned, unfolded, front-camera feed; chat screen open and
gateway-Connected.

- **Snap & Send** produced the picture bubble "Please look at this photo from my glasses" with the
  mock frame rendered, and the stub echoed `echo: … (I received a JPEG)` — **not**
  "Cannot get glasses frame" (`check-b-02-snap-and-send-returns-a-picture.png`). Logcat:
  `04:55:23.328 acquire(OpenClawSnap, forCamera=true) owners=[OpenClawChat, OpenClawSnap]
  cameraIntents=[OpenClawSnap]` → `addCamera(OpenClawSnap) ok` → `stopCamera` →
  `release(OpenClawSnap) owners=[OpenClawChat] cameraIntents=[]` (the chat kept its claim and the
  session).
- **Gateway `camera.snap` while the chat is foregrounded** (the stub fires one every 20 s):
  `<- inv-1789102484782 from rayban-0633cae2: 480x640 jpg, saved last-snap.jpg` and again at
  `inv-1789102504789`. Task 9 saw `error NO_FRAME — No video frame available` for exactly this case.
- Bonus evidence for the hygiene items in the same screenshot set: the **Commands** row now wraps as
  two right-aligned lines with the label intact (`check-b-01-settings-connected-commands-row.png`,
  D-1 fixed), and tapping **Done** with an edited port persisted `18789` with **no** connect attempt
  in logcat, confirmed by reopening the screen (D-3 fixed).

### (c) I2d fresh-grant — **PASS**

`adb shell pm revoke … BLUETOOTH_CONNECT` → `am force-stop` → launch.

- The in-app dialog appeared (`check-c-01-bluetooth-permission-dialog.png`) and, before the grant,
  logcat shows `I OpenClawIntegration: BLUETOOTH_CONNECT not granted yet; device monitoring starts
  after the grant` — the I2c gate working.
- Allow → Settings → MockDeviceKit → Enable → Pair → Power/Worn → front camera feed → Home: the
  device card reads **"Simulated Ray-Ban Meta #1 / Connected"** in the **same process** (pid 20225
  before and after), i.e. `WearablesViewModel.startMonitoring()` → `sessionManager.startMonitoring()`
  re-armed the observer without an app restart (`check-c-02-device-connected-after-grant-no-restart.png`).

---

## 6. Concerns / notes

1. **`OpenClawNodeServiceInstrumentedTest` leaks the user's OpenClaw settings on this emulator.** After
   the instrumented run the app's stored gateway port was a dead MockWebServer port (`54927`) and the
   token was `it-token`, so the app could not reach the stub until I retyped them. The test *does*
   save/restore `host/port/scheme/token` in `setUp`/`tearDown`, but it writes the app's real
   `EncryptedSharedPreferences` while the app process is alive, so the restore is not reliable. Worth
   giving that test its own store namespace (or a `@VisibleForTesting` in-memory store) in Phase C —
   it is a test-hygiene issue, not a product bug, and it is outside this prescription.
2. **D-2 (MockDeviceKit debug toggles misreport after re-entry) bit me during the checks** and behaves
   slightly worse than the Task 9 write-up says: re-entering shows every toggle off *and* the first tap
   on "Unfolded" sends `fold` (the device starts unfolded), which silently makes the glasses
   session-ineligible. Already triaged PHASE-C; the extra detail ("the first Unfolded tap folds") is
   worth carrying into that ticket.
3. **The `BluetoothAudioManager` half of I4 has no automated coverage** — `AudioManager` cannot be
   faked on the JVM and MockDeviceKit exposes no HFP audio, so the emulator cannot arm SCO at all
   (Task 9 already marked the SCO items N/A). The change is small and fail-safe (`finally` always
   clears both flags), but it is verified by inspection only; the owner's phone check is
   "select Glasses mic → leave the screen → media volume behaves normally".
4. **The exact D-7 precondition no longer exists.** The original ANR needed the stuck-"Connecting"
   state that D-6 produced; with the D-6 fix that state is unreachable, so the Stop was exercised from
   a healthy Connecting window instead (17 ms, no ANR). If a future SDK regression re-creates a
   same-session camera hand-off, Phase A recommendation 1 (SDK `stop()` on the manager's async stop
   path) is still the fallback — not needed now.
5. **Companion-level `captureMutex` serialises capture app-wide.** That is what the review asked for
   and it costs nothing today (only OpenClaw uses the provider), but a second feature that ever routes
   through `SessionFrameProvider` will queue behind an in-flight 12-15 s capture. Documented in the
   companion's KDoc.
6. **Not done because it is not in the prescription:** I6(b) (extract `RtmpConnectionState` behind
   seams so the generation fencing / `stopLock` / Error-preservation get JVM tests) and I6(d) (an
   `OpenClawViewModelTest` case wiring the *real* `SessionFrameProvider` over the fake manager). Both
   are listed in the review's Issues but neither appears in steps 1-9; the C1 seam is instead pinned by
   `sessionOnlyClaimFallsThroughToTheCapturer` (JVM) and
   `openClawSnapWorksWhileTheChatHoldsASessionOnlyClaim` (instrumented, real classes end to end).
7. **Minors deliberately left alone** (not in steps 1-9): Minor 1 (Disconnect does not stick), Minor 2
   (recovery from `Error(MaxRetries)`), Minor 6 (codec teardown on the caller's thread), Minor 9
   (debug main-thread guard, `DisplayAttacher` KDoc, `getInstance(context)`), Minor 10
   (`acquireAndStart`), Minor 12 (`resetForTests` / `deviceJob` — note `resetForTests()` *does* now
   clear the camera intents), Minor 13 (`EncryptedSharedPreferences` on first composition).

## 7. Left running

- `emulator-5554` (Pixel_5, API 31) — app installed (this build), MockDeviceKit enabled and paired
  (powered/worn/unfolded, front-camera feed), gateway Connected, RTMP server URL restored to
  `rtmp://10.0.2.2/live`, `adb reverse tcp:18789 tcp:18789` active.
- Host stub gateway on port **18789** (pid 14768), token `test`, auto-`camera.snap` every 20 s,
  logging to `android/tools/openclaw-stub-gateway/stub.log`.

## 8. Commands index

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:testDebugUnitTest                       # RED (7 failures) / GREEN
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
          :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest

adb install -r -t app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT   # + CAMERA, RECORD_AUDIO
adb shell am instrument -w -r -e package com.smartview.glassai \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner

adb reverse tcp:18789 tcp:18789          # the stub gateway is on the host
adb logcat -b events | grep am_anr       # ANR scan for the device checks
```
