package com.smartview.glassai.services

import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.BluetoothAudioManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * What OpenClawViewModel needs from a speech recognizer. [FunASRService] is the production
 * implementation; OpenClawViewModelTest drives the ViewModel with a fake.
 */
interface SpeechRecognizerSession {
    var onStarted: (() -> Unit)?
    var onPartialResult: ((String) -> Unit)?
    var onFinalResult: ((String) -> Unit)?
    var onError: ((String) -> Unit)?
    var onFinished: (() -> Unit)?
    fun start()
    fun stop()
    fun switchAudioSource(source: BluetoothAudioManager.AudioSource)
}

/**
 * Alibaba DashScope Fun-ASR realtime (research §4) over the "inference" WebSocket API:
 * run-task -> task-started -> binary PCM16 16 kHz frames -> result-generated (end_time > 0 =
 * final sentence) -> finish-task -> task-finished. Unlike iOS the endpoint follows the selected
 * Alibaba region (Beijing / Singapore). Research §8.3: whether `fun-asr-realtime` is served on the
 * intl (Singapore) endpoint could not be verified without a DashScope key on this host; Task 9
 * carries that check to the owner's phone, and a `task-failed` from the intl endpoint surfaces its
 * server message through [onError] rather than being retried on Beijing silently.
 *
 * Audio source semantics mirror Live AI: PHONE_MIC = MediaRecorder.AudioSource.MIC,
 * BLUETOOTH_MIC = VOICE_COMMUNICATION (the caller starts SCO through BluetoothAudioManager).
 */
class FunASRService(
    private val apiKey: String,
    private val endpoint: AlibabaEndpoint,
    private val httpClient: OkHttpClient,
    private val audioSourceFactory: (BluetoothAudioManager.AudioSource) -> PcmAudioSource,
    initialAudioSource: BluetoothAudioManager.AudioSource = BluetoothAudioManager.AudioSource.PHONE_MIC,
    private val endpointUrlOverride: String? = null,
) : SpeechRecognizerSession {
    companion object {
        private const val TAG = "FunASRService"
        const val MODEL = "fun-asr-realtime"
        const val SAMPLE_RATE = 16_000
        private const val WS_BEIJING = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val WS_SINGAPORE = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference"
        private const val CLOSE_DELAY_MS = 500L

        fun endpointUrl(endpoint: AlibabaEndpoint): String = when (endpoint) {
            AlibabaEndpoint.BEIJING -> WS_BEIJING
            AlibabaEndpoint.SINGAPORE -> WS_SINGAPORE
        }

        /** MediaRecorder.AudioSource constant for a Live AI audio-source choice. */
        fun recorderSourceFor(source: BluetoothAudioManager.AudioSource): Int = when (source) {
            BluetoothAudioManager.AudioSource.PHONE_MIC -> MediaRecorder.AudioSource.MIC
            BluetoothAudioManager.AudioSource.BLUETOOTH_MIC -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }

        /** The default production factory: a fresh AudioRecord per source switch. */
        fun defaultAudioSourceFactory(): (BluetoothAudioManager.AudioSource) -> PcmAudioSource =
            { source -> AudioRecordPcmSource(recorderSourceFor(source)) }
    }

    override var onStarted: (() -> Unit)? = null
    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null

    private val _isListening = MutableStateFlow(false)
    /** True from task-started until stop()/task-finished/task-failed. */
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** The URL start() dials: the test override, else the region endpoint. */
    @VisibleForTesting
    internal fun resolvedUrl(): String = endpointUrlOverride ?: endpointUrl(endpoint)

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var audio: PcmAudioSource? = null
    private var currentAudioSource = initialAudioSource
    private var taskId: String = newTaskId()
    private var taskStarted = false
    private var stopping = false

    private fun newTaskId(): String = UUID.randomUUID().toString().replace("-", "").lowercase()

    override fun start() {
        synchronized(lock) {
            if (webSocket != null) return
            taskId = newTaskId()
            taskStarted = false
            stopping = false
            val request = Request.Builder()
                .url(resolvedUrl())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            webSocket = httpClient.newWebSocket(request, Listener())
        }
    }

    /** Stops the microphone, sends finish-task and closes the socket shortly after. */
    override fun stop() {
        val socket: WebSocket?
        synchronized(lock) {
            stopping = true
            stopAudioLocked()
            socket = webSocket
        }
        _isListening.value = false
        if (socket != null) {
            socket.send(gson.toJson(finishTaskFrame()))
            scope.launch {
                delay(CLOSE_DELAY_MS)
                closeSocket(socket)
            }
        }
    }

    override fun switchAudioSource(source: BluetoothAudioManager.AudioSource) {
        synchronized(lock) {
            if (currentAudioSource == source) return
            val wasCapturing = audio != null
            stopAudioLocked()
            currentAudioSource = source
            if (wasCapturing && taskStarted && !stopping) startAudioLocked()
        }
    }

    private fun closeSocket(socket: WebSocket) {
        synchronized(lock) {
            if (webSocket === socket) webSocket = null
        }
        runCatching { socket.close(1000, "done") }
    }

    private fun startAudioLocked() {
        val source = audioSourceFactory(currentAudioSource)
        val ok = source.start { chunk ->
            val socket = synchronized(lock) { webSocket }
            // okio 3.x: the Kotlin-visible ByteString.of(array, offset, count) is a DeprecationLevel.ERROR
            // shim; ByteArray.toByteString() is the API (whole array — the source hands us exact copies).
            socket?.send(chunk.toByteString())
        }
        if (ok) {
            audio = source
        } else {
            Log.e(TAG, "audio source failed to start")
            onError?.invoke("Microphone unavailable")
        }
    }

    private fun stopAudioLocked() {
        audio?.stop()
        audio = null
    }

    private fun runTaskFrame(): JsonObject = JsonObject().apply {
        add("header", JsonObject().apply {
            addProperty("action", "run-task")
            addProperty("task_id", taskId)
            addProperty("streaming", "duplex")
        })
        add("payload", JsonObject().apply {
            addProperty("task_group", "audio")
            addProperty("task", "asr")
            addProperty("function", "recognition")
            addProperty("model", MODEL)
            add("parameters", JsonObject().apply {
                addProperty("format", "pcm")
                addProperty("sample_rate", SAMPLE_RATE)
                addProperty("vocabulary_id", "")
                addProperty("disfluency_removal_enabled", false)
            })
            add("input", JsonObject())
        })
    }

    private fun finishTaskFrame(): JsonObject = JsonObject().apply {
        add("header", JsonObject().apply {
            addProperty("action", "finish-task")
            addProperty("task_id", taskId)
            addProperty("streaming", "duplex")
        })
        add("payload", JsonObject().apply { add("input", JsonObject()) })
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "connected; sending run-task $taskId")
            webSocket.send(gson.toJson(runTaskFrame()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "closed: $code $reason")
            finish(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "socket failure: ${t.message}")
            val wasStopping = synchronized(lock) { stopping }
            finish(webSocket)
            if (!wasStopping) onError?.invoke(t.message ?: "Connection failed")
        }
    }

    private fun finish(socket: WebSocket) {
        synchronized(lock) {
            stopAudioLocked()
            if (webSocket === socket) webSocket = null
        }
        _isListening.value = false
    }

    private fun handleMessage(text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "ignoring non-JSON frame")
            return
        }
        val header = json.get("header")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        when (header.get("event")?.takeIf { it.isJsonPrimitive }?.asString) {
            "task-started" -> {
                synchronized(lock) {
                    taskStarted = true
                    if (!stopping) startAudioLocked()
                }
                _isListening.value = true
                onStarted?.invoke()
            }
            "result-generated" -> {
                val sentence = json.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("output")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("sentence")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                val sentenceText = sentence.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val endTime = sentence.get("end_time")?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
                if (endTime > 0) onFinalResult?.invoke(sentenceText) else onPartialResult?.invoke(sentenceText)
            }
            "task-finished" -> {
                // Also reached without a prior stop() (server-side end of task): stop the mic here
                // instead of waiting for onClosed, so no PCM is pushed into a finished task.
                val socket = synchronized(lock) {
                    stopAudioLocked()
                    webSocket
                }
                _isListening.value = false
                onFinished?.invoke()
                if (socket != null) closeSocket(socket)
            }
            "task-failed" -> {
                val message = header.get("error_message")?.takeIf { it.isJsonPrimitive }?.asString ?: "ASR task failed"
                Log.e(TAG, "task-failed: $message")
                val socket = synchronized(lock) {
                    stopAudioLocked()
                    webSocket
                }
                _isListening.value = false
                onError?.invoke(message)
                if (socket != null) closeSocket(socket)
            }
            else -> Log.d(TAG, "event: ${header.get("event")}")
        }
    }
}
