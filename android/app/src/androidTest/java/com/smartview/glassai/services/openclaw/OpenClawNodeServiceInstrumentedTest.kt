package com.smartview.glassai.services.openclaw

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.smartview.glassai.glasses.FrameConversions
import com.smartview.glassai.glasses.GlassesPhotoCapturer
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionFrameProvider
import com.smartview.glassai.glasses.WearablesRegistrationGateway
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class OpenClawNodeServiceInstrumentedTest {

    companion object {
        private const val TAG = "OpenClawNodeServiceIT"
        private const val TIMEOUT_MS = 30_000L
    }

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<JsonObject>()
    @Volatile private var socket: WebSocket? = null
    private lateinit var device: MockGlasses
    private lateinit var store: SecureOpenClawSettingsStore
    private lateinit var service: OpenClawNodeService
    private lateinit var manager: GlassesSessionManager

    // The real settings stored on this emulator are restored in tearDown()
    private var savedHost = ""
    private var savedPort = 0
    private var savedScheme = ""
    private var savedToken: String? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"it-nonce"}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JsonParser.parseString(text).asJsonObject
            received.add(json)
            if (json.get("type")?.asString == "req" && json.get("method")?.asString == "connect") {
                webSocket.send("""{"type":"res","id":"${json.get("id").asString}","ok":true,"payload":{"protocol":3}}""")
            }
        }
    }

    @Before
    fun setUp() {
        grantPermissions()
        val kit = MockDeviceKit.getInstance(targetContext)
        kit.enable()
        device = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        device.powerOn()
        device.don()
        device.unfold()
        device.services.camera.setCameraFeed(assetUri("plant.mp4"))
        device.services.camera.setCapturedImage(assetUri("plant.png"))
        manager = GlassesSessionManager.getInstance(targetContext)

        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()

        store = SecureOpenClawSettingsStore(targetContext)
        savedHost = store.host
        savedPort = store.port
        savedScheme = store.scheme
        savedToken = store.loadToken()
        store.host = "127.0.0.1"
        store.port = server.port
        store.scheme = "ws"
        store.saveToken("it-token")

        val registration = WearablesRegistrationGateway(targetContext)
        val config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)
        // Same wiring as SessionFrameProvider.create(), minus the foreground gate (no Activity is
        // started under instrumentation, and the gate is covered by SessionFrameProviderTest).
        val frames = SessionFrameProvider(
            sessionManager = { manager },
            isForeground = { true },
            checkPermission = { registration.checkCameraPermission() },
            encode = SessionFrameProvider.Companion::encodeBitmap,
            capture = { m ->
                val capturer = GlassesPhotoCapturer(
                    sessionManager = m,
                    owner = SessionFrameProvider.OWNER,
                    config = config,
                    decodePhoto = FrameConversions::decodePhoto,
                    decodeFrame = { FrameConversions.frameToBitmap(it, FrameConversions.CAPTURE_JPEG_QUALITY) },
                )
                withContext(Dispatchers.Main.immediate) { capturer.capture() }
            },
        )
        service = OpenClawNodeService(
            store = store,
            identity = lazy { OpenClawDeviceIdentityStore.loadOrCreate(store) },
            clientInfo = OpenClawClientInfo("it", "emulator", OpenClawNodeService.nodeIdFor(targetContext)),
            httpClient = OpenClawNodeService.lanHttpClient(),
            tickIntervalMs = 60_000L,
        )
        service.setCommandRouter(OpenClawCommandRouter(frames, OpenClawDeviceInfoSource.fromBuild()))
    }

    @After
    fun tearDown() {
        service.disconnect()
        runCatching { server.shutdown() }
        store.host = savedHost
        store.port = savedPort
        store.scheme = savedScheme
        store.saveToken(savedToken)
        runBlocking(Dispatchers.Main) {
            manager.release(SessionFrameProvider.OWNER)
            manager.stopSession()
            withTimeoutOrNull(20_000L) { manager.sessionState.first { it == com.meta.wearable.dat.core.session.DeviceSessionState.STOPPED } }
            manager.resetForTests()
        }
        val kit = MockDeviceKit.getInstance(targetContext)
        runCatching { kit.unpairDevice(device) }.onFailure { Log.w(TAG, "unpairDevice failed", it) }
        kit.disable()
        runBlocking(Dispatchers.Main) {
            withTimeoutOrNull(10_000L) { manager.activeDevice.first { it == null } }
        }
    }

    @Test
    fun handshakeOverCleartextWsReachesConnectedAndDeliversChat() {
        service.connect()
        awaitState { it == OpenClawConnectionState.Connected }
        val connect = awaitFrame { it.get("method")?.asString == "connect" }
        val params = connect.getAsJsonObject("params")
        assertEquals("openclaw-android", params.getAsJsonObject("client").get("id").asString)
        assertEquals("it-token", params.getAsJsonObject("auth").get("token").asString)
        assertEquals(64, params.getAsJsonObject("device").get("id").asString.length)
        assertEquals("/?token=it-token", server.takeRequest(5, TimeUnit.SECONDS)!!.path)

        val events = LinkedBlockingQueue<OpenClawChatEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { service.chatEvents.collect { events.add(it) } }
        Thread.sleep(200)
        socket!!.send("""{"type":"event","event":"chat","payload":{"state":"final","message":{"role":"assistant","content":[{"type":"text","text":"hi from the gateway"}]}}}""")
        assertEquals(OpenClawChatEvent("hi from the gateway", true), events.poll(5, TimeUnit.SECONDS))
        job.cancel()
    }

    @Test
    fun nodeInvokeCameraSnapReturnsAJpegFromTheMockGlasses() {
        runBlocking(Dispatchers.Main) { withTimeout(10_000L) { manager.activeDevice.first { it != null } } }
        service.connect()
        awaitState { it == OpenClawConnectionState.Connected }
        awaitFrame { it.get("method")?.asString == "connect" }

        socket!!.send("""{"type":"req","id":"snap-1","method":"node.invoke","params":{"command":"camera.snap","params":{"maxWidth":640,"quality":0.8},"timeoutMs":30000}}""")

        val result = awaitFrame(TIMEOUT_MS) { it.get("method")?.asString == "node.invoke.result" }
        val params = result.getAsJsonObject("params")
        assertEquals("snap-1", params.get("id").asString)
        assertTrue("error: ${params.get("error")}", params.get("ok").asBoolean)
        assertEquals(service.nodeId, params.get("nodeId").asString)
        val payload = JsonParser.parseString(params.get("payloadjson").asString).asJsonObject
        assertEquals("jpg", payload.get("format").asString)
        assertTrue(payload.get("width").asInt in 1..640)
        val jpeg = Base64.getDecoder().decode(payload.get("base64").asString)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        // the snap borrowed and returned the camera: nothing is left held
        runBlocking(Dispatchers.Main) {
            assertNull(manager.currentCameraOwner)
            assertEquals(0, manager.ownerCount)
        }
    }

    // ---- helpers ----

    private fun awaitState(predicate: (OpenClawConnectionState) -> Boolean): OpenClawConnectionState =
        runBlocking { withTimeout(TIMEOUT_MS) { service.connectionState.first(predicate) } }

    private fun awaitFrame(timeoutMs: Long = 10_000L, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            check(remaining > 0) { "timed out waiting for a matching frame" }
            val next = received.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(next)) return next
        }
    }

    private fun grantPermissions() {
        listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_CONNECT", "android.permission.CAMERA")
            .forEach { permission ->
                runCatching {
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommand("pm grant ${targetContext.packageName} $permission").close()
                }
            }
    }

    private fun assetUri(assetName: String): Uri {
        val outFile = File(targetContext.cacheDir, assetName)
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile)
    }
}
