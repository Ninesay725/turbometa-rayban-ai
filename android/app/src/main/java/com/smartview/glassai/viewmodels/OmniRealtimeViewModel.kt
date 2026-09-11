package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartview.glassai.glasses.GlassesDisplaySink
import com.smartview.glassai.glasses.GlassesControllerRegistry
import com.smartview.glassai.glasses.GlassesDisplayIntegration
import com.smartview.glassai.glasses.LiveAiRunGate
import com.smartview.glassai.glasses.LiveAiCardMapper
import com.smartview.glassai.glasses.LiveAiController
import com.smartview.glassai.data.ConversationStorage
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.managers.BluetoothAudioManager
import com.smartview.glassai.managers.LiveAIProvider
import com.smartview.glassai.models.ConversationMessage
import com.smartview.glassai.models.ConversationRecord
import com.smartview.glassai.models.MessageRole
import com.smartview.glassai.services.GeminiLiveService
import com.smartview.glassai.services.OmniRealtimeService
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * OmniRealtimeViewModel
 * Supports multiple Live AI providers: Alibaba Qwen Omni, Google Gemini
 * 1:1 port from iOS OmniRealtimeViewModel
 */
class OmniRealtimeViewModel internal constructor(
    application: Application,
    private val sink: GlassesDisplaySink,
    private val controllers: GlassesControllerRegistry,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        GlassesDisplayIntegration.displayManager(application).ownedSink(Any()), GlassesDisplayIntegration.router(application))

    companion object {
        private const val TAG = "OmniRealtimeViewModel"
    }

    private val apiKeyManager = APIKeyManager.getInstance(application)
    private val providerManager = APIProviderManager.getInstance(application)
    private val conversationStorage = ConversationStorage.getInstance(application)

    // Bluetooth Audio Manager
    private val bluetoothAudioManager = BluetoothAudioManager(application)

    // Services
    private var omniService: OmniRealtimeService? = null
    private var geminiService: GeminiLiveService? = null

    // Current provider
    private val _currentProvider = MutableStateFlow(providerManager.liveAIProvider.value)
    val currentProvider: StateFlow<LiveAIProvider> = _currentProvider.asStateFlow()

    // State
    sealed class ViewState {
        object Idle : ViewState()
        object Connecting : ViewState()
        object Connected : ViewState()
        object Recording : ViewState()
        object Processing : ViewState()
        object Speaking : ViewState()
        data class Error(val message: String) : ViewState()
    }

    private val _viewState = MutableStateFlow<ViewState>(ViewState.Idle)
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    private val _currentTranscript = MutableStateFlow("")
    val currentTranscript: StateFlow<String> = _currentTranscript.asStateFlow()

    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Audio source state
    val currentAudioSource: StateFlow<BluetoothAudioManager.AudioSource> = bluetoothAudioManager.currentAudioSource
    val isBluetoothScoConnected: StateFlow<Boolean> = bluetoothAudioManager.isBluetoothScoConnected
    val isBluetoothScoAvailable: StateFlow<Boolean> = bluetoothAudioManager.isBluetoothScoAvailable

    private var currentSessionId: String = UUID.randomUUID().toString()
    private var pendingVideoFrame: Bitmap? = null
    private val displayController = object : LiveAiController { override fun end() = disconnect() }
    private var hasDisplayCard = false
    private val runs = LiveAiRunGate()
    private val observerJobs = mutableListOf<Job>()

    init {
        controllers.registerLiveAi(displayController)
        viewModelScope.launch {
            combine(viewState, userTranscript, currentTranscript, messages, isConnected) { state, user, text, history, connected ->
                if (connected || state == ViewState.Connecting) {
                    LiveAiCardMapper.map(state, user, text,
                        history.lastOrNull { it.role == MessageRole.ASSISTANT }?.content)
                } else null
            }.collect { card ->
                if (card != null && runs.active) {
                    hasDisplayCard = true
                    sink.show(card)
                } else if (hasDisplayCard) {
                    hasDisplayCard = false
                    sink.showStatus()
                }
            }
        }
        // Observe provider changes
        viewModelScope.launch {
            providerManager.liveAIProvider.collect { provider ->
                if (_currentProvider.value != provider) {
                    _currentProvider.value = provider
                    Log.d(TAG, "Live AI provider changed to: ${provider.displayName}")
                    // Replacing a provider ends even an in-flight connection. Queued callbacks
                    // from that service must not attach to its replacement's generation.
                    if (runs.active) {
                        disconnect()
                    }
                    initializeService()
                }
            }
        }
        initializeService()
    }

    private fun initializeService() {
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()
        omniService?.disconnect()
        geminiService?.disconnect()
        omniService = null
        geminiService = null
        val provider = providerManager.liveAIProvider.value
        val apiKey = providerManager.getLiveAIAPIKey(apiKeyManager)
        val language = apiKeyManager.getOutputLanguage()

        if (apiKey.isBlank()) {
            _errorMessage.value = "API Key not configured for ${provider.displayName}"
            Log.e(TAG, "API Key not configured for ${provider.displayName}")
            return
        }

        Log.d(TAG, "Initializing service for provider: ${provider.displayName}")

        when (provider) {
            LiveAIProvider.ALIBABA -> initializeOmniService(apiKey, language)
            LiveAIProvider.GOOGLE -> initializeGeminiService(apiKey, language)
        }
    }

    private fun initializeOmniService(apiKey: String, language: String) {
        val run = runs.generation
        // Clean up Gemini service if exists
        geminiService?.disconnect()
        geminiService = null

        val model = providerManager.liveAIModel.value
        val endpoint = providerManager.alibabaEndpoint.value

        omniService = OmniRealtimeService(apiKey, model, language, endpoint, getApplication()).apply {
            onTranscriptDelta = { delta ->
                deliverForRun(run) {
                    _currentTranscript.value += delta
                }
            }

            onTranscriptDone = { transcript ->
                deliverForRun(run) {
                    if (transcript.isNotBlank()) {
                        addAssistantMessage(transcript)
                    }
                    _currentTranscript.value = ""
                    _viewState.value = ViewState.Connected
                }
            }

            onUserTranscript = { transcript ->
                deliverForRun(run) {
                    if (transcript.isNotBlank()) {
                        _userTranscript.value = transcript
                        addUserMessage(transcript)
                    }
                }
            }

            onSpeechStarted = {
                deliverForRun(run) {
                    _viewState.value = ViewState.Recording
                }
            }

            onSpeechStopped = {
                deliverForRun(run) {
                    _viewState.value = ViewState.Processing
                }
            }

            onError = { error ->
                deliverForRun(run) {
                    _errorMessage.value = error
                    _viewState.value = ViewState.Error(error)
                }
            }
        }

        observeOmniServiceStates(run)
    }

    private fun initializeGeminiService(apiKey: String, language: String) {
        val run = runs.generation
        // Clean up Omni service if exists
        omniService?.disconnect()
        omniService = null

        val model = providerManager.liveAIModel.value

        geminiService = GeminiLiveService(apiKey, model, language).apply {
            onTranscriptDelta = { delta ->
                deliverForRun(run) {
                    _currentTranscript.value += delta
                }
            }

            onTranscriptDone = { transcript ->
                deliverForRun(run) {
                    if (transcript.isNotBlank()) {
                        addAssistantMessage(_currentTranscript.value.ifBlank { transcript })
                    }
                    _currentTranscript.value = ""
                    _viewState.value = ViewState.Connected
                }
            }

            onUserTranscript = { transcript ->
                deliverForRun(run) {
                    if (transcript.isNotBlank()) {
                        _userTranscript.value = transcript
                        addUserMessage(transcript)
                    }
                }
            }

            onSpeechStarted = {
                deliverForRun(run) {
                    _viewState.value = ViewState.Recording
                }
            }

            onSpeechStopped = {
                deliverForRun(run) {
                    _viewState.value = ViewState.Processing
                }
            }

            onError = { error ->
                deliverForRun(run) {
                    _errorMessage.value = error
                    _viewState.value = ViewState.Error(error)
                }
            }

            onConnected = {
                deliverForRun(run) {
                    _isConnected.value = true
                    _viewState.value = ViewState.Connected
                }
            }
        }

        observeGeminiServiceStates(run)
    }

    private fun observeOmniServiceStates(run: Long) {
        observerJobs += viewModelScope.launch {
            omniService?.isConnected?.collect { connected ->
                if (!runs.accepts(run)) return@collect
                _isConnected.value = connected
                if (connected && _viewState.value == ViewState.Connecting) {
                    _viewState.value = ViewState.Connected
                } else if (!connected && _viewState.value != ViewState.Connecting) {
                    _viewState.value = ViewState.Idle
                }
            }
        }

        observerJobs += viewModelScope.launch {
            omniService?.isRecording?.collect { recording ->
                if (!runs.accepts(run)) return@collect
                _isRecording.value = recording
            }
        }

        observerJobs += viewModelScope.launch {
            omniService?.isSpeaking?.collect { speaking ->
                if (!runs.accepts(run)) return@collect
                _isSpeaking.value = speaking
                if (speaking) {
                    _viewState.value = ViewState.Speaking
                }
            }
        }
    }

    private fun observeGeminiServiceStates(run: Long) {
        observerJobs += viewModelScope.launch {
            geminiService?.isConnected?.collect { connected ->
                if (!runs.accepts(run)) return@collect
                _isConnected.value = connected
                if (connected && _viewState.value == ViewState.Connecting) {
                    _viewState.value = ViewState.Connected
                } else if (!connected && _viewState.value != ViewState.Connecting) {
                    _viewState.value = ViewState.Idle
                }
            }
        }

        observerJobs += viewModelScope.launch {
            geminiService?.isRecording?.collect { recording ->
                if (!runs.accepts(run)) return@collect
                _isRecording.value = recording
            }
        }

        observerJobs += viewModelScope.launch {
            geminiService?.isSpeaking?.collect { speaking ->
                if (!runs.accepts(run)) return@collect
                _isSpeaking.value = speaking
                if (speaking) {
                    _viewState.value = ViewState.Speaking
                }
            }
        }
    }

    fun connect() {
        viewModelScope.launch {
            if (_isConnected.value || _viewState.value == ViewState.Connecting) return@launch
            runs.begin()
            initializeService()
            if (omniService == null && geminiService == null) {
                runs.end()
                _viewState.value = ViewState.Error(_errorMessage.value.orEmpty())
                return@launch
            }

            _viewState.value = ViewState.Connecting
            _messages.value = emptyList()
            currentSessionId = UUID.randomUUID().toString()

            when (_currentProvider.value) {
                LiveAIProvider.ALIBABA -> omniService?.connect()
                LiveAIProvider.GOOGLE -> geminiService?.connect()
            }
        }
    }

    private fun deliverForRun(run: Long, block: () -> Unit) {
        viewModelScope.launch { if (runs.accepts(run)) block() }
    }

    fun disconnect() {
        runs.end()
        if (hasDisplayCard) sink.showStatus()
        hasDisplayCard = false
        viewModelScope.launch {
            saveCurrentConversation()
            omniService?.disconnect()
            geminiService?.disconnect()
            _viewState.value = ViewState.Idle
            _isConnected.value = false
            _messages.value = emptyList()
            _currentTranscript.value = ""
            _userTranscript.value = ""
        }
    }

    fun startRecording() {
        if (!_isConnected.value) {
            _errorMessage.value = "Not connected"
            return
        }

        // Update video frame if available
        pendingVideoFrame?.let { frame ->
            when (_currentProvider.value) {
                LiveAIProvider.ALIBABA -> omniService?.updateVideoFrame(frame)
                LiveAIProvider.GOOGLE -> geminiService?.updateVideoFrame(frame)
            }
        }

        when (_currentProvider.value) {
            LiveAIProvider.ALIBABA -> omniService?.startRecording()
            LiveAIProvider.GOOGLE -> geminiService?.startRecording()
        }
        _viewState.value = ViewState.Recording
    }

    fun stopRecording() {
        when (_currentProvider.value) {
            LiveAIProvider.ALIBABA -> omniService?.stopRecording()
            LiveAIProvider.GOOGLE -> geminiService?.stopRecording()
        }
        if (_viewState.value == ViewState.Recording) {
            _viewState.value = ViewState.Processing
        }
    }

    /**
     * 切换音频源（手机麦克风 / 眼镜麦克风）
     */
    fun switchAudioSource(source: BluetoothAudioManager.AudioSource) {
        Log.d(TAG, "切换音频源到: $source")

        // 切换蓝牙音频管理器的音频源
        bluetoothAudioManager.switchAudioSource(source)

        // 通知当前活动的Service切换音频源
        when (_currentProvider.value) {
            LiveAIProvider.ALIBABA -> omniService?.switchAudioSource(source)
            LiveAIProvider.GOOGLE -> geminiService?.switchAudioSource(source)
        }
    }

    fun updateVideoFrame(frame: Bitmap) {
        pendingVideoFrame = frame
        when (_currentProvider.value) {
            LiveAIProvider.ALIBABA -> omniService?.updateVideoFrame(frame)
            LiveAIProvider.GOOGLE -> geminiService?.updateVideoFrame(frame)
        }
    }

    fun sendImage(image: Bitmap) {
        when (_currentProvider.value) {
            LiveAIProvider.ALIBABA -> omniService?.updateVideoFrame(image)
            LiveAIProvider.GOOGLE -> geminiService?.sendImageInput(image)
        }
    }

    private fun addUserMessage(text: String) {
        val message = ConversationMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + message
    }

    private fun addAssistantMessage(text: String) {
        val message = ConversationMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + message
    }

    private fun saveCurrentConversation() {
        if (_messages.value.isEmpty()) return

        val record = ConversationRecord(
            id = currentSessionId,
            timestamp = System.currentTimeMillis(),
            messages = _messages.value,
            aiModel = providerManager.liveAIModel.value,
            language = apiKeyManager.getOutputLanguage()
        )

        conversationStorage.saveConversation(record)
    }

    fun clearError() {
        _errorMessage.value = null
        omniService?.clearError()
        geminiService?.clearError()
        if (_viewState.value is ViewState.Error) {
            _viewState.value = if (_isConnected.value) ViewState.Connected else ViewState.Idle
        }
    }

    fun refreshService() {
        disconnect()
        omniService = null
        geminiService = null
        initializeService()
    }

    override fun onCleared() {
        runs.end()
        controllers.unregisterLiveAi(displayController)
        if (hasDisplayCard) sink.showStatus()
        super.onCleared()
        saveCurrentConversation()
        omniService?.disconnect()
        geminiService?.disconnect()
    }
}
