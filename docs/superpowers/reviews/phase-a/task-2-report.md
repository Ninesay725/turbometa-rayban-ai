# Task 2 Report: DAT SDK 0.9.0 bump, manifest placeholders, Application-level initialize

## Summary

Implemented all steps 2.1-2.9 from the brief exactly as specified. Commit `f4bb252` on branch `android-v2`.

## What was implemented

1. **`android/gradle/libs.versions.toml`**: bumped `mwdat = "0.9.0"` (was `0.4.0`), added `mwdat-display` catalog entry right after `mwdat-mockdevice`.
2. **`android/app/build.gradle.kts`**:
   - Added `import java.util.Properties` and the `localProperties` block (reading `rootProject.file("local.properties")` if present) between the `plugins {}` block and `android {}`.
   - Added `manifestPlaceholders["mwdat_application_id"]` / `["mwdat_client_token"]` inside `defaultConfig {}`, right after `vectorDrawables {}`, each falling back through `providers.gradleProperty(...)` then `localProperties.getProperty(..., "0")`.
   - Replaced the DAT dependency lines: `implementation(libs.mwdat.display)` added, `debugImplementation(libs.mwdat.mockdevice)` and `androidTestImplementation(libs.mwdat.mockdevice)` added (previously commented out).
3. **`android/app/src/main/AndroidManifest.xml`**:
   - Added `CAMERA` permission and `<uses-feature android:name="android.hardware.camera" android:required="false" />` after `RECORD_AUDIO`, with the comment explaining it's for the MockDeviceKit phone-camera feed.
   - Replaced the single `APPLICATION_ID` meta-data (previously hardcoded `"0"`) with two meta-data entries reading `${mwdat_application_id}` / `${mwdat_client_token}` placeholders, plus the explanatory comment (including the DAM_ENABLED note).
4. **`android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt`**: rewritten to call `Wearables.initialize(this).onFailure { error, _ -> Log.e(...) }` in `onCreate()`, before `instance = this`... actually per brief ordering `instance = this` is set first, then `Wearables.initialize(this)` — matches brief verbatim. Added `TAG` companion constant.
5. **`android/app/src/main/java/com/smartview/glassai/MainActivity.kt`**: `initializeSDK()` no longer calls `Wearables.initialize(this)` — only calls `wearablesViewModel.startMonitoring()`, with updated comments. The `import com.meta.wearable.dat.core.Wearables` line was left untouched (still used by `RequestPermissionContract()`).
6. **`android/README.md`**: appended the "DAT credentials (optional) | DAT 凭据（可选）" section verbatim at the end of the file, after the `## License` section.

All file content matches the brief's snippets verbatim (verified via `git show` diff review below).

## Verification commands and output

### Step 2.7 — Dependency resolution (acceptance check)

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:dependencies --configuration debugCompileClasspath | grep -i mwdat
```

Output (resolved artifacts, all at 0.9.0):

```
+--- com.meta.wearable:mwdat-mockdevice:0.9.0
|    \--- com.meta.wearable:mwdat-core:0.9.0
+--- com.meta.wearable:mwdat-core:0.9.0 (*)
+--- com.meta.wearable:mwdat-camera:0.9.0
|    \--- com.meta.wearable:mwdat-core:0.9.0 (*)
+--- com.meta.wearable:mwdat-display:0.9.0
|    \--- com.meta.wearable:mwdat-core:0.9.0 (*)
+--- com.meta.wearable:mwdat-mockdevice:{strictly 0.9.0} -> 0.9.0 (c)
+--- com.meta.wearable:mwdat-core:{strictly 0.9.0} -> 0.9.0 (c)
+--- com.meta.wearable:mwdat-camera:{strictly 0.9.0} -> 0.9.0 (c)
+--- com.meta.wearable:mwdat-display:{strictly 0.9.0} -> 0.9.0 (c)
```

Full task ended with `BUILD SUCCESSFUL in 9s`. No `401`/`403` errors from `maven.pkg.github.com` (the `github_token` in `local.properties` has sufficient scope). No transitive AndroidX version conflicts were surfaced by the resolution — no catalog changes beyond the DAT bump were needed.

### Step 2.8 — Expected red build (do not fix)

```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e: " | head -40
```

Result: `BUILD FAILED`. Errors were confined to exactly the four files the brief names:

- `services/QuickVisionService.kt` — `activeDevice()` too-many-args, `Unresolved reference 'startStreamSession'`, `'state'`, `'StreamSessionState'`, `'videoStream'`, `'close'` (cascading from the removed StreamSession API — brief's own line numbers for this file drifted slightly since Task 1's edits, but the flagged symbols match exactly).
- `ui/screens/RTMPStreamingScreen.kt` — `Unresolved reference 'StreamSessionState'` at line 36 (exact line match), with a cascading `collectAsState`/`getValue` delegate error at line 52 because the delegated property's type can no longer be inferred.
- `viewmodels/RTMPStreamingViewModel.kt` — `'StreamSession'`, `'startStreamSession'`, `'StreamSessionState'` at lines 12/13/15 (exact match), plus cascading errors at every later use site (62-317).
- `viewmodels/WearablesViewModel.kt` — `'StreamSession'`, `'startStreamSession'`, `'StreamSessionState'` at lines 12/13/15 (exact match), `'Unavailable'` at 74, `activeDevice()` too-many-args at 126, exhaustiveness/`'Registered'`/`'Unregistering'`/etc. at 147-472 (exact match with brief's list).

Confirmed via:
```bash
grep -oE "java/com/smartview/glassai/[A-Za-z/]+\.kt" <compile.log> | sort -u
```
→ only these 4 files appear. No errors from `MainActivity.kt`, `TurboMetaApplication.kt`, `HomeScreen.kt`, `Navigation.kt`, or `LiveAIScreen.kt` — confirming `TurboMetaApplication.onCreate()`'s `Wearables.initialize(this).onFailure { error, _ -> ... }` call compiles cleanly against the 0.9.0 `DatResult`/`WearablesError` API, and `MainActivity`'s `RequestPermissionContract()` / `checkPermissionStatus` usages are unaffected.

This is the expected red-build signature per the task brief; per the resolutions-of-ambiguities note, the extra cascading error lines beyond the brief's specific line numbers are not a failure since they originate from the same four migrated-later files and the same removed 0.4.0 StreamSession API surface.

## Files changed

- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt`
- `android/app/src/main/java/com/smartview/glassai/MainActivity.kt`
- `android/README.md`

## Self-review

- Diffed the full commit (`git show f4bb252`) against the brief's snippets line-by-line — all additions match verbatim (comments, ordering, indentation).
- Confirmed only the six brief-specified files are in the commit (`git show --stat`); no incidental changes.
- Confirmed pre-existing untracked files (`.agents/`, `.codex/`, `AGENTS.md`) were not touched or staged.
- Did not read or print `android/local.properties`.
- Did not add `mwdat_application_id`/`mwdat_client_token` to `local.properties` — Gradle default `"0"` (Developer Mode) is what resolved.
- Did not attempt to fix the four migrated-later files' compile errors (WearablesViewModel.kt, QuickVisionService.kt, RTMPStreamingViewModel.kt, RTMPStreamingScreen.kt) — left for Tasks 3-5 as instructed.
- Verified `import com.meta.wearable.dat.core.Wearables` was left in `MainActivity.kt` per Step 2.5's note (still used by `RequestPermissionContract()`).
- Used the exact commit message text from the brief, no attribution lines added.
- Committed to `android-v2`, not `main`.

## Concerns

None. Dependency resolution succeeded cleanly with no transitive conflicts requiring catalog fixes. The compile failure signature matches the brief's expectation (same four files, same root-cause symbols — `StreamSession`/`startStreamSession`/`StreamSessionState`/enum-case renames — with additional cascading errors in the same files that are a natural consequence of the API removal, not a scope leak).
