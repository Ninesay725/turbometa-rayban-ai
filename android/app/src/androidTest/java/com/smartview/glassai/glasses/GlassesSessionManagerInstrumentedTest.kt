package com.smartview.glassai.glasses

import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

        /**
         * DAT 0.9.0 re-activates its internal HEVC decoder a few times right after STREAMING
         * (VideoDecoder.activateDecoder -> MediaCodec.reset on the transport thread). Camera.stop()
         * racing one of those re-activations aborts the process natively
         * (MediaCodec.cpp CHECK_EQ(mState, UNINITIALIZED)). The burst is over within ~300 ms, so
         * the contention test lets the stream settle before it borrows/tears down the camera.
         */
        private const val STREAM_SETTLE_MS = 2_000L
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
            // Phase B: forget owners / parked session / latest frame so the next test starts clean.
            manager.resetForTests()
        }
        runCatching { mockDeviceKit.unpairDevice(device) }
            .onFailure { Log.w(TAG, "unpairDevice failed", it) }
        mockDeviceKit.disable()
        // GlassesSessionManager is a process singleton shared by every test in this class: wait for
        // its activeDevice to fall back to null so the next test's awaitActiveDevice() cannot
        // observe the device this test just unpaired.
        onMain {
            withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) {
                manager.activeDevice.first { it == null }
            }
        }
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
    fun capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams() {
        onMain {
            awaitActiveDevice()
            manager.acquire("WearablesViewModel")
            assertEquals(SessionStartResult.STARTED, manager.ensureSessionStarted(SESSION_TIMEOUT_MS))
            val live = (manager.addCamera("WearablesViewModel", config) as CameraResult.Ready).camera
            assertNull(live.startStream())
            withTimeout(STREAM_TIMEOUT_MS) { live.streamState.first { it == DatStreamState.STREAMING } }
            delay(STREAM_SETTLE_MS) // see STREAM_SETTLE_MS: avoids the SDK's decoder-reset race

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
    fun captouchTapPausesAndResumesTheStreamWithoutTeardown(): Unit = onMain {
        awaitActiveDevice()
        manager.acquire(OWNER)
        assertEquals(SessionStartResult.STARTED, manager.ensureSessionStarted(SESSION_TIMEOUT_MS))
        val camera = (manager.addCamera(OWNER, config) as CameraResult.Ready).camera
        assertNull(camera.startStream())
        withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.STREAMING } }
        delay(STREAM_SETTLE_MS) // see STREAM_SETTLE_MS

        device.services.captouch.tap()
        withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.PAUSED } }
        // DAT 0.9.0 propagates the captouch pause to the *session* too (logcat:
        // "session state: PAUSED"), so sessionState is PAUSED here, not STARTED. What the test
        // asserts is that this is not a teardown: the owner keeps the camera and the session
        // object survives (onSessionState only tears down on STOPPED).
        withTimeout(STREAM_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.PAUSED } }
        assertEquals(OWNER, manager.currentCameraOwner)
        assertTrue(manager.hasSession)

        device.services.captouch.tap()
        withTimeout(STREAM_TIMEOUT_MS) { camera.streamState.first { it == DatStreamState.STREAMING } }
        withTimeout(STREAM_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STARTED } }
        assertEquals(OWNER, manager.currentCameraOwner)
        delay(STREAM_SETTLE_MS)

        manager.stopCamera(OWNER)
        manager.release(OWNER)
        withTimeout(SESSION_TIMEOUT_MS) { manager.sessionState.first { it == DeviceSessionState.STOPPED } }
    }

    /** Folding the glasses ends the session from the device side (teardownAfterDeviceStop path). */
    @Test
    fun foldingTheGlassesStopsTheSessionFromTheDeviceSide(): Unit = onMain {
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
