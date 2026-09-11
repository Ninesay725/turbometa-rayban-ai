package com.smartview.glassai.viewmodels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.smartview.glassai.managers.APIProvider
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.managers.BluetoothAudioManager
import com.smartview.glassai.services.AudioRecordPcmSource
import com.smartview.glassai.services.LiveTranslateService
import com.smartview.glassai.services.PcmAudioSource
import com.smartview.glassai.translation.*
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class TranslationUiPhase { IDLE, PERMISSION, ROUTING, CONNECTING, RECORDING, ERROR }
enum class TranslationUiError { MISSING_KEY, MICROPHONE_PERMISSION, MICROPHONE_ROUTE, CONNECTION }
enum class TranslationInput { PHONE, GLASSES }

data class TranslationUiState(
    val phase: TranslationUiPhase = TranslationUiPhase.IDLE,
    val text: TranslationText = TranslationText("", false),
    val settings: TranslateSettings = TranslateSettings(),
    val input: TranslationInput? = null,
    val phoneFallback: Boolean = false,
    val cameraUnavailable: Boolean = false,
    val error: TranslationUiError? = null,
    val serviceError: String? = null,
) {
    val active: Boolean get() = phase in setOf(TranslationUiPhase.PERMISSION,
        TranslationUiPhase.ROUTING, TranslationUiPhase.CONNECTING, TranslationUiPhase.RECORDING)
}

data class TranslationHistoryEntry(
    val id: Long, val text: String, val originalText: String,
    val source: TranslateLanguage, val target: TranslateLanguage, val timestamp: Long,
)

/** Credentials are read only for a user-started attempt; never part of UI state/history. */
internal class TranslationCredentials(val apiKey: String, val endpoint: String)

/** UI-owned boundary around the protocol worker's concrete service. */
internal interface TranslationConnection {
    val state: StateFlow<TranslateConnectionState>
    val text: StateFlow<TranslationText>
    val error: StateFlow<String?>
    fun connect(apiKey: String, endpoint: String, settings: TranslateSettings, source: PcmAudioSource)
    fun sendImage(jpeg: ByteArray): Boolean
    fun close()
}
internal interface TranslationAudioRoute {
    val connected: StateFlow<Boolean>
    suspend fun prepare(): Boolean
    fun close()
}
internal interface TranslationVisualInput {
    suspend fun start(): Boolean
    suspend fun nextJpeg(): ByteArray?
    fun stop()
}

/** Main-confined, with one resource owner per explicit Start. Foregrounding never starts capture. */
class LiveTranslateViewModel internal constructor(
    val settings: StateFlow<TranslateSettings>,
    private val credentials: () -> TranslationCredentials?,
    private val connectionFactory: () -> TranslationConnection,
    private val routeFactory: () -> TranslationAudioRoute,
    private val sourceFactory: (TranslationInput) -> PcmAudioSource,
    private val injectedScope: CoroutineScope? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val scope get() = injectedScope ?: viewModelScope
    private val mutableUi = MutableStateFlow(TranslationUiState())
    val ui: StateFlow<TranslationUiState> = mutableUi.asStateFlow()
    private val mutableHistory = MutableStateFlow<List<TranslationHistoryEntry>>(emptyList())
    val history: StateFlow<List<TranslationHistoryEntry>> = mutableHistory.asStateFlow()
    private var historyId = 0L
    private var visible = false
    private var attempt: Attempt? = null

    private class Attempt(val settings: TranslateSettings, val visual: TranslationVisualInput?) {
        var job: Job? = null
        var images: Job? = null
        var routeObserver: Job? = null
        var route: TranslationAudioRoute? = null
        var connection: TranslationConnection? = null
        var visualOwned = false
    }

    internal fun onStart() { visible = true }
    internal fun onStop() { visible = false; stop() }
    internal fun onResume(hasMicrophonePermission: Boolean) {
        if (!hasMicrophonePermission && ui.value.phase != TranslationUiPhase.PERMISSION) {
            attempt?.let { finish(it, TranslationUiError.MICROPHONE_PERMISSION) }
        }
    }

    internal fun start(
        requestMicrophonePermission: suspend () -> Boolean,
        hasMicrophonePermission: () -> Boolean,
        visual: TranslationVisualInput?,
    ) {
        if (!visible || attempt != null) return
        val snapshot = settings.value.validated()
        val key = runCatching { credentials() }.getOrNull()
        if (key == null || key.apiKey.isBlank()) {
            mutableUi.value = TranslationUiState(phase = TranslationUiPhase.ERROR,
                settings = snapshot, error = TranslationUiError.MISSING_KEY)
            return
        }
        val current = Attempt(snapshot, visual.takeIf { snapshot.imageEnabled })
        attempt = current
        mutableUi.value = TranslationUiState(phase = TranslationUiPhase.PERMISSION, settings = snapshot)
        // Assign before starting: immediate permission/service failures may retire synchronously.
        current.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!hasMicrophonePermission() && !requestMicrophonePermission()) {
                    finish(current, TranslationUiError.MICROPHONE_PERMISSION)
                    return@launch
                }
                ensureActive()
                if (!isCurrent(current)) return@launch
                if (!hasMicrophonePermission()) {
                    finish(current, TranslationUiError.MICROPHONE_PERMISSION)
                    return@launch
                }
                mutableUi.update { it.copy(phase = TranslationUiPhase.ROUTING) }
                val input = prepareInput(current)
                ensureActive()
                if (!isCurrent(current)) return@launch
                if (!hasMicrophonePermission()) {
                    finish(current, TranslationUiError.MICROPHONE_PERMISSION)
                    return@launch
                }
                mutableUi.update { it.copy(phase = TranslationUiPhase.CONNECTING, input = input,
                    phoneFallback = !snapshot.usePhoneMic && input == TranslationInput.PHONE) }
                if (input == TranslationInput.GLASSES) {
                    val route = checkNotNull(current.route)
                    current.routeObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                        route.connected.collect { connected ->
                            if (!connected && isCurrent(current)) finish(current, TranslationUiError.MICROPHONE_ROUTE)
                        }
                    }
                    ensureActive()
                    if (!isCurrent(current)) return@launch
                }
                val connection = connectionFactory()
                current.connection = connection
                val source = sourceFactory(input)
                fun inputError(): TranslationUiError? = when {
                    !hasMicrophonePermission() -> TranslationUiError.MICROPHONE_PERMISSION
                    input == TranslationInput.GLASSES && current.route?.connected?.value != true ->
                        TranslationUiError.MICROPHONE_ROUTE
                    else -> null
                }
                // ACK can arrive before the queued route observer; recheck at actual capture start.
                connection.connect(key.apiKey, key.endpoint, snapshot, object : PcmAudioSource {
                    override fun start(onChunk: (ByteArray) -> Unit): Boolean =
                        isCurrent(current) && inputError() == null && source.start(onChunk)
                    override fun stop() = source.stop()
                })
                coroutineScope {
                    launch {
                        connection.text.collect { text ->
                            if (isCurrent(current)) mutableUi.update {
                                it.copy(text = boundedText(text))
                            }
                        }
                    }
                    launch {
                        connection.state.collect { state ->
                            if (!isCurrent(current)) return@collect
                            when (state) {
                                TranslateConnectionState.READY -> {
                                    val failure = inputError()
                                    if (failure != null) finish(current, failure) else {
                                        mutableUi.update { it.copy(phase = TranslationUiPhase.RECORDING) }
                                        startImages(current)
                                    }
                                }
                                TranslateConnectionState.ERROR, TranslateConnectionState.DISCONNECTED ->
                                    finish(current, inputError() ?: TranslationUiError.CONNECTION, connection.error.value)
                                TranslateConnectionState.CONNECTING -> Unit
                            }
                        }
                    }
                    awaitCancellation()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TranslationRouteBusyException) {
                finish(current, TranslationUiError.MICROPHONE_ROUTE)
            } catch (_: Exception) {
                finish(current, TranslationUiError.CONNECTION)
            }
        }
        current.job?.start()
    }

    private suspend fun prepareInput(current: Attempt): TranslationInput {
        if (current.settings.usePhoneMic) return TranslationInput.PHONE
        val route = routeFactory()
        current.route = route
        val connected = try {
            withTimeoutOrNull(3_000) { route.prepare() } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (busy: TranslationRouteBusyException) {
            throw busy
        } catch (_: Exception) { false }
        if (connected) return TranslationInput.GLASSES
        releaseRoute(current)
        return TranslationInput.PHONE
    }

    private fun startImages(current: Attempt) {
        val visual = current.visual ?: return
        if (current.images != null) return
        current.visualOwned = true // Also owns a pending permission/start operation.
        current.images = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val started = visual.start()
                ensureActive()
                if (!isCurrent(current)) return@launch
                if (!started) {
                    mutableUi.update { it.copy(cameraUnavailable = true) }
                    releaseVisual(current)
                    return@launch
                }
                while (isCurrent(current) && ui.value.phase == TranslationUiPhase.RECORDING) {
                    val jpeg = visual.nextJpeg()
                    ensureActive()
                    if (!isCurrent(current)) return@launch
                    if (jpeg != null && jpeg.isNotEmpty() && jpeg.size <= 500_000) {
                        // The service additionally gates on its first accepted audio append.
                        current.connection?.sendImage(jpeg)
                    }
                    delay(500)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(current)) {
                    mutableUi.update { it.copy(cameraUnavailable = true) }
                    releaseVisual(current)
                }
            }
        }
        current.images?.start()
    }

    internal fun stop() { attempt?.let { finish(it) } }
    fun clearHistory() { mutableHistory.value = emptyList() }

    private fun isCurrent(current: Attempt) = visible && attempt === current
    private fun boundedText(text: TranslationText) = text.copy(text = text.text.take(MAX_TEXT),
        originalText = text.originalText.take(MAX_TEXT))

    private fun finish(current: Attempt, error: TranslationUiError? = null, detail: String? = null) {
        if (attempt !== current) return
        // close() clears the service flow; the UI collector may still be behind this snapshot.
        val last = boundedText(current.connection?.text?.value ?: ui.value.text)
        attempt = null // Fence callbacks before cancelling or closing any resource.
        current.routeObserver?.cancel()
        current.images?.cancel()
        current.job?.cancel()
        releaseVisual(current)
        runCatching { current.connection?.close() }
        current.connection = null
        releaseRoute(current)
        if (last.text.isNotBlank()) {
            val entry = TranslationHistoryEntry(++historyId, last.text, last.originalText,
                current.settings.sourceLanguage, current.settings.targetLanguage, now())
            mutableHistory.value = listOf(entry) + history.value.take(49)
        }
        mutableUi.update { it.copy(phase = if (error == null) TranslationUiPhase.IDLE else TranslationUiPhase.ERROR,
            text = last, input = null, error = error, serviceError = detail?.take(240)) }
    }

    private fun releaseRoute(current: Attempt) {
        val route = current.route ?: return
        current.route = null
        runCatching { route.close() }
    }
    private fun releaseVisual(current: Attempt) {
        if (!current.visualOwned) return
        current.visualOwned = false
        runCatching { current.visual?.stop() }
    }

    override fun onCleared() { onStop(); super.onCleared() }

    companion object {
        private const val MAX_TEXT = 16_000
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(LiveTranslateViewModel::class.java))
                    return LiveTranslateViewModel(
                        settings = TranslatePreferences.getInstance(app).settings,
                        credentials = {
                            // Translation always uses Alibaba, independently of the vision/chat provider.
                            val region = APIProviderManager.getInstance(app).alibabaEndpoint.value
                            val key = APIKeyManager.getInstance(app).getAPIKey(APIProvider.ALIBABA, region)
                            key?.takeIf { it.isNotBlank() }?.let { TranslationCredentials(it, region.websocketURL) }
                        },
                        connectionFactory = { AndroidTranslationConnection() },
                        routeFactory = { androidTranslationAudioRoute(app) },
                        sourceFactory = { input -> AudioRecordPcmSource(if (input == TranslationInput.GLASSES)
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC) },
                    ) as T
                }
            }
        }
    }
}

private class AndroidTranslationConnection : TranslationConnection {
    private val service = LiveTranslateService()
    override val state get() = service.state
    override val text get() = service.text
    override val error get() = service.error
    override fun connect(apiKey: String, endpoint: String, settings: TranslateSettings, source: PcmAudioSource) =
        service.connect(apiKey, endpoint, settings, source)
    override fun sendImage(jpeg: ByteArray) = service.sendImage(jpeg)
    override fun close() = service.close()
}

internal interface TranslationScoDevice {
    val available: StateFlow<Boolean>
    val connected: StateFlow<Boolean>
    fun supported(): Boolean
    fun request()
    fun close()
}

internal class TranslationRouteBusyException : IllegalStateException()

/** Waits for hardware without acquiring a communication route owned by another feature. */
internal class OwnedTranslationAudioRoute(
    private val hasPermission: () -> Boolean,
    private val routeFree: () -> Boolean,
    private val createDevice: () -> TranslationScoDevice,
) : TranslationAudioRoute {
    private var device: TranslationScoDevice? = null
    private val disconnected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> get() = device?.connected ?: disconnected
    override suspend fun prepare(): Boolean {
        if (!hasPermission()) return false
        if (!routeFree()) throw TranslationRouteBusyException()
        val current = createDevice().also { device = it }
        current.available.first { it }
        currentCoroutineContext().ensureActive()
        if (!hasPermission()) return false
        if (!current.supported()) return false
        // Availability may have suspended while a call/another feature acquired SCO.
        if (!routeFree()) throw TranslationRouteBusyException()
        current.request()
        current.connected.first { it }
        return true
    }
    override fun close() {
        val current = device ?: return
        device = null
        current.close()
    }
}

/** Created only after an explicit Start, microphone grant and credential check. */
private fun androidTranslationAudioRoute(context: Context): TranslationAudioRoute = OwnedTranslationAudioRoute(
    hasPermission = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    },
    routeFree = {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode == AudioManager.MODE_NORMAL && !audio.isBluetoothScoOn
    },
    createDevice = {
        val manager = BluetoothAudioManager(context, ownedScoOnly = true)
        object : TranslationScoDevice {
            override val available get() = manager.isBluetoothAvailable
            override val connected get() = manager.isBluetoothScoConnected
            override fun supported() = manager.isBluetoothScoAvailable()
            override fun request() = manager.startBluetoothSco()
            override fun close() = manager.cleanup()
        }
    },
)
