package com.smartview.glassai.services.openclaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * A MockWebServer-hosted stand-in for the OpenClaw Gateway. Every accepted socket sends a
 * connect.challenge on open and answers the `connect` request per [connectReply]. Every frame the
 * app sends is parsed and queued in [received]; the latest server-side socket is in [socket].
 */
class ScriptedGateway(val nonce: String = "nonce-1") {
    enum class ConnectReply { OK, NOT_PAIRED, REJECT, SILENT }

    val server = MockWebServer()
    val received = LinkedBlockingQueue<JsonObject>()
    @Volatile var socket: WebSocket? = null
    @Volatile var connectReply: ConnectReply = ConnectReply.OK
    /** Error code answered for [ConnectReply.REJECT] (anything but NOT_PAIRED). */
    @Volatile var rejectCode: String = "UNAUTHORIZED"
    @Volatile var rejectMessage: String = "bad token"
    @Volatile var opens = 0
    /** Client-initiated closes seen by the server (onClosing). */
    @Volatile var closes = 0

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opens++
            webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"$nonce"}}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closes++
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JsonParser.parseString(text).asJsonObject
            received.add(json)
            if (json.get("type")?.asString == "req" && json.get("method")?.asString == "connect") {
                val id = json.get("id").asString
                when (connectReply) {
                    ConnectReply.OK -> webSocket.send("""{"type":"res","id":"$id","ok":true,"payload":{"protocol":3}}""")
                    ConnectReply.NOT_PAIRED -> webSocket.send(
                        """{"type":"res","id":"$id","ok":false,"error":{"code":"NOT_PAIRED","message":"device not paired"}}"""
                    )
                    ConnectReply.REJECT -> webSocket.send(
                        """{"type":"res","id":"$id","ok":false,"error":{"code":"$rejectCode","message":"$rejectMessage"}}"""
                    )
                    ConnectReply.SILENT -> Unit
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())
    }

    /** Queues [count] WebSocket upgrades (one per expected connection attempt). */
    fun start(count: Int = 1) {
        repeat(count) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
    }

    fun enqueueUpgrade() {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    fun send(text: String) {
        checkNotNull(socket) { "no client connected" }.send(text)
    }

    /** Same JSON, but as a binary WebSocket frame (the app must decode it as UTF-8, like iOS). */
    fun sendBinary(text: String) {
        checkNotNull(socket) { "no client connected" }.send(text.encodeUtf8())
    }

    /** Waits for the next frame whose `method` (req) or `type` matches [predicate]. */
    fun await(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            check(remaining > 0) { "timed out waiting for a matching frame" }
            val next = received.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(next)) return next
        }
    }

    fun awaitMethod(method: String, timeoutMs: Long = 5_000): JsonObject =
        await(timeoutMs) { it.get("type")?.asString == "req" && it.get("method")?.asString == method }

    fun stop() {
        server.shutdown()
    }
}
