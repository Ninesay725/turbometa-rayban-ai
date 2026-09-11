package com.smartview.glassai.services.openclaw

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.BuildConfig
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * OpenClaw Gateway client (research §2). One WebSocket, JSON text frames, three envelopes
 * (req / res / event). The app is an *operator* (chat.send, chat events) and a *node*
 * (node.invoke → OpenClawCommandHandler → node.invoke.result).
 *
 * Differences from iOS that are deliberate (research §10): only the `connect` response (matched
 * by id) counts as "hello ok"; emptying the token deletes it; the token is percent-encoded; a close
 * and a failure for the same socket are counted once; the backoff wait is its own state
 * ([OpenClawConnectionState.Reconnecting]) so auto-connect cannot defeat it; `openclaw_enabled`
 * does not exist.
 *
 * Threading: OkHttp callbacks arrive on OkHttp threads; every mutable field is guarded by [lock];
 * flows are thread-safe. Nothing here touches Android UI classes so the class is JVM-testable.
 *
 * [identity] is a Lazy so the singleton can be built in Application.onCreate without reading or
 * generating the Ed25519 seed (Keystore/EncryptedSharedPreferences I/O) until the first connect().
 */
class OpenClawNodeService(
    private val store: OpenClawSettingsStore,
    private val identity: Lazy<OpenClawDeviceIdentity>,
    private val clientInfo: OpenClawClientInfo,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val reconnectDelaysMs: List<Long> = RECONNECT_DELAYS_MS,
    private val maxReconnectAttempts: Int = MAX_RECONNECT_ATTEMPTS,
    private val invokeTimeoutMs: Long = DEFAULT_INVOKE_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "OpenClawNodeService"
        const val TICK_INTERVAL_MS = 15_000L
        val RECONNECT_DELAYS_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val DEFAULT_INVOKE_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_S = 10L

        /**
         * WebSocket keepalive. The app-level `tick` is fire-and-forget and nothing checks for a
         * reply, so a half-open socket (the phone roams off Wi-Fi) kept reporting "Connected" for
         * minutes while node.invoke requests vanished into the kernel buffer. OkHttp fails the
         * socket when a pong is missing, which lands in onFailure -> the existing backoff.
         */
        private const val PING_INTERVAL_S = 20L

        @Volatile
        private var instance: OpenClawNodeService? = null

        /**
         * Process singleton (like APIKeyManager). Cheap to build: the settings store opens
         * EncryptedSharedPreferences lazily and the Ed25519 identity is loaded/generated on the
         * first connect(), so calling this from Application.onCreate does no I/O.
         */
        fun getInstance(context: Context): OpenClawNodeService =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val store = SecureOpenClawSettingsStore(appContext)
                    OpenClawNodeService(
                        store = store,
                        identity = lazy { OpenClawDeviceIdentityStore.loadOrCreate(store) },
                        clientInfo = OpenClawClientInfo(
                            version = BuildConfig.VERSION_NAME,
                            modelIdentifier = Build.MODEL ?: "android",
                            nodeId = nodeIdFor(appContext),
                        ),
                        httpClient = lanHttpClient(),
                    ).also { instance = it }
                }
            }

        /** "rayban-" + first 8 chars of ANDROID_ID, lowercase (iOS: identifierForVendor). */
        fun nodeIdFor(context: Context): String {
            val androidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "00000000"
            return "rayban-" + androidId.take(8).lowercase()
        }

        /**
         * LAN gateway client: no system proxy, 10 s connect, no read timeout (long-lived socket),
         * 20 s ping keepalive (see [PING_INTERVAL_S]).
         */
        fun lanHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val lock = Any()

    private var webSocket: WebSocket? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var tickJob: Job? = null
    private var reconnectJob: Job? = null
    private var pendingConnectId: String? = null

    @Volatile
    private var router: OpenClawCommandHandler? = null

    /** Resolved on first use (the first connect.challenge). */
    private val deviceIdentity: OpenClawDeviceIdentity
        get() = identity.value

    /** Test hook: whether the 15 s tick loop is alive. */
    @VisibleForTesting
    internal val isTickRunning: Boolean
        get() = synchronized(lock) { tickJob?.isActive == true }

    private val _connectionState = MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.Disconnected)
    val connectionState: StateFlow<OpenClawConnectionState> = _connectionState.asStateFlow()

    private val _chatEvents = MutableSharedFlow<OpenClawChatEvent>(extraBufferCapacity = 64)
    val chatEvents: SharedFlow<OpenClawChatEvent> = _chatEvents.asSharedFlow()

    /**
     * Test hook: how many collectors [chatEvents] currently has. `chatEvents` has replay 0, so a
     * test that sends an event before its collector is subscribed loses it. Instrumented tests wait
     * on this instead of sleeping for a fixed time.
     */
    @VisibleForTesting
    internal val chatSubscriptionCount: StateFlow<Int>
        get() = _chatEvents.subscriptionCount

    val nodeId: String
        get() = clientInfo.nodeId

    var gatewayHost: String
        get() = store.host
        set(value) { store.host = value.trim() }

    var gatewayPort: Int
        get() = store.port
        set(value) { store.port = value }

    /** "ws" (default) or "wss". */
    var gatewayScheme: String
        get() = store.scheme
        set(value) { store.scheme = if (value == OpenClawProtocol.SCHEME_WSS) OpenClawProtocol.SCHEME_WSS else OpenClawProtocol.SCHEME_WS }

    fun loadGatewayToken(): String? = store.loadToken()

    fun saveGatewayToken(token: String?) = store.saveToken(token?.trim())

    fun setCommandRouter(router: OpenClawCommandHandler?) {
        this.router = router
    }

    // ---- lifecycle ----

    /**
     * @param force true from the Settings "Connect to Gateway" button: cancels a running backoff
     *   and dials immediately. false (default) from the Home/chat auto-connect: a backoff in
     *   progress is left alone so the 2/4/8/16/30 s sequence and the 5-attempt cap stay intact.
     */
    fun connect(force: Boolean = false) {
        synchronized(lock) {
            val state = _connectionState.value
            if (state == OpenClawConnectionState.Connected || state == OpenClawConnectionState.Connecting) {
                Log.d(TAG, "connect(): already $state")
                return
            }
            if (state is OpenClawConnectionState.Reconnecting && !force && reconnectJob?.isActive == true) {
                Log.d(TAG, "connect(): backoff for attempt ${state.attempt} in progress")
                return
            }
            shouldReconnect = true
            reconnectAttempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
            startConnection()
        }
    }

    fun disconnect() {
        synchronized(lock) {
            shouldReconnect = false
            reconnectJob?.cancel()
            reconnectJob = null
            tickJob?.cancel()
            tickJob = null
            pendingConnectId = null
            val socket = webSocket
            webSocket = null
            runCatching { socket?.close(1000, "User disconnected") }
            _connectionState.value = OpenClawConnectionState.Disconnected
        }
    }

    /**
     * Must be called with [lock] held.
     *
     * Invariant: `tickJob == null` on entry. The tick is only ever started by [handleHelloOk] and
     * is cancelled on every path that ends a connection ([handleDisconnect], [disconnect],
     * [failWithTransport]), so a dial can never leave an orphaned tick loop behind.
     */
    private fun startConnection() {
        // A socket left open by a NOT_PAIRED wait (or any stale one) is closed before dialing
        // again, so the gateway never sees two connections from this device. Its later callbacks
        // are ignored by handleDisconnect (socket !== webSocket).
        webSocket?.let { old ->
            webSocket = null
            runCatching { old.close(1000, "reconnect") }
        }
        val url = buildUrl()
        // Request.Builder().url() throws IllegalArgumentException for anything OkHttp cannot parse;
        // that must become the InvalidUrl state, never an exception in a Compose click handler.
        val request = url?.let { runCatching { Request.Builder().url(it).build() }.getOrNull() }
        if (request == null) {
            Log.e(TAG, "invalid gateway address: '${store.host}:${store.port}'")
            shouldReconnect = false
            _connectionState.value = OpenClawConnectionState.Error(OpenClawErrorReason.InvalidUrl)
            return
        }
        Log.d(TAG, "connecting to ${store.scheme}://${store.host}:${store.port}")
        _connectionState.value = OpenClawConnectionState.Connecting
        pendingConnectId = null
        webSocket = httpClient.newWebSocket(request, Listener())
    }

    /**
     * `ws(s)://host:port/` plus `?token=<percent-encoded>` when a token is stored; null if the
     * address is malformed. Built through OkHttp's HttpUrl so the token is RFC 3986 percent-encoded
     * (a space is `%20`, not `+` — java.net.URLEncoder is form encoding) and IPv6 literals are
     * validated; a bare `::1` is bracketed for the user. Uri.encode is unavailable on the JVM.
     */
    @VisibleForTesting
    internal fun buildUrl(): String? {
        val rawHost = store.host.trim()
        val port = store.port
        if (rawHost.isEmpty() || rawHost.any { it.isWhitespace() } || port !in 1..65535) return null
        val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
        val httpScheme = if (store.scheme == OpenClawProtocol.SCHEME_WSS) "https" else "http"
        val base = "$httpScheme://$host:$port/".toHttpUrlOrNull() ?: return null
        val token = store.loadToken()?.takeIf { it.isNotBlank() }
        val url = if (token != null) base.newBuilder().addQueryParameter("token", token).build() else base
        // OkHttp accepts ws/wss request URLs and maps them back to http/https internally.
        return url.toString().replaceFirst("http", "ws")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "socket open; waiting for connect.challenge")
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(webSocket, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(webSocket, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "socket closed: $code $reason")
            handleDisconnect(webSocket, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "socket failure: ${t.message}")
            handleDisconnect(webSocket, t)
        }
    }

    private fun handleDisconnect(socket: WebSocket, error: Throwable?) {
        synchronized(lock) {
            // A closed and a failed callback for the same socket, or callbacks from a socket we
            // already replaced, must not be counted twice.
            if (socket !== webSocket) return
            webSocket = null
            tickJob?.cancel()
            tickJob = null
            pendingConnectId = null

            if (!shouldReconnect) {
                _connectionState.value = OpenClawConnectionState.Disconnected
                return
            }
            reconnectAttempts++
            if (reconnectAttempts > maxReconnectAttempts) {
                Log.e(TAG, "giving up after $maxReconnectAttempts reconnect attempts")
                shouldReconnect = false
                _connectionState.value = OpenClawConnectionState.Error(OpenClawErrorReason.MaxRetries(maxReconnectAttempts))
                return
            }
            val delayMs = reconnectDelaysMs[minOf(reconnectAttempts - 1, reconnectDelaysMs.size - 1)]
            Log.w(TAG, "reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$maxReconnectAttempts)" +
                (error?.let { ", cause: ${it.message}" } ?: ""))
            _connectionState.value = OpenClawConnectionState.Reconnecting(reconnectAttempts)
            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(lock) {
                    if (shouldReconnect && webSocket == null) startConnection()
                }
            }
        }
    }

    // ---- inbound ----

    private fun handleMessage(socket: WebSocket, text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "ignoring non-JSON frame: ${e.message}")
            return
        }
        val type = json.string("type") ?: return
        when {
            type == "event" && json.string("event") == "connect.challenge" -> {
                val nonce = json.obj("payload")?.string("nonce") ?: ""
                sendConnect(socket, nonce)
            }
            type == "res" -> handleResponse(json)
            type == "evt" || type == "event" -> handleEvent(json.string("event") ?: json.string("method") ?: "", json)
            type == "req" || type == "request" -> handleRequest(socket, json)
            else -> Log.d(TAG, "unknown message type: $type")
        }
    }

    private fun sendConnect(socket: WebSocket, nonce: String) {
        val id = UUID.randomUUID().toString()
        val signedAt = clock()
        val token = store.loadToken()?.takeIf { it.isNotBlank() }
        // Runs on the OkHttp reader thread and touches the Keystore / EncryptedSharedPreferences on
        // first use. A throw here used to escape into OkHttp, which reported it as a socket failure:
        // five reconnects and a misleading "Connection failed after 5 retries" (final review I3).
        val signed = runCatching {
            val signer = deviceIdentity // first use loads or generates the seed
            signer to signer.signConnect(
                clientId = OpenClawProtocol.CLIENT_ID,
                clientMode = OpenClawProtocol.CLIENT_MODE,
                role = OpenClawProtocol.ROLE,
                scopes = OpenClawProtocol.SCOPES,
                signedAtMs = signedAt,
                token = token,
                nonce = nonce,
                platform = OpenClawProtocol.PLATFORM,
                deviceFamily = null,
            )
        }.getOrElse { error ->
            Log.e(TAG, "device identity unavailable", error)
            synchronized(lock) {
                failWithTransport("IDENTITY: ${error.message ?: error.javaClass.simpleName}")
            }
            return
        }
        val signer = signed.first
        val signature = signed.second
        val params = JsonObject().apply {
            addProperty("minProtocol", OpenClawProtocol.PROTOCOL_VERSION)
            addProperty("maxProtocol", OpenClawProtocol.PROTOCOL_VERSION)
            add("client", JsonObject().apply {
                addProperty("id", OpenClawProtocol.CLIENT_ID)
                addProperty("displayName", OpenClawProtocol.DISPLAY_NAME)
                addProperty("version", clientInfo.version)
                addProperty("mode", OpenClawProtocol.CLIENT_MODE)
                addProperty("platform", OpenClawProtocol.PLATFORM)
                addProperty("modelIdentifier", clientInfo.modelIdentifier)
            })
            addProperty("role", OpenClawProtocol.ROLE)
            add("scopes", jsonArray(OpenClawProtocol.SCOPES))
            add("caps", jsonArray(OpenClawProtocol.CAPS))
            add("commands", jsonArray(OpenClawProtocol.COMMANDS))
            add("auth", JsonObject().apply { if (token != null) addProperty("token", token) })
            add("device", JsonObject().apply {
                addProperty("id", signer.deviceId)
                addProperty("publicKey", signer.publicKeyBase64Url)
                addProperty("signature", signature)
                addProperty("signedAt", signedAt)
                addProperty("nonce", nonce)
            })
        }
        synchronized(lock) { pendingConnectId = id }
        send(socket, request(id, "connect", params))
    }

    private fun handleResponse(json: JsonObject) {
        val id = json.string("id")
        val ok = json.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (ok) {
            val isHello = synchronized(lock) { id != null && id == pendingConnectId }
            if (isHello) handleHelloOk() else Log.d(TAG, "ok response for $id")
            return
        }
        val error = json.obj("error")
        val code = error?.string("code")
        val message = error?.string("message")
        Log.w(TAG, "error response for $id: $code $message")
        if (code == OpenClawProtocol.ERROR_NOT_PAIRED) {
            synchronized(lock) { _connectionState.value = OpenClawConnectionState.WaitingForPairing }
            return
        }
        synchronized(lock) {
            // A gateway that rejects the handshake (auth/protocol) keeps the socket open, so no
            // close/failure callback ever arrives and nothing retries: the UI used to stay
            // "Connecting..." forever (final review I3). Retrying would not help either — the token
            // or the protocol version is wrong — so the backoff is stopped as well.
            if (id != null && id == pendingConnectId) {
                failWithTransport("${code ?: "?"}: ${message ?: ""}")
            }
        }
    }

    /**
     * Ends the connection with Error(Transport) and stops reconnecting. Used for the two failures
     * that retrying cannot fix: a rejected `connect` and an unavailable device identity.
     * Must be called with [lock] held.
     */
    private fun failWithTransport(detail: String) {
        Log.e(TAG, "transport error: $detail")
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        tickJob?.cancel()
        tickJob = null
        pendingConnectId = null
        val socket = webSocket
        webSocket = null
        runCatching { socket?.close(1000, "connect rejected") }
        _connectionState.value = OpenClawConnectionState.Error(OpenClawErrorReason.Transport(detail))
    }

    private fun handleHelloOk() {
        synchronized(lock) {
            Log.d(TAG, "connected to gateway")
            reconnectAttempts = 0
            pendingConnectId = null
            _connectionState.value = OpenClawConnectionState.Connected
            startTick()
        }
    }

    /** Must be called with [lock] held. */
    private fun startTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMs)
                val params = JsonObject().apply { addProperty("ts", clock()) }
                sendJson(request(UUID.randomUUID().toString(), "tick", params))
            }
        }
    }

    private fun handleEvent(name: String, json: JsonObject) {
        when (name) {
            "chat" -> {
                val payload = json.obj("payload") ?: return
                val state = payload.string("state")
                val content = payload.obj("message")?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                val text = content?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("text") }
                    ?.joinToString("") ?: ""
                _chatEvents.tryEmit(OpenClawChatEvent(text, isFinal = state == "final"))
            }
            "node.invoke.request", "node.invoke" -> {
                // The gateway puts the invoke in `params` (iOS reads json["params"]); accept
                // `payload` as a fallback.
                val params = json.obj("params") ?: json.obj("payload")
                val request = parseInvoke(params) // event form: the invoke id is params.id
                if (request == null) {
                    Log.w(TAG, "malformed invoke event: $json")
                    return
                }
                dispatchInvoke(request)
            }
            "tick", "health" -> Unit
            else -> Log.d(TAG, "unhandled event: $name")
        }
    }

    private fun handleRequest(socket: WebSocket, json: JsonObject) {
        val id = json.string("id") ?: ""
        val method = json.string("method") ?: ""
        if (method == "node.invoke") {
            // iOS: for `req` frames the invoke id IS the frame id (research §2.5); a params.id that
            // a gateway might also send is ignored so node.invoke.result carries what iOS would.
            val request = parseInvoke(json.obj("params"), forcedId = id.takeIf { it.isNotEmpty() })
            if (request == null) {
                send(socket, errorResponse(id, OpenClawProtocol.ERROR_UNSUPPORTED, "Malformed node.invoke"))
                return
            }
            dispatchInvoke(request)
            return
        }
        send(socket, errorResponse(id, OpenClawProtocol.ERROR_UNSUPPORTED, "Unknown method: $method"))
    }

    /** @param forcedId the frame id for `req` frames (authoritative); null for the event form. */
    private fun parseInvoke(params: JsonObject?, forcedId: String? = null): OpenClawNodeInvokeRequest? {
        if (params == null) return null
        val id = forcedId ?: params.string("id") ?: return null
        val command = params.string("command") ?: return null
        val commandParams: JsonObject? = params.obj("params") ?: params.string("paramsjson")?.let { raw ->
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
        val timeout = (params.get("timeoutMs") ?: params.get("timeoutms"))
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }
        return OpenClawNodeInvokeRequest(id = id, command = command, params = commandParams, timeoutMs = timeout)
    }

    private fun dispatchInvoke(request: OpenClawNodeInvokeRequest) {
        val handler = router
        if (handler == null) {
            sendInvokeResult(OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_NO_ROUTER, "No command router installed"))
            return
        }
        scope.launch {
            val budget = request.timeoutMs?.takeIf { it > 0 } ?: invokeTimeoutMs
            val result = withTimeoutOrNull(budget) {
                try {
                    handler.handleCommand(request)
                } catch (e: CancellationException) {
                    // withTimeoutOrNull's own TimeoutCancellationException travels this path: it
                    // must reach the timeout machinery (and a cancelled scope must stay cancelled)
                    // instead of becoming an INTERNAL error (final review Minor 8).
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "command ${request.command} threw: ${e.message}")
                    OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_INTERNAL, e.message ?: "internal error")
                }
            } ?: OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_TIMEOUT, "Command timed out after ${budget}ms")
            sendInvokeResult(result)
        }
    }

    // ---- outbound ----

    /** `chat.send`; [imageJpegBase64] is a base64 (no line breaks) JPEG attachment. */
    fun sendChatMessage(text: String, imageJpegBase64: String? = null): Boolean {
        val params = JsonObject().apply {
            addProperty("sessionKey", OpenClawProtocol.SESSION_KEY)
            addProperty("message", text)
            addProperty("idempotencyKey", UUID.randomUUID().toString())
            if (imageJpegBase64 != null) {
                add("attachments", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "image")
                        addProperty("mimeType", "image/jpeg")
                        addProperty("content", imageJpegBase64)
                    })
                })
            }
        }
        return sendJson(request(UUID.randomUUID().toString(), "chat.send", params))
    }

    private fun sendInvokeResult(result: OpenClawNodeInvokeResult) {
        val params = JsonObject().apply {
            addProperty("id", result.id)
            addProperty("nodeId", clientInfo.nodeId)
            addProperty("ok", result.ok)
            // Large payloads (JPEG) travel as a JSON *string* — identical to iOS.
            result.payload?.let { addProperty("payloadjson", gson.toJson(it)) }
            result.error?.let { error ->
                add("error", JsonObject().apply {
                    error.code?.let { addProperty("code", it) }
                    error.message?.let { addProperty("message", it) }
                })
            }
        }
        sendJson(request(UUID.randomUUID().toString(), "node.invoke.result", params))
    }

    private fun request(id: String, method: String, params: JsonObject): JsonObject = JsonObject().apply {
        addProperty("type", "req")
        addProperty("id", id)
        addProperty("method", method)
        add("params", params)
    }

    private fun errorResponse(id: String, code: String, message: String): JsonObject = JsonObject().apply {
        addProperty("type", "res")
        addProperty("id", id)
        addProperty("ok", false)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    private fun sendJson(json: JsonObject): Boolean {
        val socket = synchronized(lock) { webSocket } ?: return false
        return send(socket, json)
    }

    private fun send(socket: WebSocket, json: JsonObject): Boolean = socket.send(gson.toJson(json))

    private fun jsonArray(values: List<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
