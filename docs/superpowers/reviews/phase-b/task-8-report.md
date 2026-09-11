# Task 8 Report — B3: version 2.0.0, Settings About SDK row, single error toast, docs

Branch `android-v2`, base commit `c553809` (working tree clean at start). Result commit: `eafb608`.

## What was implemented, per step

**8.1 Version bump** — `android/app/build.gradle.kts`: `versionCode = 4` → `5`, `versionName = "1.5.0"` → `"2.0.0"`.

**8.2 Settings About rows** — `SettingsScreen.kt`: the old `version`/"1.5.0" item now reads
`stringResource(R.string.settings_version)` / `BuildConfig.VERSION_NAME`; added a second item
(`Icons.Default.Memory`, `R.string.settings_sdk_version`, `"Meta Wearables DAT ${BuildConfig.MWDAT_VERSION}"`)
with a `HorizontalDivider` between them. Both string keys already existed in `values/strings.xml` and
`values-zh-rCN/strings.xml` ("App Version"/"应用版本", "SDK Version"/"SDK 版本") — no string files touched.
`BuildConfig` was already imported; `Icons.Default.Memory` resolves via the existing
`material-icons-extended` dependency.

**8.3 The single toast** — Created
`app/src/main/java/com/smartview/glassai/ui/components/WearablesErrorToast.kt` exactly as specified: a
`@Composable fun WearablesErrorToast(wearablesViewModel: WearablesViewModel)` that collects
`wearablesViewModel.errorEvents` (the one-shot `SharedFlow` that has existed since Task 1) in a
`LaunchedEffect(wearablesViewModel)` and shows a `Toast`. Wired into `Navigation.kt`: import added, and
`WearablesErrorToast(wearablesViewModel)` called once, immediately inside the `Scaffold` content lambda,
before `NavHost(`. Because it lives above the `NavHost`, it survives destination changes and each error
string can only be toasted once (one collector, one one-shot flow) even if two destinations are briefly
composed during a navigation transition.

**8.4 Removed the four duplicated toast blocks** — Deleted the 7-line
`val wearablesErrorMessage by …collectAsState(); val errorToastContext = …; LaunchedEffect { Toast… clearError() }`
block from `HomeScreen.kt`, `LiveAIScreen.kt`, `SimpleLiveStreamScreen.kt`, and the equivalent (block plus
five comment lines plus `lastGlassesError` state plus its own `LaunchedEffect`) from
`QuickVisionScreen.kt`. In `SimpleLiveStreamScreen.kt`, `Toast` and `LocalContext` were used only in that
block, so both imports (`android.widget.Toast`, `androidx.compose.ui.platform.LocalContext`) were removed.
`HomeScreen.kt` and `LiveAIScreen.kt` keep both imports (used elsewhere for other toasts /
`LocalContext.current`). In `QuickVisionScreen.kt`: removed `import android.widget.Toast` (now unused);
kept `LocalContext` (still used at `val context = LocalContext.current`); removed the
`lastGlassesError = null` reset inside `performQuickVision()`; replaced
`errorMessage = lastGlassesError ?: streamFailedText` with
`errorMessage = (streamState as? WearablesViewModel.StreamState.Error)?.message ?: streamFailedText`
(with the comment the brief specifies) — the inline error card still shows the specific DAT reason, now
read from `StreamState.Error` (state) instead of a toast-block snapshot.

**8.4b Edge-to-edge cleanup** — `Theme.kt`: removed
`window.statusBarColor = Color.Transparent.toArgb()` and
`window.navigationBarColor = Color.Transparent.toArgb()` from the `SideEffect` block, and the now-unused
`import androidx.compose.ui.graphics.toArgb`. Kept the
`WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }`
block, `Color` (still used for the color schemes), and `Activity`. Verified by inspection:
`MainActivity.onCreate()` already calls `enableEdgeToEdge()` (confirmed via grep), which makes both system
bars transparent and, by default, follows the system dark/light setting for icon contrast — which is why
the explicit `isAppearanceLight*Bars = true` stays: the app always renders the light theme
(`TurboMetaTheme` hardcodes `LightColorScheme`), so bar-icon contrast must not follow system dark mode.
Every screen already renders inside a `Scaffold` whose `paddingValues` are applied to content, so no inset
handling changed.

**8.5 Build** — see Build Results below.

**8.6 `android/CHANGELOG.md`** — inserted the `## [2.0.0] - 2026-09-10` entry verbatim, immediately after
the `# Changelog` heading and before `## [1.0.0] - 2024-12-27`.

**8.7 `android/README.md`** — (1) `**Version 1.4.0**` → `**Version 2.0.0** — Meta Wearables DAT SDK 0.9.0`.
(2) `## Requirements | 要求` block replaced with the Android-12/V282+/V126+/DAT-Wearables-App bilingual
block. (3) Inserted the `### 🔗 OpenClaw Integration | OpenClaw 集成` section (gateway JSON snippet,
numbered EN/ZH setup steps, Tailscale, Fun-ASR Singapore caveat, and the `NO_FRAME` claim-wait note framed
as "the camera is busy … the command answers `NO_FRAME` — the AI simply retries", not `CameraBusy`) before
`## ⚠️ Important Notes | 重要说明`. (4) Inserted the `### v2.0.0 (2026-09-10)` release-notes entry before
`### v1.4.0 (2024-12-31)`.

**8.8 Root `README.md` / `README_EN.md`** — `README.md`: replaced the "Android stuck at v1.5.0" warning
callout with the "Android 2.0.0 ships OpenClaw + DAT 0.9.0 + Display" success callout linking to
`android/README.md#-openclaw-integration--openclaw-集成`; updated the Android 技术栈 block (API 31, Kotlin
2.2, DAT SDK v0.9.0 with version-requirement note); changed the version badge line to
`✅ **Android v2.0.0**`. `README_EN.md`: same three changes in English, plus (4) the Android badge
`Android-8.0%2B` → `Android-12%2B`.

**8.8b Draft upstream report** — Created `docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md`
(new directory `docs/superpowers/reviews/phase-b/` — did not previously exist) with the brief's content
verbatim: environment, symptom (native abort log + Java stack), MockDeviceKit repro steps, the pointer to
`GlassesSessionManagerInstrumentedTest.kt`'s `capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams` /
`STREAM_SETTLE_MS` (both verified to exist at the referenced file), the "why SDK" rationale referencing
`docs/superpowers/reviews/phase-a/task-9-report.md` Concern 1 (verified that section exists and matches),
and the `gh issue create` posting command. Marked DRAFT / not posted; nothing was filed anywhere.

**8.9 Commit** — staged exactly the paths in the brief's `git add` command and committed with the brief's
exact message. See Build Results for the resulting SHA.

## Grep proving the four toast blocks are removed

```
$ grep -rn "Toast.makeText.*wearablesViewModel\|wearablesErrorMessage\|errorToastContext" android/app/src/main/java/com/smartview/glassai/ui/screens/
NONE FOUND

$ grep -rn "wearablesViewModel.errorMessage.collectAsState" android/app/src/main/java/com/smartview/glassai/ui/screens/
(no output — no screen collects errorMessage for a toast anymore)

$ grep -rn "WearablesErrorToast" android/app/src/main/java/com/smartview/glassai --include=*.kt
./ui/components/WearablesErrorToast.kt:15:fun WearablesErrorToast(wearablesViewModel: WearablesViewModel) {
./ui/navigation/Navigation.kt:22:import com.smartview.glassai.ui.components.WearablesErrorToast
./ui/navigation/Navigation.kt:112:        WearablesErrorToast(wearablesViewModel)
```

One producer (`WearablesViewModel.errorEvents`), one consumer (`WearablesErrorToast`, called once, above
`NavHost`). `QuickVisionScreen.kt` still reads `wearablesViewModel.streamState` /
`WearablesViewModel.StreamState.Error` for its inline card — state, not the one-shot toast — matching the
brief's design.

## Version consistency check

| Location | Value |
|---|---|
| `app/build.gradle.kts` | `versionCode = 5`, `versionName = "2.0.0"` |
| `SettingsScreen.kt` "App Version" row | `BuildConfig.VERSION_NAME` (= `2.0.0` from Gradle) |
| `SettingsScreen.kt` "SDK Version" row | `"Meta Wearables DAT ${BuildConfig.MWDAT_VERSION}"` (`libs.versions.toml` → `mwdat = "0.9.0"`, confirmed) |
| `android/README.md` header | `**Version 2.0.0** — Meta Wearables DAT SDK 0.9.0` |
| `android/README.md` release notes | `### v2.0.0 (2026-09-10)` added at top |
| `android/CHANGELOG.md` | `## [2.0.0] - 2026-09-10` added at top |
| Root `README.md` / `README_EN.md` | `✅ **Android v2.0.0**`, DAT SDK v0.9.0, API 31+ |

No remaining `1.5.0`/`v0.4.0`/API-26 references in touched files. One pre-existing, unrelated code comment
in `APIKeyManager.kt` ("1.5.0 -> 2.0.0 upgrade path") documents a migration test scenario from Task 7 and
correctly describes the version jump this task makes — left untouched, not a discrepancy.

Both locale string files still have 451 `<string name=…>` entries each (unchanged — no new keys needed;
`settings_version`/`settings_sdk_version` already existed in both `values/strings.xml` and
`values-zh-rCN/strings.xml` before this task).

## Build results

`:app:testDebugUnitTest` — `BUILD SUCCESSFUL`. **147 tests, 0 failures, 0 errors, 0 skipped** (aggregated
from `app/build/test-results/testDebugUnitTest/*.xml`); every `<system-err>` block confirmed empty
(`<![CDATA[]]>`) across all 14 test-class XML reports. This matches the stated baseline of 147 JVM unit
tests (this task adds no new unit tests — none were required).

`:app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin` — `BUILD SUCCESSFUL` (92
actionable tasks). Compiler warnings emitted are pre-existing and unrelated to this task's edits, all in
`QuickVisionScreen.kt`: a missing `@Deprecated` annotation on an `UtteranceProgressListener.onError`
override (line ~138, TTS code untouched by this task) and two deprecated icon references
(`Icons.Filled.VolumeUp` / `VolumeDown`, line ~502, in the result-replay button, also untouched). No new
warnings were introduced by any file this task modified (`Navigation.kt`, `HomeScreen.kt`, `LiveAIScreen.kt`,
`SimpleLiveStreamScreen.kt`, `SettingsScreen.kt`, `Theme.kt`, `WearablesErrorToast.kt`).

Instrumented tests were not executed (no emulator run requested by this task — the brief's verification
step is compile-only for androidTest).

## Files changed (commit `eafb608`)

- `android/app/build.gradle.kts` — version bump
- `android/app/src/main/java/com/smartview/glassai/ui/components/WearablesErrorToast.kt` — new
- `android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` — hoisted toast
- `android/app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` — removed duplicated toast block
- `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt` — removed duplicated toast block
- `android/app/src/main/java/com/smartview/glassai/ui/screens/QuickVisionScreen.kt` — removed duplicated toast block, switched inline error text to `StreamState.Error`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` — About SDK version row
- `android/app/src/main/java/com/smartview/glassai/ui/screens/SimpleLiveStreamScreen.kt` — removed duplicated toast block + unused imports
- `android/app/src/main/java/com/smartview/glassai/ui/theme/Theme.kt` — edge-to-edge cleanup
- `android/README.md`, `android/CHANGELOG.md`, `README.md`, `README_EN.md` — docs
- `docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md` — new (draft, not posted)

## Deviations

None. Every edit matches the brief's anchors and verbatim content exactly; no plumbing changes were made
(no new ViewModel/service surface — `errorEvents`, `errorMessage`, `clearError()`, and `StreamState.Error`
all already existed from earlier tasks).

## Concerns

- None blocking. The two pre-existing compiler warnings noted above are unrelated to this task and were
  present before these changes (confirmed by inspecting the surrounding, untouched code).
- The edge-to-edge fix (8.4b) was verified by code inspection and a successful `assembleDebug`/`assembleRelease`
  build only — no on-device/emulator visual check of status-bar contrast was performed, consistent with the
  brief's note that "the owner's phone check stays in Phase C."
- The DAT SDK upstream report is a draft only, per instructions; it was not filed and no `gh` command was run.
