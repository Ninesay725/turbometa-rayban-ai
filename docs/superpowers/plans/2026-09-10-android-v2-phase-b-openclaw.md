# Android 2.0 Phase B: OpenClaw Port + Stability + Version/Docs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the iOS v2.0.0 OpenClaw integration (gateway node client, Ed25519 device identity, command router, Fun-ASR voice input, chat + settings UI) to the Android app on DAT 0.9.0, land the Phase A follow-ups it depends on (frame conversions, manager-level `latestFrame`, testable ViewModels), apply the iOS 2.0 stability fixes (RTMP error residual, first-frame timeout, encrypted stream key, WebSocket resource cleanup, capture budget), and ship version 2.0.0 with updated docs.

**Architecture:** `services/openclaw/OpenClawNodeService` is a process singleton that owns one OkHttp WebSocket to the OpenClaw Gateway, speaks the iOS wire protocol field-for-field (spec §3 decision 3, research §8.6), and hands `node.invoke` requests to `OpenClawCommandRouter`, which reads glasses frames through a `GlassesFrameProvider` backed by `GlassesSessionManager.latestFrame` (published by whichever feature currently borrows the camera) with a `GlassesPhotoCapturer` fallback when nobody holds the camera. `OpenClawViewModel` drives `OpenClawChatScreen`/`OpenClawSettingsScreen` and uses `FunASRService` (DashScope Fun-ASR realtime over a second WebSocket) for voice input; every network/JSON class is constructible without Android so the protocol is unit-tested on the JVM against OkHttp's `MockWebServer`.

**Tech Stack:** Kotlin 2.2.21, AGP 8.11.1, compileSdk/targetSdk 36, minSdk 31, JVM 17 target, Compose BOM 2026.05.01, DAT SDK 0.9.0 (`mwdat-core/camera/display`, `mwdat-mockdevice` debug), OkHttp 4.12.0 (+ `mockwebserver` 4.12.0 for tests), Gson 2.10.1, Google Tink `tink-android` 1.20.0 (Ed25519; identical to the version `mwdat-core:0.9.0` already declares in its POM, `tink-android-1.20.0.pom` verified HTTP 200 on repo1.maven.org, and the jar in the local Gradle cache contains `com.google.crypto.tink.subtle.Ed25519Sign(byte[] seed)`, `Ed25519Sign.KeyPair.newKeyPairFromSeed(byte[])`, `Ed25519Verify(byte[] publicKey)`), `androidx.security:security-crypto` 1.1.0-alpha06 (`EncryptedSharedPreferences`), kotlinx-coroutines-test 1.10.2, JUnit 4.13.2.

## Global Constraints

- Toolchain is exactly Phase A's: Kotlin `2.2.21`, AGP `8.11.1`, Gradle wrapper 8.14.1, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 31`, `JavaVersion.VERSION_17` / `JvmTarget.JVM_17`; DAT `mwdat = "0.9.0"`. Do not change any of these.
- Run Gradle only from Git Bash inside `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android` as `./gradlew …` (never `gradlew.bat`); JDK 21 is on the host, the build targets 17.
- All work is on branch `android-v2` (base `a067ed4`). Commit after every task with the exact `git` commands given; never squash tasks together.
- Never print, cat, or commit `android/local.properties`.
- Every new user-visible string goes into BOTH `android/app/src/main/res/values/strings.xml` and `android/app/src/main/res/values-zh-rCN/strings.xml` with the exact text given in this plan; a key present in one file and missing in the other is a task failure.
- `GlassesSessionManager` public mutators (`acquire/release/ensureSession/addCamera/stopCamera/stopSession/resetForTests`) are main-thread-only. The one documented exception added in this phase is `publishFrame()`, which only touches a `StateFlow` and a volatile field and is called from the frame worker.
- Protocol field names are identical to iOS per research §8.6: envelopes `type,id,method,params,ok,payload,error.code,error.message`; `connect` params `minProtocol/maxProtocol = 3`, `client{id,displayName,version,mode,platform,modelIdentifier}`, `role`, `scopes`, `caps`, `commands`, `auth{token}`, `device{id,publicKey,signature,signedAt,nonce}`; `chat.send` params `sessionKey,message,idempotencyKey,attachments[{type,mimeType,content}]`; invoke inbound `params{id,command,params|paramsjson,timeoutMs|timeoutms}`; outbound `node.invoke.result` with `payloadjson` string; `tick{ts}` every 15 s; `NOT_PAIRED` → pairing state; `UNSUPPORTED` reply for unknown requests.
- Client identity constants: `client.id = "openclaw-android"`, `platform = "android"` (used in BOTH the JSON and the signature string), `displayName = "Ray-Ban Meta Glasses"`, `version = BuildConfig.VERSION_NAME`, `mode = "node"`, `role = "operator"`, `scopes = ["operator.read","operator.write"]`, `caps = ["camera"]`, `commands = ["camera.snap","camera.list","device.status","device.info"]`, `sessionKey = "turbometa-chat"`, `modelIdentifier = Build.MODEL`.
- No background `camera.snap`: `SessionFrameProvider` answers `NOT_READY` whenever no Activity is started (spec §1 non-goals). Chat history is in memory only.
- ASR microphone reuses the Live AI phone/glasses toggle semantics (`BluetoothAudioManager.AudioSource`), `AudioRecord` 16 kHz mono PCM16.
- Test counts in this plan are MINIMUMS ("at least N"), never exact numbers; adding tests is always allowed.
- JVM unit tests run with `unitTests.isReturnDefaultValues = true` (already set): `android.util.Log` returns 0, `android.util.Base64` returns null — therefore all new protocol code uses `java.util.Base64`, `java.security.MessageDigest`, Gson, and OkHttp only, never `android.util.Base64`.
- `./gradlew :app:connectedDebugAndroidTest` is blocked on this host (Windows `winnat` reserves TCP 9624-9633); instrumented tests run through `adb shell am instrument` exactly as in Task 9 of this plan.

## File Structure

| Path (relative to `android/`) | Action | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | Modify | add `tink = "1.20.0"`, `tink-android`, `okhttp-mockwebserver` |
| `app/build.gradle.kts` | Modify | `implementation(libs.tink.android)`, `testImplementation(libs.okhttp.mockwebserver)`, `androidTestImplementation(libs.okhttp.mockwebserver)`, `buildConfigField MWDAT_VERSION`, `versionName 2.0.0` / `versionCode 5` |
| `app/src/main/AndroidManifest.xml` | Modify | `android:networkSecurityConfig` |
| `app/src/main/res/xml/network_security_config.xml` | Create | cleartext `ws://` permitted (LAN gateway) |
| `app/src/main/res/values/strings.xml`, `values-zh-rCN/strings.xml` | Modify | OpenClaw / RTMP / misc strings (en + zh) |
| `app/src/main/java/com/smartview/glassai/glasses/FrameConversions.kt` | Create | single copy of I420 copy → NV21 → JPEG → Bitmap and `PhotoData` decode |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt` | Modify | `latestFrame` + `publishFrame()`, `@VisibleForTesting resetForTests()` |
| `app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt` | Modify | `DatRegistrationGateway` + `CameraPermissionCheck` seams |
| `app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt` | Modify | `WearablesRegistrationGateway` real implementation |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesFrameProvider.kt` | Create | `GlassesFrameProvider` interface, `FrameSnapshot`, `SnapshotResult`, `SessionFrameProvider` (manager `latestFrame` + capturer fallback + foreground gate) |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt` | Modify | aggregate capture budget (`PhotoCaptureOutcome.Timeout`) |
| `app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` | Modify | injectable constructor, `FrameConversions`, publishes `latestFrame`, `errorEvents` one-shot flow, ghost-frame guard, localized strings |
| `app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` | Modify | `FrameConversions`; first-frame timeout; server URL + stream key + persisted bitrate |
| `app/src/main/java/com/smartview/glassai/services/RTMPStreamingService.kt` | Modify | `onDisconnectRtmp` guarded by `isStreaming`; `initEncoder` releases the codec on failure; output loop stops (no busy-spin) on a codec error; `stopStreaming` serialized (single disconnect, off the main thread); dead `feedFrame(ByteArray)` removed |
| `app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` | Modify | uses `FrameConversions`; handles `PhotoCaptureOutcome.Timeout` |
| `app/src/main/java/com/smartview/glassai/services/HttpClients.kt` | Create | one shared `OkHttpClient` per purpose (`websocket`, `lanWebSocket`) |
| `app/src/main/java/com/smartview/glassai/services/OmniRealtimeService.kt`, `GeminiLiveService.kt` | Modify | shared client, weak-ref listener, full disconnect cleanup |
| `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawModels.kt` | Create | protocol constants, connection state (incl. `Reconnecting`), frames, chat message, snap params, client info |
| `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawSettingsStore.kt` | Create | settings/token/seed persistence seam + `SecureOpenClawSettingsStore` (lazily) over `APIKeyManager` |
| `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawDeviceIdentity.kt` | Create | Tink Ed25519 identity, `deviceId`, base64url, v3 signature string |
| `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawNodeService.kt` | Create | gateway WebSocket client: handshake, chat, invoke dispatch, tick, reconnect |
| `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouter.kt` | Create | `camera.snap/camera.list/device.status/device.info` |
| `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawIntegration.kt` | Create | wires router + provider into the singleton at app start |
| `app/src/main/java/com/smartview/glassai/services/PcmAudioSource.kt` | Create | 16 kHz PCM16 capture seam + `AudioRecordPcmSource` |
| `app/src/main/java/com/smartview/glassai/services/FunASRService.kt` | Create | `SpeechRecognizerSession` seam + DashScope Fun-ASR realtime client |
| `app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt` | Create | chat state, snap & send, ASR, audio-source toggle |
| `app/src/main/java/com/smartview/glassai/ui/screens/OpenClawChatScreen.kt` | Create | chat UI |
| `app/src/main/java/com/smartview/glassai/ui/screens/OpenClawSettingsScreen.kt` | Create | host/port/scheme/token, status, capabilities |
| `app/src/main/java/com/smartview/glassai/ui/components/WearablesErrorToast.kt` | Create | the single glasses-error toast above the NavHost |
| `app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` | Modify | `Screen.OpenClaw`, `Screen.OpenClawSettings`, toast hoist |
| `app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` | Modify | OpenClaw card replaces WordLearn placeholder; auto-connect; toast block removed |
| `app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` | Modify | Integrations section; About version + SDK rows |
| `app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt`, `QuickVisionScreen.kt`, `SimpleLiveStreamScreen.kt`, `RTMPStreamingScreen.kt` | Modify | toast blocks removed; mic re-check; stream-key field |
| `app/src/main/java/com/smartview/glassai/ui/theme/Color.kt` | Modify | `OpenClawColor`, `OpenClawColorEnd` |
| `app/src/main/java/com/smartview/glassai/ui/theme/Theme.kt` | Modify | drop the deprecated `statusBarColor`/`navigationBarColor` writes (ledger T1; `enableEdgeToEdge()` already owns the bars) |
| `app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt` | Modify | OpenClaw keys, RTMP stream key + bitrate, migration |
| `app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` | Modify | foreground tracking, OpenClaw wiring |
| `app/src/main/java/com/smartview/glassai/MainActivity.kt` | Modify | rename `initializeSDK` → `startWearablesMonitoring` |
| `app/src/test/java/com/smartview/glassai/glasses/*.kt` | Create/Modify | `FrameConversionsTest`, `TestBitmaps`, `FakeRegistrationGateway`, `GlassesSessionManagerTest` additions, `GlassesPhotoCapturerTest` additions, `SessionFrameProviderTest` |
| `app/src/test/java/com/smartview/glassai/viewmodels/WearablesViewModelTest.kt` | Create | ViewModel state mapping with fakes |
| `app/src/test/java/com/smartview/glassai/services/openclaw/*.kt` | Create | identity, service (MockWebServer), router tests |
| `app/src/test/java/com/smartview/glassai/services/FunASRServiceTest.kt` | Create | ASR protocol against MockWebServer |
| `app/src/test/java/com/smartview/glassai/utils/RtmpUrlSplitterTest.kt` | Create | stream-key migration rule |
| `app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` | Modify | `resetForTests()` in tearDown, frame-provider case, PAUSED/resume (captouch tap) and fold cases |
| `app/src/androidTest/java/com/smartview/glassai/glasses/SessionFrameProviderEncodeInstrumentedTest.kt` | Create | `encodeBitmap` aspect ratio / no upscale / quality clamp on device |
| `app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt` | Create | real handshake → chat → `node.invoke camera.snap` over cleartext `ws://` against an in-process MockWebServer on the emulator |
| `app/src/androidTest/java/com/smartview/glassai/services/AudioRecordPcmSourceInstrumentedTest.kt` | Create | 16 kHz PCM16 capture from the emulator mic |
| `app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt` | Create | `rtmp_url` → server + stream key migration; OpenClaw token/seed/scheme persistence over `EncryptedSharedPreferences` |
| `android/tools/openclaw-stub-gateway/stub.js`, `package.json` | Create | Node.js stub gateway (connect.challenge / connect / chat / node.invoke) for the manual connected-path checklist |
| `docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md` | Create | draft of the DAT 0.9.0 `VideoDecoder.activateDecoder()` vs `Camera.stop()` abort report for the product owner to file upstream |
| `README.md`, `README_EN.md` (repo root), `android/README.md`, `android/CHANGELOG.md` | Modify | 2.0.0 docs, OpenClaw setup, version requirements |

---

### Task 1: Phase A follow-ups Phase B builds on (FrameConversions, manager `latestFrame`, testable `WearablesViewModel`, `resetForTests`)

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/glasses/FrameConversions.kt`
- Modify: `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt` (anchors: `private var cameraOwner: String? = null`, `fun stopCamera(owner: String)`, `fun stopSession()`, `private fun teardownAfterDeviceStop()`)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt` (append after `interface DatDeviceObserver`)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt` (append at end)
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` (full replacement)
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` (anchors: `private fun copyFrame(`, `private fun updatePreview(`, `private fun convertI420toNV21(`)
- Modify: `app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` (anchors: `private fun decodePhoto(photo: PhotoData)`, `private fun convertVideoFrameToBitmap(`, `private fun convertI420toNV21(`)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml` (append before `</resources>`)
- Create: `app/src/test/java/com/smartview/glassai/glasses/FrameConversionsTest.kt`, `app/src/test/java/com/smartview/glassai/glasses/TestBitmaps.kt`, `app/src/test/java/com/smartview/glassai/glasses/FakeRegistrationGateway.kt`, `app/src/test/java/com/smartview/glassai/viewmodels/WearablesViewModelTest.kt`
- Modify: `app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt` (append tests), `app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` (append tests), `app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt` (anchor: `var nextCaptureResult: PhotoCaptureResult? = null` inside `class FakeGlassesSession`), `app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` (anchor: `fun tearDown()`)

**Interfaces:**
- Produces `object FrameConversions { fun copyI420(buffer: ByteBuffer): ByteArray; fun copyI420(frame: VideoFrame): ByteArray?; fun i420ToNv21(input: ByteArray, width: Int, height: Int): ByteArray; fun i420ToJpeg(i420: ByteArray, width: Int, height: Int, quality: Int): ByteArray; fun i420ToBitmap(i420: ByteArray, width: Int, height: Int, quality: Int): Bitmap?; fun frameToBitmap(frame: VideoFrame, quality: Int): Bitmap?; fun decodePhoto(photo: PhotoData): Bitmap? }`
- Produces on `GlassesSessionManager`: `val latestFrame: StateFlow<Bitmap?>`, `fun publishFrame(owner: String, frame: Bitmap)`, `@VisibleForTesting internal fun resetForTests()`
- Produces `interface DatRegistrationGateway`, `sealed class CameraPermissionCheck`, `class WearablesRegistrationGateway(context: Context)`
- Produces `WearablesViewModel internal constructor(application: Application, sessionManager: GlassesSessionManager, registration: DatRegistrationGateway, strings: (Int) -> String, videoQuality: () -> VideoQuality, frameDispatcher: CoroutineDispatcher)` + public `constructor(application: Application)`; new `val errorEvents: SharedFlow<String>`; `const val OWNER = "WearablesViewModel"`
- Consumes (unchanged): `GlassesSessionManager.acquire/release/ensureSessionStarted/addCamera/stopCamera/stopSession`, `GlassesCamera`, `GlassesErrorMessages.resId(...)`

- [ ] **Step 1.1: Write the failing `FrameConversionsTest`**

Create `app/src/test/java/com/smartview/glassai/glasses/FrameConversionsTest.kt`:

```kotlin
package com.smartview.glassai.glasses

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameConversionsTest {

    @Test
    fun i420ToNv21InterleavesVThenU() {
        // 2x2 frame: Y = 1,2,3,4 ; U = 5 ; V = 6  ->  NV21 = Y..., V, U
        val i420 = byteArrayOf(1, 2, 3, 4, 5, 6)
        val nv21 = FrameConversions.i420ToNv21(i420, width = 2, height = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 6, 5), nv21)
    }

    @Test
    fun i420ToNv21HandlesAFourByFourFrame() {
        val size = 16
        val quarter = 4
        val i420 = ByteArray(size + 2 * quarter) { it.toByte() }
        val nv21 = FrameConversions.i420ToNv21(i420, width = 4, height = 4)
        // Y plane untouched
        assertArrayEquals(i420.copyOfRange(0, size), nv21.copyOfRange(0, size))
        // chroma: V[n] then U[n]
        for (n in 0 until quarter) {
            assertEquals(i420[size + quarter + n], nv21[size + n * 2])
            assertEquals(i420[size + n], nv21[size + n * 2 + 1])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun i420ToNv21RejectsAShortBuffer() {
        FrameConversions.i420ToNv21(byteArrayOf(1, 2, 3), width = 2, height = 2)
    }

    @Test
    fun copyI420CopiesRemainingBytesAndRestoresPosition() {
        val buffer = ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3))
        buffer.position(1)
        val copy = FrameConversions.copyI420(buffer)
        assertArrayEquals(byteArrayOf(1, 2, 3), copy)
        assertEquals(1, buffer.position())
    }
}
```

- [ ] **Step 1.2: Run it and watch it fail**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.FrameConversionsTest" 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'FrameConversions'`.

- [ ] **Step 1.3: Create `FrameConversions.kt`**

```kotlin
package com.smartview.glassai.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The one place that turns DAT 0.9.0 frames into app images (spec §5.8, Phase A review Minor #15).
 * Frames are I420 (Y plane, then U plane, then V plane, each chroma plane width/2 x height/2).
 *
 * Only [i420ToNv21] and [copyI420] run on the JVM in unit tests; everything else needs
 * android.graphics and is exercised on the emulator.
 */
object FrameConversions {
    private const val TAG = "FrameConversions"

    /** JPEG quality for live previews (WearablesViewModel / RTMP preview). */
    const val PREVIEW_JPEG_QUALITY = 50

    /** JPEG quality for frames that are analyzed or sent to a gateway. */
    const val CAPTURE_JPEG_QUALITY = 85

    /** Defensive copy of [buffer]'s remaining bytes; the buffer's position is restored. */
    fun copyI420(buffer: ByteBuffer): ByteArray {
        val originalPosition = buffer.position()
        val copy = ByteArray(buffer.remaining())
        buffer.get(copy)
        buffer.position(originalPosition)
        return copy
    }

    /**
     * Copies the SDK frame. VideoFrame.buffer is only guaranteed valid inside the collector, so
     * this must be the first thing a collector does with a frame.
     */
    fun copyI420(frame: VideoFrame): ByteArray? = try {
        copyI420(frame.buffer)
    } catch (e: Exception) {
        Log.e(TAG, "Error copying video frame: ${e.message}")
        null
    }

    /** I420 (YYYY…UU…VV…) -> NV21 (YYYY…VUVU…). */
    fun i420ToNv21(input: ByteArray, width: Int, height: Int): ByteArray {
        val size = width * height
        val quarter = size / 4
        require(input.size >= size + 2 * quarter) {
            "I420 buffer too small: ${input.size} bytes for ${width}x${height}"
        }
        val output = ByteArray(input.size)
        input.copyInto(output, 0, 0, size) // Y is the same
        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
    }

    fun i420ToJpeg(i420: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val nv21 = i420ToNv21(i420, width, height)
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return ByteArrayOutputStream().use { stream ->
            image.compressToJpeg(Rect(0, 0, width, height), quality, stream)
            stream.toByteArray()
        }
    }

    fun i420ToBitmap(i420: ByteArray, width: Int, height: Int, quality: Int): Bitmap? = try {
        val jpeg = i420ToJpeg(i420, width, height, quality)
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    } catch (e: Exception) {
        Log.e(TAG, "Error converting I420 frame: ${e.message}")
        null
    }

    /** Copy + convert in one call; never call this on the main thread. */
    fun frameToBitmap(frame: VideoFrame, quality: Int): Bitmap? {
        val i420 = copyI420(frame) ?: return null
        return i420ToBitmap(i420, frame.width, frame.height, quality)
    }

    /** Decodes a captured photo (HEIC bytes or an SDK Bitmap). Never call this on the main thread. */
    fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buffer = photo.data.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
```

- [ ] **Step 1.4: Run the test again**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.FrameConversionsTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 1.5: Use `FrameConversions` in `RTMPStreamingViewModel` and `QuickVisionService`**

In `RTMPStreamingViewModel.kt` delete the three private functions `copyFrame`, `updatePreview`, `convertI420toNV21` (the block starting at `/** Defensive copy of the SDK frame; null if the buffer could not be read. */` through the closing brace of `convertI420toNV21`) and replace with:

```kotlin
    /** Defensive copy of the SDK frame; null if the buffer could not be read. */
    private fun copyFrame(videoFrame: VideoFrame): ByteArray? = FrameConversions.copyI420(videoFrame)

    private fun updatePreview(i420: ByteArray, width: Int, height: Int) {
        val bitmap = FrameConversions.i420ToBitmap(i420, width, height, FrameConversions.PREVIEW_JPEG_QUALITY)
        if (bitmap != null) _previewFrame.value = bitmap
    }
```

Then remove the now-unused imports `android.graphics.BitmapFactory`, `android.graphics.ImageFormat`, `android.graphics.Rect`, `android.graphics.YuvImage`, `java.io.ByteArrayOutputStream` and add `import com.smartview.glassai.glasses.FrameConversions`.

In `QuickVisionService.kt` delete the private functions `decodePhoto`, `convertVideoFrameToBitmap`, `convertI420toNV21` and replace with:

```kotlin
    private fun decodePhoto(photo: PhotoData): Bitmap? = FrameConversions.decodePhoto(photo)

    private fun convertVideoFrameToBitmap(videoFrame: VideoFrame): Bitmap? =
        FrameConversions.frameToBitmap(videoFrame, FrameConversions.CAPTURE_JPEG_QUALITY)
```

Remove the unused imports `android.graphics.BitmapFactory`, `android.graphics.ImageFormat`, `android.graphics.Rect`, `android.graphics.YuvImage`, `java.io.ByteArrayOutputStream` and add `import com.smartview.glassai.glasses.FrameConversions`.

- [ ] **Step 1.6: Write the failing manager tests (`latestFrame`, `publishFrame`, `resetForTests`)**

Create `app/src/test/java/com/smartview/glassai/glasses/TestBitmaps.kt`:

```kotlin
package com.smartview.glassai.glasses

import android.graphics.Bitmap

/**
 * android.graphics.Bitmap has no constructor in the android.jar stubs and every factory returns
 * null under isReturnDefaultValues, so JVM tests that only need a *reference* allocate one
 * without running a constructor. Never call any method on the result.
 */
object TestBitmaps {
    fun stub(): Bitmap {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }
}
```

Append to `GlassesSessionManagerTest.kt` (inside the class, before the final `}`):

```kotlin
    // ---- Phase B: latestFrame / publishFrame / resetForTests ----

    @Test
    fun cameraOwnerPublishesTheLatestFrame() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        val frame = TestBitmaps.stub()

        manager.publishFrame("A", frame)

        assertSame(frame, manager.latestFrame.value)
    }

    @Test
    fun nonOwnerFramesAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)

        manager.publishFrame("B", TestBitmaps.stub())

        assertNull(manager.latestFrame.value)
    }

    @Test
    fun stopCameraClearsTheLatestFrame() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        manager.publishFrame("A", TestBitmaps.stub())

        manager.stopCamera("A")

        assertNull(manager.latestFrame.value)
    }

    @Test
    fun deviceStopClearsTheLatestFrame() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        manager.publishFrame("A", TestBitmaps.stub())

        factory.last.emitStoppedByDevice()

        assertNull(manager.latestFrame.value)
    }

    @Test
    fun resetForTestsDropsOwnersSessionAndFrame() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        manager.acquire("B")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        manager.publishFrame("A", TestBitmaps.stub())

        manager.resetForTests()

        assertEquals(0, manager.ownerCount)
        assertFalse(manager.hasSession)
        assertNull(manager.currentCameraOwner)
        assertNull(manager.latestFrame.value)
        assertEquals(1, factory.last.stopCalls)
        assertEquals(DeviceSessionState.STOPPED, manager.sessionState.value)
        assertFalse(manager.isStoppingPreviousSession)
    }
```

- [ ] **Step 1.7: Run and watch them fail**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.GlassesSessionManagerTest" 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'publishFrame'` / `'latestFrame'` / `'resetForTests'`.

- [ ] **Step 1.8: Add `latestFrame`, `publishFrame`, `resetForTests` to `GlassesSessionManager`**

Add imports at the top of `GlassesSessionManager.kt`:

```kotlin
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
```

Replace the line `private var cameraOwner: String? = null` with:

```kotlin
    // @Volatile: read by publishFrame() on the frame worker, written on the main thread.
    @Volatile
    private var cameraOwner: String? = null

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    /**
     * The last decoded frame of whichever owner currently borrows the camera (Phase B: the
     * OpenClaw camera.snap source). Cleared whenever the camera is stopped or the session ends.
     */
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

    private val _lastSessionError = MutableStateFlow<DeviceSessionError?>(null)
    /**
     * The most recent error also emitted on [sessionError], readable synchronously. Callers that
     * get SessionStartResult.CREATE_FAILED read it to show the specific DAT reason regardless of
     * whether the SharedFlow collector already ran (Phase A review Minor #5).
     */
    val lastSessionError: StateFlow<DeviceSessionError?> = _lastSessionError.asStateFlow()
```

Then, in the three places that emit on `_sessionError`, record the error first:
- in `ensureSession()` replace `_sessionError.tryEmit(error)` with `_lastSessionError.value = error` followed by `_sessionError.tryEmit(error)` (two lines);
- in `ensureSessionStarted()` replace `_sessionError.tryEmit(error)` with the same two lines;
- in `onSessionError()` replace `_sessionError.tryEmit(error)` with the same two lines.

Insert after the `fun stopCamera(owner: String)` function (after its closing brace):

```kotlin
    /**
     * Called by the current camera owner from its frame worker after decoding a frame. This is the
     * only manager method that may run off the main thread: it touches nothing but a StateFlow and
     * the volatile owner name. Frames from anyone but the current owner are ignored.
     */
    fun publishFrame(owner: String, frame: Bitmap) {
        if (cameraOwner != owner) return
        _latestFrame.value = frame
    }

    /**
     * Test hook (Task 9 parked item 2): forgets every owner, stops the session, drops the parked
     * outgoing session and the latest frame so the next test starts from a clean singleton.
     * Main thread only.
     */
    @VisibleForTesting
    internal fun resetForTests() {
        owners.clear()
        stopSession()
        stoppingJob?.cancel()
        stoppingJob = null
        stoppingSession = null
        _latestFrame.value = null
        _isDatAppUpdateRequired.value = false
        _sessionState.value = DeviceSessionState.STOPPED
    }
```

In `stopCamera()` add `_latestFrame.value = null` right after `cameraOwner = null`. In `stopSession()` add `_latestFrame.value = null` right after `cameraOwner = null`. In `teardownAfterDeviceStop()` add `_latestFrame.value = null` right after `cameraOwner = null`.

- [ ] **Step 1.9: Run the manager tests**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.GlassesSessionManagerTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, at least 30 tests pass in this class (25 existing + 5 new).

- [ ] **Step 1.10: Add the registration gateway seam**

Append to `app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt` (after `interface DatDeviceObserver { … }`, before `interface DisplayAttacher`):

```kotlin
/** Result of the wearable CAMERA permission check (Wearables.checkPermissionStatus). */
sealed class CameraPermissionCheck {
    object Granted : CameraPermissionCheck()
    object Denied : CameraPermissionCheck()
    /** The SDK could not answer; [description] is the PermissionError description. */
    data class Failed(val description: String) : CameraPermissionCheck()
}

/**
 * The Wearables statics WearablesViewModel needs for registration, so the ViewModel can be built
 * with a fake on the JVM (Phase A review Important #4). Real implementation:
 * WearablesRegistrationGateway.
 */
interface DatRegistrationGateway {
    val registrationState: Flow<RegistrationState>
    val registrationErrors: Flow<RegistrationError>
    val devices: Flow<Set<DeviceIdentifier>>
    fun startRegistration(activity: Activity)
    fun startUnregistration(activity: Activity)
    /** @return null on success, else the SDK's localized description of the NavigationError. */
    fun openFirmwareUpdate(activity: Activity): String?
    /** @return null on success, else the SDK's localized description of the NavigationError. */
    fun openDATGlassesAppUpdate(activity: Activity): String?
    suspend fun checkCameraPermission(): CameraPermissionCheck
}
```

Add these imports to `DatGateway.kt`:

```kotlin
import android.app.Activity
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
```

Append to `app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt`:

```kotlin
/** Real [DatRegistrationGateway] over the Wearables statics. */
class WearablesRegistrationGateway(private val context: Context) : DatRegistrationGateway {
    override val registrationState: Flow<RegistrationState>
        get() = Wearables.registrationState
    override val registrationErrors: Flow<RegistrationError>
        get() = Wearables.registrationErrorStream
    override val devices: Flow<Set<DeviceIdentifier>>
        get() = Wearables.devices

    override fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)

    override fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)

    override fun openFirmwareUpdate(activity: Activity): String? =
        Wearables.openFirmwareUpdate(activity).fold(
            onSuccess = { null },
            onFailure = { error, _ -> error.getLocalizedDescription(context) },
        )

    override fun openDATGlassesAppUpdate(activity: Activity): String? =
        Wearables.openDATGlassesAppUpdate(activity).fold(
            onSuccess = { null },
            onFailure = { error, _ -> error.getLocalizedDescription(context) },
        )

    override suspend fun checkCameraPermission(): CameraPermissionCheck =
        Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            onSuccess = { status ->
                if (status == PermissionStatus.Granted) CameraPermissionCheck.Granted
                else CameraPermissionCheck.Denied
            },
            onFailure = { error, _ -> CameraPermissionCheck.Failed(error.description) },
        )
}
```

Add these imports to `WearablesDatAdapter.kt`:

```kotlin
import android.app.Activity
import android.content.Context
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
```

Create `app/src/test/java/com/smartview/glassai/glasses/FakeRegistrationGateway.kt`:

```kotlin
package com.smartview.glassai.glasses

import android.app.Activity
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRegistrationGateway : DatRegistrationGateway {
    val state = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val errors = MutableSharedFlow<RegistrationError>(extraBufferCapacity = 4)
    val deviceSet = MutableStateFlow<Set<DeviceIdentifier>>(emptySet())
    var registrationCalls = 0
    var unregistrationCalls = 0
    var firmwareUpdateResult: String? = null
    var datAppUpdateResult: String? = null
    var permission: CameraPermissionCheck = CameraPermissionCheck.Granted
    val calls = mutableListOf<String>()

    override val registrationState: Flow<RegistrationState> = state
    override val registrationErrors: Flow<RegistrationError> = errors
    override val devices: Flow<Set<DeviceIdentifier>> = deviceSet

    override fun startRegistration(activity: Activity) {
        registrationCalls++
        calls += "startRegistration"
    }

    override fun startUnregistration(activity: Activity) {
        unregistrationCalls++
        calls += "startUnregistration"
    }

    override fun openFirmwareUpdate(activity: Activity): String? = firmwareUpdateResult

    override fun openDATGlassesAppUpdate(activity: Activity): String? = datAppUpdateResult

    override suspend fun checkCameraPermission(): CameraPermissionCheck = permission
}
```

- [ ] **Step 1.11: Add the string for the previously hard-coded English**

Append to `app/src/main/res/values/strings.xml` before `</resources>`:

```xml

    <!-- Phase B Task 1: WearablesViewModel messages that were hard-coded English -->
    <string name="glasses_permission_check_failed">Could not check the glasses camera permission: %1$s</string>
```

Append to `app/src/main/res/values-zh-rCN/strings.xml` before `</resources>`:

```xml

    <!-- Phase B Task 1: WearablesViewModel messages that were hard-coded English -->
    <string name="glasses_permission_check_failed">无法检查眼镜相机权限：%1$s</string>
```

(`camera_permission_denied` already exists in both files and is reused for the Denied branch.)

- [ ] **Step 1.12: Write the failing `WearablesViewModelTest`**

First extend the Phase A fake so a test can script `stream.start()` failures (ledger T3: unused fake surface). In `app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt`, inside `class FakeGlassesSession`, after `var nextCaptureResult: PhotoCaptureResult? = null` add:

```kotlin
    /** Applied to every camera this session hands out: startStream() returns this error. */
    var nextStartError: StreamError? = null
```

and in its `addCamera()` after `nextCaptureResult?.let { camera.captureResult = it }` add:

```kotlin
        nextStartError?.let { camera.startError = it }
```

Then use the two previously unused fake surfaces (`FakeGlassesCamera.startError` and `FakeGlassesCamera.errors`, ledger T3 / Important #4) in the capturer test. Append inside `class GlassesPhotoCapturerTest` (before the final `}`), and add `import com.meta.wearable.dat.camera.types.StreamError` to the file's imports:

```kotlin
    @Test
    fun streamStartFailureIsReportedAndEverythingReleased() = runTest(dispatcher) {
        observer.device.value = rayban
        val manager = newManager()

        val outcome = async { capturer(manager).capture() }
        val session = factory.last
        session.nextStartError = StreamError.STREAM_ERROR // every camera's startStream() fails
        session.emitStarted()

        assertEquals(PhotoCaptureOutcome.StreamStartFailed(StreamError.STREAM_ERROR), outcome.await())
        assertEquals(1, session.cameras.single().startCalls)
        assertEquals(1, session.cameras.single().stopCalls)
        assertNull(manager.currentCameraOwner)
        assertEquals(0, manager.ownerCount)
        assertEquals(1, session.stopCalls)
    }

    @Test
    fun streamErrorInsteadOfStreamingEndsInStreamTimeout() = runTest(dispatcher) {
        observer.device.value = rayban
        val manager = newManager()

        val outcome = async { capturer(manager).capture() }
        factory.last.emitStarted()
        val camera = factory.last.cameras.single()
        // The SDK reports a stream error and never reaches STREAMING: the capturer's stream budget
        // is the only thing that ends the wait (it does not subscribe to streamErrors by design).
        assertTrue(camera.errors.tryEmit(StreamError.STREAM_ERROR))
        advanceTimeBy(GlassesPhotoCapturer.DEFAULT_STREAM_TIMEOUT_MS + 1)

        assertEquals(PhotoCaptureOutcome.StreamTimeout, outcome.await())
        assertEquals(1, camera.stopCalls)
        assertNull(manager.currentCameraOwner)
        assertEquals(0, manager.ownerCount)
    }
```

Then create `app/src/test/java/com/smartview/glassai/viewmodels/WearablesViewModelTest.kt`:

```kotlin
package com.smartview.glassai.viewmodels

import android.app.Activity
import android.app.Application
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import com.smartview.glassai.R
import com.smartview.glassai.glasses.FakeDatDeviceObserver
import com.smartview.glassai.glasses.FakeDatSessionFactory
import com.smartview.glassai.glasses.FakeRegistrationGateway
import com.smartview.glassai.glasses.GlassesDeviceInfo
import com.smartview.glassai.glasses.GlassesSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WearablesViewModel against the Phase A fakes (final review Important #4). Strings are resolved
 * by a lambda ("str:<resId>") because getString() returns null on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearablesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val factory = FakeDatSessionFactory()
    private val observer = FakeDatDeviceObserver()
    private val registration = FakeRegistrationGateway()
    private lateinit var manager: GlassesSessionManager

    private val rayban = GlassesDeviceInfo(
        id = "dev-1",
        name = "Ray-Ban Meta",
        deviceType = DeviceType.RAYBAN_META,
        isDisplayCapable = false,
        compatibility = DeviceCompatibility.COMPATIBLE,
    )

    private fun str(id: Int) = "str:$id"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        manager = GlassesSessionManager(
            sessionFactory = factory,
            deviceObserver = observer,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        ).also { it.startMonitoring() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): WearablesViewModel = WearablesViewModel(
        application = Application(),
        sessionManager = manager,
        registration = registration,
        strings = ::str,
        videoQuality = { VideoQuality.MEDIUM },
        frameDispatcher = dispatcher,
    ).also { it.startMonitoring() }

    @Test
    fun streamingMapsToStreamingAndUpgradesConnection() {
        observer.device.value = rayban
        val vm = newViewModel()
        assertEquals(WearablesViewModel.ConnectionState.Registered("Ray-Ban Meta"), vm.connectionState.value)

        vm.startStream()
        factory.last.emitStarted()
        val camera = factory.last.cameras.single()
        assertEquals(1, camera.startCalls)
        camera.stateFlow.value = DatStreamState.STREAMING

        assertEquals(WearablesViewModel.StreamState.Streaming, vm.streamState.value)
        assertEquals(WearablesViewModel.ConnectionState.Connected("Ray-Ban Meta"), vm.connectionState.value)
        assertEquals(WearablesViewModel.OWNER, manager.currentCameraOwner)
    }

    @Test
    fun pausedMapsToPausedWithoutRestarting() {
        observer.device.value = rayban
        val vm = newViewModel()
        vm.startStream()
        factory.last.emitStarted()
        val camera = factory.last.cameras.single()
        camera.stateFlow.value = DatStreamState.STREAMING
        camera.stateFlow.value = DatStreamState.PAUSED

        assertEquals(WearablesViewModel.StreamState.Paused, vm.streamState.value)
        assertEquals(1, camera.startCalls)
        assertEquals(1, factory.createCalls)
    }

    @Test
    fun stoppedAfterActiveStreamReleasesCameraAndSession() {
        observer.device.value = rayban
        val vm = newViewModel()
        vm.startStream()
        factory.last.emitStarted()
        val camera = factory.last.cameras.single()
        camera.stateFlow.value = DatStreamState.STREAMING
        camera.stateFlow.value = DatStreamState.STOPPED

        assertEquals(WearablesViewModel.StreamState.Stopped, vm.streamState.value)
        assertNull(manager.currentCameraOwner)
        assertEquals(0, manager.ownerCount)
        assertEquals(WearablesViewModel.ConnectionState.Registered("Ray-Ban Meta"), vm.connectionState.value)
    }

    @Test
    fun streamStartFailureShowsTheStreamErrorAndReleases() {
        observer.device.value = rayban
        val vm = newViewModel()
        vm.startStream()
        // Every camera this session hands out from now on fails stream.start()
        val session = factory.last
        session.nextStartError = StreamError.STREAM_ERROR
        session.emitStarted()

        val state = vm.streamState.value
        assertTrue("expected Error but was $state", state is WearablesViewModel.StreamState.Error)
        assertEquals(str(R.string.dat_stream_error), (state as WearablesViewModel.StreamState.Error).message)
        assertEquals(str(R.string.dat_stream_error), vm.errorMessage.value)
        assertEquals(1, session.cameras.single().startCalls)
        assertEquals(0, manager.ownerCount)
        assertNull(manager.currentCameraOwner)
    }

    @Test
    fun createFailedKeepsTheSpecificSessionError() {
        observer.device.value = rayban
        factory.failure = DeviceSessionError.NO_ELIGIBLE_DEVICE
        val vm = newViewModel()

        vm.startStream()

        val state = vm.streamState.value
        assertTrue(state is WearablesViewModel.StreamState.Error)
        assertEquals(str(R.string.dat_session_no_eligible_device), (state as WearablesViewModel.StreamState.Error).message)
        assertEquals(str(R.string.dat_session_no_eligible_device), vm.errorMessage.value)
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun disconnectStopsStreamThenSessionThenUnregisters() {
        observer.device.value = rayban
        val vm = newViewModel()
        vm.startStream()
        factory.last.emitStarted()
        factory.last.cameras.single().stateFlow.value = DatStreamState.STREAMING

        vm.disconnect(Activity())

        assertEquals(WearablesViewModel.StreamState.Stopped, vm.streamState.value)
        assertEquals(1, factory.last.stopCalls)
        assertEquals(1, registration.unregistrationCalls)
        assertEquals(WearablesViewModel.ConnectionState.Disconnected, vm.connectionState.value)
    }

    @Test
    fun registrationErrorWhileSearchingFallsBackToDisconnected() {
        val vm = newViewModel()
        vm.startDeviceSearch(Activity())
        assertEquals(WearablesViewModel.ConnectionState.Searching, vm.connectionState.value)
        assertEquals(1, registration.registrationCalls)

        registration.errors.tryEmit(RegistrationError.META_AI_NOT_INSTALLED)

        assertEquals(WearablesViewModel.ConnectionState.Disconnected, vm.connectionState.value)
        assertEquals(str(R.string.dat_registration_meta_ai_not_installed), vm.errorMessage.value)
    }

    @Test
    fun registeringStateMapsToConnecting() {
        val vm = newViewModel()
        registration.state.value = RegistrationState.REGISTERING
        assertEquals(WearablesViewModel.ConnectionState.Connecting, vm.connectionState.value)
        registration.state.value = RegistrationState.AVAILABLE
        assertEquals(WearablesViewModel.ConnectionState.Disconnected, vm.connectionState.value)
    }

    @Test
    fun streamErrorsWhileStreamingSurfaceAsErrorMessages() {
        observer.device.value = rayban
        val vm = newViewModel()
        vm.startStream()
        factory.last.emitStarted()
        val camera = factory.last.cameras.single()
        camera.stateFlow.value = DatStreamState.STREAMING
        assertNull(vm.errorMessage.value)

        // FakeGlassesCamera.errors backs GlassesCamera.streamErrors (ledger T3: previously unused)
        assertTrue(camera.errors.tryEmit(StreamError.STREAM_ERROR))

        assertEquals(str(R.string.dat_stream_error), vm.errorMessage.value)
        // A stream error alone does not tear the stream down; the SDK's STOPPED does that
        assertEquals(WearablesViewModel.StreamState.Streaming, vm.streamState.value)
        assertEquals(WearablesViewModel.OWNER, manager.currentCameraOwner)
    }

    @Test
    fun errorEventsAreEmittedOnceAndStateIsClearable() {
        val vm = newViewModel()
        val received = mutableListOf<String>()
        val job = CoroutineScope(dispatcher).launch {
            vm.errorEvents.collect { received += it }
        }
        vm.setError("boom")
        assertEquals(listOf("boom"), received)
        assertEquals("boom", vm.errorMessage.value)
        vm.clearError()
        assertNull(vm.errorMessage.value)
        assertEquals(listOf("boom"), received)
        job.cancel()
    }
}
```

- [ ] **Step 1.13: Run and watch it fail**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.viewmodels.WearablesViewModelTest" 2>&1 | tail -20
```
Expected: `BUILD FAILED` — no constructor of `WearablesViewModel` takes these parameters.

- [ ] **Step 1.14: Replace `WearablesViewModel.kt` with the injectable version**

Full new content of `app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt`:

```kotlin
package com.smartview.glassai.viewmodels

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraPermissionCheck
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.DatRegistrationGateway
import com.smartview.glassai.glasses.FrameConversions
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesDeviceInfo
import com.smartview.glassai.glasses.GlassesErrorMessages
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.PhotoCaptureResult
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.glasses.WearablesRegistrationGateway
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WearablesViewModel - UI-facing DAT SDK façade (DAT 0.9.0).
 *
 * - Registration / unregistration (needs a real Activity) through [DatRegistrationGateway].
 * - Device discovery + active-device metadata via GlassesSessionManager.
 * - Camera streaming borrowed from the shared GlassesSessionManager (one DeviceSession per device);
 *   every decoded frame is also published to GlassesSessionManager.latestFrame (OpenClaw snap source).
 *
 * The public contract (StreamState sealed class, currentFrame, startStream/stopStream/takePhoto,
 * capturedPhoto, hasActiveDevice, connectionState, isRegistered, errorMessage) is unchanged for the
 * screens. [errorEvents] is the one-shot channel for the toast; [errorMessage] stays as state.
 *
 * The internal constructor exists so JVM tests can inject fakes (Phase A review Important #4);
 * the Application-only constructor is the one `by viewModels()` uses.
 */
class WearablesViewModel internal constructor(
    application: Application,
    private val sessionManager: GlassesSessionManager,
    private val registration: DatRegistrationGateway,
    private val strings: (Int) -> String,
    private val videoQuality: () -> VideoQuality,
    private val frameDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        sessionManager = GlassesSessionManager.getInstance(application),
        registration = WearablesRegistrationGateway(application),
        strings = { id -> application.getString(id) },
        videoQuality = { videoQualityFromSetting(APIKeyManager.getInstance(application).getVideoQuality()) },
        // Single-threaded worker for frame decoding (never the main thread), like the 0.9.0 sample
        frameDispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    companion object {
        private const val TAG = "WearablesViewModel"
        const val OWNER = "WearablesViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        private const val FRAME_RATE = 24

        fun videoQualityFromSetting(setting: String): VideoQuality = when (setting) {
            "LOW" -> VideoQuality.LOW
            "HIGH" -> VideoQuality.HIGH
            else -> VideoQuality.MEDIUM
        }
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

    /** Latest error as state (inline consumers). Cleared by [clearError] and at every startStream(). */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** One-shot error events for the single toast above the NavHost (Phase A review Minor #13). */
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

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

    // Drop frames while the previous one is still being converted (spec §5.8)
    private val isProcessingFrame = AtomicBoolean(false)

    // Coroutine jobs for stream management
    private var startJob: Job? = null
    private var videoJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var deviceSelectorJob: Job? = null
    private var monitoringStarted = false

    private fun str(@StringRes id: Int): String = strings(id)

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        Log.d(TAG, "Starting monitoring")

        // 1. Registration errors FIRST: registrationErrorStream is hot with no replay, so it must be
        //    collected before the user can tap Connect.
        viewModelScope.launch {
            registration.registrationErrors.collect { error ->
                Log.e(TAG, "Registration error: ${error.description}")
                setError(str(GlassesErrorMessages.resId(error)))
                if (_connectionState.value is ConnectionState.Searching ||
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        // 2. Registration state (plain enum in 0.9.0)
        viewModelScope.launch {
            registration.registrationState.collect { state ->
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
            registration.devices.collect { deviceSet ->
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

        // 5. Session errors (createSession failures + DeviceSession.errors). Deduplicated against
        //    failStart(), which may already have shown the same reason via lastSessionError.
        viewModelScope.launch {
            sessionManager.sessionError.collect { error ->
                val message = str(GlassesErrorMessages.resId(error))
                if (_errorMessage.value != message) setError(message)
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
        registration.startRegistration(activity)
    }

    fun startUnregistration(activity: Activity) {
        Log.d(TAG, "Starting unregistration")
        registration.startUnregistration(activity)
    }

    /**
     * Stops the stream, stops the shared session unconditionally (we are about to unregister from
     * Meta AI, so no session could survive anyway) and unregisters.
     */
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
        // NavigationError is not part of GlassesErrorMessages (spec §5.9 covers Stream/Session errors);
        // the SDK's own localized text is used so the message still follows the device language.
        registration.openFirmwareUpdate(activity)?.let { setError(it) }
    }

    /** Opens the Meta AI app's DAT-glasses-app update flow (DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED). */
    fun openDATGlassesAppUpdate(activity: Activity) {
        registration.openDATGlassesAppUpdate(activity)?.let { setError(it) }
    }

    // Navigate to streaming (check permission first)
    fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
        viewModelScope.launch {
            when (val check = registration.checkCameraPermission()) {
                is CameraPermissionCheck.Failed -> {
                    setError(str(R.string.glasses_permission_check_failed).format(check.description))
                    return@launch
                }
                CameraPermissionCheck.Granted -> {
                    _isStreaming.value = true
                    return@launch
                }
                CameraPermissionCheck.Denied -> Unit
            }

            // Request permission
            when (onRequestWearablesPermission(Permission.CAMERA)) {
                PermissionStatus.Denied -> setError(str(R.string.camera_permission_denied))
                PermissionStatus.Granted -> _isStreaming.value = true
            }
        }
    }

    fun navigateToDeviceSelection() {
        _isStreaming.value = false
    }

    // Streaming
    suspend fun checkCameraPermission(): Boolean =
        registration.checkCameraPermission() == CameraPermissionCheck.Granted

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

        // Reset state. clearError() runs before acquire() so a stale message from an earlier
        // failed attempt cannot survive a later successful start; each failure path below sets
        // its own message afterwards.
        clearError()
        _currentFrame.value = null
        _streamState.value = StreamState.Waiting

        val quality = videoQuality()
        Log.d(TAG, "Using video quality: $quality")

        startJob = viewModelScope.launch {
            sessionManager.acquire(OWNER)
            // Leave-and-re-enter: the previous session may still be STOPPING in the SDK, so the
            // create happens inside ensureSessionStarted() once STOPPED has been observed.
            when (sessionManager.ensureSessionStarted(SESSION_START_TIMEOUT_MS)) {
                SessionStartResult.STARTED -> Unit
                SessionStartResult.CREATE_FAILED -> {
                    Log.e(TAG, "createSession failed")
                    // Show the specific DAT reason (e.g. NO_ELIGIBLE_DEVICE) read synchronously
                    // from the manager; the SharedFlow collector may run before or after this
                    // point depending on how the coroutine was resumed (Phase A review Minor #5).
                    val specific = sessionManager.lastSessionError.value?.let { str(GlassesErrorMessages.resId(it)) }
                    failStart(specific ?: str(R.string.glasses_session_failed))
                    return@launch
                }
                SessionStartResult.NOT_STARTED -> {
                    Log.e(TAG, "session did not reach STARTED")
                    failStart(_errorMessage.value ?: str(R.string.glasses_session_timeout))
                    return@launch
                }
            }
            val config = StreamConfiguration(videoQuality = quality, frameRate = FRAME_RATE)
            when (val result = sessionManager.addCamera(OWNER, config)) {
                is CameraResult.Ready -> attachCamera(result.camera)
                is CameraResult.Failed -> {
                    Log.e(TAG, "addCamera failed: ${result.error}")
                    failStart(cameraErrorMessage(result.error))
                }
            }
        }

        Log.d(TAG, "startStream END")
    }

    /** Shared failure path for startStream(): message as state + event, Error state, claim released. */
    private fun failStart(message: String) {
        if (_errorMessage.value != message) setError(message)
        _streamState.value = StreamState.Error(message)
        sessionManager.release(OWNER)
    }

    private fun attachCamera(borrowed: GlassesCamera) {
        camera = borrowed

        // Subscribe before start(): streamState is a StateFlow that replays STOPPED. The frame
        // collector is launched on the worker, so it attaches a few ms after start(); that is fine
        // for a preview. No conflate(): VideoFrame.buffer is only guaranteed valid inside collect {},
        // so FrameConversions copies the bytes as its first step. The AtomicBoolean is the drop
        // policy from spec §5.8.
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
                setError(str(GlassesErrorMessages.resId(error)))
            }
        }

        val startError = borrowed.startStream()
        if (startError != null) {
            Log.e(TAG, "stream.start failed: ${startError.description}")
            val message = str(GlassesErrorMessages.resId(startError))
            setError(message)
            stopStream()
            _streamState.value = StreamState.Error(message)
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

    private fun cameraErrorMessage(error: CameraError): String =
        str(GlassesErrorMessages.resId(error))

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
                    val bitmap = withContext(frameDispatcher) { FrameConversions.decodePhoto(result.photo) }
                    if (bitmap == null) {
                        Log.e(TAG, "Photo decode failed")
                        setError(str(R.string.photo_capture_failed))
                    } else {
                        Log.d(TAG, "Photo captured: ${bitmap.width}x${bitmap.height}")
                        _capturedPhoto.value = bitmap
                    }
                }
                is PhotoCaptureResult.Failure -> {
                    Log.e(TAG, "Photo capture failed: ${result.error.description}")
                    setError(str(GlassesErrorMessages.resId(result.error)))
                }
            }
        }
        return _capturedPhoto.value
    }

    /**
     * Handle incoming (uncompressed YUV, treated as I420) video frames on the frame worker.
     * Runs inside the videoJob coroutine: the isActive check closes the ghost-frame window
     * (a frame mid-conversion when stopStream() cancels the job must not repopulate the flows).
     *
     * Known, accepted trade-off: the published bitmap is the preview decode (JPEG quality 50), and
     * an OpenClaw camera.snap taken while Live AI streams re-encodes it at its own quality, so such
     * a snap is double-lossy compared with iOS's raw preview frame. Encoding a second, higher-quality
     * copy of every frame only for the rare snap would double the per-frame work; snaps taken while
     * nobody streams go through GlassesPhotoCapturer at CAPTURE_JPEG_QUALITY instead.
     */
    private fun CoroutineScope.handleVideoFrame(videoFrame: VideoFrame) {
        val bitmap = FrameConversions.frameToBitmap(videoFrame, FrameConversions.PREVIEW_JPEG_QUALITY) ?: return
        if (!isActive) return
        _currentFrame.value = bitmap
        sessionManager.publishFrame(OWNER, bitmap)
    }

    fun clearCapturedPhoto() {
        _capturedPhoto.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** Sets [errorMessage] (state) and emits the same text once on [errorEvents]. */
    fun setError(message: String) {
        _errorMessage.value = message
        _errorEvents.tryEmit(message)
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

What changed versus Phase A: `onFrameReceived`/`onPhotoTaken` dead callbacks are gone (Minor #14); the two hard-coded English strings are localized (ledger T4); the CREATE_FAILED path keeps the specific DAT message (Minor #5); the frame publisher checks `isActive` (Minor #6); frames go through `FrameConversions` (Minor #15) and into `sessionManager.publishFrame` (Recommendation 2); `setError` also emits on `errorEvents` (Minor #13); the "subscribe BEFORE start()" comment is corrected (Minor #17).

- [ ] **Step 1.15: Run the ViewModel and all glasses tests**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`; at least 57 unit tests pass (36 Phase A + 4 FrameConversions + 5 manager + 2 capturer + 10 ViewModel).

If `createFailedKeepsTheSpecificSessionError` fails with the generic `glasses_session_failed` text, `lastSessionError` is not being written before `tryEmit` in `ensureSessionStarted()` (Step 1.8) — fix the manager, never the test.

- [ ] **Step 1.16: Use `resetForTests()` in the instrumented tearDown**

In `GlassesSessionManagerInstrumentedTest.kt`, in `fun tearDown()`, replace the block

```kotlin
        onMain {
            manager.release(OWNER)
            manager.release("WearablesViewModel")
            manager.release("QuickVisionService")
            manager.stopSession()
            withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                manager.sessionState.first { it == DeviceSessionState.STOPPED }
            }
        }
```

with

```kotlin
        onMain {
            manager.release(OWNER)
            manager.release("WearablesViewModel")
            manager.release("QuickVisionService")
            manager.stopSession()
            withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                manager.sessionState.first { it == DeviceSessionState.STOPPED }
            }
            // Phase B: forget owners / parked session / latest frame so the next test starts clean.
            manager.resetForTests()
        }
```

- [ ] **Step 1.17: Build both variants**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 1.18: Commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/src/main/java/com/smartview/glassai/glasses android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-zh-rCN/strings.xml android/app/src/test android/app/src/androidTest && git commit -m "refactor(android): FrameConversions, manager latestFrame/publishFrame, injectable WearablesViewModel with DatRegistrationGateway, resetForTests hook, JVM ViewModel tests"
```

---

### Task 2: OpenClaw models, settings store and Ed25519 device identity

**Files:**
- Modify: `gradle/libs.versions.toml` (anchors: `security = "1.1.0-alpha06"`, `androidx-security-crypto = …`, `okhttp-logging = …`)
- Modify: `app/build.gradle.kts` (anchors: `implementation(libs.androidx.security.crypto)`, `testImplementation(libs.kotlinx.coroutines.test)`)
- Create: `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawModels.kt`
- Create: `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawSettingsStore.kt`
- Create: `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawDeviceIdentity.kt`
- Modify: `app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt` (anchors: `private const val KEY_RTMP_URL = "rtmp_url"`, `// RTMP URL`)
- Create: `app/src/test/java/com/smartview/glassai/services/openclaw/InMemoryOpenClawSettingsStore.kt`, `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawDeviceIdentityTest.kt`, `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawModelsTest.kt`

**Interfaces:**
- Produces `object OpenClawProtocol` (all constants), `sealed class OpenClawConnectionState`, `sealed class OpenClawErrorReason`, `data class OpenClawChatEvent(text, isFinal)`, `data class OpenClawNodeInvokeRequest(id, command, params: JsonObject?, timeoutMs: Long?)`, `data class OpenClawError(code, message)`, `data class OpenClawNodeInvokeResult(id, ok, payload: JsonObject?, error: OpenClawError?)`, `data class CameraSnapParams(maxWidth, quality, format) { companion fun from(JsonObject?) }`, `data class OpenClawChatMessage(id, role, text, image: Bitmap?, timestampMs)`, `data class OpenClawClientInfo(version, modelIdentifier, nodeId)`
- Produces `interface OpenClawSettingsStore`, `class SecureOpenClawSettingsStore(context: Context)` (resolves `APIKeyManager.getInstance` lazily on first use, so building the OpenClaw singleton at process start never opens `EncryptedSharedPreferences`)
- Produces `class OpenClawDeviceIdentity(seed: ByteArray) { val publicKey: ByteArray; val deviceId: String; val publicKeyBase64Url: String; fun sign(payload: String): String; fun signConnect(...): String; companion { fun generate(); fun fromSeed(seed); fun buildSignaturePayload(...); fun normalizeForAuth(String?): String; fun base64Url(bytes): String; fun sha256Hex(bytes): String } }`, `object OpenClawDeviceIdentityStore { fun loadOrCreate(store: OpenClawSettingsStore): OpenClawDeviceIdentity }`
- Produces on `APIKeyManager`: `getOpenClawHost/saveOpenClawHost`, `getOpenClawPort/saveOpenClawPort`, `getOpenClawScheme/saveOpenClawScheme`, `getOpenClawToken/saveOpenClawToken/deleteOpenClawToken`, `getOpenClawDeviceSeed/saveOpenClawDeviceSeed`

- [ ] **Step 2.1: Add the dependencies**

In `gradle/libs.versions.toml`, under `[versions]` after `security = "1.1.0-alpha06"` add:

```toml
tink = "1.20.0"
```

Under `[libraries]` after the `androidx-security-crypto` line add:

```toml
# Ed25519 for the OpenClaw device identity. 1.20.0 is exactly what mwdat-core 0.9.0 already
# depends on, so declaring it here pins the compile classpath without changing the resolved version.
tink-android = { group = "com.google.crypto.tink", name = "tink-android", version.ref = "tink" }
```

After the `okhttp-logging` line add:

```toml
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
```

In `app/build.gradle.kts` after `implementation(libs.androidx.security.crypto)` add:

```kotlin
    // Ed25519 device identity for OpenClaw (Android 12 has no java.security Ed25519 provider)
    implementation(libs.tink.android)
```

After `testImplementation(libs.kotlinx.coroutines.test)` add:

```kotlin
    // OpenClaw / Fun-ASR protocol tests run against an in-process WebSocket server
    testImplementation(libs.okhttp.mockwebserver)
```

After `androidTestImplementation(libs.androidx.test.rules)` add (Task 9 runs a real handshake over cleartext `ws://` against a MockWebServer inside the instrumented app process):

```kotlin
    androidTestImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 2.2: Write the failing identity and model tests**

Create `app/src/test/java/com/smartview/glassai/services/openclaw/InMemoryOpenClawSettingsStore.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

class InMemoryOpenClawSettingsStore : OpenClawSettingsStore {
    override var host: String = OpenClawProtocol.DEFAULT_HOST
    override var port: Int = OpenClawProtocol.DEFAULT_PORT
    override var scheme: String = OpenClawProtocol.SCHEME_WS
    private var token: String? = null
    private var seed: ByteArray? = null
    var seedSaves = 0

    override fun loadToken(): String? = token
    override fun saveToken(token: String?) {
        this.token = token?.takeIf { it.isNotBlank() }
    }

    override fun loadDeviceSeed(): ByteArray? = seed
    override fun saveDeviceSeed(seed: ByteArray) {
        seedSaves++
        this.seed = seed.copyOf()
    }
}
```

Create `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawDeviceIdentityTest.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawDeviceIdentityTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 8032 §7.1 TEST 1: seed, public key, signature over the empty message. */
    private val rfcSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val rfcPublicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val rfcSignatureOfEmpty = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    )

    @Test
    fun rfc8032TestVectorOneMatches() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        assertArrayEquals(rfcPublicKey, identity.publicKey)
        val signature = Base64.getUrlDecoder().decode(identity.sign(""))
        assertArrayEquals(rfcSignatureOfEmpty, signature)
    }

    @Test
    fun deviceIdIsSha256HexOfRawPublicKey() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val expected = MessageDigest.getInstance("SHA-256").digest(rfcPublicKey)
            .joinToString("") { "%02x".format(it) }
        assertEquals(64, identity.deviceId.length)
        assertEquals(expected, identity.deviceId)
    }

    @Test
    fun publicKeyIsBase64UrlWithoutPadding() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val encoded = identity.publicKeyBase64Url
        assertTrue(encoded, !encoded.contains('=') && !encoded.contains('+') && !encoded.contains('/'))
        assertArrayEquals(rfcPublicKey, Base64.getUrlDecoder().decode(encoded))
    }

    @Test
    fun signatureStringMatchesTheIosLayout() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            deviceId = identity.deviceId,
            clientId = OpenClawProtocol.CLIENT_ID,
            clientMode = OpenClawProtocol.CLIENT_MODE,
            role = OpenClawProtocol.ROLE,
            scopes = OpenClawProtocol.SCOPES,
            signedAtMs = 1711700000000L,
            token = "abc",
            nonce = "n1",
            platform = OpenClawProtocol.PLATFORM,
            deviceFamily = null,
        )
        assertEquals(
            "v3|${identity.deviceId}|openclaw-android|node|operator|operator.read,operator.write|1711700000000|abc|n1|android|",
            payload,
        )
    }

    @Test
    fun missingTokenIsAnEmptyField() {
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            deviceId = "d", clientId = "c", clientMode = "node", role = "operator",
            scopes = listOf("a", "b"), signedAtMs = 1L, token = null, nonce = "n",
            platform = "android", deviceFamily = "Pixel 5",
        )
        assertEquals("v3|d|c|node|operator|a,b|1||n|android|pixel5", payload)
    }

    @Test
    fun normalizeForAuthTrimsLowercasesAndStripsPunctuation() {
        assertEquals("android", OpenClawDeviceIdentity.normalizeForAuth("  Android "))
        assertEquals("ray-ban_meta.v2", OpenClawDeviceIdentity.normalizeForAuth("Ray-Ban_Meta.v2!"))
        assertEquals("", OpenClawDeviceIdentity.normalizeForAuth(null))
        assertEquals("", OpenClawDeviceIdentity.normalizeForAuth("   "))
    }

    @Test
    fun connectSignatureVerifiesWithTink() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val signedAt = 1711700000000L
        val signature = identity.signConnect(
            clientId = OpenClawProtocol.CLIENT_ID,
            clientMode = OpenClawProtocol.CLIENT_MODE,
            role = OpenClawProtocol.ROLE,
            scopes = OpenClawProtocol.SCOPES,
            signedAtMs = signedAt,
            token = "tok",
            nonce = "nonce-1",
            platform = OpenClawProtocol.PLATFORM,
            deviceFamily = null,
        )
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            identity.deviceId, OpenClawProtocol.CLIENT_ID, OpenClawProtocol.CLIENT_MODE, OpenClawProtocol.ROLE,
            OpenClawProtocol.SCOPES, signedAt, "tok", "nonce-1", OpenClawProtocol.PLATFORM, null,
        )
        // verify() throws GeneralSecurityException on a bad signature
        Ed25519Verify(identity.publicKey).verify(
            Base64.getUrlDecoder().decode(signature),
            payload.toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun storeCreatesOnceThenReloadsTheSameIdentity() {
        val store = InMemoryOpenClawSettingsStore()
        val first = OpenClawDeviceIdentityStore.loadOrCreate(store)
        val second = OpenClawDeviceIdentityStore.loadOrCreate(store)
        assertEquals(1, store.seedSaves)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(32, store.loadDeviceSeed()!!.size)
    }

    @Test
    fun storeReplacesAnInvalidSeed() {
        val store = InMemoryOpenClawSettingsStore()
        store.saveDeviceSeed(ByteArray(5))
        val identity = OpenClawDeviceIdentityStore.loadOrCreate(store)
        assertEquals(2, store.seedSaves)
        assertEquals(32, store.loadDeviceSeed()!!.size)
        // the persisted seed reproduces the identity that was handed out
        assertEquals(identity.deviceId, OpenClawDeviceIdentity.fromSeed(store.loadDeviceSeed()!!).deviceId)
    }
}
```

Create `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawModelsTest.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawModelsTest {

    @Test
    fun snapParamsDefaultsMatchIos() {
        val params = CameraSnapParams.from(null)
        assertEquals(1600, params.maxWidth)
        assertEquals(0.8, params.quality, 0.0001)
        assertEquals("jpg", params.format)
    }

    @Test
    fun snapParamsReadTheProvidedFields() {
        val json = JsonParser.parseString("""{"maxWidth": 800, "quality": 0.5, "format": "jpeg"}""").asJsonObject
        val params = CameraSnapParams.from(json)
        assertEquals(800, params.maxWidth)
        assertEquals(0.5, params.quality, 0.0001)
        assertEquals("jpeg", params.format)
    }

    @Test
    fun snapParamsIgnoreMalformedFields() {
        val json = JsonParser.parseString("""{"maxWidth": "wide", "quality": null}""").asJsonObject
        val params = CameraSnapParams.from(json)
        assertEquals(1600, params.maxWidth)
        assertEquals(0.8, params.quality, 0.0001)
    }

    @Test
    fun protocolConstantsAreTheAndroidOnes() {
        assertEquals(3, OpenClawProtocol.PROTOCOL_VERSION)
        assertEquals("openclaw-android", OpenClawProtocol.CLIENT_ID)
        assertEquals("android", OpenClawProtocol.PLATFORM)
        assertEquals("Ray-Ban Meta Glasses", OpenClawProtocol.DISPLAY_NAME)
        assertEquals("node", OpenClawProtocol.CLIENT_MODE)
        assertEquals("operator", OpenClawProtocol.ROLE)
        assertEquals(listOf("operator.read", "operator.write"), OpenClawProtocol.SCOPES)
        assertEquals(listOf("camera"), OpenClawProtocol.CAPS)
        assertEquals(listOf("camera.snap", "camera.list", "device.status", "device.info"), OpenClawProtocol.COMMANDS)
        assertEquals("turbometa-chat", OpenClawProtocol.SESSION_KEY)
        assertEquals(18789, OpenClawProtocol.DEFAULT_PORT)
        assertEquals("127.0.0.1", OpenClawProtocol.DEFAULT_HOST)
    }
}
```

- [ ] **Step 2.3: Run and watch them fail**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.*" 2>&1 | tail -20
```
Expected: `BUILD FAILED` with unresolved references (`OpenClawDeviceIdentity`, `OpenClawProtocol`, …).

- [ ] **Step 2.4: Create `OpenClawModels.kt`**

```kotlin
package com.smartview.glassai.services.openclaw

import android.graphics.Bitmap
import com.google.gson.JsonObject
import java.util.UUID

/** Wire-protocol constants. Field names and values must stay identical to iOS (research §8.6). */
object OpenClawProtocol {
    const val PROTOCOL_VERSION = 3
    const val CLIENT_ID = "openclaw-android"
    const val CLIENT_MODE = "node"
    const val PLATFORM = "android"
    const val DISPLAY_NAME = "Ray-Ban Meta Glasses"
    const val ROLE = "operator"
    val SCOPES: List<String> = listOf("operator.read", "operator.write")
    val CAPS: List<String> = listOf("camera")
    val COMMANDS: List<String> = listOf("camera.snap", "camera.list", "device.status", "device.info")
    const val SESSION_KEY = "turbometa-chat"

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 18789
    const val SCHEME_WS = "ws"
    const val SCHEME_WSS = "wss"

    // Error codes
    const val ERROR_NOT_PAIRED = "NOT_PAIRED"
    const val ERROR_UNSUPPORTED = "UNSUPPORTED"
    const val ERROR_NO_ROUTER = "NO_ROUTER"
    const val ERROR_UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
    const val ERROR_NOT_READY = "NOT_READY"
    const val ERROR_STREAM_FAILED = "STREAM_FAILED"
    const val ERROR_NO_FRAME = "NO_FRAME"
    const val ERROR_ENCODE_FAILED = "ENCODE_FAILED"
    const val ERROR_PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val ERROR_TIMEOUT = "TIMEOUT"
    const val ERROR_INTERNAL = "INTERNAL"
}

/** Why the connection is in the Error state; the UI maps each reason to a localized string. */
sealed class OpenClawErrorReason {
    data class MaxRetries(val attempts: Int) : OpenClawErrorReason()
    data class Transport(val detail: String) : OpenClawErrorReason()
    object InvalidUrl : OpenClawErrorReason()
}

sealed class OpenClawConnectionState {
    object Disconnected : OpenClawConnectionState()
    object Connecting : OpenClawConnectionState()
    /**
     * The socket dropped and the backoff timer for [attempt] (1-based) is running. Distinct from
     * [Disconnected] so the Home/chat auto-connect does not defeat the 2/4/8/16/30 s sequence by
     * dialing immediately; the explicit Connect button uses connect(force = true).
     */
    data class Reconnecting(val attempt: Int) : OpenClawConnectionState()
    /** The gateway answered NOT_PAIRED: run `openclaw devices approve` on the gateway host. */
    object WaitingForPairing : OpenClawConnectionState()
    object Connected : OpenClawConnectionState()
    data class Error(val reason: OpenClawErrorReason) : OpenClawConnectionState()
}

/** One `chat` event: [isFinal] replaces the iOS "[[FINAL]]" prefix. Non-final text is replace-style. */
data class OpenClawChatEvent(val text: String, val isFinal: Boolean)

data class OpenClawNodeInvokeRequest(
    val id: String,
    val command: String,
    val params: JsonObject?,
    val timeoutMs: Long?,
)

data class OpenClawError(val code: String?, val message: String?)

/** Router result; the service adds `nodeId` when it builds `node.invoke.result`. */
data class OpenClawNodeInvokeResult(
    val id: String,
    val ok: Boolean,
    val payload: JsonObject?,
    val error: OpenClawError?,
) {
    companion object {
        fun success(id: String, payload: JsonObject) = OpenClawNodeInvokeResult(id, true, payload, null)
        fun failure(id: String, code: String, message: String) =
            OpenClawNodeInvokeResult(id, false, null, OpenClawError(code, message))
    }
}

/** `camera.snap` parameters with the iOS defaults; `format` is parsed but always JPEG. */
data class CameraSnapParams(
    val maxWidth: Int = 1600,
    val quality: Double = 0.8,
    val format: String = "jpg",
) {
    companion object {
        fun from(json: JsonObject?): CameraSnapParams {
            if (json == null) return CameraSnapParams()
            val maxWidth = runCatching { json.get("maxWidth")?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()
            val quality = runCatching { json.get("quality")?.takeIf { it.isJsonPrimitive }?.asDouble }.getOrNull()
            val format = runCatching { json.get("format")?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
            return CameraSnapParams(
                maxWidth = maxWidth ?: 1600,
                quality = quality ?: 0.8,
                format = format ?: "jpg",
            )
        }
    }
}

/** Chat bubble (in memory only, spec §3 decision 3). */
data class OpenClawChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant"
    val text: String,
    val image: Bitmap? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** Build-time facts the service puts into `connect.client` and `node.invoke.result.nodeId`. */
data class OpenClawClientInfo(
    val version: String,
    val modelIdentifier: String,
    /** "rayban-" + first 8 hex chars of ANDROID_ID, lowercase. */
    val nodeId: String,
)
```

- [ ] **Step 2.5: Create `OpenClawSettingsStore.kt` and extend `APIKeyManager`**

Create `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawSettingsStore.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

import android.content.Context
import com.smartview.glassai.utils.APIKeyManager

/**
 * Persistence seam for the gateway settings, the gateway token and the Ed25519 seed. The real
 * store writes everything into APIKeyManager's EncryptedSharedPreferences (like rtmp_url); tests
 * use InMemoryOpenClawSettingsStore.
 */
interface OpenClawSettingsStore {
    var host: String
    var port: Int
    /** "ws" or "wss". */
    var scheme: String
    fun loadToken(): String?
    /** null or blank deletes the stored token (fixes the iOS quirk where emptying the field kept it). */
    fun saveToken(token: String?)
    fun loadDeviceSeed(): ByteArray?
    fun saveDeviceSeed(seed: ByteArray)
}

/**
 * [APIKeyManager.getInstance] builds a MasterKey and opens EncryptedSharedPreferences, which is
 * slow and can throw (a restored prefs file without its Keystore key). Resolving it lazily keeps
 * OpenClawNodeService.getInstance() free of I/O at process start; the first Settings read or
 * connect() pays the cost, exactly like every other feature screen today.
 */
class SecureOpenClawSettingsStore(context: Context) : OpenClawSettingsStore {
    private val appContext = context.applicationContext
    private val apiKeyManager: APIKeyManager by lazy { APIKeyManager.getInstance(appContext) }

    override var host: String
        get() = apiKeyManager.getOpenClawHost()
        set(value) = apiKeyManager.saveOpenClawHost(value)

    override var port: Int
        get() = apiKeyManager.getOpenClawPort()
        set(value) = apiKeyManager.saveOpenClawPort(value)

    override var scheme: String
        get() = apiKeyManager.getOpenClawScheme()
        set(value) = apiKeyManager.saveOpenClawScheme(value)

    override fun loadToken(): String? = apiKeyManager.getOpenClawToken()

    override fun saveToken(token: String?) {
        if (token.isNullOrBlank()) apiKeyManager.deleteOpenClawToken() else apiKeyManager.saveOpenClawToken(token)
    }

    override fun loadDeviceSeed(): ByteArray? = apiKeyManager.getOpenClawDeviceSeed()

    override fun saveDeviceSeed(seed: ByteArray) = apiKeyManager.saveOpenClawDeviceSeed(seed)
}
```

In `APIKeyManager.kt`, after `private const val KEY_RTMP_URL = "rtmp_url"` add:

```kotlin
        // OpenClaw (Phase B). Non-secret settings live next to rtmp_url; the token and the
        // Ed25519 seed need the encrypted store (Android Keystore has no Ed25519).
        private const val KEY_OPENCLAW_HOST = "openclaw_host"
        private const val KEY_OPENCLAW_PORT = "openclaw_port"
        private const val KEY_OPENCLAW_SCHEME = "openclaw_scheme"
        private const val KEY_OPENCLAW_TOKEN = "openclaw_gateway_token"
        private const val KEY_OPENCLAW_DEVICE_SEED = "openclaw_ed25519_seed"
```

After the `getRtmpUrl()` function (before the class's closing brace) add:

```kotlin
    // MARK: - OpenClaw (Phase B)

    fun getOpenClawHost(): String =
        sharedPreferences.getString(KEY_OPENCLAW_HOST, null)?.takeIf { it.isNotBlank() } ?: "127.0.0.1"

    fun saveOpenClawHost(host: String) {
        sharedPreferences.edit().putString(KEY_OPENCLAW_HOST, host.trim()).apply()
    }

    fun getOpenClawPort(): Int {
        val port = sharedPreferences.getInt(KEY_OPENCLAW_PORT, 0)
        return if (port in 1..65535) port else 18789
    }

    fun saveOpenClawPort(port: Int) {
        sharedPreferences.edit().putInt(KEY_OPENCLAW_PORT, port).apply()
    }

    fun getOpenClawScheme(): String =
        if (sharedPreferences.getString(KEY_OPENCLAW_SCHEME, null) == "wss") "wss" else "ws"

    fun saveOpenClawScheme(scheme: String) {
        sharedPreferences.edit().putString(KEY_OPENCLAW_SCHEME, if (scheme == "wss") "wss" else "ws").apply()
    }

    fun getOpenClawToken(): String? = try {
        sharedPreferences.getString(KEY_OPENCLAW_TOKEN, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read OpenClaw token: ${e.message}")
        null
    }

    fun saveOpenClawToken(token: String) {
        sharedPreferences.edit().putString(KEY_OPENCLAW_TOKEN, token.trim()).apply()
    }

    fun deleteOpenClawToken() {
        sharedPreferences.edit().remove(KEY_OPENCLAW_TOKEN).apply()
    }

    /** The 32-byte Ed25519 seed, stored as standard base64. */
    fun getOpenClawDeviceSeed(): ByteArray? = try {
        sharedPreferences.getString(KEY_OPENCLAW_DEVICE_SEED, null)
            ?.let { java.util.Base64.getDecoder().decode(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read OpenClaw device seed: ${e.message}")
        null
    }

    fun saveOpenClawDeviceSeed(seed: ByteArray) {
        sharedPreferences.edit()
            .putString(KEY_OPENCLAW_DEVICE_SEED, java.util.Base64.getEncoder().encodeToString(seed))
            .apply()
    }
```

- [ ] **Step 2.6: Create `OpenClawDeviceIdentity.kt`**

```kotlin
package com.smartview.glassai.services.openclaw

import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Sign
import java.security.MessageDigest
import java.util.Base64

/**
 * Per-install Ed25519 identity (research §2.4). The 32-byte seed is the only persisted secret;
 * the public key, deviceId and every signature derive from it.
 *
 * Signature payload (v3), UTF-8, signed with Ed25519, output base64url without padding:
 * `v3|deviceId|clientId|clientMode|role|scopes(',')|signedAtMs|token or ''|nonce|normalize(platform)|normalize(deviceFamily) or ''`
 */
class OpenClawDeviceIdentity private constructor(
    private val seed: ByteArray,
    val publicKey: ByteArray,
) {
    private val signer = Ed25519Sign(seed)

    /** Lowercase hex SHA-256 of the raw 32-byte public key (64 chars). */
    val deviceId: String = sha256Hex(publicKey)

    /** base64url(raw public key), no padding. */
    val publicKeyBase64Url: String = base64Url(publicKey)

    /** Signs an arbitrary UTF-8 payload; returns base64url without padding, "" on failure. */
    fun sign(payload: String): String = try {
        base64Url(signer.sign(payload.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.e(TAG, "sign failed: ${e.message}")
        ""
    }

    fun signConnect(
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String?,
        nonce: String,
        platform: String,
        deviceFamily: String?,
    ): String = sign(
        buildSignaturePayload(
            deviceId, clientId, clientMode, role, scopes, signedAtMs, token, nonce, platform, deviceFamily,
        )
    )

    companion object {
        private const val TAG = "OpenClawDeviceIdentity"
        const val SEED_LENGTH = 32

        fun generate(): OpenClawDeviceIdentity {
            val pair = Ed25519Sign.KeyPair.newKeyPair()
            return OpenClawDeviceIdentity(pair.privateKey, pair.publicKey)
        }

        fun fromSeed(seed: ByteArray): OpenClawDeviceIdentity {
            require(seed.size == SEED_LENGTH) { "Ed25519 seed must be $SEED_LENGTH bytes, got ${seed.size}" }
            val pair = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
            return OpenClawDeviceIdentity(seed.copyOf(), pair.publicKey)
        }

        fun buildSignaturePayload(
            deviceId: String,
            clientId: String,
            clientMode: String,
            role: String,
            scopes: List<String>,
            signedAtMs: Long,
            token: String?,
            nonce: String,
            platform: String,
            deviceFamily: String?,
        ): String = listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMs.toString(),
            token ?: "",
            nonce,
            normalizeForAuth(platform),
            normalizeForAuth(deviceFamily),
        ).joinToString("|")

        /** trim, lowercase, keep only letters/digits and `.`, `_`, `-` (iOS normalizeForAuth). */
        fun normalizeForAuth(value: String?): String {
            if (value == null) return ""
            return value.trim().lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        }

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Loads the persisted seed or generates + stores a new one (stable across launches). */
object OpenClawDeviceIdentityStore {
    private const val TAG = "OpenClawIdentityStore"

    fun loadOrCreate(store: OpenClawSettingsStore): OpenClawDeviceIdentity {
        val seed = store.loadDeviceSeed()
        if (seed != null && seed.size == OpenClawDeviceIdentity.SEED_LENGTH) {
            try {
                return OpenClawDeviceIdentity.fromSeed(seed)
            } catch (e: Exception) {
                Log.w(TAG, "stored seed unusable (${e.message}); generating a new identity")
            }
        }
        val fresh = OpenClawDeviceIdentity.generate()
        store.saveDeviceSeed(fresh.seedCopy())
        return fresh
    }
}
```

Then add the following member inside `class OpenClawDeviceIdentity` (after `fun signConnect(...)`, before `companion object`), so the store can persist a fresh identity:

```kotlin
    /** Copy of the seed for persistence (module-internal); never log it. */
    internal fun seedCopy(): ByteArray = seed.copyOf()
```

- [ ] **Step 2.7: Run the tests**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.*" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, at least 13 tests pass (9 identity + 4 models). If `rfc8032TestVectorOneMatches` fails on the signature bytes but the public key matches, the seed→key derivation is right and only the vector transcription is wrong: re-check the hex literal against RFC 8032 §7.1 "TEST 1" (the `SIGNATURE` line) before touching production code.

- [ ] **Step 2.8: Build and commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:assembleDebug 2>&1 | tail -10
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src/main/java/com/smartview/glassai/services/openclaw android/app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt android/app/src/test/java/com/smartview/glassai/services/openclaw && git commit -m "feat(android): OpenClaw protocol models, encrypted settings store and Tink Ed25519 device identity with v3 signature"
```

---

### Task 3: `OpenClawNodeService` — gateway WebSocket client (handshake, chat, invoke, tick, reconnect)

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawNodeService.kt`
- Create: `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouter.kt` (interface-level stub in this task: the `handleCommand` contract the service needs; Task 4 fills the commands in)
- Create: `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceTest.kt`, `app/src/test/java/com/smartview/glassai/services/openclaw/ScriptedGateway.kt`

**Interfaces:**
- Produces:
```kotlin
class OpenClawNodeService(
    store: OpenClawSettingsStore,
    identity: Lazy<OpenClawDeviceIdentity>,   // resolved on the first connect(), never at construction
    clientInfo: OpenClawClientInfo,
    httpClient: OkHttpClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    tickIntervalMs: Long = 15_000L,
    reconnectDelaysMs: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 30_000),
    maxReconnectAttempts: Int = 5,
    invokeTimeoutMs: Long = 30_000L,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    val connectionState: StateFlow<OpenClawConnectionState>
    val chatEvents: SharedFlow<OpenClawChatEvent>
    val nodeId: String
    var gatewayHost: String; var gatewayPort: Int; var gatewayScheme: String
    fun loadGatewayToken(): String?; fun saveGatewayToken(token: String?)
    fun setCommandRouter(router: OpenClawCommandHandler?)
    /** force = true (Settings button) cancels a running backoff and dials now; false (auto-connect) respects it. */
    fun connect(force: Boolean = false); fun disconnect()
    fun sendChatMessage(text: String, imageJpegBase64: String? = null): Boolean
    @VisibleForTesting internal fun buildUrl(): String?          // "ws(s)://host:port/?token=…" or null
    @VisibleForTesting internal val isTickRunning: Boolean
    companion object { fun getInstance(context: Context): OpenClawNodeService; fun lanHttpClient(): OkHttpClient }
}
```
- Produces `class OpenClawCommandRouter(...) { suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult }` (abstract contract `OpenClawCommandHandler` so the service test can use a fake)
- Consumes: Task 2 models/identity/store, OkHttp `WebSocket`/`WebSocketListener`, Gson.

- [ ] **Step 3.1: Create the command handler contract (`OpenClawCommandRouter.kt`, contract only)**

```kotlin
package com.smartview.glassai.services.openclaw

/**
 * What OpenClawNodeService needs from a router: one suspend call per `node.invoke`.
 * OpenClawCommandRouter (Task 4) is the production implementation; tests use a fake.
 */
interface OpenClawCommandHandler {
    suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult
}
```
(Task 4 appends `class OpenClawCommandRouter … : OpenClawCommandHandler` to this same file.)

- [ ] **Step 3.2: Write the scripted gateway test double**

Create `app/src/test/java/com/smartview/glassai/services/openclaw/ScriptedGateway.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * A MockWebServer-hosted stand-in for the OpenClaw Gateway. Every accepted socket sends a
 * connect.challenge on open and answers the `connect` request per [connectReply]. Every frame the
 * app sends is parsed and queued in [received]; the latest server-side socket is in [socket].
 */
class ScriptedGateway(val nonce: String = "nonce-1") {
    enum class ConnectReply { OK, NOT_PAIRED, SILENT }

    val server = MockWebServer()
    val received = LinkedBlockingQueue<JsonObject>()
    @Volatile var socket: WebSocket? = null
    @Volatile var connectReply: ConnectReply = ConnectReply.OK
    @Volatile var opens = 0
    /** Client-initiated closes seen by the server (onClosing). */
    @Volatile var closes = 0

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opens++
            webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"$nonce"}}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closes++
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JsonParser.parseString(text).asJsonObject
            received.add(json)
            if (json.get("type")?.asString == "req" && json.get("method")?.asString == "connect") {
                val id = json.get("id").asString
                when (connectReply) {
                    ConnectReply.OK -> webSocket.send("""{"type":"res","id":"$id","ok":true,"payload":{"protocol":3}}""")
                    ConnectReply.NOT_PAIRED -> webSocket.send(
                        """{"type":"res","id":"$id","ok":false,"error":{"code":"NOT_PAIRED","message":"device not paired"}}"""
                    )
                    ConnectReply.SILENT -> Unit
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())
    }

    /** Queues [count] WebSocket upgrades (one per expected connection attempt). */
    fun start(count: Int = 1) {
        repeat(count) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
    }

    fun enqueueUpgrade() {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    fun send(text: String) {
        checkNotNull(socket) { "no client connected" }.send(text)
    }

    /** Same JSON, but as a binary WebSocket frame (the app must decode it as UTF-8, like iOS). */
    fun sendBinary(text: String) {
        checkNotNull(socket) { "no client connected" }.send(text.encodeUtf8())
    }

    /** Waits for the next frame whose `method` (req) or `type` matches [predicate]. */
    fun await(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            check(remaining > 0) { "timed out waiting for a matching frame" }
            val next = received.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(next)) return next
        }
    }

    fun awaitMethod(method: String, timeoutMs: Long = 5_000): JsonObject =
        await(timeoutMs) { it.get("type")?.asString == "req" && it.get("method")?.asString == method }

    fun stop() {
        server.shutdown()
    }
}
```

- [ ] **Step 3.3: Write the failing service test**

Create `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceTest.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawNodeServiceTest {

    private val gateway = ScriptedGateway()
    private val store = InMemoryOpenClawSettingsStore()
    private val identity = OpenClawDeviceIdentity.fromSeed(ByteArray(32) { 7 })
    private val clientInfo = OpenClawClientInfo(version = "2.0.0", modelIdentifier = "Pixel 5", nodeId = "rayban-0123abcd")
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()
    private val chatEvents = mutableListOf<OpenClawChatEvent>()
    private lateinit var service: OpenClawNodeService

    private fun newService(
        reconnectDelaysMs: List<Long> = listOf(100L),
        tickIntervalMs: Long = 60_000L,
        maxReconnectAttempts: Int = 5,
    ): OpenClawNodeService = OpenClawNodeService(
        store = store,
        identity = lazyOf(identity),
        clientInfo = clientInfo,
        httpClient = httpClient,
        tickIntervalMs = tickIntervalMs,
        reconnectDelaysMs = reconnectDelaysMs,
        maxReconnectAttempts = maxReconnectAttempts,
        clock = { 1711700000000L },
    )

    private fun <T> StateFlow<T>.awaitValue(timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(timeoutMs) { first(predicate) } }

    /**
     * Records every state the flow emits from now on. Subscribes before returning, so a transient
     * state (Reconnecting/Connecting) cannot be missed by a later `first { }` on the conflated flow.
     */
    private fun recordStates(service: OpenClawNodeService): Pair<MutableList<OpenClawConnectionState>, Job> {
        val states = CopyOnWriteArrayList<OpenClawConnectionState>()
        val subscribed = CountDownLatch(1)
        val job = CoroutineScope(Dispatchers.Default).launch {
            service.connectionState.collect {
                states += it
                subscribed.countDown()
            }
        }
        assertTrue(subscribed.await(2, TimeUnit.SECONDS))
        return states to job
    }

    @Before
    fun setUp() {
        store.host = "127.0.0.1"
        store.scheme = "ws"
        store.saveToken("secret-token")
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.disconnect()
        gateway.stop()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun startGatewayAndConnect(upgrades: Int = 1): OpenClawNodeService {
        gateway.start(upgrades)
        store.port = gateway.server.port
        service = newService()
        service.connect()
        return service
    }

    @Test
    fun handshakeSendsTheIosConnectFrameAndBecomesConnected() {
        val service = startGatewayAndConnect()
        val connect = gateway.awaitMethod("connect")

        assertEquals("req", connect.get("type").asString)
        val params = connect.getAsJsonObject("params")
        assertEquals(3, params.get("minProtocol").asInt)
        assertEquals(3, params.get("maxProtocol").asInt)
        val client = params.getAsJsonObject("client")
        assertEquals("openclaw-android", client.get("id").asString)
        assertEquals("Ray-Ban Meta Glasses", client.get("displayName").asString)
        assertEquals("2.0.0", client.get("version").asString)
        assertEquals("node", client.get("mode").asString)
        assertEquals("android", client.get("platform").asString)
        assertEquals("Pixel 5", client.get("modelIdentifier").asString)
        assertEquals("operator", params.get("role").asString)
        assertEquals(listOf("operator.read", "operator.write"), params.getAsJsonArray("scopes").map { it.asString })
        assertEquals(listOf("camera"), params.getAsJsonArray("caps").map { it.asString })
        assertEquals(
            listOf("camera.snap", "camera.list", "device.status", "device.info"),
            params.getAsJsonArray("commands").map { it.asString },
        )
        assertEquals("secret-token", params.getAsJsonObject("auth").get("token").asString)
        val device = params.getAsJsonObject("device")
        assertEquals(identity.deviceId, device.get("id").asString)
        assertEquals(identity.publicKeyBase64Url, device.get("publicKey").asString)
        assertEquals(1711700000000L, device.get("signedAt").asLong)
        assertEquals("nonce-1", device.get("nonce").asString)
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            identity.deviceId, "openclaw-android", "node", "operator", listOf("operator.read", "operator.write"),
            1711700000000L, "secret-token", "nonce-1", "android", null,
        )
        Ed25519Verify(identity.publicKey).verify(
            Base64.getUrlDecoder().decode(device.get("signature").asString),
            payload.toByteArray(Charsets.UTF_8),
        )

        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        // token also travels as ?token= (URL-encoded)
        val request = gateway.server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/?token=secret-token", request.path)
    }

    @Test
    fun notPairedMovesToWaitingForPairing() {
        gateway.connectReply = ScriptedGateway.ConnectReply.NOT_PAIRED
        val service = startGatewayAndConnect()
        gateway.awaitMethod("connect")
        service.connectionState.awaitValue { it == OpenClawConnectionState.WaitingForPairing }
    }

    @Test
    fun chatEventsAreDeliveredTyped() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val collected = mutableListOf<OpenClawChatEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { collected += it } }
        Thread.sleep(200) // let the collector subscribe before the gateway emits
        gateway.send("""{"type":"event","event":"chat","payload":{"state":"delta","message":{"role":"assistant","content":[{"type":"text","text":"Hel"},{"type":"text","text":"lo"}]}}}""")
        gateway.send("""{"type":"event","event":"chat","payload":{"state":"final","message":{"role":"assistant","content":[{"type":"text","text":"Hello!"}]}}}""")
        val deadline = System.currentTimeMillis() + 5_000
        while (collected.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        job.cancel()

        assertEquals(listOf(OpenClawChatEvent("Hello", false), OpenClawChatEvent("Hello!", true)), collected)
    }

    @Test
    fun chatSendCarriesSessionKeyMessageIdempotencyKeyAndAttachment() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }

        assertTrue(service.sendChatMessage("hi there", imageJpegBase64 = "/9j/AAAA"))

        val frame = gateway.awaitMethod("chat.send")
        val params = frame.getAsJsonObject("params")
        assertEquals("turbometa-chat", params.get("sessionKey").asString)
        assertEquals("hi there", params.get("message").asString)
        assertFalse(params.get("idempotencyKey").asString.isBlank())
        val attachment = params.getAsJsonArray("attachments").single().asJsonObject
        assertEquals("image", attachment.get("type").asString)
        assertEquals("image/jpeg", attachment.get("mimeType").asString)
        assertEquals("/9j/AAAA", attachment.get("content").asString)
    }

    @Test
    fun invokeEventRoundTripsThroughTheRouter() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val seen = mutableListOf<OpenClawNodeInvokeRequest>()
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                seen += request
                val payload = JsonObject().apply { addProperty("deviceConnected", true) }
                return OpenClawNodeInvokeResult.success(request.id, payload)
            }
        })

        gateway.send("""{"type":"event","event":"node.invoke.request","payload":null,"params":{"id":"inv-1","command":"device.status","paramsjson":"{\"x\":1}","timeoutms":5000}}""")

        val result = gateway.awaitMethod("node.invoke.result")
        val params = result.getAsJsonObject("params")
        assertEquals("inv-1", params.get("id").asString)
        assertEquals("rayban-0123abcd", params.get("nodeId").asString)
        assertTrue(params.get("ok").asBoolean)
        val payload = JsonParser.parseString(params.get("payloadjson").asString).asJsonObject
        assertTrue(payload.get("deviceConnected").asBoolean)
        assertNull(params.get("error"))
        assertEquals("device.status", seen.single().command)
        assertEquals(1, seen.single().params!!.get("x").asInt)
        assertEquals(5000L, seen.single().timeoutMs)
    }

    @Test
    fun invokeRequestFrameUsesTheFrameIdAndReportsErrors() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
                OpenClawNodeInvokeResult.failure(request.id, "NO_FRAME", "No video frame available")
        })

        gateway.send("""{"type":"req","id":"req-9","method":"node.invoke","params":{"command":"camera.snap","params":{"maxWidth":800},"timeoutMs":1000}}""")

        val result = gateway.awaitMethod("node.invoke.result")
        val params = result.getAsJsonObject("params")
        assertEquals("req-9", params.get("id").asString)
        assertFalse(params.get("ok").asBoolean)
        assertEquals("NO_FRAME", params.getAsJsonObject("error").get("code").asString)
        assertEquals("No video frame available", params.getAsJsonObject("error").get("message").asString)
        assertNull(params.get("payloadjson"))
    }

    @Test
    fun reqFrameIdWinsOverParamsId() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
                OpenClawNodeInvokeResult.success(request.id, JsonObject())
        })

        gateway.send("""{"type":"req","id":"req-9","method":"node.invoke","params":{"id":"inv-x","command":"device.status"}}""")

        // research §2.5: for a `req node.invoke` the invoke id IS the frame id; params.id is ignored
        assertEquals("req-9", gateway.awaitMethod("node.invoke.result").getAsJsonObject("params").get("id").asString)
    }

    @Test
    fun malformedParamsJsonAndPayloadCarriedInvokesStillReachTheRouter() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val seen = CopyOnWriteArrayList<OpenClawNodeInvokeRequest>()
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                seen += request
                return OpenClawNodeInvokeResult.success(request.id, JsonObject())
            }
        })

        gateway.send("""{"type":"event","event":"node.invoke.request","params":{"id":"inv-bad","command":"camera.snap","paramsjson":"not json"}}""")
        val first = gateway.awaitMethod("node.invoke.result")
        assertEquals("inv-bad", first.getAsJsonObject("params").get("id").asString)
        assertNull(seen.single().params) // the router then applies the CameraSnapParams defaults
        assertEquals(1600, CameraSnapParams.from(seen.single().params).maxWidth)

        gateway.send("""{"type":"event","event":"node.invoke","payload":{"id":"inv-payload","command":"device.info"}}""")
        val second = gateway.awaitMethod("node.invoke.result")
        assertEquals("inv-payload", second.getAsJsonObject("params").get("id").asString)
        assertEquals("device.info", seen[1].command)
    }

    @Test
    fun dispatchAcceptsTheIosAliasesAndBinaryFrames() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val collected = CopyOnWriteArrayList<OpenClawChatEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { collected += it } }
        Thread.sleep(200)

        // `evt` + `method` instead of `event`/`event`, delivered as a binary frame
        gateway.sendBinary("""{"type":"evt","method":"chat","payload":{"state":"final","message":{"content":[{"type":"text","text":"bin"}]}}}""")
        // `request` instead of `req`
        gateway.send("""{"type":"request","id":"r-2","method":"nope","params":{}}""")

        val res = gateway.await { it.get("type")?.asString == "res" && it.get("id")?.asString == "r-2" }
        assertEquals("UNSUPPORTED", res.getAsJsonObject("error").get("code").asString)
        val deadline = System.currentTimeMillis() + 5_000
        while (collected.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        job.cancel()
        assertEquals(listOf(OpenClawChatEvent("bin", true)), collected)
    }

    @Test
    fun invokeWithoutRouterAnswersNoRouter() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        gateway.send("""{"type":"event","event":"node.invoke","params":{"id":"inv-2","command":"device.info"}}""")
        val result = gateway.awaitMethod("node.invoke.result")
        assertEquals("NO_ROUTER", result.getAsJsonObject("params").getAsJsonObject("error").get("code").asString)
    }

    @Test
    fun unknownRequestGetsUnsupported() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        gateway.send("""{"type":"req","id":"r-1","method":"something.else","params":{}}""")
        val res = gateway.await { it.get("type")?.asString == "res" && it.get("id")?.asString == "r-1" }
        assertFalse(res.get("ok").asBoolean)
        assertEquals("UNSUPPORTED", res.getAsJsonObject("error").get("code").asString)
        assertEquals("Unknown method: something.else", res.getAsJsonObject("error").get("message").asString)
    }

    @Test
    fun tickIsSentPeriodicallyWithTs() {
        gateway.start(1)
        store.port = gateway.server.port
        service = newService(tickIntervalMs = 200L)
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val tick = gateway.awaitMethod("tick")
        assertEquals(1711700000000L, tick.getAsJsonObject("params").get("ts").asLong)
    }

    @Test
    fun reconnectsAfterTheGatewayClosesTheSocket() {
        val service = startGatewayAndConnect(upgrades = 2)
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals(1, gateway.opens)
        val (states, recorder) = recordStates(service) // subscribed BEFORE the close: no missed transient

        gateway.socket!!.close(1000, "bye")

        service.connectionState.awaitValue(timeoutMs = 8_000) { it == OpenClawConnectionState.Connected && gateway.opens == 2 }
        recorder.cancel()
        assertTrue("states: $states", states.contains(OpenClawConnectionState.Reconnecting(1)))
        assertEquals(OpenClawConnectionState.Connected, states.last())
        assertEquals(2, gateway.opens)
        assertEquals(2, gateway.server.requestCount)
    }

    @Test
    fun connectDuringBackoffDoesNotDialEarlyButForceDoes() {
        gateway.start(2)
        store.port = gateway.server.port
        service = newService(reconnectDelaysMs = listOf(3_000L))
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }

        gateway.socket!!.close(1000, "bye")
        service.connectionState.awaitValue { it is OpenClawConnectionState.Reconnecting }
        service.connect() // what Home / the chat screen do on appear: must not cut the backoff short
        Thread.sleep(500)
        assertEquals(1, gateway.opens)
        assertTrue(service.connectionState.value is OpenClawConnectionState.Reconnecting)

        service.connect(force = true) // the Settings "Connect to Gateway" button: dial now
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals(2, gateway.opens)
    }

    @Test
    fun reconnectAttemptsResetAfterASuccessfulHello() {
        gateway.start(3)
        store.port = gateway.server.port
        service = newService(reconnectDelaysMs = listOf(50L), maxReconnectAttempts = 1)
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }

        repeat(2) { drop ->
            gateway.socket!!.close(1000, "drop ${drop + 1}")
            service.connectionState.awaitValue(timeoutMs = 8_000) {
                it == OpenClawConnectionState.Connected && gateway.opens == drop + 2
            }
        }

        // With maxReconnectAttempts = 1 the second drop would have ended in MaxRetries had the
        // counter not been reset to 0 by the hello that followed the first reconnect.
        assertEquals(3, gateway.opens)
        assertEquals(OpenClawConnectionState.Connected, service.connectionState.value)
    }

    @Test
    fun tickStopsOnDisconnect() {
        gateway.start(1)
        store.port = gateway.server.port
        service = newService(tickIntervalMs = 200L)
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        gateway.awaitMethod("tick")
        assertTrue(service.isTickRunning)

        service.disconnect()

        assertFalse(service.isTickRunning)
        gateway.received.clear()
        Thread.sleep(600)
        assertNull(gateway.received.poll())
    }

    @Test
    fun connectFromWaitingForPairingClosesTheOldSocketFirst() {
        gateway.connectReply = ScriptedGateway.ConnectReply.NOT_PAIRED
        val service = startGatewayAndConnect(upgrades = 2)
        service.connectionState.awaitValue { it == OpenClawConnectionState.WaitingForPairing }
        gateway.connectReply = ScriptedGateway.ConnectReply.OK // `openclaw devices approve` ran meanwhile

        service.connect(force = true)

        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals(2, gateway.opens)
        val deadline = System.currentTimeMillis() + 2_000
        while (gateway.closes < 1 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, gateway.closes) // the pairing-wait socket was closed, not orphaned
    }

    @Test
    fun withoutATokenAuthIsEmptyAndTheSignatureHasAnEmptyTokenField() {
        store.saveToken(null)
        val service = startGatewayAndConnect()
        val connect = gateway.awaitMethod("connect")
        val params = connect.getAsJsonObject("params")
        assertTrue(params.getAsJsonObject("auth").entrySet().isEmpty()) // `auth: {}`
        val device = params.getAsJsonObject("device")
        val payload = "v3|${identity.deviceId}|openclaw-android|node|operator|operator.read,operator.write|1711700000000||nonce-1|android|"
        Ed25519Verify(identity.publicKey).verify(
            Base64.getUrlDecoder().decode(device.get("signature").asString),
            payload.toByteArray(Charsets.UTF_8),
        )
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals("/", gateway.server.takeRequest(2, TimeUnit.SECONDS)!!.path) // no ?token=
    }

    @Test
    fun nonHelloResponsesDoNotChangeTheState() {
        gateway.connectReply = ScriptedGateway.ConnectReply.SILENT
        val service = startGatewayAndConnect()
        gateway.awaitMethod("connect")
        assertEquals(OpenClawConnectionState.Connecting, service.connectionState.value)

        gateway.send("""{"type":"res","id":"not-the-connect-id","ok":true,"payload":{"protocol":3}}""")
        gateway.send("""{"type":"res","id":"not-the-connect-id","ok":false,"error":{"code":"UNAUTHORIZED","message":"nope"}}""")
        Thread.sleep(300)

        // research §10: iOS treats every ok:true as "hello"; Android matches the connect id only,
        // and an ok:false with any code other than NOT_PAIRED leaves the state alone.
        assertEquals(OpenClawConnectionState.Connecting, service.connectionState.value)
        assertFalse(service.isTickRunning)
    }

    @Test
    fun buildUrlHandlesWssIpv6AndTokenEncoding() {
        service = newService()
        store.host = "gateway.local"
        store.port = 8443
        store.scheme = "wss"
        store.saveToken("a b+c")
        assertEquals("wss://gateway.local:8443/?token=a%20b%2Bc", service.buildUrl())

        store.scheme = "ws"
        store.port = 18789
        store.host = "[::1]"
        store.saveToken(null)
        assertEquals("ws://[::1]:18789/", service.buildUrl())

        store.host = "::1" // a bare IPv6 literal is bracketed for the user
        assertEquals("ws://[::1]:18789/", service.buildUrl())

        store.host = "bad host"
        assertNull(service.buildUrl())
    }

    @Test
    fun lanHttpClientBypassesProxiesAndNeverTimesOutReads() {
        val client = OpenClawNodeService.lanHttpClient()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis)
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun givesUpAfterMaxAttemptsWithMaxRetriesError() {
        gateway.start(0)
        store.port = gateway.server.port
        gateway.stop() // nothing listens on that port any more
        service = newService(reconnectDelaysMs = listOf(20L))
        service.connect()
        val state = service.connectionState.awaitValue(timeoutMs = 15_000) { it is OpenClawConnectionState.Error }
        assertEquals(OpenClawConnectionState.Error(OpenClawErrorReason.MaxRetries(5)), state)
    }

    @Test
    fun disconnectStopsReconnecting() {
        val service = startGatewayAndConnect(upgrades = 2)
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        service.disconnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Disconnected }
        Thread.sleep(400)
        assertEquals(1, gateway.opens)
        assertEquals(OpenClawConnectionState.Disconnected, service.connectionState.value)
    }

    @Test
    fun invalidHostIsReportedWithoutTouchingTheNetwork() {
        store.host = "   "
        service = newService()
        service.connect()
        assertEquals(OpenClawConnectionState.Error(OpenClawErrorReason.InvalidUrl), service.connectionState.value)
    }

    @Test
    fun savingABlankTokenDeletesIt() {
        service = newService()
        service.saveGatewayToken("   ")
        assertNull(service.loadGatewayToken())
    }
}
```

- [ ] **Step 3.4: Run and watch it fail**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.OpenClawNodeServiceTest" 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'OpenClawNodeService'`.

- [ ] **Step 3.5: Create `OpenClawNodeService.kt`**

```kotlin
package com.smartview.glassai.services.openclaw

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.BuildConfig
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * OpenClaw Gateway client (research §2). One WebSocket, JSON text frames, three envelopes
 * (req / res / event). The app is an *operator* (chat.send, chat events) and a *node*
 * (node.invoke → OpenClawCommandHandler → node.invoke.result).
 *
 * Differences from iOS that are deliberate (research §10): only the `connect` response (matched
 * by id) counts as "hello ok"; emptying the token deletes it; the token is percent-encoded; a close
 * and a failure for the same socket are counted once; the backoff wait is its own state
 * ([OpenClawConnectionState.Reconnecting]) so auto-connect cannot defeat it; `openclaw_enabled`
 * does not exist.
 *
 * Threading: OkHttp callbacks arrive on OkHttp threads; every mutable field is guarded by [lock];
 * flows are thread-safe. Nothing here touches Android UI classes so the class is JVM-testable.
 *
 * [identity] is a Lazy so the singleton can be built in Application.onCreate without reading or
 * generating the Ed25519 seed (Keystore/EncryptedSharedPreferences I/O) until the first connect().
 */
class OpenClawNodeService(
    private val store: OpenClawSettingsStore,
    private val identity: Lazy<OpenClawDeviceIdentity>,
    private val clientInfo: OpenClawClientInfo,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val reconnectDelaysMs: List<Long> = RECONNECT_DELAYS_MS,
    private val maxReconnectAttempts: Int = MAX_RECONNECT_ATTEMPTS,
    private val invokeTimeoutMs: Long = DEFAULT_INVOKE_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "OpenClawNodeService"
        const val TICK_INTERVAL_MS = 15_000L
        val RECONNECT_DELAYS_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val DEFAULT_INVOKE_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_S = 10L

        @Volatile
        private var instance: OpenClawNodeService? = null

        /**
         * Process singleton (like APIKeyManager). Cheap to build: the settings store opens
         * EncryptedSharedPreferences lazily and the Ed25519 identity is loaded/generated on the
         * first connect(), so calling this from Application.onCreate does no I/O.
         */
        fun getInstance(context: Context): OpenClawNodeService =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val store = SecureOpenClawSettingsStore(appContext)
                    OpenClawNodeService(
                        store = store,
                        identity = lazy { OpenClawDeviceIdentityStore.loadOrCreate(store) },
                        clientInfo = OpenClawClientInfo(
                            version = BuildConfig.VERSION_NAME,
                            modelIdentifier = Build.MODEL ?: "android",
                            nodeId = nodeIdFor(appContext),
                        ),
                        httpClient = lanHttpClient(),
                    ).also { instance = it }
                }
            }

        /** "rayban-" + first 8 chars of ANDROID_ID, lowercase (iOS: identifierForVendor). */
        fun nodeIdFor(context: Context): String {
            val androidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "00000000"
            return "rayban-" + androidId.take(8).lowercase()
        }

        /** LAN gateway client: no system proxy, 10 s connect, no read timeout (long-lived socket). */
        fun lanHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val gson = Gson()
    private val lock = Any()

    private var webSocket: WebSocket? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var tickJob: Job? = null
    private var reconnectJob: Job? = null
    private var pendingConnectId: String? = null

    @Volatile
    private var router: OpenClawCommandHandler? = null

    /** Resolved on first use (the first connect.challenge). */
    private val deviceIdentity: OpenClawDeviceIdentity
        get() = identity.value

    /** Test hook: whether the 15 s tick loop is alive. */
    @VisibleForTesting
    internal val isTickRunning: Boolean
        get() = synchronized(lock) { tickJob?.isActive == true }

    private val _connectionState = MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.Disconnected)
    val connectionState: StateFlow<OpenClawConnectionState> = _connectionState.asStateFlow()

    private val _chatEvents = MutableSharedFlow<OpenClawChatEvent>(extraBufferCapacity = 64)
    val chatEvents: SharedFlow<OpenClawChatEvent> = _chatEvents.asSharedFlow()

    val nodeId: String
        get() = clientInfo.nodeId

    var gatewayHost: String
        get() = store.host
        set(value) { store.host = value.trim() }

    var gatewayPort: Int
        get() = store.port
        set(value) { store.port = value }

    /** "ws" (default) or "wss". */
    var gatewayScheme: String
        get() = store.scheme
        set(value) { store.scheme = if (value == OpenClawProtocol.SCHEME_WSS) OpenClawProtocol.SCHEME_WSS else OpenClawProtocol.SCHEME_WS }

    fun loadGatewayToken(): String? = store.loadToken()

    fun saveGatewayToken(token: String?) = store.saveToken(token?.trim())

    fun setCommandRouter(router: OpenClawCommandHandler?) {
        this.router = router
    }

    // ---- lifecycle ----

    /**
     * @param force true from the Settings "Connect to Gateway" button: cancels a running backoff
     *   and dials immediately. false (default) from the Home/chat auto-connect: a backoff in
     *   progress is left alone so the 2/4/8/16/30 s sequence and the 5-attempt cap stay intact.
     */
    fun connect(force: Boolean = false) {
        synchronized(lock) {
            val state = _connectionState.value
            if (state == OpenClawConnectionState.Connected || state == OpenClawConnectionState.Connecting) {
                Log.d(TAG, "connect(): already $state")
                return
            }
            if (state is OpenClawConnectionState.Reconnecting && !force && reconnectJob?.isActive == true) {
                Log.d(TAG, "connect(): backoff for attempt ${state.attempt} in progress")
                return
            }
            shouldReconnect = true
            reconnectAttempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
            startConnection()
        }
    }

    fun disconnect() {
        synchronized(lock) {
            shouldReconnect = false
            reconnectJob?.cancel()
            reconnectJob = null
            tickJob?.cancel()
            tickJob = null
            pendingConnectId = null
            val socket = webSocket
            webSocket = null
            runCatching { socket?.close(1000, "User disconnected") }
            _connectionState.value = OpenClawConnectionState.Disconnected
        }
    }

    /** Must be called with [lock] held. */
    private fun startConnection() {
        // A socket left open by a NOT_PAIRED wait (or any stale one) is closed before dialing
        // again, so the gateway never sees two connections from this device. Its later callbacks
        // are ignored by handleDisconnect (socket !== webSocket).
        webSocket?.let { old ->
            webSocket = null
            runCatching { old.close(1000, "reconnect") }
        }
        val url = buildUrl()
        // Request.Builder().url() throws IllegalArgumentException for anything OkHttp cannot parse;
        // that must become the InvalidUrl state, never an exception in a Compose click handler.
        val request = url?.let { runCatching { Request.Builder().url(it).build() }.getOrNull() }
        if (request == null) {
            Log.e(TAG, "invalid gateway address: '${store.host}:${store.port}'")
            shouldReconnect = false
            _connectionState.value = OpenClawConnectionState.Error(OpenClawErrorReason.InvalidUrl)
            return
        }
        Log.d(TAG, "connecting to ${store.scheme}://${store.host}:${store.port}")
        _connectionState.value = OpenClawConnectionState.Connecting
        pendingConnectId = null
        webSocket = httpClient.newWebSocket(request, Listener())
    }

    /**
     * `ws(s)://host:port/` plus `?token=<percent-encoded>` when a token is stored; null if the
     * address is malformed. Built through OkHttp's HttpUrl so the token is RFC 3986 percent-encoded
     * (a space is `%20`, not `+` — java.net.URLEncoder is form encoding) and IPv6 literals are
     * validated; a bare `::1` is bracketed for the user. Uri.encode is unavailable on the JVM.
     */
    @VisibleForTesting
    internal fun buildUrl(): String? {
        val rawHost = store.host.trim()
        val port = store.port
        if (rawHost.isEmpty() || rawHost.any { it.isWhitespace() } || port !in 1..65535) return null
        val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
        val httpScheme = if (store.scheme == OpenClawProtocol.SCHEME_WSS) "https" else "http"
        val base = "$httpScheme://$host:$port/".toHttpUrlOrNull() ?: return null
        val token = store.loadToken()?.takeIf { it.isNotBlank() }
        val url = if (token != null) base.newBuilder().addQueryParameter("token", token).build() else base
        // OkHttp accepts ws/wss request URLs and maps them back to http/https internally.
        return url.toString().replaceFirst("http", "ws")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "socket open; waiting for connect.challenge")
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(webSocket, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(webSocket, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "socket closed: $code $reason")
            handleDisconnect(webSocket, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "socket failure: ${t.message}")
            handleDisconnect(webSocket, t)
        }
    }

    private fun handleDisconnect(socket: WebSocket, error: Throwable?) {
        synchronized(lock) {
            // A closed and a failed callback for the same socket, or callbacks from a socket we
            // already replaced, must not be counted twice.
            if (socket !== webSocket) return
            webSocket = null
            tickJob?.cancel()
            tickJob = null
            pendingConnectId = null

            if (!shouldReconnect) {
                _connectionState.value = OpenClawConnectionState.Disconnected
                return
            }
            reconnectAttempts++
            if (reconnectAttempts > maxReconnectAttempts) {
                Log.e(TAG, "giving up after $maxReconnectAttempts reconnect attempts")
                shouldReconnect = false
                _connectionState.value = OpenClawConnectionState.Error(OpenClawErrorReason.MaxRetries(maxReconnectAttempts))
                return
            }
            val delayMs = reconnectDelaysMs[minOf(reconnectAttempts - 1, reconnectDelaysMs.size - 1)]
            Log.w(TAG, "reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$maxReconnectAttempts)" +
                (error?.let { ", cause: ${it.message}" } ?: ""))
            _connectionState.value = OpenClawConnectionState.Reconnecting(reconnectAttempts)
            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(lock) {
                    if (shouldReconnect && webSocket == null) startConnection()
                }
            }
        }
    }

    // ---- inbound ----

    private fun handleMessage(socket: WebSocket, text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "ignoring non-JSON frame: ${e.message}")
            return
        }
        val type = json.string("type") ?: return
        when {
            type == "event" && json.string("event") == "connect.challenge" -> {
                val nonce = json.obj("payload")?.string("nonce") ?: ""
                sendConnect(socket, nonce)
            }
            type == "res" -> handleResponse(json)
            type == "evt" || type == "event" -> handleEvent(json.string("event") ?: json.string("method") ?: "", json)
            type == "req" || type == "request" -> handleRequest(socket, json)
            else -> Log.d(TAG, "unknown message type: $type")
        }
    }

    private fun sendConnect(socket: WebSocket, nonce: String) {
        val id = UUID.randomUUID().toString()
        val signedAt = clock()
        val token = store.loadToken()?.takeIf { it.isNotBlank() }
        val signer = deviceIdentity // first use loads or generates the seed
        val signature = signer.signConnect(
            clientId = OpenClawProtocol.CLIENT_ID,
            clientMode = OpenClawProtocol.CLIENT_MODE,
            role = OpenClawProtocol.ROLE,
            scopes = OpenClawProtocol.SCOPES,
            signedAtMs = signedAt,
            token = token,
            nonce = nonce,
            platform = OpenClawProtocol.PLATFORM,
            deviceFamily = null,
        )
        val params = JsonObject().apply {
            addProperty("minProtocol", OpenClawProtocol.PROTOCOL_VERSION)
            addProperty("maxProtocol", OpenClawProtocol.PROTOCOL_VERSION)
            add("client", JsonObject().apply {
                addProperty("id", OpenClawProtocol.CLIENT_ID)
                addProperty("displayName", OpenClawProtocol.DISPLAY_NAME)
                addProperty("version", clientInfo.version)
                addProperty("mode", OpenClawProtocol.CLIENT_MODE)
                addProperty("platform", OpenClawProtocol.PLATFORM)
                addProperty("modelIdentifier", clientInfo.modelIdentifier)
            })
            addProperty("role", OpenClawProtocol.ROLE)
            add("scopes", jsonArray(OpenClawProtocol.SCOPES))
            add("caps", jsonArray(OpenClawProtocol.CAPS))
            add("commands", jsonArray(OpenClawProtocol.COMMANDS))
            add("auth", JsonObject().apply { if (token != null) addProperty("token", token) })
            add("device", JsonObject().apply {
                addProperty("id", signer.deviceId)
                addProperty("publicKey", signer.publicKeyBase64Url)
                addProperty("signature", signature)
                addProperty("signedAt", signedAt)
                addProperty("nonce", nonce)
            })
        }
        synchronized(lock) { pendingConnectId = id }
        send(socket, request(id, "connect", params))
    }

    private fun handleResponse(json: JsonObject) {
        val id = json.string("id")
        val ok = json.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (ok) {
            val isHello = synchronized(lock) { id != null && id == pendingConnectId }
            if (isHello) handleHelloOk() else Log.d(TAG, "ok response for $id")
            return
        }
        val error = json.obj("error")
        val code = error?.string("code")
        Log.w(TAG, "error response for $id: $code ${error?.string("message")}")
        if (code == OpenClawProtocol.ERROR_NOT_PAIRED) {
            synchronized(lock) { _connectionState.value = OpenClawConnectionState.WaitingForPairing }
        }
    }

    private fun handleHelloOk() {
        synchronized(lock) {
            Log.d(TAG, "connected to gateway")
            reconnectAttempts = 0
            pendingConnectId = null
            _connectionState.value = OpenClawConnectionState.Connected
            startTick()
        }
    }

    /** Must be called with [lock] held. */
    private fun startTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMs)
                val params = JsonObject().apply { addProperty("ts", clock()) }
                sendJson(request(UUID.randomUUID().toString(), "tick", params))
            }
        }
    }

    private fun handleEvent(name: String, json: JsonObject) {
        when (name) {
            "chat" -> {
                val payload = json.obj("payload") ?: return
                val state = payload.string("state")
                val content = payload.obj("message")?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                val text = content?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("text") }
                    ?.joinToString("") ?: ""
                _chatEvents.tryEmit(OpenClawChatEvent(text, isFinal = state == "final"))
            }
            "node.invoke.request", "node.invoke" -> {
                // The gateway puts the invoke in `params` (iOS reads json["params"]); accept
                // `payload` as a fallback.
                val params = json.obj("params") ?: json.obj("payload")
                val request = parseInvoke(params) // event form: the invoke id is params.id
                if (request == null) {
                    Log.w(TAG, "malformed invoke event: $json")
                    return
                }
                dispatchInvoke(request)
            }
            "tick", "health" -> Unit
            else -> Log.d(TAG, "unhandled event: $name")
        }
    }

    private fun handleRequest(socket: WebSocket, json: JsonObject) {
        val id = json.string("id") ?: ""
        val method = json.string("method") ?: ""
        if (method == "node.invoke") {
            // iOS: for `req` frames the invoke id IS the frame id (research §2.5); a params.id that
            // a gateway might also send is ignored so node.invoke.result carries what iOS would.
            val request = parseInvoke(json.obj("params"), forcedId = id.takeIf { it.isNotEmpty() })
            if (request == null) {
                send(socket, errorResponse(id, OpenClawProtocol.ERROR_UNSUPPORTED, "Malformed node.invoke"))
                return
            }
            dispatchInvoke(request)
            return
        }
        send(socket, errorResponse(id, OpenClawProtocol.ERROR_UNSUPPORTED, "Unknown method: $method"))
    }

    /** @param forcedId the frame id for `req` frames (authoritative); null for the event form. */
    private fun parseInvoke(params: JsonObject?, forcedId: String? = null): OpenClawNodeInvokeRequest? {
        if (params == null) return null
        val id = forcedId ?: params.string("id") ?: return null
        val command = params.string("command") ?: return null
        val commandParams: JsonObject? = params.obj("params") ?: params.string("paramsjson")?.let { raw ->
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
        val timeout = (params.get("timeoutMs") ?: params.get("timeoutms"))
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }
        return OpenClawNodeInvokeRequest(id = id, command = command, params = commandParams, timeoutMs = timeout)
    }

    private fun dispatchInvoke(request: OpenClawNodeInvokeRequest) {
        val handler = router
        if (handler == null) {
            sendInvokeResult(OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_NO_ROUTER, "No command router installed"))
            return
        }
        scope.launch {
            val budget = request.timeoutMs?.takeIf { it > 0 } ?: invokeTimeoutMs
            val result = withTimeoutOrNull(budget) {
                try {
                    handler.handleCommand(request)
                } catch (e: Exception) {
                    Log.e(TAG, "command ${request.command} threw: ${e.message}")
                    OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_INTERNAL, e.message ?: "internal error")
                }
            } ?: OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_TIMEOUT, "Command timed out after ${budget}ms")
            sendInvokeResult(result)
        }
    }

    // ---- outbound ----

    /** `chat.send`; [imageJpegBase64] is a base64 (no line breaks) JPEG attachment. */
    fun sendChatMessage(text: String, imageJpegBase64: String? = null): Boolean {
        val params = JsonObject().apply {
            addProperty("sessionKey", OpenClawProtocol.SESSION_KEY)
            addProperty("message", text)
            addProperty("idempotencyKey", UUID.randomUUID().toString())
            if (imageJpegBase64 != null) {
                add("attachments", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "image")
                        addProperty("mimeType", "image/jpeg")
                        addProperty("content", imageJpegBase64)
                    })
                })
            }
        }
        return sendJson(request(UUID.randomUUID().toString(), "chat.send", params))
    }

    private fun sendInvokeResult(result: OpenClawNodeInvokeResult) {
        val params = JsonObject().apply {
            addProperty("id", result.id)
            addProperty("nodeId", clientInfo.nodeId)
            addProperty("ok", result.ok)
            // Large payloads (JPEG) travel as a JSON *string* — identical to iOS.
            result.payload?.let { addProperty("payloadjson", gson.toJson(it)) }
            result.error?.let { error ->
                add("error", JsonObject().apply {
                    error.code?.let { addProperty("code", it) }
                    error.message?.let { addProperty("message", it) }
                })
            }
        }
        sendJson(request(UUID.randomUUID().toString(), "node.invoke.result", params))
    }

    private fun request(id: String, method: String, params: JsonObject): JsonObject = JsonObject().apply {
        addProperty("type", "req")
        addProperty("id", id)
        addProperty("method", method)
        add("params", params)
    }

    private fun errorResponse(id: String, code: String, message: String): JsonObject = JsonObject().apply {
        addProperty("type", "res")
        addProperty("id", id)
        addProperty("ok", false)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    private fun sendJson(json: JsonObject): Boolean {
        val socket = synchronized(lock) { webSocket } ?: return false
        return send(socket, json)
    }

    private fun send(socket: WebSocket, json: JsonObject): Boolean = socket.send(gson.toJson(json))

    private fun jsonArray(values: List<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
```

- [ ] **Step 3.6: Run the service tests**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.OpenClawNodeServiceTest" 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`, at least 26 tests pass. Timing notes: `givesUpAfterMaxAttemptsWithMaxRetriesError` needs ~5 × (connect refusal + 20 ms) and must finish well inside its 15 s budget; `reconnectsAfterTheGatewayClosesTheSocket` / `reconnectAttemptsResetAfterASuccessfulHello` rely on the queued upgrades; `connectDuringBackoffDoesNotDialEarlyButForceDoes` deliberately uses a 3 s backoff so the 500 ms observation window cannot race the timer.

If `handshakeSendsTheIosConnectFrameAndBecomesConnected` fails on `request.path` (`"/?token=secret-token"`), print the actual path in the assertion message and adjust only the test expectation if OkHttp normalizes the root path differently (the token query must still be present).

- [ ] **Step 3.7: Build and commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:assembleDebug 2>&1 | tail -10
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/src/main/java/com/smartview/glassai/services/openclaw android/app/src/test/java/com/smartview/glassai/services/openclaw && git commit -m "feat(android): OpenClawNodeService — gateway WebSocket client with iOS-identical handshake, chat.send, node.invoke routing, tick and backoff reconnect (MockWebServer tests)"
```

---

### Task 4: `GlassesFrameProvider` (manager `latestFrame` + capturer fallback + foreground gate) and `OpenClawCommandRouter`

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/glasses/GlassesFrameProvider.kt`
- Modify: `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouter.kt` (append the router class after `interface OpenClawCommandHandler`)
- Create: `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawIntegration.kt`
- Modify: `app/build.gradle.kts` (anchor: `manifestPlaceholders["mwdat_client_token"] =` block end, inside `defaultConfig`)
- Modify: `app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` (full replacement)
- Create: `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouterTest.kt`, `app/src/test/java/com/smartview/glassai/glasses/SessionFrameProviderTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class FrameSnapshot(val jpeg: ByteArray, val width: Int, val height: Int)
sealed class SnapshotResult { data class Ok(val frame: FrameSnapshot); data class NotReady(val detail: String); data class StreamFailed(val detail: String); object NoFrame; object PermissionRequired; object EncodeFailed }
interface GlassesFrameProvider { val hasActiveDevice: Boolean; val isStreaming: Boolean; val streamStatus: String; val hasFrame: Boolean; suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult }
class SessionFrameProvider(sessionManager: () -> GlassesSessionManager, isForeground: () -> Boolean, checkPermission: suspend () -> CameraPermissionCheck, encode: (Bitmap, Int, Double) -> FrameSnapshot?, capture: suspend (GlassesSessionManager) -> PhotoCaptureOutcome<Bitmap>, encodeDispatcher: CoroutineDispatcher = Dispatchers.Default) : GlassesFrameProvider { companion { fun create(app: Application): SessionFrameProvider; fun encodeBitmap(bitmap: Bitmap, maxWidth: Int, quality: Double): FrameSnapshot?; const val OWNER = "OpenClawSnap" } }
data class OpenClawDeviceInfoSource(val appVersion: String, val sdkVersion: String, val osVersion: String) { companion fun fromBuild() }
class OpenClawCommandRouter(frames: GlassesFrameProvider, deviceInfo: OpenClawDeviceInfoSource, snapTimeoutMs: Long = 5_000) : OpenClawCommandHandler
object OpenClawIntegration { fun install(app: Application) }
```
- Produces `BuildConfig.MWDAT_VERSION` (from `libs.versions.toml` `mwdat`), `TurboMetaApplication.isInForeground`
- Consumes: `GlassesSessionManager.latestFrame/currentCameraOwner/activeDevice`, `GlassesPhotoCapturer`, `WearablesRegistrationGateway.checkCameraPermission()`, `OpenClawNodeService.setCommandRouter`

- [ ] **Step 4.1: Write the failing router test**

Create `app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouterTest.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

import com.google.gson.JsonParser
import com.smartview.glassai.glasses.FrameSnapshot
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.SnapshotResult
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawCommandRouterTest {

    private class FakeFrameProvider : GlassesFrameProvider {
        override var hasActiveDevice = true
        override var streamStatus = "stopped"
        override var hasFrame = false
        override val isStreaming: Boolean get() = streamStatus != "stopped"
        var result: SnapshotResult = SnapshotResult.NoFrame
        var lastMaxWidth = -1
        var lastQuality = -1.0
        var lastTimeout = -1L
        override suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult {
            lastMaxWidth = maxWidth
            lastQuality = quality
            lastTimeout = timeoutMs
            return result
        }
    }

    private val frames = FakeFrameProvider()
    private val info = OpenClawDeviceInfoSource(appVersion = "2.0.0", sdkVersion = "0.9.0", osVersion = "12")
    private val router = OpenClawCommandRouter(frames, info, snapTimeoutMs = 1234L)

    private fun request(command: String, params: String? = null) = OpenClawNodeInvokeRequest(
        id = "inv-1",
        command = command,
        params = params?.let { JsonParser.parseString(it).asJsonObject },
        timeoutMs = null,
    )

    @Test
    fun snapReturnsJpegBase64WithDimensions() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        frames.result = SnapshotResult.Ok(FrameSnapshot(jpeg, 640, 480))

        val result = router.handleCommand(request("camera.snap", """{"maxWidth": 800, "quality": 0.5}"""))

        assertTrue(result.ok)
        assertEquals("inv-1", result.id)
        val payload = result.payload!!
        assertEquals("jpg", payload.get("format").asString)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), payload.get("base64").asString)
        assertEquals(640, payload.get("width").asInt)
        assertEquals(480, payload.get("height").asInt)
        assertEquals(800, frames.lastMaxWidth)
        assertEquals(0.5, frames.lastQuality, 0.0001)
        assertEquals(1234L, frames.lastTimeout)
    }

    @Test
    fun snapUsesDefaultsAndClampsQuality() = runTest {
        frames.result = SnapshotResult.Ok(FrameSnapshot(byteArrayOf(1), 1, 1))
        router.handleCommand(request("camera.snap", """{"quality": 7}"""))
        assertEquals(1600, frames.lastMaxWidth)
        assertEquals(1.0, frames.lastQuality, 0.0001)
        router.handleCommand(request("camera.snap", """{"quality": 0}"""))
        assertEquals(0.1, frames.lastQuality, 0.0001)
    }

    @Test
    fun snapErrorsMapToTheIosCodes() = runTest {
        frames.result = SnapshotResult.NoFrame
        assertEquals("NO_FRAME", router.handleCommand(request("camera.snap")).error!!.code)
        frames.result = SnapshotResult.NotReady("background")
        val notReady = router.handleCommand(request("camera.snap"))
        assertEquals("NOT_READY", notReady.error!!.code)
        assertEquals("Stream not initialized", notReady.error!!.message) // iOS text; the detail goes to logcat
        frames.result = SnapshotResult.StreamFailed("x")
        val streamFailed = router.handleCommand(request("camera.snap"))
        assertEquals("STREAM_FAILED", streamFailed.error!!.code)
        assertEquals("Could not start camera stream", streamFailed.error!!.message)
        frames.result = SnapshotResult.PermissionRequired
        assertEquals("PERMISSION_REQUIRED", router.handleCommand(request("camera.snap")).error!!.code)
        frames.result = SnapshotResult.EncodeFailed
        val encode = router.handleCommand(request("camera.snap"))
        assertEquals("ENCODE_FAILED", encode.error!!.code)
        assertEquals("Failed to encode JPEG", encode.error!!.message)
        assertFalse(encode.ok)
        assertNull(encode.payload)
    }

    @Test
    fun cameraListDependsOnActiveDevice() = runTest {
        frames.hasActiveDevice = true
        val withDevice = router.handleCommand(request("camera.list")).payload!!.getAsJsonArray("cameras")
        val camera = withDevice.single().asJsonObject
        assertEquals("rayban-main", camera.get("id").asString)
        assertEquals("Ray-Ban Meta Camera", camera.get("name").asString)
        assertEquals("front", camera.get("facing").asString)
        assertTrue(camera.get("available").asBoolean)

        frames.hasActiveDevice = false
        assertEquals(0, router.handleCommand(request("camera.list")).payload!!.getAsJsonArray("cameras").size())
    }

    @Test
    fun deviceStatusReportsTheProviderFlags() = runTest {
        frames.hasActiveDevice = true
        frames.streamStatus = "streaming"
        frames.hasFrame = true
        val payload = router.handleCommand(request("device.status")).payload!!
        assertTrue(payload.get("deviceConnected").asBoolean)
        assertTrue(payload.get("isStreaming").asBoolean)
        assertEquals("streaming", payload.get("streamStatus").asString)
        assertTrue(payload.get("hasVideoFrame").asBoolean)
    }

    @Test
    fun deviceInfoUsesBuildFacts() = runTest {
        val payload = router.handleCommand(request("device.info")).payload!!
        assertEquals("Ray-Ban Meta", payload.get("deviceType").asString)
        assertEquals("TurboMeta", payload.get("appName").asString)
        assertEquals("2.0.0", payload.get("appVersion").asString)
        assertEquals("0.9.0", payload.get("sdkVersion").asString)
        assertEquals("Android", payload.get("platform").asString)
        assertEquals("12", payload.get("osVersion").asString)
    }

    @Test
    fun unknownCommandIsRejected() = runTest {
        val result = router.handleCommand(request("camera.clip"))
        assertFalse(result.ok)
        assertEquals("UNKNOWN_COMMAND", result.error!!.code)
        assertEquals("Unknown command: camera.clip", result.error!!.message)
    }
}
```

- [ ] **Step 4.2: Write the failing `SessionFrameProviderTest`**

Create `app/src/test/java/com/smartview/glassai/glasses/SessionFrameProviderTest.kt`:

```kotlin
package com.smartview.glassai.glasses

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFrameProviderTest {

    private val factory = FakeDatSessionFactory()
    private val observer = FakeDatDeviceObserver()
    private val config = StreamConfiguration()
    private val rayban = GlassesDeviceInfo("dev-1", "Ray-Ban Meta", DeviceType.RAYBAN_META, false, DeviceCompatibility.COMPATIBLE)

    private var foreground = true
    private var permission: CameraPermissionCheck = CameraPermissionCheck.Granted
    private var captureOutcome: PhotoCaptureOutcome<Bitmap> = PhotoCaptureOutcome.NoImage
    private var captureCalls = 0
    private val encoded = mutableListOf<Bitmap>()

    private fun TestScope.newManager(): GlassesSessionManager =
        GlassesSessionManager(factory, observer, backgroundScope).also { it.startMonitoring() }

    private fun provider(manager: GlassesSessionManager) = SessionFrameProvider(
        sessionManager = { manager },
        isForeground = { foreground },
        checkPermission = { permission },
        encode = { bitmap, maxWidth, _ ->
            encoded += bitmap
            FrameSnapshot(byteArrayOf(maxWidth.toByte()), maxWidth, maxWidth)
        },
        capture = { captureCalls++; captureOutcome },
        encodeDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun usesTheLatestFrameWhenAnOwnerStreams() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        observer.device.value = rayban
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        val frame = TestBitmaps.stub()
        manager.publishFrame("A", frame)

        val result = provider(manager).snapshot(640, 0.8, 1_000)

        assertTrue(result is SnapshotResult.Ok)
        assertEquals(640, (result as SnapshotResult.Ok).frame.width)
        assertEquals(listOf(frame), encoded)
        assertEquals(0, captureCalls)
        assertEquals("streaming", provider(manager).streamStatus)
        assertTrue(provider(manager).hasFrame)
    }

    @Test
    fun backgroundAppIsNotReady() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        foreground = false
        val result = provider(manager).snapshot(640, 0.8, 1_000)
        assertTrue(result is SnapshotResult.NotReady)
        assertEquals(0, captureCalls)
    }

    @Test
    fun ownerWithoutFrameYetWaitsThenNoFrame() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        observer.device.value = rayban
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)

        val result = provider(manager).snapshot(640, 0.8, 200)

        assertEquals(SnapshotResult.NoFrame, result)
        assertEquals(0, captureCalls)
        assertEquals("waiting", provider(manager).streamStatus)
    }

    @Test
    fun noOwnerFallsBackToTheCapturer() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        observer.device.value = rayban
        val photo = TestBitmaps.stub()
        captureOutcome = PhotoCaptureOutcome.Captured(photo, fromVideoFrame = false)

        val result = provider(manager).snapshot(320, 0.6, 1_000)

        assertTrue(result is SnapshotResult.Ok)
        assertEquals(1, captureCalls)
        assertEquals(listOf(photo), encoded)
        assertEquals("stopped", provider(manager).streamStatus)
        assertFalse(provider(manager).isStreaming)
    }

    @Test
    fun deniedPermissionShortCircuitsBeforeCapture() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        permission = CameraPermissionCheck.Denied
        assertEquals(SnapshotResult.PermissionRequired, provider(manager).snapshot(640, 0.8, 100))
        assertEquals(0, captureCalls)
    }

    @Test
    fun capturerOutcomesMapToSnapshotResults() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        captureOutcome = PhotoCaptureOutcome.NoDevice
        assertTrue(provider(manager).snapshot(640, 0.8, 100) is SnapshotResult.NotReady)
        captureOutcome = PhotoCaptureOutcome.NoImage
        assertEquals(SnapshotResult.NoFrame, provider(manager).snapshot(640, 0.8, 100))
        captureOutcome = PhotoCaptureOutcome.StreamTimeout
        assertTrue(provider(manager).snapshot(640, 0.8, 100) is SnapshotResult.StreamFailed)
        captureOutcome = PhotoCaptureOutcome.SessionFailed
        assertTrue(provider(manager).snapshot(640, 0.8, 100) is SnapshotResult.StreamFailed)
        captureOutcome = PhotoCaptureOutcome.CameraUnavailable(CameraError.CameraBusy("X"))
        assertEquals(SnapshotResult.NoFrame, provider(manager).snapshot(640, 0.8, 100))
    }

    @Test
    fun encoderFailureIsEncodeFailed() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        val failing = SessionFrameProvider(
            sessionManager = { manager },
            isForeground = { true },
            checkPermission = { CameraPermissionCheck.Granted },
            encode = { _, _, _ -> null },
            capture = { PhotoCaptureOutcome.Captured(TestBitmaps.stub(), fromVideoFrame = true) },
            encodeDispatcher = UnconfinedTestDispatcher(),
        )
        assertEquals(SnapshotResult.EncodeFailed, failing.snapshot(640, 0.8, 100))
    }
}
```

- [ ] **Step 4.3: Run and watch them fail**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.OpenClawCommandRouterTest" --tests "com.smartview.glassai.glasses.SessionFrameProviderTest" 2>&1 | tail -20
```
Expected: `BUILD FAILED`, unresolved `GlassesFrameProvider`, `SessionFrameProvider`, `OpenClawCommandRouter`, `OpenClawDeviceInfoSource`.

- [ ] **Step 4.4: Create `GlassesFrameProvider.kt`**

```kotlin
package com.smartview.glassai.glasses

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.smartview.glassai.TurboMetaApplication
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A JPEG-encoded glasses frame ready for base64. */
data class FrameSnapshot(val jpeg: ByteArray, val width: Int, val height: Int)

sealed class SnapshotResult {
    data class Ok(val frame: FrameSnapshot) : SnapshotResult()
    /** No glasses connected, app in background, permission check failed, … — see [detail]. */
    data class NotReady(val detail: String) : SnapshotResult()
    /** The camera stream could not be started; see [detail]. */
    data class StreamFailed(val detail: String) : SnapshotResult()
    /** A stream exists but no decodable frame arrived within the budget. */
    object NoFrame : SnapshotResult()
    /** The wearable CAMERA permission was denied; only an Activity can request it. */
    object PermissionRequired : SnapshotResult()
    object EncodeFailed : SnapshotResult()
}

/**
 * What OpenClawCommandRouter needs from the glasses (spec §6 B1). Implemented by
 * [SessionFrameProvider] in the app and by fakes in tests.
 */
interface GlassesFrameProvider {
    val hasActiveDevice: Boolean
    /** iOS parity: true while "streaming" or "waiting". */
    val isStreaming: Boolean
    /** "streaming" | "waiting" | "stopped". */
    val streamStatus: String
    val hasFrame: Boolean

    /** Scales to [maxWidth] (if wider), JPEG at [quality] (0.1..1.0), within [timeoutMs]. */
    suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult
}

/**
 * Frame source for OpenClaw (Phase A final review, Recommendation 2, design (b)):
 * 1. If a feature currently borrows the camera, the last frame it published to
 *    GlassesSessionManager.latestFrame is used (a snap during Live AI returns the live frame).
 * 2. If it borrows the camera but has not published yet, wait up to [timeoutMs] for a frame.
 * 3. If nobody holds the camera, borrow it briefly through GlassesPhotoCapturer (owner
 *    "OpenClawSnap"), which stops the camera and releases the claim before returning.
 * Never runs when no Activity is started (spec §1: no background camera.snap).
 *
 * Known limit (documented in android/README.md): QuickVisionService borrows the camera for a few
 * seconds per wake-word capture and never publishes to latestFrame. A camera.snap that lands in
 * that window gets CameraBusy from the capturer, waits [timeoutMs] on latestFrame, and answers
 * NO_FRAME; the gateway simply retries. Live AI / Live Stream / RTMP do publish, so snaps during
 * those return the live frame.
 *
 * [sessionManager] is a provider so installing this at app start does not create the manager
 * before the Bluetooth runtime permissions are granted.
 */
class SessionFrameProvider(
    private val sessionManager: () -> GlassesSessionManager,
    private val isForeground: () -> Boolean,
    private val checkPermission: suspend () -> CameraPermissionCheck,
    private val encode: (Bitmap, Int, Double) -> FrameSnapshot?,
    private val capture: suspend (GlassesSessionManager) -> PhotoCaptureOutcome<Bitmap>,
    private val encodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GlassesFrameProvider {

    companion object {
        private const val TAG = "SessionFrameProvider"
        const val OWNER = "OpenClawSnap"

        fun create(app: Application): SessionFrameProvider {
            val registration = WearablesRegistrationGateway(app)
            return SessionFrameProvider(
                sessionManager = { GlassesSessionManager.getInstance(app) },
                isForeground = { (app as? TurboMetaApplication)?.isInForeground ?: true },
                checkPermission = { registration.checkCameraPermission() },
                encode = ::encodeBitmap,
                capture = { manager ->
                    val capturer = GlassesPhotoCapturer(
                        sessionManager = manager,
                        owner = OWNER,
                        config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
                        decodePhoto = FrameConversions::decodePhoto,
                        decodeFrame = { FrameConversions.frameToBitmap(it, FrameConversions.CAPTURE_JPEG_QUALITY) },
                    )
                    // GlassesSessionManager contract: capture() must run on the main thread.
                    withContext(Dispatchers.Main.immediate) { capturer.capture() }
                },
            )
        }

        /** Downscale to [maxWidth] if wider, JPEG at quality*100. Never call on the main thread. */
        fun encodeBitmap(bitmap: Bitmap, maxWidth: Int, quality: Double): FrameSnapshot? = try {
            val scaled = if (maxWidth > 0 && bitmap.width > maxWidth) {
                val height = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
            } else {
                bitmap
            }
            val jpeg = ByteArrayOutputStream().use { stream ->
                val ok = scaled.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt().coerceIn(10, 100), stream)
                if (!ok) return null
                stream.toByteArray()
            }
            FrameSnapshot(jpeg, scaled.width, scaled.height)
        } catch (e: Exception) {
            Log.e(TAG, "encode failed: ${e.message}")
            null
        }
    }

    override val hasActiveDevice: Boolean
        get() = sessionManager().activeDevice.value != null

    override val hasFrame: Boolean
        get() = sessionManager().latestFrame.value != null

    override val streamStatus: String
        get() {
            val manager = sessionManager()
            return when {
                manager.latestFrame.value != null -> "streaming"
                manager.currentCameraOwner != null -> "waiting"
                else -> "stopped"
            }
        }

    override val isStreaming: Boolean
        get() = streamStatus != "stopped"

    override suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult {
        if (!isForeground()) return SnapshotResult.NotReady("App is in background")
        val manager = sessionManager()

        manager.latestFrame.value?.let { return encodeOrFail(it, maxWidth, quality) }

        if (manager.currentCameraOwner != null) {
            // Someone streams but has not decoded a frame yet: wait for the first one.
            val frame = withTimeoutOrNull(timeoutMs) { manager.latestFrame.first { it != null } }
            return if (frame != null) encodeOrFail(frame, maxWidth, quality) else SnapshotResult.NoFrame
        }

        when (val permission = checkPermission()) {
            CameraPermissionCheck.Denied -> return SnapshotResult.PermissionRequired
            is CameraPermissionCheck.Failed -> return SnapshotResult.NotReady(permission.description)
            CameraPermissionCheck.Granted -> Unit
        }

        return when (val outcome = capture(manager)) {
            is PhotoCaptureOutcome.Captured -> encodeOrFail(outcome.image, maxWidth, quality)
            PhotoCaptureOutcome.NoDevice -> SnapshotResult.NotReady("No glasses connected")
            PhotoCaptureOutcome.SessionFailed -> SnapshotResult.StreamFailed("Could not start the glasses session")
            PhotoCaptureOutcome.SessionTimeout -> SnapshotResult.StreamFailed("Glasses session did not start in time")
            is PhotoCaptureOutcome.CameraUnavailable -> {
                if (outcome.error is CameraError.CameraBusy) {
                    // Another owner grabbed the camera while we waited: use its next frame.
                    val frame = withTimeoutOrNull(timeoutMs) { manager.latestFrame.first { it != null } }
                    if (frame != null) encodeOrFail(frame, maxWidth, quality) else SnapshotResult.NoFrame
                } else {
                    SnapshotResult.StreamFailed("Camera unavailable: ${outcome.error}")
                }
            }
            is PhotoCaptureOutcome.StreamStartFailed -> SnapshotResult.StreamFailed(outcome.error.description)
            PhotoCaptureOutcome.StreamTimeout -> SnapshotResult.StreamFailed("Stream did not start in time")
            PhotoCaptureOutcome.NoImage -> SnapshotResult.NoFrame
        }
    }

    private suspend fun encodeOrFail(bitmap: Bitmap, maxWidth: Int, quality: Double): SnapshotResult {
        val snapshot = withContext(encodeDispatcher) { encode(bitmap, maxWidth, quality) }
        return if (snapshot != null) SnapshotResult.Ok(snapshot) else SnapshotResult.EncodeFailed
    }
}
```

(Task 7 adds `PhotoCaptureOutcome.Timeout`; its step updates this `when` with `PhotoCaptureOutcome.Timeout -> SnapshotResult.StreamFailed("Capture timed out")`.)

(The tests pass `encodeDispatcher = UnconfinedTestDispatcher()` so `withContext(encodeDispatcher)` never leaves the test scheduler.)

- [ ] **Step 4.5: Append `OpenClawDeviceInfoSource` and `OpenClawCommandRouter` to `OpenClawCommandRouter.kt`**

Append after `interface OpenClawCommandHandler { … }`:

```kotlin
/** Facts for `device.info`. Built once from BuildConfig/Build; tests pass literals. */
data class OpenClawDeviceInfoSource(
    val appVersion: String,
    val sdkVersion: String,
    val osVersion: String,
) {
    companion object {
        fun fromBuild(): OpenClawDeviceInfoSource = OpenClawDeviceInfoSource(
            appVersion = com.smartview.glassai.BuildConfig.VERSION_NAME,
            sdkVersion = com.smartview.glassai.BuildConfig.MWDAT_VERSION,
            osVersion = android.os.Build.VERSION.RELEASE ?: "unknown",
        )
    }
}

/**
 * Handles the four node commands the app advertises (research §3), using [frames] for the glasses
 * and [deviceInfo] for build facts. Result payload shapes are identical to iOS.
 */
class OpenClawCommandRouter(
    private val frames: com.smartview.glassai.glasses.GlassesFrameProvider,
    private val deviceInfo: OpenClawDeviceInfoSource,
    private val snapTimeoutMs: Long = DEFAULT_SNAP_TIMEOUT_MS,
) : OpenClawCommandHandler {

    companion object {
        private const val TAG = "OpenClawCommandRouter"
        /** iOS polls isStreaming for up to 5 s before giving up with NO_FRAME. */
        const val DEFAULT_SNAP_TIMEOUT_MS = 5_000L
    }

    override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
        android.util.Log.d(TAG, "command ${request.command} (${request.id})")
        return when (request.command) {
            "camera.snap" -> snap(request)
            "camera.list" -> cameraList(request)
            "device.status" -> deviceStatus(request)
            "device.info" -> deviceInfo(request)
            else -> OpenClawNodeInvokeResult.failure(
                request.id, OpenClawProtocol.ERROR_UNKNOWN_COMMAND, "Unknown command: ${request.command}",
            )
        }
    }

    private suspend fun snap(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
        val params = CameraSnapParams.from(request.params)
        val quality = params.quality.coerceIn(0.1, 1.0)
        return when (val result = frames.snapshot(params.maxWidth, quality, snapTimeoutMs)) {
            is com.smartview.glassai.glasses.SnapshotResult.Ok -> {
                val payload = com.google.gson.JsonObject().apply {
                    addProperty("format", "jpg")
                    addProperty("base64", java.util.Base64.getEncoder().encodeToString(result.frame.jpeg))
                    addProperty("width", result.frame.width)
                    addProperty("height", result.frame.height)
                }
                OpenClawNodeInvokeResult.success(request.id, payload)
            }
            com.smartview.glassai.glasses.SnapshotResult.NoFrame ->
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_NO_FRAME, "No video frame available")
            // Codes AND messages are the iOS ones (research §2.9); the Android-specific detail is
            // logged so gateway-side prompts see exactly what they see from the iOS node.
            is com.smartview.glassai.glasses.SnapshotResult.NotReady -> {
                android.util.Log.w(TAG, "camera.snap NOT_READY: ${result.detail}")
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_NOT_READY, "Stream not initialized")
            }
            is com.smartview.glassai.glasses.SnapshotResult.StreamFailed -> {
                android.util.Log.w(TAG, "camera.snap STREAM_FAILED: ${result.detail}")
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_STREAM_FAILED, "Could not start camera stream")
            }
            com.smartview.glassai.glasses.SnapshotResult.PermissionRequired ->
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_PERMISSION_REQUIRED, "Glasses camera permission not granted; open the app to grant it")
            com.smartview.glassai.glasses.SnapshotResult.EncodeFailed ->
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_ENCODE_FAILED, "Failed to encode JPEG")
        }
    }

    private fun cameraList(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
        val cameras = com.google.gson.JsonArray()
        if (frames.hasActiveDevice) {
            cameras.add(com.google.gson.JsonObject().apply {
                addProperty("id", "rayban-main")
                addProperty("name", "Ray-Ban Meta Camera")
                addProperty("facing", "front")
                addProperty("available", true)
            })
        }
        return OpenClawNodeInvokeResult.success(request.id, com.google.gson.JsonObject().apply { add("cameras", cameras) })
    }

    private fun deviceStatus(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
        OpenClawNodeInvokeResult.success(request.id, com.google.gson.JsonObject().apply {
            addProperty("deviceConnected", frames.hasActiveDevice)
            addProperty("isStreaming", frames.isStreaming)
            addProperty("streamStatus", frames.streamStatus)
            addProperty("hasVideoFrame", frames.hasFrame)
        })

    private fun deviceInfo(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
        OpenClawNodeInvokeResult.success(request.id, com.google.gson.JsonObject().apply {
            addProperty("deviceType", "Ray-Ban Meta")
            addProperty("appName", "TurboMeta")
            addProperty("appVersion", deviceInfo.appVersion)
            addProperty("sdkVersion", deviceInfo.sdkVersion)
            addProperty("platform", "Android")
            addProperty("osVersion", deviceInfo.osVersion)
        })
}
```

Then replace the fully-qualified names with imports at the top of the file (`import android.os.Build`, `import android.util.Log`, `import com.google.gson.JsonArray`, `import com.google.gson.JsonObject`, `import com.smartview.glassai.BuildConfig`, `import com.smartview.glassai.glasses.GlassesFrameProvider`, `import com.smartview.glassai.glasses.SnapshotResult`, `import java.util.Base64`) and shorten the references accordingly — the fully-qualified form above compiles as-is, the import form is the one to commit.

- [ ] **Step 4.6: Add `BuildConfig.MWDAT_VERSION`**

In `app/build.gradle.kts`, inside `defaultConfig { … }` right after the `manifestPlaceholders["mwdat_client_token"] = …` statement, add:

```kotlin
        // DAT SDK version for device.info (OpenClaw) and the Settings About row, fed from the
        // version catalog so it cannot drift from the dependency.
        buildConfigField("String", "MWDAT_VERSION", "\"${libs.versions.mwdat.get()}\"")
```

- [ ] **Step 4.7: Foreground tracking + integration wiring in `TurboMetaApplication`**

Create `app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawIntegration.kt`:

```kotlin
package com.smartview.glassai.services.openclaw

import android.app.Application
import android.util.Log
import com.smartview.glassai.glasses.SessionFrameProvider

/**
 * Installs the command router into the OpenClaw singleton once per process. Nothing here does I/O:
 * the settings store opens EncryptedSharedPreferences lazily and the Ed25519 identity loads on the
 * first connect(). It is still wrapped in runCatching so a feature-level failure can never turn
 * into a crash in Application.onCreate before the user can reach Settings.
 */
object OpenClawIntegration {
    private const val TAG = "OpenClawIntegration"

    fun install(app: Application) {
        runCatching {
            OpenClawNodeService.getInstance(app).setCommandRouter(
                OpenClawCommandRouter(
                    frames = SessionFrameProvider.create(app),
                    deviceInfo = OpenClawDeviceInfoSource.fromBuild(),
                )
            )
        }.onFailure { Log.e(TAG, "OpenClaw unavailable: ${it.message}", it) }
    }
}
```

Replace `app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` with:

```kotlin
package com.smartview.glassai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.smartview.glassai.services.openclaw.OpenClawIntegration

class TurboMetaApplication : Application() {

    @Volatile
    private var startedActivities = 0

    /** True while at least one Activity is started (spec §1: no background camera.snap). */
    val isInForeground: Boolean
        get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize the DAT SDK once per process, before any Activity, Service or ViewModel
        // touches Wearables APIs (the wake-word QuickVisionService can start without an Activity).
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e(TAG, "DAT SDK initialize failed: ${error.description}")
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // OpenClaw node commands (camera.snap etc.) route through the shared glasses session.
        // Nothing here touches the session manager, EncryptedSharedPreferences or the Ed25519 seed
        // until the first connect()/command; install() itself never throws.
        OpenClawIntegration.install(this)
    }

    companion object {
        private const val TAG = "TurboMetaApplication"

        lateinit var instance: TurboMetaApplication
            private set
    }
}
```

- [ ] **Step 4.8: Run the tests**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.OpenClawCommandRouterTest" --tests "com.smartview.glassai.glasses.SessionFrameProviderTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, at least 14 tests pass (7 router + 7 provider).

- [ ] **Step 4.9: Build and commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:assembleDebug :app:assembleRelease 2>&1 | tail -10
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/build.gradle.kts android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt android/app/src/main/java/com/smartview/glassai/glasses/GlassesFrameProvider.kt android/app/src/main/java/com/smartview/glassai/services/openclaw android/app/src/test && git commit -m "feat(android): OpenClawCommandRouter (camera.snap/list, device.status/info) over SessionFrameProvider — manager latestFrame with capturer fallback and foreground gate; BuildConfig.MWDAT_VERSION"
```

---

### Task 5: `FunASRService` — DashScope Fun-ASR realtime speech-to-text

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/services/PcmAudioSource.kt`
- Create: `app/src/main/java/com/smartview/glassai/services/FunASRService.kt`
- Create: `app/src/test/java/com/smartview/glassai/services/FunASRServiceTest.kt`

**Interfaces:**
- Produces:
```kotlin
interface PcmAudioSource { fun start(onChunk: (ByteArray) -> Unit): Boolean; fun stop() }
class AudioRecordPcmSource(audioSource: Int /* MediaRecorder.AudioSource.* */) : PcmAudioSource
/** What OpenClawViewModel needs from a recognizer; FunASRService is the production implementation, tests use a fake. */
interface SpeechRecognizerSession {
    var onStarted: (() -> Unit)?; var onPartialResult: ((String) -> Unit)?; var onFinalResult: ((String) -> Unit)?; var onError: ((String) -> Unit)?; var onFinished: (() -> Unit)?
    fun start(); fun stop(); fun switchAudioSource(source: BluetoothAudioManager.AudioSource)
}
class FunASRService(
    apiKey: String,
    endpoint: AlibabaEndpoint,
    httpClient: OkHttpClient,
    audioSourceFactory: (BluetoothAudioManager.AudioSource) -> PcmAudioSource,
    initialAudioSource: BluetoothAudioManager.AudioSource = PHONE_MIC,
    endpointUrlOverride: String? = null,
) : SpeechRecognizerSession {
    val isListening: StateFlow<Boolean>
    @VisibleForTesting internal fun resolvedUrl(): String   // endpointUrlOverride ?: endpointUrl(endpoint)
    companion { fun endpointUrl(endpoint: AlibabaEndpoint): String; const val MODEL = "fun-asr-realtime"; const val SAMPLE_RATE = 16000 }
}
```
- Consumes: `AlibabaEndpoint` (Beijing/Singapore), `BluetoothAudioManager.AudioSource`, OkHttp `WebSocket`.

- [ ] **Step 5.1: Write the failing protocol test**

Create `app/src/test/java/com/smartview/glassai/services/FunASRServiceTest.kt`:

```kotlin
package com.smartview.glassai.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.BluetoothAudioManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunASRServiceTest {

    private val server = MockWebServer()
    private val textFrames = LinkedBlockingQueue<JsonObject>()
    private val binaryFrames = LinkedBlockingQueue<ByteArray>()
    @Volatile private var serverSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private class FakeAudio : PcmAudioSource {
        var startCalls = 0
        var stopCalls = 0
        var chunk = ByteArray(320) { it.toByte() }
        override fun start(onChunk: (ByteArray) -> Unit): Boolean {
            startCalls++
            onChunk(chunk)
            return true
        }
        override fun stop() { stopCalls++ }
    }

    private val audio = FakeAudio()
    private val createdFor = mutableListOf<BluetoothAudioManager.AudioSource>()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { textFrames.add(JsonParser.parseString(text).asJsonObject) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { binaryFrames.add(bytes.toByteArray()) }
    }

    private fun newService(): FunASRService {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        return FunASRService(
            apiKey = "sk-test",
            endpoint = AlibabaEndpoint.BEIJING,
            httpClient = httpClient,
            audioSourceFactory = { source -> createdFor += source; audio },
            endpointUrlOverride = "ws://${server.hostName}:${server.port}/api-ws/v1/inference",
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() } // some tests shut it down mid-test on purpose
        httpClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun endpointsFollowTheAlibabaRegion() {
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference", FunASRService.endpointUrl(AlibabaEndpoint.BEIJING))
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", FunASRService.endpointUrl(AlibabaEndpoint.SINGAPORE))
    }

    @Test
    fun resolvedUrlFollowsTheRegionUnlessOverridden() {
        val singapore = FunASRService(
            apiKey = "sk-test", endpoint = AlibabaEndpoint.SINGAPORE, httpClient = httpClient,
            audioSourceFactory = { audio },
        )
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", singapore.resolvedUrl())
        val overridden = FunASRService(
            apiKey = "sk-test", endpoint = AlibabaEndpoint.SINGAPORE, httpClient = httpClient,
            audioSourceFactory = { audio }, endpointUrlOverride = "ws://127.0.0.1:1/x",
        )
        assertEquals("ws://127.0.0.1:1/x", overridden.resolvedUrl())
    }

    @Test
    fun transportFailureReportsAnErrorUnlessStopping() {
        val service = newService()
        val errors = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        service.onError = { errors += it; latch.countDown() }
        service.start()
        assertNotNull(textFrames.poll(5, TimeUnit.SECONDS)) // run-task went out
        server.shutdown() // the connection dies underneath the client -> onFailure

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, errors.size)
        assertFalse(service.isListening.value)
    }

    @Test
    fun transportFailureAfterStopIsSilent() {
        val service = newService()
        val errors = CopyOnWriteArrayList<String>()
        service.onError = { errors += it }
        service.start()
        assertNotNull(textFrames.poll(5, TimeUnit.SECONDS)) // run-task
        service.stop() // stopping = true; finish-task sent; close scheduled
        assertNotNull(textFrames.poll(5, TimeUnit.SECONDS)) // finish-task
        server.shutdown()
        Thread.sleep(700) // > CLOSE_DELAY_MS: whichever of onClosed/onFailure fires, no error surfaces

        assertTrue("unexpected errors: $errors", errors.isEmpty())
        assertFalse(service.isListening.value)
    }

    @Test
    fun taskFinishedFromTheServerStopsTheMicAndReportsFinished() {
        val service = newService()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        service.onStarted = { started.countDown() }
        service.onFinished = { finished.countDown() }
        service.start()
        val taskId = textFrames.poll(5, TimeUnit.SECONDS)!!.getAsJsonObject("header").get("task_id").asString
        serverSocket!!.send("""{"header":{"event":"task-started","task_id":"$taskId"}}""")
        assertTrue(started.await(5, TimeUnit.SECONDS))

        serverSocket!!.send("""{"header":{"event":"task-finished","task_id":"$taskId"}}""") // no stop() first

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(service.isListening.value)
        assertEquals(1, audio.stopCalls)
        assertNull(textFrames.poll(300, TimeUnit.MILLISECONDS)) // no finish-task after the server ended it
    }

    @Test
    fun fullSessionRunTaskAudioResultsFinishTask() {
        val service = newService()
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        service.onStarted = { started.countDown() }
        service.onPartialResult = { partials += it }
        service.onFinalResult = { finals += it }
        service.onFinished = { finished.countDown() }

        service.start()

        // 1. run-task with the exact DashScope header/payload
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val runTask = textFrames.poll(5, TimeUnit.SECONDS)!!
        val header = runTask.getAsJsonObject("header")
        assertEquals("run-task", header.get("action").asString)
        assertEquals("duplex", header.get("streaming").asString)
        val taskId = header.get("task_id").asString
        assertEquals(32, taskId.length)
        assertTrue(taskId.all { it in '0'..'9' || it in 'a'..'f' })
        val payload = runTask.getAsJsonObject("payload")
        assertEquals("audio", payload.get("task_group").asString)
        assertEquals("asr", payload.get("task").asString)
        assertEquals("recognition", payload.get("function").asString)
        assertEquals("fun-asr-realtime", payload.get("model").asString)
        val parameters = payload.getAsJsonObject("parameters")
        assertEquals("pcm", parameters.get("format").asString)
        assertEquals(16000, parameters.get("sample_rate").asInt)
        assertEquals("", parameters.get("vocabulary_id").asString)
        assertFalse(parameters.get("disfluency_removal_enabled").asBoolean)
        assertTrue(payload.getAsJsonObject("input").entrySet().isEmpty())
        assertEquals(0, audio.startCalls) // mic must not start before task-started

        // 2. task-started -> mic starts and PCM goes out as a binary frame
        serverSocket!!.send("""{"header":{"event":"task-started","task_id":"$taskId"}}""")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val pcm = binaryFrames.poll(5, TimeUnit.SECONDS)
        assertNotNull(pcm)
        assertEquals(320, pcm!!.size)
        assertEquals(1, audio.startCalls)
        assertEquals(listOf(BluetoothAudioManager.AudioSource.PHONE_MIC), createdFor)
        assertTrue(service.isListening.value)

        // 3. results: end_time null/0 = partial, > 0 = final
        serverSocket!!.send("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好","end_time":null}}}}""")
        serverSocket!!.send("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好世界","end_time":1234}}}}""")
        val deadline = System.currentTimeMillis() + 5_000
        while (finals.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("你好"), partials)
        assertEquals(listOf("你好世界"), finals)

        // 4. stop -> mic stops, finish-task sent, then task-finished closes the session
        service.stop()
        assertEquals(1, audio.stopCalls)
        val finish = textFrames.poll(5, TimeUnit.SECONDS)!!
        assertEquals("finish-task", finish.getAsJsonObject("header").get("action").asString)
        assertEquals(taskId, finish.getAsJsonObject("header").get("task_id").asString)
        assertEquals("duplex", finish.getAsJsonObject("header").get("streaming").asString)
        assertTrue(finish.getAsJsonObject("payload").getAsJsonObject("input").entrySet().isEmpty())
        serverSocket!!.send("""{"header":{"event":"task-finished","task_id":"$taskId"}}""")
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(service.isListening.value)
    }

    @Test
    fun taskFailedReportsTheServerMessage() {
        val service = newService()
        val errors = mutableListOf<String>()
        val latch = CountDownLatch(1)
        service.onError = { errors += it; latch.countDown() }
        service.start()
        textFrames.poll(5, TimeUnit.SECONDS)
        serverSocket!!.send("""{"header":{"event":"task-failed","error_code":"InvalidParameter","error_message":"bad model"}}""")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("bad model"), errors)
        assertFalse(service.isListening.value)
        assertEquals(0, audio.startCalls)
    }

    @Test
    fun switchingTheAudioSourceRestartsCaptureFromTheNewSource() {
        val service = newService()
        val started = CountDownLatch(1)
        service.onStarted = { started.countDown() }
        service.start()
        val taskId = textFrames.poll(5, TimeUnit.SECONDS)!!.getAsJsonObject("header").get("task_id").asString
        serverSocket!!.send("""{"header":{"event":"task-started","task_id":"$taskId"}}""")
        assertTrue(started.await(5, TimeUnit.SECONDS))

        service.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)

        assertEquals(1, audio.stopCalls)
        assertEquals(2, audio.startCalls)
        assertEquals(
            listOf(BluetoothAudioManager.AudioSource.PHONE_MIC, BluetoothAudioManager.AudioSource.BLUETOOTH_MIC),
            createdFor,
        )
        service.stop()
    }
}
```

- [ ] **Step 5.2: Run and watch it fail**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.FunASRServiceTest" 2>&1 | tail -20
```
Expected: `BUILD FAILED`, unresolved `FunASRService` / `PcmAudioSource`.

- [ ] **Step 5.3: Create `PcmAudioSource.kt`**

```kotlin
package com.smartview.glassai.services

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 16 kHz mono PCM16 capture seam so FunASRService can be tested without AudioRecord. */
interface PcmAudioSource {
    /** Starts delivering little-endian PCM16 chunks on a background thread; false if it could not start. */
    fun start(onChunk: (ByteArray) -> Unit): Boolean
    fun stop()
}

/**
 * AudioRecord at 16 kHz / mono / PCM16 from [audioSource] (MediaRecorder.AudioSource.MIC for the
 * phone, VOICE_COMMUNICATION after BluetoothAudioManager started SCO for the glasses).
 * RECORD_AUDIO must already be granted (the chat screen requests it before listening).
 */
class AudioRecordPcmSource(private val audioSource: Int) : PcmAudioSource {
    companion object {
        private const val TAG = "AudioRecordPcmSource"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var job: Job? = null

    override fun start(onChunk: (ByteArray) -> Unit): Boolean {
        if (record != null) return true
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val bufferSize = maxOf(minBuffer, 3200) // >= 100 ms of 16 kHz PCM16
            val audioRecord = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 2)
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord.release()
                return false
            }
            audioRecord.startRecording()
            record = audioRecord
            job = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) onChunk(buffer.copyOf(read))
                }
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            false
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            false
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        record?.let { audioRecord ->
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
        }
        record = null
    }
}
```

- [ ] **Step 5.4: Create `FunASRService.kt`**

```kotlin
package com.smartview.glassai.services

import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.BluetoothAudioManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * What OpenClawViewModel needs from a speech recognizer. [FunASRService] is the production
 * implementation; OpenClawViewModelTest drives the ViewModel with a fake.
 */
interface SpeechRecognizerSession {
    var onStarted: (() -> Unit)?
    var onPartialResult: ((String) -> Unit)?
    var onFinalResult: ((String) -> Unit)?
    var onError: ((String) -> Unit)?
    var onFinished: (() -> Unit)?
    fun start()
    fun stop()
    fun switchAudioSource(source: BluetoothAudioManager.AudioSource)
}

/**
 * Alibaba DashScope Fun-ASR realtime (research §4) over the "inference" WebSocket API:
 * run-task -> task-started -> binary PCM16 16 kHz frames -> result-generated (end_time > 0 =
 * final sentence) -> finish-task -> task-finished. Unlike iOS the endpoint follows the selected
 * Alibaba region (Beijing / Singapore). Research §8.3: whether `fun-asr-realtime` is served on the
 * intl (Singapore) endpoint could not be verified without a DashScope key on this host; Task 9
 * carries that check to the owner's phone, and a `task-failed` from the intl endpoint surfaces its
 * server message through [onError] rather than being retried on Beijing silently.
 *
 * Audio source semantics mirror Live AI: PHONE_MIC = MediaRecorder.AudioSource.MIC,
 * BLUETOOTH_MIC = VOICE_COMMUNICATION (the caller starts SCO through BluetoothAudioManager).
 */
class FunASRService(
    private val apiKey: String,
    private val endpoint: AlibabaEndpoint,
    private val httpClient: OkHttpClient,
    private val audioSourceFactory: (BluetoothAudioManager.AudioSource) -> PcmAudioSource,
    initialAudioSource: BluetoothAudioManager.AudioSource = BluetoothAudioManager.AudioSource.PHONE_MIC,
    private val endpointUrlOverride: String? = null,
) : SpeechRecognizerSession {
    companion object {
        private const val TAG = "FunASRService"
        const val MODEL = "fun-asr-realtime"
        const val SAMPLE_RATE = 16_000
        private const val WS_BEIJING = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val WS_SINGAPORE = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference"
        private const val CLOSE_DELAY_MS = 500L

        fun endpointUrl(endpoint: AlibabaEndpoint): String = when (endpoint) {
            AlibabaEndpoint.BEIJING -> WS_BEIJING
            AlibabaEndpoint.SINGAPORE -> WS_SINGAPORE
        }

        /** MediaRecorder.AudioSource constant for a Live AI audio-source choice. */
        fun recorderSourceFor(source: BluetoothAudioManager.AudioSource): Int = when (source) {
            BluetoothAudioManager.AudioSource.PHONE_MIC -> MediaRecorder.AudioSource.MIC
            BluetoothAudioManager.AudioSource.BLUETOOTH_MIC -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }

        /** The default production factory: a fresh AudioRecord per source switch. */
        fun defaultAudioSourceFactory(): (BluetoothAudioManager.AudioSource) -> PcmAudioSource =
            { source -> AudioRecordPcmSource(recorderSourceFor(source)) }
    }

    override var onStarted: (() -> Unit)? = null
    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null

    private val _isListening = MutableStateFlow(false)
    /** True from task-started until stop()/task-finished/task-failed. */
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** The URL start() dials: the test override, else the region endpoint. */
    @VisibleForTesting
    internal fun resolvedUrl(): String = endpointUrlOverride ?: endpointUrl(endpoint)

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var audio: PcmAudioSource? = null
    private var currentAudioSource = initialAudioSource
    private var taskId: String = newTaskId()
    private var taskStarted = false
    private var stopping = false

    private fun newTaskId(): String = UUID.randomUUID().toString().replace("-", "").lowercase()

    override fun start() {
        synchronized(lock) {
            if (webSocket != null) return
            taskId = newTaskId()
            taskStarted = false
            stopping = false
            val request = Request.Builder()
                .url(resolvedUrl())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            webSocket = httpClient.newWebSocket(request, Listener())
        }
    }

    /** Stops the microphone, sends finish-task and closes the socket shortly after. */
    override fun stop() {
        val socket: WebSocket?
        synchronized(lock) {
            stopping = true
            stopAudioLocked()
            socket = webSocket
        }
        _isListening.value = false
        if (socket != null) {
            socket.send(gson.toJson(finishTaskFrame()))
            scope.launch {
                delay(CLOSE_DELAY_MS)
                closeSocket(socket)
            }
        }
    }

    override fun switchAudioSource(source: BluetoothAudioManager.AudioSource) {
        synchronized(lock) {
            if (currentAudioSource == source) return
            val wasCapturing = audio != null
            stopAudioLocked()
            currentAudioSource = source
            if (wasCapturing && taskStarted && !stopping) startAudioLocked()
        }
    }

    private fun closeSocket(socket: WebSocket) {
        synchronized(lock) {
            if (webSocket === socket) webSocket = null
        }
        runCatching { socket.close(1000, "done") }
    }

    private fun startAudioLocked() {
        val source = audioSourceFactory(currentAudioSource)
        val ok = source.start { chunk ->
            val socket = synchronized(lock) { webSocket }
            // okio 3.x: the Kotlin-visible ByteString.of(array, offset, count) is a DeprecationLevel.ERROR
            // shim; ByteArray.toByteString() is the API (whole array — the source hands us exact copies).
            socket?.send(chunk.toByteString())
        }
        if (ok) {
            audio = source
        } else {
            Log.e(TAG, "audio source failed to start")
            onError?.invoke("Microphone unavailable")
        }
    }

    private fun stopAudioLocked() {
        audio?.stop()
        audio = null
    }

    private fun runTaskFrame(): JsonObject = JsonObject().apply {
        add("header", JsonObject().apply {
            addProperty("action", "run-task")
            addProperty("task_id", taskId)
            addProperty("streaming", "duplex")
        })
        add("payload", JsonObject().apply {
            addProperty("task_group", "audio")
            addProperty("task", "asr")
            addProperty("function", "recognition")
            addProperty("model", MODEL)
            add("parameters", JsonObject().apply {
                addProperty("format", "pcm")
                addProperty("sample_rate", SAMPLE_RATE)
                addProperty("vocabulary_id", "")
                addProperty("disfluency_removal_enabled", false)
            })
            add("input", JsonObject())
        })
    }

    private fun finishTaskFrame(): JsonObject = JsonObject().apply {
        add("header", JsonObject().apply {
            addProperty("action", "finish-task")
            addProperty("task_id", taskId)
            addProperty("streaming", "duplex")
        })
        add("payload", JsonObject().apply { add("input", JsonObject()) })
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "connected; sending run-task $taskId")
            webSocket.send(gson.toJson(runTaskFrame()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "closed: $code $reason")
            finish(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "socket failure: ${t.message}")
            val wasStopping = synchronized(lock) { stopping }
            finish(webSocket)
            if (!wasStopping) onError?.invoke(t.message ?: "Connection failed")
        }
    }

    private fun finish(socket: WebSocket) {
        synchronized(lock) {
            stopAudioLocked()
            if (webSocket === socket) webSocket = null
        }
        _isListening.value = false
    }

    private fun handleMessage(text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "ignoring non-JSON frame")
            return
        }
        val header = json.get("header")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        when (header.get("event")?.takeIf { it.isJsonPrimitive }?.asString) {
            "task-started" -> {
                synchronized(lock) {
                    taskStarted = true
                    if (!stopping) startAudioLocked()
                }
                _isListening.value = true
                onStarted?.invoke()
            }
            "result-generated" -> {
                val sentence = json.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("output")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("sentence")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                val sentenceText = sentence.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val endTime = sentence.get("end_time")?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
                if (endTime > 0) onFinalResult?.invoke(sentenceText) else onPartialResult?.invoke(sentenceText)
            }
            "task-finished" -> {
                // Also reached without a prior stop() (server-side end of task): stop the mic here
                // instead of waiting for onClosed, so no PCM is pushed into a finished task.
                val socket = synchronized(lock) {
                    stopAudioLocked()
                    webSocket
                }
                _isListening.value = false
                onFinished?.invoke()
                if (socket != null) closeSocket(socket)
            }
            "task-failed" -> {
                val message = header.get("error_message")?.takeIf { it.isJsonPrimitive }?.asString ?: "ASR task failed"
                Log.e(TAG, "task-failed: $message")
                val socket = synchronized(lock) {
                    stopAudioLocked()
                    webSocket
                }
                _isListening.value = false
                onError?.invoke(message)
                if (socket != null) closeSocket(socket)
            }
            else -> Log.d(TAG, "event: ${header.get("event")}")
        }
    }
}
```

- [ ] **Step 5.5: Run the tests**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.FunASRServiceTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, at least 8 tests pass.

- [ ] **Step 5.6: Build and commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:assembleDebug 2>&1 | tail -10
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/src/main/java/com/smartview/glassai/services/PcmAudioSource.kt android/app/src/main/java/com/smartview/glassai/services/FunASRService.kt android/app/src/test/java/com/smartview/glassai/services/FunASRServiceTest.kt && git commit -m "feat(android): FunASRService — DashScope fun-asr-realtime over WebSocket (run-task/finish-task, PCM16 16 kHz, region-aware endpoint) with a PcmAudioSource seam"
```

---

### Task 6: `OpenClawViewModel`, chat + settings screens, navigation, Home card, Settings "Integrations", cleartext config, strings

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt`
- Create: `app/src/main/java/com/smartview/glassai/ui/components/OpenClawStatus.kt`
- Create: `app/src/main/java/com/smartview/glassai/ui/screens/OpenClawChatScreen.kt`
- Create: `app/src/main/java/com/smartview/glassai/ui/screens/OpenClawSettingsScreen.kt`
- Create: `app/src/main/java/com/smartview/glassai/services/HttpClients.kt` (shared `OkHttpClient`; Task 7 consumes it)
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (anchor: the `android:theme="@style/Theme.TurboMeta"` attribute of the `<application` element, line 30 — NOT the identical attribute on the `<activity` element at line 48)
- Modify: `app/src/main/java/com/smartview/glassai/ui/theme/Color.kt` (anchor: `val QuickVisionColor = …`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` (anchors: `object MockDeviceKit : Screen("mock_device_kit")`, `composable(Screen.Home.route)`, `composable(Screen.Settings.route)`, `composable(Screen.MockDeviceKit.route)`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` (anchors: `onNavigateToRTMPStream: () -> Unit = {}`, `val isDatAppUpdateRequired by …`, `// Row 2: LeanEat + WordLearn`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` (anchors: `onNavigateToMockDeviceKit: () -> Unit = {}`, `// Data Section`)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml` (append before `</resources>`)
- Create: `app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt`

**Interfaces:**
- Produces `OpenClawViewModel` (see Step 6.4 for the exact constructor), `Screen.OpenClaw("openclaw")`, `Screen.OpenClawSettings("openclaw_settings")`, `@Composable fun OpenClawChatScreen(viewModel: OpenClawViewModel = viewModel(), onBackClick: () -> Unit, onOpenSettings: () -> Unit)`, `@Composable fun OpenClawSettingsScreen(onBackClick: () -> Unit)`, `@Composable fun openClawStatusText(state: OpenClawConnectionState): String`, `@Composable fun openClawStatusColor(state: OpenClawConnectionState): Color`
- Modifies `HomeScreen(… onNavigateToOpenClaw: () -> Unit = {})`, `SettingsScreen(… onNavigateToOpenClawSettings: () -> Unit = {})`
- Consumes: `OpenClawNodeService`, `SessionFrameProvider.create`, `FunASRService`, `BluetoothAudioManager`, `APIKeyManager.getAPIKey(APIProvider.ALIBABA, endpoint)`, `APIProviderManager.staticAlibabaEndpoint`, `GlassesSessionManager.acquire/release`

- [ ] **Step 6.1: Strings (en + zh) — every key in both files**

Append to `app/src/main/res/values/strings.xml` before `</resources>`:

```xml

    <!-- Phase B: OpenClaw (texts copied from the iOS en.lproj/Localizable.strings) -->
    <string name="settings_integrations">Integrations</string>
    <string name="openclaw_title">OpenClaw</string>
    <string name="openclaw_status_title">Status</string>
    <string name="openclaw_status_connected">Connected</string>
    <string name="openclaw_status_connecting">Connecting...</string>
    <string name="openclaw_status_reconnecting">Reconnecting (attempt %1$d)...</string>
    <string name="openclaw_status_pairing">Waiting for pairing</string>
    <string name="openclaw_status_disconnected">Not connected</string>
    <string name="openclaw_connect">Connect to Gateway</string>
    <string name="openclaw_disconnect">Disconnect</string>
    <string name="openclaw_pairing_hint">Run \'openclaw devices approve\' in your terminal to complete pairing</string>
    <string name="openclaw_gateway_section">Gateway</string>
    <string name="openclaw_gateway_help">Enter the address and port of the device running OpenClaw Gateway. Default is 127.0.0.1:18789</string>
    <string name="openclaw_host">Host</string>
    <string name="openclaw_port">Port</string>
    <string name="openclaw_scheme">Protocol</string>
    <string name="openclaw_token">Gateway Token</string>
    <string name="openclaw_capabilities">Device Capabilities</string>
    <string name="openclaw_capabilities_desc">When connected, OpenClaw AI can capture photos through the glasses, check device status, etc.</string>
    <string name="openclaw_node_id">Node ID</string>
    <string name="openclaw_commands">Commands</string>
    <string name="feature_openclaw_title">OpenClaw</string>
    <string name="feature_openclaw_subtitle">OpenClaw</string>
    <string name="feature_openclaw_connected">Connected</string>
    <string name="openclaw_chat_placeholder">Type a message...</string>
    <string name="openclaw_chat_photoprompt">Please look at this photo from my glasses</string>
    <string name="openclaw_chat_snap">Snap &amp; Send</string>
    <string name="openclaw_chat_sending">Sending...</string>
    <string name="openclaw_chat_noframe">Cannot get glasses frame, please check connection</string>
    <string name="openclaw_chat_noapikey">Please configure Alibaba API Key in Settings first</string>
    <string name="openclaw_chat_sendvoice">Send</string>
    <string name="openclaw_chat_text">Text</string>
    <string name="openclaw_chat_listening">Listening...</string>
    <string name="openclaw_chat_mic">Voice</string>
    <string name="openclaw_chat_stop">Stop</string>
    <string name="openclaw_chat_asr_failed">Speech recognition failed: %1$s</string>
    <string name="openclaw_error_max_retries">Connection failed after %1$d retries</string>
    <string name="openclaw_error_transport">Connection error: %1$s</string>
    <string name="openclaw_error_invalid_url">Invalid gateway address</string>
    <string name="audio_source_phone">Phone mic</string>
    <string name="audio_source_glasses">Glasses mic</string>
    <string name="done">Done</string>
```

Append to `app/src/main/res/values-zh-rCN/strings.xml` before `</resources>`:

```xml

    <!-- Phase B: OpenClaw（文案来自 iOS zh-Hans.lproj/Localizable.strings） -->
    <string name="settings_integrations">集成</string>
    <string name="openclaw_title">OpenClaw</string>
    <string name="openclaw_status_title">状态</string>
    <string name="openclaw_status_connected">已连接</string>
    <string name="openclaw_status_connecting">连接中...</string>
    <string name="openclaw_status_reconnecting">重连中（第 %1$d 次）...</string>
    <string name="openclaw_status_pairing">等待配对</string>
    <string name="openclaw_status_disconnected">未连接</string>
    <string name="openclaw_connect">连接 Gateway</string>
    <string name="openclaw_disconnect">断开连接</string>
    <string name="openclaw_pairing_hint">请在 OpenClaw 终端执行 openclaw devices approve 完成配对</string>
    <string name="openclaw_gateway_section">Gateway</string>
    <string name="openclaw_gateway_help">输入运行 OpenClaw Gateway 的设备地址和端口。本机默认为 127.0.0.1:18789</string>
    <string name="openclaw_host">地址</string>
    <string name="openclaw_port">端口</string>
    <string name="openclaw_scheme">协议</string>
    <string name="openclaw_token">Gateway 令牌</string>
    <string name="openclaw_capabilities">设备能力</string>
    <string name="openclaw_capabilities_desc">连接后，OpenClaw AI 可以通过眼镜拍照、获取设备状态等</string>
    <string name="openclaw_node_id">节点 ID</string>
    <string name="openclaw_commands">命令</string>
    <string name="feature_openclaw_title">OpenClaw</string>
    <string name="feature_openclaw_subtitle">OpenClaw</string>
    <string name="feature_openclaw_connected">已连接</string>
    <string name="openclaw_chat_placeholder">输入消息...</string>
    <string name="openclaw_chat_photoprompt">请看这张眼镜拍摄的照片</string>
    <string name="openclaw_chat_snap">拍照发送</string>
    <string name="openclaw_chat_sending">发送中...</string>
    <string name="openclaw_chat_noframe">无法获取眼镜画面，请确认眼镜已连接</string>
    <string name="openclaw_chat_noapikey">请先在设置中配置阿里云 API Key</string>
    <string name="openclaw_chat_sendvoice">发送</string>
    <string name="openclaw_chat_text">键盘</string>
    <string name="openclaw_chat_listening">正在聆听...</string>
    <string name="openclaw_chat_mic">语音</string>
    <string name="openclaw_chat_stop">停止</string>
    <string name="openclaw_chat_asr_failed">语音识别失败：%1$s</string>
    <string name="openclaw_error_max_retries">连接失败，已重试 %1$d 次</string>
    <string name="openclaw_error_transport">连接错误：%1$s</string>
    <string name="openclaw_error_invalid_url">Gateway 地址无效</string>
    <string name="audio_source_phone">手机麦克风</string>
    <string name="audio_source_glasses">眼镜麦克风</string>
    <string name="done">完成</string>
```

- [ ] **Step 6.2: Cleartext network config + manifest + colors**

Create `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  The OpenClaw Gateway is a LAN service reached over plain ws:// (spec §6 B1). Android blocks
  cleartext by default from targetSdk 28 on, so it is allowed here; every cloud service the app
  talks to (DashScope, OpenRouter, Google) is https/wss and unaffected.
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

In `AndroidManifest.xml`, `android:theme="@style/Theme.TurboMeta"` occurs twice: on the `<application` element (line 30) and on the `<activity` element (line 48). Add the attribute after the **`<application`** occurrence only, leaving the activity untouched:

```xml
        android:networkSecurityConfig="@xml/network_security_config"
```

In `Color.kt`, after `val QuickVisionColor = Color(0xFF9B59B6) // Purple for Quick Vision` add:

```kotlin
val OpenClawColor = Color(0xFF7E57C2) // purple → indigo gradient like the iOS card
val OpenClawColorEnd = Color(0xFF3F51B5)
```

- [ ] **Step 6.3: Write the failing `OpenClawViewModelTest`**

Create `app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt`:

```kotlin
package com.smartview.glassai.viewmodels

import android.app.Application
import com.smartview.glassai.R
import com.smartview.glassai.glasses.FakeDatDeviceObserver
import com.smartview.glassai.glasses.FakeDatSessionFactory
import com.smartview.glassai.glasses.FrameSnapshot
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SnapshotResult
import com.smartview.glassai.glasses.TestBitmaps
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.BluetoothAudioManager
import com.smartview.glassai.services.SpeechRecognizerSession
import com.smartview.glassai.services.openclaw.InMemoryOpenClawSettingsStore
import com.smartview.glassai.services.openclaw.OpenClawChatMessage
import com.smartview.glassai.services.openclaw.OpenClawClientInfo
import com.smartview.glassai.services.openclaw.OpenClawDeviceIdentity
import com.smartview.glassai.services.openclaw.OpenClawNodeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val store = InMemoryOpenClawSettingsStore()
    private val service = OpenClawNodeService(
        store = store,
        identity = lazyOf(OpenClawDeviceIdentity.fromSeed(ByteArray(32) { 3 })),
        clientInfo = OpenClawClientInfo("2.0.0", "test", "rayban-test0001"),
        httpClient = OkHttpClient(),
    )
    private val factory = FakeDatSessionFactory()
    private val observer = FakeDatDeviceObserver()
    private lateinit var manager: GlassesSessionManager

    private class FakeFrames : GlassesFrameProvider {
        var result: SnapshotResult = SnapshotResult.NoFrame
        override val hasActiveDevice = true
        override val isStreaming = false
        override val streamStatus = "stopped"
        override val hasFrame = false
        override suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult = result
    }

    /** Scriptable recognizer: the test fires the callbacks FunASRService would. */
    private class FakeAsr : SpeechRecognizerSession {
        override var onStarted: (() -> Unit)? = null
        override var onPartialResult: ((String) -> Unit)? = null
        override var onFinalResult: ((String) -> Unit)? = null
        override var onError: ((String) -> Unit)? = null
        override var onFinished: (() -> Unit)? = null
        var startCalls = 0
        var stopCalls = 0
        val switched = mutableListOf<BluetoothAudioManager.AudioSource>()
        override fun start() { startCalls++ }
        override fun stop() { stopCalls++ }
        override fun switchAudioSource(source: BluetoothAudioManager.AudioSource) { switched += source }
    }

    private val frames = FakeFrames()
    private val asr = FakeAsr()
    private val asrCreatedWith = mutableListOf<Triple<String, AlibabaEndpoint, BluetoothAudioManager.AudioSource>>()
    private var alibabaKey: String? = "sk"

    private fun str(id: Int) = "str:$id"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        manager = GlassesSessionManager(factory, observer, CoroutineScope(SupervisorJob() + dispatcher)).also { it.startMonitoring() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = OpenClawViewModel(
        application = Application(),
        service = service,
        frames = frames,
        sessionManager = { manager },
        asrFactory = { key, endpoint, source ->
            asrCreatedWith += Triple(key, endpoint, source)
            asr
        },
        alibabaKey = { alibabaKey },
        alibabaEndpoint = { AlibabaEndpoint.BEIJING },
        bluetoothAudioManager = null,
        strings = ::str,
        decodeImage = { TestBitmaps.stub() },
        decodeDispatcher = dispatcher, // keeps withContext(decodeDispatcher) on the test scheduler
    )

    @Test
    fun deltaEventsReplacePendingAndFinalAppendsAssistantBubble() {
        val vm = newViewModel()
        vm.onChatEvent("Hel", isFinal = false)
        vm.onChatEvent("Hello", isFinal = false)
        assertEquals("Hello", vm.pendingResponse.value)
        assertTrue(vm.messages.value.isEmpty())

        vm.onChatEvent("Hello!", isFinal = true)

        assertNull(vm.pendingResponse.value)
        val message = vm.messages.value.single()
        assertEquals(OpenClawChatMessage.ROLE_ASSISTANT, message.role)
        assertEquals("Hello!", message.text)
    }

    @Test
    fun sendTextAppendsUserBubbleFlushesPendingAndClearsInput() {
        val vm = newViewModel()
        vm.onChatEvent("partial", isFinal = false)
        vm.onInputChanged("  hi  ")

        vm.sendText()

        assertEquals(listOf("partial", "hi"), vm.messages.value.map { it.text })
        assertEquals(listOf(OpenClawChatMessage.ROLE_ASSISTANT, OpenClawChatMessage.ROLE_USER), vm.messages.value.map { it.role })
        assertEquals("", vm.inputText.value)
        assertNull(vm.pendingResponse.value)
    }

    @Test
    fun snapWithoutFrameShowsTheNoFrameBubble() {
        val vm = newViewModel()
        frames.result = SnapshotResult.NoFrame

        vm.snapAndSend()

        val message = vm.messages.value.single()
        assertEquals(OpenClawChatMessage.ROLE_ASSISTANT, message.role)
        assertEquals(str(R.string.openclaw_chat_noframe), message.text)
        assertFalse(vm.isSending.value)
    }

    @Test
    fun snapWithFrameAppendsUserBubbleWithImageAndPrompt() {
        val vm = newViewModel()
        frames.result = SnapshotResult.Ok(FrameSnapshot(byteArrayOf(1, 2, 3), 4, 3))

        vm.snapAndSend()

        val message = vm.messages.value.single()
        assertEquals(OpenClawChatMessage.ROLE_USER, message.role)
        assertEquals(str(R.string.openclaw_chat_photoprompt), message.text)
        assertTrue(message.image != null)
        assertFalse(vm.isSending.value)
    }

    @Test
    fun listeningWithoutAlibabaKeyShowsTheNoApiKeyBubble() {
        alibabaKey = null
        val vm = newViewModel()
        vm.startListening()
        assertEquals(str(R.string.openclaw_chat_noapikey), vm.messages.value.single().text)
        assertFalse(vm.isListening.value)
        assertTrue(asrCreatedWith.isEmpty())
    }

    @Test
    fun voiceFlowAccumulatesFinalSentencesAndSendsThem() {
        val vm = newViewModel()
        vm.startListening()
        assertTrue(vm.isListening.value)
        assertEquals(1, asr.startCalls)
        assertEquals(
            Triple("sk", AlibabaEndpoint.BEIJING, BluetoothAudioManager.AudioSource.PHONE_MIC),
            asrCreatedWith.single(),
        )

        asr.onPartialResult!!("你")
        assertEquals("你", vm.asrPartial.value)
        asr.onFinalResult!!("你好")
        assertEquals("你好", vm.asrText.value) // asrText += sentence
        assertEquals("", vm.asrPartial.value) // asrPartial cleared by a final
        asr.onFinalResult!!("世界")
        asr.onPartialResult!!("再")
        assertEquals("你好世界", vm.asrText.value)

        vm.stopListening()
        assertEquals(1, asr.stopCalls)
        assertFalse(vm.isListening.value)
        assertEquals("你好世界", vm.asrText.value) // kept on screen for review

        vm.sendAsrText()
        val message = vm.messages.value.single()
        assertEquals(OpenClawChatMessage.ROLE_USER, message.role)
        assertEquals("你好世界再", message.text) // finals + the interim tail, trimmed
        assertEquals("", vm.asrText.value)
        assertEquals("", vm.asrPartial.value)
    }

    @Test
    fun recognizerErrorShowsTheLocalizedFailureAndCancelClearsIt() {
        val vm = newViewModel()
        vm.startListening()
        asr.onFinalResult!!("hello")

        asr.onError!!("boom")

        assertEquals(str(R.string.openclaw_chat_asr_failed), vm.asrError.value) // openclaw_chat_asr_failed % message
        assertFalse(vm.isListening.value)

        vm.cancelAsr()

        assertEquals(1, asr.stopCalls)
        assertEquals("", vm.asrText.value)
        assertEquals("", vm.asrPartial.value)
        assertNull(vm.asrError.value)
        assertTrue(vm.messages.value.isEmpty())
    }

    @Test
    fun switchingTheAudioSourceReachesTheRunningRecognizer() {
        val vm = newViewModel() // no BluetoothAudioManager on the JVM: the fallback flow is used
        vm.startListening()

        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)

        assertEquals(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC, vm.currentAudioSource.value)
        assertEquals(listOf(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC), asr.switched)
    }

    @Test
    fun enteringTheScreenAcquiresTheSharedSessionAndLeavingReleasesIt() {
        val vm = newViewModel()
        vm.enterScreen()
        assertEquals(1, manager.ownerCount)
        vm.leaveScreen()
        assertEquals(0, manager.ownerCount)
    }
}
```

- [ ] **Step 6.4: Create `OpenClawViewModel.kt`**

```kotlin
package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartview.glassai.R
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionFrameProvider
import com.smartview.glassai.glasses.SnapshotResult
import com.smartview.glassai.managers.APIProvider
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.BluetoothAudioManager
import com.smartview.glassai.services.FunASRService
import com.smartview.glassai.services.HttpClients
import com.smartview.glassai.services.SpeechRecognizerSession
import com.smartview.glassai.services.openclaw.OpenClawChatMessage
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawNodeService
import com.smartview.glassai.utils.APIKeyManager
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for OpenClawChatScreen (research §5.1). Messages live only in memory (spec §3 decision 3).
 * Entering the chat acquires the shared glasses session (spec §3 decision 1) so a snap does not
 * pay the session start; the camera itself is borrowed per snap by SessionFrameProvider.
 */
class OpenClawViewModel internal constructor(
    application: Application,
    private val service: OpenClawNodeService,
    private val frames: GlassesFrameProvider,
    private val sessionManager: () -> GlassesSessionManager,
    private val asrFactory: (String, AlibabaEndpoint, BluetoothAudioManager.AudioSource) -> SpeechRecognizerSession,
    private val alibabaKey: () -> String?,
    private val alibabaEndpoint: () -> AlibabaEndpoint,
    private val bluetoothAudioManager: BluetoothAudioManager?,
    private val strings: (Int) -> String,
    private val decodeImage: (ByteArray) -> Bitmap?,
    /** Where the snapped JPEG is decoded into the bubble bitmap (never Main in the app). */
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        service = OpenClawNodeService.getInstance(application),
        frames = SessionFrameProvider.create(application),
        sessionManager = { GlassesSessionManager.getInstance(application) },
        asrFactory = { key, endpoint, source ->
            FunASRService(
                apiKey = key,
                endpoint = endpoint,
                httpClient = HttpClients.websocket,
                audioSourceFactory = FunASRService.defaultAudioSourceFactory(),
                initialAudioSource = source,
            )
        },
        alibabaKey = {
            val manager = APIKeyManager.getInstance(application)
            manager.getAPIKey(APIProvider.ALIBABA, APIProviderManager.staticAlibabaEndpoint)
        },
        alibabaEndpoint = { APIProviderManager.staticAlibabaEndpoint },
        bluetoothAudioManager = BluetoothAudioManager(application),
        strings = { id -> application.getString(id) },
        decodeImage = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) },
    )

    companion object {
        private const val TAG = "OpenClawViewModel"
        const val OWNER = "OpenClawChat"
        private const val SNAP_MAX_WIDTH = 1600
        private const val SNAP_QUALITY = 0.7
        private const val SNAP_TIMEOUT_MS = 5_000L
    }

    val connectionState: StateFlow<OpenClawConnectionState> = service.connectionState

    private val _messages = MutableStateFlow<List<OpenClawChatMessage>>(emptyList())
    val messages: StateFlow<List<OpenClawChatMessage>> = _messages.asStateFlow()

    /** The streaming assistant bubble (replace-style deltas). */
    private val _pendingResponse = MutableStateFlow<String?>(null)
    val pendingResponse: StateFlow<String?> = _pendingResponse.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _showTextInput = MutableStateFlow(false)
    val showTextInput: StateFlow<Boolean> = _showTextInput.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** Final sentences accumulated since the mic was started. */
    private val _asrText = MutableStateFlow("")
    val asrText: StateFlow<String> = _asrText.asStateFlow()

    /** The interim (not yet final) sentence. */
    private val _asrPartial = MutableStateFlow("")
    val asrPartial: StateFlow<String> = _asrPartial.asStateFlow()

    private val _asrError = MutableStateFlow<String?>(null)
    val asrError: StateFlow<String?> = _asrError.asStateFlow()

    private val fallbackAudioSource = MutableStateFlow(BluetoothAudioManager.AudioSource.PHONE_MIC)
    val currentAudioSource: StateFlow<BluetoothAudioManager.AudioSource> =
        bluetoothAudioManager?.currentAudioSource ?: fallbackAudioSource
    val isBluetoothAvailable: StateFlow<Boolean> =
        bluetoothAudioManager?.isBluetoothScoAvailable ?: MutableStateFlow(false)

    private var asr: SpeechRecognizerSession? = null
    private var chatJob: Job? = null
    private var sessionHeld = false

    private fun str(@StringRes id: Int): String = strings(id)

    init {
        chatJob = viewModelScope.launch {
            service.chatEvents.collect { event -> onChatEvent(event.text, event.isFinal) }
        }
    }

    // ---- screen lifecycle ----

    /** Acquire the shared session (spec §3 decision 1) and auto-connect when a token is stored. */
    fun enterScreen() {
        if (!sessionHeld) {
            sessionHeld = true
            sessionManager().acquire(OWNER)
        }
        connectIfNeeded()
    }

    fun leaveScreen() {
        stopListening()
        flushPendingResponse()
        if (sessionHeld) {
            sessionHeld = false
            sessionManager().release(OWNER)
        }
    }

    fun connectIfNeeded() {
        if (service.connectionState.value == OpenClawConnectionState.Disconnected &&
            !service.loadGatewayToken().isNullOrBlank()
        ) {
            service.connect()
        }
    }

    // ---- chat ----

    /** Visible for tests; the production path is the chatEvents collector. */
    fun onChatEvent(text: String, isFinal: Boolean) {
        if (isFinal) {
            _pendingResponse.value = null
            if (text.isNotBlank()) append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = text))
        } else {
            _pendingResponse.value = text
        }
    }

    fun flushPendingResponse() {
        val pending = _pendingResponse.value?.takeIf { it.isNotBlank() }
        _pendingResponse.value = null
        if (pending != null) append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = pending))
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
    }

    fun toggleTextInput() {
        _showTextInput.value = !_showTextInput.value
    }

    fun sendText() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        flushPendingResponse()
        append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_USER, text = text))
        _inputText.value = ""
        if (!service.sendChatMessage(text)) Log.w(TAG, "chat.send dropped: not connected")
    }

    /** Snap & Send: latest glasses frame (or a fresh capture) + the typed text or the photo prompt. */
    fun snapAndSend() {
        if (_isSending.value) return
        _isSending.value = true
        viewModelScope.launch {
            try {
                when (val result = frames.snapshot(SNAP_MAX_WIDTH, SNAP_QUALITY, SNAP_TIMEOUT_MS)) {
                    is SnapshotResult.Ok -> {
                        val text = _inputText.value.trim().ifEmpty { str(R.string.openclaw_chat_photoprompt) }
                        // BitmapFactory.decodeByteArray of a <= 1600 px JPEG is not main-thread work
                        val image = withContext(decodeDispatcher) { decodeImage(result.frame.jpeg) }
                        flushPendingResponse()
                        append(
                            OpenClawChatMessage(
                                role = OpenClawChatMessage.ROLE_USER,
                                text = text,
                                image = image,
                            )
                        )
                        _inputText.value = ""
                        val base64 = Base64.getEncoder().encodeToString(result.frame.jpeg)
                        if (!service.sendChatMessage(text, imageJpegBase64 = base64)) {
                            Log.w(TAG, "chat.send with image dropped: not connected")
                        }
                    }
                    else -> {
                        Log.w(TAG, "snap failed: $result")
                        append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = str(R.string.openclaw_chat_noframe)))
                    }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    // ---- voice (Fun-ASR) ----

    fun startListening() {
        if (_isListening.value) return
        val key = alibabaKey()
        if (key.isNullOrBlank()) {
            append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = str(R.string.openclaw_chat_noapikey)))
            return
        }
        _asrText.value = ""
        _asrPartial.value = ""
        _asrError.value = null
        // Named asrService on purpose: `service` is the OpenClawNodeService property.
        val asrService = asrFactory(key, alibabaEndpoint(), currentAudioSource.value).apply {
            onPartialResult = { partial -> _asrPartial.value = partial }
            onFinalResult = { sentence ->
                _asrText.value = (_asrText.value + sentence)
                _asrPartial.value = ""
            }
            onError = { message ->
                _asrError.value = str(R.string.openclaw_chat_asr_failed).format(message)
                _isListening.value = false
            }
            onFinished = { _isListening.value = false }
        }
        asr = asrService
        _isListening.value = true
        asrService.start()
    }

    /** Keeps the recognized text on screen so the user can review, then Send or Cancel. */
    fun stopListening() {
        asr?.stop()
        asr = null
        _isListening.value = false
    }

    fun sendAsrText() {
        val text = (_asrText.value + _asrPartial.value).trim()
        _asrText.value = ""
        _asrPartial.value = ""
        if (text.isEmpty()) return
        flushPendingResponse()
        append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_USER, text = text))
        if (!service.sendChatMessage(text)) Log.w(TAG, "chat.send (voice) dropped: not connected")
    }

    fun cancelAsr() {
        stopListening()
        _asrText.value = ""
        _asrPartial.value = ""
        _asrError.value = null
    }

    fun switchAudioSource(source: BluetoothAudioManager.AudioSource) {
        bluetoothAudioManager?.switchAudioSource(source) ?: run { fallbackAudioSource.value = source }
        asr?.switchAudioSource(source)
    }

    private fun append(message: OpenClawChatMessage) {
        _messages.value = _messages.value + message
    }

    override fun onCleared() {
        super.onCleared()
        chatJob?.cancel()
        leaveScreen()
        bluetoothAudioManager?.cleanup()
    }
}
```

`HttpClients.websocket` is created in Task 7; for this task to compile now, create the file `app/src/main/java/com/smartview/glassai/services/HttpClients.kt` here with its Task 7 content:

```kotlin
package com.smartview.glassai.services

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Process-wide OkHttp clients (iOS 2.0 stability fix 4.1 equivalent). One client per purpose:
 * each OkHttpClient owns a Dispatcher, a ConnectionPool and threads, so creating one per service
 * instance leaked idle resources for 60 s after every Live AI session.
 */
object HttpClients {
    /** Cloud WebSockets (DashScope Omni, Gemini Live, Fun-ASR): keepalive pings, no read timeout. */
    val websocket: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
```

- [ ] **Step 6.5: Run the ViewModel test**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.viewmodels.OpenClawViewModelTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, at least 9 tests pass.

- [ ] **Step 6.6: Status helpers `ui/components/OpenClawStatus.kt`**

```kotlin
package com.smartview.glassai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.smartview.glassai.R
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawErrorReason
import com.smartview.glassai.ui.theme.Error
import com.smartview.glassai.ui.theme.Success
import com.smartview.glassai.ui.theme.TextTertiaryLight
import com.smartview.glassai.ui.theme.Warning

@Composable
fun openClawStatusText(state: OpenClawConnectionState): String = when (state) {
    OpenClawConnectionState.Connected -> stringResource(R.string.openclaw_status_connected)
    OpenClawConnectionState.Connecting -> stringResource(R.string.openclaw_status_connecting)
    is OpenClawConnectionState.Reconnecting -> stringResource(R.string.openclaw_status_reconnecting, state.attempt)
    OpenClawConnectionState.WaitingForPairing -> stringResource(R.string.openclaw_status_pairing)
    OpenClawConnectionState.Disconnected -> stringResource(R.string.openclaw_status_disconnected)
    is OpenClawConnectionState.Error -> when (val reason = state.reason) {
        is OpenClawErrorReason.MaxRetries -> stringResource(R.string.openclaw_error_max_retries, reason.attempts)
        is OpenClawErrorReason.Transport -> stringResource(R.string.openclaw_error_transport, reason.detail)
        OpenClawErrorReason.InvalidUrl -> stringResource(R.string.openclaw_error_invalid_url)
    }
}

@Composable
fun openClawStatusColor(state: OpenClawConnectionState): Color = when (state) {
    OpenClawConnectionState.Connected -> Success
    OpenClawConnectionState.Connecting -> Color(0xFFFF9800)
    is OpenClawConnectionState.Reconnecting -> Color(0xFFFF9800)
    OpenClawConnectionState.WaitingForPairing -> Warning
    OpenClawConnectionState.Disconnected -> TextTertiaryLight
    is OpenClawConnectionState.Error -> Error
}
```

- [ ] **Step 6.7: Create `OpenClawChatScreen.kt`**

```kotlin
package com.smartview.glassai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.smartview.glassai.R
import com.smartview.glassai.managers.BluetoothAudioManager
import com.smartview.glassai.services.openclaw.OpenClawChatMessage
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.ui.components.openClawStatusColor
import com.smartview.glassai.ui.components.openClawStatusText
import com.smartview.glassai.ui.theme.AppRadius
import com.smartview.glassai.ui.theme.AppSpacing
import com.smartview.glassai.ui.theme.OpenClawColor
import com.smartview.glassai.ui.theme.OpenClawColorEnd
import com.smartview.glassai.viewmodels.OpenClawViewModel

/** OpenClaw chat (research §5.1): voice / Snap & Send / text, in-memory history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClawChatScreen(
    viewModel: OpenClawViewModel = viewModel(),
    onBackClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val pendingResponse by viewModel.pendingResponse.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val showTextInput by viewModel.showTextInput.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val asrText by viewModel.asrText.collectAsState()
    val asrPartial by viewModel.asrPartial.collectAsState()
    val asrError by viewModel.asrError.collectAsState()
    val currentAudioSource by viewModel.currentAudioSource.collectAsState()
    val isBluetoothAvailable by viewModel.isBluetoothAvailable.collectAsState()
    val isConnected = connectionState == OpenClawConnectionState.Connected
    val listState = rememberLazyListState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening()
    }
    fun toggleListening() {
        if (isListening) {
            viewModel.stopListening()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        viewModel.enterScreen()
        onDispose { viewModel.leaveScreen() }
    }

    LaunchedEffect(messages.size, pendingResponse != null) {
        val count = messages.size + (if (pendingResponse != null) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.openclaw_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(openClawStatusColor(connectionState))
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (!isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF9800))
                        .padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (connectionState == OpenClawConnectionState.Connecting ||
                        connectionState is OpenClawConnectionState.Reconnecting
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(AppSpacing.small))
                    }
                    Text(openClawStatusText(connectionState), color = Color.White, fontSize = 13.sp)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                items(messages, key = { it.id }) { message -> ChatBubble(message) }
                pendingResponse?.let { pending ->
                    item(key = "pending") {
                        ChatBubble(OpenClawChatMessage(id = "pending", role = OpenClawChatMessage.ROLE_ASSISTANT, text = pending))
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(AppSpacing.medium)
                    .navigationBarsPadding()
            ) {
                // Voice transcript box + Cancel / Send (shown while listening or when text remains)
                val transcript = asrText + asrPartial
                if (isListening || transcript.isNotEmpty() || asrError != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.medium))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(AppSpacing.medium)
                    ) {
                        Text(
                            text = when {
                                asrError != null -> asrError!!
                                transcript.isEmpty() -> stringResource(R.string.openclaw_chat_listening)
                                else -> transcript
                            },
                            modifier = Modifier.heightIn(min = 40.dp),
                            color = if (asrError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        if (!isListening) {
                            Spacer(modifier = Modifier.height(AppSpacing.small))
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                                OutlinedButton(onClick = { viewModel.cancelAsr() }) { Text(stringResource(R.string.cancel)) }
                                Button(
                                    onClick = { viewModel.sendAsrText() },
                                    enabled = transcript.isNotBlank() && isConnected,
                                    colors = ButtonDefaults.buttonColors(containerColor = OpenClawColor)
                                ) { Text(stringResource(R.string.openclaw_chat_sendvoice)) }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.small))
                }

                // Phone / glasses microphone (same semantics as Live AI)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    FilterChip(
                        selected = currentAudioSource == BluetoothAudioManager.AudioSource.PHONE_MIC,
                        onClick = { viewModel.switchAudioSource(BluetoothAudioManager.AudioSource.PHONE_MIC) },
                        label = { Text(stringResource(R.string.audio_source_phone)) },
                        leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    FilterChip(
                        selected = currentAudioSource == BluetoothAudioManager.AudioSource.BLUETOOTH_MIC,
                        onClick = { viewModel.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC) },
                        enabled = isBluetoothAvailable,
                        label = { Text(stringResource(R.string.audio_source_glasses)) },
                        leadingIcon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.small))

                // Snap & Send | big mic | Text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton(
                        icon = Icons.Default.CameraAlt,
                        label = if (isSending) stringResource(R.string.openclaw_chat_sending) else stringResource(R.string.openclaw_chat_snap),
                        enabled = isConnected && !isSending,
                        onClick = { viewModel.snapAndSend() }
                    )
                    FilledIconButton(
                        onClick = { toggleListening() },
                        enabled = isConnected,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isListening) Color(0xFFE53935) else OpenClawColor
                        )
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) stringResource(R.string.openclaw_chat_stop) else stringResource(R.string.openclaw_chat_mic),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    ActionButton(
                        icon = Icons.Default.Keyboard,
                        label = stringResource(R.string.openclaw_chat_text),
                        enabled = true,
                        onClick = { viewModel.toggleTextInput() }
                    )
                }

                if (showTextInput) {
                    Spacer(modifier = Modifier.height(AppSpacing.small))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { viewModel.onInputChanged(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.openclaw_chat_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (isConnected) viewModel.sendText() })
                        )
                        IconButton(onClick = { viewModel.sendText() }, enabled = inputText.isNotBlank() && isConnected) {
                            Icon(Icons.Default.Send, contentDescription = stringResource(R.string.openclaw_chat_sendvoice), tint = OpenClawColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.4f))
    }
}

@Composable
private fun ChatBubble(message: OpenClawChatMessage) {
    val isUser = message.role == OpenClawChatMessage.ROLE_USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(AppRadius.large))
                .then(
                    if (isUser) Modifier.background(Brush.linearGradient(listOf(OpenClawColor, OpenClawColorEnd)))
                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                )
                .padding(AppSpacing.medium)
        ) {
            message.image?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 200.dp, height = 150.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                if (message.text.isNotEmpty()) Spacer(modifier = Modifier.height(AppSpacing.small))
            }
            if (message.text.isNotEmpty()) {
                Text(
                    text = message.text,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                )
            }
        }
    }
}
```

- [ ] **Step 6.8: Create `OpenClawSettingsScreen.kt`**

```kotlin
package com.smartview.glassai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartview.glassai.R
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawNodeService
import com.smartview.glassai.services.openclaw.OpenClawProtocol
import com.smartview.glassai.ui.components.openClawStatusColor
import com.smartview.glassai.ui.components.openClawStatusText
import com.smartview.glassai.ui.theme.AppRadius
import com.smartview.glassai.ui.theme.AppSpacing
import com.smartview.glassai.ui.theme.OpenClawColor
import com.smartview.glassai.ui.theme.Warning

/** Gateway host/port/scheme/token, status with pairing hint, connect/disconnect (research §5.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClawSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val service = remember { OpenClawNodeService.getInstance(context) }
    val connectionState by service.connectionState.collectAsState()

    var host by remember { mutableStateOf(service.gatewayHost) }
    var portText by remember { mutableStateOf(service.gatewayPort.toString()) }
    var scheme by remember { mutableStateOf(service.gatewayScheme) }
    var token by remember { mutableStateOf(service.loadGatewayToken() ?: "") }
    val isConnected = connectionState == OpenClawConnectionState.Connected

    fun saveAndConnect() {
        service.gatewayHost = host.trim()
        service.gatewayPort = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: OpenClawProtocol.DEFAULT_PORT
        service.gatewayScheme = scheme
        service.saveGatewayToken(token) // blank deletes
        // The explicit button dials now, even mid-backoff (auto-connect elsewhere never does).
        service.connect(force = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.openclaw_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { TextButton(onClick = onBackClick) { Text(stringResource(R.string.done)) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
        ) {
            SectionCard(title = stringResource(R.string.openclaw_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.openclaw_status_title), modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(openClawStatusColor(connectionState)))
                    Spacer(modifier = Modifier.width(AppSpacing.small))
                    Text(openClawStatusText(connectionState), color = openClawStatusColor(connectionState))
                }
                if (connectionState == OpenClawConnectionState.WaitingForPairing) {
                    Spacer(modifier = Modifier.height(AppSpacing.small))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.small))
                        Text(stringResource(R.string.openclaw_pairing_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionCard(title = stringResource(R.string.openclaw_gateway_section)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.openclaw_host)) },
                    placeholder = { Text(OpenClawProtocol.DEFAULT_HOST) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.small))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.openclaw_port)) },
                    placeholder = { Text(OpenClawProtocol.DEFAULT_PORT.toString()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.small))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(stringResource(R.string.openclaw_scheme), modifier = Modifier.weight(1f))
                    FilterChip(selected = scheme == OpenClawProtocol.SCHEME_WS, onClick = { scheme = OpenClawProtocol.SCHEME_WS }, label = { Text("ws://") })
                    FilterChip(selected = scheme == OpenClawProtocol.SCHEME_WSS, onClick = { scheme = OpenClawProtocol.SCHEME_WSS }, label = { Text("wss://") })
                }
                Spacer(modifier = Modifier.height(AppSpacing.small))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.openclaw_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.small))
                Text(stringResource(R.string.openclaw_gateway_help), style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(AppSpacing.medium))
                if (isConnected) {
                    OutlinedButton(
                        onClick = { service.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.openclaw_disconnect)) }
                } else {
                    Button(
                        onClick = { saveAndConnect() },
                        enabled = host.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenClawColor)
                    ) { Text(stringResource(R.string.openclaw_connect)) }
                }
            }

            SectionCard(title = stringResource(R.string.openclaw_capabilities)) {
                InfoRow(stringResource(R.string.openclaw_node_id), if (isConnected) service.nodeId else "-")
                Spacer(modifier = Modifier.height(AppSpacing.small))
                InfoRow(stringResource(R.string.openclaw_commands), OpenClawProtocol.COMMANDS.joinToString(", "))
                Spacer(modifier = Modifier.height(AppSpacing.small))
                Text(stringResource(R.string.openclaw_capabilities_desc), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = AppSpacing.small)
        )
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AppRadius.medium)) {
            Column(modifier = Modifier.padding(AppSpacing.medium)) { content() }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}
```

- [ ] **Step 6.9: Navigation routes**

In `Navigation.kt`, after `object MockDeviceKit : Screen("mock_device_kit")` add:

```kotlin
    object OpenClaw : Screen("openclaw")
    object OpenClawSettings : Screen("openclaw_settings")
```

In the `composable(Screen.Home.route)` block add a parameter to the `HomeScreen(...)` call after `onNavigateToRTMPStream = { … }`:

```kotlin
                    onNavigateToOpenClaw = {
                        navController.navigate(Screen.OpenClaw.route)
                    }
```

In the `composable(Screen.Settings.route)` block add after `onNavigateToMockDeviceKit = { … }`:

```kotlin
                    onNavigateToOpenClawSettings = {
                        navController.navigate(Screen.OpenClawSettings.route)
                    }
```

After the `composable(Screen.MockDeviceKit.route) { … }` block add:

```kotlin
            composable(Screen.OpenClaw.route) {
                OpenClawChatScreen(
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.OpenClawSettings.route)
                    }
                )
            }

            composable(Screen.OpenClawSettings.route) {
                OpenClawSettingsScreen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }
```

- [ ] **Step 6.10: Home card + auto-connect**

In `HomeScreen.kt`:

1. Add the parameter after `onNavigateToRTMPStream: () -> Unit = {}`:
```kotlin
    onNavigateToOpenClaw: () -> Unit = {}
```
2. Add imports:
```kotlin
import androidx.compose.material.icons.filled.Link
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawNodeService
```
3. After `val isDatAppUpdateRequired by wearablesViewModel.isDatAppUpdateRequired.collectAsState()` add:
```kotlin
    // OpenClaw: card subtitle shows the live state; auto-connect once a token is stored
    // (iOS TurboMetaHomeView.onAppear semantics; there is no openclaw_enabled flag).
    val openClawService = remember { OpenClawNodeService.getInstance(context) }
    val openClawState by openClawService.connectionState.collectAsState()
    LaunchedEffect(Unit) {
        if (openClawService.connectionState.value == OpenClawConnectionState.Disconnected &&
            !openClawService.loadGatewayToken().isNullOrBlank()
        ) {
            openClawService.connect()
        }
    }
```
4. Replace the whole WordLearn `FeatureCard(...)` (from `FeatureCard(` with `title = stringResource(R.string.feature_wordlearn_title)` through its closing `)`) with:
```kotlin
                    FeatureCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.feature_openclaw_title),
                        subtitle = if (openClawState == OpenClawConnectionState.Connected)
                            stringResource(R.string.feature_openclaw_connected)
                        else
                            stringResource(R.string.feature_openclaw_subtitle),
                        icon = Icons.Default.Link,
                        gradientColors = listOf(OpenClawColor, OpenClawColorEnd),
                        onClick = onNavigateToOpenClaw
                    )
```
and change the comment `// Row 2: LeanEat + WordLearn` to `// Row 2: LeanEat + OpenClaw`. Remove the now-unused import `androidx.compose.material.icons.automirrored.filled.MenuBook` (the `WordLearnColor` value stays in Color.kt; the `feature_wordlearn_*` strings stay for `records_wordlearn` compatibility).

- [ ] **Step 6.11: Settings "Integrations" section**

In `SettingsScreen.kt`:

1. Add the parameter after `onNavigateToMockDeviceKit: () -> Unit = {}`:
```kotlin
    onNavigateToOpenClawSettings: () -> Unit = {}
```
2. Add imports:
```kotlin
import com.smartview.glassai.services.openclaw.OpenClawNodeService
import com.smartview.glassai.ui.components.openClawStatusColor
import com.smartview.glassai.ui.components.openClawStatusText
```
3. After `val context = LocalContext.current` (first line of the body) add:
```kotlin
    val openClawState by remember { OpenClawNodeService.getInstance(context) }.connectionState.collectAsState()
```
4. Immediately before the `// Data Section` comment add:
```kotlin
            // Integrations Section (Phase B: OpenClaw)
            SettingsSection(title = stringResource(R.string.settings_integrations)) {
                SettingsItem(
                    icon = Icons.Default.Link,
                    title = stringResource(R.string.openclaw_title),
                    subtitle = openClawStatusText(openClawState),
                    subtitleColor = openClawStatusColor(openClawState),
                    onClick = onNavigateToOpenClawSettings
                )
            }

```
(`Icons.Default.Link` resolves through the existing `androidx.compose.material.icons.filled.*` import.)

- [ ] **Step 6.12: Build, run the whole unit suite, commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest 2>&1 | tail -25
```
Expected: `BUILD SUCCESSFUL`; at least 130 unit tests pass (36 Phase A + T1 21 + T2 13 + T3 26 + T4 14 + T5 8 + T6 12).

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/src/main android/app/src/test && git commit -m "feat(android): OpenClaw chat + settings screens, OpenClawViewModel (snap & send, Fun-ASR voice, phone/glasses mic), Home card, Settings Integrations, cleartext ws:// config, en/zh strings"
```

---

### Task 7: B2 stability — RTMP error residual + first-frame timeout + encrypted stream key + bitrate, shared OkHttpClient + WebSocket cleanup, aggregate capture budget, mic re-check, hygiene

**Files:**
- Modify: `app/src/main/java/com/smartview/glassai/services/RTMPStreamingService.kt` (anchors: `override fun onDisconnectRtmp()`, `private fun initEncoder(width: Int, height: Int, bitrate: Int): Boolean`, `private fun startEncoderOutputProcessing()`, `fun stopStreaming()`, `fun release()`, `private val encoderLock = ReentrantLock(true)`)
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` (full replacement)
- Create: `app/src/main/java/com/smartview/glassai/utils/RtmpUrlSplitter.kt`
- Modify: `app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt` (anchors: `private const val KEY_RTMP_URL = "rtmp_url"`, `init {`, `// MARK: - OpenClaw (Phase B)`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/RTMPStreamingScreen.kt` (anchors: `// RTMP URL display (truncated)`, `// Settings Dialog`, `private fun RTMPSettingsDialog(`, `text = "Error",`, `Text("Dismiss", color = Color.White)`)
- Modify: `app/src/main/java/com/smartview/glassai/services/OmniRealtimeService.kt`, `GeminiLiveService.kt` (anchors: `private val client = OkHttpClient.Builder()`, `webSocket = client.newWebSocket(request, object : WebSocketListener() {`, `fun disconnect() {`)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/GlassesPhotoCapturer.kt` (anchors: `object NoImage : PhotoCaptureOutcome<Nothing>()`, `const val DEFAULT_FALLBACK_FRAME_TIMEOUT_MS = 2_000L`, `private val fallbackFrameTimeoutMs: Long = DEFAULT_FALLBACK_FRAME_TIMEOUT_MS,`, `return borrowCameraAndCapture()`)
- Modify: `app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` (anchor: `PhotoCaptureOutcome.NoImage -> {`)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/GlassesFrameProvider.kt` (anchor: `PhotoCaptureOutcome.NoImage -> SnapshotResult.NoFrame`)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt` (anchors: `private fun clearStopping(outgoing: GlassesSession)`, `clearStopping(current)`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/LiveAIScreen.kt` (anchor: `val micPermissionLauncher = rememberLauncherForActivityResult(`)
- Modify: `app/src/main/java/com/smartview/glassai/MainActivity.kt` (anchors: `initializeSDK`, `sdkInitialized`)
- Modify: strings (both files)
- Create: `app/src/test/java/com/smartview/glassai/utils/RtmpUrlSplitterTest.kt`; Modify: `app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` (append), `GlassesSessionManagerTest.kt` (replace `awaitStartedResolvesTrueOnStartedAndFalseOnStopped`)

**Interfaces:**
- Produces `object RtmpUrlSplitter { fun split(fullUrl: String): Pair<String, String>; fun join(serverUrl: String, streamKey: String): String }`
- Produces on `APIKeyManager`: `getRtmpStreamKey(): String?`, `saveRtmpStreamKey(key: String)`, `deleteRtmpStreamKey()`, `getRtmpBitrate(): Int`, `saveRtmpBitrate(bitrate: Int)`, `@VisibleForTesting internal fun rerunRtmpMigrationForTests()`
- Produces on `RTMPStreamingService`: `onDisconnectRtmp` emits `Disconnected` only while `isStreaming`; `initEncoder` releases a codec whose `configure()`/`start()` threw; the output loop exits on a codec error and reports `StreamingState.Error("Encoder failed: …")`; `stopStreaming()` is serialized on `stopLock`, swaps `rtmpClient` to null before disconnecting (exactly one `disconnect()` per client) and runs `RtmpClient.disconnect()` on a dedicated `rtmp-disconnect` thread, never on the caller's (Main) thread
- Produces on `RTMPStreamingViewModel`: `val streamKey: StateFlow<String>`, `fun updateStreamKey(key: String)`, `fun updateRtmpUrl(url: String)` (server URL only), `val bitrate` persisted; first-frame timeout `FIRST_FRAME_TIMEOUT_MS = 10_000L`
- Produces `PhotoCaptureOutcome.Timeout`, `GlassesPhotoCapturer(... totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS)` with `DEFAULT_TOTAL_BUDGET_MS = 15_000L`
- Consumes `HttpClients.websocket` (Task 6)

- [ ] **Step 7.1: Strings for RTMP (both files)**

Append to `values/strings.xml` before `</resources>`:

```xml

    <!-- Phase B Task 7: RTMP settings dialog and errors -->
    <string name="rtmp_settings_title">RTMP Settings</string>
    <string name="rtmp_server_url">Server URL</string>
    <string name="rtmp_stream_key">Stream key</string>
    <string name="rtmp_bitrate">Bitrate</string>
    <string name="rtmp_bitrate_note">Note: higher bitrate = better quality but needs more bandwidth</string>
    <string name="rtmp_disconnected">Disconnected from server</string>
    <string name="rtmp_first_frame_timeout">No video from the glasses within 10 seconds</string>
    <string name="rtmp_connect_failed">Failed to connect to RTMP server</string>
```

Append to `values-zh-rCN/strings.xml` before `</resources>`:

```xml

    <!-- Phase B Task 7: RTMP 设置对话框与错误 -->
    <string name="rtmp_settings_title">RTMP 设置</string>
    <string name="rtmp_server_url">服务器地址</string>
    <string name="rtmp_stream_key">推流密钥</string>
    <string name="rtmp_bitrate">码率</string>
    <string name="rtmp_bitrate_note">提示：码率越高画质越好，但需要更多带宽</string>
    <string name="rtmp_disconnected">与服务器断开连接</string>
    <string name="rtmp_first_frame_timeout">10 秒内未收到眼镜画面</string>
    <string name="rtmp_connect_failed">连接 RTMP 服务器失败</string>
```

- [ ] **Step 7.2: Write the failing `RtmpUrlSplitterTest`**

Create `app/src/test/java/com/smartview/glassai/utils/RtmpUrlSplitterTest.kt`:

```kotlin
package com.smartview.glassai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpUrlSplitterTest {

    @Test
    fun splitsAppAndKey() {
        assertEquals("rtmp://a.example.com/live" to "abc-123", RtmpUrlSplitter.split("rtmp://a.example.com/live/abc-123"))
        assertEquals("rtmp://localhost/live" to "stream", RtmpUrlSplitter.split("rtmp://localhost/live/stream"))
        assertEquals("rtmps://live.x.com:443/app/sub" to "k", RtmpUrlSplitter.split("rtmps://live.x.com:443/app/sub/k"))
    }

    @Test
    fun singleSegmentOrNoPathHasNoKey() {
        assertEquals("rtmp://a.example.com/live" to "", RtmpUrlSplitter.split("rtmp://a.example.com/live"))
        assertEquals("rtmp://a.example.com" to "", RtmpUrlSplitter.split("rtmp://a.example.com/"))
        assertEquals("rtmp://a.example.com" to "", RtmpUrlSplitter.split("rtmp://a.example.com"))
        assertEquals("" to "", RtmpUrlSplitter.split("   "))
    }

    @Test
    fun joinRoundTrips() {
        assertEquals("rtmp://a.example.com/live/abc", RtmpUrlSplitter.join("rtmp://a.example.com/live", "abc"))
        assertEquals("rtmp://a.example.com/live/abc", RtmpUrlSplitter.join("rtmp://a.example.com/live/", " abc "))
        assertEquals("rtmp://a.example.com/live", RtmpUrlSplitter.join("rtmp://a.example.com/live", ""))
        val (server, key) = RtmpUrlSplitter.split("rtmp://a.example.com/live/abc")
        assertEquals("rtmp://a.example.com/live/abc", RtmpUrlSplitter.join(server, key))
    }
}
```

- [ ] **Step 7.3: Run and watch it fail; create `RtmpUrlSplitter.kt`**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.utils.RtmpUrlSplitterTest" 2>&1 | tail -10
```
Expected: `BUILD FAILED` (unresolved `RtmpUrlSplitter`). Then create:

```kotlin
package com.smartview.glassai.utils

/**
 * Splits the pre-2.0 single `rtmp_url` (`rtmp://host/app/streamkey`) into the server URL and the
 * stream key (iOS 2.0 stability fix 4.7: the key moves to encrypted storage and is never shown).
 * Rule: the last path segment is the key only when the path has at least two segments.
 */
object RtmpUrlSplitter {
    fun split(fullUrl: String): Pair<String, String> {
        val trimmed = fullUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "" to ""
        val schemeEnd = trimmed.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = trimmed.indexOf('/', authorityStart)
        if (pathStart < 0) return trimmed to ""
        val segments = trimmed.substring(pathStart + 1).split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return trimmed to ""
        val key = segments.last()
        val server = trimmed.substring(0, trimmed.length - key.length - 1)
        return server to key
    }

    fun join(serverUrl: String, streamKey: String): String {
        val server = serverUrl.trim().trimEnd('/')
        val key = streamKey.trim()
        return if (key.isEmpty()) server else "$server/$key"
    }
}
```

Run again: expected `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 7.4: `APIKeyManager`: stream key, bitrate, migration**

After `private const val KEY_RTMP_URL = "rtmp_url"` add:

```kotlin
        private const val KEY_RTMP_STREAM_KEY = "rtmp_stream_key"
        private const val KEY_RTMP_BITRATE = "rtmp_bitrate"
        private const val KEY_RTMP_SPLIT_MIGRATED = "rtmp_split_migrated_v2"
        const val DEFAULT_RTMP_BITRATE = 2_000_000
```

In `init { migrateLegacyKey() }` add a second call: `migrateRtmpUrl()`. Add after `migrateLegacyKey()`'s definition:

```kotlin
    /**
     * 2.0.0: the single rtmp_url used to embed the stream key. Split it once into rtmp_url
     * (server) + rtmp_stream_key so the key is never rendered on screen.
     */
    private fun migrateRtmpUrl() {
        try {
            if (sharedPreferences.getBoolean(KEY_RTMP_SPLIT_MIGRATED, false)) return
            val full = sharedPreferences.getString(KEY_RTMP_URL, null)
            val editor = sharedPreferences.edit().putBoolean(KEY_RTMP_SPLIT_MIGRATED, true)
            if (!full.isNullOrBlank() && sharedPreferences.getString(KEY_RTMP_STREAM_KEY, null).isNullOrBlank()) {
                val (server, key) = RtmpUrlSplitter.split(full)
                editor.putString(KEY_RTMP_URL, server)
                if (key.isNotEmpty()) editor.putString(KEY_RTMP_STREAM_KEY, key)
                Log.i(TAG, "Migrated rtmp_url into server URL + stream key")
            }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "RTMP migration error: ${e.message}")
        }
    }

    /**
     * Instrumented-test hook (Task 9): forgets that the split ran and runs it again, so the
     * 1.5.0 -> 2.0.0 upgrade path can be exercised on a device without reinstalling.
     */
    @VisibleForTesting
    internal fun rerunRtmpMigrationForTests() {
        sharedPreferences.edit().remove(KEY_RTMP_SPLIT_MIGRATED).apply()
        migrateRtmpUrl()
    }
```

Add `import androidx.annotation.VisibleForTesting` to `APIKeyManager.kt`.

Before `// MARK: - OpenClaw (Phase B)` add:

```kotlin
    // RTMP stream key (secret; encrypted like API keys) and persisted bitrate
    fun getRtmpStreamKey(): String? = try {
        sharedPreferences.getString(KEY_RTMP_STREAM_KEY, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read RTMP stream key: ${e.message}")
        null
    }

    fun saveRtmpStreamKey(key: String) {
        if (key.isBlank()) deleteRtmpStreamKey() else sharedPreferences.edit().putString(KEY_RTMP_STREAM_KEY, key.trim()).apply()
    }

    fun deleteRtmpStreamKey() {
        sharedPreferences.edit().remove(KEY_RTMP_STREAM_KEY).apply()
    }

    fun getRtmpBitrate(): Int {
        val value = sharedPreferences.getInt(KEY_RTMP_BITRATE, 0)
        return if (value > 0) value else DEFAULT_RTMP_BITRATE
    }

    fun saveRtmpBitrate(bitrate: Int) {
        sharedPreferences.edit().putInt(KEY_RTMP_BITRATE, bitrate).apply()
    }
```

- [ ] **Step 7.5: `RTMPStreamingService`: `Disconnected` only for a live drop; codec leak, busy-spin, single off-main disconnect (ledger T6)**

`RtmpClient.disconnect()` (rtmp 2.2.6) invokes `onDisconnectRtmp()` **synchronously** from inside `stopStreaming()`. `stopStreaming()` clears `isStreaming` as its first statement and sets `Idle` only at its end, so a state-based guard (`Streaming`/`Connecting`) would still turn every user Stop into `Disconnected` → a persistent "Disconnected from server" card. The flag is the correct guard: by the time the callback runs, a user stop and the `onConnectionFailedRtmp → Error → stopStreaming()` path both have `isStreaming == false`; only an unexpected server-side drop while still streaming reaches `Disconnected`.

1. Replace `override fun onDisconnectRtmp()` with:

```kotlin
                override fun onDisconnectRtmp() {
                    Log.d(TAG, "RTMP disconnected")
                    // Invoked synchronously by RtmpClient.disconnect(), i.e. from inside
                    // stopStreaming() after a user Stop or after onConnectionFailedRtmp() set
                    // Error. isStreaming is cleared as the first statement of stopStreaming(), so
                    // only an unexpected server-side drop of a live stream reaches Disconnected;
                    // a user stop ends in Idle and a connection failure keeps its Error.
                    if (isStreaming) _state.value = StreamingState.Disconnected
                }
```

2. Replace the whole `private fun initEncoder(width: Int, height: Int, bitrate: Int): Boolean { … }` with (a codec whose `configure()`/`start()` threw used to stay in `encoder` unreleased):

```kotlin
    /**
     * Initialize H.264 encoder using MediaCodec
     */
    private fun initEncoder(width: Int, height: Int, bitrate: Int): Boolean {
        var codec: MediaCodec? = null
        try {
            // Find encoder for H.264
            codec = MediaCodec.createEncoderByType(MIME_TYPE)

            // Use YUV420Planar (I420) format to match DAT SDK output
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                )
                // Lower latency encoding
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec

            Log.d(TAG, "H.264 encoder initialized: ${width}x${height} (I420/YUV420Planar)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder: ${e.message}", e)
            // configure()/start() threw: release the codec instead of leaking it (ledger T6)
            runCatching { codec?.release() }
            encoder = null
            return false
        }
    }
```

3. Replace the whole `private fun startEncoderOutputProcessing() { … }` with (a codec in the error state throws on every `dequeueOutputBuffer`, and the old loop spun on it at full speed until the user stopped):

```kotlin
    /**
     * Process encoder output and send to RTMP
     */
    private fun startEncoderOutputProcessing() {
        encoderJob = scope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()
            var failure: Exception? = null

            while (isStreaming && failure == null) {
                encoderLock.withLock {
                    try {
                        val outputIndex = encoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1

                        when {
                            outputIndex >= 0 -> {
                                val outputBuffer = encoder?.getOutputBuffer(outputIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    // Check for codec config (SPS/PPS)
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        extractSpsPps(outputBuffer, bufferInfo.size)
                                    } else {
                                        // Send H.264 data to RTMP
                                        sendH264Data(outputBuffer, bufferInfo)
                                    }
                                }
                                encoder?.releaseOutputBuffer(outputIndex, false)
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                Log.d(TAG, "Encoder output format changed: ${encoder?.outputFormat}")
                            }
                        }
                    } catch (e: Exception) {
                        // A codec in the error state throws on every dequeue: leave the loop
                        // instead of spinning on it (ledger T6). Exceptions during teardown
                        // (isStreaming already false) are expected and ignored.
                        if (isStreaming) failure = e
                    }
                }
            }

            failure?.let { e ->
                Log.e(TAG, "Encoder output error: ${e.message}", e)
                _state.value = StreamingState.Error("Encoder failed: ${e.message ?: e.javaClass.simpleName}")
                stopStreaming() // keeps the Error (see the guard at its end)
            }
        }
    }
```

4. After `private val encoderLock = ReentrantLock(true)` (keep its comment block) add:

```kotlin
    // Serializes stopStreaming() — user Stop on Main, onConnectionFailedRtmp() on the RTMP thread,
    // the encoder loop on IO, release() — so the client is disconnected exactly once (ledger T6:
    // double disconnect).
    private val stopLock = Any()

    // RtmpClient.disconnect() does socket I/O and must not run on the caller's (often Main)
    // thread (ledger T6). One thread keeps disconnects ordered; release() drains and stops it.
    private val disconnectExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rtmp-disconnect") }
```

and add `import java.util.concurrent.Executors` and `import java.util.concurrent.RejectedExecutionException`.

5. Replace the whole `fun stopStreaming() { … }` and `fun release() { … }` with:

```kotlin
    /**
     * Stop streaming and release resources. Safe to call from any thread and any number of times.
     */
    fun stopStreaming() {
        synchronized(stopLock) {
            Log.d(TAG, "Stopping RTMP streaming")
            isStreaming = false

            // Stop encoder processing
            encoderJob?.cancel()
            encoderJob = null

            // Stop and release encoder under encoderLock, so release() can never run while the frame
            // worker or the output loop is inside a codec call on the same encoder.
            encoderLock.withLock {
                try {
                    encoder?.stop()
                    encoder?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping encoder: ${e.message}")
                }
                encoder = null
            }

            // Swap first so a second stopStreaming() sees null: exactly one disconnect per client.
            // The disconnect itself (socket I/O, and it invokes onDisconnectRtmp synchronously)
            // runs on the rtmp-disconnect thread, never on Main.
            val client = rtmpClient
            rtmpClient = null
            if (client != null) {
                try {
                    disconnectExecutor.execute {
                        try {
                            client.disconnect()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error disconnecting RTMP: ${e.message}")
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    // release() already shut the executor down: last resort, disconnect inline
                    runCatching { client.disconnect() }
                }
            }

            // Clear SPS/PPS
            sps = null
            pps = null

            // Reset frame counters and timestamp smoothing
            totalFrames = 0
            droppedFrames = 0
            lastLogTime = 0
            baseTimestampUs = 0
            frameIndex = 0

            // Keep a connection/auth/encoder failure visible: those paths set Error and then call
            // stopStreaming(), and StateFlow conflates, so overwriting it here made the Main
            // collector see only Idle. The next startStreaming() moves the state on to Connecting.
            if (_state.value !is StreamingState.Error) {
                _state.value = StreamingState.Idle
            }
            Log.d(TAG, "RTMP streaming stopped")
        }
    }
```

```kotlin
    /**
     * Release all resources
     */
    fun release() {
        stopStreaming()
        disconnectExecutor.shutdown() // a queued disconnect still runs; nothing new is accepted
        scope.cancel()
    }
```

6. Delete the unused `fun feedFrame(i420Data: ByteArray, width: Int, height: Int, timestampUs: Long)` overload (the one taking `ByteArray`; keep the `ByteBuffer` overload) — Phase A review Minor #14.

- [ ] **Step 7.6: Replace `RTMPStreamingViewModel.kt` (server URL + key, persisted bitrate, first-frame timeout)**

```kotlin
package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.FrameConversions
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesErrorMessages
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.services.RTMPStreamingService
import com.smartview.glassai.utils.APIKeyManager
import com.smartview.glassai.utils.RtmpUrlSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTMPStreamingViewModel - Manages RTMP streaming from glasses camera
 *
 * Borrows the camera from the shared GlassesSessionManager (DAT 0.9.0) and feeds raw I420 frames
 * to RTMPStreamingService for live broadcasting. 2.0: the server URL and the stream key are two
 * persisted fields (the key is never rendered), the bitrate is persisted, and a first-frame
 * timeout stops "Connecting" from lasting forever when the glasses never deliver video.
 */
class RTMPStreamingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RTMPStreamingVM"
        private const val OWNER = "RTMPStreamingViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        const val DEFAULT_RTMP_URL = "rtmp://localhost/live"
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

    /** Server URL only (`rtmp://host/app`); the key is appended when connecting. */
    private val _rtmpUrl = MutableStateFlow(DEFAULT_RTMP_URL)
    val rtmpUrl: StateFlow<String> = _rtmpUrl.asStateFlow()

    private val _streamKey = MutableStateFlow("")
    val streamKey: StateFlow<String> = _streamKey.asStateFlow()

    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> = _previewFrame.asStateFlow()

    private val _streamStats = MutableStateFlow(RTMPStreamingService.StreamingStats())
    val streamStats: StateFlow<RTMPStreamingService.StreamingStats> = _streamStats.asStateFlow()

    private val _cameraState = MutableStateFlow<DatStreamState?>(null)
    val cameraState: StateFlow<DatStreamState?> = _cameraState.asStateFlow()

    private val _bitrate = MutableStateFlow(APIKeyManager.DEFAULT_RTMP_BITRATE)
    val bitrate: StateFlow<Int> = _bitrate.asStateFlow()

    // Services
    private val rtmpService = RTMPStreamingService(application)
    private val apiKeyManager = APIKeyManager.getInstance(application)
    private val sessionManager: GlassesSessionManager by lazy {
        GlassesSessionManager.getInstance(application)
    }

    // Borrowed camera + jobs
    private var camera: GlassesCamera? = null

    // Single-threaded worker for frame handling (never the main thread), like the 0.9.0 sample
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

    // RTMP drop policy (spec §5.8): a frame arriving while the previous one is still being copied,
    // encoded and previewed is skipped. The encoder still sees every accepted frame in order.
    private val isProcessingFrame = AtomicBoolean(false)

    private var startJob: Job? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var statsJob: Job? = null
    private var firstFrameJob: Job? = null

    // Video parameters (set when stream starts).
    // @Volatile: written by the frame worker on frameDispatcher, read and reset on Main.
    @Volatile
    private var videoWidth = 0
    @Volatile
    private var videoHeight = 0
    @Volatile
    private var frameTimestampBase = 0L

    init {
        apiKeyManager.getRtmpUrl()?.takeIf { it.isNotBlank() }?.let { _rtmpUrl.value = it }
        apiKeyManager.getRtmpStreamKey()?.let { _streamKey.value = it }
        _bitrate.value = apiKeyManager.getRtmpBitrate()

        // Observe RTMP service state
        viewModelScope.launch {
            rtmpService.state.collect { state ->
                when (state) {
                    is RTMPStreamingService.StreamingState.Idle -> {
                        // Never downgrade a visible error: onConnectionFailedRtmp() sets Error and
                        // then calls stopStreaming(), so Idle follows an Error within milliseconds
                        // on the RTMP thread. startStreaming() moves the state on to Connecting and
                        // the Stop button calls clearError() first, so Error is still recoverable.
                        // A user Stop therefore ends here: service Idle -> UI Idle, no card.
                        if (_uiState.value != UIState.Idle && _uiState.value !is UIState.Error) {
                            _uiState.value = UIState.Idle
                        }
                    }
                    is RTMPStreamingService.StreamingState.Connecting -> {
                        _uiState.value = UIState.Connecting
                    }
                    is RTMPStreamingService.StreamingState.Streaming -> {
                        firstFrameJob?.cancel()
                        _uiState.value = UIState.Streaming
                    }
                    is RTMPStreamingService.StreamingState.Error -> {
                        _uiState.value = UIState.Error(state.message)
                    }
                    is RTMPStreamingService.StreamingState.Disconnected -> {
                        // The service only emits this while isStreaming (an unexpected drop of a
                        // live stream); the VM double-checks so a late callback after a user Stop
                        // (already Idle) or after a failure (already Error) can never overwrite them.
                        if (_uiState.value == UIState.Streaming) {
                            _uiState.value = UIState.Error(getApplication<Application>().getString(R.string.rtmp_disconnected))
                        }
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

    /** Saves the server URL (without the key). */
    fun updateRtmpUrl(url: String) {
        val trimmed = url.trim()
        _rtmpUrl.value = trimmed
        apiKeyManager.saveRtmpUrl(trimmed)
    }

    /** Saves the stream key into encrypted storage; blank deletes it. */
    fun updateStreamKey(key: String) {
        val trimmed = key.trim()
        _streamKey.value = trimmed
        apiKeyManager.saveRtmpStreamKey(trimmed)
    }

    fun updateBitrate(newBitrate: Int) {
        _bitrate.value = newBitrate
        apiKeyManager.saveRtmpBitrate(newBitrate)
    }

    /** The URL actually pushed to: server + "/" + key. Never log or render this. */
    private fun fullRtmpUrl(): String = RtmpUrlSplitter.join(_rtmpUrl.value, _streamKey.value)

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
        // A previous UIState.Error is cleared here (it is now kept until the user acts on it).
        _uiState.value = UIState.Connecting

        // Start DAT SDK camera stream first
        startCameraStream()
    }

    private fun startCameraStream() {
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)

        val videoQuality = WearablesViewModel.videoQualityFromSetting(apiKeyManager.getVideoQuality())
        Log.d(TAG, "Starting camera stream with quality: $videoQuality")

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
                        armFirstFrameTimeout()
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
                _uiState.value = UIState.Error(GlassesErrorMessages.of(getApplication(), error))
            }
        }

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

        val startError = borrowed.startStream()
        if (startError != null) {
            failCamera(GlassesErrorMessages.of(getApplication(), startError))
        }
    }

    /**
     * The glasses report STREAMING but may never deliver a decodable frame (ledger T5: RTMP could
     * stick in Connecting with no stream budget). Fail after FIRST_FRAME_TIMEOUT_MS unless a frame
     * arrived (videoWidth != 0) or the RTMP service already moved on.
     */
    private fun armFirstFrameTimeout() {
        firstFrameJob?.cancel()
        firstFrameJob = viewModelScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            if (videoWidth == 0 && _uiState.value == UIState.Connecting) {
                Log.e(TAG, "no video frame within ${FIRST_FRAME_TIMEOUT_MS}ms")
                rtmpService.stopStreaming()
                failCamera(getApplication<Application>().getString(R.string.rtmp_first_frame_timeout))
            }
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

    private fun cameraErrorMessage(error: CameraError): String =
        GlassesErrorMessages.of(getApplication(), error)

    private fun connectRtmp() {
        viewModelScope.launch {
            val success = rtmpService.startStreaming(
                rtmpUrl = fullRtmpUrl(),
                width = videoWidth,
                height = videoHeight,
                bitrate = _bitrate.value
            )

            if (!success) {
                Log.e(TAG, "Failed to connect RTMP")
                _uiState.value = UIState.Error(getApplication<Application>().getString(R.string.rtmp_connect_failed))
            }
        }
    }

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        // Copy the SDK buffer FIRST: VideoFrame.buffer is only guaranteed valid inside collect {}
        // (spec §5.8). Everything below works on our own copy.
        val i420 = FrameConversions.copyI420(videoFrame) ?: return
        val width = videoFrame.width
        val height = videoFrame.height

        // Set video dimensions on first frame and connect RTMP
        if (videoWidth == 0 || videoHeight == 0) {
            // Use original dimensions - modern MediaCodec handles alignment internally
            videoWidth = width
            videoHeight = height
            firstFrameJob?.cancel()
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

        // Also update preview (convert to bitmap for display) from the same copy, and publish it as
        // the manager's latestFrame: RTMP is a long-lived camera owner, and an OpenClaw camera.snap
        // during a broadcast must return the live frame instead of NO_FRAME (publishFrame is the
        // documented off-main exception in GlassesSessionManager).
        val bitmap = FrameConversions.i420ToBitmap(i420, width, height, FrameConversions.PREVIEW_JPEG_QUALITY)
        if (bitmap != null) {
            _previewFrame.value = bitmap
            sessionManager.publishFrame(OWNER, bitmap)
        }
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
        firstFrameJob?.cancel()
        firstFrameJob = null
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
        // A stream error must stay visible: the STOPPED transition that normally follows a stream
        // error (attachCamera's stateJob, hasBeenActive branch) must not blink the error away by
        // falling back to Idle here. An explicit user-initiated stop clears the error first (see
        // RTMPStreamingScreen's Stop button), so that path still reaches Idle.
        if (_uiState.value !is UIState.Error) {
            _uiState.value = UIState.Idle
        }
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

- [ ] **Step 7.7: `RTMPStreamingScreen`: stream-key field, never render the key, localized dialog**

1. Add after `val bitrate by viewModel.bitrate.collectAsState()`:
```kotlin
    val streamKey by viewModel.streamKey.collectAsState()
```
2. The `// RTMP URL display (truncated)` `Text(...)` keeps rendering `rtmpUrl` — it is now the server URL only, so nothing else changes there.
3. Replace the `// Settings Dialog` block with:
```kotlin
    // Settings Dialog
    if (showSettingsDialog) {
        RTMPSettingsDialog(
            currentUrl = rtmpUrl,
            currentKey = streamKey,
            currentBitrate = bitrate,
            onSave = { url, key, newBitrate ->
                viewModel.updateRtmpUrl(url)
                viewModel.updateStreamKey(key)
                viewModel.updateBitrate(newBitrate)
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
```
4. Replace the whole `private fun RTMPSettingsDialog(...)` composable with:
```kotlin
@Composable
private fun RTMPSettingsDialog(
    currentUrl: String,
    currentKey: String,
    currentBitrate: Int,
    onSave: (url: String, key: String, bitrate: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(currentUrl) }
    var keyText by remember { mutableStateOf(currentKey) }
    var selectedBitrate by remember { mutableStateOf(currentBitrate) }

    val bitrateOptions = listOf(
        500_000 to "500 kbps",
        1_000_000 to "1 Mbps",
        2_000_000 to "2 Mbps",
        4_000_000 to "4 Mbps",
        6_000_000 to "6 Mbps"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rtmp_settings_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text(stringResource(R.string.rtmp_server_url)) },
                    placeholder = { Text("rtmp://server.com/live") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                // The key is a secret: password transform, stored encrypted, never rendered elsewhere
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.rtmp_stream_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                Text(
                    text = stringResource(R.string.rtmp_bitrate),
                    style = MaterialTheme.typography.labelMedium
                )

                Column {
                    bitrateOptions.forEach { (bitrate, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedBitrate == bitrate,
                                    onClick = { selectedBitrate = bitrate },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedBitrate == bitrate,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.rtmp_bitrate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(urlText, keyText, selectedBitrate)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
```
5. Add `import androidx.compose.ui.text.input.PasswordVisualTransformation`. In the error card replace `text = "Error",` with `text = stringResource(R.string.error),` and `Text("Dismiss", color = Color.White)` with `Text(stringResource(R.string.close), color = Color.White)`. Delete the dead `val cameraState by viewModel.cameraState.collectAsState()` line (ledger T5).

- [ ] **Step 7.8: Shared `OkHttpClient` + weak-ref listeners + disconnect cleanup in `OmniRealtimeService` and `GeminiLiveService`**

`HttpClients.kt` exists since Task 6. In **`OmniRealtimeService.kt`**:

1. Replace
```kotlin
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
```
with
```kotlin
    // One process-wide client (iOS 2.0 fix 4.1): no per-instance Dispatcher/ConnectionPool leak.
    private val client: OkHttpClient
        get() = HttpClients.websocket
```
2. Replace the whole `webSocket = client.newWebSocket(request, object : WebSocketListener() { … })` expression with
```kotlin
        webSocket = client.newWebSocket(request, SocketListener(this))
```
and add these members to the class (after `fun disconnect()`):
```kotlin
    internal fun onSocketOpen() {
        Log.d(TAG, "WebSocket connected")
        _isConnected.value = true
        sendSessionUpdate()
    }

    internal fun onSocketFailure(t: Throwable) {
        Log.e(TAG, "WebSocket error: ${t.message}")
        _isConnected.value = false
        _errorMessage.value = t.message
        onError?.invoke(t.message ?: "Connection failed")
    }

    internal fun onSocketClosed(reason: String) {
        Log.d(TAG, "WebSocket closed: $reason")
        _isConnected.value = false
    }

    /**
     * Holds the service weakly (iOS 2.0 fix 4.2): a socket that outlives disconnect() must not
     * keep the service — and through its callbacks the ViewModel — alive.
     */
    private class SocketListener(service: OmniRealtimeService) : WebSocketListener() {
        private val ref = java.lang.ref.WeakReference(service)
        override fun onOpen(webSocket: WebSocket, response: Response) { ref.get()?.onSocketOpen() }
        override fun onMessage(webSocket: WebSocket, text: String) { ref.get()?.handleMessage(text) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ref.get()?.onSocketFailure(t) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ref.get()?.onSocketClosed(reason) }
    }
```
and change `private fun handleMessage(text: String)` to `internal fun handleMessage(text: String)`.
3. Replace `fun disconnect()` with
```kotlin
    fun disconnect() {
        stopRecording()
        stopAudioPlayback()
        val socket = webSocket
        webSocket = null
        // close() starts the handshake; cancel() releases the connection even if the server never
        // answers (the old code could keep a half-closed socket + reader thread alive).
        runCatching { socket?.close(1000, "User disconnected") }
        runCatching { socket?.cancel() }
        _isConnected.value = false
        _isRecording.value = false
        _isSpeaking.value = false
        pendingImageFrame = null
        synchronized(audioQueue) { audioQueue.clear() }
        bluetoothAudioManager?.cleanup()
        scope.cancel()
    }
```
4. Add `import com.smartview.glassai.services.HttpClients` is not needed (same package); remove the unused `java.util.concurrent.TimeUnit` import if nothing else uses it.

In **`GeminiLiveService.kt`**:

1. Replace
```kotlin
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
```
with
```kotlin
    // One process-wide client (iOS 2.0 fix 4.1); it already pings every 30 s, so the per-instance
    // pingInterval builder goes away with it.
    private val client: OkHttpClient
        get() = HttpClients.websocket
```
2. Replace the whole expression from `webSocket = client.newWebSocket(request, object : WebSocketListener() {` through its closing `})` (the block containing `onOpen`, `onMessage`, `onFailure`, `onClosed`) with
```kotlin
        webSocket = client.newWebSocket(request, SocketListener(this))
```
and add these members to the class (after `fun disconnect()`):
```kotlin
    internal fun onSocketOpen() {
        Log.d(TAG, "WebSocket connected")
        _isConnected.value = true
        configureSession()
    }

    internal fun onSocketFailure(t: Throwable) {
        Log.e(TAG, "WebSocket error: ${t.message}")
        _isConnected.value = false
        _errorMessage.value = t.message
        onError?.invoke(t.message ?: "Connection failed")
    }

    internal fun onSocketClosed(reason: String) {
        Log.d(TAG, "WebSocket closed: $reason")
        _isConnected.value = false
        isSessionConfigured = false
    }

    /**
     * Holds the service weakly (iOS 2.0 fix 4.2): a socket that outlives disconnect() must not
     * keep the service — and through its callbacks the ViewModel — alive.
     */
    private class SocketListener(service: GeminiLiveService) : WebSocketListener() {
        private val ref = java.lang.ref.WeakReference(service)
        override fun onOpen(webSocket: WebSocket, response: Response) { ref.get()?.onSocketOpen() }
        override fun onMessage(webSocket: WebSocket, text: String) { ref.get()?.handleServerEvent(text) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ref.get()?.onSocketFailure(t) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ref.get()?.onSocketClosed(reason) }
    }
```
and change `private fun handleServerEvent(text: String)` to `internal fun handleServerEvent(text: String)`.
3. Replace `fun disconnect()` with
```kotlin
    fun disconnect() {
        Log.d(TAG, "Disconnecting from Gemini Live")
        stopRecording()
        stopAudioPlayback()
        val socket = webSocket
        webSocket = null
        // close() starts the handshake; cancel() releases the connection even if the server never
        // answers (the old code could keep a half-closed socket + reader thread alive).
        runCatching { socket?.close(1000, "User disconnected") }
        runCatching { socket?.cancel() }
        _isConnected.value = false
        _isRecording.value = false
        _isSpeaking.value = false
        isSessionConfigured = false
        pendingImageFrame = null
        synchronized(audioQueue) { audioQueue.clear() }
        bluetoothAudioManager?.cleanup()
        scope.cancel()
    }
```
4. Remove the unused `java.util.concurrent.TimeUnit` import if nothing else in the file uses it (the compiler warns; `HttpClients` is in the same package, no import needed).

Acceptance for this step is not compile-only: Task 9 item 17 connects/disconnects Live AI three times and checks that the OkHttp thread count does not grow.

- [ ] **Step 7.9: Aggregate capture budget in `GlassesPhotoCapturer` (+ consumers)**

Write the failing test first — append to `GlassesPhotoCapturerTest.kt` inside the class:

```kotlin
    @Test
    fun captureGivesUpAfterTheTotalBudgetAndReleasesEverything() = runTest(dispatcher) {
        val manager = newManager()
        observer.device.value = rayban
        val capturer = GlassesPhotoCapturer(
            sessionManager = manager,
            owner = "QuickVisionService",
            config = config,
            decodePhoto = { "photo" },
            decodeFrame = { "frame" },
            frameDispatcher = dispatcher,
            totalBudgetMs = 5_000L,
        )
        // The session never reaches STARTED: the per-step budget (12 s) is longer than the total.
        val result = async { capturer.capture() }
        advanceTimeBy(5_001)

        assertEquals(PhotoCaptureOutcome.Timeout, result.await())
        assertEquals(0, manager.ownerCount)
        assertNull(manager.currentCameraOwner)
    }
```

Run `./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.glasses.GlassesPhotoCapturerTest"` → expected `BUILD FAILED` (`totalBudgetMs`, `Timeout` unresolved). Then in `GlassesPhotoCapturer.kt`:

1. After `object NoImage : PhotoCaptureOutcome<Nothing>()` add:
```kotlin
    /** The whole capture (device wait excluded) exceeded totalBudgetMs; everything was released. */
    object Timeout : PhotoCaptureOutcome<Nothing>()
```
2. After `const val DEFAULT_FALLBACK_FRAME_TIMEOUT_MS = 2_000L` add:
```kotlin
        /**
         * Upper bound for ensureSessionStarted + addCamera + stream + capture together (ledger T5:
         * the per-step budgets summed to ~36 s worst case; the wake-word user should hear a failure
         * within ~15 s).
         */
        const val DEFAULT_TOTAL_BUDGET_MS = 15_000L
```
3. After the constructor parameter `private val fallbackFrameTimeoutMs: Long = DEFAULT_FALLBACK_FRAME_TIMEOUT_MS,` add:
```kotlin
    private val totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS,
```
4. Replace `return borrowCameraAndCapture()` inside `capture()` with:
```kotlin
            return withTimeoutOrNull(totalBudgetMs) { borrowCameraAndCapture() }
                ?: PhotoCaptureOutcome.Timeout.also { Log.e(TAG, "capture exceeded ${totalBudgetMs}ms") }
```
5. In `QuickVisionService.captureAndAnalyze()`'s `when`, add before `PhotoCaptureOutcome.NoImage -> {`:
```kotlin
                    PhotoCaptureOutcome.Timeout -> {
                        Log.e(TAG, "Capture exceeded its total budget")
                        failAndFinish("error")
                        return@launch
                    }
```
6. In `SessionFrameProvider.snapshot()`'s `when`, add after `PhotoCaptureOutcome.NoImage -> SnapshotResult.NoFrame`:
```kotlin
            PhotoCaptureOutcome.Timeout -> SnapshotResult.StreamFailed("Capture timed out")
```

Run the capturer tests again → expected `BUILD SUCCESSFUL`, at least 7 tests in the class.

- [ ] **Step 7.10: `clearStopping()` must not cancel the job it runs inside; rewrite the tautological `awaitStarted` test**

In `GlassesSessionManager.kt` replace `private fun clearStopping(outgoing: GlassesSession)` with:

```kotlin
    /**
     * @param fromJob the stoppingJob itself when called from inside it; that job must finish on its
     *   own instead of cancelling itself (Phase C adds work after this call).
     */
    private fun clearStopping(outgoing: GlassesSession, fromJob: Job? = null) {
        if (stoppingSession !== outgoing) return
        stoppingSession = null
        val job = stoppingJob
        stoppingJob = null
        if (job != null && job !== fromJob) job.cancel()
        if (session == null) _sessionState.value = DeviceSessionState.STOPPED
    }
```

and inside `stopSession()` change the launched block to:

```kotlin
        stoppingJob = scope.launch {
            current.state.first { it == DeviceSessionState.STOPPED }
            Log.d(TAG, "previous session reported STOPPED")
            clearStopping(current, fromJob = coroutineContext[Job])
        }
```
(`kotlin.coroutines.coroutineContext` is the suspend-scope property; add `import kotlin.coroutines.coroutineContext` if the compiler cannot resolve it inside the `launch` lambda.)

In `GlassesSessionManagerTest.kt` replace the whole `awaitStartedResolvesTrueOnStartedAndFalseOnStopped` test function with:

```kotlin
    @Test
    fun awaitStartedResolvesTrueOnStartedAndFalseOnStopped() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        val started = async { manager.awaitStarted(1_000) }
        factory.last.emitStarted()
        assertTrue(started.await())

        // A fresh session that the device stops before STARTED resolves false on the real STOPPED.
        manager.release("A")
        manager.acquire("B")
        val stopped = async { manager.awaitStarted(1_000) }
        factory.last.emitStoppedByDevice()
        assertFalse(stopped.await())
        assertFalse(manager.hasSession)
    }
```

- [ ] **Step 7.11: `micGranted` re-check on ON_RESUME in `LiveAIScreen`**

After the `val micPermissionLauncher = rememberLauncherForActivityResult(…) { … }` block add:

```kotlin
    // Users who deny the mic, then grant it in system Settings, come back through ON_RESUME:
    // re-check so Live AI connects without leaving and re-entering the screen (ledger T7).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
```

Add imports `import androidx.lifecycle.Lifecycle` and `import androidx.lifecycle.compose.LifecycleEventEffect` (lifecycle-runtime-compose 2.10.0 is already a dependency).

- [ ] **Step 7.12: `MainActivity` naming hygiene**

Rename `initializeSDK()` → `startWearablesMonitoring()` (both the definition and its two call sites) and `sdkInitialized` → `monitoringStarted`; update the comment inside to `// Start observing Wearables state once the Bluetooth runtime permissions are granted.`. Remove `Manifest.permission.INTERNET` from `PERMISSIONS` (a normal permission; requesting it at runtime is a no-op) — the array becomes `arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT)`.

- [ ] **Step 7.13: Full unit suite, both builds, commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin 2>&1 | tail -25
```
Expected: `BUILD SUCCESSFUL`; at least 134 unit tests pass (130 after Task 6 + 3 `RtmpUrlSplitterTest` + 1 capturer budget test).

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/src/main android/app/src/test && git commit -m "fix(android): B2 stability — RTMP Disconnected only for a live drop, encoder leak/busy-spin/double-disconnect fixes, first-frame timeout, encrypted stream key + persisted bitrate with rtmp_url migration, shared OkHttpClient and weak-ref socket listeners with full disconnect cleanup, 15 s aggregate capture budget, mic re-check on resume, hygiene"
```

---

### Task 8: B3 — version 2.0.0, Settings About SDK row, single error toast above the NavHost, docs

**Files:**
- Modify: `app/build.gradle.kts` (anchors: `versionCode = 4`, `versionName = "1.5.0"`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` (anchor: `subtitle = "1.5.0",`)
- Create: `app/src/main/java/com/smartview/glassai/ui/components/WearablesErrorToast.kt`
- Modify: `app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` (anchor: `NavHost(`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt`, `LiveAIScreen.kt`, `QuickVisionScreen.kt`, `SimpleLiveStreamScreen.kt` (anchor in each: `val wearablesErrorMessage by wearablesViewModel.errorMessage.collectAsState()`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/theme/Theme.kt` (anchors: `window.statusBarColor = Color.Transparent.toArgb()`, `window.navigationBarColor = Color.Transparent.toArgb()`, `import androidx.compose.ui.graphics.toArgb`)
- Create: `docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md` (ledger T9: the SDK race report, drafted for the product owner to file)
- Modify: `android/README.md`, `android/CHANGELOG.md`, `README.md`, `README_EN.md` (repo root)

**Interfaces:**
- Produces `@Composable fun WearablesErrorToast(wearablesViewModel: WearablesViewModel)`
- Consumes `WearablesViewModel.errorEvents` (Task 1), `BuildConfig.VERSION_NAME`, `BuildConfig.MWDAT_VERSION` (Task 4)

- [ ] **Step 8.1: Version bump**

In `app/build.gradle.kts` replace `versionCode = 4` with `versionCode = 5` and `versionName = "1.5.0"` with `versionName = "2.0.0"`.

- [ ] **Step 8.2: Settings About rows**

In `SettingsScreen.kt` replace the version item

```kotlin
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.version),
                    subtitle = "1.5.0",
                    onClick = {}
                )
```

with

```kotlin
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_version),
                    subtitle = BuildConfig.VERSION_NAME,
                    onClick = {}
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = AppSpacing.medium))

                SettingsItem(
                    icon = Icons.Default.Memory,
                    title = stringResource(R.string.settings_sdk_version),
                    subtitle = "Meta Wearables DAT ${BuildConfig.MWDAT_VERSION}",
                    onClick = {}
                )
```

(`settings_version` = "App Version"/"应用版本" and `settings_sdk_version` = "SDK Version"/"SDK 版本" already exist in both string files; `BuildConfig` is already imported.)

- [ ] **Step 8.3: The single toast**

Create `app/src/main/java/com/smartview/glassai/ui/components/WearablesErrorToast.kt`:

```kotlin
package com.smartview.glassai.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.smartview.glassai.viewmodels.WearablesViewModel

/**
 * The one place that toasts glasses errors (Phase A review Minor #13). Lives above the NavHost so
 * a navigation during an error cannot show the toast twice, and screens keep reading
 * WearablesViewModel.errorMessage / StreamState.Error as state when they need inline text.
 */
@Composable
fun WearablesErrorToast(wearablesViewModel: WearablesViewModel) {
    val context = LocalContext.current
    LaunchedEffect(wearablesViewModel) {
        wearablesViewModel.errorEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
```

In `Navigation.kt`, inside the `Scaffold { paddingValues -> … }` content, add before `NavHost(`:

```kotlin
        // One toast for every glasses error, regardless of the screen (Phase B Task 8).
        WearablesErrorToast(wearablesViewModel)

```
and add `import com.smartview.glassai.ui.components.WearablesErrorToast`.

- [ ] **Step 8.4: Remove the four duplicated toast blocks**

In `HomeScreen.kt`, `LiveAIScreen.kt` and `SimpleLiveStreamScreen.kt` delete exactly this block (7 lines in each file):

```kotlin
    val wearablesErrorMessage by wearablesViewModel.errorMessage.collectAsState()
    val errorToastContext = LocalContext.current
    LaunchedEffect(wearablesErrorMessage) {
        val message = wearablesErrorMessage ?: return@LaunchedEffect
        Toast.makeText(errorToastContext, message, Toast.LENGTH_LONG).show()
        wearablesViewModel.clearError()
    }
```

In `SimpleLiveStreamScreen.kt` also delete `import android.widget.Toast` and `import androidx.compose.ui.platform.LocalContext` if the compiler reports them unused (`HomeScreen` and `LiveAIScreen` still use `Toast` elsewhere).

In `QuickVisionScreen.kt` delete the block from `val wearablesErrorMessage by wearablesViewModel.errorMessage.collectAsState()` (line 72) through the closing `}` of its `LaunchedEffect(wearablesErrorMessage) { … }` — that is: the `val wearablesErrorMessage` line, `val errorToastContext = LocalContext.current`, the **five** comment lines (74–78, `// clearError() below runs …` through `// generic stream_failed text.`), `var lastGlassesError by remember { mutableStateOf<String?>(null) }`, and the whole `LaunchedEffect` (5 lines). Nothing after that closing brace is touched (`// Quick Vision state` starts the next block). Then delete the line `lastGlassesError = null` inside `performQuickVision`, remove `import android.widget.Toast` and `import androidx.compose.ui.platform.LocalContext` from `QuickVisionScreen.kt` only if the compiler reports them unused (`context = LocalContext.current` at the top of the composable still uses the latter), and replace

```kotlin
                errorMessage = lastGlassesError ?: streamFailedText
```

with

```kotlin
                // The specific DAT reason lives in StreamState.Error (state), not in the one-shot toast
                errorMessage = (streamState as? WearablesViewModel.StreamState.Error)?.message ?: streamFailedText
```

- [ ] **Step 8.4b: Edge-to-edge under targetSdk 36 (ledger T1)**

`MainActivity.onCreate()` already calls `enableEdgeToEdge()` (activity 1.10), which makes both system bars transparent and picks the bar icon contrast itself; the two `window.*Color` writes in `Theme.kt` are deprecated from API 35 and are no-ops under targetSdk 36's enforced edge-to-edge. In `app/src/main/java/com/smartview/glassai/ui/theme/Theme.kt` delete these two lines inside the `SideEffect { … }` block:

```kotlin
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
```

and the now-unused import `import androidx.compose.ui.graphics.toArgb` (`Color`, `Activity` and `WindowCompat` stay in use). Keep the `WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }` block: the app forces the light theme, so the light-bar appearance must not follow the system dark-mode setting that `enableEdgeToEdge()`'s default `SystemBarStyle.auto` would use. Every screen already sits inside a `Scaffold` whose `paddingValues` are applied to the content, so no inset handling changes. Task 9 item 21 checks the layout on an API 35+ image when one is available; the owner's phone check stays in Phase C.

- [ ] **Step 8.5: Build**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8.6: `android/CHANGELOG.md`**

Insert after the `# Changelog` heading (before `## [1.0.0] - 2024-12-27`):

```markdown

## [2.0.0] - 2026-09-10

### 新功能
- **OpenClaw 集成**：完整节点模式。App 以 `openclaw-android` 身份连接自建 OpenClaw Gateway（`ws://` 或 `wss://`），
  支持文字 / 语音（阿里云 Fun-ASR 实时识别）/ 拍照发送，AI 可通过 `camera.snap`、`camera.list`、
  `device.status`、`device.info` 主动调用眼镜。设备身份为 Ed25519（Tink），与 iOS 协议逐字段一致。
- **首页 OpenClaw 卡片**、设置页「集成」分区、OpenClaw 设置页（地址 / 端口 / 协议 / 令牌 / 配对提示）。
- **设置页 About** 新增 SDK 版本行。

### 稳定性
- RTMP：连接失败原因不再被 Disconnected 覆盖；眼镜 10 秒内无画面时报错而不是一直「连接中」；
  推流密钥拆为独立加密字段并不再显示在屏幕上；码率持久化。
- WebSocket 服务（Live AI Omni / Gemini / Fun-ASR）共用一个 OkHttpClient，断开时完整释放资源，监听器使用弱引用。
- 唤醒词拍照的总耗时上限 15 秒。
- Live AI 返回前台时重新检查麦克风权限。
- 眼镜错误只弹一次提示（提示统一挂在导航根部）。

### 技术特性
- Meta Wearables DAT SDK 0.9.0（Kotlin 2.2.21 / AGP 8.11.1 / compileSdk 36），共享 `GlassesSessionManager` 会话。
- 需要 Meta AI 应用 V282+、Ray-Ban Meta 固件 V126+、Meta Ray-Ban Display 固件 V125+，且眼镜上已安装 DAT Wearables App。
- 最低 Android 12（API 31）。
```

- [ ] **Step 8.7: `android/README.md`**

1. Replace `**Version 1.4.0**` (line 3) with `**Version 2.0.0** — Meta Wearables DAT SDK 0.9.0`.
2. Replace the block under `## Requirements | 要求`:
```markdown
- Android 8.0 (API 26) or higher
- Ray-Ban Meta glasses paired via Meta View app
- Android 8.0 (API 26) 或更高版本
- 通过 Meta View 应用配对的 Ray-Ban Meta 眼镜
```
with
```markdown
- Android 12 (API 31) or higher
- Meta AI app **V282+**, Ray-Ban Meta firmware **V126+** (Meta Ray-Ban Display firmware **V125+**)
- The **DAT Wearables App** installed on the glasses (Meta AI app → Developer Mode) — required by DAT SDK 0.9.0
- Ray-Ban Meta / Meta Ray-Ban Display glasses paired via the Meta AI app
- Android 12（API 31）或更高版本
- Meta AI 应用 **V282+**，Ray-Ban Meta 固件 **V126+**（Meta Ray-Ban Display 固件 **V125+**）
- 眼镜上已安装 **DAT Wearables App**（Meta AI 应用 → 开发者模式）——DAT SDK 0.9.0 要求
- 通过 Meta AI 应用配对的 Ray-Ban Meta / Meta Ray-Ban Display 眼镜
```
3. Insert before `## ⚠️ Important Notes | 重要说明` a new section (the outer fence is four backticks only because the section itself contains a ```json block; copy the content between the four-backtick lines verbatim):
````markdown
### 🔗 OpenClaw Integration | OpenClaw 集成

Chat with your self-hosted [OpenClaw](https://openclaw.ai) assistant from the glasses: text, voice (Alibaba Fun-ASR) and
**Snap & Send**; in *node mode* the AI can call `camera.snap`, `camera.list`, `device.status`, `device.info` on its own.
The app must be in the foreground for `camera.snap` (the DAT SDK cannot stream from the background).

从眼镜与自建 [OpenClaw](https://openclaw.ai) 助手对话：文字、语音（阿里云 Fun-ASR）和**拍照发送**；节点模式下 AI 可主动调用
`camera.snap`、`camera.list`、`device.status`、`device.info`。`camera.snap` 需要 App 在前台。

**Gateway setup | Gateway 配置** (`~/.openclaw/openclaw.json` on the machine running `openclaw gateway`):

```json
{
  "gateway": {
    "bind": "lan",
    "nodes": {
      "allowCommands": ["camera.snap", "camera.list", "device.status", "device.info"]
    }
  }
}
```

1. Restart the gateway, then in the app open **Settings → Integrations → OpenClaw** (or the gear on the chat screen).
2. Enter the gateway **Host** (LAN IP) and **Port** (default `18789`), choose `ws://` (LAN) or `wss://` (behind TLS), and paste the
   **Gateway Token** from the OpenClaw dashboard URL. Tap **Connect to Gateway**.
3. First connection shows **Waiting for pairing**: on the gateway machine run `openclaw devices list` then
   `openclaw devices approve <device-id>` (the device id is the Ed25519 identity the app generated once). The app reconnects automatically.
4. Away from home: run [Tailscale](https://tailscale.com) on both machines and use the tailnet IP as the host.
5. Voice input needs an Alibaba DashScope API key (Settings → API Key); the phone/glasses microphone toggle works like Live AI.
6. Voice input follows the Alibaba region setting. `fun-asr-realtime` is confirmed on the Beijing endpoint; on the **Singapore** (intl) endpoint its availability has not been verified — if the mic reports "Speech recognition failed", switch the Alibaba endpoint to Beijing.
7. `camera.snap` needs a frame from the glasses. While Live AI / Live Stream / RTMP are running it returns the live frame; while nobody streams it borrows the camera briefly. During a wake-word Quick Vision capture (a few seconds) the camera is busy and the command answers `NO_FRAME` — the AI simply retries.

1. 重启 Gateway，然后在 App 打开 **设置 → 集成 → OpenClaw**（或聊天页右上角齿轮）。
2. 填写 Gateway **地址**（局域网 IP）与**端口**（默认 `18789`），选择 `ws://`（局域网）或 `wss://`（TLS 反代），粘贴 OpenClaw 仪表盘 URL 中的
   **Gateway 令牌**，点击 **连接 Gateway**。
3. 首次连接显示**等待配对**：在 Gateway 机器上执行 `openclaw devices list`，再 `openclaw devices approve <device-id>`。App 会自动重连。
4. 外网访问：两端安装 [Tailscale](https://tailscale.com)，地址填 tailnet IP。
5. 语音输入需要阿里云 DashScope API Key（设置 → API Key）；手机 / 眼镜麦克风切换与 Live AI 一致。
6. 语音识别跟随阿里云地域设置。`fun-asr-realtime` 已确认在北京节点可用；**新加坡**（intl）节点尚未验证——若麦克风提示「语音识别失败」，请把阿里云节点切回北京。
7. `camera.snap` 需要眼镜画面：Live AI / 直播 / RTMP 运行时返回实时画面；无人使用相机时会短暂借用相机；唤醒词 Quick Vision 拍照的几秒内相机被占用，命令返回 `NO_FRAME`，AI 重试即可。

---

````
4. Insert at the top of `## Release Notes | 更新日志` (before `### v1.4.0 (2024-12-31)`):
```markdown
### v2.0.0 (2026-09-10)

- **OpenClaw integration** (node mode, Ed25519 device identity, Fun-ASR voice, Snap & Send) | **OpenClaw 集成**
- **DAT SDK 0.9.0** with a shared glasses session; Meta Ray-Ban Display glasses work as camera devices | **DAT SDK 0.9.0**，共享眼镜会话
- Stability: RTMP error reporting, first-frame timeout, encrypted stream key, WebSocket cleanup, capture budget | 稳定性修复
- Settings → About shows the SDK version; minimum Android 12 | 设置页显示 SDK 版本；最低 Android 12

```

- [ ] **Step 8.8: Root `README.md` and `README_EN.md`**

In `README.md`:
1. Replace `> ⚠️ Android 版本目前停留在 v1.5.0，暂未包含 v2.0 的 OpenClaw 集成和 Meta Ray-Ban Display 支持。` with
```markdown
> ✅ Android 2.0.0 已包含 OpenClaw 集成（节点模式）、DAT SDK 0.9.0 与 Meta Ray-Ban Display 眼镜相机支持。配置步骤见 [`android/README.md`](android/README.md#-openclaw-integration--openclaw-集成)。
```
2. In `### Android` under 技术栈 replace
```markdown
- **平台**：Android 8.0+ (API 26)
- **语言**：Kotlin 1.9 + Jetpack Compose
- **SDK**：Meta Wearables DAT SDK v0.4.0
```
with
```markdown
- **平台**：Android 12+ (API 31)
- **语言**：Kotlin 2.2 + Jetpack Compose
- **SDK**：Meta Wearables DAT SDK v0.9.0（需要 Meta AI 应用 V282+、眼镜固件 V126+ / Display V125+）
```
3. Replace `✅ **iOS v2.0.0** | 📱 **Android v1.5.0**` with `✅ **iOS v2.0.0** | ✅ **Android v2.0.0**`.

In `README_EN.md`:
1. Replace `> ⚠️ Android is currently at v1.5.0 and does not yet include v2.0 features (OpenClaw, Meta Ray-Ban Display).` with
```markdown
> ✅ Android 2.0.0 includes the OpenClaw integration (node mode), DAT SDK 0.9.0 and Meta Ray-Ban Display glasses camera support. Setup: [`android/README.md`](android/README.md#-openclaw-integration--openclaw-集成).
```
2. Replace `- **Platform**: Android 8.0+ (API 26)` with `- **Platform**: Android 12+ (API 31)`, and the SDK / language lines directly below it with `- **Language**: Kotlin 2.2 + Jetpack Compose` and `- **SDK**: Meta Wearables DAT SDK v0.9.0 (Meta AI app V282+, glasses firmware V126+ / Display V125+)`.
3. Replace `✅ **iOS v2.0.0** | 📱 **Android v1.5.0**` with `✅ **iOS v2.0.0** | ✅ **Android v2.0.0**`.
4. Replace the badge `[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)]` with `[![Android](https://img.shields.io/badge/Android-12%2B-green.svg)]` (same link).

- [ ] **Step 8.8b: Draft the DAT SDK upstream report (ledger T9: "report upstream now with the Task 9 stack")**

Create `docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md` with exactly this content. It is a DRAFT for the product owner: filing an issue on a public tracker is a publishing action, so the plan stops at the draft plus the command, and nothing is posted without the owner's explicit go-ahead.

````markdown
# DAT SDK 0.9.0 (Android): native abort when `Camera.stop()` races `VideoDecoder.activateDecoder()`

**Status:** DRAFT for the product owner — not posted. Verify the correct tracker first (the DAT
developer portal's support channel or the `facebook/meta-wearables-dat-android` GitHub issues), then
post only after an explicit go-ahead (command at the end).

## Environment
- `com.meta.wearable:mwdat-core` / `mwdat-camera` / `mwdat-mockdevice` **0.9.0**
- Emulator Pixel_5, Android 12 (API 31), x86_64. Not seen in two runs on API 36 (not evidence of absence).
- App: TurboMeta Android 2.0.0 (`android-v2`), Kotlin 2.2.21 / AGP 8.11.1 / compileSdk 36 / minSdk 31

## Symptom
About 1 run in 3, stopping a camera within ~300 ms of `StreamState.STREAMING` kills the whole process:

```
F/MediaCodec: frameworks/av/media/libstagefright/MediaCodec.cpp:815
              CHECK_EQ( mState,UNINITIALIZED) failed: 1 vs. 0
F/libc: Fatal signal 6 (SIGABRT) in tid 5761 (IOScheduler-dup), pid 5544 (com.smartview.glassai)
```

Java stack of the aborting thread — entirely inside the SDK, on its transport thread:

```
android.media.MediaCodec.reset(MediaCodec.java:1984)
  com.meta.wearable.dat.camera.internal.codec.VideoDecoder.activateDecoder
  com.meta.wearable.dat.camera.internal.codec.VideoDecoder.enqueue
  com.meta.wearable.dat.camera.internal.WarpEventCoordinator.handleCodecConfig
  …
  com.meta.wearable.acdc.sdk.fake.FakeLinkedDeviceImpl.processQueuedMessages
```

At the same moment the main thread is inside `Camera.stop()`. The app calls the API exactly as the
official `CameraAccess` sample's `stopStreaming()` does (`camera.stop()`, no prior `stream.stop()`).

## Reproduction (MockDeviceKit, no hardware needed)
1. `Wearables.createSession(AutoDeviceSelector())` → `start()` → wait for `STARTED`.
2. `session.addCamera(StreamConfiguration(VideoQuality.MEDIUM, 24))` → `stream.start()` → wait for `STREAMING`.
3. Within ~300 ms of `STREAMING`, call `camera.stop()` on the main thread.
4. Repeat ten times: the abort shows up in roughly three of them. A 2 s wait before step 3 makes it
   disappear — the SDK re-activates its decoder at about +16 ms, +54 ms and +105 ms after `STREAMING`
   and then stays quiet, which is why the delay hides (but does not fix) the race.

Our instrumented test reproduces it when its settle delay is set to 0:
`android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt`,
`capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams`, constant `STREAM_SETTLE_MS`.

## Expected
`Camera.stop()` should be safe at any time after `stream.start()`; `VideoDecoder.activateDecoder()` /
`MediaCodec.reset()` on the transport thread should be serialized against the stop path.

## Why we believe this is in the SDK
The `MediaCodec` is created, reset and released by SDK-internal classes only; the app never touches
it. Full analysis with timings: `docs/superpowers/reviews/phase-a/task-9-report.md`, Concern 1.

## Posting (product owner only, after explicit approval)
```bash
gh issue create --repo facebook/meta-wearables-dat-android \
  --title "0.9.0: native abort (MediaCodec.reset CHECK_EQ) when Camera.stop() races VideoDecoder.activateDecoder()" \
  --body-file docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md
```
````

The hardware repro (rapid open/close of Live Stream on real glasses) stays in Phase C as before; this step only removes the "no step files the report" gap.

- [ ] **Step 8.9: Commit**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/build.gradle.kts android/app/src/main android/README.md android/CHANGELOG.md README.md README_EN.md docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md && git commit -m "chore(android): version 2.0.0, About SDK version row, single WearablesErrorToast above the NavHost, edge-to-edge cleanup, OpenClaw + DAT 0.9.0 docs, DAT SDK upstream report draft"
```

---

### Task 9: Emulator verification — unit suite, both builds, instrumented suite via `am instrument`, manual checklist

**Files:**
- Modify: `app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` (append three tests: frame provider, PAUSED/resume, fold)
- Create: `app/src/androidTest/java/com/smartview/glassai/glasses/SessionFrameProviderEncodeInstrumentedTest.kt`, `app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt`, `app/src/androidTest/java/com/smartview/glassai/services/AudioRecordPcmSourceInstrumentedTest.kt`, `app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt`
- Create: `android/tools/openclaw-stub-gateway/package.json`, `stub.js`, `.gitignore` (host-side stub gateway for the manual connected-path checks)
- No production changes in this task (fix defects found here in a follow-up commit prefixed `fix(android): phase-b verification —`).

**Interfaces:** consumes everything above; produces the verification record `docs/superpowers/reviews/phase-b/task-9-report.md` (paths, commands, per-test results, deviations).

- [ ] **Step 9.1: Add the instrumented frame-provider, PAUSED/resume and fold cases**

Append inside `GlassesSessionManagerInstrumentedTest`. Note the companion reference: `SessionFrameProvider::encodeBitmap` does not resolve (Kotlin cannot reference a companion member through the outer class name); it must be `SessionFrameProvider.Companion::encodeBitmap`.

```kotlin
    /** Phase B: the OpenClaw camera.snap source borrows the camera through the shared session. */
    @Test
    fun openClawFrameProviderSnapsThroughTheSharedSession() {
        val provider = SessionFrameProvider(
            sessionManager = { manager },
            isForeground = { true },
            checkPermission = { CameraPermissionCheck.Granted },
            encode = SessionFrameProvider.Companion::encodeBitmap,
            capture = { m ->
                val capturer = GlassesPhotoCapturer(
                    sessionManager = m,
                    owner = SessionFrameProvider.OWNER,
                    config = config,
                    decodePhoto = FrameConversions::decodePhoto,
                    decodeFrame = { FrameConversions.frameToBitmap(it, FrameConversions.CAPTURE_JPEG_QUALITY) },
                )
                withContext(Dispatchers.Main.immediate) { capturer.capture() }
            },
        )
        runBlocking { awaitActiveDevice() }

        val result = runBlocking { provider.snapshot(maxWidth = 640, quality = 0.8, timeoutMs = STREAM_TIMEOUT_MS) }

        assertTrue("expected Ok but was $result", result is SnapshotResult.Ok)
        val frame = (result as SnapshotResult.Ok).frame
        assertTrue(frame.width in 1..640)
        assertEquals(0xFF.toByte(), frame.jpeg[0])
        assertEquals(0xD8.toByte(), frame.jpeg[1])
        onMain {
            assertNull(manager.currentCameraOwner)
            assertEquals(0, manager.ownerCount)
        }
    }

    // ---- Phase A final review Minor #19 / Recommendation 4: PAUSED/resume and device-side stop ----

    /** MockDeviceKit: a single captouch tap toggles pause/resume of the active stream. */
    @Test
    fun captouchTapPausesAndResumesTheStreamWithoutTeardown() = onMain {
        awaitActiveDevice()
        manager.acquire(OWNER)
        assertEquals(SessionStartResult.STARTED, manager.ensureSessionStarted(SESSION_TIMEOUT_MS))
        val camera = (manager.addCamera(OWNER, config) as CameraResult.Ready).camera
        assertNull(camera.startStream())
        withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.STREAMING } }
        delay(STREAM_SETTLE_MS) // see STREAM_SETTLE_MS

        device.services.captouch.tap()
        withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.PAUSED } }
        // PAUSED is not a teardown: the owner keeps the camera and the session stays STARTED
        assertEquals(OWNER, manager.currentCameraOwner)
        assertEquals(DeviceSessionState.STARTED, manager.sessionState.value)
        assertTrue(manager.hasSession)

        device.services.captouch.tap()
        withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.STREAMING } }
        delay(STREAM_SETTLE_MS)

        manager.stopCamera(OWNER)
        manager.release(OWNER)
        withTimeout(SESSION_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STOPPED } }
    }

    /** Folding the glasses ends the session from the device side (teardownAfterDeviceStop path). */
    @Test
    fun foldingTheGlassesStopsTheSessionFromTheDeviceSide() = onMain {
        awaitActiveDevice()
        manager.acquire(OWNER)
        assertEquals(SessionStartResult.STARTED, manager.ensureSessionStarted(SESSION_TIMEOUT_MS))
        val camera = (manager.addCamera(OWNER, config) as CameraResult.Ready).camera
        assertNull(camera.startStream())
        withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.STREAMING } }
        delay(STREAM_SETTLE_MS)
        manager.publishFrame(OWNER, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        assertTrue(manager.latestFrame.value != null)

        device.fold()

        withTimeout(SESSION_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STOPPED } }
        assertNull(manager.currentCameraOwner)
        assertNull(manager.latestFrame.value)
        assertFalse(manager.hasSession)
        manager.release(OWNER)
        assertEquals(0, manager.ownerCount)
        device.unfold() // leave the mock in the state setUp() expects
    }
```

Add the import `import android.graphics.Bitmap` and, if missing, `import kotlinx.coroutines.withContext`; `SessionFrameProvider`, `CameraPermissionCheck`, `FrameConversions`, `SnapshotResult` are same-package (no import). `awaitActiveDevice()` is the existing private helper (`private suspend fun awaitActiveDevice(): GlassesDeviceInfo`). If `device.fold()` on this SDK build only PAUSES the session instead of stopping it (the MockDeviceKit docs list `captouch.tapAndHold()` as "stops the active session" and fold as the hinge-close), record it in the report and switch the fold test to `device.services.captouch.tapAndHold()` — a test-only change allowed by this task.

Create `app/src/androidTest/java/com/smartview/glassai/glasses/SessionFrameProviderEncodeInstrumentedTest.kt` (`encodeBitmap` needs `android.graphics`, so its contract is checked here rather than on the JVM):

```kotlin
package com.smartview.glassai.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionFrameProviderEncodeInstrumentedTest {

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun decode(jpeg: ByteArray): Bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)!!

    @Test
    fun widerThanMaxWidthIsDownscaledKeepingTheAspectRatio() {
        val snap = SessionFrameProvider.encodeBitmap(bitmap(800, 400), maxWidth = 400, quality = 0.8)!!
        assertEquals(400, snap.width)
        assertEquals(200, snap.height)
        assertEquals(0xFF.toByte(), snap.jpeg[0])
        assertEquals(0xD8.toByte(), snap.jpeg[1])
        val decoded = decode(snap.jpeg)
        assertEquals(400, decoded.width)
        assertEquals(200, decoded.height)
    }

    @Test
    fun narrowerThanMaxWidthIsNotUpscaled() {
        val snap = SessionFrameProvider.encodeBitmap(bitmap(300, 200), maxWidth = 640, quality = 0.8)!!
        assertEquals(300, snap.width)
        assertEquals(200, snap.height)
        assertEquals(300, decode(snap.jpeg).width)
    }

    @Test
    fun qualityIsClampedInsteadOfThrowing() {
        val low = SessionFrameProvider.encodeBitmap(bitmap(64, 64), maxWidth = 0, quality = -3.0)!! // maxWidth 0: no scaling; quality -> 10
        val high = SessionFrameProvider.encodeBitmap(bitmap(64, 64), maxWidth = 64, quality = 7.0)!! // quality -> 100
        assertEquals(64, low.width)
        assertEquals(64, high.width)
        assertTrue("q100 (${high.jpeg.size} B) should not be smaller than q10 (${low.jpeg.size} B)", high.jpeg.size >= low.jpeg.size)
    }
}
```

Create `app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt`. This is the connected path on the device: a `MockWebServer` inside the app process plays the gateway over **cleartext `ws://127.0.0.1`** (so `network_security_config.xml` is exercised for real — without it OkHttp refuses the cleartext upgrade with `CLEARTEXT communication not permitted`), the real `OpenClawNodeService` handshakes with its real identity and `EncryptedSharedPreferences` store, and a `node.invoke camera.snap` goes through `OpenClawCommandRouter → SessionFrameProvider → GlassesPhotoCapturer → MockDeviceKit`:

```kotlin
package com.smartview.glassai.services.openclaw

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.smartview.glassai.glasses.FrameConversions
import com.smartview.glassai.glasses.GlassesPhotoCapturer
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionFrameProvider
import com.smartview.glassai.glasses.WearablesRegistrationGateway
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class OpenClawNodeServiceInstrumentedTest {

    companion object {
        private const val TAG = "OpenClawNodeServiceIT"
        private const val TIMEOUT_MS = 30_000L
    }

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<JsonObject>()
    @Volatile private var socket: WebSocket? = null
    private lateinit var device: MockGlasses
    private lateinit var store: SecureOpenClawSettingsStore
    private lateinit var service: OpenClawNodeService
    private lateinit var manager: GlassesSessionManager

    // The app's real settings on this emulator are restored in tearDown()
    private var savedHost = ""
    private var savedPort = 0
    private var savedScheme = ""
    private var savedToken: String? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"it-nonce"}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JsonParser.parseString(text).asJsonObject
            received.add(json)
            if (json.get("type")?.asString == "req" && json.get("method")?.asString == "connect") {
                webSocket.send("""{"type":"res","id":"${json.get("id").asString}","ok":true,"payload":{"protocol":3}}""")
            }
        }
    }

    @Before
    fun setUp() {
        grantPermissions()
        val kit = MockDeviceKit.getInstance(targetContext)
        kit.enable()
        device = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        device.powerOn()
        device.don()
        device.unfold()
        device.services.camera.setCameraFeed(assetUri("plant.mp4"))
        device.services.camera.setCapturedImage(assetUri("plant.png"))
        manager = GlassesSessionManager.getInstance(targetContext)

        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()

        store = SecureOpenClawSettingsStore(targetContext)
        savedHost = store.host
        savedPort = store.port
        savedScheme = store.scheme
        savedToken = store.loadToken()
        store.host = "127.0.0.1"
        store.port = server.port
        store.scheme = "ws"
        store.saveToken("it-token")

        val registration = WearablesRegistrationGateway(targetContext)
        val config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)
        // Same wiring as SessionFrameProvider.create(), minus the foreground gate (no Activity is
        // started under instrumentation, and the gate is covered by SessionFrameProviderTest).
        val frames = SessionFrameProvider(
            sessionManager = { manager },
            isForeground = { true },
            checkPermission = { registration.checkCameraPermission() },
            encode = SessionFrameProvider.Companion::encodeBitmap,
            capture = { m ->
                val capturer = GlassesPhotoCapturer(
                    sessionManager = m,
                    owner = SessionFrameProvider.OWNER,
                    config = config,
                    decodePhoto = FrameConversions::decodePhoto,
                    decodeFrame = { FrameConversions.frameToBitmap(it, FrameConversions.CAPTURE_JPEG_QUALITY) },
                )
                withContext(Dispatchers.Main.immediate) { capturer.capture() }
            },
        )
        service = OpenClawNodeService(
            store = store,
            identity = lazy { OpenClawDeviceIdentityStore.loadOrCreate(store) },
            clientInfo = OpenClawClientInfo("it", "emulator", OpenClawNodeService.nodeIdFor(targetContext)),
            httpClient = OpenClawNodeService.lanHttpClient(),
            tickIntervalMs = 60_000L,
        )
        service.setCommandRouter(OpenClawCommandRouter(frames, OpenClawDeviceInfoSource.fromBuild()))
    }

    @After
    fun tearDown() {
        service.disconnect()
        runCatching { server.shutdown() }
        store.host = savedHost
        store.port = savedPort
        store.scheme = savedScheme
        store.saveToken(savedToken)
        runBlocking(Dispatchers.Main) {
            manager.release(SessionFrameProvider.OWNER)
            manager.stopSession()
            withTimeoutOrNull(20_000L) { manager.sessionState.first { it == com.meta.wearable.dat.core.session.DeviceSessionState.STOPPED } }
            manager.resetForTests()
        }
        val kit = MockDeviceKit.getInstance(targetContext)
        runCatching { kit.unpairDevice(device) }.onFailure { Log.w(TAG, "unpairDevice failed", it) }
        kit.disable()
        runBlocking(Dispatchers.Main) {
            withTimeoutOrNull(10_000L) { manager.activeDevice.first { it == null } }
        }
    }

    @Test
    fun handshakeOverCleartextWsReachesConnectedAndDeliversChat() {
        service.connect()
        awaitState { it == OpenClawConnectionState.Connected }
        val connect = awaitFrame { it.get("method")?.asString == "connect" }
        val params = connect.getAsJsonObject("params")
        assertEquals("openclaw-android", params.getAsJsonObject("client").get("id").asString)
        assertEquals("it-token", params.getAsJsonObject("auth").get("token").asString)
        assertEquals(64, params.getAsJsonObject("device").get("id").asString.length)
        assertEquals("/?token=it-token", server.takeRequest(5, TimeUnit.SECONDS)!!.path)

        val events = LinkedBlockingQueue<OpenClawChatEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { events.add(it) } }
        Thread.sleep(200)
        socket!!.send("""{"type":"event","event":"chat","payload":{"state":"final","message":{"role":"assistant","content":[{"type":"text","text":"hi from the gateway"}]}}}""")
        assertEquals(OpenClawChatEvent("hi from the gateway", true), events.poll(5, TimeUnit.SECONDS))
        job.cancel()
    }

    @Test
    fun nodeInvokeCameraSnapReturnsAJpegFromTheMockGlasses() {
        runBlocking(Dispatchers.Main) { withTimeout(10_000L) { manager.activeDevice.first { it != null } } }
        service.connect()
        awaitState { it == OpenClawConnectionState.Connected }
        awaitFrame { it.get("method")?.asString == "connect" }

        socket!!.send("""{"type":"req","id":"snap-1","method":"node.invoke","params":{"command":"camera.snap","params":{"maxWidth":640,"quality":0.8},"timeoutMs":30000}}""")

        val result = awaitFrame(TIMEOUT_MS) { it.get("method")?.asString == "node.invoke.result" }
        val params = result.getAsJsonObject("params")
        assertEquals("snap-1", params.get("id").asString)
        assertTrue("error: ${params.get("error")}", params.get("ok").asBoolean)
        assertEquals(service.nodeId, params.get("nodeId").asString)
        val payload = JsonParser.parseString(params.get("payloadjson").asString).asJsonObject
        assertEquals("jpg", payload.get("format").asString)
        assertTrue(payload.get("width").asInt in 1..640)
        val jpeg = Base64.getDecoder().decode(payload.get("base64").asString)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        // the snap borrowed and returned the camera: nothing is left held
        runBlocking(Dispatchers.Main) {
            assertNull(manager.currentCameraOwner)
            assertEquals(0, manager.ownerCount)
        }
    }

    // ---- helpers ----

    private fun awaitState(predicate: (OpenClawConnectionState) -> Boolean): OpenClawConnectionState =
        runBlocking { withTimeout(TIMEOUT_MS) { service.connectionState.first(predicate) } }

    private fun awaitFrame(timeoutMs: Long = 10_000L, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            check(remaining > 0) { "timed out waiting for a matching frame" }
            val next = received.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(next)) return next
        }
    }

    private fun grantPermissions() {
        listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_CONNECT", "android.permission.CAMERA")
            .forEach { permission ->
                runCatching {
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommand("pm grant ${targetContext.packageName} $permission").close()
                }
            }
    }

    private fun assetUri(assetName: String): Uri {
        val outFile = File(targetContext.cacheDir, assetName)
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile)
    }
}
```

Create `app/src/androidTest/java/com/smartview/glassai/services/AudioRecordPcmSourceInstrumentedTest.kt`:

```kotlin
package com.smartview.glassai.services

import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartview.glassai.managers.BluetoothAudioManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The mic seam on a device: 16 kHz mono PCM16 chunks arrive and stop on stop(). */
@RunWith(AndroidJUnit4::class)
class AudioRecordPcmSourceInstrumentedTest {

    @Before
    fun grantMic() {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO").close()
        }
    }

    @Test
    fun phoneMicDeliversPcm16ChunksUntilStopped() {
        val source = AudioRecordPcmSource(MediaRecorder.AudioSource.MIC)
        val chunks = LinkedBlockingQueue<ByteArray>()

        assertTrue("AudioRecord did not start (RECORD_AUDIO granted? emulator mic enabled?)", source.start { chunks.add(it) })
        val first = chunks.poll(5, TimeUnit.SECONDS)
        assertNotNull("no PCM within 5 s", first)
        assertTrue(first!!.isNotEmpty())
        assertEquals(0, first.size % 2) // 16-bit samples

        source.stop()
        chunks.clear()
        Thread.sleep(300)
        assertNull(chunks.poll()) // nothing after stop()
    }

    @Test
    fun voiceCommunicationSourceStartsWithoutSco() {
        // BLUETOOTH_MIC maps to VOICE_COMMUNICATION; without an SCO link Android routes it to the
        // phone mic, which is the Live AI fallback the chat screen relies on.
        val source = AudioRecordPcmSource(FunASRService.recorderSourceFor(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC))
        val chunks = LinkedBlockingQueue<ByteArray>()
        assertTrue(source.start { chunks.add(it) })
        assertNotNull(chunks.poll(5, TimeUnit.SECONDS))
        source.stop()
    }
}
```

Create `app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt` (the 1.5.0 → 2.0.0 `rtmp_url` split and the OpenClaw keys over the real `EncryptedSharedPreferences`; the app's stored values are restored afterwards):

```kotlin
package com.smartview.glassai.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartview.glassai.services.openclaw.OpenClawDeviceIdentity
import com.smartview.glassai.services.openclaw.OpenClawDeviceIdentityStore
import com.smartview.glassai.services.openclaw.SecureOpenClawSettingsStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class APIKeyManagerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var manager: APIKeyManager
    private lateinit var store: SecureOpenClawSettingsStore

    private var savedUrl: String? = null
    private var savedKey: String? = null
    private var savedToken: String? = null
    private var savedScheme = "ws"
    private var savedPort = 0
    private var savedSeed: ByteArray? = null

    @Before
    fun setUp() {
        manager = APIKeyManager.getInstance(context)
        store = SecureOpenClawSettingsStore(context)
        savedUrl = manager.getRtmpUrl()
        savedKey = manager.getRtmpStreamKey()
        savedToken = store.loadToken()
        savedScheme = store.scheme
        savedPort = store.port
        savedSeed = store.loadDeviceSeed()
    }

    @After
    fun tearDown() {
        manager.saveRtmpUrl(savedUrl ?: "")
        savedKey?.let { manager.saveRtmpStreamKey(it) } ?: manager.deleteRtmpStreamKey()
        store.saveToken(savedToken)
        store.scheme = savedScheme
        store.port = savedPort
        // Never leave the deterministic test seed behind as the app's identity
        store.saveDeviceSeed(savedSeed ?: OpenClawDeviceIdentity.generate().seedCopy())
    }

    @Test
    fun legacyRtmpUrlIsSplitIntoServerAndStreamKeyOnce() {
        manager.saveRtmpUrl("rtmp://h/live/key")
        manager.deleteRtmpStreamKey()

        manager.rerunRtmpMigrationForTests() // what the first 2.0.0 launch does in init {}

        assertEquals("rtmp://h/live", manager.getRtmpUrl())
        assertEquals("key", manager.getRtmpStreamKey())

        // An already-split pair is left alone on later runs (the key is present)
        manager.rerunRtmpMigrationForTests()
        assertEquals("rtmp://h/live", manager.getRtmpUrl())
        assertEquals("key", manager.getRtmpStreamKey())
    }

    @Test
    fun rtmpUrlWithoutAKeySegmentMigratesToNoKey() {
        manager.saveRtmpUrl("rtmp://h/live")
        manager.deleteRtmpStreamKey()

        manager.rerunRtmpMigrationForTests()

        assertEquals("rtmp://h/live", manager.getRtmpUrl())
        assertNull(manager.getRtmpStreamKey())
    }

    @Test
    fun openClawTokenSchemeAndPortAreNormalizedInTheEncryptedStore() {
        store.saveToken("  tok  ")
        assertEquals("tok", store.loadToken())
        store.saveToken("   ")
        assertNull(store.loadToken()) // blank deletes (iOS quirk not ported)
        store.scheme = "bogus"
        assertEquals("ws", store.scheme)
        store.scheme = "wss"
        assertEquals("wss", store.scheme)
        store.port = 70_000
        assertEquals(18789, store.port) // out of range -> default
    }

    @Test
    fun deviceSeedPersistsAndReproducesTheIdentity() {
        val seed = ByteArray(32) { (it * 7).toByte() }
        store.saveDeviceSeed(seed)
        assertArrayEquals(seed, store.loadDeviceSeed())
        assertEquals(
            OpenClawDeviceIdentity.fromSeed(seed).deviceId,
            OpenClawDeviceIdentityStore.loadOrCreate(store).deviceId,
        )
    }
}
```

- [ ] **Step 9.2: Unit suite + builds**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && ./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`; **at least 134 unit tests** pass (36 Phase A + ≥ 98 new: T1 ≥ 21, T2 ≥ 13, T3 ≥ 26, T4 ≥ 14, T5 ≥ 8, T6 ≥ 12, T7 ≥ 4). `:app:assembleDebugAndroidTest` compiling proves the `SessionFrameProvider.Companion::encodeBitmap` reference and the four new instrumented classes. Record the exact number from `app/build/test-results/testDebugUnitTest/*.xml`:

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android && grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | awk -F'"' '{s+=$2} END {print "unit tests:", s}'; grep -l 'failures="[1-9]' app/build/test-results/testDebugUnitTest/*.xml || echo "no failures"
```

- [ ] **Step 9.3: Emulator boot (only if `adb devices` does not already list `emulator-5554`)**

```bash
adb devices
"/c/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_5 \
  -no-snapshot-load -no-boot-anim -camera-back virtualscene -camera-front emulated &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
adb shell input keyevent 82
```

- [ ] **Step 9.4: Install + grants + instrumented suite (Gradle's `connectedDebugAndroidTest` is blocked by winnat on this host — Task 9 report of Phase A)**

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android
adb install -r -t app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.smartview.glassai android.permission.CAMERA
adb shell pm grant com.smartview.glassai android.permission.RECORD_AUDIO
adb shell am instrument -w -r \
  -e package com.smartview.glassai \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$TMPDIR/phase-b-instrumented.txt" | tail -60
```
Expected: `OK (19 tests)` — **at least 19**: `GlassesSessionManagerInstrumentedTest` 9 (6 Phase A + frame provider + captouch PAUSED/resume + fold), `OpenClawNodeServiceInstrumentedTest` 2 (cleartext `ws://` handshake + chat; `node.invoke camera.snap` end to end), `APIKeyManagerInstrumentedTest` 4, `AudioRecordPcmSourceInstrumentedTest` 2, `SessionFrameProviderEncodeInstrumentedTest` 3. Run it twice more; all three runs must be green (the Phase A SDK stop race is mitigated by the 2 s settle already in the file). If `am instrument` prints `INSTRUMENTATION_STATUS: stack=…`, check `adb logcat -s SessionFrameProvider GlassesPhotoCapturer GlassesSessionManager OpenClawNodeService OpenClawCommandRouter AudioRecordPcmSource` before touching code. `CLEARTEXT communication … not permitted` in the OpenClaw class means `android:networkSecurityConfig` did not land in the manifest (Task 6 Step 6.2).

- [ ] **Step 9.5: Stub gateway on the host (makes the connected path observable in the manual checklist)**

There is no OpenClaw Gateway on this host, but Node.js is (`node --version` → v22). Create `android/tools/openclaw-stub-gateway/package.json`:

```json
{
  "name": "openclaw-stub-gateway",
  "private": true,
  "type": "module",
  "description": "Minimal OpenClaw Gateway stand-in for the TurboMeta Android manual checklist (no AI, echoes chat, requests camera.snap)",
  "dependencies": {
    "ws": "^8.18.0"
  }
}
```

`android/tools/openclaw-stub-gateway/.gitignore`:

```
node_modules/
last-snap.jpg
```

`android/tools/openclaw-stub-gateway/stub.js` — speaks exactly the frames the app uses (research §2): `connect.challenge` → `connect` (ok, or `NOT_PAIRED` with `--not-paired` until you press `a` + Enter, like `openclaw devices approve`) → `chat.send` echoed back as replace-style `chat` deltas and a final → a `node.invoke.request` `camera.snap` every 20 s (or press `s` + Enter), whose `node.invoke.result` JPEG is written to `last-snap.jpg`:

```javascript
// Minimal OpenClaw Gateway stand-in for the manual checklist (Task 9).
// Usage: node stub.js [--port 18789] [--token T] [--not-paired]
//   a + Enter  -> approve (stop answering NOT_PAIRED; the app reconnects on its own)
//   s + Enter  -> send a node.invoke camera.snap now (also sent automatically every 20 s)
import { WebSocketServer } from "ws";
import { randomUUID } from "node:crypto";
import { writeFileSync } from "node:fs";

const args = process.argv.slice(2);
const flag = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const port = Number(flag("--port") ?? 18789);
const expectedToken = flag("--token");
let notPaired = args.includes("--not-paired");

const wss = new WebSocketServer({ host: "0.0.0.0", port });
console.log(`stub gateway listening on ws://0.0.0.0:${port}` + (notPaired ? " (answering NOT_PAIRED until you press a)" : ""));

const snapRequest = (asReq) => asReq
  ? { type: "req", id: `snap-${Date.now()}`, method: "node.invoke",
      params: { command: "camera.snap", params: { maxWidth: 640, quality: 0.7, format: "jpg" }, timeoutMs: 30000 } }
  : { type: "event", event: "node.invoke.request", payload: null,
      params: { id: `inv-${Date.now()}`, command: "camera.snap", params: { maxWidth: 640, quality: 0.7, format: "jpg" }, timeoutMs: 30000 } };

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, "http://placeholder");
  const token = url.searchParams.get("token");
  console.log(`client connected from ${req.socket.remoteAddress}, token=${token ?? "(none)"}`);
  if (expectedToken && token !== expectedToken) { console.log("bad token -> closing 1008"); ws.close(1008, "bad token"); return; }
  const send = (obj) => ws.send(JSON.stringify(obj));
  send({ type: "event", event: "connect.challenge", payload: { nonce: randomUUID() } });

  let snapTimer;
  ws.on("message", (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.type === "req" && msg.method === "connect") {
      const p = msg.params;
      console.log(`connect: client=${p.client.id}/${p.client.platform} v${p.client.version} model=${p.client.modelIdentifier} device=${p.device.id.slice(0, 8)}… nonce=${p.device.nonce} commands=${p.commands.join(",")}`);
      if (notPaired) { send({ type: "res", id: msg.id, ok: false, error: { code: "NOT_PAIRED", message: "device not paired" } }); return; }
      send({ type: "res", id: msg.id, ok: true, payload: { protocol: 3 } });
      snapTimer = setInterval(() => { console.log("-> node.invoke.request camera.snap"); send(snapRequest(false)); }, 20000);
      return;
    }
    if (msg.type === "req" && msg.method === "chat.send") {
      send({ type: "res", id: msg.id, ok: true, payload: {} });
      const text = msg.params.message;
      const hasImage = (msg.params.attachments ?? []).length > 0;
      console.log(`chat.send [${msg.params.sessionKey}]: "${text}"${hasImage ? " + image/jpeg attachment" : ""}`);
      const reply = `echo: ${text}${hasImage ? " (I received a JPEG)" : ""}`;
      let shown = 0;
      const t = setInterval(() => {
        shown += 5;
        if (shown < reply.length) {
          send({ type: "event", event: "chat", payload: { state: "delta", message: { role: "assistant", content: [{ type: "text", text: reply.slice(0, shown) }] } } });
        } else {
          clearInterval(t);
          send({ type: "event", event: "chat", payload: { state: "final", message: { role: "assistant", content: [{ type: "text", text: reply }] } } });
        }
      }, 150);
      return;
    }
    if (msg.type === "req" && msg.method === "node.invoke.result") {
      send({ type: "res", id: msg.id, ok: true, payload: {} });
      const p = msg.params;
      if (p.ok && p.payloadjson) {
        const payload = JSON.parse(p.payloadjson);
        if (payload.base64) {
          writeFileSync("last-snap.jpg", Buffer.from(payload.base64, "base64"));
          console.log(`<- ${p.id} from ${p.nodeId}: ${payload.width}x${payload.height} ${payload.format}, saved last-snap.jpg`);
        } else {
          console.log(`<- ${p.id} from ${p.nodeId}: ${p.payloadjson}`);
        }
      } else {
        console.log(`<- ${p.id} from ${p.nodeId}: error ${p.error?.code} — ${p.error?.message}`);
      }
      return;
    }
    if (msg.type === "req" && msg.method === "tick") { send({ type: "res", id: msg.id, ok: true, payload: {} }); return; }
    console.log("unhandled:", JSON.stringify(msg).slice(0, 200));
  });
  ws.on("close", (code, reason) => { clearInterval(snapTimer); console.log(`client disconnected ${code} ${reason}`); });
});

process.stdin.on("data", (d) => {
  const key = d.toString().trim();
  if (key === "a") { notPaired = false; console.log("approved: the next connect gets ok:true"); }
  if (key === "s") for (const c of wss.clients) c.send(JSON.stringify(snapRequest(true)));
});
```

Run it (leave this terminal open for the checklist):

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/tools/openclaw-stub-gateway && npm install --no-audit --no-fund && node stub.js --port 18789 --token test --not-paired
```

The emulator reaches the host as `10.0.2.2`. The emulator's NAT delivers the connection to the host as `127.0.0.1`, so Windows Defender Firewall needs no rule; if the app still logs a connect timeout, allow `node.exe` on private networks once.

- [ ] **Step 9.6: Manual checklist (debug APK on `emulator-5554`, MockDeviceKit paired via Settings → Developer → MockDeviceKit as in Phase A Task 10)**

Record PASS/FAIL per line in the report.

OpenClaw — unreachable-host path (stub NOT running yet):
1. Home shows the **OpenClaw** card in the WordLearn slot (row 2), subtitle "OpenClaw"; tapping opens the chat screen (no device/API-key dialogs).
2. Chat screen: orange banner "Not connected" (zh: 未连接), mic + Snap & Send disabled, Text toggle shows the input bar; gear opens OpenClaw settings; X returns Home.
3. Settings screen: Host `127.0.0.1`, Port `18789`, `ws://` selected, empty token; enter Host `10.255.255.1`, Port `18789`, token `test`, tap **Connect to Gateway** → status turns orange "Connecting...", then within ~10 s the status shows "Reconnecting (attempt 1)..." (zh: 重连中（第 1 次）...) and the attempts follow 2/4/8/16/30 s gaps (logcat `OpenClawNodeService: reconnecting in …ms (attempt n/5)`); going Home and back to the chat during the backoff must NOT start an extra attempt (logcat shows no "connecting to" between the scheduled ones); after the 5th attempt the status shows red "Connection failed after 5 retries" (zh: 连接失败，已重试 5 次).
4. Tap **Connect to Gateway** again → a fresh attempt sequence starts (Error state is re-connectable); tapping it during a backoff dials immediately (force). Tap **Done** → back; **Settings → Integrations → OpenClaw** row shows the same status text in the status color.
5. Host set to a blank string → Connect button disabled; Host `bad host` → status "Invalid gateway address" without any network attempt (logcat shows no "connecting to"); Host `::1` is accepted (bracketed internally).
6. Switch language to 中文 (Settings → App language) and confirm the chat placeholders/buttons/status texts are the zh strings from this plan.

OpenClaw — connected path (Step 9.5 stub running with `--token test --not-paired`, mock device paired and worn, feed `plant.mp4` set in the MockDeviceKit screen):
7. Settings: Host `10.0.2.2`, Port `18789`, `ws://`, token `test`, **Connect to Gateway** → the stub logs `connect: client=openclaw-android/android v2.0.0 …` and the app shows "Waiting for pairing" with the `openclaw devices approve` hint. Press `a` + Enter in the stub terminal, then **Connect to Gateway** → "Connected"; the stub logs exactly one earlier `client disconnected 1000 reconnect` (the pairing-wait socket was closed, not orphaned) and the Node ID row shows `rayban-` + 8 hex characters (not `rayban-node`).
8. Chat: Text → type `hello` → Send: the user bubble appears, the assistant bubble streams `echo: hello` (pending bubble grows, then finalizes). Wrong token (`--token test` vs app token `nope`) → the stub closes with 1008 and the app reconnects with backoff, then "Connection failed after 5 retries".
9. **Snap & Send** with the mock feed on: a user bubble with the thumbnail and the photo prompt text appears; the stub logs `chat.send … + image/jpeg attachment`. With the feed **unset** (MockDeviceKit screen → clear feed) and no captured image: the assistant bubble "Cannot get glasses frame, please check connection" (zh: 无法获取眼镜画面，请确认眼镜已连接) appears within ~15 s.
10. Node mode: press `s` + Enter in the stub → the app must be in the foreground: the stub logs `<- snap-… 640x…, saved last-snap.jpg` and `last-snap.jpg` opens as a JPEG. Background the app (Home button) and press `s` again → the stub logs `error NOT_READY — Stream not initialized` (no background snap, spec §1). Return to the app.
11. Open **Live AI**, start it (mock device streaming), then press `s` in the stub → the snap returns the live frame within ~1 s (no capturer round-trip; logcat shows no `OpenClawSnap` owner). Stop Live AI. Start an **RTMP** stream to any unreachable URL so the camera is held, press `s` → again a live frame (RTMP publishes `latestFrame`).
12. Mic button with RECORD_AUDIO revoked (`adb shell pm revoke com.smartview.glassai android.permission.RECORD_AUDIO`) — while connected it requests the permission; while not connected it stays disabled. With the permission granted and no Alibaba key: "Please configure Alibaba API Key in Settings first" bubble. (Actual recognition needs the DashScope key on the owner's phone — see item 24.)
13. Kill and relaunch the app: Home auto-connects (token stored) — the stub logs a new `connect`; Settings → Integrations shows "Connected"; the token field is still populated; the node id is unchanged across the relaunch (persisted seed).

Home / Settings / toast / B2:
14. Settings → About shows **App Version 2.0.0** and **SDK Version Meta Wearables DAT 0.9.0**.
15. With MockDeviceKit disabled, open Live Stream: exactly **one** toast "No compatible glasses are connected" appears (previously two during navigation); go Back → no second toast.
16. Quick Vision with the mock device disabled: the inline card shows the specific DAT reason (not the generic "stream failed" text).
17. Live AI: deny the mic, background the app, grant RECORD_AUDIO in system Settings, return → Live AI connects without leaving the screen. Then, WebSocket cleanup (Task 7 Step 7.8): record `adb shell ps -T -p $(adb shell pidof com.smartview.glassai) | grep -c OkHttp`, connect/disconnect Live AI (Omni and Gemini providers, whichever keys exist on the emulator; without keys the connect fails but still opens a socket) three times each, wait 70 s, record the count again → it must not grow (the shared client's idle threads are reused; before this fix every session left its own Dispatcher/ConnectionPool threads for 60 s).
18. RTMP: open Settings dialog → **Server URL** and **Stream key** (masked) fields; save `rtmp://10.255.255.1/live` + key `abc`; the on-screen URL never shows `abc`; Start → "Connecting" then an error card within ~15 s (either RTMP connect failure or "No video from the glasses within 10 seconds" when the mock feed is off) — the error text must stay on screen until Dismiss.
19. RTMP user Stop: with a reachable RTMP endpoint (e.g. `docker run -p 1935:1935 tiangolo/nginx-rtmp` on the host → `rtmp://10.0.2.2/live`, key `test`) start a stream from the mock feed, wait for "Streaming", tap **Stop** → the screen returns to Idle with **no** "Disconnected from server" card, and logcat shows `RTMPStreamingService: RTMP disconnected` only after `RTMP streaming stopped`. If no RTMP endpoint can be run on this host, record N/A for the live part; the service-side fix (`isStreaming` guard, off-main disconnect) is still verified by item 18's stop-after-error path leaving the error card intact.
20. Wake-word Quick Vision (or the MockDeviceKit capturer path): with the mock device paired but the camera feed unset, the capture gives up within ~15 s with the spoken error instead of ~36 s. Then, with the feed set, start a wake-word capture and press `s` in the stub during those seconds → the stub logs `NO_FRAME — No video frame available` (the documented Quick Vision window), and a second `s` afterwards succeeds.
21. Edge-to-edge (ledger T1, Task 8 Step 8.4b): `"/c/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -list-avds`; if an API 35+ AVD exists, install the debug APK there and check Home, Settings, the OpenClaw chat (input bar above the navigation bar, banner below the status bar) and Live AI for content under the system bars; otherwise record N/A and carry the check to the owner's phone (Phase C).
22. 1.5.0 → 2.0.0 upgrade of the RTMP URL: covered by `APIKeyManagerInstrumentedTest.legacyRtmpUrlIsSplitIntoServerAndStreamKeyOnce` (Step 9.4) because `EncryptedSharedPreferences` cannot be seeded through adb; record its result here.

Deferred to the owner's phone (record N/A here, list in the report):
23. Fun-ASR on the **Singapore** region (research §8.3: `fun-asr-realtime` availability on `dashscope-intl` unverified): set Alibaba endpoint → Singapore, tap the mic in OpenClaw chat, speak; expected either a transcript or a "Speech recognition failed: <server message>" bubble — if the latter, the README note (Task 8) is the documented fallback and the endpoint choice stays user-controlled.
24. Fun-ASR Beijing end to end (needs the DashScope key): partial transcript updates while speaking, final sentences accumulate, Send posts the text to the gateway; glasses-mic chip works after SCO connects.

- [ ] **Step 9.7: Write the report and commit**

Write `docs/superpowers/reviews/phase-b/task-9-report.md` with: the exact commands run, the unit-test total, the three `am instrument` transcripts' summary lines (per class), the checklist results, the stub gateway's log for items 7–11, logcat excerpts for items 3, 15 and 19, the OkHttp thread counts from item 17, and every deviation. Then:

```bash
cd D:/Coding/Workspaces/Android/turbometa-rayban-ai && git add android/app/src/androidTest android/tools/openclaw-stub-gateway docs/superpowers/reviews/phase-b/task-9-report.md && git commit -m "test(android): Phase B verification — instrumented OpenClaw handshake/snap, PAUSED/fold, migration, mic and encode cases; stub gateway; unit suite and emulator checklist report"
```

---

## Self-Review

### Spec §6 (Phase B) coverage

| Spec item | Task |
|---|---|
| B1 `OpenClawNodeService`: OkHttp WebSocket, `Proxy.NO_PROXY`, 10 s connect, handshake, `chat.send`, `node.invoke` in/out, 15 s `tick`, backoff 2/4/8/16/30 s max 5, `NOT_PAIRED` → waiting; typed `(text, isFinal)` instead of `[[FINAL]]` | Task 3 (`lanHttpClient()`, `sendConnect`, `sendChatMessage`, `dispatchInvoke`/`sendInvokeResult`, `startTick`, `handleDisconnect`, `handleResponse`, `OpenClawChatEvent`) |
| B1 `OpenClawDeviceIdentity`: Tink Ed25519, 32-byte seed base64 in `EncryptedSharedPreferences`, `deviceId = sha256hex(pub)`, v3 signature identical to iOS, `platform = "android"` in JSON and signature | Task 2 (`OpenClawDeviceIdentity`, `SecureOpenClawSettingsStore` → `APIKeyManager.saveOpenClawDeviceSeed`, `OpenClawProtocol.PLATFORM` used by both `sendConnect` JSON and `signConnect`) |
| B1 `OpenClawCommandRouter`: `camera.snap/list`, `device.status/info` via `GlassesFrameProvider`; `appVersion = BuildConfig.VERSION_NAME`, `sdkVersion` from the version catalog | Task 4 (`OpenClawCommandRouter`, `SessionFrameProvider`, `BuildConfig.MWDAT_VERSION`, `OpenClawDeviceInfoSource.fromBuild()`) |
| B1 `FunASRService`: `fun-asr-realtime`, Beijing/Singapore by `AlibabaEndpoint`, `AudioRecord` 16 kHz PCM16, mic source via `BluetoothAudioManager` | Task 5 (`FunASRService.endpointUrl`, `AudioRecordPcmSource`, `recorderSourceFor`) + Task 6 (`OpenClawViewModel.switchAudioSource` → `BluetoothAudioManager.switchAudioSource`) |
| B1 UI: chat screen, settings screen (Host/Port/Token/ws\|wss), Home card replacing WordLearn, Settings "Integrations", `network_security_config.xml`, strings zh/en | Task 6 (all; 42 new OpenClaw-related keys in both files incl. `openclaw_status_reconnecting`) + Task 9 `OpenClawNodeServiceInstrumentedTest` (cleartext `ws://` verified on device) |
| Decision 3: full node mode, field-for-field protocol, `openclaw-android`/`android`, `wss://`, ASR mic toggle reuse, in-memory history, no background snap | Tasks 2–6 (`OpenClawProtocol`, `gatewayScheme`, `buildUrl` wss test, `TurboMetaApplication.isInForeground` gate in `SessionFrameProvider`, `OpenClawViewModel` in-memory `messages`); Task 9 items 10 (NOT_READY in background) and 11 (live frame during Live AI / RTMP) |
| Decision 1: session created on entering the OpenClaw chat page, stopped on leaving | Task 6 (`OpenClawViewModel.enterScreen/leaveScreen` → `acquire/release("OpenClawChat")`) |
| B1 `req node.invoke` id rule (research §2.5: frame id is the invoke id) | Task 3 (`parseInvoke(forcedId)`, `reqFrameIdWinsOverParamsId`) |
| B1 reconnect backoff not defeated by auto-connect; explicit Connect dials now | Task 2 (`OpenClawConnectionState.Reconnecting`) + Task 3 (`connect(force)`, `connectDuringBackoffDoesNotDialEarlyButForceDoes`, `reconnectAttemptsResetAfterASuccessfulHello`, `tickStopsOnDisconnect`) + Task 6 (`connect(force = true)` in Settings; status text/color) |
| B1 iOS dispatch aliases (`evt`/`request`, `event ?? method`, binary UTF-8), token-less connect (`auth: {}`, empty signature field), non-hello `ok:true` ignored, malformed `paramsjson`, `payload`-carried invoke, `Proxy.NO_PROXY`/10 s/read 0 client | Task 3 (`dispatchAcceptsTheIosAliasesAndBinaryFrames`, `withoutATokenAuthIsEmptyAndTheSignatureHasAnEmptyTokenField`, `nonHelloResponsesDoNotChangeTheState`, `malformedParamsJsonAndPayloadCarriedInvokesStillReachTheRouter`, `lanHttpClientBypassesProxiesAndNeverTimesOutReads`) |
| B1 `FunASRService` transport failure / server-side `task-finished` / Singapore URL | Task 5 (`transportFailureReportsAnErrorUnlessStopping`, `transportFailureAfterStopIsSilent`, `taskFinishedFromTheServerStopsTheMicAndReportsFinished`, `resolvedUrlFollowsTheRegionUnlessOverridden`); intl model availability → Task 9 item 23 (owner's phone) + README note (Task 8) |
| B1 voice flow in the ViewModel (`asrText += sentence`, partial cleared, Send/Cancel, `switchAudioSource`, `openclaw_chat_asr_failed`) | Task 5 (`SpeechRecognizerSession` seam) + Task 6 (`FakeAsr`; `voiceFlowAccumulatesFinalSentencesAndSendsThem`, `recognizerErrorShowsTheLocalizedFailureAndCancelClearsIt`, `switchingTheAudioSourceReachesTheRunningRecognizer`) |
| B1 `AudioRecordPcmSource` on a device; `encodeBitmap` aspect ratio / no upscale / clamp | Task 9 (`AudioRecordPcmSourceInstrumentedTest`, `SessionFrameProviderEncodeInstrumentedTest`) |
| B1 process-start safety: `OpenClawIntegration.install` never opens `EncryptedSharedPreferences` or generates the seed, and never throws | Task 2 (`SecureOpenClawSettingsStore(context)` lazy) + Task 3 (`identity: Lazy`, `getInstance`) + Task 4 (`runCatching` in `install`) |
| B2: WebSocket services release `OkHttpClient` resources on disconnect, weak-ref listeners; RTMP `feedFrame` lock (Phase A) + stop on VM cleanup; stream key separate encrypted field, not shown; bitrate persisted | Task 7 (`HttpClients`, `SocketListener` with `WeakReference` — verbatim for Omni and Gemini, `disconnect()` cleanup; `RtmpUrlSplitter`, `APIKeyManager.saveRtmpStreamKey/saveRtmpBitrate`, `RTMPSettingsDialog` password field) + Task 9 item 17 (OkHttp thread count flat after 3× connect/disconnect) + `APIKeyManagerInstrumentedTest` (migration on device) |
| B2 RTMP `latestFrame` while RTMP holds the camera | Task 7 Step 7.6 (`sessionManager.publishFrame(OWNER, bitmap)` in `handleVideoFrame`) + Task 9 item 11 |
| B3: `versionName 2.0.0`, About SDK version row, `android/README.md`, `android/CHANGELOG.md`, root README Android sections | Task 8 (incl. README notes on the Quick Vision `NO_FRAME` window and the Singapore Fun-ASR caveat) |
| §10 tests: OpenClaw signature string + base64url, `params`/`paramsjson` parsing, manager ref-count with fakes, instrumented suite | Task 2 (`signatureStringMatchesTheIosLayout`, `publicKeyIsBase64UrlWithoutPadding`), Task 3 (`invokeEventRoundTripsThroughTheRouter` uses `paramsjson`, `invokeRequestFrameUsesTheFrameIdAndReportsErrors` uses `params`), Task 1 (manager tests), Task 9 (`am instrument`, ≥ 19 instrumented cases in 5 classes; end-to-end handshake → chat → `camera.snap` on the emulator, plus the Node stub gateway for the manual connected path) |

### Phase A final review — PHASE-B triage rows → tasks

| Triage row / recommendation | Task |
|---|---|
| Important #4: ViewModels constructible with fakes; `DatRegistrationGateway`; tests for STREAMING/PAUSED/STOPPED mapping, `stream.start()` failure, `disconnect()` ordering, capturer `StreamStartFailed` | Task 1 (`WearablesViewModel internal constructor`, `WearablesRegistrationGateway`, `WearablesViewModelTest`; `streamStartFailureIsReportedAndEverythingReleased` in `GlassesPhotoCapturerTest`) |
| Recommendation 2: frame source that is not a ViewModel — manager `latestFrame` published by the owner + capturer fallback; extract YUV conversion first | Task 1 (`publishFrame`, `FrameConversions`) + Task 4 (`SessionFrameProvider`) + Task 7 (RTMP publishes too) |
| Recommendation 4 / Minor #19: `resetForTests()` hook; instrumented tearDown; PAUSED/resume and fold instrumented cases | Task 1 (`resetForTests`, tearDown change) + Task 9 (`captouchTapPausesAndResumesTheStreamWithoutTeardown`, `foldingTheGlassesStopsTheSessionFromTheDeviceSide`) |
| Minor #5 specific DAT error masked by generic message | Task 1 (`failStart(_errorMessage.value ?: …)`, `createFailedKeepsTheSpecificSessionError`) |
| Minor #6 / T6 ghost frame after `stopStream()` | Task 1 (`isActive` guard in `handleVideoFrame`) |
| Minor #13 / T6 toast duplicated ×4, one-shot events | Task 1 (`errorEvents`) + Task 8 (`WearablesErrorToast` above the NavHost, four blocks removed, Quick Vision reads `StreamState.Error.message`) |
| Minor #14 dead callbacks, stale `MainActivity.initializeSDK`, `INTERNET` runtime request, unused `feedFrame(ByteArray)`, dead `cameraState` in RTMP screen | Task 1 (callbacks removed) + Task 7 (rename, `INTERNET` dropped, overload deleted, `cameraState` line deleted) |
| Minor #15 / T5 `convertI420toNV21` triplicated | Task 1 (`FrameConversions`) |
| Minor #17 / T6 `videoJob` comment | Task 1 (comment corrected in `attachCamera`) |
| T3 `clearStopping()` self-cancel | Task 7 Step 7.10 |
| T3 tautological `awaitStarted` test | Task 7 Step 7.10 |
| T3 unused fake surface (`startError`, `frames`, `errors`) | Task 1: `startError` → `streamStartFailureShowsTheStreamErrorAndReleases` (VM) and `streamStartFailureIsReportedAndEverythingReleased` (capturer); `FakeGlassesCamera.errors` → `streamErrorsWhileStreamingSurfaceAsErrorMessages` (VM `streamErrors → setError`) and `streamErrorInsteadOfStreamingEndsInStreamTimeout` (capturer); `frames` stays unused on the JVM because `VideoFrame` has no JVM constructor — the frame path is covered by the instrumented `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable` (Phase A) and `openClawFrameProviderSnapsThroughTheSharedSession` (Task 9) |
| T4 hard-coded English `WearablesViewModel.kt:286,:298` | Task 1 (`glasses_permission_check_failed`, `camera_permission_denied`) |
| T5 aggregate capture latency unbounded | Task 7 Step 7.9 (`DEFAULT_TOTAL_BUDGET_MS = 15_000`, `PhotoCaptureOutcome.Timeout`) |
| T5 RTMP stuck in Connecting with no stream budget | Task 7 (`FIRST_FRAME_TIMEOUT_MS`, `armFirstFrameTimeout`) |
| Important #3 residual: `Error → Disconnected → Idle` conflation, VM maps Disconnected to a generic error; a user Stop must end in Idle | Task 7 Step 7.5 (`onDisconnectRtmp` guarded by the `isStreaming` flag — `RtmpClient.disconnect()` invokes it synchronously inside `stopStreaming()`) + Step 7.6 (Disconnected → Error only while `UIState.Streaming`) + localized `rtmp_disconnected` + Task 9 item 19 |
| T6 pre-existing RTMP items: output loop busy-spin on codec error, `initEncoder` codec leak, `stopStreaming()` not serialized (double disconnect), `rtmpClient.disconnect()` on Main, dead `feedFrame(ByteArray)` | Task 7 Step 7.5 items 2–6 (`failure` exit + `Error("Encoder failed…")`, `codec?.release()` in the catch, `stopLock` + swap-then-disconnect, `rtmp-disconnect` executor, overload deleted) |
| T7 `micGranted` seeded once | Task 7 Step 7.11 (`LifecycleEventEffect(ON_RESUME)`) |
| T1 targetSdk 36 edge-to-edge (`Theme.kt:61-62` deprecated `statusBarColor`/`navigationBarColor`) | Task 8 Step 8.4b (both writes removed; `enableEdgeToEdge()` in `MainActivity` already owns the bars; the insets controller stays for the forced light theme) + Task 9 item 21 (API 35+ AVD when available; owner's phone in Phase C) |
| T2 `MainActivity.initializeSDK()` stale naming | Task 7 Step 7.12 |
| T9 SDK finding: "report upstream now with the Task 9 stack" | Task 8 Step 8.8b (`docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md` drafted with the stack, repro and the `gh issue create` command; posting is the product owner's explicit decision). The hardware repro stays in Phase C |
| T6 encoderLock serialization measurement; T10 I1 stop wall-time; T9 SDK race hardware repro | Hardware-only measurements — out of this emulator-bound plan; carried to Phase C's real-device checklist (recorded as assumptions, not tasks) |
| T5 `getLocalizedString` half-migrated; Minor #16 | Explicitly Phase D (TTS unification) per the triage |
| T10 D2 `mock_device_name` language switch | Debug-only; folded with the Phase A D1 fix already landed (3433bf0); no Phase B work |

### Review-round fixes folded into this revision (for traceability)
- Blocking: RTMP user Stop no longer ends in a "Disconnected from server" card (`isStreaming` guard, Task 7); `SessionFrameProvider.Companion::encodeBitmap` (Task 9); okio `chunk.toByteString()` instead of the ERROR-deprecated `ByteString.of(array, off, len)` (Task 5); `req node.invoke` uses the frame id even when `params.id` is present (Task 3); `OpenClawIntegration.install` is lazy and fault-tolerant (Tasks 2–4).
- Minor: RTMP VM publishes `latestFrame`; Interfaces blocks corrected (`OpenClawCommandHandler?`, `encodeDispatcher`); test counts made consistent (30 / 57 / 130 / 134); stale-socket close on reconnect from `WaitingForPairing`; `recordStates` collector before the close (no flake); Gemini code verbatim; five-line comment block in `QuickVisionScreen`; manifest anchor disambiguated; identity/store lazy; `decodeImage` off Main; tautological assertion replaced; dead `isNullOrJsonNull` removed; `HttpClients.kt` listed in Task 6 Files; double-lossy snap documented; `HttpUrl`-based percent-encoding (`%20`, `%2B`) and IPv6 bracketing in `buildUrl`; `Reconnecting` state so auto-connect respects the backoff; `asrService` local (no shadowing); router messages match iOS text with the detail logged.

### Things this plan deliberately does NOT do
- No `openclaw_enabled` flag, no "every ok:true is hello", no raw-token URL, no `rayban-node` placeholder (research §10 quirks).
- No background `camera.snap` (spec §1), no persisted chat history (decision 3), no `camera.clip` (not implemented on iOS either).
- No change to `GlassesSessionManager` threading contract beyond the documented `publishFrame()` exception.
- No silent Beijing fallback when the Singapore Fun-ASR endpoint rejects the model: the region stays the user's setting and the failure is surfaced (README note + Task 9 item 23).
- No `latestFrame` publishing from `QuickVisionService`'s short-lived capture (documented `NO_FRAME` window; the gateway retries).
