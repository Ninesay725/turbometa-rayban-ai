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
import com.smartview.glassai.glasses.GlassesDisplayState
import com.smartview.glassai.glasses.GlassesSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val displayGlasses = rayban.copy(
        name = "Meta Ray-Ban Display",
        deviceType = DeviceType.META_RAYBAN_DISPLAY,
        isDisplayCapable = true,
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

    private fun newViewModel(sessionManager: GlassesSessionManager = manager): WearablesViewModel = WearablesViewModel(
        application = Application(),
        sessionManager = sessionManager,
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

    @Test
    fun displayStateFollowsTheManager() {
        observer.device.value = displayGlasses
        val vm = newViewModel()
        assertTrue(vm.isDisplayAvailable.value)
        assertEquals(GlassesDisplayState.NOT_ATTACHED, vm.displayState.value)

        manager.acquire("display-state-test")
        try {
            factory.last.emitStarted()
            assertEquals(GlassesDisplayState.STARTING, vm.displayState.value)
            factory.last.display.emitStarted()
            assertEquals(GlassesDisplayState.STARTED, vm.displayState.value)
            factory.last.display.emitStopped()
            assertEquals(GlassesDisplayState.STOPPED, vm.displayState.value)
        } finally {
            manager.release("display-state-test")
        }
    }

    @Test
    fun isDisplayCapableFollowsTheActiveDevice() {
        val vm = newViewModel()
        observer.device.value = rayban
        assertFalse(vm.isDisplayCapable.value)
        observer.device.value = displayGlasses
        assertTrue(vm.isDisplayCapable.value)
        observer.device.value = null
        assertFalse(vm.isDisplayCapable.value)
    }

    @Test
    fun isDisplayAvailableIsFalseWhenTheSettingIsOff() = runTest(dispatcher) {
        val disabledManager = GlassesSessionManager(
            sessionFactory = factory,
            deviceObserver = observer,
            scope = backgroundScope,
            displayEnabled = { false },
        )
        val vm = newViewModel(disabledManager)
        observer.device.value = displayGlasses
        runCurrent()
        assertTrue(vm.isDisplayCapable.value)
        assertFalse(vm.isDisplayAvailable.value)

        disabledManager.acquire("display-disabled-test")
        try {
            factory.last.emitStarted()
            runCurrent()
            assertFalse(vm.isDisplayAvailable.value)
            assertEquals(GlassesDisplayState.NOT_ATTACHED, vm.displayState.value)
            assertEquals(0, factory.last.addDisplayCalls)
        } finally {
            disabledManager.release("display-disabled-test")
        }
    }
}
