package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraPermissionCheck
import com.smartview.glassai.glasses.DisplayAction
import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.FakeControllerRegistry
import com.smartview.glassai.glasses.FakeDatDeviceObserver
import com.smartview.glassai.glasses.FakeDatSessionFactory
import com.smartview.glassai.glasses.FrameSnapshot
import com.smartview.glassai.glasses.GlassesActionRouter
import com.smartview.glassai.glasses.GlassesControllerRegistry
import com.smartview.glassai.glasses.GlassesDeviceInfo
import com.smartview.glassai.glasses.GlassesDisplayState
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.GlassesPhotoCapturer
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.NavigationRequest
import com.smartview.glassai.glasses.PhotoCaptureResult
import com.smartview.glassai.glasses.RecordingDisplaySink
import com.smartview.glassai.glasses.SessionFrameProvider
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

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
    private val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sink = RecordingDisplaySink()
    private val controllers = FakeControllerRegistry()
    private val viewModels = ViewModelStore()
    private var viewModelCount = 0

    private class FakeFrames : GlassesFrameProvider {
        var result: SnapshotResult = SnapshotResult.NoFrame
        var pending: CompletableDeferred<SnapshotResult>? = null
        override suspend fun awaitActiveDevice(timeoutMs: Long): Boolean = true
        override val isStreaming = false
        override val streamStatus = "stopped"
        override val hasFrame = false
        override suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult = pending?.await() ?: result
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

    /** Scriptable audio route: `sco` is the live SCO link, flipped the way the manager's receiver would. */
    private class FakeAudioRoute : OpenClawAudioRoute {
        override val bluetoothAvailable = MutableStateFlow(true)
        val sco = MutableStateFlow(false)
        override val scoConnected: StateFlow<Boolean> = sco
        var startCalls = 0
        var stopCalls = 0
        var cleanupCalls = 0
        override fun startSco() { startCalls++ }
        override fun stopSco() { stopCalls++ }
        override fun cleanup() { cleanupCalls++ }
    }

    private val frames = FakeFrames()
    private val asr = FakeAsr()
    private val route = FakeAudioRoute()
    private val asrCreatedWith = mutableListOf<Triple<String, AlibabaEndpoint, BluetoothAudioManager.AudioSource>>()
    private var alibabaKey: String? = "sk"

    private fun str(id: Int) = "str:$id"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        manager = GlassesSessionManager(factory, observer, managerScope).also { it.startMonitoring() }
    }

    @After
    fun tearDown() {
        viewModels.clear()
        manager.resetForTests()
        managerScope.cancel()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        audioRoute: OpenClawAudioRoute? = null,
        frameProvider: GlassesFrameProvider = frames,
        registry: GlassesControllerRegistry = controllers,
        ownerStore: ViewModelStore = viewModels,
    ) = OpenClawViewModel(
        application = Application(),
        service = service,
        frames = frameProvider,
        sessionManager = { manager },
        asrFactory = { key, endpoint, source ->
            asrCreatedWith += Triple(key, endpoint, source)
            asr
        },
        alibabaKey = { alibabaKey },
        alibabaEndpoint = { AlibabaEndpoint.BEIJING },
        audioRoute = audioRoute,
        strings = ::str,
        decodeImage = { TestBitmaps.stub() },
        sink = sink,
        controllers = registry,
        decodeDispatcher = dispatcher, // keeps withContext(decodeDispatcher) on the test scheduler
    ).also { ownerStore.put("openClaw-${viewModelCount++}", it) }

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
        val vm = newViewModel() // no audio route on the JVM: only the desired source moves
        vm.startListening()

        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)

        assertEquals(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC, vm.desiredAudioSource.value)
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

    @Test
    fun sendingIsGatedOnConnectedSoAPreHelloSocketNeverSeesChatSend() {
        // OpenClawNodeService.sendChatMessage() answers true for any live socket, including one
        // that has not completed the hello — the ViewModel is the gate (Task 3 report, known gap).
        val vm = newViewModel()
        vm.onInputChanged("hi")
        vm.sendText()
        vm.startListening()
        asr.onFinalResult!!("voice")
        vm.sendAsrText()
        frames.result = SnapshotResult.Ok(FrameSnapshot(byteArrayOf(1, 2, 3), 4, 3))
        vm.snapAndSend()

        // The bubbles are local and always appended; only the wire sends are suppressed.
        assertEquals(listOf("hi", "voice", str(R.string.openclaw_chat_photoprompt)), vm.messages.value.map { it.text })
        assertEquals(3, vm.suppressedSends)
    }

    @Test
    fun asrTransportErrorsAreMappedToLocalizedText() {
        val vm = newViewModel()
        // FunASRService's three English literals are wire/log-level; the chat screen shows the
        // localized resource instead. Anything else (a DashScope server message) passes through.
        assertEquals(str(R.string.openclaw_asr_error_mic), vm.localizeAsrError("Microphone unavailable"))
        assertEquals(str(R.string.openclaw_asr_error_connection), vm.localizeAsrError("Connection failed"))
        assertEquals(str(R.string.openclaw_asr_error_task), vm.localizeAsrError("ASR task failed"))
        assertEquals("boom", vm.localizeAsrError("boom"))
    }

    @Test
    fun listeningStopsWhenTheScreenIsLeft() {
        val vm = newViewModel()
        vm.enterScreen()
        vm.startListening()

        vm.leaveScreen()

        assertFalse(vm.isListening.value)
        assertEquals(1, asr.stopCalls)
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun desiredSourceSurvivesFinishListening() {
        // The regression: finishListening() used to stop SCO, BluetoothAudioManager's
        // SCO_AUDIO_STATE_DISCONNECTED receiver flipped its own source back to PHONE_MIC, and the
        // chip (bound to that flow) snapped back — so the next utterance recorded from the phone.
        val vm = newViewModel(route)
        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)
        assertEquals(1, route.startCalls)
        route.sco.value = true

        vm.startListening()
        vm.stopListening()

        assertEquals(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC, vm.desiredAudioSource.value)
        assertEquals(0, route.stopCalls) // SCO is session-scoped, never per utterance

        vm.startListening() // the next utterance still opens on the glasses mic
        assertEquals(
            listOf(
                BluetoothAudioManager.AudioSource.BLUETOOTH_MIC,
                BluetoothAudioManager.AudioSource.BLUETOOTH_MIC,
            ),
            asrCreatedWith.map { it.third },
        )

        vm.leaveScreen()
        assertEquals(1, route.stopCalls)
    }

    @Test
    fun leaveScreenStopsSco() {
        val vm = newViewModel(route)
        vm.enterScreen()
        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)
        assertEquals(1, route.startCalls)
        assertEquals(0, route.stopCalls)

        vm.leaveScreen()

        assertEquals(1, route.stopCalls)
        assertEquals(0, manager.ownerCount)
    }

    /**
     * Final review I4 (VM half): the SCO link may never come up (SCO_AUDIO_STATE_ERROR, or the user
     * leaves inside the 3 s window). The ViewModel must still call stopSco() on the way out —
     * BluetoothAudioManager then resets MODE_IN_COMMUNICATION even though it never saw a
     * SCO_AUDIO_STATE_CONNECTED broadcast (the manager half of the fix is `scoRequested`).
     */
    @Test
    fun releaseScoAfterAFailedArmStillCallsStopSco() {
        val vm = newViewModel(route)
        vm.enterScreen()
        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)
        assertEquals(1, route.startCalls)
        assertFalse(route.sco.value) // the link never came up

        vm.leaveScreen()

        assertEquals(1, route.stopCalls)
    }

    @Test
    fun switchingBackToThePhoneMicStopsSco() {
        val vm = newViewModel(route)
        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)

        vm.switchAudioSource(BluetoothAudioManager.AudioSource.PHONE_MIC)

        assertEquals(1, route.stopCalls)
        assertEquals(BluetoothAudioManager.AudioSource.PHONE_MIC, vm.desiredAudioSource.value)
    }

    @Test
    fun startListeningWaitsForScoBeforeStartingAsr() {
        // AudioRecord on VOICE_COMMUNICATION before the SCO link exists records silence.
        val vm = newViewModel(route)
        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)

        vm.startListening()

        assertTrue(vm.isListening.value)
        assertEquals(0, asr.startCalls)
        assertTrue(asrCreatedWith.isEmpty())

        route.sco.value = true // SCO_AUDIO_STATE_CONNECTED

        assertEquals(1, asr.startCalls)
        assertEquals(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC, asrCreatedWith.single().third)
        assertNull(vm.asrNotice.value)
    }

    @Test
    fun scoTimeoutFallsBackToPhoneMicForThatUtterance() {
        val vm = newViewModel(route)
        vm.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)

        vm.startListening()
        assertEquals(0, asr.startCalls)

        dispatcher.scheduler.advanceUntilIdle() // the bounded wait elapses, SCO never connects

        assertEquals(1, asr.startCalls)
        assertEquals(BluetoothAudioManager.AudioSource.PHONE_MIC, asrCreatedWith.single().third)
        assertEquals(str(R.string.openclaw_asr_sco_timeout), vm.asrNotice.value)
        // Fallback is for this utterance only: the user's choice is untouched.
        assertEquals(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC, vm.desiredAudioSource.value)
    }

    @Test
    fun sendAsrTextClearsTheAsrError() {
        val vm = newViewModel()
        vm.startListening()
        asr.onFinalResult!!("hello")
        asr.onError!!("boom")
        assertEquals(str(R.string.openclaw_chat_asr_failed), vm.asrError.value)

        vm.sendAsrText()

        assertNull(vm.asrError.value)
        assertEquals("hello", vm.messages.value.single().text)
    }

    @Test
    fun enterScreenAcquiresAndStartsOnce() {
        val vm = newViewModel()
        vm.enterScreen()
        vm.enterScreen()

        assertEquals(1, manager.ownerCount)
        assertEquals(1, factory.createCalls)
        assertEquals(1, factory.last.startCalls)
        assertFalse(manager.hasCameraClaim)
        vm.leaveScreen()
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun leavingAfterTheStartTimeoutStillReleasesTheSessionClaim() {
        val vm = newViewModel()
        vm.enterScreen()
        dispatcher.scheduler.advanceTimeBy(OpenClawViewModel.SESSION_START_TIMEOUT_MS)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, manager.ownerCount)

        vm.leaveScreen()

        assertEquals(0, manager.ownerCount)
        assertFalse(manager.hasSession)
        assertEquals(1, factory.last.stopCalls)
    }

    @Test
    fun enterScreenAfterAStoppingSessionStillGetsASession() {
        factory.stopAsync = true
        manager.acquire("previous")
        factory.last.emitStarted()
        manager.release("previous")
        val vm = newViewModel()

        vm.enterScreen()
        assertEquals(1, factory.createCalls)
        factory.sessions[0].emitStoppedByDevice()

        assertEquals(2, factory.createCalls)
        assertEquals(DeviceSessionState.STARTING, manager.sessionState.value)
        assertEquals(1, manager.ownerCount)
        assertFalse(manager.hasCameraClaim)
    }

    @Test
    fun leaveScreenDuringTheStoppingWaitCreatesNoSession() {
        factory.stopAsync = true
        manager.acquire("previous")
        factory.last.emitStarted()
        manager.release("previous")
        val vm = newViewModel()
        vm.enterScreen()
        assertEquals(1, manager.ownerCount)
        assertEquals(1, factory.createCalls)

        vm.leaveScreen()
        assertEquals(0, manager.ownerCount)
        factory.sessions[0].emitStoppedByDevice()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, factory.createCalls)
        assertFalse(manager.hasSession)
        assertEquals(DeviceSessionState.STOPPED, manager.sessionState.value)
    }

    @Test
    fun leaveAndReenterDuringTheStoppingWaitAcquiresOnlyForTheNewEntry() {
        factory.stopAsync = true
        manager.acquire("previous")
        factory.last.emitStarted()
        manager.release("previous")
        val vm = newViewModel()
        vm.enterScreen()
        vm.leaveScreen()
        vm.enterScreen()
        factory.sessions[0].emitStoppedByDevice()

        assertEquals(2, factory.createCalls)
        assertEquals(1, manager.ownerCount)
        assertEquals(1, factory.last.startCalls)
        assertFalse(manager.hasCameraClaim)
    }

    @Test
    fun chatDeltaShowsAPendingOpenClawCard() {
        val vm = newViewModel()
        vm.enterScreen()
        vm.onInputChanged("What is this?")
        vm.sendText()
        vm.onChatEvent("It is", isFinal = false)
        vm.onChatEvent("It is rice", isFinal = false)

        assertEquals(DisplayCard.OpenClaw("What is this?", "It is rice", false), sink.last)
    }

    @Test
    fun finalChatShowsAFinalCard() {
        val vm = newViewModel()
        vm.enterScreen()
        vm.onChatEvent("A complete answer", isFinal = true)
        assertEquals(DisplayCard.OpenClaw(null, "A complete answer", true), sink.last)
    }

    @Test
    fun sendTextShowsTheUserTextWithAnEmptyReply() {
        val vm = newViewModel()
        vm.enterScreen()
        vm.onInputChanged("  hello  ")
        vm.sendText()
        assertEquals(DisplayCard.OpenClaw("hello", "", false), sink.last)
    }

    @Test
    fun sendAsrTextShowsTheRecognizedTextAndAssociatesTheReply() {
        val vm = newViewModel()
        vm.enterScreen()
        vm.startListening()
        asr.onFinalResult!!("hello")
        asr.onPartialResult!!(" there")
        vm.sendAsrText()
        assertEquals(DisplayCard.OpenClaw("hello there", "", false), sink.last)

        vm.onChatEvent("Hi!", isFinal = true)
        assertEquals(DisplayCard.OpenClaw("hello there", "Hi!", true), sink.last)
    }

    @Test
    fun leaveScreenReturnsToStatusAndLateChatCannotRestoreTheCard() {
        val vm = newViewModel()
        vm.enterScreen()
        vm.onChatEvent("partial", isFinal = false)
        vm.leaveScreen()
        val shownAtExit = sink.shown.size
        vm.onChatEvent("late delta", isFinal = false)
        vm.onChatEvent("late final", isFinal = true)

        assertEquals(1, sink.statusCalls)
        assertEquals(shownAtExit, sink.shown.size)
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun controllerIsRegisteredAndSnapForwardsToSnapAndSend() {
        val vm = newViewModel()
        vm.enterScreen()
        frames.pending = CompletableDeferred()
        vm.onInputChanged("What food?")

        controllers.openClaw!!.snapAndSend()
        assertTrue(vm.isSending.value)
        frames.pending!!.complete(SnapshotResult.Ok(FrameSnapshot(byteArrayOf(1, 2, 3), 4, 3)))

        assertFalse(vm.isSending.value)
        assertEquals("What food?", vm.messages.value.single().text)
        assertEquals(DisplayCard.OpenClaw("What food?", "", false), sink.last)
        vm.onChatEvent("Rice", isFinal = true)
        assertEquals(DisplayCard.OpenClaw("What food?", "Rice", true), sink.last)
    }

    @Test
    fun aControllerTapAfterLeavingCannotStartAnotherSnap() {
        val vm = newViewModel()
        vm.enterScreen()
        val oldController = controllers.openClaw!!
        vm.leaveScreen()
        assertNull(controllers.openClaw)
        frames.pending = CompletableDeferred()

        oldController.snapAndSend()

        assertFalse(vm.isSending.value)
        assertTrue(vm.messages.value.isEmpty())
        assertTrue(sink.shown.isEmpty())
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun snapTapNavigatesAfterLeavingAndSnapsAgainAfterReentry() {
        val navigation = MutableSharedFlow<NavigationRequest>(extraBufferCapacity = 4)
        val requests = mutableListOf<NavigationRequest>()
        managerScope.launch { navigation.collect { requests += it } }
        val router = GlassesActionRouter(sink, navigation) {}
        val vm = newViewModel(registry = router)
        vm.enterScreen()
        vm.leaveScreen() // the ViewModel remains alive in its store

        router.dispatch(DisplayAction.OpenClawSnap)

        assertEquals(listOf(NavigationRequest.OpenClaw), requests)
        assertFalse(vm.isSending.value)
        vm.enterScreen()
        frames.pending = CompletableDeferred()
        router.dispatch(DisplayAction.OpenClawSnap)

        assertTrue(vm.isSending.value)
        assertEquals(listOf(NavigationRequest.OpenClaw), requests)
        frames.pending!!.complete(SnapshotResult.NoFrame)
        assertEquals(str(R.string.openclaw_chat_noframe), vm.messages.value.single().text)
    }

    @Test
    fun leavingAndClearingAnOlderViewModelKeepsTheNewController() {
        val navigation = MutableSharedFlow<NavigationRequest>(extraBufferCapacity = 4)
        val requests = mutableListOf<NavigationRequest>()
        managerScope.launch { navigation.collect { requests += it } }
        val router = GlassesActionRouter(sink, navigation) {}
        val previousStore = ViewModelStore()
        try {
            val previous = newViewModel(registry = router, ownerStore = previousStore)
            previous.enterScreen()
            previous.leaveScreen()
            val current = newViewModel(registry = router)
            current.enterScreen()

            previous.leaveScreen()
            frames.pending = CompletableDeferred()
            router.dispatch(DisplayAction.OpenClawSnap)
            assertTrue(current.isSending.value)
            assertFalse(previous.isSending.value)
            frames.pending!!.complete(SnapshotResult.NoFrame)

            previousStore.clear()
            frames.pending = CompletableDeferred()
            router.dispatch(DisplayAction.OpenClawSnap)
            assertTrue(current.isSending.value)
            assertFalse(previous.isSending.value)
            assertTrue(requests.isEmpty())
            assertEquals(1, manager.ownerCount)
            frames.pending!!.complete(SnapshotResult.NoFrame)
        } finally {
            previousStore.clear()
        }
    }

    @Test
    fun snapCompletionFromThePreviousEntryCannotRestoreItsCard() {
        val vm = newViewModel()
        vm.enterScreen()
        frames.pending = CompletableDeferred()
        vm.snapAndSend()
        vm.leaveScreen()
        vm.enterScreen()
        vm.onInputChanged("New question")
        vm.sendText()
        val shownAtExit = sink.shown.size

        frames.pending!!.complete(SnapshotResult.Ok(FrameSnapshot(byteArrayOf(1, 2, 3), 4, 3)))

        assertEquals(shownAtExit, sink.shown.size)
        assertEquals(DisplayCard.OpenClaw("New question", "", false), sink.last)
        vm.onChatEvent("New answer", isFinal = true)
        assertEquals(DisplayCard.OpenClaw("New question", "New answer", true), sink.last)
    }

    @Test
    fun leavingCancelsASnapWithoutLettingItsLateCompletionClearTheNextSnapBusyFlag() {
        val first = CompletableDeferred<SnapshotResult>()
        val second = CompletableDeferred<SnapshotResult>()
        var calls = 0
        val provider = object : GlassesFrameProvider by frames {
            override suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult =
                if (++calls == 1) withContext(NonCancellable) { first.await() } else second.await()
        }
        val vm = newViewModel(frameProvider = provider)
        vm.enterScreen()
        vm.snapAndSend()
        assertTrue(vm.isSending.value)
        vm.leaveScreen()
        assertFalse(vm.isSending.value)
        vm.enterScreen()
        vm.snapAndSend()
        first.complete(SnapshotResult.Ok(FrameSnapshot(byteArrayOf(1), 1, 1)))

        assertTrue(vm.isSending.value)
        assertTrue(vm.messages.value.isEmpty())
        assertTrue(sink.shown.isEmpty())
        second.complete(SnapshotResult.Ok(FrameSnapshot(byteArrayOf(2), 1, 1)))
        assertFalse(vm.isSending.value)
        assertEquals(1, vm.messages.value.size)
        assertEquals(1, sink.shown.size)
    }

    @Test
    fun onClearedUnregistersTheControllerAndReturnsToStatus() {
        val vm = newViewModel()
        vm.enterScreen()
        viewModels.clear()

        assertNull(controllers.openClaw)
        assertEquals(1, controllers.openClawUnregisterCalls)
        assertEquals(1, sink.statusCalls)
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun realProviderSnapBorrowsCameraWhileChatKeepsSessionAndDisplayAlive() {
        observer.device.value = GlassesDeviceInfo(
            "display-1", "Display glasses", DeviceType.RAYBAN_META, true, DeviceCompatibility.COMPATIBLE,
        )
        factory.nextCaptureResult = PhotoCaptureResult.Success(PhotoData.HEIC(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        val captured = TestBitmaps.stub()
        val encoded = mutableListOf<Triple<Bitmap, Int, Double>>()
        val provider = SessionFrameProvider(
            sessionManager = { manager },
            isForeground = { true },
            checkPermission = { CameraPermissionCheck.Granted },
            encode = { bitmap, width, quality ->
                encoded += Triple(bitmap, width, quality)
                FrameSnapshot(byteArrayOf(1, 2, 3), 4, 3)
            },
            capture = { sharedManager ->
                GlassesPhotoCapturer(
                    sessionManager = sharedManager,
                    owner = SessionFrameProvider.OWNER,
                    config = StreamConfiguration(),
                    decodePhoto = { captured },
                    decodeFrame = { null },
                    frameDispatcher = dispatcher,
                ).capture()
            },
            encodeDispatcher = dispatcher,
        )
        val vm = newViewModel(frameProvider = provider)
        vm.enterScreen()
        factory.last.emitStarted()
        val session = factory.last
        session.display.emitStarted()
        assertEquals(GlassesDisplayState.STARTED, manager.displayState.value)
        assertFalse(manager.hasCameraClaim)

        vm.snapAndSend()
        assertTrue(vm.isSending.value)
        assertEquals(2, manager.ownerCount)
        assertEquals(SessionFrameProvider.OWNER, manager.currentCameraOwner)
        session.cameras.single().stateFlow.value = StreamState.STREAMING
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.isSending.value)
        assertEquals(listOf(Triple(captured, 1600, 0.7)), encoded)
        assertEquals(str(R.string.openclaw_chat_photoprompt), vm.messages.value.single().text)
        assertTrue(vm.messages.value.single().image != null)
        assertEquals(1, factory.createCalls)
        assertEquals(1, manager.ownerCount)
        assertFalse(manager.hasCameraClaim)
        assertNull(manager.currentCameraOwner)
        assertEquals(1, session.cameras.single().stopCalls)
        assertEquals(0, session.stopCalls)
        assertEquals(0, session.removeDisplayCalls)
        assertEquals(GlassesDisplayState.STARTED, manager.displayState.value)

        vm.leaveScreen()
        assertEquals(0, manager.ownerCount)
        assertFalse(manager.hasSession)
        assertEquals(listOf("removeDisplay", "stop"), session.lifecycleCalls)
        assertEquals(1, sink.statusCalls)
    }
}
