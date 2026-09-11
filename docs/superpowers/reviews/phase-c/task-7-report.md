# Phase C Task 7 — Settings, Home display status and Task 8 entry wiring

Implemented the assigned S8 phone UI and S11 rows 1–9 and 19–20 in the shared `android-v2` workspace. Task 8 Navigation/Settings integration uses Dalton's exact S9 debug/release entry contract. No Gradle invocation, commits, dependency changes or access to `android/local.properties`.

## Files written by this worker

- `android/app/src/main/java/com/smartview/glassai/viewmodels/SettingsViewModel.kt` — `isDisplayEnabled` starts from `APIKeyManager.isGlassesDisplayEnabled()`; `setDisplayEnabled(enabled)` persists immediately, updates the shared session manager, then publishes the new setting state. It uses the existing APIKeyManager instance.
- `android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` — exposes `displayState` and `isDisplayAvailable` directly from the shared manager and derives `isDisplayCapable` with `activeDevice.map { it?.isDisplayCapable == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)`. No permission, monitoring or camera behavior was changed.
- `android/app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` — display section between Quick Vision and AI settings, using the existing toggle component. It collects active-device metadata independently of WearablesViewModel and shows the unsupported subtitle only when a selected device explicitly has `isDisplayCapable == false`. No second APIKeyManager lookup or Save button was introduced.
- `android/app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` — collects the three display properties, passes them into DeviceStatusCard, and adds DisplayStatusRow after the update prompts only when the device is display-capable.
- `android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` — adds `Screen.GlassesDisplayPreview("glasses_display_preview")`, passes the Settings callback, and registers the preview destination only when `GlassesDisplayPreviewEntry.isAvailable`.
- `android/app/src/main/res/values/strings.xml` and `android/app/src/main/res/values-zh-rCN/strings.xml` — minimal append of the 11 assigned S11 rows. Popper's existing rows 10–18 were left intact.
- `android/app/src/test/java/com/smartview/glassai/viewmodels/WearablesViewModelTest.kt` — three S8 JVM cases added under the expanded write scope, using the existing fake manager and an optional manager argument on the existing ViewModel test helper.

## UI behavior

Home's row displays Off when display availability is false. Otherwise STARTING maps to Preparing, STARTED to Ready, STOPPING/STOPPED/CLOSED to Stopped, and NOT_ATTACHED to Not attached. The Visibility icon and text use Success only while available and STARTED, and TextSecondaryLight otherwise. Ordinary glasses do not get this row.

Settings exposes `onNavigateToGlassesDisplayPreview: () -> Unit = {}`. The preview entry is in the developer section, guarded by `BuildConfig.DEBUG` and the entry's `isAvailable`. Its callback and destination also check availability, so the release stub has no reachable preview destination. Dalton's preview entry/screen files and MockDeviceKit controls were not edited.

## Strings and checks actually performed

Both locale XML documents parsed successfully with **474 unique string keys each** at the final audit. There were no duplicate keys or locale-key differences. All 20 S11 rows, including Popper's nine card labels, matched the approved plan's exact English and Chinese text. The live tree's total differs from the plan's old baseline; no existing strings were removed to force the planned count.

Scoped `git diff --check` passed. Manual diff review checked the exact status-state mapping, capability gate, immediate setting persistence order, preserved permission/startup behavior, and compatibility with the existing debug/release preview entry signatures. No emulator or device UI verification was performed by this worker.

## Tests added versus run; controller work remaining

Added the three Task 7 JVM cases below after the controller expanded write scope to `WearablesViewModelTest.kt` and this report. They have not been run by this worker. This follow-up changed only those two files and did not invoke Gradle or make commits. Task 4 also contributes the 26 JUnit tests detailed in `task-4-report.md`.

The controller has already added `APIKeyManagerInstrumentedTest.glassesDisplayEnabledPersists` with restoration of the display flag; the named test was confirmed present read-only and was not duplicated. This worker did not execute it.

The following cases are now present in `android/app/src/test/java/com/smartview/glassai/viewmodels/WearablesViewModelTest.kt`:

- `displayStateFollowsTheManager`: verifies availability for a capable device and ViewModel display-state transitions through NOT_ATTACHED → STARTING → STARTED → STOPPED, driven by the real manager and fake session/display.
- `isDisplayCapableFollowsTheActiveDevice`: ordinary device → capable device → null yields false → true → false through the derived ViewModel flow.
- `isDisplayAvailableIsFalseWhenTheSettingIsOff`: injects a manager constructed with `displayEnabled = { false }`; the capable device remains unavailable before and after its session starts, with no display attachment. The dedicated manager uses the test's background scope. Session claims in both session-based cases are released in `finally`.

The controller reported successful integrated debug/release builds and tests (320 debug / 309 release) before this follow-up. Those results predate these three new tests and do not establish that they pass. This worker performed scoped diff/whitespace checks and source review only; no JVM or instrumented tests were run. All three planned Task 7 cases are now authored; their execution remains with the controller, alongside any outstanding emulator/device UI checks. No hardware-rendering claim is made.
