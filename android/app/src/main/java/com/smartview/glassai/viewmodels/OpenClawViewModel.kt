package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartview.glassai.R
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SnapshotResult
import com.smartview.glassai.managers.APIProvider
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.BluetoothAudioManager
import com.smartview.glassai.services.FunASRService
import com.smartview.glassai.services.HttpClients
import com.smartview.glassai.services.SpeechRecognizerSession
import com.smartview.glassai.services.openclaw.OpenClawChatMessage
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawIntegration
import com.smartview.glassai.services.openclaw.OpenClawNodeService
import com.smartview.glassai.utils.APIKeyManager
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * State for OpenClawChatScreen (research §5.1). Messages live only in memory (spec §3 decision 3).
 * Entering the chat acquires the shared glasses session (spec §3 decision 1) so a snap does not
 * pay the session start; the camera itself is borrowed per snap by the process-wide
 * SessionFrameProvider (OpenClawIntegration.frameProvider). The claim is deliberately session-only
 * (no camera intent), so it never blocks a snap — see GlassesSessionManager.hasCameraClaim.
 */
class OpenClawViewModel internal constructor(
    application: Application,
    private val service: OpenClawNodeService,
    private val frames: GlassesFrameProvider,
    private val sessionManager: () -> GlassesSessionManager,
    private val asrFactory: (String, AlibabaEndpoint, BluetoothAudioManager.AudioSource) -> SpeechRecognizerSession,
    private val alibabaKey: () -> String?,
    private val alibabaEndpoint: () -> AlibabaEndpoint,
    private val audioRoute: OpenClawAudioRoute?,
    private val strings: (Int) -> String,
    private val decodeImage: (ByteArray) -> Bitmap?,
    /** Where the snapped JPEG is decoded into the bubble bitmap (never Main in the app). */
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        service = OpenClawNodeService.getInstance(application),
        // One provider per process: two instances would both borrow the camera as
        // "OpenClawSnap" and could release each other's claim (final review I1).
        frames = OpenClawIntegration.frameProvider(application),
        sessionManager = { GlassesSessionManager.getInstance(application) },
        asrFactory = { key, endpoint, source ->
            FunASRService(
                apiKey = key,
                endpoint = endpoint,
                httpClient = HttpClients.websocket,
                audioSourceFactory = FunASRService.defaultAudioSourceFactory(),
                initialAudioSource = source,
            )
        },
        alibabaKey = {
            val manager = APIKeyManager.getInstance(application)
            manager.getAPIKey(APIProvider.ALIBABA, APIProviderManager.staticAlibabaEndpoint)
        },
        alibabaEndpoint = {
            // Binds APIProviderManager's static prefs before the static getter is read: without an
            // instance it silently answers BEIJING even when the user picked Singapore.
            APIProviderManager.getInstance(application)
            APIProviderManager.staticAlibabaEndpoint
        },
        audioRoute = BluetoothAudioRoute(BluetoothAudioManager(application)),
        strings = { id -> application.getString(id) },
        decodeImage = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) },
    )

    companion object {
        private const val TAG = "OpenClawViewModel"
        const val OWNER = "OpenClawChat"
        private const val SNAP_MAX_WIDTH = 1600
        private const val SNAP_QUALITY = 0.7
        private const val SNAP_TIMEOUT_MS = 5_000L

        /** Bounded wait for the SCO link before the recognizer opens its AudioRecord. */
        @VisibleForTesting
        internal const val SCO_WAIT_MS = 3_000L

        /** FunASRService's wire/log-level English texts, mapped to localized resources for the UI. */
        private val ASR_ERROR_STRINGS: Map<String, Int> = mapOf(
            "Microphone unavailable" to R.string.openclaw_asr_error_mic,
            "Connection failed" to R.string.openclaw_asr_error_connection,
            "ASR task failed" to R.string.openclaw_asr_error_task,
        )
    }

    val connectionState: StateFlow<OpenClawConnectionState> = service.connectionState

    private val _messages = MutableStateFlow<List<OpenClawChatMessage>>(emptyList())
    val messages: StateFlow<List<OpenClawChatMessage>> = _messages.asStateFlow()

    /** The streaming assistant bubble (replace-style deltas). */
    private val _pendingResponse = MutableStateFlow<String?>(null)
    val pendingResponse: StateFlow<String?> = _pendingResponse.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _showTextInput = MutableStateFlow(false)
    val showTextInput: StateFlow<Boolean> = _showTextInput.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** Final sentences accumulated since the mic was started. */
    private val _asrText = MutableStateFlow("")
    val asrText: StateFlow<String> = _asrText.asStateFlow()

    /** The interim (not yet final) sentence. */
    private val _asrPartial = MutableStateFlow("")
    val asrPartial: StateFlow<String> = _asrPartial.asStateFlow()

    private val _asrError = MutableStateFlow<String?>(null)
    val asrError: StateFlow<String?> = _asrError.asStateFlow()

    /** Non-fatal voice notice (currently: the SCO fallback), shown next to the live transcript. */
    private val _asrNotice = MutableStateFlow<String?>(null)
    val asrNotice: StateFlow<String?> = _asrNotice.asStateFlow()

    private val _desiredAudioSource = MutableStateFlow(BluetoothAudioManager.AudioSource.PHONE_MIC)

    /**
     * The microphone the user chose, owned here and never read back from BluetoothAudioManager:
     * the manager's own currentAudioSource tracks the *live* SCO link and falls back to PHONE_MIC
     * the instant SCO drops, so binding the chip to it made the selection disappear between
     * utterances and silently recorded the next one from the phone.
     */
    val desiredAudioSource: StateFlow<BluetoothAudioManager.AudioSource> = _desiredAudioSource.asStateFlow()

    val isBluetoothAvailable: StateFlow<Boolean> =
        audioRoute?.bluetoothAvailable ?: MutableStateFlow(false)

    /** Test hook: how many chat.send calls the Connected gate suppressed. */
    @VisibleForTesting
    internal var suppressedSends = 0
        private set

    private var asr: SpeechRecognizerSession? = null
    private var chatJob: Job? = null
    private var listenJob: Job? = null
    private var sessionHeld = false

    /** True while this ViewModel is holding the SCO link open for the chat session. */
    private var scoHeld = false

    private fun str(@StringRes id: Int): String = strings(id)

    init {
        // Subscribed BEFORE any connect() this ViewModel issues: chatEvents has replay 0, so a
        // `chat` event that lands between connect() and the first collector would be dropped.
        chatJob = viewModelScope.launch {
            service.chatEvents.collect { event -> onChatEvent(event.text, event.isFinal) }
        }
    }

    // ---- screen lifecycle ----

    /** Acquire the shared session (spec §3 decision 1) and auto-connect when a token is stored. */
    fun enterScreen() {
        if (!sessionHeld) {
            sessionHeld = true
            sessionManager().acquire(OWNER)
        }
        connectIfNeeded()
    }

    fun leaveScreen() {
        stopListening()
        releaseSco()
        flushPendingResponse()
        if (sessionHeld) {
            sessionHeld = false
            sessionManager().release(OWNER)
        }
    }

    fun connectIfNeeded() {
        if (service.connectionState.value == OpenClawConnectionState.Disconnected &&
            !service.loadGatewayToken().isNullOrBlank()
        ) {
            service.connect()
        }
    }

    // ---- chat ----

    /** Visible for tests; the production path is the chatEvents collector. */
    fun onChatEvent(text: String, isFinal: Boolean) {
        if (isFinal) {
            _pendingResponse.value = null
            if (text.isNotBlank()) append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = text))
        } else {
            _pendingResponse.value = text
        }
    }

    fun flushPendingResponse() {
        val pending = _pendingResponse.value?.takeIf { it.isNotBlank() }
        _pendingResponse.value = null
        if (pending != null) append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = pending))
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
    }

    fun toggleTextInput() {
        _showTextInput.value = !_showTextInput.value
    }

    fun sendText() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        flushPendingResponse()
        append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_USER, text = text))
        _inputText.value = ""
        deliver(text)
    }

    /** Snap & Send: latest glasses frame (or a fresh capture) + the typed text or the photo prompt. */
    fun snapAndSend() {
        if (_isSending.value) return
        _isSending.value = true
        viewModelScope.launch {
            try {
                when (val result = frames.snapshot(SNAP_MAX_WIDTH, SNAP_QUALITY, SNAP_TIMEOUT_MS)) {
                    is SnapshotResult.Ok -> {
                        val text = _inputText.value.trim().ifEmpty { str(R.string.openclaw_chat_photoprompt) }
                        // BitmapFactory.decodeByteArray of a <= 1600 px JPEG is not main-thread work
                        val image = withContext(decodeDispatcher) { decodeImage(result.frame.jpeg) }
                        flushPendingResponse()
                        append(
                            OpenClawChatMessage(
                                role = OpenClawChatMessage.ROLE_USER,
                                text = text,
                                image = image,
                            )
                        )
                        _inputText.value = ""
                        val base64 = Base64.getEncoder().encodeToString(result.frame.jpeg)
                        deliver(text, imageJpegBase64 = base64)
                    }
                    else -> {
                        Log.w(TAG, "snap failed: $result")
                        append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = str(R.string.openclaw_chat_noframe)))
                    }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * The single wire-send path, gated on [OpenClawConnectionState.Connected] (Task 3 known gap):
     * OpenClawNodeService.sendChatMessage() answers true for any live socket, including one that
     * has not finished the `connect` hello, so the service alone cannot tell a caller the gateway
     * would accept the message. The UI disables its send affordances off Connected as well; this
     * is the backstop that keeps a pre-hello socket from swallowing a chat.send.
     */
    private fun deliver(text: String, imageJpegBase64: String? = null) {
        if (connectionState.value != OpenClawConnectionState.Connected) {
            suppressedSends++
            Log.w(TAG, "chat.send dropped: not connected (${connectionState.value})")
            return
        }
        if (!service.sendChatMessage(text, imageJpegBase64)) Log.w(TAG, "chat.send dropped: no socket")
    }

    // ---- voice (Fun-ASR) ----

    fun startListening() {
        if (_isListening.value) return
        val key = alibabaKey()
        if (key.isNullOrBlank()) {
            append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_ASSISTANT, text = str(R.string.openclaw_chat_noapikey)))
            return
        }
        _asrText.value = ""
        _asrPartial.value = ""
        _asrError.value = null
        _asrNotice.value = null
        _isListening.value = true
        listenJob = viewModelScope.launch { beginListening(key) }
    }

    /**
     * Arms the audio route, waits (bounded) for it, then builds and starts the recognizer.
     * Suspending because the SCO link is asynchronous: an AudioRecord opened on
     * VOICE_COMMUNICATION before SCO connects records silence for the whole utterance.
     */
    private suspend fun beginListening(key: String) {
        var source = _desiredAudioSource.value
        if (source == BluetoothAudioManager.AudioSource.BLUETOOTH_MIC) {
            holdSco() // no-op when SCO is already held for this chat session
            if (!awaitScoRoute()) {
                Log.w(TAG, "SCO not connected after $SCO_WAIT_MS ms; this utterance uses the phone mic")
                _asrNotice.value = str(R.string.openclaw_asr_sco_timeout)
                // Fallback for this utterance only - the choice stays BLUETOOTH_MIC so the next
                // attempt (with the link up by then) goes back to the glasses.
                source = BluetoothAudioManager.AudioSource.PHONE_MIC
            }
        }
        // Named asrService on purpose: `service` is the OpenClawNodeService property.
        val asrService = asrFactory(key, alibabaEndpoint(), source).apply {
            // FunASRService fires these on an OkHttp thread. Every one of them writes state
            // that Main also writes (_asrText / _asrPartial, and through stopListening() the
            // listenJob / asr fields), so they are hopped onto viewModelScope (Main) — final
            // review Minor 7.
            onPartialResult = { partial -> viewModelScope.launch { _asrPartial.value = partial } }
            onFinalResult = { sentence ->
                viewModelScope.launch {
                    _asrText.value = (_asrText.value + sentence)
                    _asrPartial.value = ""
                }
            }
            onError = { message ->
                viewModelScope.launch {
                    _asrError.value = str(R.string.openclaw_chat_asr_failed).format(localizeAsrError(message))
                    stopListening()
                }
            }
            // The recognizer ended on its own (task-finished): it has already closed its socket, so
            // only the flags and the SCO route are ours to clean up — calling stop() again here
            // would re-enter FunASRService.stop() from inside its own callback.
            onFinished = { viewModelScope.launch { finishListening() } }
        }
        asr = asrService
        asrService.start()
    }

    /** True once the SCO link is up, false if it did not come up within [SCO_WAIT_MS]. */
    private suspend fun awaitScoRoute(): Boolean {
        val route = audioRoute ?: return false
        if (route.scoConnected.value) return true
        return withTimeoutOrNull(SCO_WAIT_MS) { route.scoConnected.first { it } } ?: false
    }

    /** Keeps the recognized text on screen so the user can review, then Send or Cancel. */
    fun stopListening() {
        listenJob?.cancel() // it may still be waiting for the SCO route
        listenJob = null
        val session = asr
        asr = null // cleared first so a stop()-triggered onFinished cannot re-enter stop()
        session?.stop()
        finishListening()
    }

    /** Flags only; the recognizer is already (or is being) torn down. */
    private fun finishListening() {
        asr = null
        _isListening.value = false
        // SCO is deliberately NOT stopped here. It is scoped to the chat session (released by
        // leaveScreen / onCleared, or by switching back to the phone mic): stopping it per
        // utterance made BluetoothAudioManager's SCO_AUDIO_STATE_DISCONNECTED receiver reset its
        // own source to PHONE_MIC, which reset the chip and the next utterance with it.
    }

    /** SCO is session-scoped: armed on selection or on the first listen, held across utterances. */
    private fun holdSco() {
        if (scoHeld) return
        scoHeld = true
        audioRoute?.startSco()
    }

    /** An open SCO route keeps the phone in MODE_IN_COMMUNICATION and mutes system media. */
    private fun releaseSco() {
        if (!scoHeld) return
        scoHeld = false
        audioRoute?.stopSco()
    }

    fun sendAsrText() {
        val text = (_asrText.value + _asrPartial.value).trim()
        _asrText.value = ""
        _asrPartial.value = ""
        _asrError.value = null
        _asrNotice.value = null
        if (text.isEmpty()) return
        flushPendingResponse()
        append(OpenClawChatMessage(role = OpenClawChatMessage.ROLE_USER, text = text))
        deliver(text)
    }

    fun cancelAsr() {
        stopListening()
        _asrText.value = ""
        _asrPartial.value = ""
        _asrError.value = null
        _asrNotice.value = null
    }

    /**
     * FunASRService reports three fixed English texts ("Microphone unavailable", "Connection
     * failed", "ASR task failed") that are wire/log-level; anything else is a DashScope server
     * message and passes through untranslated.
     */
    @VisibleForTesting
    internal fun localizeAsrError(raw: String): String =
        ASR_ERROR_STRINGS[raw]?.let { str(it) } ?: raw

    fun switchAudioSource(source: BluetoothAudioManager.AudioSource) {
        _desiredAudioSource.value = source
        _asrNotice.value = null
        when (source) {
            // Arm the link now, so the route is up before the mic button is ever pressed.
            BluetoothAudioManager.AudioSource.BLUETOOTH_MIC -> holdSco()
            BluetoothAudioManager.AudioSource.PHONE_MIC -> releaseSco()
        }
        asr?.switchAudioSource(source)
    }

    private fun append(message: OpenClawChatMessage) {
        _messages.value = _messages.value + message
    }

    override fun onCleared() {
        super.onCleared()
        chatJob?.cancel()
        leaveScreen()
        audioRoute?.cleanup()
    }
}

/**
 * The audio-route seam this ViewModel needs from [BluetoothAudioManager]. Split out so the JVM
 * tests can drive the SCO link (the manager itself wraps AudioManager plus a BroadcastReceiver and
 * cannot be built off-device), and so the *live route* signal stays separate from the microphone
 * the user chose.
 */
interface OpenClawAudioRoute {
    /** A headset able to carry SCO is connected. */
    val bluetoothAvailable: StateFlow<Boolean>

    /** The SCO link is actually up - the live route, not the user's choice. */
    val scoConnected: StateFlow<Boolean>

    fun startSco()

    fun stopSco()

    fun cleanup()
}

/** Production adapter: every member is an existing [BluetoothAudioManager] API. */
class BluetoothAudioRoute(private val manager: BluetoothAudioManager) : OpenClawAudioRoute {
    override val bluetoothAvailable: StateFlow<Boolean> get() = manager.isBluetoothScoAvailable
    override val scoConnected: StateFlow<Boolean> get() = manager.isBluetoothScoConnected
    override fun startSco() = manager.startBluetoothSco()
    override fun stopSco() = manager.stopBluetoothSco()
    override fun cleanup() = manager.cleanup()
}
