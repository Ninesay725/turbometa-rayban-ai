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
        override suspend fun awaitActiveDevice(timeoutMs: Long): Boolean = true
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
}
