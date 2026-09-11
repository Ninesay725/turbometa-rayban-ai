package com.smartview.glassai.glasses

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.session.DeviceSessionState
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

    /**
     * State-transition guard, NOT a pin on the liveFrame() two-condition gate (Task 4 fix round 2,
     * re-review finding): GlassesSessionManager.stopCamera() clears cameraOwner and _latestFrame in
     * the same call, so a naive `liveFrame() = latestFrame.value` would already answer
     * `hasFrame == false` / `streamStatus == "stopped"` here — this test cannot tell that from the
     * real `currentCameraOwner != null && sessionState == STARTED` gate. It still guards the
     * observable state transition (stop -> no frame -> capturer fallback) and remains valid as a
     * regression test for that. See [frameIsNotServedWhileSessionIsPaused] for the test that
     * actually pins the `sessionState == STARTED` half of the gate.
     */
    @Test
    fun staleFrameAfterStopCameraIsNotServed() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        observer.device.value = rayban
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        manager.publishFrame("A", TestBitmaps.stub())
        manager.stopCamera("A")
        manager.release("A")
        val photo = TestBitmaps.stub()
        captureOutcome = PhotoCaptureOutcome.Captured(photo, fromVideoFrame = false)

        val provider = provider(manager)
        assertEquals("stopped", provider.streamStatus)
        assertFalse(provider.hasFrame)

        val result = provider.snapshot(320, 0.6, 200)

        assertTrue(result is SnapshotResult.Ok)
        assertEquals(1, captureCalls)
        assertEquals(listOf(photo), encoded)
    }

    /**
     * Pins the `sessionState == STARTED` half of the liveFrame() gate (Task 4 fix round 2,
     * re-review finding): a PAUSED session still has a camera owner and an un-cleared latestFrame,
     * so only the STARTED check can explain why the frame stops being served and the router falls
     * back to NO_FRAME instead of replaying the stale frame. The owner half of the gate
     * (`currentCameraOwner != null`) is structurally guaranteed by stopCamera() clearing
     * currentCameraOwner and latestFrame together (see staleFrameAfterStopCameraIsNotServed's KDoc)
     * and is intentionally not re-pinned here.
     *
     * Mutation check performed for this fix: removing `&& sessionState.value ==
     * DeviceSessionState.STARTED` from GlassesFrameProvider.kt's private `liveFrame()` extension
     * makes this test fail (hasFrame/streamStatus read the still-present latestFrame as live, and
     * snapshot() returns Ok via the fast path instead of NoFrame); restoring the condition makes it
     * pass again. See task-4-report.md "Fix round 2" for the recorded RED/GREEN output.
     */
    @Test
    fun frameIsNotServedWhileSessionIsPaused() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        observer.device.value = rayban
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        val frame = TestBitmaps.stub()
        manager.publishFrame("A", frame)

        val provider = provider(manager)
        assertTrue(provider.hasFrame)
        assertEquals("streaming", provider.streamStatus)

        factory.last.stateFlow.value = DeviceSessionState.PAUSED

        assertFalse(provider.hasFrame)
        assertEquals("waiting", provider.streamStatus)

        val result = provider.snapshot(320, 0.6, 200)

        assertEquals(SnapshotResult.NoFrame, result)
        assertEquals(0, captureCalls)
    }

    /**
     * Review finding 2 (fix round 1): a feature between acquire() and addCamera() reports no camera
     * owner yet, but it does hold a claim. The capturer fallback must not race it for the camera —
     * with claims outstanding the snap waits for the owner's first frame and answers NO_FRAME.
     */
    @Test
    fun fallbackIsSkippedWhileAnotherOwnerHoldsAClaim() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        observer.device.value = rayban
        manager.acquire("B") // claimed; addCamera() has not run yet, so currentCameraOwner is null

        val result = provider(manager).snapshot(640, 0.8, 200)

        assertEquals(SnapshotResult.NoFrame, result)
        assertEquals(0, captureCalls)
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
