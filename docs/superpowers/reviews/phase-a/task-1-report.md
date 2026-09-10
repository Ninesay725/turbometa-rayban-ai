# Task 1 Report — Toolchain upgrade (mwdat stays 0.4.0)

**Status:** DONE_WITH_CONCERNS
**Branch:** `android-v2`
**Commit:** `35cb7ca` — `build(android): upgrade toolchain to Kotlin 2.2.21 / AGP 8.11.1 / Gradle 8.14.1, SDK 36, JVM 17, official wrapper, test deps`

---

## What was implemented

Every step of the brief, in order, with the brief's exact file contents and values.

### Step 1.0 — Branch

`git switch -c android-v2 2>/dev/null || git switch android-v2` → `Already on 'android-v2'`; `git branch --show-current` → `android-v2`. No commit ever touched `main`.

### Step 1.1 — Official Gradle 8.14.1 wrapper

Copied all four wrapper files from `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/samples/CameraAccess/`, then `chmod +x gradlew`.

```
8941 gradlew        <- expected 8941  OK
2918 gradlew.bat    <- expected 2918  OK
43453 gradle/wrapper/gradle-wrapper.jar
```

`cmp` confirms all three are byte-identical to the sample. The old 595-byte custom `gradlew` is gone; `gradlew.bat` is new.

`gradle/wrapper/gradle-wrapper.properties` after the copy matches the brief verbatim:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Note: `gradle/wrapper/gradle-wrapper.jar` was **already byte-identical** to the sample's jar, so git recorded no change to it (the commit touches 6 files, not 7). Only the `distributionUrl` line of the properties file changed (`8.7` → `8.14.1`).

### Step 1.2 — Wrapper downloads Gradle 8.14.1

```
$ ./gradlew --version
Gradle 8.14.1
Build time:    2025-05-22 13:44:09 UTC
Kotlin:        2.0.21
Groovy:        3.0.24
Launcher JVM:  21.0.5 (Amazon.com Inc. 21.0.5+11-LTS)
Daemon JVM:    C:\Program Files\Amazon Corretto\jdk21.0.5_11 (no JDK specified, using current Java home)
OS:            Windows 11 10.0 amd64
```

Matches the brief's expectation (`Gradle 8.14.1`, JVM `21.0.5 (Amazon.com Inc. 21.0.5+11-LTS)`).

### Step 1.3 — `android/gradle/libs.versions.toml`

Replaced with the brief's exact content (96 lines). Version deltas:

| key | before | after |
|---|---|---|
| agp | 8.6.0 | **8.11.1** |
| kotlin | 2.0.0 | **2.2.21** |
| mwdat | 0.4.0 | 0.4.0 (unchanged, as required) |
| composeBom | 2024.12.01 | **2026.05.01** |
| lifecycle | 2.6.2 | **2.10.0** |
| navigation | 2.7.7 | **2.9.8** |
| activityCompose | 1.9.0 | **1.13.0** |
| exifinterface | 1.3.7 | **1.4.2** |
| kotlinxCollectionsImmutable | 0.3.7 | **0.4.0** |

New entries: `junit = 4.13.2`, `kotlinxCoroutinesTest = 1.10.2`, `androidxTestExtJunit = 1.3.0`, `androidxTestRunner = 1.7.0`, `androidxTestRules = 1.7.0`, plus the five matching `[libraries]` aliases (`junit`, `kotlinx-coroutines-test`, `androidx-test-ext-junit`, `androidx-test-runner`, `androidx-test-rules`).

### Step 1.4 — Daemon heap

`android/gradle.properties` line 11: `-Xmx2048m` → `-Xmx4096m`. Single-line diff, nothing else touched.

### Step 1.5 — `android/app/build.gradle.kts`

Replaced with the brief's exact content (150 lines). Substantive changes vs. the old file:

- `import org.jetbrains.kotlin.gradle.dsl.JvmTarget` added
- `compileSdk` 35 → **36**, `targetSdk` 34 → **36**
- `compileOptions` `VERSION_1_8` → **`VERSION_17`** (source + target)
- removed the deprecated `kotlinOptions { jvmTarget = "1.8" }` block, replaced by a top-level `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`
- added `testOptions { unitTests.isReturnDefaultValues = true }`
- added `testImplementation(libs.junit)`, `testImplementation(libs.kotlinx.coroutines.test)` and the three `androidTestImplementation` entries
- `isUniversalApk` comment updated to the brief's wording

### Step 1.6 — Build

```
$ ./gradlew :app:assembleDebug
...
> Task :app:assembleDebug
> Task :app:createDebugApkListingFileRedirect

BUILD SUCCESSFUL in 2m 43s
37 actionable tasks: 37 executed
Configuration cache entry stored.
```

Exit code 0. **0 Kotlin errors (`e:`), 15 Kotlin warnings (`w:`)**, no Gradle deprecation warnings, no `'kotlinOptions' is deprecated` warning (the block was removed), and the anticipated javac `system modules path not set` warning did not appear at all.

No K2 type *errors* appeared in the unchanged app code, so no source file was touched in this task.

### Step 1.6a — coroutines alignment

```
$ ./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath | grep -E "kotlinx-coroutines-(core|test)" | sort -u
+--- org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2
|         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
|         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.10.2 (*)
|         |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0 -> 1.10.2 (*)
|    \--- org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.10.2
...
```

**Every** `kotlinx-coroutines-core` line resolves to `1.10.2`, which equals the catalog's `kotlinxCoroutinesTest = "1.10.2"`. No catalog edit was required; the brief's fallback branch did not trigger.

### Step 1.7 — APK outputs

```
$ ls -la app/build/outputs/apk/debug/
-rw-r--r--  89041982  app-arm64-v8a-debug.apk
-rw-r--r--  85671108  app-armeabi-v7a-debug.apk
-rw-r--r-- 117924297  app-universal-debug.apk
-rw-r--r--       974  output-metadata.json
```

All three expected APKs present, including the universal one needed for emulator installs.

### Step 1.8 — Commit

Branch guard passed silently; commit landed on `android-v2`:

```
[android-v2 35cb7ca] build(android): upgrade toolchain to Kotlin 2.2.21 / AGP 8.11.1 / Gradle 8.14.1, SDK 36, JVM 17, official wrapper, test deps
 6 files changed, 377 insertions(+), 31 deletions(-)
 create mode 100644 android/gradlew.bat
```

No attribution lines in the message. `android/local.properties` is git-ignored (`android/.gitignore:24`) and was never read, printed, or staged.

---

## Extra verification (beyond the brief, cheap and de-risks Task 9)

The brief's `assembleDebug` never exercises the newly declared `androidTest` dependencies, so I resolved them explicitly:

```
$ ./gradlew :app:dependencies --configuration debugAndroidTestRuntimeClasspath
+--- androidx.test.ext:junit:1.3.0
+--- androidx.test:rules:1.7.0
+--- androidx.test:runner:1.7.0
     (pulls androidx.test:core:1.7.0, androidx.test:monitor:1.8.0, androidx.test.services:storage:1.6.0)
BUILD SUCCESSFUL in 1s
```

Key runtime versions resolved as declared (`debugRuntimeClasspath`):

```
com.meta.wearable:mwdat-core:0.4.0            <- deliberately unchanged
com.meta.wearable:mwdat-camera:0.4.0          <- deliberately unchanged
androidx.compose:compose-bom:2026.05.01
androidx.lifecycle:lifecycle-runtime-ktx:2.10.0
androidx.lifecycle:lifecycle-runtime-compose:2.10.0
androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0
androidx.navigation:navigation-compose:2.9.8
androidx.activity:activity-compose:1.13.0
androidx.exifinterface:exifinterface:1.4.2
androidx.security:security-crypto:1.1.0-alpha06
org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0
ai.picovoice:porcupine-android:3.0.3
com.github.pedroSG94.rtmp-rtsp-stream-client-java:rtplibrary:2.2.6 (+ encoder/rtmp/rtsp 2.2.6)
```

None of the "at risk" dependencies flagged in the task context (Picovoice 3.0.3, RootEncoder/rtplibrary 2.2.6 from JitPack, security-crypto) needed any fix — all resolved and compiled unchanged under the new toolchain.

Line-ending sanity: the working tree keeps the sample's exact bytes (CRLF) per the brief's resolution, and the repo's `core.autocrlf=true` normalized the committed blob to LF (`git show HEAD:android/gradlew | head -1 | cat -A` → `#!/bin/sh$`, blob 8692 bytes), so a non-Windows checkout gets a valid POSIX script. `gradlew` kept mode `100755`; `gradlew.bat` is `100644`.

---

## Files changed

| File | Change |
|---|---|
| `android/gradlew` | replaced (595-byte custom script → official 8941-byte Gradle 8.14.1 wrapper) |
| `android/gradlew.bat` | **new** (2918 bytes, official) |
| `android/gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` 8.7 → 8.14.1 |
| `android/gradle/wrapper/gradle-wrapper.jar` | copied; already byte-identical, so no recorded change |
| `android/gradle/libs.versions.toml` | whole file replaced |
| `android/gradle.properties` | line 11 heap 2048m → 4096m |
| `android/app/build.gradle.kts` | whole file replaced |

No source file (`app/src/**`) was modified. Nothing outside the toolchain scope was touched.

---

## Self-review findings

- **Completeness:** all nine brief steps executed; every literal value (versions, sizes, file contents, commit message) used verbatim. Step 1.6a's conditional edit correctly did not fire because core and test already agree at 1.10.2.
- **Quality:** diffs are minimal and mechanical — `gradle.properties` is a one-line diff; the toml and build-script diffs contain only the brief's intended deltas (verified with `git diff`).
- **Discipline:** no app logic changed, no restructuring, no extra dependencies, no `main`-branch activity. The untracked `.agents/`, `.codex/`, `AGENTS.md` at the repo root pre-date this task and were left alone (not staged).
- **New warnings I introduced:** none from my own edits. The build emits no build-script deprecation warnings.

---

## Concerns

1. **13 pre-existing deprecation warnings now surface under the newer libraries** (they compile fine; nothing is an error). They are consequences of the version bumps, not of new code, and they are outside this task's scope — but they are the cheapest available signal about what the newer Compose/SDK lines will eventually break:
   - `ui/theme/Theme.kt:61,62` — `statusBarColor` / `navigationBarColor` deprecated (compileSdk 36). The modern replacement is `enableEdgeToEdge()`; leaving these is fine for now, but on targetSdk 36 edge-to-edge is enforced, so **whichever later task touches theming should confirm the app still lays out correctly under forced edge-to-edge on Android 15+**. This is the one warning with a plausible *visual* consequence of the targetSdk 34 → 36 bump.
   - `ui/screens/ModeSettingsScreen.kt:381,405` and `ui/screens/QuickVisionScreen.kt:501` (x2) — `Icons.Filled.MenuBook` / `VolumeUp` / `VolumeDown` deprecated in favour of `Icons.AutoMirrored.Filled.*` (Compose BOM 2026.05.01).
   - `ui/screens/RecordsScreen.kt:98` — `TabRow` deprecated, replaced by `PrimaryTabRow` / `SecondaryTabRow`.
   - `managers/BluetoothAudioManager.kt:203,222` — `startBluetoothSco()` / `stopBluetoothSco()` deprecated in the platform.
   - `services/QuickVisionService.kt:127` — `Locale(String, String)` constructor deprecated; `:386` and `ui/screens/QuickVisionScreen.kt:138` — override a deprecated member without being marked deprecated.
   - `services/RTMPStreamingService.kt:195` — `MediaCodecInfo...COLOR_FormatYUV420Planar` deprecated.
2. **`viewmodels/WearablesViewModel.kt:192` and `:197`** — the two known K2 warnings: "Type argument for type parameter 'T' cannot be inferred because it has incompatible upper bounds: Application, Activity ... This will become an error in a future release (KT-51221)." Per the task resolutions these are **not fixed here** (Task 4 rewrites this file), but note they are on a path to becoming hard errors in a future Kotlin release, so Task 4 must actually resolve them rather than carry them forward.
3. **`app/src` has only a `main` source set** — no `test/` or `androidTest/` directories exist yet, so the new `testImplementation` / `androidTestImplementation` declarations are currently inert (correct: Task 9 adds the sources). `:app:testDebugUnitTest` was therefore not run; the brief does not ask for it and it would be a no-op.
4. **`app/build.gradle.kts` declares a release `signingConfig` pointing at the debug keystore** with hard-coded `"android"` credentials (carried over verbatim from the old file, as the brief requires). It is the debug keystore so no real secret is exposed, but shipping a release build signed with it would be wrong; that is pre-existing and out of scope here.
5. The `stripDebugDebugSymbols` task prints a long "Unable to strip the following libraries" list (~70 `.so` files, mostly from the DAT SDK). This is pre-existing and benign (packaging unstripped), but it is why the debug APKs are 85-118 MB.
