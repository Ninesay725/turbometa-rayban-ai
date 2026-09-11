package com.smartview.glassai.viewmodels

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.smartview.glassai.bridge.BridgePreferences
import com.smartview.glassai.bridge.BridgeSettings
import com.smartview.glassai.bridge.NotificationBridgeRuntime
import com.smartview.glassai.services.TTSService
import com.smartview.glassai.services.assistant.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

enum class AssistantUiError { CONFIGURATION, REQUEST, TIMEOUT, INTERRUPTED, CAMERA_PERMISSION, IMAGES_DISABLED, NOTIFICATIONS_DISABLED, DICTATION, MICROPHONE_PERMISSION, SPEECH }

data class AssistantChatEntry(val role: String, val text: String, val notificationSummary: Boolean = false)

data class AssistantUiState(
    val input: String = "",
    val messages: List<AssistantChatEntry> = emptyList(),
    val busy: Boolean = false,
    val listening: Boolean = false,
    val error: AssistantUiError? = null,
    val safeDetail: String? = null,
    val configured: Boolean = false,
    val supportsImages: Boolean = false,
    val speakReplies: Boolean = true,
)

/** Main-confined screen owner. Credentials and images never enter observable or saved UI state. */
class AssistantViewModel internal constructor(
    private val readConfig: () -> AssistantConfig,
    val notifications: StateFlow<BridgeSettings>,
    private val reply: suspend (AssistantConfig, List<AssistantMessage>, String, ByteArray?, Boolean, AssistantTools, String?) -> AssistantReply,
    private val tools: AssistantTools,
    private val capture: suspend () -> ByteArray,
    private val enterDevice: () -> Unit,
    private val leaveDevice: () -> Unit,
    private val displayReply: (String) -> Unit,
    private val clearReply: () -> Unit,
    private val speak: suspend (String) -> Boolean,
    private val stopSpeech: () -> Unit,
    private val closeSpeech: () -> Unit,
    val dictationAvailable: Boolean,
    private val startDictation: ((String) -> Unit, () -> Unit) -> Unit,
    private val stopDictation: () -> Unit,
    private val injectedScope: CoroutineScope? = null,
    private val beginTurn: (Boolean) -> Unit = {},
    private val accessEpoch: StateFlow<Long>? = null,
) : ViewModel() {
    private val scope get() = injectedScope ?: viewModelScope
    private val mutableUi = MutableStateFlow(AssistantUiState())
    val ui: StateFlow<AssistantUiState> = mutableUi.asStateFlow()
    private var visible = false
    private var deviceOwned = false
    private var generation = 0L
    private var request: Job? = null
    private var speech: Job? = null
    private var consentObserver: Job? = null
    private var accessObserver: Job? = null
    private var permissionDraft: String? = null
    private var consent = currentConsent()

    fun enterScreen() {
        if (visible) return
        visible = true
        val config = runCatching { readConfig() }.getOrNull()
        mutableUi.update { it.copy(
            configured = config != null && runCatching { config.validate() }.isSuccess,
            supportsImages = config?.supportsImages == true,
            speakReplies = config?.speakReplies ?: true,
            error = if (config == null) AssistantUiError.CONFIGURATION else it.error,
        ) }
        consent = currentConsent()
        consentObserver = scope.launch {
            notifications.collect { settings ->
                val current = Triple(settings.aiNotificationsEnabled, settings.aiNotificationPackages, settings.aiConsentRevision)
                if (current != consent) {
                    consent = current
                    clear()
                }
            }
        }
        accessEpoch?.let { epoch ->
            var last = epoch.value
            accessObserver = scope.launch {
                epoch.collect { value -> if (value != last) { last = value; clear() } }
            }
        }
    }

    fun leaveScreen() {
        visible = false
        consentObserver?.cancel()
        consentObserver = null
        accessObserver?.cancel(); accessObserver = null
        clear()
    }

    /** External permission activities may STOP us. Keep only the unsent draft for an explicit retry. */
    fun onBackground() {
        if (!visible) return
        val draft = permissionDraft ?: ui.value.input
        val interrupted = ui.value.busy || ui.value.listening
        visible = false
        consentObserver?.cancel(); consentObserver = null
        accessObserver?.cancel(); accessObserver = null
        stop()
        mutableUi.update { it.copy(input = draft, messages = emptyList(), safeDetail = null,
            error = if (interrupted) AssistantUiError.INTERRUPTED else null) }
    }

    fun setInput(text: String) { mutableUi.update { it.copy(input = text.take(8_000)) } }
    fun dismissError() { mutableUi.update { it.copy(error = null, safeDetail = null) } }

    fun stop() = cancelTurn(releaseDevice = true)

    private fun cancelTurn(releaseDevice: Boolean) {
        generation++
        permissionDraft = null
        request?.cancel(); request = null
        speech?.cancel(); speech = null
        runCatching { stopDictation() }
        runCatching { stopSpeech() }
        if (deviceOwned && releaseDevice) {
            runCatching { clearReply() }
            runCatching { leaveDevice() }
            deviceOwned = false
        }
        mutableUi.update { it.copy(busy = false, listening = false) }
    }

    fun clear() {
        stop()
        mutableUi.update { it.copy(input = "", messages = emptyList(), error = null, safeDetail = null) }
    }

    fun send(withPhoto: Boolean = false, requestCameraPermission: suspend () -> Boolean = { false }) {
        val prompt = ui.value.input.trim()
        if (prompt.isEmpty()) return
        launchRequest(prompt, withPhoto, false, null, requestCameraPermission)
    }

    fun summarizeNotifications(prompt: String, packageName: String? = null) {
        val settings = notifications.value
        if (!settings.aiNotificationsEnabled || settings.aiNotificationPackages.isEmpty() ||
            (packageName != null && packageName !in settings.aiNotificationPackages)) {
            mutableUi.update { it.copy(error = AssistantUiError.NOTIFICATIONS_DISABLED, safeDetail = null) }
            return
        }
        launchRequest(prompt, false, true, packageName) { false }
    }

    private fun launchRequest(prompt: String, photo: Boolean, notificationsOnly: Boolean, packageName: String?, permission: suspend () -> Boolean) {
        if (!visible || ui.value.busy) return
        val config = runCatching { readConfig().also { it.validate() } }.getOrNull()
        if (config == null) {
            mutableUi.update { it.copy(error = AssistantUiError.CONFIGURATION, safeDetail = null, configured = false) }
            return
        }
        if (photo && !config.supportsImages) {
            mutableUi.update { it.copy(error = AssistantUiError.IMAGES_DISABLED, safeDetail = null) }
            return
        }
        cancelTurn(releaseDevice = false)
        beginTurn(notificationsOnly)
        val run = generation
        val allowed = currentPrivacyStamp()
        val history = if (notificationsOnly) emptyList() else ui.value.messages
            .filterNot { it.notificationSummary }.takeLast(12).map { AssistantMessage(it.role, it.text) }
        mutableUi.update { it.copy(busy = true, error = null, safeDetail = null) }
        request = scope.launch(start = CoroutineStart.LAZY) {
            var image: ByteArray? = null
            try {
                if (photo) {
                    if (!cameraPermission(run, prompt, permission)) {
                        if (isCurrent(run)) mutableUi.update { it.copy(error = AssistantUiError.CAMERA_PERMISSION) }
                        return@launch
                    }
                    ensureActive()
                    if (!isCurrent(run)) return@launch
                }
                enterDevice()
                deviceOwned = true
                if (photo) image = capture()
                ensureActive()
                if (!isCurrent(run)) return@launch
                append(AssistantChatEntry("user", prompt, notificationsOnly))
                if (!notificationsOnly) mutableUi.update { it.copy(input = "") }
                // Recheck consent at each tool boundary; a cancelled/old job cannot perform device work.
                val guardedTools = AssistantTools { name, arguments ->
                    currentCoroutineContext().ensureActive()
                    checkCurrent(run)
                    if (notificationsOnly) {
                        check(name == "notifications_read")
                        check(allowed == currentPrivacyStamp())
                    } else {
                        check(name != "notifications_read")
                        if (name == "camera_capture") {
                            check(config.supportsImages)
                            if (!cameraPermission(run, prompt, permission)) throw CameraPermissionDenied()
                            currentCoroutineContext().ensureActive()
                            checkCurrent(run)
                        }
                    }
                    tools.execute(name, arguments)
                }
                val result = reply(config, history, prompt, image, notificationsOnly, guardedTools, packageName)
                ensureActive()
                if (!isCurrent(run)) return@launch
                if (notificationsOnly && allowed != currentPrivacyStamp()) {
                    clear()
                    return@launch
                }
                append(AssistantChatEntry("assistant", result.text, notificationsOnly))
                if ("display_card" !in result.toolsUsed) displayReply(result.text)
                if (config.speakReplies && result.text.isNotBlank()) {
                    speech = scope.launch {
                        try {
                            if (!speak(result.text) && isCurrent(run)) mutableUi.update { it.copy(error = AssistantUiError.SPEECH) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { if (isCurrent(run)) mutableUi.update { it.copy(error = AssistantUiError.SPEECH) } }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (isCurrent(run)) mutableUi.update { it.copy(error = AssistantUiError.TIMEOUT) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: CameraPermissionDenied) {
                if (isCurrent(run)) mutableUi.update { it.copy(error = AssistantUiError.CAMERA_PERMISSION) }
            } catch (safe: AssistantException) {
                if (isCurrent(run)) mutableUi.update { it.copy(error = AssistantUiError.REQUEST, safeDetail = safe.message) }
            } catch (_: Exception) {
                // Never surface raw HTTP/recognition exceptions, headers, keys or request contents.
                if (isCurrent(run)) mutableUi.update { it.copy(error = AssistantUiError.REQUEST) }
            } finally {
                image?.fill(0)
                if (isCurrent(run)) {
                    request = null
                    mutableUi.update { it.copy(busy = false) }
                }
            }
        }
        request?.start()
    }

    fun dictate(requestMicrophonePermission: suspend () -> Boolean) {
        if (!visible || ui.value.busy || ui.value.listening) return
        if (!dictationAvailable) { mutableUi.update { it.copy(error = AssistantUiError.DICTATION) }; return }
        cancelTurn(releaseDevice = false)
        if (deviceOwned) beginTurn(false)
        val run = generation
        mutableUi.update { it.copy(listening = true, error = null, safeDetail = null) }
        request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!requestMicrophonePermission()) {
                    if (isCurrent(run)) mutableUi.update { it.copy(listening = false, error = AssistantUiError.MICROPHONE_PERMISSION) }
                    return@launch
                }
                ensureActive()
                if (!isCurrent(run)) return@launch
                startDictation({ text ->
                    if (isCurrent(run)) mutableUi.update { it.copy(input = text.take(8_000), listening = false) }
                }, {
                    if (isCurrent(run)) mutableUi.update { it.copy(listening = false, error = AssistantUiError.DICTATION) }
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrent(run)) {
                    runCatching { stopDictation() }
                    mutableUi.update { it.copy(listening = false, error = AssistantUiError.DICTATION) }
                }
            }
            finally { if (isCurrent(run)) request = null }
        }
        request?.start()
    }

    private fun append(entry: AssistantChatEntry) { mutableUi.update { it.copy(messages = (it.messages + entry).takeLast(12)) } }
    private fun currentConsent() = notifications.value.let { Triple(it.aiNotificationsEnabled, it.aiNotificationPackages, it.aiConsentRevision) }
    private fun currentPrivacyStamp() = currentConsent() to accessEpoch?.value
    private suspend fun cameraPermission(run: Long, prompt: String, permission: suspend () -> Boolean): Boolean {
        permissionDraft = prompt
        return try { permission() } finally { if (isCurrent(run)) permissionDraft = null }
    }
    private fun isCurrent(run: Long) = visible && generation == run
    private fun checkCurrent(run: Long) { if (!isCurrent(run)) throw CancellationException() }
    private class CameraPermissionDenied : Exception()

    override fun onCleared() { leaveScreen(); closeSpeech() }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val application = context.applicationContext as Application
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val store by lazy { AssistantSettingsStore(application) }
                    val engine = AssistantEngine()
                    val device = AssistantDeviceTools(application)
                    val tts = TTSService(application)
                    val dictation = AssistantDictation(application)
                    return AssistantViewModel(
                        readConfig = { store.load() }, notifications = BridgePreferences.getInstance(application).settings,
                        reply = { config, history, prompt, image, only, tools, pkg ->
                            engine.reply(config, history, prompt, image, only, tools, pkg)
                        }, tools = device, capture = device::capture,
                        enterDevice = device::enter, leaveDevice = device::leave,
                        displayReply = device::showReply, clearReply = device::clearReply,
                        speak = { text -> tts.speak(text, Locale.getDefault().toLanguageTag()) },
                        stopSpeech = tts::stop, closeSpeech = tts::close,
                        dictationAvailable = dictation.available, startDictation = dictation::start, stopDictation = dictation::stop,
                        beginTurn = device::beginTurn,
                        accessEpoch = NotificationBridgeRuntime.assistantAccessEpoch,
                    ) as T
                }
            }
        }
    }
}

/** System recognition, with Android's default microphone routing; never claims a glasses route. */
private class AssistantDictation(private val context: Context) {
    val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L

    fun start(onResult: (String) -> Unit, onError: () -> Unit) {
        stop()
        val run = generation
        val current = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = current
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                if (generation != run) return
                stop(); onError()
            }
            override fun onResults(results: Bundle?) {
                if (generation != run) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stop()
                if (text.isNullOrBlank()) onError() else onResult(text)
            }
        })
        current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    fun stop() {
        generation++
        val previous = recognizer
        recognizer = null
        try { previous?.cancel() } finally { previous?.destroy() }
    }
}
