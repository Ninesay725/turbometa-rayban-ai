# Task 10 — Manual emulator verification with MockDeviceKit

**Date:** 2026-09-10
**Branch / HEAD:** `android-v2` @ `e3cd466` — working tree clean before and after (no code changes, nothing to commit)
**Result:** 14 PASS / 0 FAIL / 0 FAIL-SDK-RACE / 1 N/A (of 15 checklist items). Steps 10.1, 10.3, 10.4 also PASS; Step 10.2 skipped (emulator from Task 9 still running).

---

## Environment

| Item | Value |
|---|---|
| AVD | `Pixel_5` (`emulator-5554`), `sdk_gphone64_x86_64`, x86_64 |
| Android | API 31 (Android 12), `ro.build.version.sdk=31` |
| App | `com.smartview.glassai`, versionName **1.5.0**, versionCode 4, minSdk 31, targetSdk 36 |
| APK | `android/app/build/outputs/apk/debug/app-universal-debug.apk` (rebuilt in Step 10.1, re-installed before the run) |
| adb root | available on this image (`restarting adbd as root`) — item 13 executed for real |
| Host | Windows 11, 31.2 GB RAM (4.9 GB free at the time of the incident below) |
| Screenshots | `.superpowers/sdd/2026-09-10-android-v2-phase-a-sdk-0.9/task-10-screens/` |

**Step 10.1 — clean build + `./gradlew test`:** `./gradlew clean :app:assembleDebug test` → `BUILD SUCCESSFUL in 8s` (69 tasks, 30 executed / 39 from cache).
`app/build/reports/tests/testDebugUnitTest/index.html` → **35 tests, 0 failures, 0 ignored**;
`app/build/reports/tests/testReleaseUnitTest/index.html` → **35 tests, 0 failures, 0 ignored**. `git status --short` empty. → **PASS**

**Step 10.2 — boot emulator:** skipped, `emulator-5554 device` already present from Task 9. → N/A (per brief)

**Step 10.3 — install + grants + mock photo:** `adb install -r …app-universal-debug.apk` → `Success`; `pm grant BLUETOOTH_CONNECT` / `CAMERA` silent; `adb shell screencap -p /sdcard/Download/mock_capture.png` → 481 143 bytes (1080×2340). Also pushed `app/src/androidTest/assets/plant.mp4` to `/sdcard/Download/` (used as the H.265 feed for items 8/12). → **PASS**

**Step 10.4 — launch + log tail:** `am start -n com.smartview.glassai/.MainActivity` → started; logcat filtered on the brief's tags throughout. → **PASS**

---

## Checklist (Step 10.5)

| # | Action | Observation | Evidence | Verdict |
|---|---|---|---|---|
| 1 | Force-stop, clear logcat, `am start .MainActivity` | App launched, `MainActivity` resumed, no dialog at all (perms pre-granted; no microphone prompt). No `AndroidRuntime` error, no `DAT SDK initialize failed`. | `WearablesViewModel: Starting monitoring` → `Registration state changed: UNAVAILABLE` → `Devices changed: 0 devices` → `Active device: none (null)`; `item-01-launch.png` | **PASS** |
| 2 | Inspect Home device card | Card reads "RayBan Meta Glasses / **Disconnected**" with a **Connect Glasses** button. | `item-02-home-disconnected.png` | **PASS** |
| 3 | Settings → scroll to bottom | **Developer** section present in the debug build with the **MockDeviceKit** row ("Simulate glasses without hardware (debug only)"); tapping it opens the screen with **Enable MockDeviceKit**. | `item-03-settings-developer.png`, `item-03b-mockdevicekit-screen.png` | **PASS** |
| 4 | Tap **Enable MockDeviceKit**, go Home | Header switches to "0 paired" + Disable/Pair buttons; Home card still **Disconnected** (no device yet). | `WearablesViewModel: Registration state changed: REGISTERED` / `Device registered` (19:52:15.328); `item-04-mock-enabled.png`, `item-04b-home-still-disconnected.png` | **PASS** |
| 5 | **Pair Ray-Ban Meta** → **Power** on → **Worn** on | Unfolded flips on automatically with `don`. Home card becomes **Connected** (green badge). | `MockDeviceKitViewModel: Paired mock Ray-Ban Meta …` → `powerOn on …` → `WearablesViewModel: Active device: Simulated Ray-Ban Meta #1 (RAYBAN_META)` → `don on …`; `item-05-mock-powered-worn.png`, `item-05b-home-connected.png` | **PASS** (note A) |
| 6 | Camera source **Back camera** + **Select captured image** → `Download/mock_capture.png` | Both accepted; no CAMERA dialog (pre-granted). Order matters: choosing a phone camera hides the "Select captured image" button (`usesPhoneCamera` branch), so the image must be set first. | `setCapturedImage on …`, `setCameraFeed(BACK) on …`; `item-06a-back-camera-selected.png`, `item-06b-captured-image-set.png`, `item-06c-back-camera-and-image.png` | **PASS** (note B) |
| 7 | Home → **Live Stream** wide card | Emulator virtual-scene living room visible in ~1 s, "● Live" badge. | `acquire(WearablesViewModel) owners=[WearablesViewModel] stoppingPrevious=false` → `session state: STARTING` → `STARTED` → `addCamera(WearablesViewModel) ok` → `Stream state: STARTING` → `STREAMING` → `Upgraded to Connected (streaming confirmed)`; `item-07-livestream-preview.png` | **PASS** |
| 8 | Cap-touch **Tap (pause/resume)** while Live Stream visible | Overlay **"Paused / Tap your glasses to resume"** with the last frame retained and badge "Connecting"; second tap resumes to "● Live". Required a workaround (defect **D1** below): a second `MainActivity` task (`am start … --activity-multiple-task`) so the MockDeviceKit card survives while Live Stream runs. | `captouch.tap on …` → `session state: PAUSED` → `Stream state: PAUSED`; then `captouch.tap` → `session state: STARTED` → `Stream state: STREAMING`; `item-08b-stream-before-tap.png`, `item-08c-paused-overlay.png`, `item-08d-resumed.png` | **PASS** (via workaround) |
| 9 | Leave Live Stream, then re-enter | Leaving tears the session down cleanly; re-entering builds a new one. First attempt ANR'd — see incident **I1**; after the emulator reboot this passed on every repetition (4×). | leave: `stopStream START` → `stopCamera(WearablesViewModel)` → `release(WearablesViewModel) owners=[]` → `stopSession` → `previous session reported STOPPED` → `Downgraded to Registered (stream stopped)`; re-enter: `acquire… STARTING → STARTED → addCamera… ok → STREAMING`; `item-09-livestream-recreated.png` | **PASS** (retest; see I1) |
| 10 | Home → **Quick Vision** (placeholder key `sk-placeholder-task10` saved in Settings) | Stream started, photo captured through the shared session, session stopped, then the HTTP analysis failed with HTTP 401 (expected for a fake key). The captured image is exactly `mock_capture.png`, rendered upright. | `Waiting for stream to stabilize…` → `Capturing photo…` → **`Photo captured: 1080x2340`** → `Stopping stream…` → `stopCamera/release/stopSession/previous session reported STOPPED` → `Analyzing image…` → `VisionAPIService: API Error: 401 … invalid_api_key`; `item-10a-apikey-configured.png`, `item-10b-quickvision-photo.png` | **PASS** (note C) |
| 11 | Settings → App Language → **中文** | Settings, bottom nav, Home card and the whole MockDeviceKit screen switch to Chinese (设置 / 已连接 / 快速识图 / 禁用 MockDeviceKit / 配对 Ray-Ban Meta / 电源 / 佩戴 / 展开 / 触控板手势（仅会话激活时）/ 轻触（暂停/恢复）/ 长按（停止）/ 相机源：无 / 视频文件必须为 H.265 (HEVC) 编码。/ 选择拍照图片 / 取消配对 / 已配对 0 台). One label stays English — defect **D2**. | `item-11a-settings-zh.png`, `item-11b-home-zh.png`, `item-11c-mockdevicekit-zh.png`, `item-11d-mock-card-zh.png` | **PASS** (minor defect D2) |
| 12 | Switch **Unfolded** off while Live Stream open; then leave/re-enter | Stream ends immediately, screen falls back to "Connecting to stream…" spinner; after re-donning, leaving and re-entering creates a fresh session. Same two-instance workaround as item 8. | `fold on …` → `session error: Session ended by device` (×2) → `session state: STOPPED` → `Stream state: STOPPED` → `Stream terminated, calling stopStream()` → `release(WearablesViewModel) owners=[]`; re-enter: `acquire… STARTING → STARTED → addCamera… ok → STREAMING`; `item-12-fold-stops-stream.png`, `item-12b-session-recreated.png` | **PASS** |
| 13 | `adb root` + `am start-foreground-service … CAPTURE_AND_ANALYZE` while Live Stream streams | Service refused the camera and finished; the Live Stream preview kept running uninterrupted. | `acquire(QuickVisionService) owners=[WearablesViewModel, QuickVisionService]` → **`addCamera(QuickVisionService) refused: camera held by WearablesViewModel`** → `stopCamera(QuickVisionService) ignored: held by WearablesViewModel` → `release(QuickVisionService) owners=[WearablesViewModel]` → `QuickVisionService: addCamera refused: CameraBusy(owner=WearablesViewModel)` → `Broadcast status: error` → `Finishing service` → `Broadcast status: finished`; `item-13-camerabusy-stream-continues.png` | **PASS** |
| 14 | `:app:assembleRelease` | No action required by the brief (green in Task 8); `testReleaseUnitTest` (which compiles `src/release/.../MockDeviceKitEntry.kt`) was green again in Step 10.1. | Step 10.1 output | **N/A** (no action per brief) |
| 15 | Uninstall → install **without** grants → launch → accept Bluetooth dialog → MockDeviceKit enable/pair/power/worn | `Wearables.initialize` runs in `Application.onCreate()` **before** any Bluetooth grant. After install, `BLUETOOTH_CONNECT: granted=false`; the in-app dialog ("Allow TurboMeta to find, connect to, and determine the relative position of nearby devices?") appeared at launch and was accepted → `granted=true`. No `POST_NOTIFICATIONS` dialog (API 31, correct). Discovery then worked with **no** re-initialize fallback needed; Home card reached **Connected** within ~4 s of donning. | `Registration state changed: REGISTERED` → `Devices changed: 1 devices` → `Paired mock Ray-Ban Meta e30bacbe-…` → `powerOn` → `Active device: Simulated Ray-Ban Meta #1 (RAYBAN_META)` → `don`; `item-15a-permission-dialog.png`, `item-15b-connected-after-late-grant.png` | **PASS** — no deviation, the Step 10.5-item-15 fallback was **not** required |

Crash buffer (`adb logcat -b crash`) is empty for the whole post-reboot run. No `FATAL`, no `CHECK_EQ`, no `SIGABRT`: **the DAT 0.9.0 native stop race known from Task 9 did not reproduce once in this session** (≈8 session start/stop cycles).

### Notes referenced above

- **Note A (item 5, cosmetic):** the brief expects `Active device: Mock glasses (RAYBAN_META)`. The SDK reports the mock device as `Simulated Ray-Ban Meta #N`; "Mock glasses" is only the app's own card label (`R.string.mock_device_name`). Home therefore shows "Simulated Ray-Ban Meta #1 / Connected". Cosmetic wording difference in the brief, not a defect.
- **Note B (item 6):** the camera feed and the captured image are mutually exclusive **in the UI only** (`usesPhoneCamera = info.cameraSource != null` hides the "Select captured image" button; the ViewModel comment says the phone camera and video-file feed are mutually exclusive). Setting the captured image first and the camera source second leaves both configured at the SDK level, which is how items 6/7/10 were run.
- **Note C (item 10):** the brief predicts the mock photo appears "rotated 90°"; it is rendered upright and correct (1080×2340 portrait). Better than expected — no action.

---

## Incident I1 — ANR while stopping the stream (first attempt at item 9)

Not counted as a checklist FAIL because it did not reproduce on a healthy host, but the controller should see it.

Sequence: item 7's stream was running from the **phone back camera** feed; pressing Back to leave the Live Stream screen produced no `stopCamera` log at all and the whole emulator became unresponsive (`dumpsys`, `screencap`, even `am force-stop` all timing out). Raw logcat:

```
09-10 20:03:03.064   469 11253 E Camera3-Stream: getBuffer: wait for output buffer return timed out after 8000ms (max_buffers 3)
09-10 20:03:21.874   541   541 W Looper  : Slow delivery took 52287ms main h=android.os.Handler c=com.android.internal.os.BinderCallsStats$1@f968b4e m=0
09-10 20:03:33.630   541   570 W Looper  : Slow dispatch took 11482ms android.fg h=android.os.Handler c=com.android.server.Watchdog$HandlerChecker@ca3f86 m=0
09-10 20:04:19.387   541 19913 E ActivityManager: ANR in com.smartview.glassai (com.smartview.glassai/.MainActivity)
09-10 20:04:19.387   541 19913 E ActivityManager: Reason: Input dispatching timed out (f8ee1fe com.smartview.glassai/com.smartview.glassai.MainActivity (server) is not responding. Waited 5029ms for KeyEvent(... keyCode=4 ...))
09-10 20:04:19.387   541 19913 E ActivityManager:   57% 10261/com.smartview.glassai: 0% user + 57% kernel / faults: 10817 minor 18342 major
```

Diagnosis: the host was memory-starved (4.9 GB free of 31.2 GB, Gradle daemon holding 1.4 GB; **18 342 major faults** = the emulator was being paged to disk), and `system_server` itself was 52 s behind on its main looper — i.e. the whole VM was thrashing, not just the app. Recovery: `./gradlew --stop`, then `adb reboot` (`adb emu kill` was not permitted in this session; a guest reboot was enough). After the reboot item 9 passed cleanly and was repeated three more times without issue.

Residual risk worth flagging: every `stopStream` / `stopCamera` / `release` / `stopSession` line is logged on the main thread (tid == pid), so a slow SDK/camera teardown blocks input dispatch and turns into an ANR rather than a dropped frame. Consider moving the teardown off the main thread (or making `SimpleLiveStreamScreen`'s `onDispose` fire-and-forget on a background scope) if this shows up on real hardware.

---

## Defects found

**D1 — MockDeviceKit paired devices are lost on screen re-entry (Medium; debug screen, but it blocks the brief's own test flow)**

- Repro: Settings → Developer → MockDeviceKit → **Enable** → **Pair Ray-Ban Meta** (card appears, "1 paired") → Back → re-open MockDeviceKit.
- Observed: header still shows the enabled state (Disable/Pair buttons) but "**0 paired**" and no device card, while the mock device is still paired and active in the SDK. Proof: tapping **Pair** again logs `WearablesViewModel: Devices changed: 2 devices`.
- Impact: after leaving the screen once, the previously paired mock glasses can no longer be powered off, doffed, folded, cap-touched or unpaired — only `Disable MockDeviceKit` (which unpairs everything: `Devices changed: 0 devices`) clears them. This is what makes the brief's items 8 and 12 ("open it from Settings in a second pass") impossible as written; they were only completed by launching a **second `MainActivity` task** (`adb shell am start -n com.smartview.glassai/.MainActivity --activity-multiple-task`) so one task could hold the MockDeviceKit screen while the other ran Live Stream, switching between them via Recents.
- Cause: `MockDeviceKitViewModel` (`android/app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitViewModel.kt`) initialises `MockDeviceKitUiState(isEnabled = mockDeviceKit.isEnabled)` with `pairedDevices = emptyList()` and never re-reads the kit's existing paired devices; the VM is obtained with `viewModel()` inside the nav destination, so it dies with the back-stack entry.
- Suggested fix: seed `pairedDevices` from MockDeviceKit's paired-device list in `init`, or scope the ViewModel to the Activity / nav graph.

**D2 — MockDeviceKit card title ignores the in-app language switch (Low, debug-only)**

- Repro: set Settings → App Language → 中文, then pair a mock device.
- Observed: every other string on the screen is Chinese, but the card title stays "**Mock glasses**" although `values-zh-rCN/strings.xml:445` has `模拟眼镜`. See `item-11d-mock-card-zh.png`.
- Cause: `MockDeviceKitViewModel.pairGlasses()` resolves the name with `application.getString(R.string.mock_device_name)`; the Application context configuration does not follow `AppCompatDelegate.setApplicationLocales` until the process restarts.
- Suggested fix: resolve the label in the composable with `stringResource(...)` (or use an activity/locale-aware context).

**D3 — transient `Active device: <raw id> (UNKNOWN)` during unpair/disable (Low, log-only)**

- Repro: MockDeviceKit → Disable (or Unpair) while a device is paired.
- Observed: `WearablesViewModel: Devices changed: 1 devices` → `Active device: fd6cd616aaf930a383250b9dc66bc378 (UNKNOWN)` → `Devices changed: 0 devices` → `Active device: none (null)`. The Home card did not visibly flicker, but `devicesMetadata` briefly resolves a device whose type/name is unknown (raw id shown). Worth a look in `WearablesDatAdapter.activeDeviceInfoFlow` if the card is ever observed showing a hex id.

**D4 — brief/implementation mismatch, not a code defect (informational)**

Item 6 of the brief asks for "Back camera → Select captured image"; the UI hides the captured-image button whenever a phone camera is selected. Suggest rewording the brief to "Select captured image → then Back camera".

---

## Deviations from the brief

1. Step 10.2 skipped (emulator already running from Task 9), per the task instructions.
2. Items 8 and 12 used the H.265 file feed (`/sdcard/Download/plant.mp4`, from `app/src/androidTest/assets/`) instead of the phone back camera, and a second `MainActivity` task to keep the MockDeviceKit card alive (D1). Both were deliberate: the video feed avoids the emulator Camera3 pipeline that wedged the VM in incident I1, and the second task is the only way to have the Live Stream screen visible while the cap-touch/fold is fired.
3. Item 10's Vision key is the placeholder `sk-placeholder-task10` (the app was uninstalled in item 15, so it is gone from the device).
4. The emulator was rebooted once (`adb reboot`) mid-run to recover from incident I1. It is left running (`emulator-5554 device`) with the debug APK installed, MockDeviceKit enabled and one mock device paired/powered/worn.
5. `adb emu kill` and one probe command were blocked by the sandbox classifier; `adb reboot` achieved the same recovery.

## State left behind

- `emulator-5554` running, unlocked, app installed (fresh install from item 15, `BLUETOOTH_CONNECT` granted, `CAMERA` **not** granted — it was never re-granted after the item-15 reinstall), MockDeviceKit enabled with one mock Ray-Ban Meta paired/powered/worn, app language back to English (prefs wiped by the reinstall), no Vision API key.
- `/sdcard/Download/mock_capture.png` and `/sdcard/Download/plant.mp4` remain on the device.
- `adb` is still running as root (`adb root` from item 13).
- Repository untouched: `git status --short` empty at `e3cd466` on `android-v2`.
