# Android 2.0 Phase A: Toolchain + DAT SDK 0.9.0 Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the TurboMeta Android app (`android/`, package `com.smartview.glassai`) from DAT SDK 0.4.0 to 0.9.0 on the Meta 0.9.0 sample toolchain, introduce a single process-wide `GlassesSessionManager` that owns the one allowed `DeviceSession`, migrate the three camera call sites onto it, fix the registration `Activity` crash, and add a debug-only MockDeviceKit screen so the whole flow is verifiable on an emulator without glasses.

**Architecture:** `GlassesSessionManager` (new package `com.smartview.glassai.glasses`) is the only code that calls `Wearables.createSession`; it is reference-counted (`acquire(owner)`/`release(owner)`), keeps an outgoing session observed until the SDK reports `STOPPED` before it creates the next one (`ensureSessionStarted()`), lends the single `Camera` to one owner at a time (`addCamera` → `CameraResult`, `CameraBusy` for a second owner), exposes `sessionState`/`displayState`/`activeDevice` StateFlows plus a `sessionError` SharedFlow, and leaves a `DisplayAttacher` hook for Phase C. The DAT static entry points are wrapped behind small interfaces (`DatSessionFactory`, `DatDeviceObserver`, `GlassesSession`, `GlassesCamera`) so the manager's state machine and the wake-word capture path (`GlassesPhotoCapturer`) are unit-tested with fakes on the JVM, and an `androidTest` suite drives the real SDK with MockDeviceKit on the emulator. `WearablesViewModel`, `QuickVisionService` and `RTMPStreamingViewModel` become borrowers of the shared session and keep their public contracts so no screen needs to change its API usage.

**Tech Stack:** Kotlin 2.2.21, AGP 8.11.1, Gradle 8.14.1 (official wrapper), compileSdk/targetSdk 36, minSdk 31, JVM 17, Jetpack Compose BOM 2026.05.01, activity-compose 1.13.0, lifecycle 2.10.0, navigation-compose 2.9.8, Meta DAT `com.meta.wearable:mwdat-{core,camera,display}:0.9.0` + `debugImplementation mwdat-mockdevice:0.9.0`, JUnit 4.13.2, kotlinx-coroutines-test 1.10.2 (aligned to the resolved `kotlinx-coroutines-core` in Step 1.6a), androidx.test `ext:junit 1.3.0` / `runner 1.7.0` / `rules 1.7.0` for `androidTest`.

## Global Constraints

- Kotlin `2.2.21`, Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` `2.2.21` (spec §3 decision 2).
- AGP `8.11.1`; Gradle `8.14.1` via the **official** wrapper (`gradlew` + `gradlew.bat` + `gradle/wrapper/*`) copied from `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/samples/CameraAccess/` (spec §3 decision 2).
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 31`; `JavaVersion.VERSION_17` + `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`.
- `activity-compose = 1.13.0` (sample version; `LocalActivity` needs ≥ 1.10.0), Compose BOM `2026.05.01`, `lifecycle = 2.10.0`, `navigation = 2.9.8`.
- `mwdat = 0.9.0`: `implementation(mwdat-core, mwdat-camera, mwdat-display)`, `debugImplementation(mwdat-mockdevice)` (spec §5.1).
- All Gradle commands run from **Git Bash** inside `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android` as `./gradlew …` (never `gradlew.bat`, never `gradle`). JDK: `JAVA_HOME=C:\Program Files\Amazon Corretto\jdk21.0.5_11` (already set). Android SDK: `C:/Users/Lee_L/AppData/Local/Android/Sdk` (from `android/local.properties`, which is git-ignored).
- **Never print, cat, grep, or read `android/local.properties`** (it contains `github_token`). Only *append* the two optional keys described in Task 2 if the user asks; the build defaults both to `"0"` when absent.
- All Phase A work happens on branch `android-v2` (spec §3 decision 5). Step 1.0 creates/checks out the branch; the first commit (Step 1.8) is guarded by `git branch --show-current | grep -qx android-v2 || { echo 'wrong branch'; exit 1; }`. Commit after every task with the exact `git` commands given (run from the repo root `D:/Coding/Workspaces/Android/turbometa-rayban-ai`). No attribution lines in commit messages.
- Every new user-visible string is added to **both** `android/app/src/main/res/values/strings.xml` (English) and `android/app/src/main/res/values-zh-rCN/strings.xml` (Simplified Chinese) with exactly the text given in this plan. Both files end with `</resources>`; insert new strings immediately before that closing tag.
- Unit tests are JVM tests (JUnit4 + kotlinx-coroutines-test) under `android/app/src/test/java/com/smartview/glassai/…`, run with `./gradlew :app:testDebugUnitTest --tests '<fqcn>'`; the final gate is `./gradlew test` (both `testDebugUnitTest` and `testReleaseUnitTest`). Instrumented tests (spec §10 仪器测试) live under `android/app/src/androidTest/java/com/smartview/glassai/…` and run on the emulator with `./gradlew :app:connectedDebugAndroidTest` (Task 9).
- No physical phone is attached in Phase A. Verification = `assembleDebug` + unit tests + instrumented tests + manual checklist on the emulator (`Pixel_5`, API 31 x86_64, or `Medium_Phone_API_36.0`) with MockDeviceKit. ABI splits are ARM-only, so manual emulator installs use `app/build/outputs/apk/debug/app-universal-debug.apk` (AGP's `connectedDebugAndroidTest` falls back to the universal APK on its own).
- The app is a single Gradle module: after Task 2 bumps the SDK, `:app:assembleDebug` is **expected to fail** until Task 5 finishes migrating all three camera call sites. Tasks 2–4 verify with the commands stated in each task, not with a green `assembleDebug`.
- Line numbers in this plan were taken from the files as they are at the start of Phase A. **When a line number and a quoted anchor disagree, the quoted text is authoritative.**
- SDK member names are verified against the 0.9.0 sample sources in the marketplace clone, not against session-local research notes: `C:/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/CHANGELOG.md`, `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/camera/CameraViewModel.kt`, `.../mockdevicekit/MockDeviceKitViewModel.kt`, `.../wearables/WearablesViewModel.kt`, and `samples/CameraAccess/app/src/androidTest/.../InstrumentationTest.kt` (all paths below abbreviate this prefix as `$SAMPLE`).
- `DatResult` members used anywhere in this plan: `fold(onSuccess, onFailure = { error, cause -> })`, `onFailure { error, _ -> }`, `getOrNull()`, `getOrDefault()`, `getOrThrow()` — all present in 0.9.0 (`DatResult` is a regular class); no factory functions (`success`/`failure`) are ever called, which is why the gateway interfaces return Kotlin sealed results.
- Preserve `WearablesViewModel`'s public contract consumed by `LiveAIScreen`, `QuickVisionScreen`, `SimpleLiveStreamScreen`, `LeanEatScreen`, `VisionScreen`, `HomeScreen`, `Navigation`: nested `sealed class StreamState { Stopped, Waiting, Streaming, Paused (new), Error(message) }`, `currentFrame`, `capturedPhoto`, `hasActiveDevice`, `connectionState`, `streamState`, `startStream()`, `stopStream()`, `takePhoto(): Bitmap?`, `clearCapturedPhoto()`, `isRegistered`, `setError()`, `clearError()`, `errorMessage`. The SDK enum is imported as `import com.meta.wearable.dat.camera.types.StreamState as DatStreamState`.

## File Structure

| Path (relative to `android/`) | Action | Responsibility |
|---|---|---|
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | Replace (Task 1) | Official Gradle 8.14.1 wrapper |
| `gradle/libs.versions.toml` | Modify (Tasks 1, 2) | Version catalog: toolchain bump, unit + instrumented test libs, `mwdat 0.9.0`, `mwdat-display` |
| `gradle.properties` | Modify (Task 1) | Daemon heap for Kotlin 2.2 + AGP 8.11 |
| `app/build.gradle.kts` | Modify (Tasks 1, 2) | SDK 36 / JVM 17 / unit + androidTest deps / manifest placeholders / DAT deps |
| `app/src/main/AndroidManifest.xml` | Modify (Task 2) | `${mwdat_application_id}`/`${mwdat_client_token}` meta-data, `CAMERA` permission + `uses-feature` |
| `app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` | Modify (Task 2) | `Wearables.initialize` once per process |
| `app/src/main/java/com/smartview/glassai/MainActivity.kt` | Modify (Tasks 2, 7) | Remove SDK init; Bluetooth-only permission gate; `POST_NOTIFICATIONS` |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionState.kt` | Create (Task 3) | Pure-Kotlin value types: `GlassesDeviceInfo`, `CameraResult`, `CameraError`, `GlassesDisplayState` |
| `app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt` | Create (Task 3) | Interfaces wrapping DAT statics: `DatSessionFactory`, `DatDeviceObserver`, `GlassesSession`, `GlassesCamera`, `DisplayAttacher`, result types |
| `app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt` | Create (Task 3) | Real implementations over `Wearables`, `DeviceSession`, `Camera`, `Stream` |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt` | Create (Task 3) | Process singleton, ref-counted session owner, waits for the outgoing session's `STOPPED` before re-creating, camera lending, device metadata |
| `app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt` | Create (Task 3), extend (Task 5) | Test fakes for the gateway interfaces (sync or async `stop()`, scripted failures, scripted capture results) |
| `app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt` | Create (Task 3) | State-machine unit tests (24 tests) |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt` | Create (Task 5) | The wake-word capture path as a testable class: wait device → acquire → session → camera → `capturePhoto()` → frame fallback → release |
| `app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` | Create (Task 5) | JVM tests for the capture path with the fakes (6 tests) |
| `app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` | Create (Task 9) | Emulator + MockDeviceKit: registration, stream, photo, capturer through the shared session, `displayState` stays `NOT_ATTACHED` (spec §10) |
| `app/src/androidTest/assets/plant.mp4` | Create (Task 9, copied from `$SAMPLE`) | H.265 clip used as the mock camera feed |
| `app/src/androidTest/assets/plant.png` | Create (Task 9, copied from `$SAMPLE`) | Image returned by the mock `capturePhoto()` |
| `app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` | Rewrite (Task 4), modify (Task 6) | UI-facing DAT façade: registration (Activity), device metadata, stream via manager, photo capture |
| `app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` | Modify (Task 4) | `LocalActivity` for register/disconnect; firmware / DAT-app update buttons |
| `app/src/main/java/com/smartview/glassai/ui/screens/QuickVisionScreen.kt` | Modify (Task 4) | Stream wait budget 5 s → 20 s with early exit on `StreamState.Error` (covers stop-wait 5 s + session start 12 s + retry 1 s) |
| `app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` | Modify (Task 5) | Wake-word capture via `GlassesPhotoCapturer` (shared session, `capturePhoto()`, 12 s budgets, `CameraBusy`) |
| `app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` | Modify (Tasks 5, 6) | RTMP feed via shared session |
| `app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt` | Modify (Task 5) | Import rename |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesErrorMessages.kt` | Create (Task 6) | zh/en string mapping for `DeviceSessionError`, `StreamError`, `CaptureError`, `RegistrationError`, `CameraError` |
| `app/src/test/java/com/smartview/glassai/glasses/GlassesErrorMessagesTest.kt` | Create (Task 6) | Exhaustiveness test for the mapping |
| `app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt` | Modify (Tasks 6, 7) | Paused status glyph; lazy `RECORD_AUDIO` request |
| `app/src/main/java/com/smartview/glassai/ui/screens/SimpleLiveStreamScreen.kt` | Modify (Task 6) | Paused overlay |
| `app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitEntry.kt` | Create (Task 8) | Debug variant: exposes the MockDeviceKit screen |
| `app/src/release/java/com/smartview/glassai/debug/MockDeviceKitEntry.kt` | Create (Task 8) | Release variant: stub (`isAvailable = false`) |
| `app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitViewModel.kt` | Create (Task 8) | Enable/disable, pair, power/don/fold, feeds, captouch |
| `app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitScreen.kt` | Create (Task 8) | Compose UI for the above |
| `app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` | Modify (Task 8) | `Screen.MockDeviceKit` route |
| `app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` | Modify (Task 8) | "Developer" section (debug builds only) |
| `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml` | Modify (Tasks 4, 6, 7, 8) | New strings |
| `README.md` (android/) | Modify (Task 2) | Document the two optional `local.properties` keys |

---

### Task 1: Toolchain upgrade (mwdat stays 0.4.0) — ends with a passing `assembleDebug`

**Files:**
- Replace: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradle/wrapper/gradle-wrapper.properties`
- Modify: `android/gradle/libs.versions.toml` (whole file), `android/gradle.properties` (line 11), `android/app/build.gradle.kts` (whole file)

**Interfaces:** none (build configuration only). Produces a build that compiles the *unchanged* 0.4.0 app code with Kotlin 2.2.21 / AGP 8.11.1 / Gradle 8.14.1 so that toolchain breakages (RootEncoder `rtplibrary 2.2.6`, Picovoice, Compose BOM 2026.05.01) surface before any SDK migration (spec §11 row 1).

- [ ] **Step 1.0 — Create/check out the Phase A branch** (spec §3 decision 5; the repo starts on `main`):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git switch -c android-v2 2>/dev/null || git switch android-v2
git branch --show-current   # expect: android-v2
```

Expected: `android-v2`. Every later commit in this plan runs on this branch; Step 1.8 refuses to commit anywhere else.

- [ ] **Step 1.1 — Replace the custom 595-byte `gradlew` and add `gradlew.bat` with the official 8.14.1 wrapper files.** Run from Git Bash:

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
SAMPLE=/c/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/samples/CameraAccess
cp "$SAMPLE/gradlew" ./gradlew
cp "$SAMPLE/gradlew.bat" ./gradlew.bat
cp "$SAMPLE/gradle/wrapper/gradle-wrapper.jar" gradle/wrapper/gradle-wrapper.jar
cp "$SAMPLE/gradle/wrapper/gradle-wrapper.properties" gradle/wrapper/gradle-wrapper.properties
chmod +x gradlew
wc -c gradlew gradlew.bat            # expect 8941 gradlew, 2918 gradlew.bat
cat gradle/wrapper/gradle-wrapper.properties
```

Expected `gradle/wrapper/gradle-wrapper.properties` content after the copy:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 1.2 — Verify the wrapper downloads Gradle 8.14.1** (first run downloads ~130 MB):

```bash
./gradlew --version
```

Expected output contains `Gradle 8.14.1` and `JVM: 21.0.5 (Amazon.com Inc. 21.0.5+11-LTS)`.

- [ ] **Step 1.3 — Replace `android/gradle/libs.versions.toml` with this exact content** (`mwdat` deliberately stays `0.4.0`; new entries: `navigation`, `junit`, `kotlinxCoroutinesTest`, and the three androidx.test artifacts for the `androidTest` suite added in Task 9):

```toml
[versions]
agp = "8.11.1"
kotlin = "2.2.21"
mwdat = "0.4.0"
composeBom = "2026.05.01"
lifecycle = "2.10.0"
navigation = "2.9.8"
room = "2.6.1"
okhttp = "4.12.0"
gson = "2.10.1"
datastore = "1.0.0"
security = "1.1.0-alpha06"
coil = "2.6.0"
activityCompose = "1.13.0"
appcompat = "1.7.0"
coreKtx = "1.13.0"
exifinterface = "1.4.2"
kotlinxCollectionsImmutable = "0.4.0"
picovoice = "3.0.3"
rtmpClient = "2.2.6"
junit = "4.13.2"
kotlinxCoroutinesTest = "1.10.2"
androidxTestExtJunit = "1.3.0"
androidxTestRunner = "1.7.0"
androidxTestRules = "1.7.0"

[libraries]
# Meta Wearables DAT SDK
mwdat-core = { group = "com.meta.wearable", name = "mwdat-core", version.ref = "mwdat" }
mwdat-camera = { group = "com.meta.wearable", name = "mwdat-camera", version.ref = "mwdat" }
mwdat-mockdevice = { group = "com.meta.wearable", name = "mwdat-mockdevice", version.ref = "mwdat" }

# AndroidX Core
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-exifinterface = { group = "androidx.exifinterface", name = "exifinterface", version.ref = "exifinterface" }

# Compose
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }

# Lifecycle
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }

# Navigation
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

# Room Database
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# DataStore
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Security
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "security" }

# Networking
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }

# Image Loading
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }

# Collections
kotlinx-collections-immutable = { group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable", version.ref = "kotlinxCollectionsImmutable" }

# Picovoice Wake Word Detection
picovoice-porcupine = { group = "ai.picovoice", name = "porcupine-android", version.ref = "picovoice" }

# RTMP Streaming (old version to avoid Compose conflicts)
rtmp-client = { group = "com.github.pedroSG94.rtmp-rtsp-stream-client-java", name = "rtplibrary", version.ref = "rtmpClient" }

# Unit testing (JVM)
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }

# Instrumented testing (androidTest, emulator + MockDeviceKit)
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRules" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 1.4 — Raise the Gradle daemon heap.** In `android/gradle.properties` change line 11 from `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8` to:

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

- [ ] **Step 1.5 — Replace `android/app/build.gradle.kts` with this exact content** (compileSdk/targetSdk 36, JVM 17 via `compilerOptions`, unit-test deps, `unitTests.isReturnDefaultValues = true` so `android.util.Log` calls in JVM tests return defaults instead of throwing):

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.smartview.glassai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartview.glassai"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Use debug keystore for now
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Split APKs by ABI for smaller file sizes
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true  // Also generate a universal APK (used for emulator installs)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Meta Wearables DAT SDK
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    // implementation(libs.mwdat.mockdevice) // Only needed for testing

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Security
    implementation(libs.androidx.security.crypto)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Image Loading
    implementation(libs.coil.compose)

    // Collections
    implementation(libs.kotlinx.collections.immutable)

    // Picovoice Wake Word Detection
    implementation(libs.picovoice.porcupine)

    // RTMP Streaming (RootEncoder old version without Compose dependencies)
    implementation(libs.rtmp.client)

    // Unit tests (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests (emulator + MockDeviceKit, Task 9)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
```

- [ ] **Step 1.6 — Build.** (First run resolves AGP 8.11.1, Kotlin 2.2.21 and Compose BOM 2026.05.01 from Google/Maven Central and `mwdat 0.4.0` from the GitHub Packages repo already configured in `settings.gradle.kts:33-39`; allow 5–10 min.)

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Expected warnings that are fine to ignore: `w: ... 'kotlinOptions' is deprecated` will NOT appear (we removed it); Java `[options] system modules path not set in conjunction with -source 17` (JDK 21 compiling for 17) is fine. If Kotlin 2.2 (K2) reports a *new* type error in unchanged app code, fix that single site in the most local way that keeps behavior (e.g. add an explicit type argument) and record the change in the commit message; do not touch DAT call sites in this task.

- [ ] **Step 1.6a — Align `kotlinx-coroutines-test` with the resolved `kotlinx-coroutines-core`** (Compose BOM 2026.05.01 / lifecycle 2.10.0 may pull a newer coroutines line; a test artifact older than core is the classic `NoSuchMethodError` inside `runTest`):

```bash
./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath | grep -E "kotlinx-coroutines-(core|test)" | sort -u
```

Expected: every `kotlinx-coroutines-core` line ends in the same version (e.g. `-> 1.10.2` or `-> 1.11.0`). If that version differs from `kotlinxCoroutinesTest = "1.10.2"` in `gradle/libs.versions.toml`, change the `kotlinxCoroutinesTest` line to that exact version (only `1.10.0`, `1.10.1`, `1.10.2`, `1.11.0` are published) and re-run the command until the `-core` and `-test` versions match.

- [ ] **Step 1.7 — Confirm the universal debug APK exists** (needed for emulator installs later):

```bash
ls -la app/build/outputs/apk/debug/
```

Expected: `app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk`, `app-universal-debug.apk`.

- [ ] **Step 1.8 — Commit (branch-guarded).**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git branch --show-current | grep -qx android-v2 || { echo 'wrong branch'; exit 1; }
git add android/gradlew android/gradlew.bat android/gradle/wrapper/gradle-wrapper.jar android/gradle/wrapper/gradle-wrapper.properties android/gradle/libs.versions.toml android/gradle.properties android/app/build.gradle.kts
git commit -m "build(android): upgrade toolchain to Kotlin 2.2.21 / AGP 8.11.1 / Gradle 8.14.1, SDK 36, JVM 17, official wrapper, test deps"
```

Expected: the guard prints nothing and the commit lands on `android-v2` (`git log --oneline -1` shows the message above).

---

### Task 2: DAT SDK 0.9.0 bump, manifest placeholders, Application-level initialize — ends with passing dependency resolution

**Files:**
- Modify: `android/gradle/libs.versions.toml` (line 4 `mwdat`, add `mwdat-display` after line 26)
- Modify: `android/app/build.gradle.kts` (add `import java.util.Properties` + `localProperties` block at top, `manifestPlaceholders` inside `defaultConfig`, DAT dependency lines)
- Modify: `android/app/src/main/AndroidManifest.xml` (lines 5-21 permissions, lines 33-36 meta-data)
- Modify: `android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` (whole file)
- Modify: `android/app/src/main/java/com/smartview/glassai/MainActivity.kt` (lines 118-127 `initializeSDK`)
- Modify: `android/README.md` (append a section)

**Interfaces:**
- Produces: `TurboMetaApplication.onCreate()` calls `Wearables.initialize(this): DatResult<Unit, WearablesError>` exactly once per process, before any Activity/Service exists (spec §5.3; fixes `WearablesError.NOT_INITIALIZED` for the wake-word `QuickVisionService` path; `initialize` signature per `$SAMPLE/.../wearables/WearablesViewModel.kt` and the 0.9.0 entry of the marketplace `CHANGELOG.md`). Note the marketplace skills recommend initializing *after* the Bluetooth grant; spec §5.3 deliberately moves it earlier, and Task 10 checklist item 15 verifies that a grant arriving after `initialize` still leads to device discovery.
- Produces: manifest `<meta-data com.meta.wearable.mwdat.APPLICATION_ID = ${mwdat_application_id}>` and `CLIENT_TOKEN = ${mwdat_client_token}` from `local.properties` keys `mwdat_application_id` / `mwdat_client_token`, defaulting to `"0"` (Developer Mode) (spec §5.2).

- [ ] **Step 2.1 — Bump the catalog.** In `android/gradle/libs.versions.toml` change line 4 to `mwdat = "0.9.0"` and add the display artifact right after the `mwdat-mockdevice` line so the DAT block reads:

```toml
# Meta Wearables DAT SDK
mwdat-core = { group = "com.meta.wearable", name = "mwdat-core", version.ref = "mwdat" }
mwdat-camera = { group = "com.meta.wearable", name = "mwdat-camera", version.ref = "mwdat" }
mwdat-mockdevice = { group = "com.meta.wearable", name = "mwdat-mockdevice", version.ref = "mwdat" }
mwdat-display = { group = "com.meta.wearable", name = "mwdat-display", version.ref = "mwdat" }
```

- [ ] **Step 2.2 — Wire manifest placeholders and the new dependencies in `android/app/build.gradle.kts`.** Replace the top of the file (the `import` + `plugins` block) with:

```kotlin
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Optional DAT credentials. Developer Mode (Meta AI app) accepts "0" for both, which is the default.
// To use production credentials, add to android/local.properties (git-ignored):
//   mwdat_application_id=<APPLICATION_ID from Wearables Developer Center>
//   mwdat_client_token=<CLIENT_TOKEN from Wearables Developer Center>
// or pass -Pmwdat_application_id=... -Pmwdat_client_token=... on the Gradle command line.
val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(::load)
        }
    }
```

Inside `defaultConfig { ... }`, after the `vectorDrawables { ... }` block, add:

```kotlin
        // Meta Wearables Device Access Toolkit attestation values (see localProperties above)
        manifestPlaceholders["mwdat_application_id"] =
            providers.gradleProperty("mwdat_application_id").orNull
                ?: localProperties.getProperty("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] =
            providers.gradleProperty("mwdat_client_token").orNull
                ?: localProperties.getProperty("mwdat_client_token", "0")
```

Replace the DAT dependency lines in `dependencies { ... }` with:

```kotlin
    // Meta Wearables DAT SDK
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)
    debugImplementation(libs.mwdat.mockdevice)
    // androidTest compiles against the debug variant; declare MockDeviceKit explicitly so the
    // instrumented tests (Task 9) do not depend on AGP's tested-variant classpath inheritance.
    androidTestImplementation(libs.mwdat.mockdevice)
```

- [ ] **Step 2.3 — Manifest.** Replace lines 5-21 (the permission block) of `android/app/src/main/AndroidManifest.xml` with:

```xml
    <!-- Permissions -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <!-- Phone camera: only used as a MockDeviceKit feed source in debug builds -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28"
        tools:ignore="ScopedStorage" />
    <!-- Foreground Service for Wake Word Detection -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <!-- Background Running -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

Replace the meta-data block (lines 33-36) with:

```xml
        <!-- Meta Wearables DAT Configuration.
             Values come from manifestPlaceholders in app/build.gradle.kts
             (local.properties mwdat_application_id / mwdat_client_token, default "0" = Developer Mode).
             DAM_ENABLED is intentionally NOT declared: DAM is always on in 0.9.0. -->
        <meta-data
            android:name="com.meta.wearable.mwdat.APPLICATION_ID"
            android:value="${mwdat_application_id}" />
        <meta-data
            android:name="com.meta.wearable.mwdat.CLIENT_TOKEN"
            android:value="${mwdat_client_token}" />
```

- [ ] **Step 2.4 — Initialize the SDK in the Application.** Replace `android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` with:

```kotlin
package com.smartview.glassai

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class TurboMetaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize the DAT SDK once per process, before any Activity, Service or ViewModel
        // touches Wearables APIs (the wake-word QuickVisionService can start without an Activity).
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e(TAG, "DAT SDK initialize failed: ${error.description}")
        }
    }

    companion object {
        private const val TAG = "TurboMetaApplication"

        lateinit var instance: TurboMetaApplication
            private set
    }
}
```

- [ ] **Step 2.5 — Stop initializing from `MainActivity`.** Replace lines 118-127 of `android/app/src/main/java/com/smartview/glassai/MainActivity.kt` (the `initializeSDK()` function) with:

```kotlin
    private fun initializeSDK() {
        if (sdkInitialized) return
        sdkInitialized = true

        // Wearables.initialize() already ran in TurboMetaApplication.onCreate().
        // Start observing Wearables state once the Bluetooth runtime permissions are granted.
        wearablesViewModel.startMonitoring()
    }
```

(The `import com.meta.wearable.dat.core.Wearables` line stays: it is still used by `Wearables.RequestPermissionContract()` at line 61.)

- [ ] **Step 2.6 — Document the optional keys.** Append to `android/README.md` (outer fence uses four backticks only because the snippet itself contains a fenced block; append the inner text verbatim):

````markdown

## DAT credentials (optional) | DAT 凭据（可选）

The build reads two optional keys from `android/local.properties` (git-ignored) and injects them into
`AndroidManifest.xml` as `com.meta.wearable.mwdat.APPLICATION_ID` / `CLIENT_TOKEN`.
When absent both default to `0`, which is what Meta AI **Developer Mode** expects.

```properties
mwdat_application_id=YOUR_APPLICATION_ID
mwdat_client_token=YOUR_CLIENT_TOKEN
```

构建会从 `android/local.properties` 读取这两个可选键并写入清单；未设置时默认为 `0`（Meta AI 开发者模式）。
````

- [ ] **Step 2.7 — Verify dependency resolution** (this is the acceptance check for this task):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:dependencies --configuration debugCompileClasspath | grep mwdat
```

Expected lines (order may vary):

```
+--- com.meta.wearable:mwdat-core:0.9.0
+--- com.meta.wearable:mwdat-camera:0.9.0
+--- com.meta.wearable:mwdat-display:0.9.0
+--- com.meta.wearable:mwdat-mockdevice:0.9.0
```

If resolution fails with `401`/`403` from `maven.pkg.github.com`, the `github_token` in `local.properties` lacks `read:packages`; ask the user to fix the token — do not print the file.

- [ ] **Step 2.8 — Confirm the expected compile failure (do not fix here).**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e: " | head -40
```

Expected: `BUILD FAILED` with unresolved references only in these four files (Tasks 4–5 fix them):
- `viewmodels/WearablesViewModel.kt`: `Unresolved reference 'StreamSession'` (line 12), `'startStreamSession'` (13), `'StreamSessionState'` (15), `Unresolved reference 'Unavailable'` (74), `'Registered'`/`'Available'`/`'Registering'`/`'Unregistering'` (148-162, 472), `Too many arguments`/`activeDevice` (126).
- `services/QuickVisionService.kt`: `'StreamSession'` (23), `'startStreamSession'` (24), `'StreamSessionState'` (26), `activeDevice` (210).
- `viewmodels/RTMPStreamingViewModel.kt`: `'StreamSession'` (12), `'startStreamSession'` (13), `'StreamSessionState'` (15).
- `ui/screens/RTMPStreamingScreen.kt`: `'StreamSessionState'` (36).

No errors may come from `MainActivity.kt`, `HomeScreen.kt`, `Navigation.kt`, `LiveAIScreen.kt`, `TurboMetaApplication.kt` — those still compile against 0.9.0 (type-only imports, `RequestPermissionContract`, `checkPermissionStatus`, `initialize` are unchanged).

- [ ] **Step 2.9 — Commit (build intentionally red until Task 5).**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt android/app/src/main/java/com/smartview/glassai/MainActivity.kt android/README.md
git commit -m "build(android): bump DAT SDK to 0.9.0 (+display, mockdevice debug), manifest placeholders, init SDK in Application

WIP: camera call sites still target the removed 0.4.0 StreamSession API; migrated in the next commits."
```

---

### Task 3: `GlassesSessionManager` + gateway interfaces + fakes + unit tests

**Files:**
- Create: `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionState.kt`
- Create: `android/app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt`
- Create: `android/app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt`
- Create: `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt`
- Create: `android/app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt`
- Create: `android/app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt`

**Interfaces:**

Consumes (0.9.0 SDK; verified against `$SAMPLE/.../camera/CameraViewModel.kt:156-215,270-330` and the marketplace `CHANGELOG.md` 0.7.0/0.9.0 entries):
- `Wearables.createSession(deviceSelector: DeviceSelector): DatResult<DeviceSession, DeviceSessionError>` — synchronous; `NO_ELIGIBLE_DEVICE` / `SESSION_ALREADY_EXISTS` on failure. **`SESSION_ALREADY_EXISTS` is returned while the SDK's previous session is still `STOPPING`**, which is why the manager keeps the outgoing session observed until `STOPPED` (the sample's `observeSession` only runs `cleanupSession()` on `STOPPED`, `CameraViewModel.kt:185-191`).
- `DeviceSession.state: StateFlow<DeviceSessionState>` (`IDLE, STARTING, STARTED, PAUSED, STOPPING, STOPPED`), `DeviceSession.errors: SharedFlow<DeviceSessionError>`, `fun start()` (sync fire-and-forget), `fun stop()` (transitions to `STOPPING`, later `STOPPED`; `STOPPED` is terminal — create a new session afterwards).
- Extension `DeviceSession.addCamera(streamConfiguration: StreamConfiguration): DatResult<Camera, DeviceSessionError>` (`import com.meta.wearable.dat.camera.addCamera`), only valid after `STARTED`.
- `Camera.stream: Stream`, `Camera.stop()`; `Stream.state: StateFlow<StreamState>`, `Stream.videoStream: Flow<VideoFrame>`, `Stream.errorStream: Flow<StreamError>`, `Stream.start(): DatResult<Unit, StreamError>`, `suspend Stream.capturePhoto(): DatResult<PhotoData, CaptureError>`.
- `DeviceSelector.activeDeviceFlow(): Flow<DeviceIdentifier?>`, `Wearables.devices: StateFlow<Set<DeviceIdentifier>>`, `Wearables.devicesMetadata: Map<DeviceIdentifier, StateFlow<Device>>`, `Device.name`, `Device.deviceType: DeviceType`, `Device.compatibility: DeviceCompatibility`, `Device.isDisplayCapable(): Boolean`.
- `DatResult.fold(onSuccess: (T) -> R, onFailure: (error: E, cause: Throwable?) -> R): R` (used exactly as in `$SAMPLE/.../mockdevicekit/MockDeviceKitViewModel.kt:55-74`).

Produces (all in package `com.smartview.glassai.glasses`):

```kotlin
data class GlassesDeviceInfo(val id: String, val name: String, val deviceType: DeviceType, val isDisplayCapable: Boolean, val compatibility: DeviceCompatibility)
enum class GlassesDisplayState { NOT_ATTACHED, STARTING, STARTED, STOPPED }
enum class SessionStartResult { STARTED, CREATE_FAILED, NOT_STARTED }
sealed class CameraError { data class CameraBusy(val owner: String); object NoSession; object SessionNotStarted; data class Sdk(val error: DeviceSessionError) }
sealed class CameraResult { data class Ready(val camera: GlassesCamera); data class Failed(val error: CameraError) }
sealed class SessionCreateResult { data class Success(val session: GlassesSession); data class Failure(val error: DeviceSessionError) }
sealed class CameraAddResult { data class Success(val camera: GlassesCamera); data class Failure(val error: DeviceSessionError) }
sealed class PhotoCaptureResult { data class Success(val photo: PhotoData); data class Failure(val error: CaptureError) }
interface GlassesCamera { val streamState: StateFlow<DatStreamState>; val videoFrames: Flow<VideoFrame>; val streamErrors: Flow<StreamError>; fun startStream(): StreamError?; suspend fun capturePhoto(): PhotoCaptureResult; fun stop() }
interface GlassesSession { val state: StateFlow<DeviceSessionState>; val errors: SharedFlow<DeviceSessionError>; val nativeSession: DeviceSession?; fun start(); fun stop(); fun addCamera(config: StreamConfiguration): CameraAddResult }
interface DatSessionFactory { fun createSession(): SessionCreateResult }
interface DatDeviceObserver { fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?> }
interface DisplayAttacher { val displayState: StateFlow<GlassesDisplayState>; fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?); fun detach(); object None : DisplayAttacher }
class GlassesSessionManager internal constructor(sessionFactory: DatSessionFactory, deviceObserver: DatDeviceObserver, scope: CoroutineScope, displayAttacher: DisplayAttacher = DisplayAttacher.None) {
    companion object { fun getInstance(context: Context): GlassesSessionManager }
    val sessionState: StateFlow<DeviceSessionState>; val displayState: StateFlow<GlassesDisplayState>
    val sessionError: SharedFlow<DeviceSessionError>; val activeDevice: StateFlow<GlassesDeviceInfo?>
    val isFirmwareUpdateRequired: StateFlow<Boolean>; val isDatAppUpdateRequired: StateFlow<Boolean>
    val hasSession: Boolean; val isStoppingPreviousSession: Boolean; val ownerCount: Int; val currentCameraOwner: String?
    fun startMonitoring(); fun acquire(owner: String); fun release(owner: String); fun ensureSession(): Boolean
    suspend fun ensureSessionStarted(timeoutMs: Long): SessionStartResult
    suspend fun awaitStarted(timeoutMs: Long): Boolean
    fun addCamera(owner: String, config: StreamConfiguration): CameraResult; fun stopCamera(owner: String); fun stopSession()
}
```

Design notes (spec §4, §5.4): our interfaces return Kotlin sealed results instead of `DatResult` so fakes never depend on the SDK's `DatResult` factory functions; real adapters convert with `fold`. All `GlassesSessionManager` methods must be called on the main thread (production scope is `Dispatchers.Main.immediate`; ViewModels and `QuickVisionService` already run there). `DisplayAttacher` is the Phase C extension point: Phase C implements it with `DeviceSession.addDisplay` through `GlassesSession.nativeSession` and injects it in `getInstance`; nothing else in the manager changes.

Session re-creation contract (fixes the leave-and-re-enter / disconnect-then-connect race): `stopSession()` moves the current session into `stoppingSession`, calls `stop()` and keeps a collector on it until it reports `STOPPED`. `acquire(owner)` registers the owner and creates a session synchronously only when no previous session is still stopping (fast path); every camera owner then calls `ensureSessionStarted(timeoutMs)` inside its coroutine, which (1) waits up to 5 s for the outgoing session's `STOPPED`, (2) creates the session if needed — retrying once after 1 s on `SESSION_ALREADY_EXISTS` — and (3) waits for `STARTED`. Only `ensureSessionStarted`'s final failure is emitted on `sessionError`.

- [ ] **Step 3.1 — Create the pure value types** `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionState.kt`:

```kotlin
package com.smartview.glassai.glasses

import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType

/** Metadata of the device currently chosen by the AutoDeviceSelector (spec §5.7). */
data class GlassesDeviceInfo(
    val id: String,
    val name: String,
    val deviceType: DeviceType,
    val isDisplayCapable: Boolean,
    val compatibility: DeviceCompatibility,
)

/** Display capability lifecycle as seen by the app. Phase A never leaves NOT_ATTACHED. */
enum class GlassesDisplayState { NOT_ATTACHED, STARTING, STARTED, STOPPED }

/** Result of [GlassesSessionManager.ensureSessionStarted]. */
enum class SessionStartResult {
    /** The session is STARTED; capabilities may be added. */
    STARTED,
    /** Wearables.createSession refused (NO_ELIGIBLE_DEVICE, SESSION_ALREADY_EXISTS after one retry, ...). */
    CREATE_FAILED,
    /** The session was created but reached STOPPED, or did not reach STARTED within the timeout. */
    NOT_STARTED,
}

/** Why [GlassesSessionManager.addCamera] could not lend the camera. */
sealed class CameraError {
    /** Another feature owner currently holds the single camera capability. */
    data class CameraBusy(val owner: String) : CameraError()
    /** No DeviceSession exists (acquire() failed or the device stopped it). */
    object NoSession : CameraError()
    /** The session exists but is not STARTED yet; wait with awaitStarted(). */
    object SessionNotStarted : CameraError()
    /** The SDK refused addCamera (CAPABILITY_DENIED, CAPABILITY_ALREADY_ADDED, ...). */
    data class Sdk(val error: DeviceSessionError) : CameraError()
}

sealed class CameraResult {
    data class Ready(val camera: GlassesCamera) : CameraResult()
    data class Failed(val error: CameraError) : CameraResult()
}
```

- [ ] **Step 3.2 — Create the gateway interfaces** `android/app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt`:

```kotlin
package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Thin seams over the DAT SDK statics so GlassesSessionManager can be unit-tested with fakes.
 * Real implementations live in WearablesDatAdapter.kt; fakes live in app/src/test/.../FakeDat.kt.
 */

sealed class SessionCreateResult {
    data class Success(val session: GlassesSession) : SessionCreateResult()
    data class Failure(val error: DeviceSessionError) : SessionCreateResult()
}

sealed class CameraAddResult {
    data class Success(val camera: GlassesCamera) : CameraAddResult()
    data class Failure(val error: DeviceSessionError) : CameraAddResult()
}

sealed class PhotoCaptureResult {
    data class Success(val photo: PhotoData) : PhotoCaptureResult()
    data class Failure(val error: CaptureError) : PhotoCaptureResult()
}

/** One camera capability lent to exactly one owner. Wraps Camera + Camera.stream. */
interface GlassesCamera {
    val streamState: StateFlow<DatStreamState>
    val videoFrames: Flow<VideoFrame>
    val streamErrors: Flow<StreamError>
    /** Starts the stream; returns null on success or the StreamError on failure. */
    fun startStream(): StreamError?
    suspend fun capturePhoto(): PhotoCaptureResult
    /** Stops the camera and detaches it from the session (required before a later addCamera). */
    fun stop()
}

/** One DeviceSession. Wraps com.meta.wearable.dat.core.session.DeviceSession. */
interface GlassesSession {
    val state: StateFlow<DeviceSessionState>
    val errors: SharedFlow<DeviceSessionError>
    /** The underlying SDK session (null in fakes). Phase C uses it for addDisplay(). */
    val nativeSession: DeviceSession?
    fun start()
    fun stop()
    fun addCamera(config: StreamConfiguration): CameraAddResult
}

interface DatSessionFactory {
    /** Wraps Wearables.createSession(deviceSelector). Synchronous. */
    fun createSession(): SessionCreateResult
}

interface DatDeviceObserver {
    /** Metadata of the selector's active device, null when none is connected. */
    fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?>
}

/**
 * Phase C extension point. GlassesSessionManager calls maybeAttach() every time the session
 * reaches STARTED and detach() before the session stops. Phase A ships only [None].
 */
interface DisplayAttacher {
    val displayState: StateFlow<GlassesDisplayState>
    fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?)
    fun detach()

    object None : DisplayAttacher {
        override val displayState: StateFlow<GlassesDisplayState> =
            MutableStateFlow(GlassesDisplayState.NOT_ATTACHED)
        override fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?) = Unit
        override fun detach() = Unit
    }
}
```

- [ ] **Step 3.3 — Create the real adapters** `android/app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt`:

```kotlin
package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Wraps an SDK Camera (and its Stream) behind [GlassesCamera]. */
class SdkGlassesCamera(private val camera: Camera) : GlassesCamera {
    override val streamState: StateFlow<DatStreamState>
        get() = camera.stream.state
    override val videoFrames: Flow<VideoFrame>
        get() = camera.stream.videoStream
    override val streamErrors: Flow<StreamError>
        get() = camera.stream.errorStream

    override fun startStream(): StreamError? =
        camera.stream.start().fold(
            onSuccess = { null },
            onFailure = { error, _ -> error },
        )

    override suspend fun capturePhoto(): PhotoCaptureResult =
        camera.stream.capturePhoto().fold(
            onSuccess = { PhotoCaptureResult.Success(it) },
            onFailure = { error, _ -> PhotoCaptureResult.Failure(error) },
        )

    override fun stop() {
        camera.stop()
    }
}

/** Wraps an SDK DeviceSession behind [GlassesSession]. */
class SdkGlassesSession(private val session: DeviceSession) : GlassesSession {
    override val state: StateFlow<DeviceSessionState>
        get() = session.state
    override val errors: SharedFlow<DeviceSessionError>
        get() = session.errors
    override val nativeSession: DeviceSession
        get() = session

    override fun start() = session.start()

    override fun stop() = session.stop()

    override fun addCamera(config: StreamConfiguration): CameraAddResult =
        session.addCamera(config).fold(
            onSuccess = { CameraAddResult.Success(SdkGlassesCamera(it)) },
            onFailure = { error, _ -> CameraAddResult.Failure(error) },
        )
}

/**
 * Real DAT gateway. Owns the single AutoDeviceSelector (spec §3 decision 7: no filter).
 * Requires Wearables.initialize() to have run (TurboMetaApplication.onCreate()).
 */
class WearablesDatAdapter(
    private val deviceSelector: DeviceSelector = AutoDeviceSelector(),
) : DatSessionFactory, DatDeviceObserver {

    override fun createSession(): SessionCreateResult =
        Wearables.createSession(deviceSelector).fold(
            onSuccess = { SessionCreateResult.Success(SdkGlassesSession(it)) },
            onFailure = { error, _ -> SessionCreateResult.Failure(error) },
        )

    /**
     * Re-evaluates devicesMetadata on every Wearables.devices change (like the 0.9.0 sample), so a
     * metadata entry that appears after the selector already emitted the id is picked up instead of
     * the flow settling on unknownDevice(id).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?> =
        deviceSelector.activeDeviceFlow()
            .combine(Wearables.devices) { id, _ -> id }
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(null)
                } else {
                    val metadata = Wearables.devicesMetadata[id]
                    if (metadata == null) {
                        flowOf(unknownDevice(id))
                    } else {
                        metadata.map { device -> device.toGlassesDeviceInfo(id) }
                    }
                }
            }

    private fun unknownDevice(id: DeviceIdentifier) = GlassesDeviceInfo(
        id = id.toString(),
        name = id.toString(),
        deviceType = DeviceType.UNKNOWN,
        isDisplayCapable = false,
        compatibility = DeviceCompatibility.UNDEFINED,
    )

    private fun Device.toGlassesDeviceInfo(id: DeviceIdentifier) = GlassesDeviceInfo(
        id = id.toString(),
        name = name.ifEmpty { id.toString() },
        deviceType = deviceType,
        isDisplayCapable = isDisplayCapable(),
        compatibility = compatibility,
    )
}
```

- [ ] **Step 3.4 — Write the failing unit tests first.** Create `android/app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt`:

```kotlin
package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeGlassesCamera : GlassesCamera {
    val stateFlow = MutableStateFlow(DatStreamState.STOPPED)
    val frames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 4)
    val errors = MutableSharedFlow<StreamError>(extraBufferCapacity = 4)
    var startCalls = 0
    var stopCalls = 0
    var startError: StreamError? = null
    var captureResult: PhotoCaptureResult = PhotoCaptureResult.Failure(CaptureError.NotStreaming)

    override val streamState: StateFlow<DatStreamState> = stateFlow
    override val videoFrames: Flow<VideoFrame> = frames
    override val streamErrors: Flow<StreamError> = errors

    override fun startStream(): StreamError? {
        startCalls++
        if (startError == null) stateFlow.value = DatStreamState.STARTING
        return startError
    }

    override suspend fun capturePhoto(): PhotoCaptureResult = captureResult

    override fun stop() {
        stopCalls++
        stateFlow.value = DatStreamState.STOPPED
    }
}

/**
 * @param stopAsync when true, stop() only transitions to STOPPING like the real 0.9.0 DeviceSession;
 *   the test must call emitStoppedByDevice() to finish the stop. When false (default) stop() lands
 *   on STOPPED synchronously.
 */
class FakeGlassesSession(private val stopAsync: Boolean = false) : GlassesSession {
    val stateFlow = MutableStateFlow(DeviceSessionState.IDLE)
    val errorFlow = MutableSharedFlow<DeviceSessionError>(extraBufferCapacity = 16)
    var startCalls = 0
    var stopCalls = 0
    var addCameraCalls = 0
    var nextAddCameraFailure: DeviceSessionError? = null
    /** Applied to every camera this session hands out (null = FakeGlassesCamera default). */
    var nextCaptureResult: PhotoCaptureResult? = null
    val cameras = mutableListOf<FakeGlassesCamera>()

    override val state: StateFlow<DeviceSessionState> = stateFlow
    override val errors: SharedFlow<DeviceSessionError> = errorFlow
    override val nativeSession: DeviceSession? = null

    override fun start() {
        startCalls++
        stateFlow.value = DeviceSessionState.STARTING
    }

    override fun stop() {
        stopCalls++
        stateFlow.value = if (stopAsync) DeviceSessionState.STOPPING else DeviceSessionState.STOPPED
    }

    override fun addCamera(config: StreamConfiguration): CameraAddResult {
        addCameraCalls++
        nextAddCameraFailure?.let { return CameraAddResult.Failure(it) }
        val camera = FakeGlassesCamera()
        nextCaptureResult?.let { camera.captureResult = it }
        cameras += camera
        return CameraAddResult.Success(camera)
    }

    /** Simulates the SDK reaching STARTED. */
    fun emitStarted() {
        stateFlow.value = DeviceSessionState.STARTED
    }

    /** Simulates the device ending the session (fold, tap-and-hold, Bluetooth loss). */
    fun emitStoppedByDevice() {
        stateFlow.value = DeviceSessionState.STOPPING
        stateFlow.value = DeviceSessionState.STOPPED
    }
}

class FakeDatSessionFactory : DatSessionFactory {
    var createCalls = 0
    /** Permanent failure (every call). */
    var failure: DeviceSessionError? = null
    /** One-shot failures consumed in order before [failure] is consulted. */
    val scriptedFailures = ArrayDeque<DeviceSessionError>()
    /** Sessions created from now on stop asynchronously (see FakeGlassesSession.stopAsync). */
    var stopAsync = false
    /** Capture result for cameras of sessions created from now on. */
    var nextCaptureResult: PhotoCaptureResult? = null
    val sessions = mutableListOf<FakeGlassesSession>()

    override fun createSession(): SessionCreateResult {
        createCalls++
        scriptedFailures.removeFirstOrNull()?.let { return SessionCreateResult.Failure(it) }
        failure?.let { return SessionCreateResult.Failure(it) }
        val session = FakeGlassesSession(stopAsync).also { it.nextCaptureResult = nextCaptureResult }
        sessions += session
        return SessionCreateResult.Success(session)
    }

    val last: FakeGlassesSession
        get() = sessions.last()
}

class FakeDatDeviceObserver : DatDeviceObserver {
    val device = MutableStateFlow<GlassesDeviceInfo?>(null)
    override fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?> = device
}

class RecordingDisplayAttacher : DisplayAttacher {
    val attachCalls = mutableListOf<GlassesDeviceInfo?>()
    var detachCalls = 0
    override val displayState = MutableStateFlow(GlassesDisplayState.NOT_ATTACHED)
    override fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?) {
        attachCalls += device
    }
    override fun detach() {
        detachCalls++
    }
}
```

Create `android/app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt`:

```kotlin
package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesSessionManagerTest {

    private val factory = FakeDatSessionFactory()
    private val observer = FakeDatDeviceObserver()
    private val attacher = RecordingDisplayAttacher()
    private val config = StreamConfiguration()

    private fun TestScope.newManager(displayAttacher: DisplayAttacher = attacher): GlassesSessionManager =
        GlassesSessionManager(
            sessionFactory = factory,
            deviceObserver = observer,
            scope = backgroundScope,
            displayAttacher = displayAttacher,
        ).also { it.startMonitoring() }

    private val rayban = GlassesDeviceInfo(
        id = "dev-1",
        name = "Ray-Ban Meta",
        deviceType = DeviceType.RAYBAN_META,
        isDisplayCapable = false,
        compatibility = DeviceCompatibility.COMPATIBLE,
    )

    @Test
    fun acquireCreatesAndStartsOneSession() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()

        manager.acquire("A")

        assertEquals(1, factory.createCalls)
        assertEquals(1, factory.last.startCalls)
        assertEquals(DeviceSessionState.STARTING, manager.sessionState.value)
        assertTrue(manager.hasSession)
        assertEquals(1, manager.ownerCount)
    }

    @Test
    fun secondOwnerSharesTheSession() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        manager.acquire("A") // idempotent per owner
        manager.acquire("B")

        assertEquals(1, factory.createCalls)
        assertEquals(2, manager.ownerCount)
    }

    @Test
    fun sessionStopsOnlyWhenLastOwnerReleases() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        manager.acquire("B")
        factory.last.emitStarted()

        manager.release("A")
        assertEquals(0, factory.last.stopCalls)
        assertEquals(DeviceSessionState.STARTED, manager.sessionState.value)

        manager.release("B")
        assertEquals(1, factory.last.stopCalls)
        assertEquals(DeviceSessionState.STOPPED, manager.sessionState.value)
        assertFalse(manager.hasSession)
        assertEquals(1, attacher.detachCalls)
    }

    @Test
    fun releaseOfUnknownOwnerIsNoOp() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")

        manager.release("ghost")

        assertEquals(1, manager.ownerCount)
        assertEquals(0, factory.last.stopCalls)
    }

    @Test
    fun createFailureIsReportedByEnsureSessionStartedOnly() = runTest(UnconfinedTestDispatcher()) {
        factory.failure = DeviceSessionError.NO_ELIGIBLE_DEVICE
        val manager = newManager()
        val errors = mutableListOf<DeviceSessionError>()
        backgroundScope.launch { manager.sessionError.collect { errors += it } }

        manager.acquire("A") // fast path fails silently; ensureSessionStarted() retries and reports

        assertEquals(1, factory.createCalls)
        assertTrue(errors.isEmpty())
        assertFalse(manager.hasSession)
        assertEquals(1, manager.ownerCount) // owner keeps its claim

        assertEquals(SessionStartResult.CREATE_FAILED, manager.ensureSessionStarted(1_000))

        assertEquals(2, factory.createCalls)
        assertEquals(listOf(DeviceSessionError.NO_ELIGIBLE_DEVICE), errors)
        assertEquals(DeviceSessionState.STOPPED, manager.sessionState.value)
        assertFalse(manager.hasSession)
    }

    @Test
    fun acquireAfterStopWaitsForStoppedBeforeCreatingSession() = runTest(UnconfinedTestDispatcher()) {
        factory.stopAsync = true
        val manager = newManager()
        manager.acquire("A")
        val first = factory.last
        first.emitStarted()

        manager.release("A") // last owner: stop() only reaches STOPPING (real SDK behaviour)
        assertEquals(DeviceSessionState.STOPPING, first.stateFlow.value)
        assertEquals(DeviceSessionState.STOPPING, manager.sessionState.value)
        assertFalse(manager.hasSession)
        assertTrue(manager.isStoppingPreviousSession)

        manager.acquire("A") // must NOT call createSession while the previous session is stopping
        assertEquals(1, factory.createCalls)

        val started = async { manager.ensureSessionStarted(5_000) }
        assertEquals(1, factory.createCalls) // still waiting for STOPPED

        first.emitStoppedByDevice() // the SDK finishes the stop
        assertFalse(manager.isStoppingPreviousSession)
        assertEquals(2, factory.createCalls)
        assertEquals(1, factory.last.startCalls)

        factory.last.emitStarted()
        assertEquals(SessionStartResult.STARTED, started.await())
        assertEquals(DeviceSessionState.STARTED, manager.sessionState.value)
    }

    @Test
    fun ensureSessionStartedGivesUpWaitingForStoppedAfterTimeout() = runTest(UnconfinedTestDispatcher()) {
        factory.stopAsync = true
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        manager.release("A")
        manager.acquire("A")

        val started = async { manager.ensureSessionStarted(5_000) }
        assertEquals(1, factory.createCalls)

        advanceTimeBy(5_001) // previous session never reports STOPPED
        assertFalse(manager.isStoppingPreviousSession)
        assertEquals(2, factory.createCalls)

        factory.last.emitStarted()
        assertEquals(SessionStartResult.STARTED, started.await())
    }

    @Test
    fun sessionAlreadyExistsIsRetriedOnceByEnsureSessionStarted() = runTest(UnconfinedTestDispatcher()) {
        factory.scriptedFailures += DeviceSessionError.SESSION_ALREADY_EXISTS // acquire() fast path
        factory.scriptedFailures += DeviceSessionError.SESSION_ALREADY_EXISTS // first ensureSessionStarted() attempt
        val manager = newManager()
        val errors = mutableListOf<DeviceSessionError>()
        backgroundScope.launch { manager.sessionError.collect { errors += it } }

        manager.acquire("A")
        val started = async { manager.ensureSessionStarted(5_000) }
        assertEquals(2, factory.createCalls) // retry is scheduled after a 1 s pause

        advanceTimeBy(1_001)
        assertEquals(3, factory.createCalls)
        assertTrue(manager.hasSession)

        factory.last.emitStarted()
        assertEquals(SessionStartResult.STARTED, started.await())
        assertTrue(errors.isEmpty()) // the retry succeeded, so nothing was reported
    }

    @Test
    fun ensureSessionStartedReportsNotStartedWhenSessionStopsOrTimesOut() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")

        val stoppedResult = async { manager.ensureSessionStarted(5_000) }
        factory.last.emitStoppedByDevice()
        assertEquals(SessionStartResult.NOT_STARTED, stoppedResult.await())

        manager.acquire("A") // recreated (2nd session) but never reaches STARTED
        val timedOut = async { manager.ensureSessionStarted(5_000) }
        advanceTimeBy(5_001)
        assertEquals(SessionStartResult.NOT_STARTED, timedOut.await())
        assertEquals(2, factory.createCalls)
    }

    @Test
    fun addCameraBeforeStartedFails() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")

        val result = manager.addCamera("A", config)

        assertEquals(CameraResult.Failed(CameraError.SessionNotStarted), result)
    }

    @Test
    fun addCameraWithoutSessionFails() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()

        val result = manager.addCamera("A", config)

        assertEquals(CameraResult.Failed(CameraError.NoSession), result)
    }

    @Test
    fun cameraIsLentToOneOwnerAndBusyForOthers() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        manager.acquire("B")
        factory.last.emitStarted()

        val first = manager.addCamera("A", config)
        assertTrue(first is CameraResult.Ready)
        assertEquals("A", manager.currentCameraOwner)

        val second = manager.addCamera("B", config)
        assertEquals(CameraResult.Failed(CameraError.CameraBusy("A")), second)
        assertEquals(1, factory.last.addCameraCalls)

        // Same owner asking again gets the same camera, no second SDK call.
        val again = manager.addCamera("A", config)
        assertSame((first as CameraResult.Ready).camera, (again as CameraResult.Ready).camera)
        assertEquals(1, factory.last.addCameraCalls)
    }

    @Test
    fun stopCameraDetachesAndLetsAnotherOwnerBorrow() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        manager.acquire("B")
        factory.last.emitStarted()
        manager.addCamera("A", config)

        manager.stopCamera("B") // not the holder: ignored
        assertEquals("A", manager.currentCameraOwner)

        manager.stopCamera("A")
        assertEquals(1, factory.last.cameras[0].stopCalls)
        assertNull(manager.currentCameraOwner)

        assertTrue(manager.addCamera("B", config) is CameraResult.Ready)
        assertEquals("B", manager.currentCameraOwner)
    }

    @Test
    fun releaseByCameraOwnerStopsTheCamera() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        manager.acquire("B")
        factory.last.emitStarted()
        manager.addCamera("A", config)

        manager.release("A")

        assertEquals(1, factory.last.cameras[0].stopCalls)
        assertNull(manager.currentCameraOwner)
        assertTrue(manager.hasSession) // B still holds the session
    }

    @Test
    fun sdkAddCameraFailureIsSurfaced() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        factory.last.nextAddCameraFailure = DeviceSessionError.CAPABILITY_DENIED

        val result = manager.addCamera("A", config)

        assertEquals(CameraResult.Failed(CameraError.Sdk(DeviceSessionError.CAPABILITY_DENIED)), result)
        assertNull(manager.currentCameraOwner)
    }

    @Test
    fun deviceStoppingSessionClearsStateAndNextAcquireRecreates() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)

        factory.last.emitStoppedByDevice()

        assertEquals(DeviceSessionState.STOPPED, manager.sessionState.value)
        assertFalse(manager.hasSession)
        assertNull(manager.currentCameraOwner)
        assertEquals(1, attacher.detachCalls)
        assertEquals(1, manager.ownerCount)

        manager.acquire("A") // no previous session is stopping (the device already reported STOPPED)
        assertEquals(2, factory.createCalls)
        assertTrue(manager.hasSession)
    }

    @Test
    fun sessionErrorsAreForwardedAndDatAppUpdateFlagged() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        val errors = mutableListOf<DeviceSessionError>()
        backgroundScope.launch { manager.sessionError.collect { errors += it } }
        manager.acquire("A")

        factory.last.errorFlow.tryEmit(DeviceSessionError.THERMAL_CRITICAL)
        assertFalse(manager.isDatAppUpdateRequired.value)

        factory.last.errorFlow.tryEmit(DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED)

        assertEquals(
            listOf(
                DeviceSessionError.THERMAL_CRITICAL,
                DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED,
            ),
            errors,
        )
        assertTrue(manager.isDatAppUpdateRequired.value)
    }

    @Test
    fun awaitStartedResolvesTrueOnStartedAndFalseOnStopped() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        assertFalse(manager.awaitStarted(1_000)) // no session at all

        manager.acquire("A")
        factory.last.emitStarted()
        assertTrue(manager.awaitStarted(1_000))

        factory.last.emitStoppedByDevice()
        manager.acquire("A")
        factory.last.emitStoppedByDevice()
        assertFalse(manager.awaitStarted(1_000))
    }

    @Test
    fun awaitStartedTimesOut() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A") // stays STARTING forever

        assertFalse(manager.awaitStarted(12_000))
    }

    @Test
    fun activeDeviceAndFirmwareFlagFollowObserver() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        assertNull(manager.activeDevice.value)
        assertFalse(manager.isFirmwareUpdateRequired.value)

        observer.device.value = rayban
        assertEquals(rayban, manager.activeDevice.value)
        assertFalse(manager.isFirmwareUpdateRequired.value)

        observer.device.value = rayban.copy(compatibility = DeviceCompatibility.DEVICE_UPDATE_REQUIRED)
        assertTrue(manager.isFirmwareUpdateRequired.value)

        observer.device.value = null
        assertNull(manager.activeDevice.value)
        assertFalse(manager.isFirmwareUpdateRequired.value)
    }

    @Test
    fun displayAttacherIsCalledOnStartedWithActiveDevice() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        val displayDevice = rayban.copy(
            deviceType = DeviceType.META_RAYBAN_DISPLAY,
            isDisplayCapable = true,
        )
        observer.device.value = displayDevice
        manager.acquire("A")
        assertTrue(attacher.attachCalls.isEmpty())

        factory.last.emitStarted()

        assertEquals(listOf<GlassesDeviceInfo?>(displayDevice), attacher.attachCalls)
        assertEquals(GlassesDisplayState.NOT_ATTACHED, manager.displayState.value)
    }

    /** Phase A ships DisplayAttacher.None: the display is never attached, even on a display-capable device. */
    @Test
    fun defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager(displayAttacher = DisplayAttacher.None)
        observer.device.value = rayban.copy(
            deviceType = DeviceType.META_RAYBAN_DISPLAY,
            isDisplayCapable = true,
        )
        manager.acquire("A")
        factory.last.emitStarted()
        assertEquals(GlassesDisplayState.NOT_ATTACHED, manager.displayState.value)

        manager.release("A")
        assertEquals(GlassesDisplayState.NOT_ATTACHED, manager.displayState.value)
        assertNull(factory.last.nativeSession) // fakes never expose an SDK session for addDisplay()
    }

    @Test
    fun stopSessionIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.stopSession()
        manager.acquire("A")
        manager.stopSession()
        manager.stopSession()

        assertEquals(1, factory.last.stopCalls)
        assertEquals(DeviceSessionState.STOPPED, manager.sessionState.value)
    }
}
```

- [ ] **Step 3.5 — Run the tests and watch them fail** (`GlassesSessionManager` does not exist yet):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesSessionManagerTest' 2>&1 | grep -E "^e: |BUILD" | head -20
```

Expected: `BUILD FAILED` at `:app:compileDebugKotlin` listing only the four files from Step 2.8. The test source set is **not** compiled at all here (`:app:testDebugUnitTest` depends on `compileDebugKotlin`, which fails first, so `compileDebugUnitTestKotlin` never runs and no `GlassesSessionManagerTest.kt` error is printed) — this red run only proves the module is still red. The test cannot go green before Task 5; the green run is the acceptance step of Task 5 (Step 5.9).

- [ ] **Step 3.6 — Implement the manager** `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt`:

```kotlin
package com.smartview.glassai.glasses

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single owner of the one DeviceSession the SDK allows per device (spec §4).
 *
 * - Reference counted: feature owners call [acquire]/[release]; the session stops when the last
 *   owner releases.
 * - Keeps the outgoing session observed until the SDK reports STOPPED and makes
 *   [ensureSessionStarted] wait for it before Wearables.createSession() (0.9.0: stop() only
 *   transitions to STOPPING; creating earlier fails with SESSION_ALREADY_EXISTS).
 * - Lends the single camera capability to one owner at a time ([addCamera] / [stopCamera]).
 * - Exposes session/display/device state as StateFlows and session errors as a SharedFlow.
 * - [DisplayAttacher] is the Phase C hook: attach on STARTED, detach before stop.
 *
 * Threading: every public function must be called on the main thread. The production scope is
 * Dispatchers.Main.immediate; unit tests inject a TestScope.
 */
class GlassesSessionManager internal constructor(
    private val sessionFactory: DatSessionFactory,
    private val deviceObserver: DatDeviceObserver,
    private val scope: CoroutineScope,
    private val displayAttacher: DisplayAttacher = DisplayAttacher.None,
) {
    companion object {
        private const val TAG = "GlassesSessionManager"

        /** How long [ensureSessionStarted] waits for the previous session to report STOPPED. */
        private const val PREVIOUS_STOP_TIMEOUT_MS = 5_000L

        /** Pause before the single retry when createSession() answers SESSION_ALREADY_EXISTS. */
        private const val ALREADY_EXISTS_RETRY_DELAY_MS = 1_000L

        @Volatile
        private var instance: GlassesSessionManager? = null

        /** Process singleton. Requires Wearables.initialize() (done in TurboMetaApplication). */
        fun getInstance(context: Context): GlassesSessionManager =
            instance ?: synchronized(this) {
                instance ?: run {
                    val adapter = WearablesDatAdapter()
                    GlassesSessionManager(
                        sessionFactory = adapter,
                        deviceObserver = adapter,
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                        displayAttacher = DisplayAttacher.None,
                    ).also { created ->
                        created.startMonitoring()
                        instance = created
                    }
                }
            }
    }

    private var session: GlassesSession? = null
    /** The session we last stopped, kept until the SDK reports STOPPED (or the wait times out). */
    private var stoppingSession: GlassesSession? = null
    private var stoppingJob: Job? = null
    private var camera: GlassesCamera? = null
    private var cameraOwner: String? = null
    private val owners = LinkedHashSet<String>()

    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var deviceJob: Job? = null

    private val _sessionState = MutableStateFlow(DeviceSessionState.STOPPED)
    /**
     * STOPPED while no session exists; IDLE immediately after a successful createSession; STOPPING
     * from stopSession() until the SDK reports STOPPED for the outgoing session.
     */
    val sessionState: StateFlow<DeviceSessionState> = _sessionState.asStateFlow()

    /** Display capability state (Phase C); always NOT_ATTACHED in Phase A. */
    val displayState: StateFlow<GlassesDisplayState>
        get() = displayAttacher.displayState

    private val _sessionError = MutableSharedFlow<DeviceSessionError>(extraBufferCapacity = 16)
    /** createSession failures and DeviceSession.errors, in order. */
    val sessionError: SharedFlow<DeviceSessionError> = _sessionError.asSharedFlow()

    private val _activeDevice = MutableStateFlow<GlassesDeviceInfo?>(null)
    /** Metadata of the AutoDeviceSelector's active device; null when none is connected. */
    val activeDevice: StateFlow<GlassesDeviceInfo?> = _activeDevice.asStateFlow()

    /** Device.compatibility == DEVICE_UPDATE_REQUIRED → offer Wearables.openFirmwareUpdate(). */
    val isFirmwareUpdateRequired: StateFlow<Boolean> =
        _activeDevice
            .map { it?.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _isDatAppUpdateRequired = MutableStateFlow(false)
    /** Set once DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED was seen → offer openDATGlassesAppUpdate(). */
    val isDatAppUpdateRequired: StateFlow<Boolean> = _isDatAppUpdateRequired.asStateFlow()

    val hasSession: Boolean
        get() = session != null

    /** True between stopSession() and the SDK's STOPPED for the outgoing session. */
    val isStoppingPreviousSession: Boolean
        get() = stoppingSession != null

    val ownerCount: Int
        get() = owners.size

    val currentCameraOwner: String?
        get() = cameraOwner

    /** Starts observing the active device. Idempotent. */
    fun startMonitoring() {
        if (deviceJob != null) return
        deviceJob = scope.launch {
            deviceObserver.activeDeviceInfoFlow().collect { info ->
                _activeDevice.value = info
            }
        }
    }

    /**
     * Registers [owner]. Fast path: when no session exists and no previous session is still
     * stopping, a session is created and started synchronously. Failures are NOT reported here —
     * owners that need the session call [ensureSessionStarted], which waits for the outgoing
     * session, retries once on SESSION_ALREADY_EXISTS and reports the final error.
     */
    fun acquire(owner: String) {
        owners.add(owner)
        Log.d(TAG, "acquire($owner) owners=$owners stoppingPrevious=${stoppingSession != null}")
        if (session == null && stoppingSession == null) {
            val error = createSessionIfNeeded()
            if (error != null) {
                Log.w(TAG, "acquire($owner): createSession failed (${error.description}); ensureSessionStarted() will retry")
            }
        }
    }

    /** Drops [owner]'s claim (and its camera); stops the session when nobody is left. */
    fun release(owner: String) {
        if (!owners.remove(owner)) return
        Log.d(TAG, "release($owner) owners=$owners")
        if (cameraOwner == owner) stopCamera(owner)
        if (owners.isEmpty()) stopSession()
    }

    /**
     * Synchronous create + start if no session exists (does not wait for a stopping session).
     * @return false if the SDK refused; the error is emitted on [sessionError].
     */
    fun ensureSession(): Boolean {
        val error = createSessionIfNeeded() ?: return true
        _sessionState.value = DeviceSessionState.STOPPED
        _sessionError.tryEmit(error)
        return false
    }

    /**
     * The call every camera owner makes after [acquire]:
     * 1. waits (≤ 5 s) for the previous session to report STOPPED,
     * 2. creates + starts a session if none exists, retrying once after 1 s on SESSION_ALREADY_EXISTS,
     * 3. waits up to [timeoutMs] for STARTED.
     * Only the final createSession failure is emitted on [sessionError].
     */
    suspend fun ensureSessionStarted(timeoutMs: Long): SessionStartResult {
        awaitPreviousSessionStopped()
        var error = createSessionIfNeeded()
        if (error == DeviceSessionError.SESSION_ALREADY_EXISTS) {
            Log.w(TAG, "SESSION_ALREADY_EXISTS: SDK still holds the previous session; retrying once")
            delay(ALREADY_EXISTS_RETRY_DELAY_MS)
            error = createSessionIfNeeded()
        }
        if (error != null) {
            Log.e(TAG, "ensureSessionStarted: createSession failed: ${error.description}")
            _sessionState.value = DeviceSessionState.STOPPED
            _sessionError.tryEmit(error)
            return SessionStartResult.CREATE_FAILED
        }
        return if (awaitStarted(timeoutMs)) SessionStartResult.STARTED else SessionStartResult.NOT_STARTED
    }

    /**
     * Suspends until the session is STARTED (true) or STOPPED / timed out / absent (false).
     * Safe to call right after [acquire]: the state is already IDLE or STARTING by then.
     */
    suspend fun awaitStarted(timeoutMs: Long): Boolean {
        if (session == null) return false
        val terminal = withTimeoutOrNull(timeoutMs) {
            sessionState.first {
                it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED
            }
        }
        return terminal == DeviceSessionState.STARTED
    }

    /** @return null when a session exists afterwards, else the SDK error. Never emits. */
    private fun createSessionIfNeeded(): DeviceSessionError? {
        if (session != null) return null
        return when (val result = sessionFactory.createSession()) {
            is SessionCreateResult.Success -> {
                val created = result.session
                session = created
                _sessionState.value = DeviceSessionState.IDLE
                // Subscribe before start() so no transition is missed.
                sessionStateJob = scope.launch {
                    created.state.collect { state -> onSessionState(created, state) }
                }
                sessionErrorJob = scope.launch {
                    created.errors.collect { error -> onSessionError(error) }
                }
                created.start()
                null
            }
            is SessionCreateResult.Failure -> {
                Log.e(TAG, "createSession failed: ${result.error.description}")
                result.error
            }
        }
    }

    /** Waits (bounded) for the outgoing session's STOPPED, then forgets it either way. */
    private suspend fun awaitPreviousSessionStopped() {
        val outgoing = stoppingSession ?: return
        val stopped = withTimeoutOrNull(PREVIOUS_STOP_TIMEOUT_MS) {
            outgoing.state.first { it == DeviceSessionState.STOPPED }
        } != null
        if (!stopped) {
            Log.w(TAG, "previous session did not report STOPPED within ${PREVIOUS_STOP_TIMEOUT_MS}ms; creating anyway")
        }
        clearStopping(outgoing)
    }

    private fun clearStopping(outgoing: GlassesSession) {
        if (stoppingSession !== outgoing) return
        stoppingSession = null
        stoppingJob?.cancel()
        stoppingJob = null
        if (session == null) _sessionState.value = DeviceSessionState.STOPPED
    }

    /** Lends the camera to [owner]. Must be called after the session is STARTED. */
    fun addCamera(owner: String, config: StreamConfiguration): CameraResult {
        val current = session ?: return CameraResult.Failed(CameraError.NoSession)
        if (_sessionState.value != DeviceSessionState.STARTED) {
            return CameraResult.Failed(CameraError.SessionNotStarted)
        }
        val holder = cameraOwner
        if (holder != null && holder != owner) {
            Log.w(TAG, "addCamera($owner) refused: camera held by $holder")
            return CameraResult.Failed(CameraError.CameraBusy(holder))
        }
        camera?.let { existing -> return CameraResult.Ready(existing) }
        return when (val result = current.addCamera(config)) {
            is CameraAddResult.Success -> {
                camera = result.camera
                cameraOwner = owner
                Log.d(TAG, "addCamera($owner) ok")
                CameraResult.Ready(result.camera)
            }
            is CameraAddResult.Failure -> {
                Log.e(TAG, "addCamera($owner) failed: ${result.error.description}")
                CameraResult.Failed(CameraError.Sdk(result.error))
            }
        }
    }

    /** Stops and detaches the camera if [owner] holds it; ignored otherwise. */
    fun stopCamera(owner: String) {
        if (cameraOwner != owner) {
            if (cameraOwner != null) Log.w(TAG, "stopCamera($owner) ignored: held by $cameraOwner")
            return
        }
        Log.d(TAG, "stopCamera($owner)")
        camera?.stop()
        camera = null
        cameraOwner = null
    }

    /**
     * Stops the session and all capabilities regardless of owners. Idempotent.
     * The outgoing session stays observed until the SDK reports STOPPED (see [ensureSessionStarted]).
     */
    fun stopSession() {
        val current = session ?: return
        Log.d(TAG, "stopSession")
        displayAttacher.detach()
        camera?.stop()
        camera = null
        cameraOwner = null
        cancelSessionJobs()
        session = null
        _sessionState.value = DeviceSessionState.STOPPING
        stoppingJob?.cancel()
        stoppingSession = current
        // Subscribe before stop(): a synchronous STOPPED must not be missed.
        stoppingJob = scope.launch {
            current.state.first { it == DeviceSessionState.STOPPED }
            Log.d(TAG, "previous session reported STOPPED")
            clearStopping(current)
        }
        current.stop()
    }

    private fun onSessionState(source: GlassesSession, state: DeviceSessionState) {
        if (source !== session) return
        Log.d(TAG, "session state: $state")
        _sessionState.value = state
        when (state) {
            DeviceSessionState.STARTED -> displayAttacher.maybeAttach(source, _activeDevice.value)
            DeviceSessionState.STOPPED -> teardownAfterDeviceStop()
            else -> Unit
        }
    }

    private fun onSessionError(error: DeviceSessionError) {
        Log.e(TAG, "session error: ${error.description}")
        if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
            _isDatAppUpdateRequired.value = true
        }
        _sessionError.tryEmit(error)
    }

    /** The SDK already stopped every capability; just drop our references. Owners keep their claims. */
    private fun teardownAfterDeviceStop() {
        displayAttacher.detach()
        camera = null
        cameraOwner = null
        cancelSessionJobs()
        session = null
        _sessionState.value = DeviceSessionState.STOPPED
    }

    private fun cancelSessionJobs() {
        sessionStateJob?.cancel()
        sessionStateJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
    }
}
```

- [ ] **Step 3.7 — Verify the new package compiles cleanly** (the module as a whole still fails on the four unmigrated files; only errors inside `glasses/` count here):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e: " | grep -c "glassai/glasses/"
```

Expected output: `0` (no compiler error mentions a file under `glasses/`). If the count is non-zero, read the listed lines: the only legitimate causes are a wrong SDK member name (compare against `$SAMPLE/.../camera/CameraViewModel.kt` for `createSession`/`addCamera`/`stream.*` and `$SAMPLE/.../wearables/WearablesViewModel.kt` for `devicesMetadata`/`activeDeviceFlow`) — fix the adapter, not the interfaces.

- [ ] **Step 3.8 — Commit.**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/app/src/main/java/com/smartview/glassai/glasses android/app/src/test/java/com/smartview/glassai/glasses
git commit -m "feat(android): add GlassesSessionManager (ref-counted DeviceSession owner, waits for STOPPED before re-create) with DAT gateway seams, fakes and unit tests

Unit tests compile but cannot run until the remaining 0.4.0 call sites are migrated (single module)."
```

---

### Task 4: Migrate `WearablesViewModel` + `HomeScreen` (registration with `Activity`, device metadata, stream via manager)

**Files:**
- Rewrite: `android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` (whole file, currently 492 lines)
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` (imports lines 3-41; state block lines 54-59 from `val context = LocalContext.current` to `val hasActiveDevice by ...`; device-required dialog line 168; `DeviceStatusCard(...)` call lines 253-259 including its closing `)`; `DeviceStatusCard` composable lines 577-715)
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/screens/QuickVisionScreen.kt` (line 185, the `while (streamState !is ... && streamWait < 50)` loop)
- Modify: `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-zh-rCN/strings.xml` (append before `</resources>`)
- `MainActivity.kt`: no change in this task (SDK init moved in Task 2; permission trimming is Task 7).

**Interfaces:**

Consumes: `GlassesSessionManager` (Task 3), `Wearables.registrationState: StateFlow<RegistrationState>` (enum `UNAVAILABLE, AVAILABLE, UNREGISTERING, REGISTERED, REGISTERING`), `Wearables.registrationErrorStream: Flow<RegistrationError>` (hot, no replay), `Wearables.devices: StateFlow<Set<DeviceIdentifier>>`, `Wearables.startRegistration(activity: Activity)`, `Wearables.startUnregistration(activity: Activity)`, `Wearables.openFirmwareUpdate(activity): DatResult<Unit, NavigationError>`, `Wearables.openDATGlassesAppUpdate(activity): DatResult<Unit, NavigationError>`, `Wearables.checkPermissionStatus(Permission): DatResult<PermissionStatus, PermissionError>`, `PhotoData.Bitmap(bitmap)`, `PhotoData.HEIC(data: ByteBuffer)`, `error.getLocalizedDescription(context)` on every DAT error enum/object.

Produces (public contract kept for all screens; additions marked *new*):

```kotlin
class WearablesViewModel(application: Application) : AndroidViewModel(application) {
    sealed class ConnectionState { Disconnected; Searching; Connecting; Registered(deviceName); Connected(deviceName); Error(message) }
    sealed class StreamState { Stopped; Waiting; Streaming; Paused /*new*/; Error(message) }
    val connectionState: StateFlow<ConnectionState>; val registrationState: StateFlow<RegistrationState>
    val streamState: StateFlow<StreamState>; val currentFrame: StateFlow<Bitmap?>; val capturedPhoto: StateFlow<Bitmap?>
    val batteryLevel: StateFlow<Int?>; val devices: StateFlow<List<DeviceIdentifier>>; val hasActiveDevice: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>; val isStreaming: StateFlow<Boolean>
    val activeDevice: StateFlow<GlassesDeviceInfo?> /*new*/; val isFirmwareUpdateRequired: StateFlow<Boolean> /*new*/; val isDatAppUpdateRequired: StateFlow<Boolean> /*new*/
    var onFrameReceived: ((Bitmap) -> Unit)?; var onPhotoTaken: ((Bitmap) -> Unit)?
    fun startMonitoring(); fun startDeviceSearch(activity: Activity); fun stopDeviceSearch()
    fun startRegistration(activity: Activity); fun startUnregistration(activity: Activity); fun disconnect(activity: Activity)
    fun openFirmwareUpdate(activity: Activity) /*new*/; fun openDATGlassesAppUpdate(activity: Activity) /*new*/
    fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus); fun navigateToDeviceSelection()
    suspend fun checkCameraPermission(): Boolean
    fun startStream(); fun stopStream(); fun takePhoto(): Bitmap?; fun clearCapturedPhoto(); fun clearError(); fun setError(message: String)
    val isRegistered: Boolean
}
```

`StreamState.Paused` is declared now (so screens compile against it) but only *emitted* from Task 6; in this task `DatStreamState.PAUSED` maps to `Waiting` exactly like today's `else` branch. Frame decoding stays on the main thread in this task; Task 6 moves it.

- [ ] **Step 4.1 — Add the strings.** Append before `</resources>` in `android/app/src/main/res/values/strings.xml`:

```xml

    <!-- Phase A: glasses session (DAT 0.9.0) -->
    <string name="error_activity_unavailable">Can\'t open right now. Try again.</string>
    <string name="update_firmware">Update firmware</string>
    <string name="update_dat_app">Update glasses app</string>
    <string name="update_required_firmware">Your glasses need a firmware update before they can be used.</string>
    <string name="update_required_dat_app">The Device Access Toolkit app on your glasses needs an update.</string>
    <string name="glasses_session_failed">Could not start the glasses session</string>
    <string name="glasses_session_timeout">Timed out waiting for the glasses</string>
    <string name="glasses_camera_busy">The glasses camera is being used by another feature</string>
    <string name="glasses_no_session">No active glasses session</string>
    <string name="photo_capture_failed">Photo capture failed</string>
```

Append before `</resources>` in `android/app/src/main/res/values-zh-rCN/strings.xml`:

```xml

    <!-- Phase A: glasses session (DAT 0.9.0) -->
    <string name="error_activity_unavailable">暂时无法打开，请重试</string>
    <string name="update_firmware">更新固件</string>
    <string name="update_dat_app">更新眼镜端应用</string>
    <string name="update_required_firmware">眼镜需要先更新固件才能使用</string>
    <string name="update_required_dat_app">眼镜上的 Device Access Toolkit 应用需要更新</string>
    <string name="glasses_session_failed">无法启动眼镜会话</string>
    <string name="glasses_session_timeout">等待眼镜响应超时</string>
    <string name="glasses_camera_busy">眼镜相机正被其他功能占用</string>
    <string name="glasses_no_session">眼镜会话未建立</string>
    <string name="photo_capture_failed">拍照失败</string>
```

- [ ] **Step 4.2 — Replace `WearablesViewModel.kt` entirely** with:

```kotlin
package com.smartview.glassai.viewmodels

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesDeviceInfo
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.PhotoCaptureResult
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * WearablesViewModel - UI-facing DAT SDK façade (DAT 0.9.0).
 *
 * - Registration / unregistration (needs a real Activity: the 0.4.0 code passed the Application
 *   and crashed with ClassCastException).
 * - Device discovery + active-device metadata via GlassesSessionManager.
 * - Camera streaming borrowed from the shared GlassesSessionManager (one DeviceSession per device).
 *
 * The public contract (StreamState sealed class, currentFrame, startStream/stopStream/takePhoto,
 * capturedPhoto, hasActiveDevice, connectionState, isRegistered) is unchanged for the screens.
 */
class WearablesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "WearablesViewModel"
        private const val OWNER = "WearablesViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        private const val FRAME_RATE = 24
    }

    // Connection states
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Searching : ConnectionState()
        object Connecting : ConnectionState()
        data class Registered(val deviceName: String) : ConnectionState() // Device registered but may not be actively connected
        data class Connected(val deviceName: String) : ConnectionState() // Device is actively connected and ready
        data class Error(val message: String) : ConnectionState()
    }

    // Streaming status (app-level; the SDK's StreamState is imported as DatStreamState)
    sealed class StreamState {
        object Stopped : StreamState()
        object Waiting : StreamState()  // starting, stopping
        object Streaming : StreamState()
        object Paused : StreamState()   // paused by a cap-touch tap on the glasses
        data class Error(val message: String) : StreamState()
    }

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _registrationState = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Stopped)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    private val _capturedPhoto = MutableStateFlow<Bitmap?>(null)
    val capturedPhoto: StateFlow<Bitmap?> = _capturedPhoto.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceIdentifier>>(emptyList())
    val devices: StateFlow<List<DeviceIdentifier>> = _devices.asStateFlow()

    private val _hasActiveDevice = MutableStateFlow(false)
    val hasActiveDevice: StateFlow<Boolean> = _hasActiveDevice.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // Shared session owner (spec §4)
    private val sessionManager: GlassesSessionManager by lazy {
        GlassesSessionManager.getInstance(getApplication())
    }

    /** Active device metadata: name, type, display capability, compatibility (spec §5.7). */
    val activeDevice: StateFlow<GlassesDeviceInfo?>
        get() = sessionManager.activeDevice

    /** Device.compatibility == DEVICE_UPDATE_REQUIRED -> show "Update firmware". */
    val isFirmwareUpdateRequired: StateFlow<Boolean>
        get() = sessionManager.isFirmwareUpdateRequired

    /** DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED seen -> show "Update glasses app". */
    val isDatAppUpdateRequired: StateFlow<Boolean>
        get() = sessionManager.isDatAppUpdateRequired

    // Borrowed camera (null when not streaming)
    private var camera: GlassesCamera? = null

    // Coroutine jobs for stream management
    private var startJob: Job? = null
    private var videoJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var deviceSelectorJob: Job? = null
    private var monitoringStarted = false

    // Callbacks for external use
    var onFrameReceived: ((Bitmap) -> Unit)? = null
    var onPhotoTaken: ((Bitmap) -> Unit)? = null

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        Log.d(TAG, "Starting monitoring")

        // 1. Registration errors FIRST: registrationErrorStream is hot with no replay, so it must be
        //    collected before the user can tap Connect.
        viewModelScope.launch {
            Wearables.registrationErrorStream.collect { error ->
                Log.e(TAG, "Registration error: ${error.description}")
                setError(error.getLocalizedDescription(getApplication()))
                if (_connectionState.value is ConnectionState.Searching ||
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        // 2. Registration state (plain enum in 0.9.0)
        viewModelScope.launch {
            Wearables.registrationState.collect { state ->
                Log.d(TAG, "Registration state changed: $state")
                _registrationState.value = state
                when (state) {
                    RegistrationState.REGISTERED -> Log.d(TAG, "Device registered")
                    RegistrationState.UNAVAILABLE -> {
                        Log.d(TAG, "Registration unavailable")
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    RegistrationState.AVAILABLE -> {
                        Log.d(TAG, "Registration available")
                        if (_connectionState.value is ConnectionState.Connecting) {
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }
                    RegistrationState.REGISTERING -> {
                        Log.d(TAG, "Registering...")
                        _connectionState.value = ConnectionState.Connecting
                    }
                    RegistrationState.UNREGISTERING -> Log.d(TAG, "Unregistering...")
                }
            }
        }

        // 3. Available devices
        viewModelScope.launch {
            Wearables.devices.collect { deviceSet ->
                Log.d(TAG, "Devices changed: ${deviceSet.size} devices")
                _devices.value = deviceSet.toList()
            }
        }

        // 4. Active device (AutoDeviceSelector + devicesMetadata, via the session manager)
        deviceSelectorJob = viewModelScope.launch {
            sessionManager.activeDevice.collect { info ->
                Log.d(TAG, "Active device: ${info?.name ?: "none"} (${info?.deviceType})")
                _hasActiveDevice.value = info != null

                if (info != null) {
                    if (_connectionState.value !is ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Registered(info.name)
                    }
                } else if (_connectionState.value is ConnectionState.Connected ||
                    _connectionState.value is ConnectionState.Registered
                ) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        // 5. Session errors (createSession failures + DeviceSession.errors)
        viewModelScope.launch {
            sessionManager.sessionError.collect { error ->
                setError(error.getLocalizedDescription(getApplication()))
            }
        }
    }

    fun startDeviceSearch(activity: Activity) {
        Log.d(TAG, "Starting device search")
        _connectionState.value = ConnectionState.Searching
        startRegistration(activity)
    }

    fun stopDeviceSearch() {
        if (_connectionState.value is ConnectionState.Searching) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun startRegistration(activity: Activity) {
        Log.d(TAG, "Starting registration")
        Wearables.startRegistration(activity)
    }

    fun startUnregistration(activity: Activity) {
        Log.d(TAG, "Starting unregistration")
        Wearables.startUnregistration(activity)
    }

    fun disconnect(activity: Activity) {
        viewModelScope.launch {
            stopStream()
            sessionManager.stopSession()
            startUnregistration(activity)
            _connectionState.value = ConnectionState.Disconnected
            _batteryLevel.value = null
        }
    }

    /** Opens the Meta AI app's firmware update flow (Device.compatibility == DEVICE_UPDATE_REQUIRED). */
    fun openFirmwareUpdate(activity: Activity) {
        Wearables.openFirmwareUpdate(activity).onFailure { error, _ ->
            // NavigationError is not part of GlassesErrorMessages (spec §5.9 covers Stream/Session errors);
            // the SDK's own localized text is used so the message still follows the device language.
            setError(error.getLocalizedDescription(getApplication()))
        }
    }

    /** Opens the Meta AI app's DAT-glasses-app update flow (DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED). */
    fun openDATGlassesAppUpdate(activity: Activity) {
        Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ ->
            setError(error.getLocalizedDescription(getApplication()))
        }
    }

    // Navigate to streaming (check permission first)
    fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
        viewModelScope.launch {
            val permission = Permission.CAMERA
            val result = Wearables.checkPermissionStatus(permission)

            result.onFailure { error, _ ->
                setError("Permission check error: ${error.description}")
                return@launch
            }

            val permissionStatus = result.getOrNull()
            if (permissionStatus == PermissionStatus.Granted) {
                _isStreaming.value = true
                return@launch
            }

            // Request permission
            when (onRequestWearablesPermission(permission)) {
                PermissionStatus.Denied -> setError("Permission denied")
                PermissionStatus.Granted -> _isStreaming.value = true
            }
        }
    }

    fun navigateToDeviceSelection() {
        _isStreaming.value = false
    }

    // Streaming
    suspend fun checkCameraPermission(): Boolean {
        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        return result.getOrNull() == PermissionStatus.Granted
    }

    /**
     * Start streaming from the wearable device camera through the shared session:
     * acquire -> ensureSessionStarted (waits for a previous session's STOPPED, creates, waits STARTED)
     * -> addCamera -> subscribe -> stream.start().
     */
    fun startStream() {
        Log.d(TAG, "startStream START")

        cancelStreamJobs()
        camera = null
        sessionManager.stopCamera(OWNER)

        // Reset state
        _currentFrame.value = null
        _streamState.value = StreamState.Waiting

        // Get saved video quality setting
        val savedQuality = APIKeyManager.getInstance(getApplication()).getVideoQuality()
        val videoQuality = when (savedQuality) {
            "LOW" -> VideoQuality.LOW
            "HIGH" -> VideoQuality.HIGH
            else -> VideoQuality.MEDIUM
        }
        Log.d(TAG, "Using video quality: $savedQuality")

        startJob = viewModelScope.launch {
            sessionManager.acquire(OWNER)
            // Leave-and-re-enter: the previous session may still be STOPPING in the SDK, so the
            // create happens inside ensureSessionStarted() once STOPPED has been observed.
            when (sessionManager.ensureSessionStarted(SESSION_START_TIMEOUT_MS)) {
                SessionStartResult.STARTED -> Unit
                SessionStartResult.CREATE_FAILED -> {
                    Log.e(TAG, "createSession failed")
                    _streamState.value = StreamState.Error(
                        getApplication<Application>().getString(R.string.glasses_session_failed)
                    )
                    sessionManager.release(OWNER)
                    return@launch
                }
                SessionStartResult.NOT_STARTED -> {
                    Log.e(TAG, "session did not reach STARTED")
                    _streamState.value = StreamState.Error(
                        getApplication<Application>().getString(R.string.glasses_session_timeout)
                    )
                    sessionManager.release(OWNER)
                    return@launch
                }
            }
            val config = StreamConfiguration(videoQuality = videoQuality, frameRate = FRAME_RATE)
            when (val result = sessionManager.addCamera(OWNER, config)) {
                is CameraResult.Ready -> attachCamera(result.camera)
                is CameraResult.Failed -> {
                    Log.e(TAG, "addCamera failed: ${result.error}")
                    _streamState.value = StreamState.Error(cameraErrorMessage(result.error))
                    sessionManager.release(OWNER)
                }
            }
        }

        Log.d(TAG, "startStream END")
    }

    private fun attachCamera(borrowed: GlassesCamera) {
        camera = borrowed

        // Subscribe BEFORE start(): streamState is a StateFlow that replays STOPPED.
        videoJob = viewModelScope.launch {
            Log.d(TAG, "Starting video frame collection")
            borrowed.videoFrames.collect { videoFrame ->
                if (videoFrame.isCompressed || videoFrame.isCodecConfig) return@collect
                handleVideoFrame(videoFrame)
            }
        }

        streamStateJob = viewModelScope.launch {
            var hasBeenActive = false
            borrowed.streamState.collect { currentState ->
                Log.d(TAG, "Stream state: $currentState")
                when (currentState) {
                    DatStreamState.STREAMING -> {
                        hasBeenActive = true
                        _streamState.value = StreamState.Streaming
                        // Upgrade connection state to Connected when streaming confirmed
                        val currentConnection = _connectionState.value
                        if (currentConnection is ConnectionState.Registered) {
                            _connectionState.value = ConnectionState.Connected(currentConnection.deviceName)
                            Log.d(TAG, "Upgraded to Connected (streaming confirmed)")
                        }
                    }
                    DatStreamState.STARTING,
                    DatStreamState.STARTED,
                    DatStreamState.STOPPING,
                    DatStreamState.PAUSED -> {
                        hasBeenActive = true
                        _streamState.value = StreamState.Waiting
                    }
                    DatStreamState.STOPPED,
                    DatStreamState.CLOSED -> {
                        if (hasBeenActive) {
                            hasBeenActive = false
                            Log.d(TAG, "Stream terminated, calling stopStream()")
                            stopStream()
                        }
                    }
                }
            }
        }

        streamErrorJob = viewModelScope.launch {
            borrowed.streamErrors.collect { error ->
                Log.e(TAG, "Stream error: ${error.description}")
                setError(error.getLocalizedDescription(getApplication()))
            }
        }

        val startError = borrowed.startStream()
        if (startError != null) {
            Log.e(TAG, "stream.start failed: ${startError.description}")
            _streamState.value = StreamState.Error(startError.getLocalizedDescription(getApplication()))
            stopStream()
        }
    }

    /**
     * Stop streaming and give the camera + session claim back to the manager.
     */
    fun stopStream() {
        Log.d(TAG, "stopStream START")

        cancelStreamJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)

        // Clear frame (let GC handle bitmap)
        _currentFrame.value = null
        _streamState.value = StreamState.Stopped

        // Downgrade connection state
        val currentConnection = _connectionState.value
        if (currentConnection is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Registered(currentConnection.deviceName)
            Log.d(TAG, "Downgraded to Registered (stream stopped)")
        }

        Log.d(TAG, "stopStream END")
    }

    private fun cancelStreamJobs() {
        startJob?.cancel()
        startJob = null
        videoJob?.cancel()
        videoJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
    }

    private fun cameraErrorMessage(error: CameraError): String {
        val app = getApplication<Application>()
        return when (error) {
            is CameraError.CameraBusy -> app.getString(R.string.glasses_camera_busy)
            CameraError.NoSession -> app.getString(R.string.glasses_no_session)
            CameraError.SessionNotStarted -> app.getString(R.string.glasses_session_timeout)
            is CameraError.Sdk -> error.error.getLocalizedDescription(app)
        }
    }

    /**
     * Capture a photo from the stream (DatResult<PhotoData, CaptureError> in 0.9.0).
     * Returns the previously captured photo synchronously; the new one lands in [capturedPhoto].
     */
    fun takePhoto(): Bitmap? {
        val activeCamera = camera
        if (activeCamera == null || _streamState.value != StreamState.Streaming) {
            Log.w(TAG, "Cannot take photo: not streaming")
            return null
        }

        viewModelScope.launch {
            Log.d(TAG, "Capturing photo...")
            when (val result = activeCamera.capturePhoto()) {
                is PhotoCaptureResult.Success -> {
                    val bitmap = withContext(Dispatchers.Default) { decodePhoto(result.photo) }
                    if (bitmap == null) {
                        Log.e(TAG, "Photo decode failed")
                        _errorMessage.value = getApplication<Application>().getString(R.string.photo_capture_failed)
                    } else {
                        Log.d(TAG, "Photo captured: ${bitmap.width}x${bitmap.height}")
                        _capturedPhoto.value = bitmap
                        onPhotoTaken?.invoke(bitmap)
                    }
                }
                is PhotoCaptureResult.Failure -> {
                    Log.e(TAG, "Photo capture failed: ${result.error.description}")
                    _errorMessage.value = result.error.getLocalizedDescription(getApplication())
                }
            }
        }
        return _capturedPhoto.value
    }

    private fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buffer = photo.data.duplicate().apply { rewind() }
            val byteArray = ByteArray(buffer.remaining())
            buffer.get(byteArray)
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
        }
    }

    /**
     * Handle incoming (uncompressed YUV, treated as I420) video frames.
     */
    private fun handleVideoFrame(videoFrame: VideoFrame) {
        try {
            val buffer = videoFrame.buffer
            val dataSize = buffer.remaining()
            val byteArray = ByteArray(dataSize)

            // Save current position
            val originalPosition = buffer.position()
            buffer.get(byteArray)
            // Restore position
            buffer.position(originalPosition)

            // Convert I420 to NV21 format
            val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
            val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)

            val jpegBytes = ByteArrayOutputStream().use { stream ->
                image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
                stream.toByteArray()
            }

            val newBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

            _currentFrame.value = newBitmap
            onFrameReceived?.invoke(newBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling video frame: ${e.message}")
        }
    }

    // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
    private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4

        input.copyInto(output, 0, 0, size) // Y is the same

        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
    }

    fun clearCapturedPhoto() {
        _capturedPhoto.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setError(message: String) {
        _errorMessage.value = message
    }

    // Check if registered with Meta AI app (UNREGISTERING still counts as registered, like the sample)
    val isRegistered: Boolean
        get() = _registrationState.value == RegistrationState.REGISTERED ||
            _registrationState.value == RegistrationState.UNREGISTERING

    override fun onCleared() {
        Log.d(TAG, "onCleared START - cleaning up all resources")
        super.onCleared()

        stopStream()

        deviceSelectorJob?.cancel()
        deviceSelectorJob = null
        monitoringStarted = false

        Log.d(TAG, "onCleared END - cleanup complete")
    }
}
```

- [ ] **Step 4.3 — HomeScreen: thread the Activity and add the update buttons.**

(a) Add these imports to `HomeScreen.kt` (after line 2 `package ...`, keep the existing ones):

```kotlin
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.LocalActivity
```

(b) Replace lines 54-59 (from `val context = LocalContext.current` through `val hasActiveDevice by wearablesViewModel.hasActiveDevice.collectAsState()`) with:

```kotlin
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val apiKeyManager = remember { APIKeyManager.getInstance(context) }
    val connectionState by wearablesViewModel.connectionState.collectAsState()
    val hasActiveDevice by wearablesViewModel.hasActiveDevice.collectAsState()
    val isFirmwareUpdateRequired by wearablesViewModel.isFirmwareUpdateRequired.collectAsState()
    val isDatAppUpdateRequired by wearablesViewModel.isDatAppUpdateRequired.collectAsState()

    // Registration / update flows need a real Activity (LocalActivity: activity-compose >= 1.10)
    val activity = LocalActivity.current
    val activityUnavailableText = stringResource(R.string.error_activity_unavailable)
    fun withActivity(block: (Activity) -> Unit) {
        val current = activity
        if (current == null) {
            Toast.makeText(context, activityUnavailableText, Toast.LENGTH_SHORT).show()
        } else {
            block(current)
        }
    }
```

(c) In the device-required dialog, replace line 168 `wearablesViewModel.startDeviceSearch()` with:

```kotlin
                        withActivity { wearablesViewModel.startDeviceSearch(it) }
```

(d) Replace the `DeviceStatusCard(...)` call (lines 253-259, from `// Device Connection Card` through the closing `)` on line 259) with:

```kotlin
            // Device Connection Card
            DeviceStatusCard(
                connectionState = connectionState,
                isFirmwareUpdateRequired = isFirmwareUpdateRequired,
                isDatAppUpdateRequired = isDatAppUpdateRequired,
                onConnect = { withActivity { wearablesViewModel.startDeviceSearch(it) } },
                onDisconnect = { withActivity { wearablesViewModel.disconnect(it) } },
                onUpdateFirmware = { withActivity { wearablesViewModel.openFirmwareUpdate(it) } },
                onUpdateDatApp = { withActivity { wearablesViewModel.openDATGlassesAppUpdate(it) } },
                modifier = Modifier.padding(horizontal = AppSpacing.large)
            )
```

(e) Replace the whole `private fun DeviceStatusCard(...)` composable (lines 577-715, to the end of file) with:

```kotlin
@Composable
private fun DeviceStatusCard(
    connectionState: WearablesViewModel.ConnectionState,
    isFirmwareUpdateRequired: Boolean,
    isDatAppUpdateRequired: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUpdateFirmware: () -> Unit,
    onUpdateDatApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    // iOS doesn't distinguish between Registered and Connected on home screen
    // Both states mean the device is available - show as "Connected"
    val isConnected = connectionState is WearablesViewModel.ConnectionState.Connected
    val isRegistered = connectionState is WearablesViewModel.ConnectionState.Registered
    val isSearching = connectionState is WearablesViewModel.ConnectionState.Searching
    val isConnecting = connectionState is WearablesViewModel.ConnectionState.Connecting
    val hasDevice = isConnected || isRegistered

    // Treat both Registered and Connected as "connected" for UI purposes (matching iOS)
    val showAsConnected = hasDevice

    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Disconnect confirmation dialog
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.settings_disconnect)) },
            text = { Text(stringResource(R.string.disconnect_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDisconnect()
                        showDisconnectDialog = false
                    }
                ) {
                    Text(stringResource(R.string.disconnect), color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        colors = CardDefaults.cardColors(
            containerColor = CardBackgroundLight
        ),
        onClick = if (hasDevice) { { showDisconnectDialog = true } } else { {} }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (showAsConnected) Success.copy(alpha = 0.1f)
                            else Primary.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (showAsConnected) Success else Primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(AppSpacing.medium))

                // Text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (connectionState) {
                            is WearablesViewModel.ConnectionState.Connected -> connectionState.deviceName
                            is WearablesViewModel.ConnectionState.Registered -> connectionState.deviceName
                            else -> stringResource(R.string.rayban_glasses)
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryLight
                    )
                    Text(
                        text = when {
                            showAsConnected -> stringResource(R.string.connected)
                            isSearching -> stringResource(R.string.searching)
                            isConnecting -> stringResource(R.string.connecting)
                            connectionState is WearablesViewModel.ConnectionState.Error -> connectionState.message
                            else -> stringResource(R.string.disconnected)
                        },
                        fontSize = 14.sp,
                        color = if (showAsConnected) Success else TextSecondaryLight
                    )
                }

                // Connect Button or Status
                when {
                    showAsConnected -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.small))
                                .background(Success.copy(alpha = 0.1f))
                                .padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small)
                        ) {
                            Text(
                                text = stringResource(R.string.connected),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Success
                            )
                        }
                    }
                    isSearching || isConnecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                    }
                    else -> {
                        Button(
                            onClick = onConnect,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary
                            ),
                            shape = RoundedCornerShape(AppRadius.small)
                        ) {
                            Text(stringResource(R.string.connect_glasses))
                        }
                    }
                }
            }

            // Update prompts (spec §5.7): firmware (Device.compatibility) and DAT glasses app (session error)
            if (isFirmwareUpdateRequired) {
                UpdateRequiredRow(
                    message = stringResource(R.string.update_required_firmware),
                    buttonText = stringResource(R.string.update_firmware),
                    onClick = onUpdateFirmware
                )
            }
            if (isDatAppUpdateRequired) {
                UpdateRequiredRow(
                    message = stringResource(R.string.update_required_dat_app),
                    buttonText = stringResource(R.string.update_dat_app),
                    onClick = onUpdateDatApp
                )
            }
        }
    }
}

@Composable
private fun UpdateRequiredRow(
    message: String,
    buttonText: String,
    onClick: () -> Unit
) {
    HorizontalDivider(modifier = Modifier.padding(horizontal = AppSpacing.medium))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Warning,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(AppSpacing.small))
        Text(
            text = message,
            fontSize = 13.sp,
            color = TextSecondaryLight,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(AppSpacing.small))
        TextButton(onClick = onClick) {
            Text(buttonText, color = Primary, fontWeight = FontWeight.Medium)
        }
    }
}
```

(`Warning`, `Success`, `Error`, `Primary`, `TextPrimaryLight`, `TextSecondaryLight`, `CardBackgroundLight` all exist in `ui/theme/Color.kt:6-51` and are already imported via `com.smartview.glassai.ui.theme.*`.)

- [ ] **Step 4.3a — QuickVisionScreen: widen the stream wait budget to 12 s.** The 0.9.0 chain (`createSession` → `STARTED` → `addCamera` → `stream.start()` → `STREAMING`) takes longer than the 0.4.0 `startStreamSession`, and spec §5.5 budgets 12 s for the equivalent service path. In `android/app/src/main/java/com/smartview/glassai/ui/screens/QuickVisionScreen.kt` replace lines 183-185:

```kotlin
            // Wait for stream to be ready (max 5 seconds)
            var streamWait = 0
            while (streamState !is WearablesViewModel.StreamState.Streaming && streamWait < 50) {
```

with:

```kotlin
            // Wait for stream to be ready (max 20 seconds: previous-session stop-wait + session create + STARTED + addCamera + STREAMING); exit early on Error
            var streamWait = 0
            while (streamState !is WearablesViewModel.StreamState.Streaming && streamState !is WearablesViewModel.StreamState.Error && streamWait < 200) {
```

(The loop body `delay(100); streamWait++` on lines 186-187 is unchanged.)

- [ ] **Step 4.4 — Verify this task's files compile** (module still red on the two files owned by Task 5):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e: " | grep -vE "QuickVisionService.kt|RTMPStreamingViewModel.kt|RTMPStreamingScreen.kt" ; echo "exit=$?"
```

Expected: no lines printed, `exit=1`. In particular there must be no error in `WearablesViewModel.kt`, `HomeScreen.kt`, `LiveAIScreen.kt` (the `when (streamState)` at `LiveAIScreen.kt:580` has an `else` branch so the new `Paused` case compiles), `QuickVisionScreen.kt`, `SimpleLiveStreamScreen.kt`, `Navigation.kt`.

- [ ] **Step 4.5 — Commit.**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt android/app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt android/app/src/main/java/com/smartview/glassai/ui/screens/QuickVisionScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(android): migrate WearablesViewModel/HomeScreen to DAT 0.9.0 via GlassesSessionManager; registration takes an Activity; firmware/DAT-app update prompts; Quick Vision waits 12 s for the stream"
```

---

### Task 5: Migrate `QuickVisionService` + `RTMPStreamingViewModel` — ends with green `assembleDebug` and green unit tests

**Files:**
- Create: `android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt`
- Create: `android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt`
- Modify: `android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` (imports lines 23-30 and line 43; fields lines 90-95; `cleanup()` lines 175-183; `captureAndAnalyze()` lines 185-322; `getLocalizedString` lines 399-444)
- Rewrite: `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` (whole file, 340 lines)
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt` (line 36)

**Interfaces:**
- Consumes: `GlassesSessionManager.acquire/ensureSessionStarted/addCamera/stopCamera/release/activeDevice`, `GlassesCamera.videoFrames/streamState/startStream/capturePhoto/stop`, `PhotoCaptureResult`, `CameraResult`, `CameraError`, `SessionStartResult`, `RTMPStreamingService.feedFrame(buffer: ByteBuffer, width: Int, height: Int, timestampUs: Long)` (unchanged, `services/RTMPStreamingService.kt:346-399`; it keeps the timestamp smoothing and drop statistics) — it is now called with `ByteBuffer.wrap(copy)`, where `copy` is a `ByteArray` taken from `VideoFrame.buffer` as the **first** statement of `handleVideoFrame` (spec §5.8: the SDK buffer is only guaranteed valid inside `collect {}`), and the same copy feeds the preview.
- Produces:

```kotlin
sealed class PhotoCaptureOutcome<out T> { Captured(image: T, fromVideoFrame: Boolean); NoDevice; SessionFailed; SessionTimeout; CameraUnavailable(error: CameraError); StreamStartFailed(error: StreamError); StreamTimeout; NoImage }
class GlassesPhotoCapturer<T : Any>(sessionManager: GlassesSessionManager, owner: String, config: StreamConfiguration, decodePhoto: (PhotoData) -> T?, decodeFrame: (VideoFrame) -> T?, frameDispatcher: CoroutineDispatcher = Dispatchers.Default, deviceWaitMs: Long = 3_000, sessionTimeoutMs: Long = 12_000, streamTimeoutMs: Long = 12_000, fallbackFrameTimeoutMs: Long = 2_000) {
    suspend fun capture(): PhotoCaptureOutcome<T>   // main thread; always stops the camera and releases the owner before returning
}
```

  `QuickVisionService` public contract unchanged (`ACTION_*`, `EXTRA_*`, broadcasts `started|streaming|analyzing|complete|error|finished` — `PorcupineWakeWordService.kt:79-88` depends on `finished`/`error`). `RTMPStreamingViewModel` public contract unchanged except `cameraState: StateFlow<DatStreamState?>` (was `StreamSessionState?`).

Behavior (spec §5.5): the wake-word path lives in `GlassesPhotoCapturer` so it is unit-tested with the Task 3 fakes and instrumented in Task 9. It waits up to 3 s for `activeDevice` (the StateFlow starts `null` and is filled asynchronously — when the service is the first `getInstance()` caller a `.value` read would be `null` although glasses are connected), borrows the shared session (`acquire` + `ensureSessionStarted`, 12 s), waits up to 12 s for `STREAMING`, then uses `capturePhoto()`; if capture fails it falls back to the first decoded video frame (2 s); `CameraBusy` (e.g. Live AI screen is streaming) is announced with `glasses_camera_busy` and the service finishes cleanly. Frames are decoded on `Dispatchers.Default`, never on the service's main-thread scope.

- [ ] **Step 5.0 — Failing test first: `GlassesPhotoCapturerTest`.** Create `android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt`:

```kotlin
package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import java.nio.ByteBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-word capture path (QuickVisionService) against the Task 3 fakes. T = String so no
 * android.graphics.Bitmap is needed on the JVM; PhotoData.HEIC wraps a plain ByteBuffer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesPhotoCapturerTest {

    private val factory = FakeDatSessionFactory()
    private val observer = FakeDatDeviceObserver()
    private val dispatcher = UnconfinedTestDispatcher()
    private val config = StreamConfiguration()

    private val rayban = GlassesDeviceInfo(
        id = "dev-1",
        name = "Ray-Ban Meta",
        deviceType = DeviceType.RAYBAN_META,
        isDisplayCapable = false,
        compatibility = DeviceCompatibility.COMPATIBLE,
    )

    private val heicPhoto = PhotoData.HEIC(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))

    private fun TestScope.newManager(): GlassesSessionManager =
        GlassesSessionManager(
            sessionFactory = factory,
            deviceObserver = observer,
            scope = backgroundScope,
        ).also { it.startMonitoring() }

    private fun capturer(manager: GlassesSessionManager) = GlassesPhotoCapturer(
        sessionManager = manager,
        owner = "QuickVisionService",
        config = config,
        decodePhoto = { "photo" },
        decodeFrame = { "frame" },
        frameDispatcher = dispatcher,
    )

    @Test
    fun capturesPhotoThroughSharedSessionAndReleasesEverything() = runTest(dispatcher) {
        observer.device.value = rayban
        factory.nextCaptureResult = PhotoCaptureResult.Success(heicPhoto)
        val manager = newManager()

        val outcome = async { capturer(manager).capture() }
        assertEquals(1, factory.createCalls)        // acquire() fast path created the session
        assertEquals(1, manager.ownerCount)
        factory.last.emitStarted()                   // ensureSessionStarted resumes -> addCamera -> start
        val camera = factory.last.cameras.single()
        assertEquals(1, camera.startCalls)
        assertEquals("QuickVisionService", manager.currentCameraOwner)

        camera.stateFlow.value = DatStreamState.STREAMING

        assertEquals(PhotoCaptureOutcome.Captured("photo", fromVideoFrame = false), outcome.await())
        assertEquals(1, camera.stopCalls)
        assertNull(manager.currentCameraOwner)
        assertEquals(0, manager.ownerCount)
        assertEquals(1, factory.last.stopCalls)      // last owner released -> session stopped
    }

    @Test
    fun noActiveDeviceWithinBudgetNeverTouchesTheSession() = runTest(dispatcher) {
        val manager = newManager()                   // observer stays null

        val outcome = async { capturer(manager).capture() }
        advanceTimeBy(GlassesPhotoCapturer.DEFAULT_DEVICE_WAIT_MS + 1)

        assertEquals(PhotoCaptureOutcome.NoDevice, outcome.await())
        assertEquals(0, factory.createCalls)
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun cameraHeldByAnotherOwnerIsReportedAsBusyAndClaimReleased() = runTest(dispatcher) {
        observer.device.value = rayban
        val manager = newManager()
        manager.acquire("WearablesViewModel")
        factory.last.emitStarted()
        assertTrue(manager.addCamera("WearablesViewModel", config) is CameraResult.Ready)

        val outcome = capturer(manager).capture()    // session already STARTED: completes synchronously

        assertEquals(
            PhotoCaptureOutcome.CameraUnavailable(CameraError.CameraBusy("WearablesViewModel")),
            outcome,
        )
        assertEquals("WearablesViewModel", manager.currentCameraOwner) // the other owner keeps streaming
        assertEquals(1, manager.ownerCount)
        assertEquals(0, factory.last.stopCalls)
        assertEquals(1, factory.last.addCameraCalls)
    }

    @Test
    fun sessionThatNeverStartsTimesOutAndReleasesClaim() = runTest(dispatcher) {
        observer.device.value = rayban
        val manager = newManager()

        val outcome = async { capturer(manager).capture() }
        advanceTimeBy(GlassesPhotoCapturer.DEFAULT_SESSION_TIMEOUT_MS + 1)

        assertEquals(PhotoCaptureOutcome.SessionTimeout, outcome.await())
        assertEquals(0, manager.ownerCount)
        assertEquals(1, factory.last.stopCalls)
    }

    @Test
    fun capturePhotoFailureWithoutFrameFallsBackToNoImage() = runTest(dispatcher) {
        observer.device.value = rayban
        factory.nextCaptureResult = PhotoCaptureResult.Failure(CaptureError.CaptureFailed)
        val manager = newManager()

        val outcome = async { capturer(manager).capture() }
        factory.last.emitStarted()
        factory.last.cameras.single().stateFlow.value = DatStreamState.STREAMING
        advanceTimeBy(GlassesPhotoCapturer.DEFAULT_FALLBACK_FRAME_TIMEOUT_MS + 1) // no frame ever arrives

        assertEquals(PhotoCaptureOutcome.NoImage, outcome.await())
        assertEquals(0, manager.ownerCount)
        assertEquals(1, factory.last.cameras.single().stopCalls)
    }
}
```

The decoded-frame fallback branch (`Captured(image, fromVideoFrame = true)`) needs a real `VideoFrame`, which has no public constructor usable on the JVM; it is covered by the instrumented test `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable` in Task 9 (Step 9.2), which drives the capturer with `decodePhoto = { null }` against a real MockDeviceKit frame.

- [ ] **Step 5.0a — Confirm the test cannot compile yet** (the module is still red on the unmigrated files, so the message is the same as Step 3.5):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesPhotoCapturerTest' 2>&1 | grep -E "^e: |BUILD" | head -20
```

Expected: `BUILD FAILED` at `:app:compileDebugKotlin` (the two Task 5 files); the test source set is not compiled yet.

- [ ] **Step 5.0b — Create `android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt`:**

```kotlin
package com.smartview.glassai.glasses

import android.util.Log
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of one [GlassesPhotoCapturer.capture] attempt. */
sealed class PhotoCaptureOutcome<out T> {
    /** [image] came from Stream.capturePhoto() (fromVideoFrame = false) or from the first decoded frame. */
    data class Captured<T>(val image: T, val fromVideoFrame: Boolean) : PhotoCaptureOutcome<T>()
    /** No active device within the device wait budget. */
    object NoDevice : PhotoCaptureOutcome<Nothing>()
    /** Wearables.createSession refused (the error was emitted on GlassesSessionManager.sessionError). */
    object SessionFailed : PhotoCaptureOutcome<Nothing>()
    /** The session did not reach STARTED in time, or stopped first. */
    object SessionTimeout : PhotoCaptureOutcome<Nothing>()
    /** The manager refused to lend the camera (CameraBusy, NoSession, SessionNotStarted, Sdk). */
    data class CameraUnavailable(val error: CameraError) : PhotoCaptureOutcome<Nothing>()
    /** Stream.start() failed. */
    data class StreamStartFailed(val error: StreamError) : PhotoCaptureOutcome<Nothing>()
    /** The stream never reached STREAMING within the budget. */
    object StreamTimeout : PhotoCaptureOutcome<Nothing>()
    /** capturePhoto() failed and no decodable video frame arrived within the fallback budget. */
    object NoImage : PhotoCaptureOutcome<Nothing>()
}

/**
 * The wake-word capture path (spec §5.5) as one testable unit:
 * wait for a device -> acquire -> ensureSessionStarted -> addCamera -> stream.start() ->
 * wait STREAMING -> capturePhoto() -> (fallback) first decoded video frame -> stopCamera + release.
 *
 * [T] is the decoded image type (Bitmap in the app, any type in JVM tests). Frames are decoded on
 * [frameDispatcher] (Dispatchers.Default in the app) so the main thread never converts pixels
 * (spec §5.8). capture() must be called on the main thread (GlassesSessionManager contract) and
 * always gives the camera and the owner claim back before returning, also when cancelled.
 */
class GlassesPhotoCapturer<T : Any>(
    private val sessionManager: GlassesSessionManager,
    private val owner: String,
    private val config: StreamConfiguration,
    private val decodePhoto: (PhotoData) -> T?,
    private val decodeFrame: (VideoFrame) -> T?,
    private val frameDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deviceWaitMs: Long = DEFAULT_DEVICE_WAIT_MS,
    private val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS,
    private val streamTimeoutMs: Long = DEFAULT_STREAM_TIMEOUT_MS,
    private val fallbackFrameTimeoutMs: Long = DEFAULT_FALLBACK_FRAME_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "GlassesPhotoCapturer"

        /** activeDevice is a StateFlow that starts null; its first emission is asynchronous. */
        const val DEFAULT_DEVICE_WAIT_MS = 3_000L
        /** spec §5.5: session budget relaxed to 12 s. */
        const val DEFAULT_SESSION_TIMEOUT_MS = 12_000L
        const val DEFAULT_STREAM_TIMEOUT_MS = 12_000L
        const val DEFAULT_FALLBACK_FRAME_TIMEOUT_MS = 2_000L
    }

    suspend fun capture(): PhotoCaptureOutcome<T> {
        // Never read activeDevice.value here: when the wake-word service is the first
        // getInstance() caller the StateFlow is still null although glasses are connected.
        val hasDevice = withTimeoutOrNull(deviceWaitMs) {
            sessionManager.activeDevice.first { it != null }
        } != null
        if (!hasDevice) {
            Log.w(TAG, "no active device within ${deviceWaitMs}ms")
            return PhotoCaptureOutcome.NoDevice
        }
        sessionManager.acquire(owner)
        try {
            return borrowCameraAndCapture()
        } finally {
            sessionManager.stopCamera(owner)
            sessionManager.release(owner)
        }
    }

    private suspend fun borrowCameraAndCapture(): PhotoCaptureOutcome<T> {
        when (sessionManager.ensureSessionStarted(sessionTimeoutMs)) {
            SessionStartResult.STARTED -> Unit
            SessionStartResult.CREATE_FAILED -> return PhotoCaptureOutcome.SessionFailed
            SessionStartResult.NOT_STARTED -> return PhotoCaptureOutcome.SessionTimeout
        }
        val camera = when (val result = sessionManager.addCamera(owner, config)) {
            is CameraResult.Ready -> result.camera
            is CameraResult.Failed -> {
                Log.e(TAG, "addCamera refused: ${result.error}")
                return PhotoCaptureOutcome.CameraUnavailable(result.error)
            }
        }
        return coroutineScope {
            val fallbackFrame = CompletableDeferred<T>()
            // Subscribe BEFORE start(); frames are decoded off the main thread.
            val frameJob = launch(frameDispatcher) {
                camera.videoFrames.collect { frame ->
                    if (frame.isCompressed || frame.isCodecConfig) return@collect
                    if (!fallbackFrame.isCompleted) {
                        decodeFrame(frame)?.let { fallbackFrame.complete(it) }
                    }
                }
            }
            try {
                captureFrom(camera, fallbackFrame)
            } finally {
                frameJob.cancel()
            }
        }
    }

    private suspend fun captureFrom(
        camera: GlassesCamera,
        fallbackFrame: CompletableDeferred<T>,
    ): PhotoCaptureOutcome<T> {
        camera.startStream()?.let { error ->
            Log.e(TAG, "stream.start failed: ${error.description}")
            return PhotoCaptureOutcome.StreamStartFailed(error)
        }
        val streaming = withTimeoutOrNull(streamTimeoutMs) {
            camera.streamState.first { it == DatStreamState.STREAMING }
        } != null
        if (!streaming) {
            Log.e(TAG, "stream did not reach STREAMING within ${streamTimeoutMs}ms")
            return PhotoCaptureOutcome.StreamTimeout
        }
        when (val result = camera.capturePhoto()) {
            is PhotoCaptureResult.Success -> {
                val image = decodePhoto(result.photo)
                if (image != null) {
                    Log.d(TAG, "capturePhoto ok")
                    return PhotoCaptureOutcome.Captured(image, fromVideoFrame = false)
                }
                Log.w(TAG, "capturePhoto returned undecodable data; using a video frame")
            }
            is PhotoCaptureResult.Failure ->
                Log.w(TAG, "capturePhoto failed: ${result.error.description}; using a video frame")
        }
        val frame = withTimeoutOrNull(fallbackFrameTimeoutMs) { fallbackFrame.await() }
        return if (frame != null) {
            PhotoCaptureOutcome.Captured(frame, fromVideoFrame = true)
        } else {
            PhotoCaptureOutcome.NoImage
        }
    }
}
```

- [ ] **Step 5.1 — QuickVisionService imports.** Replace lines 23-30 with:

```kotlin
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.GlassesPhotoCapturer
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.PhotoCaptureOutcome
```

Then delete line 43 (`import kotlinx.coroutines.flow.first`, no longer used) and add `import kotlinx.coroutines.CancellationException` next to the other `kotlinx.coroutines.*` imports. (`kotlinx.coroutines.withTimeoutOrNull`, `Job`, `delay`, `launch`, `suspendCancellableCoroutine` stay used.)

- [ ] **Step 5.2 — QuickVisionService fields.** Replace lines 90-95 (`// DAT SDK components` … `capturedFrame`) with:

```kotlin
    // DAT SDK: the camera is borrowed from the process-wide session owner through GlassesPhotoCapturer
    private val sessionManager: GlassesSessionManager by lazy { GlassesSessionManager.getInstance(this) }
    private var captureJob: Job? = null
```

Add this constant inside `companion object` after `CHANNEL_ID` (line 63) — the time budgets are the `GlassesPhotoCapturer` defaults (3 s device, 12 s session, 12 s stream, 2 s fallback frame):

```kotlin
        private const val OWNER = "QuickVisionService"
```

- [ ] **Step 5.3 — QuickVisionService `cleanup()`.** Replace lines 175-183 with:

```kotlin
    private fun cleanup() {
        // GlassesPhotoCapturer.capture() releases the camera and the owner claim in its finally block,
        // also when the job is cancelled here; stopCamera/release are idempotent, so calling them
        // again is only a safety net for a capture that never started.
        captureJob?.cancel()
        captureJob = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)
    }
```

- [ ] **Step 5.4 — QuickVisionService `captureAndAnalyze()`.** Replace the whole function (lines 185-322) with the three functions below. The DAT work is delegated to `GlassesPhotoCapturer` (Step 5.0b): it waits up to 3 s for `activeDevice` (never a `.value` snapshot — when this service is the first `getInstance()` caller the StateFlow is still `null` although glasses are connected), then `acquire` → `ensureSessionStarted` (12 s) → `addCamera` → `stream.start()` → `STREAMING` (12 s) → `capturePhoto()` → first decoded frame as fallback (2 s), and always gives the camera and the owner claim back before returning. Frames are decoded on `Dispatchers.Default`, so nothing below runs pixel conversion on the service's main-thread `scope`.

```kotlin
    private fun captureAndAnalyze() {
        Log.d(TAG, "captureAndAnalyze called")

        captureJob = scope.launch {
            try {
                // Wait for TTS to initialize
                withTimeoutOrNull(2000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        if (isTtsReady) {
                            continuation.resume(Unit)
                        } else {
                            Thread {
                                ttsInitLatch.await()
                                mainHandler.post { continuation.resume(Unit) }
                            }.start()
                        }
                    }
                }

                // 1. Announce "looking"
                val lookingText = getLocalizedString("looking")
                Log.d(TAG, "Speaking: $lookingText")
                speak(lookingText)

                // 2. Borrow the shared session + camera and take the photo (see GlassesPhotoCapturer)
                Log.d(TAG, "Acquiring shared glasses session...")
                broadcastStatus("streaming")

                val capturer = GlassesPhotoCapturer(
                    sessionManager = sessionManager,
                    owner = OWNER,
                    config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
                    decodePhoto = ::decodePhoto,
                    decodeFrame = ::convertVideoFrameToBitmap,
                )
                val image: Bitmap = when (val outcome = capturer.capture()) {
                    is PhotoCaptureOutcome.Captured -> {
                        Log.d(
                            TAG,
                            "Image ready (fromVideoFrame=${outcome.fromVideoFrame}): " +
                                "${outcome.image.width}x${outcome.image.height}"
                        )
                        outcome.image
                    }
                    PhotoCaptureOutcome.NoDevice -> {
                        Log.e(TAG, "No device connected")
                        failAndFinish("no_device")
                        return@launch
                    }
                    PhotoCaptureOutcome.SessionFailed,
                    PhotoCaptureOutcome.SessionTimeout -> {
                        Log.e(TAG, "Shared session unavailable: $outcome")
                        failAndFinish("error")
                        return@launch
                    }
                    is PhotoCaptureOutcome.CameraUnavailable -> {
                        Log.e(TAG, "addCamera refused: ${outcome.error}")
                        // Another feature (e.g. the Live AI screen) holds the camera: say so and leave it alone
                        failAndFinish(if (outcome.error is CameraError.CameraBusy) "camera_busy" else "error")
                        return@launch
                    }
                    is PhotoCaptureOutcome.StreamStartFailed -> {
                        Log.e(TAG, "stream.start failed: ${outcome.error.description}")
                        failAndFinish("error")
                        return@launch
                    }
                    PhotoCaptureOutcome.StreamTimeout -> {
                        Log.e(TAG, "Stream did not reach STREAMING within the budget")
                        failAndFinish("error")
                        return@launch
                    }
                    PhotoCaptureOutcome.NoImage -> {
                        Log.e(TAG, "No image captured")
                        failAndFinish("no_image")
                        return@launch
                    }
                }

                // 3. Analyze the captured image (camera and session claim are already released)
                Log.d(TAG, "Analyzing captured image: ${image.width}x${image.height}")
                broadcastStatus("analyzing")
                updateNotification(getLocalizedString("analyzing"))

                val language = apiKeyManager.getOutputLanguage()
                val result = visionService.quickVision(image, language)

                result.fold(
                    onSuccess = { description ->
                        Log.d(TAG, "Analysis result: $description")

                        // Save record with thumbnail
                        val prompt = modeManager.getPrompt()
                        val currentMode = modeManager.currentMode.value
                        val visionModel = providerManager.selectedModel.value
                        quickVisionStorage.saveRecord(
                            bitmap = image,
                            prompt = prompt,
                            result = description,
                            mode = currentMode,
                            visionModel = visionModel
                        )
                        Log.d(TAG, "Record saved with thumbnail")

                        broadcastResult(description)
                        broadcastStatus("complete")
                        speakAndWait(description, useOutputLocale = true)  // AI reply uses the output language
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Analysis failed: ${error.message}")
                        speak(getLocalizedString("analysis_failed"))
                        broadcastError(error.message ?: "Unknown error")
                        broadcastStatus("error")
                    }
                )

                delay(500)
                finishService()

            } catch (e: CancellationException) {
                // cleanup() cancelled us because the service is stopping; the capturer already
                // released the camera and the owner claim in its finally block.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in captureAndAnalyze: ${e.message}", e)
                speak(getLocalizedString("error"))
                broadcastStatus("error")
                delay(2000)
                finishService()
            }
        }
    }

    /** Announces [key], tells PorcupineWakeWordService we failed, and stops after 2 s. */
    private suspend fun failAndFinish(key: String) {
        speak(getLocalizedString(key))
        broadcastStatus("error")
        delay(2000)
        finishService()
    }

    private fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buffer = photo.data.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
```

`convertVideoFrameToBitmap` and `convertI420toNV21` (lines 324-362) stay exactly as they are: they are pure functions and now run on `Dispatchers.Default` inside the capturer. `android.graphics.BitmapFactory/ImageFormat/Rect/YuvImage` and `java.io.ByteArrayOutputStream` therefore remain imported.

- [ ] **Step 5.5 — QuickVisionService `getLocalizedString`.** Inside the `return when (key) { ... }` (lines 405-443) add a branch before `else -> key`:

```kotlin
            "camera_busy" -> getString(R.string.glasses_camera_busy)
```

(`R` is already imported at line 32; the resource follows the app language set by `LanguageManager`.)

- [ ] **Step 5.6 — Rewrite `RTMPStreamingViewModel.kt` entirely:**

```kotlin
package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.services.RTMPStreamingService
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * RTMPStreamingViewModel - Manages RTMP streaming from glasses camera
 *
 * Borrows the camera from the shared GlassesSessionManager (DAT 0.9.0) and feeds raw I420 frames
 * to RTMPStreamingService for live broadcasting.
 */
class RTMPStreamingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RTMPStreamingVM"
        private const val OWNER = "RTMPStreamingViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        const val DEFAULT_RTMP_URL = "rtmp://localhost/live/stream"
    }

    // States
    sealed class UIState {
        object Idle : UIState()
        object Connecting : UIState()
        object Streaming : UIState()
        data class Error(val message: String) : UIState()
    }

    private val _uiState = MutableStateFlow<UIState>(UIState.Idle)
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    private val _rtmpUrl = MutableStateFlow(DEFAULT_RTMP_URL)
    val rtmpUrl: StateFlow<String> = _rtmpUrl.asStateFlow()

    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> = _previewFrame.asStateFlow()

    private val _streamStats = MutableStateFlow(RTMPStreamingService.StreamingStats())
    val streamStats: StateFlow<RTMPStreamingService.StreamingStats> = _streamStats.asStateFlow()

    private val _cameraState = MutableStateFlow<DatStreamState?>(null)
    val cameraState: StateFlow<DatStreamState?> = _cameraState.asStateFlow()

    private val _bitrate = MutableStateFlow(2_000_000) // 2 Mbps default
    val bitrate: StateFlow<Int> = _bitrate.asStateFlow()

    // Services
    private val rtmpService = RTMPStreamingService(application)
    private val sessionManager: GlassesSessionManager by lazy {
        GlassesSessionManager.getInstance(application)
    }

    // Borrowed camera + jobs
    private var camera: GlassesCamera? = null
    private var startJob: Job? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var statsJob: Job? = null

    // Video parameters (set when stream starts)
    private var videoWidth = 0
    private var videoHeight = 0
    private var frameTimestampBase = 0L

    init {
        // Load saved RTMP URL
        val apiKeyManager = APIKeyManager.getInstance(application)
        apiKeyManager.getRtmpUrl()?.let { savedUrl ->
            if (savedUrl.isNotEmpty()) {
                _rtmpUrl.value = savedUrl
            }
        }

        // Observe RTMP service state
        viewModelScope.launch {
            rtmpService.state.collect { state ->
                when (state) {
                    is RTMPStreamingService.StreamingState.Idle -> {
                        if (_uiState.value != UIState.Idle) {
                            _uiState.value = UIState.Idle
                        }
                    }
                    is RTMPStreamingService.StreamingState.Connecting -> {
                        _uiState.value = UIState.Connecting
                    }
                    is RTMPStreamingService.StreamingState.Streaming -> {
                        _uiState.value = UIState.Streaming
                    }
                    is RTMPStreamingService.StreamingState.Error -> {
                        _uiState.value = UIState.Error(state.message)
                    }
                    is RTMPStreamingService.StreamingState.Disconnected -> {
                        _uiState.value = UIState.Error("Disconnected from server")
                    }
                }
            }
        }

        // Observe stats
        statsJob = viewModelScope.launch {
            rtmpService.stats.collect { stats ->
                _streamStats.value = stats
            }
        }
    }

    fun updateRtmpUrl(url: String) {
        _rtmpUrl.value = url
        // Save URL
        val apiKeyManager = APIKeyManager.getInstance(getApplication())
        apiKeyManager.saveRtmpUrl(url)
    }

    fun updateBitrate(newBitrate: Int) {
        _bitrate.value = newBitrate
    }

    /**
     * Start RTMP streaming
     * 1. Borrows the glasses camera from the shared session
     * 2. Connects to the RTMP server after the first frame (dimensions known)
     * 3. Encodes and streams
     */
    fun startStreaming() {
        if (_uiState.value == UIState.Streaming || _uiState.value == UIState.Connecting) {
            Log.w(TAG, "Already streaming or connecting")
            return
        }

        Log.d(TAG, "Starting streaming to: ${_rtmpUrl.value}")
        _uiState.value = UIState.Connecting

        // Start DAT SDK camera stream first
        startCameraStream()
    }

    private fun startCameraStream() {
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)

        // Get video quality setting
        val apiKeyManager = APIKeyManager.getInstance(getApplication())
        val savedQuality = apiKeyManager.getVideoQuality()
        val videoQuality = when (savedQuality) {
            "LOW" -> VideoQuality.LOW
            "HIGH" -> VideoQuality.HIGH
            else -> VideoQuality.MEDIUM
        }

        Log.d(TAG, "Starting camera stream with quality: $savedQuality")

        startJob = viewModelScope.launch {
            sessionManager.acquire(OWNER)
            when (sessionManager.ensureSessionStarted(SESSION_START_TIMEOUT_MS)) {
                SessionStartResult.STARTED -> Unit
                SessionStartResult.CREATE_FAILED -> {
                    failCamera(getApplication<Application>().getString(R.string.glasses_session_failed))
                    return@launch
                }
                SessionStartResult.NOT_STARTED -> {
                    failCamera(getApplication<Application>().getString(R.string.glasses_session_timeout))
                    return@launch
                }
            }
            val config = StreamConfiguration(videoQuality = videoQuality, frameRate = 24)
            when (val result = sessionManager.addCamera(OWNER, config)) {
                is CameraResult.Ready -> attachCamera(result.camera)
                is CameraResult.Failed -> failCamera(cameraErrorMessage(result.error))
            }
        }
    }

    private fun attachCamera(borrowed: GlassesCamera) {
        camera = borrowed

        // Subscribe BEFORE start()
        stateJob = viewModelScope.launch {
            var hasBeenActive = false
            borrowed.streamState.collect { state ->
                Log.d(TAG, "Camera state: $state")
                _cameraState.value = state

                when (state) {
                    DatStreamState.STREAMING -> {
                        hasBeenActive = true
                        // Camera is ready, RTMP will connect after first frame arrives
                        Log.d(TAG, "Camera streaming, waiting for first frame...")
                    }
                    DatStreamState.STARTING,
                    DatStreamState.STARTED,
                    DatStreamState.STOPPING,
                    DatStreamState.PAUSED -> {
                        hasBeenActive = true
                    }
                    DatStreamState.STOPPED,
                    DatStreamState.CLOSED -> {
                        if (hasBeenActive) {
                            hasBeenActive = false
                            Log.d(TAG, "Camera stream ended; stopping RTMP")
                            stopStreaming()
                        }
                    }
                }
            }
        }

        streamErrorJob = viewModelScope.launch {
            borrowed.streamErrors.collect { error ->
                Log.e(TAG, "Stream error: ${error.description}")
                _uiState.value = UIState.Error(error.getLocalizedDescription(getApplication()))
            }
        }

        videoJob = viewModelScope.launch {
            frameTimestampBase = 0L
            borrowed.videoFrames.collect { videoFrame ->
                if (videoFrame.isCompressed || videoFrame.isCodecConfig) return@collect
                handleVideoFrame(videoFrame)
            }
        }

        val startError = borrowed.startStream()
        if (startError != null) {
            failCamera(startError.getLocalizedDescription(getApplication()))
        }
    }

    private fun failCamera(message: String) {
        Log.e(TAG, "Camera failure: $message")
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)
        _cameraState.value = null
        _uiState.value = UIState.Error(message)
    }

    private fun cameraErrorMessage(error: CameraError): String {
        val app = getApplication<Application>()
        return when (error) {
            is CameraError.CameraBusy -> app.getString(R.string.glasses_camera_busy)
            CameraError.NoSession -> app.getString(R.string.glasses_no_session)
            CameraError.SessionNotStarted -> app.getString(R.string.glasses_session_timeout)
            is CameraError.Sdk -> error.error.getLocalizedDescription(app)
        }
    }

    private fun connectRtmp() {
        viewModelScope.launch {
            val success = rtmpService.startStreaming(
                rtmpUrl = _rtmpUrl.value,
                width = videoWidth,
                height = videoHeight,
                bitrate = _bitrate.value
            )

            if (!success) {
                Log.e(TAG, "Failed to connect RTMP")
                _uiState.value = UIState.Error("Failed to connect to RTMP server")
            }
        }
    }

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        // Copy the SDK buffer FIRST: VideoFrame.buffer is only guaranteed valid inside collect {}
        // (spec §5.8). Everything below works on our own copy.
        val i420 = copyFrame(videoFrame) ?: return
        val width = videoFrame.width
        val height = videoFrame.height

        // Set video dimensions on first frame and connect RTMP
        if (videoWidth == 0 || videoHeight == 0) {
            // Use original dimensions - modern MediaCodec handles alignment internally
            videoWidth = width
            videoHeight = height
            Log.d(TAG, "Video dimensions: ${videoWidth}x${videoHeight}")

            // Now connect RTMP with proper dimensions
            if (_uiState.value == UIState.Connecting && !rtmpService.isStreaming()) {
                connectRtmp()
            }
        }

        // Calculate timestamp
        val timestampUs = if (frameTimestampBase == 0L) {
            frameTimestampBase = System.nanoTime() / 1000
            0L
        } else {
            System.nanoTime() / 1000 - frameTimestampBase
        }

        // Feed the copied I420 frame to the RTMP encoder (ByteBuffer overload: timestamp smoothing)
        rtmpService.feedFrame(
            buffer = ByteBuffer.wrap(i420),
            width = width,
            height = height,
            timestampUs = timestampUs
        )

        // Also update preview (convert to bitmap for display) from the same copy
        updatePreview(i420, width, height)
    }

    /** Defensive copy of the SDK frame; null if the buffer could not be read. */
    private fun copyFrame(videoFrame: VideoFrame): ByteArray? = try {
        val buffer = videoFrame.buffer
        val originalPosition = buffer.position()
        val copy = ByteArray(buffer.remaining())
        buffer.get(copy)
        buffer.position(originalPosition)
        copy
    } catch (e: Exception) {
        Log.e(TAG, "Error copying video frame: ${e.message}")
        null
    }

    private fun updatePreview(i420: ByteArray, width: Int, height: Int) {
        try {
            // Convert I420 to NV21 for preview
            val nv21 = convertI420toNV21(i420, width, height)
            val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)

            val jpegBytes = ByteArrayOutputStream().use { stream ->
                image.compressToJpeg(Rect(0, 0, width, height), 50, stream)
                stream.toByteArray()
            }

            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            _previewFrame.value = bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error updating preview: ${e.message}")
        }
    }

    private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4

        input.copyInto(output, 0, 0, size) // Y is the same

        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
    }

    private fun cancelCameraJobs() {
        startJob?.cancel()
        startJob = null
        videoJob?.cancel()
        videoJob = null
        stateJob?.cancel()
        stateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
    }

    /**
     * Stop streaming
     */
    fun stopStreaming() {
        Log.d(TAG, "Stopping streaming")

        // Stop RTMP service
        rtmpService.stopStreaming()

        // Give the camera and the session claim back
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)

        // Reset
        videoWidth = 0
        videoHeight = 0
        frameTimestampBase = 0L
        _previewFrame.value = null
        _cameraState.value = null
        _uiState.value = UIState.Idle
    }

    fun clearError() {
        if (_uiState.value is UIState.Error) {
            _uiState.value = UIState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
        rtmpService.release()
        statsJob?.cancel()
    }
}
```

- [ ] **Step 5.7 — RTMPStreamingScreen.** Delete line 36 (`import com.meta.wearable.dat.camera.types.StreamSessionState`). `val cameraState by viewModel.cameraState.collectAsState()` (line 52) needs no import.

- [ ] **Step 5.8 — Full debug build must now pass:**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, `app/build/outputs/apk/debug/app-universal-debug.apk` regenerated.

- [ ] **Step 5.9 — Unit tests (acceptance for Tasks 3–5):**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesSessionManagerTest' --tests 'com.smartview.glassai.glasses.GlassesPhotoCapturerTest'
```

Expected: `BUILD SUCCESSFUL`; the report `app/build/reports/tests/testDebugUnitTest/index.html` shows 30 tests (24 in `GlassesSessionManagerTest` + 6 in `GlassesPhotoCapturerTest`), 0 failures. If a test fails on a `Log` call (`Method d in android.util.Log not mocked`), confirm `testOptions { unitTests.isReturnDefaultValues = true }` is present in `app/build.gradle.kts` (Task 1, Step 1.5). If `capturesPhotoThroughSharedSessionAndReleasesEverything` fails to construct `PhotoData.HEIC`, replace `heicPhoto` with `PhotoData.HEIC(ByteBuffer.allocate(0))` — only the constructor shape, never the assertions, may change.

- [ ] **Step 5.10 — Commit.**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt android/app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt android/app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt android/app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt
git commit -m "feat(android): migrate QuickVisionService (via GlassesPhotoCapturer) and RTMP streaming to the shared session (capturePhoto, 12 s budgets, CameraBusy); assembleDebug green on DAT 0.9.0"
```

---

### Task 6: Frame pipeline off the main thread, `Paused` UI state, zh/en error mapping

**Files:**
- Create: `android/app/src/main/java/com/smartview/glassai/glasses/GlassesErrorMessages.kt`
- Create: `android/app/src/test/java/com/smartview/glassai/glasses/GlassesErrorMessagesTest.kt`
- Modify: `android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` (as written in Task 4: imports, one new field, `attachCamera`, `cameraErrorMessage`, `takePhoto`, `startMonitoring` error sites)
- Modify: `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` (as written in Task 5: imports, two new fields, `attachCamera`, `cameraErrorMessage`)
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt` (`getStatusText`, lines 577-595)
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/screens/SimpleLiveStreamScreen.kt` (line 57 and the block before `// UI Overlay`)
- Modify: both `strings.xml` files

**Interfaces:**
- Produces `object GlassesErrorMessages { @StringRes fun resId(e: DeviceSessionError): Int; fun resId(e: StreamError): Int; fun resId(e: CaptureError): Int; fun resId(e: RegistrationError): Int; fun of(context: Context, e: DeviceSessionError): String; …(same for the other three); fun of(context: Context, e: CameraError): String }` (spec §5.9).
- `WearablesViewModel.StreamState.Paused` is now emitted for `DatStreamState.PAUSED` (spec §5.8: "轻触眼镜恢复").
- Frames are collected on a single-threaded worker (`Dispatchers.Default.limitedParallelism(1)`, exactly like `$SAMPLE/.../camera/CameraViewModel.kt:94,318`) with an `isProcessingFrame` `AtomicBoolean` guard as the drop policy (spec §5.8). **No `conflate()`**: a buffering operator would let the consumer read `VideoFrame.buffer` after the SDK has moved on to later frames (the `VideoFrame` reference gives no buffer-lifetime guarantee and the marketplace `CHANGELOG.md` 0.7.0 entry records a frame buffer-lifecycle fix), so the collector reads the buffer inside `collect {}` and both `handleVideoFrame` implementations copy the bytes as their first step. `isCompressed`/`isCodecConfig` frames are skipped. Note: `WearablesViewModel.onFrameReceived` is now invoked on a background thread (no current caller sets it; `grep -rn onFrameReceived app/src` shows only the ViewModel).

- [ ] **Step 6.1 — Strings.** Append before `</resources>` in `values/strings.xml`:

```xml

    <!-- Phase A: stream pause + DAT error mapping (spec §5.8, §5.9) -->
    <string name="stream_paused_title">Paused</string>
    <string name="stream_paused_subtitle">Tap your glasses to resume</string>
    <string name="dat_error_unknown">Unknown glasses error</string>
    <string name="dat_session_capability_denied">Permission for this feature was denied in the Meta AI app</string>
    <string name="dat_session_no_eligible_device">No compatible glasses are connected</string>
    <string name="dat_session_already_stopped">The glasses session has already ended</string>
    <string name="dat_session_idle">The glasses session has not started yet</string>
    <string name="dat_session_capability_already_added">The glasses camera is already in use</string>
    <string name="dat_session_capability_not_found">The requested glasses feature is not available</string>
    <string name="dat_session_device_disconnected">The glasses disconnected</string>
    <string name="dat_session_ended_by_device">The glasses ended the session</string>
    <string name="dat_session_already_exists">Another session is already running on the glasses</string>
    <string name="dat_session_thermal_critical">The glasses are too hot; the session was stopped</string>
    <string name="dat_session_thermal_emergency">The glasses overheated and shut down the session</string>
    <string name="dat_session_peak_power_shutdown">The glasses shut down the session to save power</string>
    <string name="dat_session_battery_critical">Glasses battery is critically low</string>
    <string name="dat_session_dat_app_update_required">The Device Access Toolkit app on your glasses needs an update</string>
    <string name="dat_session_dwa_unavailable">The glasses app service is unavailable. Check the Meta AI app.</string>
    <string name="dat_session_unexpected_error">Unexpected glasses session error</string>
    <string name="dat_stream_error">Video stream error</string>
    <string name="dat_stream_critical_error">Critical video stream error; the stream was stopped</string>
    <string name="dat_stream_hinge_closed">The glasses were folded; the stream stopped</string>
    <string name="dat_stream_permissions_denied">Camera permission was denied in the Meta AI app</string>
    <string name="dat_stream_thermal_hot">The glasses are getting hot; video quality may drop</string>
    <string name="dat_stream_battery_low">Glasses battery is low</string>
    <string name="dat_stream_peak_power_limit">The glasses reduced the stream to save power</string>
    <string name="dat_stream_timeout">The video stream timed out</string>
    <string name="dat_capture_device_disconnected">Cannot take a photo: the glasses disconnected</string>
    <string name="dat_capture_not_streaming">Cannot take a photo: the stream is not running</string>
    <string name="dat_capture_in_progress">A photo is already being captured</string>
    <string name="dat_registration_already_registered">Already connected to the Meta AI app</string>
    <string name="dat_registration_already_unregistered">Not connected to the Meta AI app</string>
    <string name="dat_registration_failed_to_register">Could not connect to the Meta AI app</string>
    <string name="dat_registration_failed_to_unregister">Could not disconnect from the Meta AI app</string>
    <string name="dat_registration_meta_ai_not_installed">The Meta AI app is not installed</string>
    <string name="dat_registration_unknown">Registration failed</string>
```

Append before `</resources>` in `values-zh-rCN/strings.xml`:

```xml

    <!-- Phase A: stream pause + DAT error mapping (spec §5.8, §5.9) -->
    <string name="stream_paused_title">已暂停</string>
    <string name="stream_paused_subtitle">轻触眼镜恢复</string>
    <string name="dat_error_unknown">眼镜发生未知错误</string>
    <string name="dat_session_capability_denied">Meta AI 应用未授予该功能权限</string>
    <string name="dat_session_no_eligible_device">没有已连接的兼容眼镜</string>
    <string name="dat_session_already_stopped">眼镜会话已结束</string>
    <string name="dat_session_idle">眼镜会话尚未开始</string>
    <string name="dat_session_capability_already_added">眼镜相机已在使用中</string>
    <string name="dat_session_capability_not_found">所请求的眼镜功能不可用</string>
    <string name="dat_session_device_disconnected">眼镜已断开连接</string>
    <string name="dat_session_ended_by_device">眼镜结束了会话</string>
    <string name="dat_session_already_exists">眼镜上已有另一个会话在运行</string>
    <string name="dat_session_thermal_critical">眼镜温度过高，会话已停止</string>
    <string name="dat_session_thermal_emergency">眼镜严重过热，会话已关闭</string>
    <string name="dat_session_peak_power_shutdown">眼镜因功耗过高关闭了会话</string>
    <string name="dat_session_battery_critical">眼镜电量严重不足</string>
    <string name="dat_session_dat_app_update_required">眼镜上的 Device Access Toolkit 应用需要更新</string>
    <string name="dat_session_dwa_unavailable">眼镜端应用服务不可用，请检查 Meta AI 应用</string>
    <string name="dat_session_unexpected_error">眼镜会话发生意外错误</string>
    <string name="dat_stream_error">视频流错误</string>
    <string name="dat_stream_critical_error">视频流严重错误，已停止取流</string>
    <string name="dat_stream_hinge_closed">眼镜已折叠，视频流已停止</string>
    <string name="dat_stream_permissions_denied">Meta AI 应用未授予相机权限</string>
    <string name="dat_stream_thermal_hot">眼镜温度较高，视频质量可能下降</string>
    <string name="dat_stream_battery_low">眼镜电量不足</string>
    <string name="dat_stream_peak_power_limit">眼镜为节省电量降低了视频流</string>
    <string name="dat_stream_timeout">视频流超时</string>
    <string name="dat_capture_device_disconnected">无法拍照：眼镜已断开</string>
    <string name="dat_capture_not_streaming">无法拍照：视频流未运行</string>
    <string name="dat_capture_in_progress">正在拍照，请稍候</string>
    <string name="dat_registration_already_registered">已连接到 Meta AI 应用</string>
    <string name="dat_registration_already_unregistered">尚未连接到 Meta AI 应用</string>
    <string name="dat_registration_failed_to_register">无法连接到 Meta AI 应用</string>
    <string name="dat_registration_failed_to_unregister">无法与 Meta AI 应用断开连接</string>
    <string name="dat_registration_meta_ai_not_installed">未安装 Meta AI 应用</string>
    <string name="dat_registration_unknown">注册失败</string>
```

- [ ] **Step 6.2 — Failing test first.** Create `android/app/src/test/java/com/smartview/glassai/glasses/GlassesErrorMessagesTest.kt`:

```kotlin
package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import com.smartview.glassai.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every DAT error case must map to its own string resource (spec §5.9). If the SDK adds an enum
 * case, that case falls through to dat_error_unknown and the assertFalse(...) below fails on
 * purpose so a real string gets added (the distinctness check alone would still pass for a
 * single new case).
 */
class GlassesErrorMessagesTest {

    @Test
    fun everyDeviceSessionErrorHasADistinctString() {
        val ids = DeviceSessionError.entries.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun everyStreamErrorHasADistinctString() {
        val ids = StreamError.entries.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun everyRegistrationErrorHasADistinctString() {
        val ids = RegistrationError.entries.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun captureErrorsHaveDistinctStrings() {
        val cases = listOf(
            CaptureError.DeviceDisconnected,
            CaptureError.NotStreaming,
            CaptureError.CaptureInProgress,
            CaptureError.CaptureFailed,
        )
        val ids = cases.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun cameraBusyMapsToBusyString() {
        assertTrue(GlassesErrorMessages.resId(CameraError.CameraBusy("x")) != 0)
        assertNotEquals(
            GlassesErrorMessages.resId(CameraError.CameraBusy("x")),
            GlassesErrorMessages.resId(CameraError.NoSession),
        )
    }
}
```

(`R.string.dat_error_unknown` is a plain `Int` constant in the JVM unit test; `unitTests.isReturnDefaultValues` is not involved. `DeviceSessionError.entries` etc. need Kotlin ≥ 1.9 — we are on 2.2.21.)

Run it and confirm it fails to compile:

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesErrorMessagesTest' 2>&1 | grep -E "^e: |BUILD"
```

Expected: `e: ... GlassesErrorMessagesTest.kt: Unresolved reference 'GlassesErrorMessages'` and `BUILD FAILED` (main compiles since Task 5, so this time the test source set *is* compiled and the error names the test file).

- [ ] **Step 6.3 — Create `android/app/src/main/java/com/smartview/glassai/glasses/GlassesErrorMessages.kt`:**

```kotlin
package com.smartview.glassai.glasses

import android.content.Context
import androidx.annotation.StringRes
import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import com.smartview.glassai.R

/**
 * Localized (zh/en) messages for every DAT error branch (spec §5.9).
 * The `when`s already list every 0.9.0 constant, so the `else` branches are redundant today
 * (hence the @Suppress); they exist only so a future SDK enum case cannot break compilation.
 * GlassesErrorMessagesTest asserts that no case falls through to dat_error_unknown, so such a
 * case fails the unit test until a string is added.
 */
@Suppress("REDUNDANT_ELSE_IN_WHEN")
object GlassesErrorMessages {

    @StringRes
    fun resId(error: DeviceSessionError): Int = when (error) {
        DeviceSessionError.CAPABILITY_DENIED -> R.string.dat_session_capability_denied
        DeviceSessionError.NO_ELIGIBLE_DEVICE -> R.string.dat_session_no_eligible_device
        DeviceSessionError.SESSION_ALREADY_STOPPED -> R.string.dat_session_already_stopped
        DeviceSessionError.SESSION_IDLE -> R.string.dat_session_idle
        DeviceSessionError.CAPABILITY_ALREADY_ADDED -> R.string.dat_session_capability_already_added
        DeviceSessionError.CAPABILITY_NOT_FOUND -> R.string.dat_session_capability_not_found
        DeviceSessionError.DEVICE_DISCONNECTED -> R.string.dat_session_device_disconnected
        DeviceSessionError.SESSION_ENDED_BY_DEVICE -> R.string.dat_session_ended_by_device
        DeviceSessionError.SESSION_ALREADY_EXISTS -> R.string.dat_session_already_exists
        DeviceSessionError.THERMAL_CRITICAL -> R.string.dat_session_thermal_critical
        DeviceSessionError.THERMAL_EMERGENCY -> R.string.dat_session_thermal_emergency
        DeviceSessionError.PEAK_POWER_SHUTDOWN -> R.string.dat_session_peak_power_shutdown
        DeviceSessionError.BATTERY_CRITICAL -> R.string.dat_session_battery_critical
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> R.string.dat_session_dat_app_update_required
        DeviceSessionError.DWA_UNAVAILABLE -> R.string.dat_session_dwa_unavailable
        DeviceSessionError.UNEXPECTED_ERROR -> R.string.dat_session_unexpected_error
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: StreamError): Int = when (error) {
        StreamError.STREAM_ERROR -> R.string.dat_stream_error
        StreamError.CRITICAL_STREAM_ERROR -> R.string.dat_stream_critical_error
        StreamError.HINGE_CLOSED -> R.string.dat_stream_hinge_closed
        StreamError.PERMISSIONS_DENIED -> R.string.dat_stream_permissions_denied
        StreamError.THERMAL_HOT -> R.string.dat_stream_thermal_hot
        StreamError.BATTERY_LOW -> R.string.dat_stream_battery_low
        StreamError.PEAK_POWER_LIMIT -> R.string.dat_stream_peak_power_limit
        StreamError.TIMEOUT -> R.string.dat_stream_timeout
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: CaptureError): Int = when (error) {
        CaptureError.DeviceDisconnected -> R.string.dat_capture_device_disconnected
        CaptureError.NotStreaming -> R.string.dat_capture_not_streaming
        CaptureError.CaptureInProgress -> R.string.dat_capture_in_progress
        CaptureError.CaptureFailed -> R.string.photo_capture_failed
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: RegistrationError): Int = when (error) {
        RegistrationError.ALREADY_REGISTERED -> R.string.dat_registration_already_registered
        RegistrationError.ALREADY_UNREGISTERED -> R.string.dat_registration_already_unregistered
        RegistrationError.FAILED_TO_REGISTER -> R.string.dat_registration_failed_to_register
        RegistrationError.FAILED_TO_UNREGISTER -> R.string.dat_registration_failed_to_unregister
        RegistrationError.META_AI_NOT_INSTALLED -> R.string.dat_registration_meta_ai_not_installed
        RegistrationError.UNKNOWN -> R.string.dat_registration_unknown
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: CameraError): Int = when (error) {
        is CameraError.CameraBusy -> R.string.glasses_camera_busy
        CameraError.NoSession -> R.string.glasses_no_session
        CameraError.SessionNotStarted -> R.string.glasses_session_timeout
        is CameraError.Sdk -> resId(error.error)
    }

    fun of(context: Context, error: DeviceSessionError): String = context.getString(resId(error))
    fun of(context: Context, error: StreamError): String = context.getString(resId(error))
    fun of(context: Context, error: CaptureError): String = context.getString(resId(error))
    fun of(context: Context, error: RegistrationError): String = context.getString(resId(error))
    fun of(context: Context, error: CameraError): String = context.getString(resId(error))
}
```

- [ ] **Step 6.4 — Run the mapping test (must pass):**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesErrorMessagesTest'
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed. If `everyDeviceSessionErrorHasADistinctString` fails, the 0.9.0 enum has a case missing from the `when`: add a `dat_session_*` string (en + zh) and a branch for it.

- [ ] **Step 6.5 — WearablesViewModel: background frame pipeline + Paused + localized errors.** Apply these exact edits to the file written in Task 4:

(a) Add imports (alphabetical position does not matter; `kotlinx.coroutines.Dispatchers` is already imported):

```kotlin
import com.smartview.glassai.glasses.GlassesErrorMessages
import java.util.concurrent.atomic.AtomicBoolean
```

(b) Add two fields next to `private var camera: GlassesCamera? = null`:

```kotlin
    // Single-threaded worker for frame decoding (never the main thread), like the 0.9.0 sample
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

    // Drop frames while the previous one is still being converted (spec §5.8)
    private val isProcessingFrame = AtomicBoolean(false)
```

(c) In `attachCamera`, replace the `videoJob = viewModelScope.launch { ... }` block with:

```kotlin
        // Frames are decoded on a single-threaded worker, never on the main thread. No conflate():
        // VideoFrame.buffer is only guaranteed valid inside collect {}, so the collector reads it
        // directly and handleVideoFrame copies the bytes as its first step. The AtomicBoolean is
        // the drop policy from spec §5.8: a frame that arrives while the previous one is still being
        // converted is skipped instead of queued.
        videoJob = viewModelScope.launch(frameDispatcher) {
            Log.d(TAG, "Starting video frame collection")
            borrowed.videoFrames.collect { videoFrame ->
                if (videoFrame.isCompressed || videoFrame.isCodecConfig) return@collect
                if (!isProcessingFrame.compareAndSet(false, true)) return@collect
                try {
                    handleVideoFrame(videoFrame)
                } finally {
                    isProcessingFrame.set(false)
                }
            }
        }
```

(`handleVideoFrame` already starts with `buffer.get(byteArray)` into a fresh `ByteArray` — the copy happens before any conversion, so nothing else in it changes.)

(d) In `attachCamera`'s `streamState.collect { ... when (currentState) { ... } }`, replace the combined branch

```kotlin
                    DatStreamState.STARTING,
                    DatStreamState.STARTED,
                    DatStreamState.STOPPING,
                    DatStreamState.PAUSED -> {
                        hasBeenActive = true
                        _streamState.value = StreamState.Waiting
                    }
```

with

```kotlin
                    DatStreamState.STARTING,
                    DatStreamState.STARTED,
                    DatStreamState.STOPPING -> {
                        hasBeenActive = true
                        _streamState.value = StreamState.Waiting
                    }
                    DatStreamState.PAUSED -> {
                        // Paused by a cap-touch tap on the glasses; resumes on the next tap.
                        // Do NOT restart the stream or the session here (spec §5.8).
                        hasBeenActive = true
                        _streamState.value = StreamState.Paused
                    }
```

(e) Replace every SDK-provided message with the app mapping — five sites:

| In function | Find | Replace with |
|---|---|---|
| `startMonitoring` (registration errors) | `setError(error.getLocalizedDescription(getApplication()))` (inside `registrationErrorStream.collect`) | `setError(GlassesErrorMessages.of(getApplication(), error))` |
| `startMonitoring` (session errors) | `setError(error.getLocalizedDescription(getApplication()))` (inside `sessionManager.sessionError.collect`) | `setError(GlassesErrorMessages.of(getApplication(), error))` |
| `attachCamera` (stream errors) | `setError(error.getLocalizedDescription(getApplication()))` (inside `streamErrors.collect`) | `setError(GlassesErrorMessages.of(getApplication(), error))` |
| `attachCamera` (start failure) | `_streamState.value = StreamState.Error(startError.getLocalizedDescription(getApplication()))` | `_streamState.value = StreamState.Error(GlassesErrorMessages.of(getApplication(), startError))` |
| `takePhoto` (capture failure) | `_errorMessage.value = result.error.getLocalizedDescription(getApplication())` | `_errorMessage.value = GlassesErrorMessages.of(getApplication(), result.error)` |

(f) Replace the whole `cameraErrorMessage` function with:

```kotlin
    private fun cameraErrorMessage(error: CameraError): String =
        GlassesErrorMessages.of(getApplication(), error)
```

- [ ] **Step 6.6 — RTMPStreamingViewModel: same treatment.** In the file written in Task 5:

(a) Add imports:

```kotlin
import com.smartview.glassai.glasses.GlassesErrorMessages
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
```

(a') Add two fields next to `private var camera: GlassesCamera? = null`:

```kotlin
    // Single-threaded worker for frame handling (never the main thread), like the 0.9.0 sample
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

    // RTMP drop policy (spec §5.8): a frame arriving while the previous one is still being copied,
    // encoded and previewed is skipped. The encoder still sees every accepted frame in order.
    private val isProcessingFrame = AtomicBoolean(false)
```

(b) In `attachCamera`, replace the `videoJob = viewModelScope.launch { ... }` block with:

```kotlin
        // No conflate(): the SDK buffer is only valid inside collect {}, and handleVideoFrame copies
        // it first (Task 5). Frames are handled on the single-threaded worker.
        videoJob = viewModelScope.launch(frameDispatcher) {
            frameTimestampBase = 0L
            borrowed.videoFrames.collect { videoFrame ->
                if (videoFrame.isCompressed || videoFrame.isCodecConfig) return@collect
                if (!isProcessingFrame.compareAndSet(false, true)) return@collect
                try {
                    handleVideoFrame(videoFrame)
                } finally {
                    isProcessingFrame.set(false)
                }
            }
        }
```

(c) Replace `_uiState.value = UIState.Error(error.getLocalizedDescription(getApplication()))` (stream errors) with `_uiState.value = UIState.Error(GlassesErrorMessages.of(getApplication(), error))`, and `failCamera(startError.getLocalizedDescription(getApplication()))` with `failCamera(GlassesErrorMessages.of(getApplication(), startError))`.

(d) Replace the whole `cameraErrorMessage` function with:

```kotlin
    private fun cameraErrorMessage(error: CameraError): String =
        GlassesErrorMessages.of(getApplication(), error)
```

- [ ] **Step 6.7 — LiveAIScreen status glyph.** In `getStatusText` (`LiveAIScreen.kt:576-595`) replace

```kotlin
    val streamText = when (streamState) {
        is WearablesViewModel.StreamState.Streaming -> "📹"
        is WearablesViewModel.StreamState.Waiting -> "⏳"
        else -> ""
    }
```

with

```kotlin
    val streamText = when (streamState) {
        is WearablesViewModel.StreamState.Streaming -> "📹"
        is WearablesViewModel.StreamState.Waiting -> "⏳"
        is WearablesViewModel.StreamState.Paused -> "⏸ " + stringResource(R.string.stream_paused_subtitle)
        else -> ""
    }
```

- [ ] **Step 6.8 — SimpleLiveStreamScreen paused overlay.** Replace line 57 `val isStreaming = streamState is WearablesViewModel.StreamState.Streaming` with:

```kotlin
    val isStreaming = streamState is WearablesViewModel.StreamState.Streaming
    val isPaused = streamState is WearablesViewModel.StreamState.Paused
```

Immediately before the line `        // UI Overlay` (inside the root `Box`, after the `currentFrame?.let { ... } ?: run { ... }` block) insert:

```kotlin
        // Paused by the glasses (cap-touch tap): keep the last frame, show the hint
        if (isPaused) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.stream_paused_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(AppSpacing.small))
                Text(
                    text = stringResource(R.string.stream_paused_subtitle),
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
        }
```

Make sure these imports exist at the top of `SimpleLiveStreamScreen.kt` (add any that are missing):

```kotlin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.smartview.glassai.R
```

- [ ] **Step 6.9 — Build + all unit tests:**

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Expected: both `BUILD SUCCESSFUL`; 35 tests passed (24 `GlassesSessionManagerTest` + 6 `GlassesPhotoCapturerTest` + 5 `GlassesErrorMessagesTest`), 0 failures.

- [ ] **Step 6.10 — Commit.**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/app/src/main/java/com/smartview/glassai/glasses/GlassesErrorMessages.kt android/app/src/test/java/com/smartview/glassai/glasses/GlassesErrorMessagesTest.kt android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt android/app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt android/app/src/main/java/com/smartview/glassai/ui/screens/SimpleLiveStreamScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(android): decode frames off the main thread with frame dropping, Paused stream state, zh/en messages for every DAT error"
```

---


### Task 6 addendum (controller ruling after the Task 4 review): surface `WearablesViewModel.errorMessage`

**Why:** the Task 4 review found that no screen collects `wearablesViewModel.errorMessage` or reacts to `WearablesViewModel.StreamState.Error`, so the localized session/stream/registration/navigation errors added in Task 4 are unreachable. Task 6 already modifies `LiveAIScreen.kt` and `SimpleLiveStreamScreen.kt` for the Paused state, so the error surface lands here.

**Files:**
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt`, `QuickVisionScreen.kt`, `SimpleLiveStreamScreen.kt`, `HomeScreen.kt` (one block each, placed with the screen's other top-level `LaunchedEffect`s)

**Interfaces:**
- Consumes: `WearablesViewModel.errorMessage: StateFlow<String?>`, `WearablesViewModel.clearError()` (Task 4).
- Produces: nothing new.

- [ ] **Step 6.A1: Add the error toast block to each of the four screens**

Insert this block in each screen's composable body, after the existing `collectAsState()` declarations (imports needed: `android.widget.Toast`, `androidx.compose.runtime.LaunchedEffect`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.collectAsState`, `androidx.compose.ui.platform.LocalContext` — add only the ones the file does not already import):

```kotlin
    val wearablesErrorMessage by wearablesViewModel.errorMessage.collectAsState()
    val errorToastContext = LocalContext.current
    LaunchedEffect(wearablesErrorMessage) {
        val message = wearablesErrorMessage ?: return@LaunchedEffect
        Toast.makeText(errorToastContext, message, Toast.LENGTH_LONG).show()
        wearablesViewModel.clearError()
    }
```

In `HomeScreen.kt` the ViewModel parameter is named `wearablesViewModel` (see `HomeScreen(...)` signature); in the three streaming screens use whatever name the composable already binds the shared `WearablesViewModel` to (grep `wearablesViewModel` in each file; `QuickVisionScreen.kt` and `SimpleLiveStreamScreen.kt` receive it as a parameter, `LiveAIScreen.kt` likewise).

- [ ] **Step 6.A2: Verify**

Run (Git Bash, from `android/`): `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, no new warnings. Then on the emulator with MockDeviceKit disabled, open Home → Quick Vision with no device: the toast shows the localized `glasses_session_failed` / `glasses_no_session` text and clears (no repeat on recomposition).

- [ ] **Step 6.A3: Commit** — include these four files in Task 6's final commit (Step 6.10) rather than a separate commit.

### Task 7: Permission gating — Bluetooth-only SDK gate, lazy `RECORD_AUDIO`, `POST_NOTIFICATIONS`

**Files:**
- Rewrite: `android/app/src/main/java/com/smartview/glassai/MainActivity.kt` (whole file)
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt` (imports; the two comment lines + `LaunchedEffect(Unit)` at lines 75-84)
- Modify: both `strings.xml` (`permission_all_required` text)

**Interfaces:**
- `MainActivity.PERMISSIONS` becomes `[BLUETOOTH, BLUETOOTH_CONNECT, INTERNET]` (matches `samples/CameraAccess/.../MainActivity.kt:47`); `POST_NOTIFICATIONS` is requested on API 33+ but never blocks SDK monitoring; `RECORD_AUDIO` is requested by `LiveAIScreen` when entering Live AI (wake word already requests it in `SettingsScreen.kt:111-130`) (spec §5.3).
- `PorcupineWakeWordService` still checks `RECORD_AUDIO` itself (`PorcupineWakeWordService.kt:131`) and `QuickVisionService` keeps `foregroundServiceType="microphone"`; it is only ever started by the wake-word service, which already holds `RECORD_AUDIO`, so `startForeground` with a microphone type remains legal.

- [ ] **Step 7.1 — Replace `MainActivity.kt` entirely:**

```kotlin
package com.smartview.glassai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.managers.LanguageManager
import com.smartview.glassai.ui.navigation.TurboMetaNavigation
import com.smartview.glassai.ui.theme.TurboMetaTheme
import com.smartview.glassai.viewmodels.WearablesViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : AppCompatActivity() {

    companion object {
        // Android permissions the DAT SDK needs before registration / device monitoring.
        // RECORD_AUDIO is NOT here any more: Live AI and the wake word request it when needed.
        val PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.INTERNET
        )

        // Requested at launch on API 33+ so the foreground-service notifications are visible,
        // but never a prerequisite for the SDK.
        private val OPTIONAL_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }

    val wearablesViewModel: WearablesViewModel by viewModels()

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private var sdkInitialized = false

    // Android permissions launcher - must be registered at creation time
    private val androidPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        val requiredGranted = PERMISSIONS.all { permission ->
            permissionsResult[permission] ?: isGranted(permission)
        }
        if (requiredGranted) {
            initializeSDK()
        } else {
            wearablesViewModel.setError(getString(R.string.permission_all_required))
        }
    }

    // Requesting wearable device permissions via the Meta AI app
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(permissionStatus)
        permissionContinuation = null
    }

    // Request wearables permission in a sequential manner
    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                wearablesPermissionLauncher.launch(permission)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Language Manager (for app language switching)
        LanguageManager.init(this)

        // Check and request permissions
        checkAndRequestPermissions()

        setContent {
            TurboMetaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TurboMetaNavigation(
                        wearablesViewModel = wearablesViewModel,
                        onRequestWearablesPermission = ::requestWearablesPermission
                    )
                }
            }
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        val missing = (PERMISSIONS + OPTIONAL_PERMISSIONS).filter { !isGranted(it) }.toTypedArray()

        if (PERMISSIONS.all { isGranted(it) }) {
            // Required permissions already granted: start monitoring now
            initializeSDK()
        }
        if (missing.isNotEmpty()) {
            // Ask for whatever is missing (required and/or POST_NOTIFICATIONS)
            androidPermissionsLauncher.launch(missing)
        }
    }

    private fun initializeSDK() {
        if (sdkInitialized) return
        sdkInitialized = true

        // Wearables.initialize() already ran in TurboMetaApplication.onCreate().
        // Start observing Wearables state once the Bluetooth runtime permissions are granted.
        wearablesViewModel.startMonitoring()
    }
}
```

- [ ] **Step 7.2 — Strings.** In `values/strings.xml` replace the line

`    <string name="permission_all_required">Please grant all permissions (Bluetooth, Network, Microphone)</string>`

with

`    <string name="permission_all_required">Please grant the Bluetooth and network permissions</string>`

In `values-zh-rCN/strings.xml` replace the existing `permission_all_required` line (whatever its current text) with

`    <string name="permission_all_required">请授予蓝牙和网络权限</string>`

- [ ] **Step 7.3 — LiveAIScreen requests the microphone in context.** Add imports to `LiveAIScreen.kt`:

```kotlin
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
```

Replace the block at lines 75-84 (from `// Connect to AI and start stream when entering LiveAI` through the closing `}` of the `LaunchedEffect`):

```kotlin
    // Connect to AI and start stream when entering LiveAI
    // Note: Device connection is already verified before navigating here
    LaunchedEffect(Unit) {
        // Start video stream first
        wearablesViewModel.startStream()
        // Then connect to AI
        if (!viewModel.isConnected.value) {
            viewModel.connect()
        }
    }
```

with:

```kotlin
    // Phone microphone permission is requested here, in context, instead of at app launch (spec §5.3)
    val micContext = LocalContext.current
    val micDeniedText = stringResource(R.string.permission_microphone)
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (!granted) {
            Toast.makeText(micContext, micDeniedText, Toast.LENGTH_LONG).show()
        }
    }

    // Start the video stream right away; ask for the microphone if we do not have it yet.
    // Note: Device connection is already verified before navigating here
    LaunchedEffect(Unit) {
        wearablesViewModel.startStream()
        if (!micGranted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Connect to AI only once the microphone is available
    LaunchedEffect(micGranted) {
        if (micGranted && !viewModel.isConnected.value) {
            viewModel.connect()
        }
    }
```

(`remember`, `mutableStateOf`, `getValue`/`setValue`, `stringResource` and `R` are already imported in this file — see lines 19-21, 30 and the existing `R.string.*` usages.)

- [ ] **Step 7.4 — Build:**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7.5 — Commit.**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/app/src/main/java/com/smartview/glassai/MainActivity.kt android/app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(android): gate DAT monitoring on Bluetooth permissions only; request RECORD_AUDIO in Live AI and POST_NOTIFICATIONS at launch"
```

---

### Task 8: MockDeviceKit debug screen (debug builds only) + Settings entry

**Files:**
- Create: `android/app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitEntry.kt`
- Create: `android/app/src/release/java/com/smartview/glassai/debug/MockDeviceKitEntry.kt`
- Create: `android/app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitViewModel.kt`
- Create: `android/app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitScreen.kt`
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` (sealed `Screen` lines 25-38; Settings composable lines 181-196; add a route)
- Modify: `android/app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` (imports; signature lines 59-65; insert a section before `// About Section` at line 418)
- Modify: both `strings.xml`

**Interfaces:**
- Consumes (`mwdat-mockdevice 0.9.0`; member names verified against `$SAMPLE/.../mockdevicekit/MockDeviceKitViewModel.kt`, `$SAMPLE/app/src/androidTest/.../InstrumentationTest.kt:512-523` and the marketplace `CHANGELOG.md` 0.9.0 entry): `MockDeviceKit.getInstance(context): MockDeviceKitInterface`; `enable(config: MockDeviceKitConfig = MockDeviceKitConfig())`, `disable()`, `isEnabled`, `pairGlasses(GlassesModel): DatResult<MockGlasses, MockDeviceKitError>`, `unpairDevice(MockDevice)`; `MockGlasses.powerOn()/powerOff()/don()/doff()/fold()/unfold()`, `services.camera.setCameraFeed(Uri)`, `services.camera.setCameraFeed(CameraFacing)`, `services.camera.setCapturedImage(Uri)`, `services.captouch.tap()/tapAndHold()`.
- Produces: `object MockDeviceKitEntry { val isAvailable: Boolean; @Composable fun Screen(onBackClick: () -> Unit) }` with the same FQN in `src/debug` and `src/release` so `main` code can reference it; `Screen.MockDeviceKit` route; `SettingsScreen(onNavigateToMockDeviceKit: () -> Unit = {})`.

Because `mwdat-mockdevice` is `debugImplementation` only, every file that imports `com.meta.wearable.dat.mockdevice.*` lives under `app/src/debug/`. Main code only touches `MockDeviceKitEntry`.

- [ ] **Step 8.1 — Strings.** Append before `</resources>` in `values/strings.xml`:

```xml

    <!-- Phase A: MockDeviceKit debug screen (debug builds only) -->
    <string name="settings_developer">Developer</string>
    <string name="mock_device_kit_title">MockDeviceKit</string>
    <string name="mock_device_kit_subtitle">Simulate glasses without hardware (debug only)</string>
    <string name="mock_device_kit_description">This screen simulates Ray-Ban Meta glasses and mocks their camera and states.</string>
    <string name="mock_enable">Enable MockDeviceKit</string>
    <string name="mock_disable">Disable MockDeviceKit</string>
    <string name="mock_pair_rayban">Pair Ray-Ban Meta</string>
    <string name="mock_paired_count">%1$d paired</string>
    <string name="mock_device_name">Mock glasses</string>
    <string name="mock_unpair">Unpair</string>
    <string name="mock_power">Power</string>
    <string name="mock_donned">Worn</string>
    <string name="mock_unfolded">Unfolded</string>
    <string name="mock_captouch_title">Touchpad gestures (active session only)</string>
    <string name="mock_captouch_tap">Tap (pause/resume)</string>
    <string name="mock_captouch_tap_and_hold">Tap and hold (stop)</string>
    <string name="mock_camera_source">Camera source: %1$s</string>
    <string name="mock_camera_source_none">None</string>
    <string name="mock_camera_source_video">Video file</string>
    <string name="mock_front_camera">Front camera</string>
    <string name="mock_back_camera">Back camera</string>
    <string name="mock_select_image">Select captured image</string>
    <string name="mock_has_captured_image">Captured image set</string>
    <string name="mock_video_hint">Video files must be H.265 (HEVC).</string>
    <string name="mock_camera_permission_denied">Camera permission is required to use the phone camera as the mock feed.</string>
    <string name="mock_open_settings">Open settings</string>
```

Append before `</resources>` in `values-zh-rCN/strings.xml`:

```xml

    <!-- Phase A: MockDeviceKit debug screen (debug builds only) -->
    <string name="settings_developer">开发者</string>
    <string name="mock_device_kit_title">MockDeviceKit</string>
    <string name="mock_device_kit_subtitle">无需真机模拟眼镜（仅调试版）</string>
    <string name="mock_device_kit_description">此页面模拟 Ray-Ban Meta 眼镜及其相机和状态。</string>
    <string name="mock_enable">启用 MockDeviceKit</string>
    <string name="mock_disable">禁用 MockDeviceKit</string>
    <string name="mock_pair_rayban">配对 Ray-Ban Meta</string>
    <string name="mock_paired_count">已配对 %1$d 台</string>
    <string name="mock_device_name">模拟眼镜</string>
    <string name="mock_unpair">取消配对</string>
    <string name="mock_power">电源</string>
    <string name="mock_donned">佩戴</string>
    <string name="mock_unfolded">展开</string>
    <string name="mock_captouch_title">触控板手势（仅会话激活时）</string>
    <string name="mock_captouch_tap">轻触（暂停/恢复）</string>
    <string name="mock_captouch_tap_and_hold">长按（停止）</string>
    <string name="mock_camera_source">相机源：%1$s</string>
    <string name="mock_camera_source_none">无</string>
    <string name="mock_camera_source_video">视频文件</string>
    <string name="mock_front_camera">前置摄像头</string>
    <string name="mock_back_camera">后置摄像头</string>
    <string name="mock_select_image">选择拍照图片</string>
    <string name="mock_has_captured_image">已设置拍照图片</string>
    <string name="mock_video_hint">视频文件必须为 H.265 (HEVC) 编码。</string>
    <string name="mock_camera_permission_denied">使用手机摄像头作为模拟视频源需要相机权限。</string>
    <string name="mock_open_settings">打开设置</string>
```

- [ ] **Step 8.2 — Release stub** `android/app/src/release/java/com/smartview/glassai/debug/MockDeviceKitEntry.kt`:

```kotlin
package com.smartview.glassai.debug

import androidx.compose.runtime.Composable

/** Release builds ship no MockDeviceKit; the Settings entry and route are hidden. */
object MockDeviceKitEntry {
    const val isAvailable: Boolean = false

    @Composable
    fun Screen(onBackClick: () -> Unit) {
        // Intentionally empty: unreachable in release builds.
    }
}
```

- [ ] **Step 8.3 — Debug entry** `android/app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitEntry.kt`:

```kotlin
package com.smartview.glassai.debug

import androidx.compose.runtime.Composable

/** Debug builds expose the MockDeviceKit screen (spec §5.10). */
object MockDeviceKitEntry {
    const val isAvailable: Boolean = true

    @Composable
    fun Screen(onBackClick: () -> Unit) {
        MockDeviceKitScreen(onBackClick = onBackClick)
    }
}
```

- [ ] **Step 8.4 — ViewModel** `android/app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitViewModel.kt`:

```kotlin
package com.smartview.glassai.debug

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.smartview.glassai.R
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MockDeviceInfo(
    val device: MockGlasses,
    val deviceId: String,
    val deviceName: String,
    val hasCameraFeed: Boolean = false,
    val hasCapturedImage: Boolean = false,
    val cameraSource: CameraFacing? = null,
    val isPoweredOn: Boolean = false,
    val isDonned: Boolean = false,
    val isUnfolded: Boolean = false,
)

data class MockDeviceKitUiState(
    val isEnabled: Boolean = false,
    val pairedDevices: List<MockDeviceInfo> = emptyList(),
    val lastError: String? = null,
)

/**
 * Drives MockDeviceKit (debug builds only). Mirrors the 0.9.0 CameraAccess sample:
 * enable/disable, pair up to 3 mock Ray-Ban Meta, power/don/fold, camera feeds, cap-touch.
 * MockDeviceKit.enable() registers the app automatically (MockDeviceKitConfig default), so the
 * Home screen shows "Connected" once a mock device is powered on and worn.
 */
class MockDeviceKitViewModel(private val application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MockDeviceKitViewModel"
        const val MAX_DEVICES = 3
    }

    private val mockDeviceKit = MockDeviceKit.getInstance(application.applicationContext)

    private val _uiState = MutableStateFlow(MockDeviceKitUiState(isEnabled = mockDeviceKit.isEnabled))
    val uiState: StateFlow<MockDeviceKitUiState> = _uiState.asStateFlow()

    fun enable() {
        mockDeviceKit.enable()
        _uiState.update { it.copy(isEnabled = true, lastError = null) }
    }

    fun disable() {
        mockDeviceKit.disable()
        _uiState.update { it.copy(isEnabled = false, pairedDevices = emptyList(), lastError = null) }
    }

    fun pairGlasses() {
        if (_uiState.value.pairedDevices.size >= MAX_DEVICES) return
        mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).fold(
            onSuccess = { device ->
                val info = MockDeviceInfo(
                    device = device,
                    deviceId = UUID.randomUUID().toString(),
                    deviceName = application.getString(R.string.mock_device_name),
                )
                _uiState.update { it.copy(pairedDevices = it.pairedDevices + info, lastError = null) }
                Log.d(TAG, "Paired mock Ray-Ban Meta ${info.deviceId}")
            },
            onFailure = { error, _ ->
                Log.e(TAG, "pairGlasses failed: $error")
                _uiState.update { it.copy(lastError = error.toString()) }
            },
        )
    }

    fun unpairDevice(info: MockDeviceInfo) {
        runCatching { mockDeviceKit.unpairDevice(info.device) }
            .onFailure { Log.e(TAG, "unpairDevice failed", it) }
        _uiState.update { state -> state.copy(pairedDevices = state.pairedDevices.filter { it.deviceId != info.deviceId }) }
    }

    fun powerOn(info: MockDeviceInfo) = execute(info, "powerOn", info.copy(isPoweredOn = true)) { it.powerOn() }

    fun powerOff(info: MockDeviceInfo) =
        execute(info, "powerOff", info.copy(isPoweredOn = false, isDonned = false, isUnfolded = false)) { it.powerOff() }

    /** don() auto-unfolds on the mock device. */
    fun don(info: MockDeviceInfo) = execute(info, "don", info.copy(isDonned = true, isUnfolded = true)) { it.don() }

    fun doff(info: MockDeviceInfo) = execute(info, "doff", info.copy(isDonned = false)) { it.doff() }

    fun fold(info: MockDeviceInfo) = execute(info, "fold", info.copy(isUnfolded = false, isDonned = false)) { it.fold() }

    fun unfold(info: MockDeviceInfo) = execute(info, "unfold", info.copy(isUnfolded = true)) { it.unfold() }

    /** Single cap-touch tap: pauses/resumes the active stream (StreamState.PAUSED). */
    fun tap(info: MockDeviceInfo) = execute(info, "captouch.tap", info) { it.services.captouch.tap() }

    /** Tap-and-hold: stops the active session. */
    fun tapAndHold(info: MockDeviceInfo) = execute(info, "captouch.tapAndHold", info) { it.services.captouch.tapAndHold() }

    /** H.265 video file streamed as the camera feed. Mutually exclusive with the phone camera. */
    fun setCameraFeed(info: MockDeviceInfo, uri: Uri) =
        execute(info, "setCameraFeed(uri)", info.copy(hasCameraFeed = true, cameraSource = null)) {
            it.services.camera.setCameraFeed(uri)
        }

    /** Phone camera as feed (needs android.permission.CAMERA at runtime). */
    fun setCameraFeed(info: MockDeviceInfo, facing: CameraFacing) =
        execute(info, "setCameraFeed($facing)", info.copy(cameraSource = facing, hasCameraFeed = false)) {
            it.services.camera.setCameraFeed(facing)
        }

    /** Image returned by Stream.capturePhoto() (rotated 90 degrees like a real device). */
    fun setCapturedImage(info: MockDeviceInfo, uri: Uri) =
        execute(info, "setCapturedImage", info.copy(hasCapturedImage = true)) {
            it.services.camera.setCapturedImage(uri)
        }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }

    private fun execute(
        info: MockDeviceInfo,
        operation: String,
        updated: MockDeviceInfo,
        block: (MockGlasses) -> Unit,
    ) {
        try {
            Log.d(TAG, "$operation on ${info.deviceId}")
            block(info.device)
            _uiState.update { state ->
                state.copy(pairedDevices = state.pairedDevices.map { if (it.deviceId == updated.deviceId) updated else it })
            }
        } catch (e: Exception) {
            Log.e(TAG, "$operation failed on ${info.deviceId}", e)
            _uiState.update { it.copy(lastError = "$operation: ${e.message}") }
        }
    }
}
```

The helper is named `execute` (mirroring the sample's `executeMockDeviceOperation`) on purpose: a member called `run` would shadow `kotlin.run` inside the class and turn any later stdlib `run { }` into a confusing overload error.

- [ ] **Step 8.5 — Screen** `android/app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitScreen.kt`:

```kotlin
package com.smartview.glassai.debug

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.smartview.glassai.R
import com.smartview.glassai.ui.theme.AppSpacing
import com.smartview.glassai.ui.theme.Error
import com.smartview.glassai.ui.theme.Primary
import com.smartview.glassai.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockDeviceKitScreen(
    onBackClick: () -> Unit,
    viewModel: MockDeviceKitViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mock_device_kit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.mock_device_kit_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (uiState.isEnabled) {
                            Text(
                                text = stringResource(R.string.mock_paired_count, uiState.pairedDevices.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Success,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.mock_device_kit_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    if (uiState.isEnabled) {
                        ActionButton(
                            text = stringResource(R.string.mock_disable),
                            onClick = { viewModel.disable() },
                            containerColor = Error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ActionButton(
                            text = stringResource(R.string.mock_pair_rayban),
                            onClick = { viewModel.pairGlasses() },
                            enabled = uiState.pairedDevices.size < MockDeviceKitViewModel.MAX_DEVICES,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ActionButton(
                            text = stringResource(R.string.mock_enable),
                            onClick = { viewModel.enable() },
                            containerColor = Success,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    uiState.lastError?.let { error ->
                        Text(text = error, color = Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (uiState.isEnabled) {
                uiState.pairedDevices.forEach { info ->
                    MockDeviceCard(info = info, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Primary,
) {
    Button(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = Color.White),
    ) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MockDeviceCard(info: MockDeviceInfo, viewModel: MockDeviceKitViewModel) {
    val context = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setCameraFeed(info, it) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setCapturedImage(info, it) }
    }

    var pendingFacing by remember { mutableStateOf<CameraFacing?>(null) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingFacing?.let { viewModel.setCameraFeed(info, it) }
        } else {
            showCameraPermissionDialog = true
        }
        pendingFacing = null
    }

    val usesPhoneCamera = info.cameraSource != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(info.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(info.deviceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { viewModel.unpairDevice(info) }) {
                    Text(stringResource(R.string.mock_unpair), color = Error)
                }
            }
            HorizontalDivider()

            ToggleRow(stringResource(R.string.mock_power), info.isPoweredOn) { on ->
                if (on) viewModel.powerOn(info) else viewModel.powerOff(info)
            }
            ToggleRow(stringResource(R.string.mock_donned), info.isDonned) { on ->
                if (on) viewModel.don(info) else viewModel.doff(info)
            }
            ToggleRow(stringResource(R.string.mock_unfolded), info.isUnfolded) { on ->
                if (on) viewModel.unfold(info) else viewModel.fold(info)
            }

            Text(
                text = stringResource(R.string.mock_captouch_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                ActionButton(
                    text = stringResource(R.string.mock_captouch_tap),
                    onClick = { viewModel.tap(info) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = stringResource(R.string.mock_captouch_tap_and_hold),
                    onClick = { viewModel.tapAndHold(info) },
                    modifier = Modifier.weight(1f),
                )
            }

            CameraSourceDropdown(
                info = info,
                onFrontCamera = {
                    pendingFacing = CameraFacing.FRONT
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onBackCamera = {
                    pendingFacing = CameraFacing.BACK
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onVideoFile = { videoPicker.launch("video/*") },
            )
            Text(
                text = stringResource(R.string.mock_video_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!usesPhoneCamera) {
                if (info.hasCapturedImage) {
                    Text(
                        text = stringResource(R.string.mock_has_captured_image),
                        style = MaterialTheme.typography.bodySmall,
                        color = Success,
                    )
                }
                ActionButton(
                    text = stringResource(R.string.mock_select_image),
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showCameraPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionDialog = false },
            title = { Text(stringResource(R.string.permission_required)) },
            text = { Text(stringResource(R.string.mock_camera_permission_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    showCameraPermissionDialog = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }) {
                    Text(stringResource(R.string.mock_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraPermissionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CameraSourceDropdown(
    info: MockDeviceInfo,
    onFrontCamera: () -> Unit,
    onBackCamera: () -> Unit,
    onVideoFile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = when {
        info.cameraSource == CameraFacing.FRONT -> stringResource(R.string.mock_front_camera)
        info.cameraSource == CameraFacing.BACK -> stringResource(R.string.mock_back_camera)
        info.hasCameraFeed -> stringResource(R.string.mock_camera_source_video)
        else -> stringResource(R.string.mock_camera_source_none)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.mock_camera_source, currentLabel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mock_front_camera)) },
                onClick = { onFrontCamera(); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mock_back_camera)) },
                onClick = { onBackCamera(); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mock_camera_source_video)) },
                onClick = { onVideoFile(); expanded = false },
            )
        }
        Spacer(modifier = Modifier.height(0.dp))
    }
}
```

- [ ] **Step 8.6 — Navigation route.** In `Navigation.kt`:

(a) Add `import com.smartview.glassai.debug.MockDeviceKitEntry` to the imports.

(b) Add to `sealed class Screen` (after `object LiveAIMode : Screen("live_ai_mode")`):

```kotlin
    object MockDeviceKit : Screen("mock_device_kit")
```

(c) Replace the Settings composable (lines 181-196) with:

```kotlin
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onNavigateToRecords = {
                        navController.navigate(Screen.Records.route)
                    },
                    onNavigateToQuickVisionMode = {
                        navController.navigate(Screen.QuickVisionMode.route)
                    },
                    onNavigateToLiveAIMode = {
                        navController.navigate(Screen.LiveAIMode.route)
                    },
                    onNavigateToMockDeviceKit = {
                        navController.navigate(Screen.MockDeviceKit.route)
                    }
                )
            }
```

(d) Add a new route after the `LiveAIMode` composable (before the closing `}` of `NavHost`):

```kotlin
            composable(Screen.MockDeviceKit.route) {
                MockDeviceKitEntry.Screen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }
```

- [ ] **Step 8.7 — Settings entry.** In `SettingsScreen.kt`:

(a) Add imports:

```kotlin
import com.smartview.glassai.BuildConfig
import com.smartview.glassai.debug.MockDeviceKitEntry
```

(b) Replace the signature (lines 59-65) with:

```kotlin
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onBackClick: () -> Unit,
    onNavigateToRecords: () -> Unit,
    onNavigateToQuickVisionMode: () -> Unit = {},
    onNavigateToLiveAIMode: () -> Unit = {},
    onNavigateToMockDeviceKit: () -> Unit = {}
) {
```

(c) Insert immediately before the line `            // About Section` (line 418):

```kotlin
            // Developer Section (debug builds only)
            if (BuildConfig.DEBUG && MockDeviceKitEntry.isAvailable) {
                SettingsSection(title = stringResource(R.string.settings_developer)) {
                    SettingsItem(
                        icon = Icons.Default.BugReport,
                        title = stringResource(R.string.mock_device_kit_title),
                        subtitle = stringResource(R.string.mock_device_kit_subtitle),
                        onClick = onNavigateToMockDeviceKit
                    )
                }
            }

```

- [ ] **Step 8.8 — Build both variants** (release proves the stub compiles without `mwdat-mockdevice`):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:assembleDebug :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL` for both. If release R8 reports missing classes from `com.meta.wearable.dat.mockdevice`, nothing in `src/main` or `src/release` may import that package — re-check Step 8.2/8.6/8.7 (only `MockDeviceKitEntry` is referenced from main).

- [ ] **Step 8.9 — Commit.**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/app/src/debug android/app/src/release android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt android/app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(android): MockDeviceKit debug screen (pair/power/don/fold, feeds, captouch) reachable from Settings in debug builds"
```

---

### Task 9: Instrumented tests on the emulator with MockDeviceKit (spec §10 仪器测试)

**Files:**
- Create: `android/app/src/androidTest/assets/plant.mp4`, `android/app/src/androidTest/assets/plant.png` (copied from `$SAMPLE/app/src/androidTest/assets/`)
- Create: `android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt`
- No `main` code changes. Dependencies were prepared earlier: `androidx.test` `ext:junit`/`runner`/`rules` (Task 1, Step 1.3/1.5), `androidTestImplementation(libs.mwdat.mockdevice)` and `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` (Task 2, Step 2.2 / Task 1, Step 1.5).

**Interfaces:**
- Consumes (real SDK, `mwdat-mockdevice 0.9.0`; names as in `$SAMPLE/app/src/androidTest/.../InstrumentationTest.kt:512-523,598-617,697-703`): `MockDeviceKit.getInstance(context)`, `.enable()` (default `MockDeviceKitConfig()` auto-registers the app and grants the wearable `CAMERA` permission), `.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow(): MockGlasses`, `.unpairDevice(MockGlasses)`, `.disable()`; `MockGlasses.powerOn()/don()/unfold()`, `services.camera.setCameraFeed(Uri)`, `services.camera.setCapturedImage(Uri)`; `Wearables.registrationState: StateFlow<RegistrationState>`; everything from Task 3/5 (`GlassesSessionManager`, `GlassesPhotoCapturer`, `SessionStartResult`, `CameraResult`, `CameraError`, `PhotoCaptureResult`, `PhotoCaptureOutcome`, `GlassesDisplayState`).
- Produces: six `@Test`s that cover the four §10 bullets — registration (`mockDeviceRegistersAndBecomesTheActiveDevice`), stream + photo through the shared session (`sharedSessionStreamsAndCapturesAPhoto`), the Quick Vision service path through the shared session (`capturerTakesThePhotoThroughTheSharedSession`, `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable`, `capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams`) and "`isDisplayCapable == false` → `addDisplay` is never called" (`displayIsNeverAttachedForANonDisplayCapableDevice`: `displayState` stays `NOT_ATTACHED` across `STARTED`; the JVM test `defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice` pins the same for a display-capable device).

Threading: `GlassesSessionManager` must be driven on the main thread, so every manager call runs inside `onMain { }` = `runBlocking(Dispatchers.Main) { }` from the instrumentation thread. `Wearables.initialize()` has already run because `AndroidJUnitRunner` instantiates `TurboMetaApplication`. MockDeviceKit is enabled *after* that, per test, and disabled in `@After`.

- [ ] **Step 9.1 — Copy the mock media assets:**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
SAMPLE=/c/Users/Lee_L/.claude/plugins/marketplaces/mwdat-android-marketplace/samples/CameraAccess
mkdir -p app/src/androidTest/assets
cp "$SAMPLE/app/src/androidTest/assets/plant.mp4" app/src/androidTest/assets/plant.mp4
cp "$SAMPLE/app/src/androidTest/assets/plant.png" app/src/androidTest/assets/plant.png
ls -la app/src/androidTest/assets/     # expect plant.mp4 (2231432 bytes, H.265) and plant.png (2347789 bytes)
```

- [ ] **Step 9.2 — Create the test** `android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt`:

```kotlin
package com.smartview.glassai.glasses

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec §10 仪器测试: the real DAT 0.9.0 SDK on the emulator, driven by MockDeviceKit —
 * registration, stream, photo, the wake-word capture path (GlassesPhotoCapturer) through the
 * shared session, and "isDisplayCapable == false -> addDisplay() is never called".
 *
 * GlassesSessionManager must be driven on the main thread: every manager call runs inside
 * onMain { } (runBlocking on Dispatchers.Main from the instrumentation thread).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GlassesSessionManagerInstrumentedTest {

    companion object {
        private const val TAG = "GlassesSessionManagerIT"
        private const val OWNER = "InstrumentedTest"
        private const val REGISTRATION_TIMEOUT_MS = 10_000L
        private const val SESSION_TIMEOUT_MS = 20_000L
        private const val STREAM_TIMEOUT_MS = 20_000L
        private const val FRAME_TIMEOUT_MS = 10_000L
    }

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val mockDeviceKit
        get() = MockDeviceKit.getInstance(targetContext)

    private val config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)

    private lateinit var device: MockGlasses
    private lateinit var manager: GlassesSessionManager

    @Before
    fun setUp() {
        grantPermissions()
        // Wearables.initialize() already ran in TurboMetaApplication.onCreate(). The default
        // MockDeviceKitConfig registers the app and grants the wearable CAMERA permission.
        mockDeviceKit.enable()
        device = mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        device.powerOn()
        device.don()
        device.unfold()
        device.services.camera.setCameraFeed(assetUri("plant.mp4"))
        manager = GlassesSessionManager.getInstance(targetContext)
    }

    @After
    fun tearDown() {
        onMain {
            manager.release(OWNER)
            manager.release("WearablesViewModel")
            manager.release("QuickVisionService")
            manager.stopSession()
            withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                manager.sessionState.first { it == DeviceSessionState.STOPPED }
            }
        }
        runCatching { mockDeviceKit.unpairDevice(device) }
            .onFailure { Log.w(TAG, "unpairDevice failed", it) }
        mockDeviceKit.disable()
    }

    // ---- registration + device metadata (spec §5.6, §5.7) ----

    @Test
    fun mockDeviceRegistersAndBecomesTheActiveDevice() = onMain {
        val registration = withTimeout(REGISTRATION_TIMEOUT_MS) {
            Wearables.registrationState.first { it == RegistrationState.REGISTERED }
        }
        assertEquals(RegistrationState.REGISTERED, registration)

        val info = awaitActiveDevice()
        assertTrue(info.name.isNotBlank())
        assertEquals(DeviceType.RAYBAN_META, info.deviceType)
        assertFalse(info.isDisplayCapable)
        assertEquals(DeviceCompatibility.COMPATIBLE, info.compatibility)
        assertFalse(manager.isFirmwareUpdateRequired.value)
    }

    // ---- stream + photo through the shared session (spec §5.4, §5.5) ----

    @Test
    fun sharedSessionStreamsAndCapturesAPhoto() {
        device.services.camera.setCapturedImage(assetUri("plant.png"))
        onMain {
            awaitActiveDevice()
            manager.acquire(OWNER)
            assertEquals(SessionStartResult.STARTED, manager.ensureSessionStarted(SESSION_TIMEOUT_MS))
            assertEquals(DeviceSessionState.STARTED, manager.sessionState.value)

            val result = manager.addCamera(OWNER, config)
            assertTrue("addCamera failed: $result", result is CameraResult.Ready)
            val camera = (result as CameraResult.Ready).camera
            assertEquals(OWNER, manager.currentCameraOwner)

            assertNull(camera.startStream())
            withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.STREAMING } }

            val frame = withTimeout(FRAME_TIMEOUT_MS) {
                camera.videoFrames.first { !it.isCompressed && !it.isCodecConfig }
            }
            assertTrue(frame.width > 0 && frame.height > 0)

            val photo = camera.capturePhoto()
            assertTrue("capturePhoto failed: $photo", photo is PhotoCaptureResult.Success)

            manager.stopCamera(OWNER)
            assertNull(manager.currentCameraOwner)
            manager.release(OWNER)
            assertEquals(0, manager.ownerCount)
            withTimeout(SESSION_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STOPPED } }
            assertFalse(manager.hasSession)
        }
    }

    // ---- Quick Vision service path through the shared session (spec §5.5, §10) ----

    @Test
    fun capturerTakesThePhotoThroughTheSharedSession() {
        device.services.camera.setCapturedImage(assetUri("plant.png"))
        onMain {
            val outcome = capturer(decodePhoto = { "photo" }, decodeFrame = { "frame" }).capture()

            assertEquals(PhotoCaptureOutcome.Captured("photo", fromVideoFrame = false), outcome)
            assertNull(manager.currentCameraOwner)
            assertEquals(0, manager.ownerCount)
            withTimeout(SESSION_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STOPPED } }
        }
    }

    /** The frame fallback needs a real VideoFrame (no JVM constructor): force it with an undecodable photo. */
    @Test
    fun capturerFallsBackToVideoFrameWhenPhotoIsUndecodable() {
        device.services.camera.setCapturedImage(assetUri("plant.png"))
        onMain {
            val outcome = capturer(decodePhoto = { null }, decodeFrame = { "frame" }).capture()

            assertEquals(PhotoCaptureOutcome.Captured("frame", fromVideoFrame = true), outcome)
            assertNull(manager.currentCameraOwner)
            assertEquals(0, manager.ownerCount)
        }
    }

    @Test
    fun capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams() = onMain {
        awaitActiveDevice()
        manager.acquire("WearablesViewModel")
        assertEquals(SessionStartResult.STARTED, manager.ensureSessionStarted(SESSION_TIMEOUT_MS))
        val live = (manager.addCamera("WearablesViewModel", config) as CameraResult.Ready).camera
        assertNull(live.startStream())
        withTimeout(STREAM_TIMEOUT_MS) { live.streamState.first { it == DatStreamState.STREAMING } }

        val outcome = capturer(decodePhoto = { "photo" }, decodeFrame = { "frame" }).capture()

        assertEquals(
            PhotoCaptureOutcome.CameraUnavailable(CameraError.CameraBusy("WearablesViewModel")),
            outcome,
        )
        assertEquals("WearablesViewModel", manager.currentCameraOwner)
        assertEquals(DatStreamState.STREAMING, live.streamState.value) // the other owner keeps streaming
        assertEquals(1, manager.ownerCount)

        manager.release("WearablesViewModel")
        withTimeout(SESSION_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STOPPED } }
    }

    // ---- Phase A never attaches the Display (spec §10) ----

    @Test
    fun displayIsNeverAttachedForANonDisplayCapableDevice() = onMain {
        val info = awaitActiveDevice()
        assertFalse(info.isDisplayCapable)

        manager.acquire(OWNER)
        assertEquals(SessionStartResult.STARTED, manager.ensureSessionStarted(SESSION_TIMEOUT_MS))
        // DisplayAttacher.None is wired in getInstance(): STARTED must not trigger addDisplay().
        assertEquals(GlassesDisplayState.NOT_ATTACHED, manager.displayState.value)

        manager.release(OWNER)
        withTimeout(SESSION_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STOPPED } }
        assertEquals(GlassesDisplayState.NOT_ATTACHED, manager.displayState.value)
    }

    // ---- helpers ----

    private fun <T> onMain(block: suspend () -> T): T = runBlocking(Dispatchers.Main) { block() }

    private suspend fun awaitActiveDevice(): GlassesDeviceInfo =
        withTimeout(REGISTRATION_TIMEOUT_MS) { manager.activeDevice.first { it != null } }!!

    private fun capturer(
        decodePhoto: (PhotoData) -> String?,
        decodeFrame: (VideoFrame) -> String?,
    ) = GlassesPhotoCapturer(
        sessionManager = manager,
        owner = "QuickVisionService",
        config = config,
        decodePhoto = decodePhoto,
        decodeFrame = decodeFrame,
    )

    private fun grantPermissions() {
        listOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.CAMERA",
            "android.permission.INTERNET",
        ).forEach { permission ->
            try {
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand("pm grant ${targetContext.packageName} $permission")
                    .close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to grant $permission", e)
            }
        }
    }

    /** Copies a test-APK asset into the app's cache and returns a file Uri the mock camera can read. */
    private fun assetUri(assetName: String): Uri {
        val outFile = File(targetContext.cacheDir, assetName)
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile)
    }
}
```

- [ ] **Step 9.3 — Compile the instrumented test source set** (no emulator needed yet):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: `BUILD SUCCESSFUL`. If `Unresolved reference 'MockDeviceKit'`, `androidTestImplementation(libs.mwdat.mockdevice)` from Step 2.2 is missing in `app/build.gradle.kts`; if `Unresolved reference 'AndroidJUnit4'`, the three `androidTestImplementation(libs.androidx.test.*)` lines from Step 1.5 are missing.

- [ ] **Step 9.4 — Boot the emulator (Git Bash; it keeps running in the background and is reused by Task 10):**

```bash
"/c/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_5 -no-snapshot-load -no-boot-anim -camera-back virtualscene -camera-front emulated > /dev/null 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
adb devices
```

Expected: one `emulator-5554  device` line. (`Medium_Phone_API_36.0` works the same way — replace `-avd Pixel_5`.)

- [ ] **Step 9.5 — Run the instrumented tests:**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:connectedDebugAndroidTest 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`; `app/build/reports/androidTests/connected/debug/index.html` shows 6 tests, 0 failures (`GlassesSessionManagerInstrumentedTest`). AGP installs `app-universal-debug.apk` on the x86_64 emulator because the ABI splits are ARM-only and `isUniversalApk = true`. Known failure signatures and the one allowed fix for each:
- `No compatible APK for device` / `INSTALL_FAILED_NO_MATCHING_ABIS` → add `"x86_64"` to `include("arm64-v8a", "armeabi-v7a")` in the `splits.abi` block of `app/build.gradle.kts` for this run and revert it before committing.
- `ensureSessionStarted` returns `CREATE_FAILED` with `NO_ELIGIBLE_DEVICE` in logcat (`adb logcat -s GlassesSessionManager:D`) → the mock device was not donned/unfolded before `awaitActiveDevice()`; check that `setUp()` still calls `powerOn()`, `don()`, `unfold()` in that order.
- `sharedSessionStreamsAndCapturesAPhoto` times out waiting for `STREAMING` → the copied `plant.mp4` must be the H.265 file from `$SAMPLE` (Step 9.1); re-copy it.

- [ ] **Step 9.6 — Commit.**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai
git add android/app/src/androidTest
git commit -m "test(android): instrumented MockDeviceKit suite for the shared glasses session (registration, stream, photo, capturer path, no Display attach)"
```

---

### Task 10: Manual emulator verification with MockDeviceKit (no code changes)

**Files:** none created or modified. This task produces a verified build; there is nothing to commit (if `git status --short` is non-empty at the end, something in Tasks 1–9 was left uncommitted — commit it with the message of the task it belongs to).

**Interfaces:** consumes the debug APK `android/app/build/outputs/apk/debug/app-universal-debug.apk` and the AVDs `Pixel_5` (API 31, x86_64) / `Medium_Phone_API_36.0` under `C:/Users/Lee_L/.android/avd`.

- [ ] **Step 10.1 — Final full build + `./gradlew test` from a clean tree** (spec §5 验证 names `./gradlew test`, which runs both unit-test variants; `testReleaseUnitTest` additionally compiles `src/release/.../MockDeviceKitEntry.kt` against the test classpath):

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
git -C .. status --short            # expect empty
./gradlew clean :app:assembleDebug test
```

Expected: `BUILD SUCCESSFUL`; both `app/build/reports/tests/testDebugUnitTest/index.html` and `app/build/reports/tests/testReleaseUnitTest/index.html` show 35 tests (24 + 6 + 5), 0 failures.

- [ ] **Step 10.2 — Boot the emulator** (skip if the one from Step 9.4 is still running; Git Bash, the emulator keeps running in the background):

```bash
"/c/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_5 -no-snapshot-load -no-boot-anim -camera-back virtualscene -camera-front emulated > /dev/null 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
adb devices
```

Expected: one `emulator-5554  device` line. (`Medium_Phone_API_36.0` can be used the same way — replace `-avd Pixel_5` with `-avd Medium_Phone_API_36.0`; on API 36 the `POST_NOTIFICATIONS` dialog appears at launch.)

- [ ] **Step 10.3 — Install the universal APK and grant the runtime permissions the DAT SDK needs** (the ABI-split APKs are ARM-only; checklist item 15 later re-installs *without* these grants):

```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.smartview.glassai android.permission.CAMERA
adb shell screencap -p /sdcard/Download/mock_capture.png      # any PNG works as the mock "captured photo"
```

Expected: `Success` for the install; the two `pm grant` calls print nothing.

- [ ] **Step 10.4 — Launch and tail the relevant logs in a second Git Bash window:**

```bash
adb shell am start -n com.smartview.glassai/.MainActivity
adb logcat -c
adb logcat -s GlassesSessionManager:D WearablesViewModel:D QuickVisionService:D RTMPStreamingVM:D MockDeviceKitViewModel:D TurboMetaApplication:E AndroidRuntime:E
```

- [ ] **Step 10.5 — Manual checklist (tick each in order; every line must hold):**

1. App launches without a crash; on API 31 no permission dialog for microphone appears (only Bluetooth if the emulator asks); logcat shows no `AndroidRuntime` error and no `DAT SDK initialize failed`.
2. Home shows the device card as **Disconnected** with a **Connect Glasses** button.
3. Settings → **Developer** section is visible (debug build) → tap **MockDeviceKit**.
4. Tap **Enable MockDeviceKit** → go back Home: the card still says Disconnected (no device yet) but `WearablesViewModel` logs `Registration state changed: REGISTERED` (MockDeviceKit auto-registers).
5. MockDeviceKit → **Pair Ray-Ban Meta** → switch **Power** on → switch **Worn** on (Unfolded flips on automatically). Back on Home the card shows **Mock glasses** / **Connected** (`Active device: Mock glasses (RAYBAN_META)` in logcat). This exercises `activeDeviceFlow` + `devicesMetadata` through `WearablesDatAdapter`.
6. MockDeviceKit → camera source **Back camera** (grant the CAMERA dialog if shown; it was pre-granted by `pm grant`) → **Select captured image** → pick `Download/mock_capture.png`.
7. Home → **Live Stream** wide card: the camera-permission check passes (MockDeviceKit grants by default); the preview shows the emulator's virtual-scene camera within ~5 s. Logcat: `acquire(WearablesViewModel)`, `session state: STARTING`, `session state: STARTED`, `addCamera(WearablesViewModel) ok`, `Stream state: STREAMING`.
8. MockDeviceKit (open it from Settings in a second pass, or use a second paired-device card) → **Tap (pause/resume)**: the Live Stream screen shows the **Paused / Tap your glasses to resume** overlay; tap again → preview resumes. Logcat: `Stream state: PAUSED` then `STREAMING`.
9. Leave the Live Stream screen: logcat shows `stopCamera(WearablesViewModel)`, `release(WearablesViewModel) owners=[]`, `stopSession`. Re-enter: a new session is created (`createSession` → `STARTED` again) — proves STOPPED is terminal and the ref count recreates.
10. Home → **Quick Vision** (requires a Vision API key; if none is configured the flow stops at the API-key dialog — configure any non-empty key in Settings to pass the gate, the analysis call itself may then fail, which is fine). The screen starts the stream (now waiting up to 20 s with early exit on Error, Step 4.3a), calls `takePhoto()`; `capturedPhoto` becomes the mock PNG (rotated 90°). Logcat: `Photo captured: WxH`. If no captured image was set the mock's `capturePhoto()` behaviour is undocumented: either `Photo capture failed:` is logged and the screen falls back to `currentFrame`, or a placeholder image is returned — both are acceptable here; the deterministic fallback path is covered by Task 9's `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable`.
11. Switch app language to 中文 in Settings and re-check strings on the Home card and MockDeviceKit screen (Chinese text appears for every new string).
12. Fold test: on MockDeviceKit switch **Unfolded** off while Live Stream is open → the stream ends and the screen goes back to the loading state; logcat shows `session state: STOPPED`; leaving and re-entering the screen recreates the session.
13. `CameraBusy` test (wake-word path is not available on the emulator): open Live Stream, then from a second Git Bash run
    `adb root && adb shell am start-foreground-service -n com.smartview.glassai/.services.QuickVisionService -a com.smartview.glassai.CAPTURE_AND_ANALYZE`
    (works only on rootable `google_apis` images; if `adb root` reports `adbd cannot run as root in production builds`, skip this line). Expected logcat: `addCamera(QuickVisionService) refused: camera held by WearablesViewModel` and the service finishes with `Broadcast status: error` → `finished`; the Live Stream preview keeps running.
14. `./gradlew :app:assembleRelease` was green in Task 8 (release stub) — no further action.
15. **Grant-after-initialize test** (spec §5.3 moves `Wearables.initialize` to `Application.onCreate()`, i.e. *before* any Bluetooth grant, while the marketplace skills initialize only after the grant): uninstall, re-install **without** any `pm grant`, launch, and accept the in-app Bluetooth permission dialog:
    ```bash
    adb uninstall com.smartview.glassai
    adb install app/build/outputs/apk/debug/app-universal-debug.apk
    adb shell am start -n com.smartview.glassai/.MainActivity
    ```
    Accept the Bluetooth (and, on API 33+, notification) dialog, then Settings → Developer → MockDeviceKit → **Enable** → **Pair Ray-Ban Meta** → **Power** on → **Worn** on → back on Home. Expected: the device card reaches **Mock glasses / Connected** and logcat shows `Registration state changed: REGISTERED` followed by `Active device: Mock glasses (RAYBAN_META)` — discovery works although the grant arrived after `initialize`. If the card stays Disconnected for more than 30 s, the fallback is: in `MainActivity.initializeSDK()` (Task 7) re-run `Wearables.initialize(applicationContext)` right before `wearablesViewModel.startMonitoring()` (idempotent per the 0.9.0 sample, which also calls it from an Activity) and re-test; record the deviation in Step 10.6.

- [ ] **Step 10.6 — Record the result.** Append the checklist outcome (date, AVD name, the `connectedDebugAndroidTest` result from Step 9.5, any deviations — in particular item 15) as a comment in the final Phase A merge/PR description; no repository file changes are required for this task.

---

## Self-Review

Spec section → task coverage (design spec `docs/superpowers/specs/2026-09-10-android-v2-design.md`):

| Spec item | Where it is implemented / checked |
|---|---|
| §3 decision 2 — Kotlin 2.2.21 / AGP 8.11.1 / Gradle 8.14.1 / SDK 36 / JVM 17 / activity-compose ≥ 1.10 / BOM 2026.05.01 / official `gradlew.bat` | Task 1 (Steps 1.1–1.6a) |
| §3 decision 5 — all work on branch `android-v2` | Step 1.0 creates/checks out the branch; Step 1.8's commit is guarded by `git branch --show-current` |
| §5.1 — `mwdat = 0.9.0`, `mwdat-display`, `debugImplementation(mwdat-mockdevice)` | Task 2 (Steps 2.1–2.2, 2.7) |
| §5.2 — manifest placeholders from `local.properties` (default `0`), `CLIENT_TOKEN`, `CAMERA` + `uses-feature`, no `DAM_ENABLED` | Task 2 (Steps 2.2–2.3, 2.6) |
| §5.3 — `Wearables.initialize` in `TurboMetaApplication`; no `RECORD_AUDIO` prerequisite; mic requested by Live AI/wake word; `POST_NOTIFICATIONS` | Task 2 (Steps 2.4–2.5) + Task 7; the grant-after-initialize path is exercised by Task 10 item 15 |
| §5.4 / §4 — `glasses/GlassesSessionManager.kt` + `GlassesSessionState.kt`, singleton, ref-count, camera lending with `CameraBusy`, state/error flows, Display hook, waits for the outgoing session's `STOPPED` before `createSession` | Task 3 (`DisplayAttacher` = Phase C extension point; `ensureSessionStarted` + `stoppingSession`) |
| §5.5 — three camera call sites on the manager; `WearablesViewModel.StreamState` + `currentFrame` contract kept; SDK enum aliased `DatStreamState`; `QuickVisionService` uses `capturePhoto()`, 12 s budgets, `CameraBusy`; RTMP migrated | Task 4 (`WearablesViewModel`, Quick Vision screen wait budget 12 s), Task 5 (`GlassesPhotoCapturer` + `QuickVisionService`, `RTMPStreamingViewModel`) |
| §5.6 — `RegistrationState` enum, `registrationErrorStream` subscribed first in `startMonitoring()`, `startRegistration/startUnregistration/disconnect(Activity)`, Compose `LocalActivity.current` | Task 4 (Steps 4.2–4.3) |
| §5.7 — `devicesMetadata` → name/type/`isDisplayCapable`/`compatibility` (re-evaluated on every `Wearables.devices` change); `DEVICE_UPDATE_REQUIRED` → `openFirmwareUpdate`; `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` → `openDATGlassesAppUpdate` buttons | Task 3 (`WearablesDatAdapter.activeDeviceInfoFlow`, `isFirmwareUpdateRequired`, `isDatAppUpdateRequired`) + Task 4 (`UpdateRequiredRow`) |
| §5.8 — frames off the main thread (`Dispatchers.Default.limitedParallelism(1)`), drop while busy (`isProcessingFrame`), SDK buffer copied first, no `conflate()`, skip `isCompressed`/`isCodecConfig`, `PAUSED` → `Paused` ("轻触眼镜恢复") | Task 5 (`GlassesPhotoCapturer` decodes on `Dispatchers.Default`; RTMP `handleVideoFrame` copies first) + Task 6 (Steps 6.5–6.8) |
| §5.9 — localized zh/en text for every `StreamError` / `DeviceSessionError` branch (plus `CaptureError`, `RegistrationError`) | Task 6 (`GlassesErrorMessages`, strings, exhaustiveness + no-fallthrough test) |
| §5.10 — MockDeviceKit debug page (debug only, Settings entry): enable/disable, pair `RAYBAN_META`, power, don/doff, fold, video/image source, phone camera, captouch tap | Task 8 |
| §5 验证 — `assembleDebug` green; `Pixel_5` + MockDeviceKit registration / stream / photo / Quick Vision; `./gradlew test` passes the new unit tests | `assembleDebug`: Steps 5.8, 6.9, 7.4, 8.8, 10.1; MockDeviceKit flows: Task 9 (automated, 6 instrumented tests) + Task 10 (manual checklist, 15 items); `./gradlew test` (both variants, 35 tests): Step 10.1 |
| §10 单元测试 — ref-count state machine with fake DAT interfaces | Task 3 (`FakeDat.kt`, `GlassesSessionManagerTest`: 24 tests incl. the STOPPING → STOPPED re-create race), Task 5 (`GlassesPhotoCapturerTest`: 6 incl. the retry when the session disappears during start), Task 6 (`GlassesErrorMessagesTest`: 5) |
| §10 仪器测试 — registration, stream, photo, Quick Vision service through the shared session, `isDisplayCapable == false` → no `addDisplay` | Task 9 `GlassesSessionManagerInstrumentedTest`: `mockDeviceRegistersAndBecomesTheActiveDevice`, `sharedSessionStreamsAndCapturesAPhoto`, `capturerTakesThePhotoThroughTheSharedSession`, `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable`, `capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams`, `displayIsNeverAttachedForANonDisplayCapableDevice` (+ JVM `defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice`) |
| §11 risk row 1 — toolchain bump isolated before any migration | Task 1 ends green on 0.4.0; Task 2 bumps the SDK |
| Feature-gap row 62 — MockDeviceKit debug UI | Task 8 |
| Global constraint — zh + en for every new string | Tasks 4, 6, 7, 8 each add both files in the same step |
| Global constraint — commit after every task | Steps 1.8, 2.9, 3.8, 4.5, 5.10, 6.10, 7.5, 8.9, 9.6 (Task 10 has no file changes) |
| Global constraint — line numbers vs. quoted anchors | Anchors re-checked against the current files: HomeScreen 54-59 / 253-259 (incl. closing `)`), LiveAIScreen 75-84, SettingsScreen 418, QuickVisionScreen 185; quoted text wins on any disagreement |

Placeholder audit: no TBD/TODO; every code step carries the full Kotlin/Gradle/XML; the only "if this fails" instructions name the exact command and the exact file/line to fix (Steps 1.6, 1.6a, 2.7, 3.7, 5.9, 6.4, 8.8, 9.3, 9.5, 10.5 items 13 and 15).

Assumptions the implementer should keep in mind (all backed by the 0.9.0 sample code, none verified against a physical device):
1. `DatResult` members used: `fold(onSuccess, onFailure = { error, cause -> })`, `onFailure { error, _ -> }` (Steps 2.4, 4.2), `getOrNull()` (Step 4.2), `getOrDefault()` (Step 7.1), `getOrThrow()` (Step 9.2) — all present in 0.9.0 (`DatResult` is a regular class, see `$SAMPLE/.../MockDeviceKitViewModel.kt:55-74`, `CameraViewModel.kt`, `InstrumentationTest.kt:516`); no factory functions (`success`/`failure`) are called anywhere, which is why the gateway interfaces return Kotlin sealed results.
2. `Device`, `DeviceType`, `DeviceCompatibility`, `DeviceSessionState`, `DeviceSessionError`, `StreamError`, `CaptureError`, `PhotoData.HEIC(ByteBuffer)` and `StreamConfiguration()` are constructible/referenced from plain JVM unit tests (plain Kotlin classes in `mwdat-core`/`mwdat-camera`; `android.util.Log` is neutralised by `unitTests.isReturnDefaultValues = true`). `VideoFrame` has no JVM-usable constructor, so the frame-fallback branch is tested on the emulator only.
3. Decoded `VideoFrame.buffer` is I420 (unchanged assumption from 1.5.0; the RTMP encoder is configured for `COLOR_FormatYUV420Planar`) and is only guaranteed valid inside `collect {}` — hence "copy first, never `conflate()`".
4. `QuickVisionService` keeps `foregroundServiceType="microphone"` because it is only launched by the wake-word service that already holds `RECORD_AUDIO`; switching to `connectedDevice` is deliberately out of Phase A scope.
5. MockDeviceKit's `capturePhoto()` behaviour when no captured image was set is undocumented, so the instrumented fallback test forces the path with an undecodable photo (`decodePhoto = { null }`) instead of relying on a mock failure; Task 10 item 10 accepts both mock behaviours.
6. `Dispatchers.Main` is available to the instrumented test through `kotlinx-coroutines-android`, which the app already pulls transitively (`QuickVisionService` uses `Dispatchers.Main` today).
7. `Wearables.initialize` before the Bluetooth grant (spec §5.3) deviates from the marketplace skills' "request permissions first" advice; Task 10 item 15 is the acceptance check and names the fallback if discovery does not start after the late grant.
