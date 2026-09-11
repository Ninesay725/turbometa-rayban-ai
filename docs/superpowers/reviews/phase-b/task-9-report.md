# Task 9 report — Phase B emulator verification (unit suite, both builds, instrumented suite, manual checklist)

Branch: `android-v2` · Base commit: `eafb608` · Plan: `.superpowers/sdd/2026-09-10-android-v2-phase-b-openclaw/task-9-brief.md`

## Result in one line

**147 JVM unit tests pass on both variants, `assembleDebug` / `assembleRelease` / `assembleDebugAndroidTest`
are green, and the instrumented suite is 20/20 on three consecutive `am instrument` runs.** The manual
checklist is **20 PASS / 1 FAIL / 3 N/A** over its 24 items (2 of the N/A were deferred to the owner's phone
by the brief, the third needs a DashScope key). The one FAIL and two of the defects below are real product bugs, not harness problems:
**Snap & Send in the OpenClaw chat can never return a frame**, **RTMP can hang in "Connecting" forever after
a failed attempt**, and **stopping from that state produced an ANR**.

---

## 1. Environment

| Item | Value |
|---|---|
| Host | Windows 11 Pro for Workstations 10.0.26200, Git Bash |
| Primary AVD | `Pixel_5`, API **31**, x86_64 `google_apis`, `emulator-5554` (already running; reused) |
| Secondary AVD | `Medium_Phone_API_36.0`, API **36**, `emulator-5556` (booted for item 21, killed afterwards) |
| Node.js / npm | **v22.11.0** / 11.2.0 |
| App installed | `com.smartview.glassai` **versionName 2.0.0** (`versionCode 5`), `app-universal-debug.apk` |
| Test APK | `app-debug-androidTest.apk` (same Gradle build) |
| Gradle | run only from `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android` as `./gradlew …` |

`android/local.properties` was never read or printed. No system-level commands were run
(`net stop winnat` etc.). No production source was modified.

## 2. Step 9.1 — instrumented tests added

Created / modified (test sources only):

- `android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt`
  (+3 tests: frame provider, captouch PAUSED/resume, fold)
- `android/app/src/androidTest/java/com/smartview/glassai/glasses/SessionFrameProviderEncodeInstrumentedTest.kt` (new, 3 tests)
- `android/app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt` (new, 2 tests)
- `android/app/src/androidTest/java/com/smartview/glassai/services/AudioRecordPcmSourceInstrumentedTest.kt` (new, 2 tests)
- `android/app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt` (new, 4 tests)
- `android/tools/openclaw-stub-gateway/{package.json,stub.js,.gitignore}` (new, host-side stub gateway)

### Deviations from the brief's test code (test-only, all recorded here)

1. **`captouchTapPausesAndResumesTheStreamWithoutTeardown()` / `foldingTheGlassesStopsTheSessionFromTheDeviceSide()`
   needed an explicit `: Unit`.** As written (`fun … () = onMain { … }`) Kotlin infers the return type of the
   last expression (`withTimeout { … }` → `DeviceSessionState`), and JUnit rejects the class at load time:

   ```
   org.junit.runners.model.InvalidTestClassError: Invalid test class '…GlassesSessionManagerInstrumentedTest':
     1. Method captouchTapPausesAndResumesTheStreamWithoutTeardown() should be void
   ```

   This failed the **whole class** (run 1: `Tests run: 12, Failures: 1`, the other 11 tests never ran).

2. **`assertEquals(DeviceSessionState.STARTED, manager.sessionState.value)` after the captouch tap is wrong on
   DAT 0.9.0.** Run 2 produced `java.lang.AssertionError: expected:<STARTED> but was:<PAUSED>` at
   `GlassesSessionManagerInstrumentedTest.kt:297`, and logcat confirms the SDK propagates the captouch pause to
   the **session**, not only the stream:

   ```
   09-11 03:06:59.305 D GlassesSessionManager: session state: PAUSED
   09-11 03:06:59.372 D GlassesSessionManager: session state: IDLE      <- test tore down afterwards
   ```

   The assertion was replaced by `withTimeout { manager.sessionState.first { it == DeviceSessionState.PAUSED } }`
   plus the "not a teardown" assertions the test is actually about (`currentCameraOwner == OWNER`, `hasSession`),
   and a wait back to `STARTED` after the resume tap. A comment in the file records why.

   *Consequence worth noting for the product:* while the session is `PAUSED`, `GlassesSessionManager.addCamera()`
   returns `SessionNotStarted` and `SessionFrameProvider.liveFrame()` returns null (it requires `STARTED`), so a
   gateway `camera.snap` taken while the wearer has paused the stream answers `NO_FRAME`. That is consistent
   with the documented behaviour but was not previously written down.

3. **`device.fold()` does stop the session** on this SDK build — the brief's fallback to
   `captouch.tapAndHold()` was **not** needed; `foldingTheGlassesStopsTheSessionFromTheDeviceSide` passes as written.

No other changes: `SessionFrameProvider.Companion::encodeBitmap` resolved on the first try, `internal`
members (`APIKeyManager.rerunRtmpMigrationForTests`, `OpenClawDeviceIdentity.seedCopy`) are visible from
`androidTest` (AGP friend paths), and `okhttp.mockwebserver` was already on the `androidTestImplementation`
classpath.

## 3. Step 9.2 — unit suite and builds

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew :app:testReleaseUnitTest
```

| Task | Result |
|---|---|
| `clean` + `testDebugUnitTest` + `assembleDebug` + `assembleRelease` + `assembleDebugAndroidTest` | **BUILD SUCCESSFUL in 11s** (117 tasks) |
| `testReleaseUnitTest` | **BUILD SUCCESSFUL in 9s** |

```bash
grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | awk -F'"' '{s+=$2} END {print s}'
```

| Variant | Tests | Failures/errors |
|---|---|---|
| `testDebugUnitTest` | **147** (14 classes) | none |
| `testReleaseUnitTest` | **147** | none |

147 ≥ the brief's floor of 134. `:app:assembleDebugAndroidTest` compiling proves the four new instrumented
classes and the `SessionFrameProvider.Companion::encodeBitmap` reference.

## 4. Steps 9.3 / 9.4 — instrumented suite via `am instrument`

`emulator-5554` (`Pixel_5`, API 31) was already up and was reused; no boot was necessary.
Gradle's `connectedDebugAndroidTest` is still blocked on this host by the Windows `winnat`
reservation of TCP 9577–9676 (AGP's UTP listener is hardcoded to 9624–9633) — see the Phase A Task 9
report. The identical Gradle-built APKs were driven through the platform runner instead:

```bash
adb install -r -t app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.smartview.glassai android.permission.CAMERA
adb shell pm grant com.smartview.glassai android.permission.RECORD_AUDIO
adb shell am instrument -w -r -e package com.smartview.glassai \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner
```

### Runs

| Run | APK state | Result |
|---|---|---|
| 1 | brief's test code verbatim | `Tests run: 12, Failures: 1` — class-load failure (deviation 1) |
| 2 | `: Unit` fix applied | `Tests run: 20, Failures: 1` — `expected:<STARTED> but was:<PAUSED>` (deviation 2) |
| 3 | both fixes applied | **`OK (20 tests)`** — `Time: 22.925` |
| 4 | unchanged | **`OK (20 tests)`** — `Time: 17.991` |
| 5 | unchanged | **`OK (20 tests)`** — `Time: 22.359` |

Three consecutive green full-suite runs, as the brief requires. No `CHECK_EQ` / `VideoDecoder` native abort
occurred in any run (the 2 s `STREAM_SETTLE_MS` in the file still holds).

### Per-test results (run 5)

| Class | Test | Result |
|---|---|---|
| `GlassesSessionManagerInstrumentedTest` | `mockDeviceRegistersAndBecomesTheActiveDevice` | PASS |
| | `sharedSessionStreamsAndCapturesAPhoto` | PASS |
| | `capturerTakesThePhotoThroughTheSharedSession` | PASS |
| | `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable` | PASS |
| | `capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams` | PASS |
| | `displayIsNeverAttachedForANonDisplayCapableDevice` | PASS |
| | `openClawFrameProviderSnapsThroughTheSharedSession` *(new)* | PASS |
| | `captouchTapPausesAndResumesTheStreamWithoutTeardown` *(new)* | PASS |
| | `foldingTheGlassesStopsTheSessionFromTheDeviceSide` *(new)* | PASS |
| `SessionFrameProviderEncodeInstrumentedTest` | `widerThanMaxWidthIsDownscaledKeepingTheAspectRatio` | PASS |
| | `narrowerThanMaxWidthIsNotUpscaled` | PASS |
| | `qualityIsClampedInsteadOfThrowing` | PASS |
| `OpenClawNodeServiceInstrumentedTest` | `handshakeOverCleartextWsReachesConnectedAndDeliversChat` | PASS |
| | `nodeInvokeCameraSnapReturnsAJpegFromTheMockGlasses` | PASS |
| `APIKeyManagerInstrumentedTest` | `legacyRtmpUrlIsSplitIntoServerAndStreamKeyOnce` | PASS |
| | `rtmpUrlWithoutAKeySegmentMigratesToNoKey` | PASS |
| | `openClawTokenSchemeAndPortAreNormalizedInTheEncryptedStore` | PASS |
| | `deviceSeedPersistsAndReproducesTheIdentity` | PASS |
| `AudioRecordPcmSourceInstrumentedTest` | `phoneMicDeliversPcm16ChunksUntilStopped` | PASS |
| | `voiceCommunicationSourceStartsWithoutSco` | PASS |

**20/20.** No `CLEARTEXT communication … not permitted` — `android:networkSecurityConfig` is in the merged
manifest and the real `OpenClawNodeService` completes a `ws://127.0.0.1` handshake inside the app process.

## 5. Step 9.5 — stub gateway

`android/tools/openclaw-stub-gateway/` created exactly as the brief specifies (`package.json`, `stub.js`,
`.gitignore`). `npm install --no-audit --no-fund` → "added 1 package in 1s" (`ws@8`).

Because this session cannot type into a terminal, the stub's `a` / `s` keys were fed through a file:

```bash
: > control.txt
tail -f control.txt | node stub.js --port 18789 --token test --not-paired > stub.log 2>&1 &
echo a >> control.txt    # approve
echo s >> control.txt    # snap now
```

The emulator reached it at `10.0.2.2:18789` with no firewall rule, as the brief predicted.

## 6. Step 9.6 — manual checklist

Screenshots: `.superpowers/sdd/2026-09-10-android-v2-phase-b-openclaw/task-9-screens/`.

| # | Action | Observation | Evidence | Verdict |
|---|---|---|---|---|
| 1 | Home → OpenClaw card | Card is in row 2, WordLearn slot; title "OpenClaw", subtitle "OpenClaw"; tap opens the chat with no device/API-key dialog | `item-01-home-openclaw-card.png` | **PASS** |
| 2 | Chat while disconnected | Orange "Not connected" banner, grey (disabled) mic, greyed Snap & Send, Text reveals the input bar, gear opens settings, X returns Home | `item-02-chat-not-connected.png`, `item-02b-text-input-bar.png` | **PASS** |
| 3 | Settings defaults + unreachable host | Host `127.0.0.1`, Port `18789`, `ws://`, empty token. With `10.255.255.1` + token `test`: Connecting → "Reconnecting (attempt n)…" with **2/4/8/16/30 s** gaps; navigating chat → Home → chat during the 16 s backoff started **no** extra dial; after the 5th attempt red "Connection failed after 5 retries" | `item-03-*.png`; logcat below | **PASS** |
| 4 | Re-Connect from Error / force during backoff / Integrations row | A tap in the Error state starts a fresh sequence (`attempt 1/5`); a tap 1.6 s into a 4 s backoff dialled immediately; Settings → Integrations → OpenClaw shows the same text in the status colour | `item-04-settings-error-red.png`, `item-04-integrations-openclaw-row.png` | **PASS** |
| 5 | Blank host / `bad host` / `::1` | Blank host → **Connect button disabled**; `bad host` → red "Invalid gateway address" and logcat `invalid gateway address: 'bad host:18789'` with **no** "connecting to"; `::1` accepted and dialled | `item-05-*.png` | **PASS** |
| 6 | 中文 | Chat and settings render the zh strings (手机麦克风 / 眼镜麦克风 / 拍照发送 / 键盘; 状态 / 已连接 / 地址 / 端口 / 协议 / Gateway 令牌 / 断开连接 / 设备能力) | `item-06-chat-zh.png`, `item-06-settings-zh.png` | **PASS** (see defect D-8) |
| 7 | Pairing wait → approve → connected + node id | Stub logged `connect: client=openclaw-android/android v2.0.0 model=sdk_gphone64_x86_64 device=d1c9a546… commands=camera.snap,camera.list,device.status,device.info`; app showed yellow "Waiting for pairing" + the `openclaw devices approve` hint; after `a` + Connect the stub logged **exactly one** `client disconnected 1000 reconnect` then a fresh connect; Node ID row shows **`rayban-0633cae2`** | `item-07-*.png` | **PASS** |
| 8 | Chat echo + wrong token | `hello` → user bubble + streamed assistant bubble `echo: hello`; with token `nope` the stub logged `bad token -> closing 1008`, the app backed off 2/4/8/16/30 s and ended on red "Connection failed after 5 retries" | `item-08-chat-echo.png`, `item-08-wrong-token-max-retries.png` | **PASS** |
| 9 | **Snap & Send** with the mock feed on | Assistant bubble **"Cannot get glasses frame, please check connection"**; `W OpenClawViewModel: snap failed: SnapshotResult$NoFrame` | `item-09-snap-and-send.png` | **FAIL** — defect **D-4** |
| 10 | Node-mode snap, foreground vs background | App on Home: stub logged `<- snap-1789097567343 from rayban-0633cae2: 1x1 jpg, saved last-snap.jpg` (valid JPEG; 1×1 because the MockDeviceKit *captured image* was never set). App backgrounded: `error NOT_READY — Stream not initialized` | stub log | **PASS** |
| 11 | Snap while a feature streams | With **Live Stream** running the snap returned **480x640** in <1 s and logcat shows **no** `acquire(OpenClawSnap)` — the live `latestFrame` path, no capturer round-trip | stub log + logcat | **PASS** (Live AI itself N/A, see below) |
| 12 | Mic permission | With `RECORD_AUDIO` revoked and connected, the mic tap raises "Allow TurboMeta to record audio?"; after granting, with no Alibaba key: "Please configure Alibaba API Key in Settings first" | `item-12-*.png` | **PASS** |
| 13 | Kill & relaunch | New process auto-connected (token stored), stub logged a new `connect` with the **same** `device=d1c9a546…` (persisted seed); Integrations showed "Connected"; token field still populated | stub log, logcat pid 13015 → 15318 | **PASS** |
| 14 | About | **App Version 2.0.0**, **SDK Version Meta Wearables DAT 0.9.0** | `item-14-about-versions.png` | **PASS** |
| 15 | Single toast | The specific string could not be produced (see deviation): with no glasses the app gates Live Stream behind a "Device Required" dialog. Driving the real error path ("The Meta AI app is not installed") produced **exactly one** toast over a 10 s window, and navigating Home → Records during it produced **no** second toast | `toast` contact sheets | **PASS** (deviated) |
| 16 | Quick Vision inline specific error | Not reachable: Quick Vision is gated by "Device Required" with no glasses and by "API Key Required" with glasses, on an emulator with no DashScope key | `item-16-quickvision-error.png` | **N/A** |
| 17 | Mic re-check + OkHttp threads | Mic re-check: covered by item 12 (grant → the screen recovers without leaving). Thread count around 3 connect/disconnect cycles: **4 → 5 → 3** after 75 s idle (`OkHttp ConnectionPool` + `OkHttp TaskRunner`), i.e. no per-session growth. Live AI providers could not be connected (no keys) | `ps -T` counts | **PASS** (Live AI part N/A) |
| 18 | RTMP settings + error card | Dialog has separate **Server URL** and **Stream key** (masked `•••`) fields; the on-screen URL shows `rtmp://10.255.255.1/live` and **never** the key; Start → error card *"Error configure stream, failed to connect to /10.255.255.1 (port 1935) … after 5000ms"* within ~6 s, persisting until **Close** | `item-18-*.png` | **PASS** |
| 19 | RTMP user Stop on a live stream | A local `node-media-server` on the host accepted the publish (`[rtmp publish] … H264 480x640`), the app showed **LIVE / FPS 25.8 / 1193 kbps**; **Stop → Ready with no "Disconnected from server" card**; logcat shows only `Stopping RTMP streaming` → `RTMP streaming stopped` | `item-19-*.png` | **PASS** |
| 20 | Capture budget + `NO_FRAME` window | With the camera feed unset, an OpenClaw `camera.snap` gave up after **12.0 s** (`stream did not reach STREAMING within 12000ms` → `STREAM_FAILED`), not ~36 s. A second snap fired 1.3 s into an in-flight capture answered `NO_FRAME`, and a later one succeeded — the documented window | stub log + logcat | **PASS** (deviated: driven through the OpenClaw capturer, Quick Vision is key-gated) |
| 21 | Edge-to-edge on API 35+ | `Medium_Phone_API_36.0` (API 36) booted, debug APK installed: Home, Settings and the OpenClaw chat all render with content **below** the status bar and **above** the gesture bar; the chat input bar sits above the navigation bar and the banner below the status bar | `item-21-api36-*.png` | **PASS** |
| 22 | 1.5.0 → 2.0.0 RTMP URL split | `APIKeyManagerInstrumentedTest.legacyRtmpUrlIsSplitIntoServerAndStreamKeyOnce` and `…rtmpUrlWithoutAKeySegmentMigratesToNoKey` both PASS on the real `EncryptedSharedPreferences` | run 3–5 | **PASS** |
| 23 | Fun-ASR on Singapore | No DashScope key on this host | — | **N/A** (owner's phone) |
| 24 | Fun-ASR Beijing end to end | No DashScope key on this host | — | **N/A** (owner's phone) |

Totals over the 24 items: **20 PASS / 1 FAIL (item 9) / 0 FAIL-SDK-RACE / 3 N/A (items 16, 23, 24)**. Three
PASSes are marked *deviated* (11, 15, 20) and two carry a partial N/A inside them (the Live AI halves of 11
and 17) — each is explained in its row.

### Key logcat excerpts

Item 3 — backoff and the "no extra attempt while navigating" check (the navigation chat → Home → chat happened
between 03:15:11 and 03:15:18, inside the 16 s backoff; there is no extra `connecting to`):

```
03:14:15.454 D connecting to ws://10.255.255.1:18789
03:14:25.490 W reconnecting in 2000ms  (attempt 1/5)
03:14:37.507 W reconnecting in 4000ms  (attempt 2/5)
03:14:51.526 W reconnecting in 8000ms  (attempt 3/5)
03:15:09.534 W reconnecting in 16000ms (attempt 4/5)     <- navigated Home and back here
03:15:25.538 D connecting to ws://10.255.255.1:18789     <- next dial is the scheduled one
03:15:35.550 W reconnecting in 30000ms (attempt 5/5)
03:16:15.568 E giving up after 5 reconnect attempts
```

Item 15 — the toast fires once (frames 0.5 s apart; the toast is present in two consecutive frames as it
fades and never returns, including across a Home → Records navigation).

Item 19 — user Stop:

```
03:45:04.050 D RTMPStreamingVM: Stopping streaming
03:45:04.050 D RTMPStreamingService: Stopping RTMP streaming
03:45:04.067 D RTMPStreamingService: RTMP streaming stopped
```

(no `RTMP disconnected` / no error card — the `isStreaming` guard from Task 7 holds.)

### Stub gateway log for items 7–11 (abridged)

```
stub gateway listening on ws://0.0.0.0:18789 (answering NOT_PAIRED until you press a)
client connected from 127.0.0.1, token=test
connect: client=openclaw-android/android v2.0.0 model=sdk_gphone64_x86_64 device=d1c9a546… commands=camera.snap,camera.list,device.status,device.info
approved: the next connect gets ok:true
client disconnected 1000 reconnect          <- exactly one; the pairing-wait socket, not orphaned
client connected from 127.0.0.1, token=test
chat.send [turbometa-chat]: "hello"
<- inv-…228681 from rayban-0633cae2: 1x1 jpg, saved last-snap.jpg      (app on Home)
<- inv-…248692 from rayban-0633cae2: error NO_FRAME — No video frame available   (chat screen open, D-4)
<- snap-…567343 from rayban-0633cae2: 1x1 jpg, saved last-snap.jpg      (manual `s`, app on Home)
<- inv-…588836 from rayban-0633cae2: error NOT_READY — Stream not initialized    (app backgrounded)
<- snap-…700252 from rayban-0633cae2: 480x640 jpg, saved last-snap.jpg  (during Live Stream)
<- snap-…804332 from rayban-0633cae2: error STREAM_FAILED — Could not start camera stream (feed unset, 12 s)
client connected from 127.0.0.1, token=nope
bad token -> closing 1008
```

## 7. The five review-requested device checks

| # | Check | Result |
|---|---|---|
| 1 | **RTMP** malformed URL / no wedge / second Start / late callback / migration | **PASS with two defects.** `rtmp://192.168.1.10` + empty key: the client rejects it synchronously (`Endpoint malformed, should be: rtmp://ip:port/appname/streamname`), the error card appears and the service stops in 11 ms — **no spin** (app CPU while the card is up is the live camera preview, 48–56 %, and drops when the preview stops). A second Start after Stop works (it produced the full error path). Start → Stop after 2 s → **Ready, and 12 s later still no card**, i.e. the retired client's late `onConnectionFailed` did not resurrect one. `rerunRtmpMigrationForTests()` is covered by two instrumented tests (it is a no-op once a key is stored — that is asserted). **But** see defects **D-6** and **D-7**. |
| 2 | **OpenClaw** first `device.status` / snap during a claim / unreachable host / `wss://` / node id | **PASS.** `device.status` + `camera.list` on the first frames after a process start answered after **1671 ms / 1676 ms** — the `awaitActiveDevice` ≤1.5 s wait, visibly used; with the mock device paired the same pair answered in **13 ms / 14 ms** with `deviceConnected:true` and `cameras:[{"id":"rayban-main",…}]`. `camera.snap` while another owner holds a claim returns `NO_FRAME` after the wait (documented). Unreachable host → `Reconnecting (attempt n)…` then the max-retries error. `wss://` is selectable. Node ID reads **`rayban-0633cae2`** (`rayban-` + 8 hex) — note it renders `-` until the socket is Connected, by design (`if (isConnected) service.nodeId else "-"`). |
| 3 | **Fun-ASR** mic permission / chip persistence / SCO notice | **PARTIAL / N/A.** The mic button requests `RECORD_AUDIO` when connected and stays disabled when not; with the permission granted and no key the localized "configure Alibaba API Key" bubble appears. The Phone/Glasses chip persists across taps and utterances as an intent (Phone mic stayed selected). The **SCO-timeout notice is N/A**: MockDeviceKit exposes no HFP audio, so the "Glasses mic" chip stays disabled and `startSco()` is never reachable. End-to-end recognition needs the DashScope key (items 23/24). |
| 4 | **`WearablesErrorToast` once per error** | **PASS.** One toast per error, and navigating Home → Records while it was showing produced no second toast. |
| 5 | **`awaitActiveDevice` startup path** | **PARTIAL.** MockDeviceKit pairing does **not** survive a process restart (it is process-scoped: after `force-stop` the debug screen comes back showing "Enable MockDeviceKit"), so "fresh launch with the mock already paired" cannot be reproduced on the emulator. What was proved instead is both halves separately: the wait is really taken on the first command of a new process (1671 ms), and when a device is present the same command answers `deviceConnected:true` in 13 ms. The combined case belongs on the owner's phone (Phase C). |

## 8. Defects found

All of these are **new findings from this task**; no production code was changed here (the brief forbids it).
Suggested fix commit prefix: `fix(android): phase-b verification —`.

### D-4 — Snap & Send in the OpenClaw chat can never return a frame *(major, the item 9 FAIL)*

**Repro:** glasses connected (MockDeviceKit paired, powered, worn, camera source set), gateway connected,
open the OpenClaw chat, tap **Snap & Send**.
**Observed:** after ~15 s the assistant bubble "Cannot get glasses frame, please check connection";
`W OpenClawViewModel: snap failed: SnapshotResult$NoFrame`.
**Cause:** `OpenClawViewModel.enterScreen()` acquires the shared session as owner `OpenClawChat`
(spec §3 decision 1) but never streams, so `GlassesSessionManager.ownerCount == 1` while the chat is open.
`SessionFrameProvider.snapshot()` therefore takes direction 2 ("someone claims the session → wait for its
first frame, then `NO_FRAME`") instead of direction 3 (borrow the camera through `GlassesPhotoCapturer`).
The same cause makes **every gateway `camera.snap` answer `NO_FRAME` whenever the chat screen is in the
foreground** — verified: snaps succeeded on Home and returned `NO_FRAME` from 03:27:22 (the moment
`acquire(OpenClawChat)` was logged) onwards.
**Suggested direction:** the claim held by a screen that never publishes frames should not count as a
"streaming owner". Either exclude `OpenClawChat` from the `ownerCount > 0` gate, or have `snapAndSend()` /
the provider fall through to the capturer when the only owner is the caller itself.

### D-6 — RTMP can hang in "Connecting" forever after a previous failed attempt *(major)*

**Repro:** RTMP screen → Start to an unreachable endpoint → error card → Close → set another URL → Start.
**Observed:** `RTMPStreamingVM: Camera streaming, waiting for first frame...` and then nothing; the UI stayed
in **Connecting** for >2 minutes with the Stop button as the only escape. `RTMPStreamingService` logged
nothing at all for that attempt (the encoder was never started).
**Cause:** `armFirstFrameTimeout()` only fails the attempt `if (videoWidth == 0 && _uiState.value == Connecting)`,
and `videoWidth` is never reset between attempts — after a first attempt that did receive frames it stays 480,
so the 10 s budget is a no-op on every later attempt on the same screen.
**Suggested direction:** reset `videoWidth`/`videoHeight` in `startStreaming()` (or track "frames seen in this
attempt" separately).

### D-7 — ANR when stopping from that stuck state *(major, dependent on D-6)*

**Repro:** reach the D-6 state, tap **Stop**.
**Observed:** "TurboMeta isn't responding".
```
E ActivityManager: ANR in com.smartview.glassai (com.smartview.glassai/.MainActivity)
E ActivityManager: Reason: Input dispatching timed out (… is not responding. Waited 5066ms for MotionEvent
                   … pointers=[0: (540.0, 1911.0)])            <- the Stop button
```
The UI recovered to Ready afterwards (so the ANR dump captured an already-idle main thread and carries no
useful frame). The same Stop from a healthy Connecting state (item 19 / the late-callback check) does **not**
ANR, so the blocking teardown appears specific to the state where the camera reports STREAMING but delivers
no frames.

### D-8 — the App Language choice is not applied after a restart *(medium, pre-existing, not Phase B code)*

**Repro:** Settings → App Language → 中文 (UI switches to Chinese immediately) → force-stop → relaunch.
**Observed:** the whole UI is English again, while the **App Language row still reads 中文** — the preference
is persisted but not applied at process start.

### D-1 — OpenClaw settings "Commands" row breaks its label *(minor, cosmetic)*

`InfoRow(label, value)` gives the label `Modifier.weight(1f)` but lets the value take its intrinsic width; the
long `camera.snap, camera.list, device.status, device.info` squeezes "Commands" to **one character per line**.
Visible in `item-07-node-id-connected.png`.

### D-2 — MockDeviceKit debug screen misreports Power/Worn/Unfolded after re-entry *(minor, debug-only)*

`MockDeviceKitViewModel` keeps an optimistic local `MockDeviceInfo` and never reads the device back, and the
ViewModel dies with the screen — re-entering shows every toggle **off** and "Camera source: None" even though
the device is powered, donned, unfolded and has a feed. The first tap then sends the *opposite* command
(logcat showed `fold` when the visible toggle was already off).

### D-3 — OpenClaw settings edits are only saved by "Connect to Gateway" *(minor, UX)*

`saveAndConnect()` is the only writer; the "Done" action is `onBackClick` and silently discards Host/Port/
Token edits. Cost me one wasted connect attempt during this checklist (typed `10.0.2.2`, tapped Done, came
back to `::1`).

## 9. Concerns / notes for the reviewer

- **MockDeviceKit's default captured image is 1×1.** Every `camera.snap` that goes through
  `GlassesPhotoCapturer` (i.e. the app on Home / not streaming) returns a valid but 1×1 JPEG unless
  "Select captured image" has been used. The plumbing (format, base64, round-trip, JPEG magic) is proven;
  a realistic frame size only comes from the live path (480×640 during Live Stream) or the instrumented
  tests, which set `plant.png` explicitly.
- **Live AI, Quick Vision and LeanEat are all gated by an "API Key Required" dialog** on this emulator, which
  is why items 11 (Live AI half), 16, 17 (Live AI half), 23 and 24 are N/A. Anything that needs a DashScope
  key must move to the owner's phone in Phase C.
- **Items 15 and 16 as written assume screens that this build gates behind dialogs** ("Device Required" before
  Live Stream / Quick Vision when no glasses are connected). The underlying behaviours were verified through
  the reachable equivalents and are marked "deviated" above.
- **`adb root` was used once** to read `/data/anr/` for D-7 and **`adb unroot` was issued immediately after**;
  the daemon is back to its normal state.
- A local `node-media-server` was installed **in the session scratchpad** (not in the repo) to give item 19 a
  reachable RTMP endpoint; it was stopped at the end. Docker is installed on this host but its daemon is not
  running, so the brief's `tiangolo/nginx-rtmp` suggestion was not usable.
- Left running at the end: **`emulator-5554`** (Pixel_5, API 31, app connected to the stub with token `test`,
  MockDeviceKit paired/powered/worn) and the **stub gateway on port 18789**. `emulator-5556` was killed.

## 10. Commands index

```bash
# build + unit
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew :app:testReleaseUnitTest
grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | awk -F'"' '{s+=$2} END {print s}'

# instrumented
adb install -r -t app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.smartview.glassai android.permission.CAMERA
adb shell pm grant com.smartview.glassai android.permission.RECORD_AUDIO
adb shell am instrument -w -r -e package com.smartview.glassai \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner

# stub gateway
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/tools/openclaw-stub-gateway
npm install --no-audit --no-fund
: > control.txt; tail -f control.txt | node stub.js --port 18789 --token test --not-paired > stub.log 2>&1 &

# API 36 edge-to-edge
"/c/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Medium_Phone_API_36.0 -port 5556 \
  -no-audio -no-boot-anim -no-snapshot-load &
adb -s emulator-5556 install -r -t app/build/outputs/apk/debug/app-universal-debug.apk
```
