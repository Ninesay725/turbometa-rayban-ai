# Task 7 Report: Permission gating — Bluetooth-only SDK gate, lazy RECORD_AUDIO, POST_NOTIFICATIONS

## Status: DONE

## What was implemented

### Step 7.1 — `MainActivity.kt` rewritten (whole file)
- `PERMISSIONS` is now `[BLUETOOTH, BLUETOOTH_CONNECT, INTERNET]` — `RECORD_AUDIO` removed as a gate for `startMonitoring()`.
- Added `OPTIONAL_PERMISSIONS` (private companion val): `POST_NOTIFICATIONS` on API 33+ (`Build.VERSION.SDK_INT >= TIRAMISU`), empty array below.
- `checkAndRequestPermissions()` now: starts the SDK immediately if all required (`PERMISSIONS`) are already granted, and separately launches a request for whatever is missing from `PERMISSIONS + OPTIONAL_PERMISSIONS` — so a missing/denied `POST_NOTIFICATIONS` never blocks `initializeSDK()`.
- `androidPermissionsLauncher` callback now checks only `PERMISSIONS.all { ... }` (falling back to `isGranted` for permissions not present in the result map, e.g. when only `POST_NOTIFICATIONS` was requested and already-granted required perms aren't in the callback map) before calling `initializeSDK()`.
- Added private `isGranted(permission)` helper.
- File matches the brief's Step 7.1 code block verbatim.

### Step 7.2 — Strings
- `values/strings.xml`: `permission_all_required` changed from "Please grant all permissions (Bluetooth, Network, Microphone)" to "Please grant the Bluetooth and network permissions".
- `values-zh-rCN/strings.xml`: `permission_all_required` changed from "请授予所有权限（蓝牙、网络、录音）" to "请授予蓝牙和网络权限".
- `permission_microphone` already existed in both locale files (EN: "Microphone permission is required for AI conversation"; ZH: "AI 对话需要麦克风权限") — used as-is by the new `LiveAIScreen` mic-denied toast; no new string keys were needed for this task.

### Step 7.3 — `LiveAIScreen.kt`
- Imports: `Toast` and `LocalContext` were already imported in this file (Task 6 toast block); added the remaining five: `android.Manifest`, `android.content.pm.PackageManager`, `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`, `androidx.core.content.ContextCompat`.
- Replaced the `// Connect to AI and start stream when entering LiveAI` comment + `LaunchedEffect(Unit)` block with the brief's three-part replacement: mic-permission state (`micGranted`, seeded from `ContextCompat.checkSelfPermission`), a `rememberLauncherForActivityResult(RequestPermission())` launcher that shows a toast (`R.string.permission_microphone`) on denial, a `LaunchedEffect(Unit)` that starts the video stream and launches the mic permission request if not yet granted, and a `LaunchedEffect(micGranted)` that connects to AI only once the mic is available.
- No reformatting of surrounding code; the anchor text matched the current file exactly (Task 6's toast block for `wearablesErrorMessage` sits just above this block and was left untouched).

### Manifest / other services (verified, not touched per brief)
- `AndroidManifest.xml` already declares `BLUETOOTH`, `BLUETOOTH_CONNECT`, `INTERNET`, `RECORD_AUDIO`, and `POST_NOTIFICATIONS` — no manifest change was required for the new runtime-permission requests to function.
- `PorcupineWakeWordService.kt:131` still gates `startWakeWordDetection()` on its own `RECORD_AUDIO` check — untouched, per brief.
- `SettingsScreen.kt:111-130` already has its own microphone permission launcher for wake word — untouched, per brief.
- `QuickVisionService` foreground-service type — untouched, per brief (only ever started by the wake-word service, which already holds `RECORD_AUDIO`).

## Verification

```
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:assembleDebug
```
Result: `BUILD SUCCESSFUL in 5s` (37 actionable tasks: 10 executed, 27 up-to-date). Re-ran with `--rerun` and grepped output for `warning|w:` — no matches, so no new compiler warnings from the changed files.

```
./gradlew :app:testDebugUnitTest
```
Result: `BUILD SUCCESSFUL in 2s`. Aggregated the `app/build/test-results/testDebugUnitTest/*.xml` reports: `tests=35 skipped=0 failures=0 errors=0` — matches the expected unchanged baseline (35 tests, 0 failures).

## Files changed

- `android/app/src/main/java/com/smartview/glassai/MainActivity.kt` (rewritten)
- `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt` (imports + `LaunchedEffect` block)
- `android/app/src/main/res/values/strings.xml` (`permission_all_required` text)
- `android/app/src/main/res/values-zh-rCN/strings.xml` (`permission_all_required` text)

`git status --porcelain` before commit showed exactly these four files modified — nothing else touched.

## Deviations / adaptations from the brief

None. The brief's Step 7.1 code block was applied verbatim. For Step 7.3, the anchor text (`// Connect to AI and start stream when entering LiveAI` through the `LaunchedEffect(Unit)` closing brace) matched the current file exactly, so the replacement block was applied verbatim too; the only adaptation was import placement — `Toast` and `LocalContext` were already present from Task 6, so only the five missing imports were added (in reasonable alphabetical positions) rather than re-adding duplicates.

## Self-review

- Completeness: all three steps (MainActivity rewrite, strings in both locales, LiveAIScreen mic-on-demand) implemented as specified.
- Both locales: confirmed `permission_all_required` updated in both `values/strings.xml` and `values-zh-rCN/strings.xml`; `permission_microphone` (reused, not new) already existed in both.
- No changes outside task's files: verified via `git status --porcelain` (4 files) and `git diff` review of each.
- No `RECORD_AUDIO` re-introduced as a monitoring/SDK gate: `MainActivity.PERMISSIONS` contains only `BLUETOOTH`, `BLUETOOTH_CONNECT`, `INTERNET`; grepped `MainActivity.kt` for `RECORD_AUDIO` — only appears in a comment explaining its removal. Grepped `WearablesViewModel.kt` for `RECORD_AUDIO` — no references (startMonitoring is not gated on it there either, consistent with the task-4 report's WearablesViewModel API).
- Commit: single commit `65d6a0e` on branch `android-v2`, exact message from the brief, no body/attribution lines (verified with `git log -1 --format="%H%n%s%n%b"` — body is empty).

## Concerns

None. Build and tests are green, diff is scoped exactly to the four files the brief names, and the runtime-permission flow (Bluetooth-only gate for SDK monitoring, optional `POST_NOTIFICATIONS` request, lazy `RECORD_AUDIO` request in Live AI) matches DAT spec §5.3 as described in the task context.
