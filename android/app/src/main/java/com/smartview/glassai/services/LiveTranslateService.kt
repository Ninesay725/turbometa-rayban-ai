package com.smartview.glassai.services

import androidx.annotation.MainThread
import com.google.gson.JsonObject
import com.smartview.glassai.translation.TranslateConnectionState
import com.smartview.glassai.translation.TranslateConnectionState.*
import com.smartview.glassai.translation.TranslateSettings
import com.smartview.glassai.translation.TranslationText
import com.smartview.glassai.translation.validated
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Foreground translation owner. Public operations are Main-confined; socket/capture callbacks
 * enter bounded queues. disconnect is reusable; close is terminal. No transcript is persisted.
 * READY means a matching session.updated AND successful capture start, within the deadline.
 */
open class LiveTranslateService(
    private val client: WebSocket.Factory = HttpClients.websocket,
    private val playback: Pcm16Playback = AudioTrackPcm16Playback(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val configurationTimeoutMs: Long = 10_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init { require(configurationTimeoutMs in 1..10_000) }
    private val lifetime = SupervisorJob()
    private val mutableState = MutableStateFlow(DISCONNECTED)
    private val mutableText = MutableStateFlow(TranslationText("", false))
    private val mutableError = MutableStateFlow<String?>(null)
    open val state: StateFlow<TranslateConnectionState> = mutableState.asStateFlow()
    open val text: StateFlow<TranslationText> = mutableText.asStateFlow()
    open val error: StateFlow<String?> = mutableError.asStateFlow()
    private var active: Attempt? = null
    private var closed = false

    private sealed interface Incoming {
        data object Open : Incoming
        data class Message(val text: String) : Incoming
    }

    private inner class Attempt(val settings: TranslateSettings, val source: PcmAudioSource) {
        val job = SupervisorJob(lifetime)
        val scope = CoroutineScope(job + dispatcher)
        val retired = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val acceptingInput = AtomicBoolean(false)
        val events = Channel<Incoming>(32)
        val input = Channel<ByteArray>(8)
        val output = Channel<ByteArray>(8)
        val reducer = TranslateTextReducer()
        val beganAt = nowMs()
        var socket: WebSocket? = null
        var timeout: Job? = null
        var playbackJob: Job? = null
        var configuredSent = false
        var ready = false
        var audioAccepted = false
        var lastImageAt: Long? = null

        // Called on arbitrary producer threads. One failure wakes the existing actor, never a
        // coroutine per callback. Retired listeners cannot publish or grow their queues.
        fun reject(message: String) {
            if (!retired.get() && failure.compareAndSet(null, message)) events.close()
        }

        fun offer(event: Incoming) {
            if (!retired.get() && failure.get() == null && !events.trySend(event).isSuccess)
                reject("Translation events arrived too quickly.")
        }

        fun capture(bytes: ByteArray) {
            if (!acceptingInput.get() || retired.get() || failure.get() != null || bytes.isEmpty()) return
            if (bytes.size > LiveTranslateProtocol.MAX_AUDIO_BYTES) {
                reject("Microphone audio exceeded the translation buffer.")
            } else if (!input.trySend(bytes.copyOf()).isSuccess) {
                reject("Microphone audio could not be sent fast enough.")
            }
        }
    }

    @MainThread
    open fun connect(apiKey: String, endpoint: String, settings: TranslateSettings, source: PcmAudioSource) {
        if (closed) return
        active?.let { retire(it) }
        mutableText.value = TranslationText("", false)
        mutableError.value = null
        mutableState.value = CONNECTING
        val attempt = Attempt(settings.validated(), source)
        active = attempt
        if (apiKey.isBlank()) { fail(attempt, "An Alibaba API key is required for translation."); return }
        val request = runCatching {
            val url = Request.Builder().url(endpoint).build().url.newBuilder()
                .setQueryParameter("model", LiveTranslateProtocol.MODEL).build()
            Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        }.getOrNull()
        if (request == null) { fail(attempt, "The translation endpoint is invalid."); return }

        attempt.timeout = attempt.scope.launch {
            delay(configurationTimeoutMs)
            if (current(attempt) && !attempt.ready) fail(attempt, "Translation configuration timed out.")
        }
        try {
            attempt.socket = client.newWebSocket(request, listener(attempt))
        } catch (_: Exception) {
            fail(attempt, "Could not connect to translation.")
            return
        }
        attempt.scope.launch {
            for (event in attempt.events) {
                if (!current(attempt)) return@launch
                attempt.failure.get()?.let { fail(attempt, it); return@launch }
                when (event) {
                    Incoming.Open -> {
                        if (!attempt.configuredSent) {
                            if (!send(attempt, LiveTranslateProtocol.configure(attempt.settings))) return@launch
                            attempt.configuredSent = true
                        }
                    }
                    is Incoming.Message -> receive(attempt, event.text)
                }
            }
            attempt.failure.get()?.let { fail(attempt, it) }
        }
    }

    private fun listener(attempt: Attempt) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { attempt.offer(Incoming.Open) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > LiveTranslateProtocol.MAX_EVENT_CHARS)
                attempt.reject("Translation returned an oversized event.")
            else attempt.offer(Incoming.Message(text))
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            attempt.reject("Translation returned an unsupported audio format.")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            attempt.reject("The translation connection closed.")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            attempt.reject("The translation connection closed.")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Provider bodies and exception messages can contain credentials or speech.
            attempt.reject("The translation connection failed.")
        }
    }

    private fun receive(attempt: Attempt, raw: String) {
        val event = LiveTranslateProtocol.parse(raw)
        if (event == null) { fail(attempt, "Translation returned an invalid event."); return }
        when (event.string("type")) {
            "error" -> fail(attempt, "The translation provider rejected the request.")
            "session.updated" -> {
                if (!attempt.configuredSent || !LiveTranslateProtocol.matchesConfiguration(event, attempt.settings)) {
                    fail(attempt, "The translation provider did not accept the requested configuration.")
                } else if (!attempt.ready) startCapture(attempt)
            }
            "session.finished" -> fail(attempt, "The translation session ended.")
            else -> if (attempt.ready) receiveContent(attempt, event)
        }
    }

    private fun startCapture(attempt: Attempt) {
        if (nowMs() - attempt.beganAt >= configurationTimeoutMs) {
            fail(attempt, "Translation configuration timed out."); return
        }
        attempt.acceptingInput.set(true)
        val started = runCatching { attempt.source.start(attempt::capture) }.getOrDefault(false)
        if (!current(attempt)) return
        if (!started) { fail(attempt, "The microphone could not start."); return }
        attempt.failure.get()?.let { fail(attempt, it); return }
        if (nowMs() - attempt.beganAt >= configurationTimeoutMs) {
            fail(attempt, "Translation configuration timed out."); return
        }
        attempt.ready = true
        attempt.timeout?.cancel()
        mutableState.value = READY
        attempt.scope.launch {
            var pending: Byte? = null
            for (bytes in attempt.input) {
                if (!healthy(attempt) || !attempt.ready) return@launch
                val aligned = pending?.let { byteArrayOf(it) + bytes } ?: bytes
                val evenSize = aligned.size / 2 * 2
                pending = if (evenSize < aligned.size) aligned.last() else null
                if (evenSize == 0) continue
                val pcm = if (evenSize == aligned.size) aligned else aligned.copyOf(evenSize)
                if (!send(attempt, LiveTranslateProtocol.append("input_audio_buffer.append", "audio", pcm))) return@launch
                attempt.audioAccepted = true
            }
        }
    }

    private fun receiveContent(attempt: Attempt, event: JsonObject) {
        when (event.string("type")) {
            "response.audio.delta" -> {
                if (!attempt.settings.audioEnabled || !attempt.reducer.acceptsIdentity(event)) return
                val bytes = LiveTranslateProtocol.decodeAudio(event)
                if (bytes == null) { fail(attempt, "Translation returned invalid PCM audio."); return }
                if (bytes.isEmpty()) return
                if (!attempt.output.trySend(bytes).isSuccess) {
                    fail(attempt, "Translated audio could not play fast enough."); return
                }
                if (attempt.playbackJob == null) {
                    attempt.playbackJob = attempt.scope.launch {
                        try {
                            playback.play(attempt.output.receiveAsFlow())
                            if (current(attempt)) fail(attempt, "Translation audio playback ended unexpectedly.")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            fail(attempt, "Translated audio could not play.")
                        }
                    }
                }
            }
            "response.done" -> {
                if (!attempt.reducer.acceptsIdentity(event)) return
                val status = event.obj("response")?.string("status") ?: event.string("status")
                if (status in setOf("failed", "incomplete", "cancelled")) {
                    mutableText.value = mutableText.value.copy(isFinal = false)
                    fail(attempt, "The translation response did not complete.")
                }
            }
            else -> attempt.reducer.accept(event)?.let { mutableText.value = it }
        }
    }

    @MainThread
    open fun sendImage(jpeg: ByteArray): Boolean {
        val attempt = active ?: return false
        if (!healthy(attempt) || !attempt.ready || !attempt.settings.imageEnabled || !attempt.audioAccepted) return false
        // UI owns decoding/downscaling/encoding. Check the JPEG markers and byte cap here.
        if (jpeg.size !in 4..LiveTranslateProtocol.MAX_IMAGE_BYTES ||
            jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte() ||
            jpeg[jpeg.lastIndex - 1] != 0xff.toByte() || jpeg.last() != 0xd9.toByte()) return false
        val now = nowMs()
        if (attempt.lastImageAt?.let { now - it < 500 } == true) return false
        val payload = LiveTranslateProtocol.append("input_image_buffer.append", "image", jpeg)
        val socket = attempt.socket ?: return false
        if (socket.queueSize() + payload.length > LiveTranslateProtocol.MAX_SOCKET_BYTES) return false
        if (!send(attempt, payload)) return false
        attempt.lastImageAt = now
        return true
    }

    private fun send(attempt: Attempt, payload: String): Boolean {
        if (!healthy(attempt)) return false
        val socket = attempt.socket ?: return false
        val sent = runCatching {
            socket.queueSize() + payload.length <= LiveTranslateProtocol.MAX_SOCKET_BYTES && socket.send(payload)
        }.getOrDefault(false)
        if (!sent) fail(attempt, "Translation could not send data fast enough.")
        return sent
    }

    private fun current(attempt: Attempt) = active === attempt && !attempt.retired.get()
    private fun healthy(attempt: Attempt) = current(attempt) && attempt.failure.get() == null

    private fun fail(attempt: Attempt, message: String) {
        if (!current(attempt)) return
        retire(attempt)
        mutableError.value = message
        mutableState.value = ERROR
    }

    private fun retire(attempt: Attempt) {
        if (!attempt.retired.compareAndSet(false, true)) return
        if (active === attempt) active = null
        attempt.acceptingInput.set(false)
        attempt.ready = false
        attempt.job.cancel()
        attempt.events.cancel(); attempt.input.cancel(); attempt.output.cancel()
        runCatching { attempt.source.stop() }
        runCatching { playback.stop() }
        attempt.socket?.let { socket ->
            // Immediate user stop/error: no claim of trailing-cloud flush or finish acknowledgement.
            runCatching { socket.close(1000, "Translation stopped") }
            runCatching { socket.cancel() }
        }
        attempt.socket = null
    }

    @MainThread
    open fun disconnect() {
        active?.let { retire(it) }
        mutableError.value = null
        mutableState.value = DISCONNECTED
    }

    @MainThread
    open fun close() {
        if (closed) return
        closed = true
        disconnect()
        mutableText.value = TranslationText("", false)
        lifetime.cancel()
    }
}
