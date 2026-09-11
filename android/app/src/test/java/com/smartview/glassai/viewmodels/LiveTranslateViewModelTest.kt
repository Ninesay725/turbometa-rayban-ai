package com.smartview.glassai.viewmodels

import com.smartview.glassai.services.PcmAudioSource
import com.smartview.glassai.translation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTranslateViewModelTest {
    @Test fun openingOrChangingSettingsDoesNotArmAnyInput() = runTest {
        val f = Fixture(this)
        f.vm.onStart()
        f.settings.value = TranslateSettings(imageEnabled = true)
        runCurrent()
        assertTrue(f.connections.isEmpty())
        assertEquals(0, f.routeCreates)
        assertEquals(0, f.visual.starts)
    }

    @Test fun missingKeyDoesNotRequestPermissionOrRouteAudio() = runTest {
        val f = Fixture(this); f.key = null; f.start(); runCurrent()
        assertEquals(TranslationUiError.MISSING_KEY, f.vm.ui.value.error)
        assertEquals(0, f.permissionRequests)
        assertEquals(0, f.routeCreates)
    }

    @Test fun deniedPermissionNeverCreatesCaptureOrConnection() = runTest {
        val f = Fixture(this); f.permission = false; f.grant = false
        f.start(); runCurrent()
        assertEquals(TranslationUiError.MICROPHONE_PERMISSION, f.vm.ui.value.error)
        assertTrue(f.connections.isEmpty()); assertTrue(f.inputs.isEmpty())
    }

    @Test fun grantAfterLeavingCannotStartRecording() = runTest {
        val f = Fixture(this); f.permission = false
        val grant = CompletableDeferred<Boolean>()
        f.vm.onStart()
        f.vm.start({ grant.await() }, { f.permission }, f.visual)
        runCurrent(); f.vm.onStop(); f.permission = true; grant.complete(true); runCurrent()
        assertTrue(f.connections.isEmpty()); assertEquals(0, f.routeCreates)
        assertFalse(f.vm.ui.value.active)
    }

    @Test fun scoDeadlineFallsBackToPhoneAndReleasesPendingRoute() = runTest {
        val f = Fixture(this); f.route.wait = CompletableDeferred()
        f.start(); runCurrent(); advanceTimeBy(2_999); runCurrent()
        assertTrue(f.connections.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(TranslationInput.PHONE), f.inputs)
        assertTrue(f.vm.ui.value.phoneFallback)
        assertEquals(1, f.route.closes)
        f.vm.onStop(); assertEquals(1, f.route.closes)
    }

    @Test fun permissionRevokedWhileRoutingCannotConnect() = runTest {
        val f = Fixture(this); f.route.wait = CompletableDeferred()
        f.start(); runCurrent(); f.permission = false
        f.route.wait!!.complete(true); runCurrent()
        assertTrue(f.connections.isEmpty())
        assertEquals(1, f.route.closes)
        assertEquals(TranslationUiError.MICROPHONE_PERMISSION, f.vm.ui.value.error)
    }

    @Test fun imagesStartOnlyAfterReadyAndRemainOffWhenDisabled() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.start(); runCurrent(); assertEquals(0, f.visual.starts)
        f.latest.ready(); runCurrent(); assertEquals(1, f.visual.starts)
        assertEquals(1, f.latest.images)
        f.vm.stop(); f.settings.value = TranslateSettings(imageEnabled = false)
        f.start(); runCurrent(); f.latest.ready(); runCurrent()
        assertEquals(1, f.visual.starts)
        assertEquals(0, f.latest.images)
    }

    @Test fun terminalErrorReleasesOwnedResourcesAndCannotResumeImages() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.start(); runCurrent(); val connection = f.latest; connection.ready(); runCurrent()
        connection.text.value = TranslationText("Current translation", true)
        runCurrent(); connection.state.value = TranslateConnectionState.ERROR; runCurrent()
        assertEquals(1, connection.closes); assertEquals(1, f.route.closes)
        assertEquals(1, f.visual.stops)
        assertEquals(TranslationUiError.CONNECTION, f.vm.ui.value.error)
        val sent = connection.images
        connection.state.value = TranslateConnectionState.READY
        advanceTimeBy(1_000); runCurrent()
        assertEquals(sent, connection.images)
        assertEquals(1, f.vm.history.value.size)
    }

    @Test fun leaveDuringVisualPermissionCancelsItsJobAndOwner() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.visual.gate = CompletableDeferred()
        f.start(); runCurrent(); f.latest.ready(); runCurrent()
        f.vm.onStop(); f.visual.gate!!.complete(true); runCurrent()
        assertEquals(1, f.visual.stops); assertEquals(0, f.latest.images)
    }

    @Test fun oldErrorsAndTextCannotAffectNewAttempt() = runTest {
        val f = Fixture(this); f.start(); runCurrent(); val old = f.latest
        f.vm.stop(); f.start(); runCurrent(); val current = f.latest
        current.ready(); current.text.value = TranslationText("New", true); runCurrent()
        old.state.value = TranslateConnectionState.ERROR
        old.text.value = TranslationText("Retired", true); runCurrent()
        assertEquals(TranslationUiPhase.RECORDING, f.vm.ui.value.phase)
        assertEquals("New", f.vm.ui.value.text.text); assertEquals(0, current.closes)
    }

    @Test fun historyIsNewestFirstBoundedAndStopIsIdempotent() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(usePhoneMic = true)
        repeat(55) { index ->
            f.start(); runCurrent(); f.latest.ready()
            f.latest.text.value = TranslationText("Line $index", true); runCurrent()
            f.vm.stop(); f.vm.stop()
        }
        assertEquals(50, f.vm.history.value.size)
        assertEquals("Line 54", f.vm.history.value.first().text)
        assertEquals("Line 5", f.vm.history.value.last().text)
        f.vm.clearHistory(); assertTrue(f.vm.history.value.isEmpty())
    }

    @Test fun settingsAreSnapshottedForTheActiveAttempt() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(usePhoneMic = true)
        f.start(); runCurrent()
        f.settings.value = TranslateSettings(targetLanguage = TranslateLanguage.JA, imageEnabled = true)
        f.latest.ready(); runCurrent()
        assertEquals(TranslateLanguage.ZH, f.latest.settings!!.targetLanguage)
        assertEquals(0, f.visual.starts); assertEquals(0, f.routeCreates)
    }

    @Test fun resumeWithRevokedPermissionStopsAndNeverAutoRestarts() = runTest {
        val f = Fixture(this); f.start(); runCurrent(); f.latest.ready(); runCurrent()
        f.vm.onResume(false); runCurrent()
        assertEquals(TranslationUiError.MICROPHONE_PERMISSION, f.vm.ui.value.error)
        assertEquals(1, f.latest.closes)
        f.vm.onResume(true); runCurrent(); assertEquals(1, f.connections.size)
    }

    @Test fun cameraFailureKeepsAudioAndItsNoticeUntilStop() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.visual.succeeds = false
        f.start(); runCurrent(); f.latest.ready(); runCurrent()
        assertTrue(f.vm.ui.value.cameraUnavailable)
        assertEquals(TranslationUiPhase.RECORDING, f.vm.ui.value.phase)
        assertEquals(0, f.latest.closes); assertEquals(1, f.visual.stops)
        f.latest.text.value = TranslationText("Audio still works", true); runCurrent()
        assertTrue(f.vm.ui.value.cameraUnavailable)
    }

    @Test fun imageLoopBoundsRateAndRejectsOversizedData() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.visual.bytes = ByteArray(500_001)
        f.start(); runCurrent(); f.latest.ready(); runCurrent()
        assertEquals(0, f.latest.images)
        f.visual.bytes = byteArrayOf(1)
        advanceTimeBy(500); runCurrent(); assertEquals(1, f.latest.images)
        advanceTimeBy(499); runCurrent(); assertEquals(1, f.latest.images)
        advanceTimeBy(1); runCurrent(); assertEquals(2, f.latest.images)
        f.vm.stop(); advanceTimeBy(1_000); runCurrent(); assertEquals(2, f.latest.images)
    }

    @Test fun lateImageEncodingCannotSendIntoAReplacementAttempt() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.visual.jpegGate = CompletableDeferred()
        f.start(); runCurrent(); val old = f.latest; old.ready(); runCurrent()
        f.vm.stop(); f.settings.value = TranslateSettings(imageEnabled = false)
        f.start(); runCurrent(); val current = f.latest; current.ready(); runCurrent()
        f.visual.jpegGate!!.complete(byteArrayOf(1)); runCurrent()
        assertEquals(0, old.images); assertEquals(0, current.images)
        assertEquals(TranslationUiPhase.RECORDING, f.vm.ui.value.phase)
        assertEquals(0, current.closes)
    }

    @Test fun duplicateStartDoesNotAcquireAnotherRouteOrConnection() = runTest {
        val f = Fixture(this); f.start(); f.start(); runCurrent()
        assertEquals(1, f.routeCreates); assertEquals(1, f.connections.size)
    }

    @Test fun scoLossBeforeAcknowledgementPreventsCaptureAndRetiresTheAttempt() = runTest {
        val f = Fixture(this); f.start(); runCurrent(); val connection = f.latest
        f.route.connected.value = false
        connection.ready() // ACK beats delivery of the route observer, but must not start input.
        runCurrent()
        assertEquals(0, f.sourceStarts)
        assertEquals(1, connection.closes); assertEquals(1, f.route.closes)
        assertEquals(TranslationUiError.MICROPHONE_ROUTE, f.vm.ui.value.error)
        assertNull(f.vm.ui.value.input)
    }

    @Test fun scoLossAfterReadyStopsImagesAndCaptureUntilExplicitRetry() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.start(); runCurrent(); f.latest.ready(); runCurrent()
        assertEquals(1, f.sourceStarts)
        f.route.connected.value = false; runCurrent()
        val sent = f.latest.images
        assertEquals(1, f.latest.closes); assertEquals(1, f.visual.stops)
        assertEquals(1, f.sourceStops)
        assertEquals(TranslationUiError.MICROPHONE_ROUTE, f.vm.ui.value.error)
        assertNull(f.vm.ui.value.input)
        f.route.connected.value = true; advanceTimeBy(1_000); runCurrent()
        assertEquals(sent, f.latest.images); assertEquals(1, f.connections.size)
    }

    @Test fun retiredRouteDisconnectionCannotStopItsReplacement() = runTest {
        val f = Fixture(this); f.start(); runCurrent(); f.latest.ready(); runCurrent()
        val oldRoute = f.route
        f.vm.stop(); f.route = FakeRoute(); f.start(); runCurrent()
        f.latest.ready(); runCurrent()
        oldRoute.connected.value = true; runCurrent(); oldRoute.connected.value = false; runCurrent()
        assertEquals(TranslationUiPhase.RECORDING, f.vm.ui.value.phase)
        assertEquals(0, f.latest.closes)
    }

    @Test fun foreignCommunicationRouteStopsVisiblyWithoutCreatingPhoneCapture() = runTest {
        val f = Fixture(this); f.route.failure = TranslationRouteBusyException()
        f.start(); runCurrent()
        assertEquals(TranslationUiError.MICROPHONE_ROUTE, f.vm.ui.value.error)
        assertNull(f.vm.ui.value.input); assertFalse(f.vm.ui.value.phoneFallback)
        assertTrue(f.inputs.isEmpty()); assertTrue(f.connections.isEmpty())
        assertEquals(1, f.route.closes)
    }

    @Test fun losingCameraLeaseStopsImagesWithGuidanceWhileAudioContinues() = runTest {
        val f = Fixture(this); f.settings.value = TranslateSettings(imageEnabled = true)
        f.start(); runCurrent(); f.latest.ready(); runCurrent()
        assertEquals(1, f.latest.images)
        f.visual.jpegFailure = IllegalStateException("Camera unavailable")
        advanceTimeBy(500); runCurrent()
        assertTrue(f.vm.ui.value.cameraUnavailable)
        assertEquals(TranslationUiPhase.RECORDING, f.vm.ui.value.phase)
        assertEquals(1, f.visual.stops); assertEquals(0, f.latest.closes)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, f.latest.images)
        f.vm.stop(); assertEquals(1, f.visual.stops)
    }

    @Test fun stopSnapshotsNewestServiceTextBeforeCloseAndBeforeCollectorRuns() = runTest {
        val f = Fixture(this); f.start(); runCurrent(); f.latest.ready(); runCurrent()
        f.latest.text.value = TranslationText("Newest", true, "Original")
        assertEquals("", f.vm.ui.value.text.text)
        f.vm.stop(); f.vm.stop(); runCurrent()
        assertEquals("Newest", f.vm.ui.value.text.text)
        assertEquals("Original", f.vm.ui.value.text.originalText)
        assertEquals("Newest", f.vm.history.value.single().text)
        assertEquals("", f.latest.text.value.text) // Actual close clears the service snapshot.
    }

    @Test fun immediateErrorSavesLatestTextEvenWhenTextCollectorIsQueuedLater() = runTest {
        val f = Fixture(this); f.start(); runCurrent(); f.latest.ready(); runCurrent()
        f.latest.state.value = TranslateConnectionState.ERROR // Queue error observer first.
        f.latest.text.value = TranslationText("Final before error", true)
        runCurrent()
        assertEquals("Final before error", f.vm.history.value.single().text)
        assertEquals("Final before error", f.vm.ui.value.text.text)
        val old = f.latest
        f.start(); runCurrent(); f.latest.text.value = TranslationText("Replacement", true)
        old.text.value = TranslationText("Late retired text", true)
        f.vm.stop(); runCurrent()
        assertEquals(listOf("Replacement", "Final before error"), f.vm.history.value.map { it.text })
    }

    private class Fixture(scope: TestScope) {
        val settings = MutableStateFlow(TranslateSettings())
        var key: TranslationCredentials? = TranslationCredentials("fixture", "wss://fixture")
        var permission = true; var grant = true; var permissionRequests = 0; var routeCreates = 0
        var sourceStarts = 0; var sourceStops = 0
        var route = FakeRoute(); val visual = FakeVisual()
        val connections = mutableListOf<FakeConnection>(); val inputs = mutableListOf<TranslationInput>()
        val latest get() = connections.last()
        val vm = LiveTranslateViewModel(settings, { key },
            { FakeConnection().also { connections += it } },
            { routeCreates++; route },
            { input -> inputs += input; object : PcmAudioSource {
                override fun start(onChunk: (ByteArray) -> Unit): Boolean { sourceStarts++; return true }
                override fun stop() { sourceStops++ }
            } }, scope.backgroundScope, { scope.testScheduler.currentTime })
        fun start() {
            vm.onStart()
            vm.start({ permissionRequests++; permission = grant; grant }, { permission }, visual)
        }
    }

    private class FakeRoute : TranslationAudioRoute {
        var wait: CompletableDeferred<Boolean>? = null; var closes = 0
        var failure: Exception? = null
        override val connected = MutableStateFlow(false)
        override suspend fun prepare(): Boolean {
            failure?.let { throw it }
            return (wait?.await() ?: true).also { connected.value = it }
        }
        override fun close() { closes++; connected.value = false }
    }
    private class FakeVisual : TranslationVisualInput {
        var starts = 0; var stops = 0; var succeeds = true
        var gate: CompletableDeferred<Boolean>? = null; var bytes = byteArrayOf(1)
        var jpegGate: CompletableDeferred<ByteArray>? = null
        var jpegFailure: Exception? = null
        override suspend fun start(): Boolean { starts++; return gate?.await() ?: succeeds }
        override suspend fun nextJpeg(): ByteArray? {
            jpegFailure?.let { throw it }
            return jpegGate?.let { gate ->
                withContext(NonCancellable) { gate.await() } // Models encoding already running on a worker.
            } ?: bytes
        }
        override fun stop() { stops++ }
    }
    private class FakeConnection : TranslationConnection {
        override val state = MutableStateFlow(TranslateConnectionState.DISCONNECTED)
        override val text = MutableStateFlow(TranslationText("", false))
        override val error = MutableStateFlow<String?>(null)
        var settings: TranslateSettings? = null; var closes = 0; var images = 0
        private var source: PcmAudioSource? = null
        override fun connect(apiKey: String, endpoint: String, settings: TranslateSettings, source: PcmAudioSource) {
            this.settings = settings; this.source = source; state.value = TranslateConnectionState.CONNECTING
        }
        fun ready() { state.value = if (source?.start {} == true) TranslateConnectionState.READY else TranslateConnectionState.ERROR }
        override fun sendImage(jpeg: ByteArray): Boolean { images++; return true }
        override fun close() {
            closes++; source?.stop(); source = null
            state.value = TranslateConnectionState.DISCONNECTED; text.value = TranslationText("", false)
        }
    }
}
