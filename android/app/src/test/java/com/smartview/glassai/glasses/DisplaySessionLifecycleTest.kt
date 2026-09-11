package com.smartview.glassai.glasses

import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySessionLifecycleTest {
    private val factory = FakeDatSessionFactory()
    private val observer = FakeDatDeviceObserver()
    private val capable = GlassesDeviceInfo("display-1", "Display", DeviceType.META_RAYBAN_DISPLAY,
        true, DeviceCompatibility.COMPATIBLE)
    private fun TestScope.manager(enabled: () -> Boolean = { true }) =
        GlassesSessionManager(factory, observer, backgroundScope, enabled).also { it.startMonitoring() }

    @Test
    fun capableStartedSessionAttachesDisplay() = runTest(UnconfinedTestDispatcher()) {
        val factory = FakeDatSessionFactory()
        val observer = FakeDatDeviceObserver()
        val manager = GlassesSessionManager(factory, observer, backgroundScope)
        manager.startMonitoring()
        observer.device.value = GlassesDeviceInfo(
            "display-1", "Display", DeviceType.META_RAYBAN_DISPLAY, true,
            DeviceCompatibility.COMPATIBLE,
        )
        manager.acquire("feature")
        factory.last.emitStarted()
        assertEquals(GlassesDisplayState.STARTING, manager.displayState.value)
    }

    @Test fun lateMetadataAttachesOnceAndResumeIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        manager.acquire("feature")
        factory.last.emitStarted()
        assertEquals(0, factory.last.addDisplayCalls)
        observer.device.value = capable
        assertEquals(1, factory.last.addDisplayCalls)
        factory.last.stateFlow.value = DeviceSessionState.PAUSED
        factory.last.emitStarted()
        assertEquals(1, factory.last.addDisplayCalls)
        assertTrue(manager.isDisplayAvailable.value)
    }

    @Test fun displayStateMirrorsWithoutRestartingTerminalDisplay() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        factory.last.display.emitStarted()
        assertEquals(GlassesDisplayState.STARTED, manager.displayState.value)
        factory.last.display.emitStopped()
        assertEquals(GlassesDisplayState.STOPPED, manager.displayState.value)
        factory.last.emitStarted()
        assertEquals(1, factory.last.addDisplayCalls)
    }

    @Test fun privateEpochSurvivesSameDisplayPauseResumeBeforeConsumerRuns() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        factory.last.display.emitStarted()
        val display = manager.currentDisplay()
        val boundEpoch = manager.privateDisplayEpoch.value
        val observed = mutableListOf<Long>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            manager.privateDisplayEpoch.collect { observed += it }
        }
        runCurrent()

        factory.last.stateFlow.value = DeviceSessionState.PAUSED
        assertEquals(boundEpoch + 1, manager.privateDisplayEpoch.value)
        factory.last.emitStarted()
        assertSame(display, manager.currentDisplay())
        assertEquals(GlassesDisplayState.STARTED, manager.displayState.value)
        assertEquals(listOf(boundEpoch), observed) // The feature has not consumed either transition.
        runCurrent()
        assertEquals(listOf(boundEpoch, boundEpoch + 1), observed)
    }

    @Test fun privateEpochChangesOncePerReadyDisplayLoss() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        assertEquals(0L, manager.privateDisplayEpoch.value) // STARTING cannot contain private content.
        factory.last.display.emitStarted()
        val boundEpoch = manager.privateDisplayEpoch.value

        factory.last.display.emitStopped() // STOPPING then STOPPED must invalidate only once.
        assertEquals(boundEpoch + 1, manager.privateDisplayEpoch.value)
        manager.setDisplayEnabled(false)
        manager.setDisplayEnabled(false)
        assertEquals(boundEpoch + 1, manager.privateDisplayEpoch.value)
        manager.setDisplayEnabled(true)
        factory.last.display.emitStarted()
        manager.setDisplayEnabled(false) // Explicit detach of a ready capability also invalidates.
        assertEquals(boundEpoch + 2, manager.privateDisplayEpoch.value)
    }

    @Test fun privateEpochDoesNotCountSessionAndDisplayTeardownTwice() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        factory.last.display.emitStarted()
        val boundEpoch = manager.privateDisplayEpoch.value

        factory.last.emitStoppedByDevice()
        assertEquals(boundEpoch + 1, manager.privateDisplayEpoch.value)
        assertNull(manager.currentDisplay())
        manager.stopSession()
        manager.resetForTests()
        assertEquals(boundEpoch + 1, manager.privateDisplayEpoch.value)
    }

    @Test fun ordinaryGlassesNeverAddOrRemoveDisplay() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable.copy(isDisplayCapable = false)
        manager.acquire("feature")
        factory.last.emitStarted()
        manager.release("feature")
        manager.resetForTests()
        assertEquals(0, factory.last.addDisplayCalls)
        assertEquals(0, factory.last.removeDisplayCalls)
        assertNull(manager.currentDisplay())
        assertFalse(manager.isDisplayAvailable.value)
    }

    @Test fun disabledSettingIsLazyAndReadOnce() = runTest(UnconfinedTestDispatcher()) {
        var reads = 0
        val manager = manager { reads++; false }
        assertEquals(0, reads)
        observer.device.value = capable.copy(isDisplayCapable = false)
        assertEquals(0, reads)
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        assertEquals(1, reads)
        assertEquals(0, factory.last.addDisplayCalls)
        assertFalse(manager.isDisplayAvailable.value)
        manager.setDisplayEnabled(true)
        assertEquals(1, reads)
        assertEquals(1, factory.last.addDisplayCalls)
    }

    @Test fun settingOffRemovesAndOnReattaches() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        manager.setDisplayEnabled(false)
        assertEquals(1, factory.last.removeDisplayCalls)
        assertNull(manager.currentDisplay())
        assertFalse(manager.isDisplayAvailable.value)
        manager.setDisplayEnabled(true)
        assertEquals(2, factory.last.addDisplayCalls)
        assertTrue(manager.isDisplayAvailable.value)
    }

    @Test fun explicitReattachRemovesThenAddsExactlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        factory.last.display.emitStopped()
        manager.reattachDisplay()
        assertEquals(1, factory.last.removeDisplayCalls)
        assertEquals(2, factory.last.addDisplayCalls)
        assertEquals(GlassesDisplayState.STARTING, manager.displayState.value)
    }

    @Test fun removesDisplayBeforeSessionStopAndDoesNotRemoveTwice() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        manager.stopSession()
        manager.stopSession()
        manager.resetForTests()
        assertEquals(listOf("removeDisplay", "stop"), factory.last.lifecycleCalls)
        assertEquals(GlassesDisplayState.NOT_ATTACHED, manager.displayState.value)
    }

    @Test fun deviceStopDetachesDisplay() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        factory.last.emitStoppedByDevice()
        assertEquals(1, factory.last.removeDisplayCalls)
        assertNull(manager.currentDisplay())
    }

    @Test fun removalFailuresCloseTheCapability() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.emitStarted()
        for (error in listOf(DeviceSessionError.SESSION_ALREADY_STOPPED, DeviceSessionError.CAPABILITY_NOT_FOUND)) {
            val display = factory.last.display
            factory.last.nextRemoveDisplayFailure = error
            manager.setDisplayEnabled(false)
            assertEquals(1, display.closeCalls)
            assertNull(manager.currentDisplay())
            manager.setDisplayEnabled(true)
        }
    }

    @Test fun addFailureSurfacesAndWaitsForAnotherTrigger() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        val errors = mutableListOf<DeviceSessionError>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.sessionError.collect { errors += it } }
        observer.device.value = capable
        manager.acquire("feature")
        factory.last.nextAddDisplayFailure = DeviceSessionError.CAPABILITY_DENIED
        factory.last.emitStarted()
        assertEquals(listOf(DeviceSessionError.CAPABILITY_DENIED), errors)
        assertEquals(1, manager.displayAttachAttempts)
        assertNull(manager.currentDisplay())
        factory.last.stateFlow.value = DeviceSessionState.PAUSED
        factory.last.emitStarted()
        assertEquals(2, manager.displayAttachAttempts)
    }

    @Test fun sessionOnlyStartWaitsForPreviousStop() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        factory.stopAsync = true
        manager.acquire("A")
        val old = factory.last
        old.emitStarted()
        manager.release("A")
        val result = async { manager.acquireAndStart("B", 5_000) }
        assertEquals(1, factory.createCalls)
        old.emitStoppedByDevice()
        factory.last.emitStarted()
        assertEquals(SessionStartResult.STARTED, result.await())
        assertEquals(1, manager.ownerCount)
        assertFalse(manager.hasCameraClaim)
    }

    @Test fun releasedOwnerDoesNotCreateSessionAfterStopWait() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        factory.stopAsync = true
        manager.acquire("A")
        val old = factory.last
        old.emitStarted()
        manager.release("A")
        val result = async { manager.acquireAndStart("B", 5_000) }
        manager.release("B")
        old.emitStoppedByDevice()
        assertEquals(SessionStartResult.NOT_STARTED, result.await())
        assertEquals(1, factory.createCalls)
        assertEquals(0, manager.ownerCount)
        assertFalse(manager.hasSession)
    }

    @Test fun releasedOwnerDoesNotCreateSessionAfterRetryDelay() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        factory.failure = DeviceSessionError.SESSION_ALREADY_EXISTS
        val result = async { manager.acquireAndStart("B", 5_000) }
        val calls = factory.createCalls
        manager.release("B")
        factory.failure = null
        assertEquals(SessionStartResult.NOT_STARTED, result.await())
        assertEquals(calls, factory.createCalls)
        assertFalse(manager.hasSession)
    }

    @Test fun createFailureIsReportedAndResetRestartsMonitoring() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        observer.device.value = capable
        factory.failure = DeviceSessionError.NO_ELIGIBLE_DEVICE
        assertEquals(SessionStartResult.CREATE_FAILED, manager.acquireAndStart("feature", 100))
        assertEquals(DeviceSessionError.NO_ELIGIBLE_DEVICE, manager.lastSessionError.value)
        manager.resetForTests()
        assertNull(manager.activeDevice.value)
        assertNull(manager.lastSessionError.value)
        assertEquals(0, manager.displayAttachAttempts)
        manager.startMonitoring()
        assertEquals(capable, manager.activeDevice.value)
    }
}
