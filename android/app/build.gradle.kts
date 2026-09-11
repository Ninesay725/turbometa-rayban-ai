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

android {
    namespace = "com.smartview.glassai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartview.glassai"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Meta Wearables Device Access Toolkit attestation values (see localProperties above)
        manifestPlaceholders["mwdat_application_id"] =
            providers.gradleProperty("mwdat_application_id").orNull
                ?: localProperties.getProperty("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] =
            providers.gradleProperty("mwdat_client_token").orNull
                ?: localProperties.getProperty("mwdat_client_token", "0")

        // DAT SDK version for device.info (OpenClaw) and the Settings About row, fed from the
        // version catalog so it cannot drift from the dependency.
        buildConfigField("String", "MWDAT_VERSION", "\"${libs.versions.mwdat.get()}\"")
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
    implementation(libs.mwdat.display)
    debugImplementation(libs.mwdat.mockdevice)
    // androidTest compiles against the debug variant; declare MockDeviceKit explicitly so the
    // instrumented tests (Task 9) do not depend on AGP's tested-variant classpath inheritance.
    androidTestImplementation(libs.mwdat.mockdevice)

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
    // Ed25519 device identity for OpenClaw (Android 12 has no java.security Ed25519 provider)
    implementation(libs.tink.android)

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
    // OpenClaw / Fun-ASR protocol tests run against an in-process WebSocket server
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented tests (emulator + MockDeviceKit, Task 9)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
