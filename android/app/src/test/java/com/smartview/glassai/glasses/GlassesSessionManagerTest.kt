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
    fun ensureSessionReturnsFalseWhilePreviousSessionIsStopping() = runTest(UnconfinedTestDispatcher()) {
        factory.stopAsync = true
        val manager = newManager()
        manager.acquire("A")
        val first = factory.last
        first.emitStarted()

        manager.release("A") // last owner: stop() only reaches STOPPING (real SDK behaviour)
        assertTrue(manager.isStoppingPreviousSession)

        // Creating now would hit SESSION_ALREADY_EXISTS on the real SDK: refuse instead.
        assertFalse(manager.ensureSession())
        assertEquals(1, factory.createCalls)
        assertFalse(manager.hasSession)

        first.emitStoppedByDevice() // the SDK finishes the stop
        assertFalse(manager.isStoppingPreviousSession)

        assertTrue(manager.ensureSession())
        assertEquals(2, factory.createCalls)
        assertEquals(1, factory.last.startCalls)
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

    /**
     * A device-initiated stop (fold, tap-and-hold, thermal, Bluetooth loss) must still release the
     * lent Camera: the borrower's stopCamera() is a no-op afterwards (cameraOwner is already null),
     * so the SDK Camera — and its MediaCodec decoder — would otherwise only be freed by GC.
     */
    @Test
    fun deviceStoppingSessionStopsTheLentCamera() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.acquire("A")
        factory.last.emitStarted()
        manager.addCamera("A", config)
        val lent = factory.last.cameras.single()
        assertEquals(0, lent.stopCalls)

        factory.last.emitStoppedByDevice()

        assertEquals(1, lent.stopCalls)
        assertNull(manager.currentCameraOwner)
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

    // ---- Task 4 fix round 2: startMonitoring() must survive a throwing device flow ----

    /**
     * OpenClawIntegration.install() calls startMonitoring() from Application.onCreate() before
     * Bluetooth permissions are granted (Task 4 fix round 1). The existing `.catch {}` only guards
     * the flow's *collection*; it does nothing for a DatDeviceObserver.activeDeviceInfoFlow() that
     * throws synchronously while being constructed, which used to escape uncaught out of the
     * launched coroutine. Pins: (1) the throw does not escape/crash the process, (2) the manager is
     * left in a harmless, unmonitored state (no session, no active device), and (3) a later
     * startMonitoring() call actually retries — deviceJob must have been nulled out, not left
     * pointing at the dead job — and succeeds once the observer stops throwing.
     */
    @Test
    fun startMonitoringSurvivesAThrowingDeviceFlow() = runTest(UnconfinedTestDispatcher()) {
        observer.throwOnFlow = true
        val manager = GlassesSessionManager(
            sessionFactory = factory,
            deviceObserver = observer,
            scope = backgroundScope,
            displayAttacher = attacher,
        )

        manager.startMonitoring() // must not throw out of this call

        assertFalse(manager.hasSession)
        assertNull(manager.activeDevice.value)

        observer.throwOnFlow = false
        observer.device.value = rayban
        manager.startMonitoring() // must actually retry, not silently no-op

        assertEquals(rayban, manager.activeDevice.value)
    }
}
