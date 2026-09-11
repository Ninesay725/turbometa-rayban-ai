package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
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

    @Test
    fun retriesOnceWhenSessionDisappearsDuringStart() = runTest(dispatcher) {
        observer.device.value = rayban
        factory.nextCaptureResult = PhotoCaptureResult.Success(heicPhoto)
        val manager = newManager()

        val outcome = async { capturer(manager).capture() }
        assertEquals(1, factory.createCalls) // acquire() fast path created the first session

        // Simulates WearablesViewModel.disconnect() racing the wake-word capture: the session
        // disappears while ensureSessionStarted() is still waiting for STARTED.
        manager.stopSession()
        assertEquals(2, factory.createCalls) // NOT_STARTED retried once -> a fresh session was created

        factory.last.emitStarted() // the retried ensureSessionStarted resumes -> addCamera -> start
        val camera = factory.last.cameras.single()
        assertEquals(1, camera.startCalls)

        camera.stateFlow.value = DatStreamState.STREAMING

        assertEquals(PhotoCaptureOutcome.Captured("photo", fromVideoFrame = false), outcome.await())
        assertEquals(2, factory.createCalls)
        assertEquals(0, manager.ownerCount)
    }

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
}
