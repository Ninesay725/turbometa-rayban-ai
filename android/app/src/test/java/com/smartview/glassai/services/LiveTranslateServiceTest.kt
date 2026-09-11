package com.smartview.glassai.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.translation.TranslateConnectionState.*
import com.smartview.glassai.translation.TranslateSettings
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTranslateServiceTest {
    private class Capture(var startsSuccessfully: Boolean = true) : PcmAudioSource {
        var starts = 0
        var stops = 0
        var callback: ((ByteArray) -> Unit)? = null
        var duringStart: (() -> Unit)? = null
        override fun start(onChunk: (ByteArray) -> Unit): Boolean {
            starts++
            callback = onChunk
            duringStart?.invoke()
            return startsSuccessfully
        }
        override fun stop() { stops++ }
        fun emit(vararg bytes: Byte) { callback?.invoke(bytes) }
    }

    private class Output(private val consume: Boolean = true) : Pcm16Playback {
        val chunks = CopyOnWriteArrayList<ByteArray>()
        var stops = 0
        override suspend fun play(chunks: Flow<ByteArray>) {
            if (!consume) awaitCancellation()
            chunks.collect { this.chunks += it }
        }
        override fun stop() { stops++ }
    }

    private class Socket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<JsonObject>()
        var cancelled = false
        var accept = true
        var queued = 0L
        override fun request() = request
        override fun queueSize() = queued
        override fun send(text: String): Boolean {
            if (accept) sent += json(text)
            return accept
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("upgrade").build())
        fun message(value: String) = listener.onMessage(this, value)
        fun ack(settings: TranslateSettings = TranslateSettings()) = message(
            json(LiveTranslateProtocol.configure(settings)).apply { addProperty("type", "session.updated") }.toString())
        fun fail() = listener.onFailure(this, IOException("private credential MUST NOT APPEAR"), null)
    }

    private class Sockets : WebSocket.Factory {
        val all = mutableListOf<Socket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also { all += it }
        val last get() = all.last()
    }

    private fun TestScope.service(sockets: Sockets, output: Output = Output(), timeout: Long = 1_000) =
        LiveTranslateService(sockets, output, StandardTestDispatcher(testScheduler), timeout) { testScheduler.currentTime }

    @Test fun readinessRequiresCurrentMatchingAckAndSuccessfulCaptureStart() = runTest {
        val sockets = Sockets(); val capture = Capture(); val output = Output()
        val service = service(sockets, output)
        capture.duringStart = { assertEquals(CONNECTING, service.state.value); capture.emit(1, 2) }
        service.connect("fixture", "wss://example.test/realtime?model=wrong", TranslateSettings(), capture)
        assertEquals(CONNECTING, service.state.value)
        sockets.last.open(); runCurrent()
        assertEquals(LiveTranslateProtocol.MODEL, sockets.last.request().url.queryParameter("model"))
        assertEquals(0, capture.starts)
        sockets.last.message("""{"type":"session.created"}"""); runCurrent()
        assertEquals(CONNECTING, service.state.value)
        sockets.last.ack(); runCurrent()
        assertEquals(READY, service.state.value)
        assertEquals(1, capture.starts)
        assertEquals("input_audio_buffer.append", sockets.last.sent.last()["type"].asString)
        sockets.last.ack(); runCurrent()
        assertEquals(1, capture.starts)
        service.close()
        assertEquals(1, capture.stops)
        assertEquals(1, output.stops)
    }

    @Test fun missingAcknowledgementExpiresAndLateAckCannotReviveTheAttempt() = runTest {
        val sockets = Sockets(); val capture = Capture(); val output = Output()
        val service = service(sockets, output)
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        sockets.last.open(); sockets.last.message("""{"type":"session.created"}"""); runCurrent()
        advanceTimeBy(999); runCurrent(); assertEquals(CONNECTING, service.state.value)
        advanceTimeBy(1); runCurrent()
        assertEquals(ERROR, service.state.value)
        assertTrue(sockets.last.cancelled)
        assertEquals(0, capture.starts)
        assertEquals(1, capture.stops)
        assertEquals(1, output.stops)
        sockets.last.ack(); runCurrent()
        assertEquals(ERROR, service.state.value)
        assertEquals(0, capture.starts)
        service.close()
    }

    @Test fun connectWithoutSocketOpenAlsoTimesOut() = runTest {
        val sockets = Sockets(); val capture = Capture(); val service = service(sockets)
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(ERROR, service.state.value)
        assertTrue(sockets.last.cancelled)
        service.close()
    }

    @Test fun mismatchedConfigurationAndCaptureFailureRetireEverything() = runTest {
        for (mismatch in listOf(true, false)) {
            val sockets = Sockets(); val capture = Capture(startsSuccessfully = false)
            val output = Output(); val service = service(sockets, output)
            service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
            sockets.last.open(); runCurrent()
            sockets.last.ack(TranslateSettings(audioEnabled = !mismatch)); runCurrent()
            assertEquals(ERROR, service.state.value)
            assertEquals(if (mismatch) 0 else 1, capture.starts)
            assertEquals(1, capture.stops)
            assertEquals(1, output.stops)
            assertTrue(sockets.last.cancelled)
            assertNotNull(service.error.value)
            service.close()
        }
    }

    @Test fun stoppedSocketAckFailureAndCaptureCannotAffectSuccessor() = runTest {
        val sockets = Sockets(); val oldCapture = Capture(); val capture = Capture(); val service = service(sockets)
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), oldCapture)
        val old = sockets.last
        old.open(); runCurrent()
        service.disconnect()
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        old.ack(); old.fail(); runCurrent()
        assertEquals(CONNECTING, service.state.value)
        assertEquals(0, oldCapture.starts)
        assertEquals(1, oldCapture.stops)
        sockets.last.open(); sockets.last.ack(); runCurrent()
        assertEquals(READY, service.state.value)
        old.message("""{"type":"response.text.done","text":"stale"}"""); old.fail(); runCurrent()
        assertEquals("", service.text.value.text)
        assertEquals(0, capture.stops)
        assertEquals(READY, service.state.value)
        service.close()
    }

    @Test fun captureStartMustAlsoFinishWithinTheConfigurationDeadline() = runTest {
        val sockets = Sockets(); val capture = Capture(); val output = Output()
        var time = 0L
        val service = LiveTranslateService(sockets, output, StandardTestDispatcher(testScheduler), 1_000) { time }
        capture.duringStart = { time = 1_000 }
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        sockets.last.open(); sockets.last.ack(); runCurrent()
        assertEquals(ERROR, service.state.value)
        assertEquals(1, capture.starts)
        assertEquals(1, capture.stops)
        assertEquals(1, output.stops)
        assertTrue(sockets.last.cancelled)
        service.close()
    }

    @Test fun reconnectDropsOldCaptureCallbackAndCloseIsTerminal() = runTest {
        val sockets = Sockets(); val capture = Capture(); val service = service(sockets)
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        sockets.last.open(); sockets.last.ack(); runCurrent()
        val retiredCallback = capture.callback!!
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        sockets.last.open(); sockets.last.ack(); runCurrent()
        retiredCallback(byteArrayOf(9, 9)); runCurrent()
        assertEquals(1, sockets.last.sent.size)
        assertEquals(2, capture.starts)
        assertEquals(1, capture.stops)
        service.close(); service.close()
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        assertEquals(2, sockets.all.size)
        assertEquals(DISCONNECTED, service.state.value)
        assertEquals(2, capture.stops)
    }

    @Test fun imagesRequireEnabledReadyAcceptedAudioAndObeySizePacingAndBackpressure() = runTest {
        val sockets = Sockets(); val capture = Capture(); val service = service(sockets)
        val settings = TranslateSettings(imageEnabled = true)
        val jpeg = byteArrayOf(-1, -40, 0, -1, -39)
        service.connect("fixture", "wss://example.test/realtime", settings, capture)
        assertFalse(service.sendImage(jpeg))
        sockets.last.open(); sockets.last.ack(settings); runCurrent()
        assertFalse(service.sendImage(jpeg))
        capture.emit(1); runCurrent(); assertFalse(service.sendImage(jpeg))
        capture.emit(2); runCurrent()
        assertFalse(service.sendImage(ByteArray(500_001)))
        assertFalse(service.sendImage(byteArrayOf(1, 2, 3)))
        assertTrue(service.sendImage(jpeg))
        assertFalse(service.sendImage(jpeg))
        advanceTimeBy(499); assertFalse(service.sendImage(jpeg))
        advanceTimeBy(1); assertTrue(service.sendImage(jpeg))
        advanceTimeBy(500); sockets.last.queued = 1_048_576
        assertFalse(service.sendImage(jpeg))
        sockets.last.queued = 0
        assertTrue(service.sendImage(jpeg))
        service.disconnect(); assertFalse(service.sendImage(jpeg))
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        sockets.last.open(); sockets.last.ack(); runCurrent(); capture.emit(1, 2); runCurrent()
        assertFalse(service.sendImage(jpeg))
        service.close()
    }

    @Test fun pcmInputPreservesOddBoundariesAndAudioOutputIsDecodedAsBytes() = runTest {
        val sockets = Sockets(); val capture = Capture(); val output = Output(); val service = service(sockets, output)
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        sockets.last.open(); sockets.last.ack(); runCurrent()
        capture.emit(1); runCurrent(); capture.emit(2, 3, 4, 5); runCurrent(); capture.emit(6); runCurrent()
        val bytes = sockets.last.sent.filter { it["type"].asString == "input_audio_buffer.append" }
            .flatMap { Base64.getDecoder().decode(it["audio"].asString).toList() }.toByteArray()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), bytes)
        sockets.last.message("""{"type":"response.audio.delta","delta":"AQID"}"""); runCurrent()
        sockets.last.message("""{"type":"response.audio.delta","delta":"BA=="}"""); runCurrent()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.chunks.flatMap { it.toList() }.toByteArray())
        service.close()
    }

    @Test fun inputOutputAndSocketBackpressureFailVisiblyWithBoundedQueues() = runTest {
        for (mode in listOf("input", "output", "socket", "invalidAudio", "oversizeEvent", "sendRejected")) {
            val sockets = Sockets(); val capture = Capture(); val output = Output(consume = false)
            val service = service(sockets, output)
            service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
            sockets.last.open(); sockets.last.ack(); runCurrent()
            when (mode) {
                "input" -> repeat(20) { capture.emit(1, 2) }
                "output" -> repeat(20) { sockets.last.message("""{"type":"response.audio.delta","delta":"AQI="}"""); runCurrent() }
                "socket" -> { sockets.last.queued = 1_048_576; capture.emit(1, 2) }
                "invalidAudio" -> sockets.last.message("""{"type":"response.audio.delta","delta":"%invalid%"}""")
                "oversizeEvent" -> sockets.last.message("x".repeat(131_073))
                "sendRejected" -> { sockets.last.accept = false; capture.emit(1, 2) }
            }
            runCurrent()
            assertEquals(mode, ERROR, service.state.value)
            assertEquals(mode, 1, capture.stops)
            assertEquals(mode, 1, output.stops)
            assertTrue(mode, sockets.last.cancelled)
            service.close()
        }
    }

    @Test fun protocolAndTransportErrorsNeverExposeProviderBodies() = runTest {
        for (transport in listOf(true, false)) {
            val sockets = Sockets(); val capture = Capture(); val service = service(sockets)
            service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
            sockets.last.open(); runCurrent()
            if (transport) sockets.last.fail() else sockets.last.message("""{"type":"error","error":{"message":"private credential MUST NOT APPEAR"}}""")
            runCurrent()
            assertEquals(ERROR, service.state.value)
            assertFalse(service.error.value!!.contains("private"))
            service.close()
        }
    }

    @Test fun textOnlyIgnoresAudioAndFailedResponseNeverBecomesASuccessfulFinal() = runTest {
        val sockets = Sockets(); val capture = Capture(); val output = Output()
        val service = service(sockets, output)
        val settings = TranslateSettings(audioEnabled = false)
        service.connect("fixture", "wss://example.test/realtime", settings, capture)
        sockets.last.open(); sockets.last.ack(settings); runCurrent()
        sockets.last.message("""{"type":"response.text.text","response_id":"one","text":"hello ","stash":"world"}""")
        sockets.last.message("""{"type":"response.audio.delta","delta":"AQI="}"""); runCurrent()
        assertEquals("hello world", service.text.value.text)
        assertFalse(service.text.value.isFinal)
        assertTrue(output.chunks.isEmpty())
        sockets.last.message("""{"type":"response.text.done","response_id":"one","text":"hello world"}"""); runCurrent()
        assertTrue(service.text.value.isFinal)
        sockets.last.message("""{"type":"response.done","response":{"id":"one","status":"failed"}}"""); runCurrent()
        assertEquals(ERROR, service.state.value)
        assertFalse(service.text.value.isFinal)
        service.close()
    }

    @Test fun retiredResponseAudioCannotLeakIntoCurrentResponse() = runTest {
        val sockets = Sockets(); val capture = Capture(); val output = Output(); val service = service(sockets, output)
        service.connect("fixture", "wss://example.test/realtime", TranslateSettings(), capture)
        sockets.last.open(); sockets.last.ack(); runCurrent()
        sockets.last.message("""{"type":"response.created","response":{"id":"one"}}""")
        sockets.last.message("""{"type":"response.created","response":{"id":"two"}}""")
        sockets.last.message("""{"type":"response.audio.delta","response_id":"one","delta":"AQI="}""")
        sockets.last.message("""{"type":"response.audio.delta","response_id":"two","delta":"AwQ="}"""); runCurrent()
        assertEquals(1, output.chunks.size)
        assertArrayEquals(byteArrayOf(3, 4), output.chunks.single())
        service.close()
    }

    @Test fun mockWebServerExchangesExactConfigurationRawPcmAndFinalTranscript() {
        val server = MockWebServer()
        val frames = LinkedBlockingQueue<JsonObject>()
        val peer = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = json(text); frames.add(event)
                if (event["type"].asString == "session.update") {
                    val ack = event.deepCopy()
                    // The optional ASR echo is absent unless a separate source ASR model is configured.
                    ack.getAsJsonObject("session").remove("input_audio_transcription")
                    ack.getAsJsonObject("session").getAsJsonObject("turn_detection").remove("prefix_padding_ms")
                    ack.addProperty("type", "session.updated"); webSocket.send(ack.toString())
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            runBlocking(dispatcher) {
                val capture = Capture(); val output = Output()
                val service = LiveTranslateService(client, output, dispatcher)
                try {
                    service.connect("fixture-key", server.url("/api-ws/v1/realtime").toString(), TranslateSettings(), capture)
                    withTimeout(5_000) { service.state.first { it == READY } }
                    val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS)!! }
                    assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
                    assertEquals(LiveTranslateProtocol.MODEL, request.requestUrl!!.queryParameter("model"))
                    assertEquals("session.update", frames.poll(5, TimeUnit.SECONDS)!!["type"].asString)
                    capture.emit(1, 2, 3, 4)
                    val audio = withContext(Dispatchers.IO) { frames.poll(5, TimeUnit.SECONDS)!! }
                    assertEquals("input_audio_buffer.append", audio["type"].asString)
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(audio["audio"].asString))
                    val socket = peer.poll(5, TimeUnit.SECONDS)!!
                    socket.send("""{"type":"response.audio_transcript.text","response_id":"one","text":"你好","stash":"啊"}""")
                    withTimeout(5_000) { service.text.first { it.text == "你好啊" } }
                    socket.send("""{"type":"response.audio_transcript.done","response_id":"one","transcript":"你好！"}""")
                    assertEquals("你好！", withTimeout(5_000) { service.text.first { it.isFinal } }.text)
                    service.disconnect()
                    assertEquals(DISCONNECTED, service.state.value)
                    assertEquals(1, capture.stops)
                } finally { service.close() }
            }
        } finally {
            dispatcher.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    companion object { private fun json(value: String) = JsonParser.parseString(value).asJsonObject }
}
