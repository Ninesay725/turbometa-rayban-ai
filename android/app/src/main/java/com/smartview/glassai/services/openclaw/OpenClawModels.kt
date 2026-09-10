package com.smartview.glassai.services.openclaw

import android.graphics.Bitmap
import com.google.gson.JsonObject
import java.util.UUID

/** Wire-protocol constants. Field names and values must stay identical to iOS (research §8.6). */
object OpenClawProtocol {
    const val PROTOCOL_VERSION = 3
    const val CLIENT_ID = "openclaw-android"
    const val CLIENT_MODE = "node"
    const val PLATFORM = "android"
    const val DISPLAY_NAME = "Ray-Ban Meta Glasses"
    const val ROLE = "operator"
    val SCOPES: List<String> = listOf("operator.read", "operator.write")
    val CAPS: List<String> = listOf("camera")
    val COMMANDS: List<String> = listOf("camera.snap", "camera.list", "device.status", "device.info")
    const val SESSION_KEY = "turbometa-chat"

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 18789
    const val SCHEME_WS = "ws"
    const val SCHEME_WSS = "wss"

    // Error codes
    const val ERROR_NOT_PAIRED = "NOT_PAIRED"
    const val ERROR_UNSUPPORTED = "UNSUPPORTED"
    const val ERROR_NO_ROUTER = "NO_ROUTER"
    const val ERROR_UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
    const val ERROR_NOT_READY = "NOT_READY"
    const val ERROR_STREAM_FAILED = "STREAM_FAILED"
    const val ERROR_NO_FRAME = "NO_FRAME"
    const val ERROR_ENCODE_FAILED = "ENCODE_FAILED"
    const val ERROR_PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val ERROR_TIMEOUT = "TIMEOUT"
    const val ERROR_INTERNAL = "INTERNAL"
}

/** Why the connection is in the Error state; the UI maps each reason to a localized string. */
sealed class OpenClawErrorReason {
    data class MaxRetries(val attempts: Int) : OpenClawErrorReason()
    data class Transport(val detail: String) : OpenClawErrorReason()
    object InvalidUrl : OpenClawErrorReason()
}

sealed class OpenClawConnectionState {
    object Disconnected : OpenClawConnectionState()
    object Connecting : OpenClawConnectionState()
    /**
     * The socket dropped and the backoff timer for [attempt] (1-based) is running. Distinct from
     * [Disconnected] so the Home/chat auto-connect does not defeat the 2/4/8/16/30 s sequence by
     * dialing immediately; the explicit Connect button uses connect(force = true).
     */
    data class Reconnecting(val attempt: Int) : OpenClawConnectionState()
    /** The gateway answered NOT_PAIRED: run `openclaw devices approve` on the gateway host. */
    object WaitingForPairing : OpenClawConnectionState()
    object Connected : OpenClawConnectionState()
    data class Error(val reason: OpenClawErrorReason) : OpenClawConnectionState()
}

/** One `chat` event: [isFinal] replaces the iOS "[[FINAL]]" prefix. Non-final text is replace-style. */
data class OpenClawChatEvent(val text: String, val isFinal: Boolean)

data class OpenClawNodeInvokeRequest(
    val id: String,
    val command: String,
    val params: JsonObject?,
    val timeoutMs: Long?,
)

data class OpenClawError(val code: String?, val message: String?)

/** Router result; the service adds `nodeId` when it builds `node.invoke.result`. */
data class OpenClawNodeInvokeResult(
    val id: String,
    val ok: Boolean,
    val payload: JsonObject?,
    val error: OpenClawError?,
) {
    companion object {
        fun success(id: String, payload: JsonObject) = OpenClawNodeInvokeResult(id, true, payload, null)
        fun failure(id: String, code: String, message: String) =
            OpenClawNodeInvokeResult(id, false, null, OpenClawError(code, message))
    }
}

/** `camera.snap` parameters with the iOS defaults; `format` is parsed but always JPEG. */
data class CameraSnapParams(
    val maxWidth: Int = 1600,
    val quality: Double = 0.8,
    val format: String = "jpg",
) {
    companion object {
        fun from(json: JsonObject?): CameraSnapParams {
            if (json == null) return CameraSnapParams()
            val maxWidth = runCatching { json.get("maxWidth")?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()
            val quality = runCatching { json.get("quality")?.takeIf { it.isJsonPrimitive }?.asDouble }.getOrNull()
            val format = runCatching { json.get("format")?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
            return CameraSnapParams(
                maxWidth = maxWidth ?: 1600,
                quality = quality ?: 0.8,
                format = format ?: "jpg",
            )
        }
    }
}

/** Chat bubble (in memory only, spec §3 decision 3). */
data class OpenClawChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant"
    val text: String,
    val image: Bitmap? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** Build-time facts the service puts into `connect.client` and `node.invoke.result.nodeId`. */
data class OpenClawClientInfo(
    val version: String,
    val modelIdentifier: String,
    /** "rayban-" + first 8 hex chars of ANDROID_ID, lowercase. */
    val nodeId: String,
)
