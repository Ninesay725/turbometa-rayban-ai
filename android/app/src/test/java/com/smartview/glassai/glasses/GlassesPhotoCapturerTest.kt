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
