package com.smartview.glassai.services.openclaw

import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawNodeServiceTest {

    private val gateway = ScriptedGateway()
    private val store = InMemoryOpenClawSettingsStore()
    private val identity = OpenClawDeviceIdentity.fromSeed(ByteArray(32) { 7 })
    private val clientInfo = OpenClawClientInfo(version = "2.0.0", modelIdentifier = "Pixel 5", nodeId = "rayban-0123abcd")
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()
    private val chatEvents = mutableListOf<OpenClawChatEvent>()
    private lateinit var service: OpenClawNodeService

    private fun newService(
        reconnectDelaysMs: List<Long> = listOf(100L),
        tickIntervalMs: Long = 60_000L,
        maxReconnectAttempts: Int = 5,
    ): OpenClawNodeService = OpenClawNodeService(
        store = store,
        identity = lazyOf(identity),
        clientInfo = clientInfo,
        httpClient = httpClient,
        tickIntervalMs = tickIntervalMs,
        reconnectDelaysMs = reconnectDelaysMs,
        maxReconnectAttempts = maxReconnectAttempts,
        clock = { 1711700000000L },
    )

    private fun <T> StateFlow<T>.awaitValue(timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(timeoutMs) { first(predicate) } }

    /**
     * Records every state the flow emits from now on. Subscribes before returning, so a transient
     * state (Reconnecting/Connecting) cannot be missed by a later `first { }` on the conflated flow.
     */
    private fun recordStates(service: OpenClawNodeService): Pair<MutableList<OpenClawConnectionState>, Job> {
        val states = CopyOnWriteArrayList<OpenClawConnectionState>()
        val subscribed = CountDownLatch(1)
        val job = CoroutineScope(Dispatchers.Default).launch {
            service.connectionState.collect {
                states += it
                subscribed.countDown()
            }
        }
        assertTrue(subscribed.await(2, TimeUnit.SECONDS))
        return states to job
    }

    @Before
    fun setUp() {
        store.host = "127.0.0.1"
        store.scheme = "ws"
        store.saveToken("secret-token")
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.disconnect()
        gateway.stop()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun startGatewayAndConnect(upgrades: Int = 1): OpenClawNodeService {
        gateway.start(upgrades)
        store.port = gateway.server.port
        service = newService()
        service.connect()
        return service
    }

    @Test
    fun handshakeSendsTheIosConnectFrameAndBecomesConnected() {
        val service = startGatewayAndConnect()
        val connect = gateway.awaitMethod("connect")

        assertEquals("req", connect.get("type").asString)
        val params = connect.getAsJsonObject("params")
        assertEquals(3, params.get("minProtocol").asInt)
        assertEquals(3, params.get("maxProtocol").asInt)
        val client = params.getAsJsonObject("client")
        assertEquals("openclaw-android", client.get("id").asString)
        assertEquals("Ray-Ban Meta Glasses", client.get("displayName").asString)
        assertEquals("2.0.0", client.get("version").asString)
        assertEquals("node", client.get("mode").asString)
        assertEquals("android", client.get("platform").asString)
        assertEquals("Pixel 5", client.get("modelIdentifier").asString)
        assertEquals("operator", params.get("role").asString)
        assertEquals(listOf("operator.read", "operator.write"), params.getAsJsonArray("scopes").map { it.asString })
        assertEquals(listOf("camera"), params.getAsJsonArray("caps").map { it.asString })
        assertEquals(
            listOf("camera.snap", "camera.list", "device.status", "device.info"),
            params.getAsJsonArray("commands").map { it.asString },
        )
        assertEquals("secret-token", params.getAsJsonObject("auth").get("token").asString)
        val device = params.getAsJsonObject("device")
        assertEquals(identity.deviceId, device.get("id").asString)
        assertEquals(identity.publicKeyBase64Url, device.get("publicKey").asString)
        assertEquals(1711700000000L, device.get("signedAt").asLong)
        assertEquals("nonce-1", device.get("nonce").asString)
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            identity.deviceId, "openclaw-android", "node", "operator", listOf("operator.read", "operator.write"),
            1711700000000L, "secret-token", "nonce-1", "android", null,
        )
        Ed25519Verify(identity.publicKey).verify(
            Base64.getUrlDecoder().decode(device.get("signature").asString),
            payload.toByteArray(Charsets.UTF_8),
        )

        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        // token also travels as ?token= (URL-encoded)
        val request = gateway.server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/?token=secret-token", request.path)
    }

    @Test
    fun notPairedMovesToWaitingForPairing() {
        gateway.connectReply = ScriptedGateway.ConnectReply.NOT_PAIRED
        val service = startGatewayAndConnect()
        gateway.awaitMethod("connect")
        service.connectionState.awaitValue { it == OpenClawConnectionState.WaitingForPairing }
    }

    @Test
    fun chatEventsAreDeliveredTyped() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val collected = mutableListOf<OpenClawChatEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { collected += it } }
        Thread.sleep(200) // let the collector subscribe before the gateway emits
        gateway.send("""{"type":"event","event":"chat","payload":{"state":"delta","message":{"role":"assistant","content":[{"type":"text","text":"Hel"},{"type":"text","text":"lo"}]}}}""")
        gateway.send("""{"type":"event","event":"chat","payload":{"state":"final","message":{"role":"assistant","content":[{"type":"text","text":"Hello!"}]}}}""")
        val deadline = System.currentTimeMillis() + 5_000
        while (collected.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        job.cancel()

        assertEquals(listOf(OpenClawChatEvent("Hello", false), OpenClawChatEvent("Hello!", true)), collected)
    }

    @Test
    fun chatSendCarriesSessionKeyMessageIdempotencyKeyAndAttachment() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }

        assertTrue(service.sendChatMessage("hi there", imageJpegBase64 = "/9j/AAAA"))

        val frame = gateway.awaitMethod("chat.send")
        val params = frame.getAsJsonObject("params")
        assertEquals("turbometa-chat", params.get("sessionKey").asString)
        assertEquals("hi there", params.get("message").asString)
        assertFalse(params.get("idempotencyKey").asString.isBlank())
        val attachment = params.getAsJsonArray("attachments").single().asJsonObject
        assertEquals("image", attachment.get("type").asString)
        assertEquals("image/jpeg", attachment.get("mimeType").asString)
        assertEquals("/9j/AAAA", attachment.get("content").asString)
    }

    @Test
    fun invokeEventRoundTripsThroughTheRouter() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val seen = mutableListOf<OpenClawNodeInvokeRequest>()
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                seen += request
                val payload = JsonObject().apply { addProperty("deviceConnected", true) }
                return OpenClawNodeInvokeResult.success(request.id, payload)
            }
        })

        gateway.send("""{"type":"event","event":"node.invoke.request","payload":null,"params":{"id":"inv-1","command":"device.status","paramsjson":"{\"x\":1}","timeoutms":5000}}""")

        val result = gateway.awaitMethod("node.invoke.result")
        val params = result.getAsJsonObject("params")
        assertEquals("inv-1", params.get("id").asString)
        assertEquals("rayban-0123abcd", params.get("nodeId").asString)
        assertTrue(params.get("ok").asBoolean)
        val payload = JsonParser.parseString(params.get("payloadjson").asString).asJsonObject
        assertTrue(payload.get("deviceConnected").asBoolean)
        assertNull(params.get("error"))
        assertEquals("device.status", seen.single().command)
        assertEquals(1, seen.single().params!!.get("x").asInt)
        assertEquals(5000L, seen.single().timeoutMs)
    }

    @Test
    fun invokeRequestFrameUsesTheFrameIdAndReportsErrors() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
                OpenClawNodeInvokeResult.failure(request.id, "NO_FRAME", "No video frame available")
        })

        gateway.send("""{"type":"req","id":"req-9","method":"node.invoke","params":{"command":"camera.snap","params":{"maxWidth":800},"timeoutMs":1000}}""")

        val result = gateway.awaitMethod("node.invoke.result")
        val params = result.getAsJsonObject("params")
        assertEquals("req-9", params.get("id").asString)
        assertFalse(params.get("ok").asBoolean)
        assertEquals("NO_FRAME", params.getAsJsonObject("error").get("code").asString)
        assertEquals("No video frame available", params.getAsJsonObject("error").get("message").asString)
        assertNull(params.get("payloadjson"))
    }

    @Test
    fun reqFrameIdWinsOverParamsId() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
                OpenClawNodeInvokeResult.success(request.id, JsonObject())
        })

        gateway.send("""{"type":"req","id":"req-9","method":"node.invoke","params":{"id":"inv-x","command":"device.status"}}""")

        // research §2.5: for a `req node.invoke` the invoke id IS the frame id; params.id is ignored
        assertEquals("req-9", gateway.awaitMethod("node.invoke.result").getAsJsonObject("params").get("id").asString)
    }

    @Test
    fun malformedParamsJsonAndPayloadCarriedInvokesStillReachTheRouter() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val seen = CopyOnWriteArrayList<OpenClawNodeInvokeRequest>()
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                seen += request
                return OpenClawNodeInvokeResult.success(request.id, JsonObject())
            }
        })

        gateway.send("""{"type":"event","event":"node.invoke.request","params":{"id":"inv-bad","command":"camera.snap","paramsjson":"not json"}}""")
        val first = gateway.awaitMethod("node.invoke.result")
        assertEquals("inv-bad", first.getAsJsonObject("params").get("id").asString)
        assertNull(seen.single().params) // the router then applies the CameraSnapParams defaults
        assertEquals(1600, CameraSnapParams.from(seen.single().params).maxWidth)

        gateway.send("""{"type":"event","event":"node.invoke","payload":{"id":"inv-payload","command":"device.info"}}""")
        val second = gateway.awaitMethod("node.invoke.result")
        assertEquals("inv-payload", second.getAsJsonObject("params").get("id").asString)
        assertEquals("device.info", seen[1].command)
    }

    @Test
    fun dispatchAcceptsTheIosAliasesAndBinaryFrames() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val collected = CopyOnWriteArrayList<OpenClawChatEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { collected += it } }
        Thread.sleep(200)

        // `evt` + `method` instead of `event`/`event`, delivered as a binary frame
        gateway.sendBinary("""{"type":"evt","method":"chat","payload":{"state":"final","message":{"content":[{"type":"text","text":"bin"}]}}}""")
        // `request` instead of `req`
        gateway.send("""{"type":"request","id":"r-2","method":"nope","params":{}}""")

        val res = gateway.await { it.get("type")?.asString == "res" && it.get("id")?.asString == "r-2" }
        assertEquals("UNSUPPORTED", res.getAsJsonObject("error").get("code").asString)
        val deadline = System.currentTimeMillis() + 5_000
        while (collected.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        job.cancel()
        assertEquals(listOf(OpenClawChatEvent("bin", true)), collected)
    }

    @Test
    fun invokeWithoutRouterAnswersNoRouter() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        gateway.send("""{"type":"event","event":"node.invoke","params":{"id":"inv-2","command":"device.info"}}""")
        val result = gateway.awaitMethod("node.invoke.result")
        assertEquals("NO_ROUTER", result.getAsJsonObject("params").getAsJsonObject("error").get("code").asString)
    }

    @Test
    fun unknownRequestGetsUnsupported() {
        val service = startGatewayAndConnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        gateway.send("""{"type":"req","id":"r-1","method":"something.else","params":{}}""")
        val res = gateway.await { it.get("type")?.asString == "res" && it.get("id")?.asString == "r-1" }
        assertFalse(res.get("ok").asBoolean)
        assertEquals("UNSUPPORTED", res.getAsJsonObject("error").get("code").asString)
        assertEquals("Unknown method: something.else", res.getAsJsonObject("error").get("message").asString)
    }

    @Test
    fun tickIsSentPeriodicallyWithTs() {
        gateway.start(1)
        store.port = gateway.server.port
        service = newService(tickIntervalMs = 200L)
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        val tick = gateway.awaitMethod("tick")
        assertEquals(1711700000000L, tick.getAsJsonObject("params").get("ts").asLong)
    }

    @Test
    fun reconnectsAfterTheGatewayClosesTheSocket() {
        val service = startGatewayAndConnect(upgrades = 2)
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals(1, gateway.opens)
        val (states, recorder) = recordStates(service) // subscribed BEFORE the close: no missed transient

        gateway.socket!!.close(1000, "bye")

        service.connectionState.awaitValue(timeoutMs = 8_000) { it == OpenClawConnectionState.Connected && gateway.opens == 2 }
        recorder.cancel()
        assertTrue("states: $states", states.contains(OpenClawConnectionState.Reconnecting(1)))
        assertEquals(OpenClawConnectionState.Connected, states.last())
        assertEquals(2, gateway.opens)
        assertEquals(2, gateway.server.requestCount)
    }

    @Test
    fun connectDuringBackoffDoesNotDialEarlyButForceDoes() {
        gateway.start(2)
        store.port = gateway.server.port
        service = newService(reconnectDelaysMs = listOf(3_000L))
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }

        gateway.socket!!.close(1000, "bye")
        service.connectionState.awaitValue { it is OpenClawConnectionState.Reconnecting }
        service.connect() // what Home / the chat screen do on appear: must not cut the backoff short
        Thread.sleep(500)
        assertEquals(1, gateway.opens)
        assertTrue(service.connectionState.value is OpenClawConnectionState.Reconnecting)

        service.connect(force = true) // the Settings "Connect to Gateway" button: dial now
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals(2, gateway.opens)
    }

    @Test
    fun reconnectAttemptsResetAfterASuccessfulHello() {
        gateway.start(3)
        store.port = gateway.server.port
        service = newService(reconnectDelaysMs = listOf(50L), maxReconnectAttempts = 1)
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }

        repeat(2) { drop ->
            gateway.socket!!.close(1000, "drop ${drop + 1}")
            service.connectionState.awaitValue(timeoutMs = 8_000) {
                it == OpenClawConnectionState.Connected && gateway.opens == drop + 2
            }
        }

        // With maxReconnectAttempts = 1 the second drop would have ended in MaxRetries had the
        // counter not been reset to 0 by the hello that followed the first reconnect.
        assertEquals(3, gateway.opens)
        assertEquals(OpenClawConnectionState.Connected, service.connectionState.value)
    }

    @Test
    fun tickStopsOnDisconnect() {
        gateway.start(1)
        store.port = gateway.server.port
        service = newService(tickIntervalMs = 200L)
        service.connect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        gateway.awaitMethod("tick")
        assertTrue(service.isTickRunning)

        service.disconnect()

        assertFalse(service.isTickRunning)
        gateway.received.clear()
        Thread.sleep(600)
        assertNull(gateway.received.poll())
    }

    @Test
    fun connectFromWaitingForPairingClosesTheOldSocketFirst() {
        gateway.connectReply = ScriptedGateway.ConnectReply.NOT_PAIRED
        val service = startGatewayAndConnect(upgrades = 2)
        service.connectionState.awaitValue { it == OpenClawConnectionState.WaitingForPairing }
        gateway.connectReply = ScriptedGateway.ConnectReply.OK // `openclaw devices approve` ran meanwhile

        service.connect(force = true)

        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals(2, gateway.opens)
        val deadline = System.currentTimeMillis() + 2_000
        while (gateway.closes < 1 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, gateway.closes) // the pairing-wait socket was closed, not orphaned
    }

    @Test
    fun withoutATokenAuthIsEmptyAndTheSignatureHasAnEmptyTokenField() {
        store.saveToken(null)
        val service = startGatewayAndConnect()
        val connect = gateway.awaitMethod("connect")
        val params = connect.getAsJsonObject("params")
        assertTrue(params.getAsJsonObject("auth").entrySet().isEmpty()) // `auth: {}`
        val device = params.getAsJsonObject("device")
        val payload = "v3|${identity.deviceId}|openclaw-android|node|operator|operator.read,operator.write|1711700000000||nonce-1|android|"
        Ed25519Verify(identity.publicKey).verify(
            Base64.getUrlDecoder().decode(device.get("signature").asString),
            payload.toByteArray(Charsets.UTF_8),
        )
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        assertEquals("/", gateway.server.takeRequest(2, TimeUnit.SECONDS)!!.path) // no ?token=
    }

    @Test
    fun nonHelloResponsesDoNotChangeTheState() {
        gateway.connectReply = ScriptedGateway.ConnectReply.SILENT
        val service = startGatewayAndConnect()
        gateway.awaitMethod("connect")
        assertEquals(OpenClawConnectionState.Connecting, service.connectionState.value)

        gateway.send("""{"type":"res","id":"not-the-connect-id","ok":true,"payload":{"protocol":3}}""")
        gateway.send("""{"type":"res","id":"not-the-connect-id","ok":false,"error":{"code":"UNAUTHORIZED","message":"nope"}}""")
        Thread.sleep(300)

        // research §10: iOS treats every ok:true as "hello"; Android matches the connect id only,
        // and an ok:false with any code other than NOT_PAIRED leaves the state alone.
        assertEquals(OpenClawConnectionState.Connecting, service.connectionState.value)
        assertFalse(service.isTickRunning)
    }

    @Test
    fun buildUrlHandlesWssIpv6AndTokenEncoding() {
        service = newService()
        store.host = "gateway.local"
        store.port = 8443
        store.scheme = "wss"
        store.saveToken("a b+c")
        assertEquals("wss://gateway.local:8443/?token=a%20b%2Bc", service.buildUrl())

        store.scheme = "ws"
        store.port = 18789
        store.host = "[::1]"
        store.saveToken(null)
        assertEquals("ws://[::1]:18789/", service.buildUrl())

        store.host = "::1" // a bare IPv6 literal is bracketed for the user
        assertEquals("ws://[::1]:18789/", service.buildUrl())

        store.host = "bad host"
        assertNull(service.buildUrl())
    }

    @Test
    fun lanHttpClientBypassesProxiesAndNeverTimesOutReads() {
        val client = OpenClawNodeService.lanHttpClient()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis)
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun givesUpAfterMaxAttemptsWithMaxRetriesError() {
        gateway.start(0)
        store.port = gateway.server.port
        gateway.stop() // nothing listens on that port any more
        service = newService(reconnectDelaysMs = listOf(20L))
        service.connect()
        val state = service.connectionState.awaitValue(timeoutMs = 15_000) { it is OpenClawConnectionState.Error }
        assertEquals(OpenClawConnectionState.Error(OpenClawErrorReason.MaxRetries(5)), state)
    }

    @Test
    fun disconnectStopsReconnecting() {
        val service = startGatewayAndConnect(upgrades = 2)
        service.connectionState.awaitValue { it == OpenClawConnectionState.Connected }
        service.disconnect()
        service.connectionState.awaitValue { it == OpenClawConnectionState.Disconnected }
        Thread.sleep(400)
        assertEquals(1, gateway.opens)
        assertEquals(OpenClawConnectionState.Disconnected, service.connectionState.value)
    }

    @Test
    fun invalidHostIsReportedWithoutTouchingTheNetwork() {
        store.host = "   "
        service = newService()
        service.connect()
        assertEquals(OpenClawConnectionState.Error(OpenClawErrorReason.InvalidUrl), service.connectionState.value)
    }

    @Test
    fun savingABlankTokenDeletesIt() {
        service = newService()
        service.saveGatewayToken("   ")
        assertNull(service.loadGatewayToken())
    }
}
