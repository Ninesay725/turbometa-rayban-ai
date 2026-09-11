package com.smartview.glassai.services.openclaw

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Paired-node chat via node.event/chat.subscribe + agent.request, verified in v2026.9.4
 * server-node-events.ts. No operator credential or second role is requested. One in-flight
 * turn: this ingress does not return a client-correlated run ID or durable acceptance receipt.
 */
internal class OpenClawNodeChat(
    private val lock: Any,
    private val scope: CoroutineScope,
    private val sessionKey: String,
    private val send: (JsonObject) -> Boolean,
    private val emit: (OpenClawChatEvent) -> Unit,
    private val onState: (OpenClawConnectionState) -> Unit,
    private val requestTimeoutMs: Long = 10_000L,
    private val replyTimeoutMs: Long = 120_000L,
    private val onBusy: (Boolean) -> Unit = {},
) {
    private var closed = false
    private var subscribed = false
    private var subscribeId: String? = null
    private var requestId: String? = null
    private var timeout: Job? = null
    private var awaitingReply = false
    private var runId: String? = null
    private var lastSeq = -1L
    private var text = ""
    private var subscriptionSessionKey = ""

    fun start() = synchronized(lock) {
        close()
        closed = false
        if (subscriptionSessionKey.isNotEmpty()) {
            send(nodeEvent(UUID.randomUUID().toString(), "chat.unsubscribe", JsonObject().apply {
                addProperty("sessionKey", subscriptionSessionKey)
            }))
        }
        // Released node ingress uses sessionId as runId. A fresh subscription must not receive
        // a timed-out/disconnected turn's late final as the answer to a new turn.
        subscriptionSessionKey = "$sessionKey-${UUID.randomUUID()}"
        onState(OpenClawConnectionState.Connecting)
        val id = UUID.randomUUID().toString()
        subscribeId = id
        armTimeout(requestTimeoutMs, "CHAT_SUBSCRIBE_TIMEOUT")
        if (!send(nodeEvent(id, "chat.subscribe", JsonObject().apply { addProperty("sessionKey", subscriptionSessionKey) }))) {
            fail("CHAT_SUBSCRIBE_SEND_FAILED")
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        subscribed = false
        subscribeId = null
        requestId = null
        timeout?.cancel()
        timeout = null
        awaitingReply = false
        onBusy(false)
        runId = null
        lastSeq = -1
        text = ""
    }

    fun sendMessage(message: String, imageJpegBase64: String?): Boolean = synchronized(lock) {
        if (closed || !subscribed || awaitingReply || message.trim().length > 20_000 ||
            (message.isBlank() && imageJpegBase64.isNullOrBlank())
        ) return@synchronized false
        val id = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            addProperty("sessionKey", subscriptionSessionKey)
            addProperty("message", message.trim())
            // Keep the reply on this subscribed node, never deliver it to a messaging channel.
            addProperty("deliver", false)
            addProperty("receipt", false)
            if (imageJpegBase64 != null) add("attachments", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "image")
                    addProperty("mimeType", "image/jpeg")
                    addProperty("content", imageJpegBase64)
                })
            })
        }
        requestId = id
        awaitingReply = true
        onBusy(true)
        runId = null
        lastSeq = -1
        text = ""
        armTimeout(replyTimeoutMs, "CHAT_REPLY_TIMEOUT: no terminal reply received")
        if (!send(nodeEvent(id, "agent.request", payload))) {
            fail("CHAT_SEND_FAILED")
            return@synchronized false
        }
        true
    }

    fun handleResponse(frame: JsonObject): Boolean = synchronized(lock) {
        val id = frame.string("id") ?: return@synchronized false
        if (closed || (id != subscribeId && id != requestId)) return@synchronized false
        val payload = frame.obj("payload")
        if (frame.boolean("ok") != true || payload?.boolean("ok") == false || payload?.boolean("handled") == false) {
            val error = frame.obj("error")
            fail("${error?.string("code") ?: "CHAT_REJECTED"}: ${error?.string("message") ?: payload?.string("reason") ?: "node event rejected"}")
        } else if (id == subscribeId) {
            subscribeId = null
            subscribed = true
            timeout?.cancel()
            timeout = null
            onState(OpenClawConnectionState.Connected)
        } else {
            // An RPC acknowledgement means ingress accepted the event, not that inference finished.
            requestId = null
        }
        true
    }

    fun handleChat(payload: JsonObject) = synchronized(lock) {
        if (closed || !subscribed || !awaitingReply || payload.string("sessionKey") != subscriptionSessionKey) return@synchronized
        val state = payload.string("state")
        if (state !in listOf("delta", "final", "aborted", "error")) return@synchronized
        val incomingRun = payload.string("runId") ?: return@synchronized
        val seq = payload.get("seq")?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrNull() } ?: return@synchronized
        if (seq < 0 || (runId != null && runId != incomingRun) || seq < lastSeq || (seq == lastSeq && state == "delta")) return@synchronized
        runId = incomingRun
        lastSeq = seq
        val content = payload.obj("message")?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
        val snapshot = content?.mapNotNull {
            it.takeIf { value -> value.isJsonObject }?.asJsonObject?.takeIf { part -> part.string("type") == "text" }?.string("text")
        }?.joinToString("")
        if (state == "delta") {
            val delta = payload.string("deltaText")
            text = if (delta != null) {
                if (payload.boolean("replace") == true) delta else text + delta
            } else snapshot ?: return@synchronized // Official v3 snapshot-style deltas.
            if (text.length > 500_000) { fail("CHAT_REPLY_TOO_LARGE"); return@synchronized }
            emit(OpenClawChatEvent(text, false))
        } else if (state == "error" || state == "aborted") {
            fail(payload.string("errorMessage") ?: "CHAT_${state.uppercase()}")
        } else {
            awaitingReply = false
            onBusy(false)
            requestId = null
            timeout?.cancel()
            timeout = null
            // A missing final message is an intentionally silent/tool-only reply, not old text.
            emit(OpenClawChatEvent(snapshot ?: "", true))
            text = ""
        }
    }

    private fun fail(detail: String) {
        timeout?.cancel()
        timeout = null
        subscribeId = null
        requestId = null
        subscribed = false
        if (awaitingReply) emit(OpenClawChatEvent("", true))
        awaitingReply = false
        onBusy(false)
        text = ""
        onState(OpenClawConnectionState.Error(OpenClawErrorReason.Transport(detail)))
    }

    private fun armTimeout(milliseconds: Long, detail: String) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(milliseconds)
            synchronized(lock) {
                // Cancellation while this continuation waits for the JVM monitor must still win.
                coroutineContext.ensureActive()
                if (!closed) fail(detail)
            }
        }
    }

    private fun nodeEvent(id: String, event: String, payload: JsonObject) = JsonObject().apply {
        addProperty("type", "req")
        addProperty("id", id)
        addProperty("method", "node.event")
        add("params", JsonObject().apply {
            addProperty("event", event)
            addProperty("payloadJSON", payload.toString())
        })
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.boolean(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
