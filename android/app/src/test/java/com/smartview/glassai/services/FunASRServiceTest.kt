package com.smartview.glassai.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.BluetoothAudioManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunASRServiceTest {

    private val server = MockWebServer()
    private val textFrames = LinkedBlockingQueue<JsonObject>()
    private val binaryFrames = LinkedBlockingQueue<ByteArray>()
    @Volatile private var serverSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private class FakeAudio : PcmAudioSource {
        var startCalls = 0
        var stopCalls = 0
        var chunk = ByteArray(320) { it.toByte() }
        override fun start(onChunk: (ByteArray) -> Unit): Boolean {
            startCalls++
            onChunk(chunk)
            return true
        }
        override fun stop() { stopCalls++ }
    }

    private val audio = FakeAudio()
    private val createdFor = mutableListOf<BluetoothAudioManager.AudioSource>()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { textFrames.add(JsonParser.parseString(text).asJsonObject) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { binaryFrames.add(bytes.toByteArray()) }
    }

    private fun newService(): FunASRService {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        return FunASRService(
            apiKey = "sk-test",
            endpoint = AlibabaEndpoint.BEIJING,
            httpClient = httpClient,
            audioSourceFactory = { source -> createdFor += source; audio },
            endpointUrlOverride = "ws://${server.hostName}:${server.port}/api-ws/v1/inference",
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() } // some tests shut it down mid-test on purpose
        httpClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun endpointsFollowTheAlibabaRegion() {
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference", FunASRService.endpointUrl(AlibabaEndpoint.BEIJING))
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", FunASRService.endpointUrl(AlibabaEndpoint.SINGAPORE))
    }

    @Test
    fun resolvedUrlFollowsTheRegionUnlessOverridden() {
        val singapore = FunASRService(
            apiKey = "sk-test", endpoint = AlibabaEndpoint.SINGAPORE, httpClient = httpClient,
            audioSourceFactory = { audio },
        )
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", singapore.resolvedUrl())
        val overridden = FunASRService(
            apiKey = "sk-test", endpoint = AlibabaEndpoint.SINGAPORE, httpClient = httpClient,
            audioSourceFactory = { audio }, endpointUrlOverride = "ws://127.0.0.1:1/x",
        )
        assertEquals("ws://127.0.0.1:1/x", overridden.resolvedUrl())
    }

    @Test
    fun transportFailureReportsAnErrorUnlessStopping() {
        val service = newService()
        val errors = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        service.onError = { errors += it; latch.countDown() }
        service.start()
        assertNotNull(textFrames.poll(5, TimeUnit.SECONDS)) // run-task went out
        server.shutdown() // the connection dies underneath the client -> onFailure

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, errors.size)
        assertFalse(service.isListening.value)
    }

    @Test
    fun transportFailureAfterStopIsSilent() {
        val service = newService()
        val errors = CopyOnWriteArrayList<String>()
        service.onError = { errors += it }
        service.start()
        assertNotNull(textFrames.poll(5, TimeUnit.SECONDS)) // run-task
        service.stop() // stopping = true; finish-task sent; close scheduled
        assertNotNull(textFrames.poll(5, TimeUnit.SECONDS)) // finish-task
        server.shutdown()
        Thread.sleep(700) // > CLOSE_DELAY_MS: whichever of onClosed/onFailure fires, no error surfaces

        assertTrue("unexpected errors: $errors", errors.isEmpty())
        assertFalse(service.isListening.value)
    }

    @Test
    fun taskFinishedFromTheServerStopsTheMicAndReportsFinished() {
        val service = newService()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        service.onStarted = { started.countDown() }
        service.onFinished = { finished.countDown() }
        service.start()
        val taskId = textFrames.poll(5, TimeUnit.SECONDS)!!.getAsJsonObject("header").get("task_id").asString
        serverSocket!!.send("""{"header":{"event":"task-started","task_id":"$taskId"}}""")
        assertTrue(started.await(5, TimeUnit.SECONDS))

        serverSocket!!.send("""{"header":{"event":"task-finished","task_id":"$taskId"}}""") // no stop() first

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(service.isListening.value)
        assertEquals(1, audio.stopCalls)
        assertNull(textFrames.poll(300, TimeUnit.MILLISECONDS)) // no finish-task after the server ended it
    }

    @Test
    fun fullSessionRunTaskAudioResultsFinishTask() {
        val service = newService()
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        service.onStarted = { started.countDown() }
        service.onPartialResult = { partials += it }
        service.onFinalResult = { finals += it }
        service.onFinished = { finished.countDown() }

        service.start()

        // 1. run-task with the exact DashScope header/payload
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val runTask = textFrames.poll(5, TimeUnit.SECONDS)!!
        val header = runTask.getAsJsonObject("header")
        assertEquals("run-task", header.get("action").asString)
        assertEquals("duplex", header.get("streaming").asString)
        val taskId = header.get("task_id").asString
        assertEquals(32, taskId.length)
        assertTrue(taskId.all { it in '0'..'9' || it in 'a'..'f' })
        val payload = runTask.getAsJsonObject("payload")
        assertEquals("audio", payload.get("task_group").asString)
        assertEquals("asr", payload.get("task").asString)
        assertEquals("recognition", payload.get("function").asString)
        assertEquals("fun-asr-realtime", payload.get("model").asString)
        val parameters = payload.getAsJsonObject("parameters")
        assertEquals("pcm", parameters.get("format").asString)
        assertEquals(16000, parameters.get("sample_rate").asInt)
        assertEquals("", parameters.get("vocabulary_id").asString)
        assertFalse(parameters.get("disfluency_removal_enabled").asBoolean)
        assertTrue(payload.getAsJsonObject("input").entrySet().isEmpty())
        assertEquals(0, audio.startCalls) // mic must not start before task-started

        // 2. task-started -> mic starts and PCM goes out as a binary frame
        serverSocket!!.send("""{"header":{"event":"task-started","task_id":"$taskId"}}""")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val pcm = binaryFrames.poll(5, TimeUnit.SECONDS)
        assertNotNull(pcm)
        assertEquals(320, pcm!!.size)
        assertEquals(1, audio.startCalls)
        assertEquals(listOf(BluetoothAudioManager.AudioSource.PHONE_MIC), createdFor)
        assertTrue(service.isListening.value)

        // 3. results: end_time null/0 = partial, > 0 = final
        serverSocket!!.send("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好","end_time":null}}}}""")
        serverSocket!!.send("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好世界","end_time":1234}}}}""")
        val deadline = System.currentTimeMillis() + 5_000
        while (finals.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("你好"), partials)
        assertEquals(listOf("你好世界"), finals)

        // 4. stop -> mic stops, finish-task sent, then task-finished closes the session
        service.stop()
        assertEquals(1, audio.stopCalls)
        val finish = textFrames.poll(5, TimeUnit.SECONDS)!!
        assertEquals("finish-task", finish.getAsJsonObject("header").get("action").asString)
        assertEquals(taskId, finish.getAsJsonObject("header").get("task_id").asString)
        assertEquals("duplex", finish.getAsJsonObject("header").get("streaming").asString)
        assertTrue(finish.getAsJsonObject("payload").getAsJsonObject("input").entrySet().isEmpty())
        serverSocket!!.send("""{"header":{"event":"task-finished","task_id":"$taskId"}}""")
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(service.isListening.value)
    }

    @Test
    fun taskFailedReportsTheServerMessage() {
        val service = newService()
        val errors = mutableListOf<String>()
        val latch = CountDownLatch(1)
        service.onError = { errors += it; latch.countDown() }
        service.start()
        textFrames.poll(5, TimeUnit.SECONDS)
        serverSocket!!.send("""{"header":{"event":"task-failed","error_code":"InvalidParameter","error_message":"bad model"}}""")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("bad model"), errors)
        assertFalse(service.isListening.value)
        assertEquals(0, audio.startCalls)
    }

    @Test
    fun switchingTheAudioSourceRestartsCaptureFromTheNewSource() {
        val service = newService()
        val started = CountDownLatch(1)
        service.onStarted = { started.countDown() }
        service.start()
        val taskId = textFrames.poll(5, TimeUnit.SECONDS)!!.getAsJsonObject("header").get("task_id").asString
        serverSocket!!.send("""{"header":{"event":"task-started","task_id":"$taskId"}}""")
        assertTrue(started.await(5, TimeUnit.SECONDS))

        service.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC)

        assertEquals(1, audio.stopCalls)
        assertEquals(2, audio.startCalls)
        assertEquals(
            listOf(BluetoothAudioManager.AudioSource.PHONE_MIC, BluetoothAudioManager.AudioSource.BLUETOOTH_MIC),
            createdFor,
        )
        service.stop()
    }
}
