package com.smartview.glassai.services.openclaw

import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Wire expectations are pinned to openclaw/openclaw v2026.9.4, not our payload builder. */
class OpenClawCompatibilityTest {
    private val gateway = ScriptedGateway()
    private val store = InMemoryOpenClawSettingsStore()
    private val identity = OpenClawDeviceIdentity.fromSeed(ByteArray(32) { 7 })
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private lateinit var service: OpenClawNodeService

    @Before fun setUp() {
        gateway.protocolVersion = 4
        gateway.helloAuth = JsonObject().apply {
            addProperty("role", "node")
            add("scopes", com.google.gson.JsonArray())
            addProperty("deviceToken", "paired-node-token")
        }
        store.saveToken("shared-token")
    }

    @After fun tearDown() {
        if (::service.isInitialized) service.disconnect()
        gateway.stop()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun connect(upgrades: Int = 1) {
        gateway.start(upgrades)
        store.port = gateway.server.port
        service = newService()
        service.connect()
    }

    private fun newService() = OpenClawNodeService(
        store, lazyOf(identity), OpenClawClientInfo("test", "Pixel 5", "rayban-test"), client,
        reconnectDelaysMs = listOf(50L), clock = { 1711700000000L },
    )

    private fun awaitState(predicate: (OpenClawConnectionState) -> Boolean) = runBlocking {
        withTimeout(5_000) { service.connectionState.first(predicate) }
    }

    private fun connected() = awaitState { it == OpenClawConnectionState.Connected }

    private fun eventSession(frame: JsonObject): String =
        JsonParser.parseString(frame.getAsJsonObject("params").get("payloadJSON").asString)
            .asJsonObject.get("sessionKey").asString

    @Test fun redirectDestinationNeverReceivesTheOriginalGatewaysSharedCredential() {
        assertRedirectDoesNotConnectToDestination(cachedToken = false)
    }

    @Test fun redirectDestinationNeverReceivesTheOriginalGatewaysPairedCredential() {
        assertRedirectDoesNotConnectToDestination(cachedToken = true)
    }

    private fun assertRedirectDoesNotConnectToDestination(cachedToken: Boolean) {
        gateway.start() // Destination would challenge and collect a signed connect if followed.
        val origin = MockWebServer()
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", gateway.server.url("/")))
        origin.start()
        try {
            store.port = origin.port
            if (cachedToken) store.saveDeviceToken("ws://127.0.0.1:${origin.port}/", identity.deviceId, "node", "origin-paired-token")
            assertTrue(client.followRedirects) // The injected client is deliberately permissive.
            assertTrue(client.followSslRedirects)
            service = OpenClawNodeService(store, lazyOf(identity), OpenClawClientInfo("test", "Pixel", "rayban-test"),
                client, maxReconnectAttempts = 0)
            service.connect()
            awaitState { it is OpenClawConnectionState.Error || it == OpenClawConnectionState.Connected }
            assertNotNull(origin.takeRequest(2, TimeUnit.SECONDS))
            assertNull(gateway.server.takeRequest(200, TimeUnit.MILLISECONDS))
            assertEquals(0, gateway.opens)
            assertTrue(gateway.received.isEmpty())
            assertTrue(service.connectionState.value is OpenClawConnectionState.Error)
        } finally {
            if (::service.isInitialized) service.disconnect()
            origin.shutdown()
        }
    }

    private fun verifySignature(params: JsonObject, token: String, nonce: String = "nonce-1") {
        val device = params.getAsJsonObject("device")
        val expected = "v3|${identity.deviceId}|openclaw-android|node|node||1711700000000|$token|$nonce|android|"
        Ed25519Verify(identity.publicKey).verify(
            Base64.getUrlDecoder().decode(device.get("signature").asString), expected.toByteArray(),
        )
    }

    @Test fun currentConnectIsANodeWithV3SignatureAndNoCredentialInUrl() {
        connect()
        val params = gateway.awaitMethod("connect").getAsJsonObject("params")
        assertEquals(4, params.get("minProtocol").asInt)
        assertEquals(4, params.get("maxProtocol").asInt)
        assertEquals("openclaw-android", params.getAsJsonObject("client").get("id").asString)
        assertEquals("node", params.get("role").asString)
        assertEquals(0, params.getAsJsonArray("scopes").size())
        verifySignature(params, "shared-token")
        connected()
        assertEquals("/", gateway.server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertFalse(service.isTickRunning) // tick is a server event; WebSocket ping handles liveness.
    }

    @Test fun issuedTokenSurvivesServiceRecreationAndSignsReconnect() {
        connect(upgrades = 2)
        gateway.awaitMethod("connect")
        connected()
        service.disconnect()
        service = newService()
        service.connect()
        val params = gateway.awaitMethod("connect").getAsJsonObject("params")
        assertEquals("paired-node-token", params.getAsJsonObject("auth").get("deviceToken").asString)
        assertNull(params.getAsJsonObject("auth").get("token"))
        verifySignature(params, "paired-node-token")
        connected()
    }

    @Test fun tokenIsBoundToEndpointDeviceAndRole() {
        connect()
        connected()
        val endpoint = "ws://127.0.0.1:${store.port}/"
        assertEquals("paired-node-token", store.loadDeviceToken(endpoint, identity.deviceId, "node"))
        assertNull(store.loadDeviceToken(endpoint, identity.deviceId, "operator"))
        assertNull(store.loadDeviceToken(endpoint, "another-device", "node"))
        assertNull(store.loadDeviceToken(endpoint.replace("ws:", "wss:"), identity.deviceId, "node"))
        assertNull(store.loadDeviceToken("ws://another-host:${store.port}/", identity.deviceId, "node"))
    }

    @Test fun officialThreeIsAvailableOnlyWithExplicitRangeConfiguration() {
        store.compatibility = OpenClawCompatibility.ALLOW_NODE_V3
        gateway.protocolVersion = 3
        connect()
        val params = gateway.awaitMethod("connect").getAsJsonObject("params")
        assertEquals(3, params.get("minProtocol").asInt)
        assertEquals(4, params.get("maxProtocol").asInt)
        assertEquals("node", params.get("role").asString)
        verifySignature(params, "shared-token")
        connected()
    }

    @Test fun currentModeRejectsOldHelloWithoutRetryingOrDowngrading() {
        gateway.protocolVersion = 3
        connect(upgrades = 2)
        awaitState { it is OpenClawConnectionState.Error }
        Thread.sleep(200)
        assertEquals(1, gateway.opens)
        assertNull(store.loadDeviceToken("ws://127.0.0.1:${store.port}/", identity.deviceId, "node"))
    }

    @Test fun wrongRoleHelloCannotPersistAnOperatorToken() {
        gateway.helloAuth!!.addProperty("role", "operator")
        connect()
        awaitState { it is OpenClawConnectionState.Error }
        assertNull(store.loadDeviceToken("ws://127.0.0.1:${store.port}/", identity.deviceId, "node"))
    }

    @Test fun nodeHelloCannotExpandScopes() {
        gateway.helloAuth!!.getAsJsonArray("scopes").add("operator.admin")
        connect()
        awaitState { it is OpenClawConnectionState.Error }
        assertNull(store.loadDeviceToken("ws://127.0.0.1:${store.port}/", identity.deviceId, "node"))
    }

    @Test fun pairingWaitSurvivesGatewayCloseAndExplicitReconnectUsesFreshHandshake() {
        gateway.connectReply = ScriptedGateway.ConnectReply.SILENT
        connect(upgrades = 2)
        val id = gateway.awaitMethod("connect").get("id").asString
        gateway.send("""{"type":"res","id":"$id","ok":false,"error":{"code":"NOT_PAIRED","message":"pairing required","details":{"code":"PAIRING_REQUIRED","requestId":"request-1"}}}""")
        gateway.socket!!.close(1008, "pairing required")
        awaitState { it == OpenClawConnectionState.WaitingForPairing }
        assertEquals("request-1", service.pairingRequestId)
        service.connect() // Background auto-connect must leave approval waiting alone.
        Thread.sleep(200)
        assertEquals(OpenClawConnectionState.WaitingForPairing, service.connectionState.value)
        assertEquals(1, gateway.opens)
        gateway.connectReply = ScriptedGateway.ConnectReply.OK
        service.connect(force = true)
        connected()
        assertEquals(2, gateway.opens)
    }

    @Test fun unrelatedPairingResponseAndPreHelloInvokesDoNotChangeStateOrRunCamera() {
        gateway.connectReply = ScriptedGateway.ConnectReply.SILENT
        connect()
        gateway.awaitMethod("connect")
        val invoked = CountDownLatch(1)
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                invoked.countDown()
                return OpenClawNodeInvokeResult.success(request.id, JsonObject())
            }
        })
        gateway.send("""{"type":"res","id":"unrelated","ok":false,"error":{"code":"NOT_PAIRED"}}""")
        gateway.send("""{"type":"event","event":"node.invoke.request","payload":{"id":"early","nodeId":"${identity.deviceId}","command":"camera.snap"}}""")
        assertFalse(invoked.await(250, TimeUnit.MILLISECONDS))
        assertEquals(OpenClawConnectionState.Connecting, service.connectionState.value)
    }

    @Test fun canonicalInvokeUsesParamsJSONAndIdentityNodeIdAndPayloadJSON() {
        connect()
        connected()
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                assertEquals(640, request.params!!.get("maxWidth").asInt)
                return OpenClawNodeInvokeResult.success(request.id, JsonObject().apply { addProperty("width", 640) })
            }
        })
        gateway.send("""{"type":"event","event":"node.invoke.request","payload":{"id":"snap","nodeId":"${identity.deviceId}","command":"camera.snap","paramsJSON":"{\"maxWidth\":640}","timeoutMs":5000}}""")
        val result = gateway.awaitMethod("node.invoke.result").getAsJsonObject("params")
        assertEquals("snap", result.get("id").asString)
        assertEquals(identity.deviceId, result.get("nodeId").asString)
        assertEquals("{\"width\":640}", result.get("payloadJSON").asString)
        assertNull(result.get("payloadjson"))
    }

    @Test fun staleInvocationCannotReplyOnReplacementSocket() {
        connect(upgrades = 2)
        connected()
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                entered.countDown()
                release.await()
                return OpenClawNodeInvokeResult.success(request.id, JsonObject())
            }
        })
        gateway.send("""{"type":"event","event":"node.invoke.request","payload":{"id":"old","nodeId":"${identity.deviceId}","command":"camera.snap"}}""")
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        service.disconnect()
        service.connect()
        connected()
        gateway.received.clear()
        release.complete(Unit)
        assertNull(gateway.received.poll(250, TimeUnit.MILLISECONDS))
    }

    @Test fun rejectedDeviceTokenNeverFallsBackToSharedSecret() {
        connect(upgrades = 3)
        connected()
        gateway.awaitMethod("connect")
        service.disconnect()
        gateway.connectReply = ScriptedGateway.ConnectReply.REJECT
        service.connect()
        val params = gateway.awaitMethod("connect").getAsJsonObject("params")
        assertTrue(params.getAsJsonObject("auth").has("deviceToken"))
        awaitState { it is OpenClawConnectionState.Error }
        Thread.sleep(200)
        assertEquals(2, gateway.opens)
    }

    @Test fun emptyChallengeFailsBeforeAnySignedConnectIsSent() {
        gateway.nonce = " "
        connect()
        awaitState { it is OpenClawConnectionState.Error }
        assertNull(gateway.received.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun repeatedChallengeCannotReplacePendingConnect() {
        gateway.connectReply = ScriptedGateway.ConnectReply.SILENT
        connect()
        val id = gateway.awaitMethod("connect").get("id").asString
        gateway.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"replacement"}}""")
        assertNull(gateway.received.poll(200, TimeUnit.MILLISECONDS))
        gateway.send("""{"type":"res","id":"$id","ok":true,"payload":{"type":"hello-ok","protocol":4,"features":{"methods":["node.event"]},"auth":{"role":"node","scopes":[]}}}""")
        connected()
    }

    @Test fun malformedHelloNeverCountsAsAuthenticated() {
        gateway.connectReply = ScriptedGateway.ConnectReply.SILENT
        connect()
        val id = gateway.awaitMethod("connect").get("id").asString
        gateway.send("""{"type":"res","id":"$id","ok":true,"payload":{"type":"hello-ok","protocol":4.5,"auth":{"role":"node","scopes":[]}}}""")
        awaitState { it is OpenClawConnectionState.Error }
    }

    @Test fun malformedAndMisaddressedInvokesCannotCaptureWithDefaultParams() {
        connect()
        connected()
        val invoked = CountDownLatch(1)
        service.setCommandRouter(object : OpenClawCommandHandler {
            override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
                invoked.countDown()
                return OpenClawNodeInvokeResult.success(request.id, JsonObject())
            }
        })
        gateway.send("""{"type":"event","event":"node.invoke.request","payload":{"id":"bad-json","nodeId":"${identity.deviceId}","command":"camera.snap","paramsJSON":"broken"}}""")
        gateway.send("""{"type":"event","event":"node.invoke.request","payload":{"id":"wrong-node","nodeId":"other-node","command":"camera.snap"}}""")
        assertFalse(invoked.await(200, TimeUnit.MILLISECONDS))
    }

    @Test fun startupSidecarsRetryIsBoundedAndKeepsCurrentProfile() {
        gateway.connectReply = ScriptedGateway.ConnectReply.SILENT
        connect(upgrades = 2)
        val id = gateway.awaitMethod("connect").get("id").asString
        gateway.send("""{"type":"res","id":"$id","ok":false,"error":{"code":"UNAVAILABLE","message":"starting","retryable":true,"retryAfterMs":250,"details":{"reason":"startup-sidecars"}}}""")
        awaitState { it is OpenClawConnectionState.Reconnecting }
        Thread.sleep(75)
        assertEquals(1, gateway.opens)
        gateway.connectReply = ScriptedGateway.ConnectReply.OK
        val retry = gateway.awaitMethod("connect").getAsJsonObject("params")
        assertEquals(4, retry.get("minProtocol").asInt)
        assertEquals("shared-token", retry.getAsJsonObject("auth").get("token").asString)
        connected()
    }

    @Test fun changedEndpointDoesNotReceiveAnotherGatewaysDeviceToken() {
        connect(upgrades = 2)
        connected()
        gateway.awaitMethod("connect")
        service.disconnect()
        store.host = "localhost"
        service.connect()
        val retry = gateway.awaitMethod("connect").getAsJsonObject("params")
        assertNull(retry.getAsJsonObject("auth").get("deviceToken"))
        assertEquals("shared-token", retry.getAsJsonObject("auth").get("token").asString)
        connected()
    }

    @Test fun explicitForgetAllowsFreshPairingWithConfiguredSharedToken() {
        connect(upgrades = 2)
        connected()
        gateway.awaitMethod("connect")
        service.disconnect()
        service.forgetPairedDeviceToken()
        service.connect()
        val retry = gateway.awaitMethod("connect").getAsJsonObject("params")
        assertFalse(retry.getAsJsonObject("auth").has("deviceToken"))
        assertEquals("shared-token", retry.getAsJsonObject("auth").get("token").asString)
        connected()
    }

    @Test fun currentChatSubscribesThenSendsNodeAgentRequestAndReceivesAppendReplaceAndFinal() {
        connect()
        connected()
        val subscription = gateway.awaitMethod("node.event").getAsJsonObject("params")
        assertEquals("chat.subscribe", subscription.get("event").asString)
        val session = JsonParser.parseString(subscription.get("payloadJSON").asString).asJsonObject.get("sessionKey").asString
        assertTrue(session.startsWith("agent:main:turbometa-chat-"))
        val events = LinkedBlockingQueue<OpenClawChatEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { events.add(it) } }
        try {
            runBlocking { withTimeout(2_000) { service.chatSubscriptionCount.first { it > 0 } } }
            assertTrue(service.sendChatMessage("what is this?", "/9j/AAAA"))
            assertTrue(service.isChatBusy.value)
            val frame = gateway.awaitMethod("node.event").getAsJsonObject("params")
            assertEquals("agent.request", frame.get("event").asString)
            val payload = com.google.gson.JsonParser.parseString(frame.get("payloadJSON").asString).asJsonObject
            assertEquals(session, payload.get("sessionKey").asString)
            assertEquals("what is this?", payload.get("message").asString)
            assertFalse(payload.get("deliver").asBoolean)
            assertFalse(payload.get("receipt").asBoolean)
            assertEquals("/9j/AAAA", payload.getAsJsonArray("attachments")[0].asJsonObject.get("content").asString)
            gateway.send("""{"type":"event","event":"chat","payload":{"runId":"run-1","sessionKey":"$session","seq":1,"state":"delta","deltaText":"Hel"}}""")
            gateway.send("""{"type":"event","event":"chat","payload":{"runId":"run-1","sessionKey":"$session","seq":2,"state":"delta","deltaText":"lo"}}""")
            gateway.send("""{"type":"event","event":"chat","payload":{"runId":"run-1","sessionKey":"$session","seq":3,"state":"delta","deltaText":"Corrected","replace":true}}""")
            gateway.send("""{"type":"event","event":"chat","payload":{"runId":"run-1","sessionKey":"$session","seq":4,"state":"final","message":{"role":"assistant","content":[{"type":"text","text":"Corrected!"}]}}}""")
            assertEquals(OpenClawChatEvent("Hel", false), events.poll(2, TimeUnit.SECONDS))
            assertEquals(OpenClawChatEvent("Hello", false), events.poll(2, TimeUnit.SECONDS))
            assertEquals(OpenClawChatEvent("Corrected", false), events.poll(2, TimeUnit.SECONDS))
            assertEquals(OpenClawChatEvent("Corrected!", true), events.poll(2, TimeUnit.SECONDS))
            assertFalse(service.isChatBusy.value)
        } finally { job.cancel() }
    }

    @Test fun rejectedChatSubscriptionLeavesCameraConnectedButAggregateStateShowsError() {
        gateway.nodeEventReply = ScriptedGateway.ConnectReply.REJECT
        connect()
        awaitState { it is OpenClawConnectionState.Error }
        assertEquals(OpenClawConnectionState.Connected, service.nodeConnectionState.value)
        assertFalse(service.sendChatMessage("not subscribed"))
        gateway.send("""{"type":"event","event":"node.invoke.request","payload":{"id":"camera-still-live","nodeId":"${identity.deviceId}","command":"camera.list","paramsJSON":null}}""")
        val result = gateway.awaitMethod("node.invoke.result").getAsJsonObject("params")
        assertEquals("camera-still-live", result.get("id").asString)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun timedOutTurnThenForceConnectCannotDeliverItsLateFinalAsTheNextReply() = runTest {
        gateway.start()
        store.port = gateway.server.port
        service = OpenClawNodeService(store, lazyOf(identity), OpenClawClientInfo("test", "Pixel", "rayban-test"), client, scope = this)
        service.connect()
        connected()
        val sessionA = eventSession(gateway.awaitMethod("node.event"))
        val events = LinkedBlockingQueue<OpenClawChatEvent>()
        val collector = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { events.add(it) } }
        try {
            runBlocking { withTimeout(2_000) { service.chatSubscriptionCount.first { it > 0 } } }
            assertTrue(service.sendChatMessage("A"))
            gateway.awaitMethod("node.event")
            runCurrent()
            advanceTimeBy(120_001)
            runCurrent()
            assertTrue(service.connectionState.value is OpenClawConnectionState.Error)
            assertEquals(OpenClawChatEvent("", true), events.poll(2, TimeUnit.SECONDS))

            service.connect(force = true)
            connected()
            val subscribeB = gateway.await { it.getAsJsonObject("params")?.get("event")?.asString == "chat.subscribe" }
            val sessionB = eventSession(subscribeB)
            assertNotEquals(sessionA, sessionB)
            assertEquals(1, gateway.opens) // The node/camera socket stayed connected.
            assertTrue(service.sendChatMessage("B"))
            assertEquals(sessionB, eventSession(gateway.awaitMethod("node.event")))
            // The release uses sessionId as runId, not the client request ID. A may finish very late.
            gateway.send("""{"type":"event","event":"chat","payload":{"sessionKey":"$sessionA","runId":"session-id-A","seq":1,"state":"final","message":{"content":[{"type":"text","text":"late A"}]}}}""")
            assertNull(events.poll(200, TimeUnit.MILLISECONDS))
            assertTrue(service.isChatBusy.value)
            gateway.send("""{"type":"event","event":"chat","payload":{"sessionKey":"$sessionB","runId":"session-id-B","seq":1,"state":"final","message":{"content":[{"type":"text","text":"B reply"}]}}}""")
            assertEquals(OpenClawChatEvent("B reply", true), events.poll(2, TimeUnit.SECONDS))
            assertFalse(service.isChatBusy.value)
            assertEquals(OpenClawConnectionState.Connected, service.connectionState.value)
        } finally {
            service.disconnect()
            collector.cancel()
        }
    }
}
