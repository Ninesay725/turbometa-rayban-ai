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
 * OpenClaw camera node. Official gateways bind one role to each WebSocket; node connections
 * cannot call operator RPCs such as chat.send. The former custom operator/node port remains
 * available only through explicit [OpenClawCompatibility.LEGACY_CUSTOM_V3] configuration.
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
    httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val reconnectDelaysMs: List<Long> = RECONNECT_DELAYS_MS,
    private val maxReconnectAttempts: Int = MAX_RECONNECT_ATTEMPTS,
    private val invokeTimeoutMs: Long = DEFAULT_INVOKE_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val handshakeTimeoutMs: Long = 10_000L,
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
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val lock = Any()
    // Enforce at the socket boundary too: injected clients must not move endpoint-bound credentials.
    private val httpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private var webSocket: WebSocket? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var tickJob: Job? = null
    private var reconnectJob: Job? = null
    private var pendingConnectId: String? = null
    private var handshakeJob: Job? = null
    private var activeCompatibility = OpenClawCompatibility.CURRENT
    private var activeEndpoint: String? = null
    private var activeSharedToken: String? = null
    private var activeDeviceId: String? = null
    private var nodeChat: OpenClawNodeChat? = null

    /** Pending gateway request identifier for a parent-owned pairing UI, if supplied. */
    var pairingRequestId: String? = null
        private set

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
    /** Camera transport readiness, independent of chat subscription or inference errors. */
    val nodeConnectionState: StateFlow<OpenClawConnectionState> = _connectionState.asStateFlow()
    private val _chatConnectionState = MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.Disconnected)
    /** Existing UI consumers see Connected only once chat subscription has been acknowledged. */
    val connectionState: StateFlow<OpenClawConnectionState> = _chatConnectionState.asStateFlow()
    val chatConnectionState: StateFlow<OpenClawConnectionState> = connectionState
    private val _isChatBusy = MutableStateFlow(false)
    val isChatBusy: StateFlow<Boolean> = _isChatBusy.asStateFlow()

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
        get() = synchronized(lock) {
            if (activeCompatibility.isLegacyCustom) clientInfo.nodeId else activeDeviceId ?: clientInfo.nodeId
        }

    /** Takes effect on the next connection; no rejection ever changes it implicitly. */
    var compatibility: OpenClawCompatibility
        get() = store.compatibility
        set(value) { store.compatibility = value }

    /** Explicit recovery after a revoked paired token; never used as an automatic auth fallback. */
    fun forgetPairedDeviceToken() {
        synchronized(lock) {
            val endpoint = buildUrl(OpenClawCompatibility.CURRENT) ?: return
            store.saveDeviceToken(endpoint, deviceIdentity.deviceId, OpenClawProtocol.ROLE, null)
        }
    }

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
            if (state == OpenClawConnectionState.WaitingForPairing && !force) return
            if (state == OpenClawConnectionState.Connected && force &&
                _chatConnectionState.value != OpenClawConnectionState.Connected
            ) {
                nodeChat?.start()
                return
            }
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
            handshakeJob?.cancel()
            handshakeJob = null
            pairingRequestId = null
            val socket = webSocket
            webSocket = null
            runCatching { socket?.close(1000, "User disconnected") }
            updateConnectionState(OpenClawConnectionState.Disconnected)
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
        val url = runCatching {
            activeCompatibility = store.compatibility
            activeSharedToken = store.loadToken()?.takeIf { it.isNotBlank() }
            activeEndpoint = buildUrl(OpenClawCompatibility.CURRENT)
            activeDeviceId = null
            pairingRequestId = null
            buildUrl(activeCompatibility)
        }.getOrElse {
            failWithTransport("SETTINGS: unable to load gateway settings")
            return
        }
        // Request.Builder().url() throws IllegalArgumentException for anything OkHttp cannot parse;
        // that must become the InvalidUrl state, never an exception in a Compose click handler.
        val request = url?.let { runCatching { Request.Builder().url(it).build() }.getOrNull() }
        if (request == null) {
            Log.e(TAG, "invalid gateway address: '${store.host}:${store.port}'")
            shouldReconnect = false
            updateConnectionState(OpenClawConnectionState.Error(OpenClawErrorReason.InvalidUrl))
            return
        }
        Log.d(TAG, "connecting to ${store.scheme}://${store.host}:${store.port}")
        updateConnectionState(OpenClawConnectionState.Connecting)
        pendingConnectId = null
        webSocket = httpClient.newWebSocket(request, Listener())
        val socket = webSocket
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            delay(handshakeTimeoutMs)
            synchronized(lock) {
                if (!isActive) return@synchronized
                if (socket === webSocket && _connectionState.value == OpenClawConnectionState.Connecting) {
                    failWithTransport("HANDSHAKE_TIMEOUT: gateway did not complete connect")
                }
            }
        }
    }

    /**
     * Official auth travels only in connect.params.auth. The explicit custom profile retains
     * its old percent-encoded query token. Null if the address is malformed. HttpUrl ensures
     * RFC 3986 encoding (a space is `%20`, not `+`) and IPv6 literals are
     * validated; a bare `::1` is bracketed for the user. Uri.encode is unavailable on the JVM.
     */
    @VisibleForTesting
    internal fun buildUrl(profile: OpenClawCompatibility = store.compatibility): String? {
        val rawHost = store.host.trim()
        val port = store.port
        if (rawHost.isEmpty() || rawHost.any { it.isWhitespace() || it in "/\\?#@" } || port !in 1..65535) return null
        val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
        val httpScheme = if (store.scheme == OpenClawProtocol.SCHEME_WSS) "https" else "http"
        val base = "$httpScheme://$host:$port/".toHttpUrlOrNull() ?: return null
        val token = if (profile.isLegacyCustom) store.loadToken()?.takeIf { it.isNotBlank() } else null
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

    private fun handleDisconnect(socket: WebSocket, error: Throwable?, retryAfterMs: Long = 0L) {
        synchronized(lock) {
            // A closed and a failed callback for the same socket, or callbacks from a socket we
            // already replaced, must not be counted twice.
            if (socket !== webSocket) return
            webSocket = null
            tickJob?.cancel()
            tickJob = null
            pendingConnectId = null
            handshakeJob?.cancel()
            handshakeJob = null

            if (!shouldReconnect) {
                updateConnectionState(OpenClawConnectionState.Disconnected)
                return
            }
            reconnectAttempts++
            if (reconnectAttempts > maxReconnectAttempts) {
                Log.e(TAG, "giving up after $maxReconnectAttempts reconnect attempts")
                shouldReconnect = false
                updateConnectionState(OpenClawConnectionState.Error(OpenClawErrorReason.MaxRetries(maxReconnectAttempts)))
                return
            }
            val delayMs = maxOf(retryAfterMs, reconnectDelaysMs[minOf(reconnectAttempts - 1, reconnectDelaysMs.size - 1)])
            Log.w(TAG, "reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$maxReconnectAttempts)" +
                (error?.let { ", cause: ${it.message}" } ?: ""))
            updateConnectionState(OpenClawConnectionState.Reconnecting(reconnectAttempts))
            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(lock) {
                    if (!isActive) return@synchronized
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
        synchronized(lock) {
            if (socket !== webSocket) return
            val type = json.string("type") ?: return
            when {
                type == "event" && json.string("event") == "connect.challenge" -> {
                    if (_connectionState.value != OpenClawConnectionState.Connecting || pendingConnectId != null) return
                    val nonce = json.obj("payload")?.string("nonce")?.trim()
                    if (nonce.isNullOrEmpty()) {
                        failWithTransport("DEVICE_AUTH_NONCE_REQUIRED: empty gateway challenge")
                        return
                    }
                    sendConnect(socket, nonce)
                }
                type == "res" -> handleResponse(socket, json)
                _connectionState.value != OpenClawConnectionState.Connected -> Unit
                type == "evt" || type == "event" -> handleEvent(socket, json.string("event") ?: json.string("method") ?: "", json)
                type == "req" || type == "request" -> handleRequest(socket, json)
                else -> Log.d(TAG, "unknown message type: $type")
            }
        }
    }

    private fun sendConnect(socket: WebSocket, nonce: String) {
        val id = UUID.randomUUID().toString()
        val signedAt = clock()
        var cachedToken: String? = null
        // Runs on the OkHttp reader thread and touches the Keystore / EncryptedSharedPreferences on
        // first use. A throw here used to escape into OkHttp, which reported it as a socket failure:
        // five reconnects and a misleading "Connection failed after 5 retries" (final review I3).
        val signed = runCatching {
            val signer = deviceIdentity // first use loads or generates the seed
            activeDeviceId = signer.deviceId
            if (!activeCompatibility.isLegacyCustom) {
                cachedToken = store.loadDeviceToken(checkNotNull(activeEndpoint), signer.deviceId, OpenClawProtocol.ROLE)
                    ?.takeIf { it.isNotBlank() }
            }
            signer to signer.signConnect(
                clientId = OpenClawProtocol.CLIENT_ID,
                clientMode = OpenClawProtocol.CLIENT_MODE,
                role = activeCompatibility.role,
                scopes = activeCompatibility.scopes,
                signedAtMs = signedAt,
                token = cachedToken ?: activeSharedToken,
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
        if (signature.isBlank()) {
            failWithTransport("IDENTITY: unable to sign gateway challenge")
            return
        }
        val params = JsonObject().apply {
            addProperty("minProtocol", activeCompatibility.minProtocol)
            addProperty("maxProtocol", activeCompatibility.maxProtocol)
            add("client", JsonObject().apply {
                addProperty("id", OpenClawProtocol.CLIENT_ID)
                addProperty("displayName", OpenClawProtocol.DISPLAY_NAME)
                addProperty("version", clientInfo.version)
                addProperty("mode", OpenClawProtocol.CLIENT_MODE)
                addProperty("platform", OpenClawProtocol.PLATFORM)
                addProperty("modelIdentifier", clientInfo.modelIdentifier)
            })
            addProperty("role", activeCompatibility.role)
            add("scopes", jsonArray(activeCompatibility.scopes))
            add("caps", jsonArray(OpenClawProtocol.CAPS))
            add("commands", jsonArray(OpenClawProtocol.COMMANDS))
            add("auth", JsonObject().apply {
                if (cachedToken != null) addProperty("deviceToken", cachedToken)
                else activeSharedToken?.let { addProperty("token", it) }
            })
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

    private fun handleResponse(socket: WebSocket, json: JsonObject) {
        if (nodeChat?.handleResponse(json) == true) return
        val id = json.string("id")
        // Only this socket's outstanding connect may change authentication/pairing state.
        if (id == null || id != pendingConnectId) return
        val ok = json.get("ok")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        if (ok) {
            handleHelloOk(json.obj("payload"))
            return
        }
        val error = json.obj("error")
        val code = error?.string("code")
        val message = error?.string("message")
        Log.w(TAG, "error response for $id: $code $message")
        val details = error?.obj("details")
        if (code == OpenClawProtocol.ERROR_NOT_PAIRED || details?.string("code") == "PAIRING_REQUIRED") {
            shouldReconnect = false
            pendingConnectId = null
            handshakeJob?.cancel()
            handshakeJob = null
            pairingRequestId = details?.string("requestId")
            webSocket = null
            socket.close(1000, "awaiting pairing approval")
            updateConnectionState(OpenClawConnectionState.WaitingForPairing)
            return
        }
        if (code == "UNAVAILABLE" && details?.string("reason") == "startup-sidecars" &&
            error?.get("retryable")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        ) {
            val retryAfter = error?.get("retryAfterMs")?.let { runCatching { it.asLong }.getOrNull() }
                ?.coerceIn(0L, 30_000L) ?: 0L
            handleDisconnect(socket, null, retryAfter)
            socket.close(1000, "gateway starting")
            return
        }
        synchronized(lock) {
            // A gateway that rejects the handshake (auth/protocol) keeps the socket open, so no
            // close/failure callback ever arrives and nothing retries: the UI used to stay
            // "Connecting..." forever (final review I3). Retrying would not help either — the token
            // or the protocol version is wrong — so the backoff is stopped as well.
            if (id != null && id == pendingConnectId) {
                failWithTransport("${details?.string("code") ?: code ?: "?"}: ${message ?: ""}")
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
        handshakeJob?.cancel()
        handshakeJob = null
        val socket = webSocket
        webSocket = null
        runCatching { socket?.close(1000, "connect rejected") }
        updateConnectionState(OpenClawConnectionState.Error(OpenClawErrorReason.Transport(detail)))
    }

    /** Transport transitions invalidate chat subscriptions; a chat-only error leaves nodes live. */
    private fun updateConnectionState(state: OpenClawConnectionState) {
        nodeChat?.close()
        nodeChat = null
        _connectionState.value = state
        _chatConnectionState.value = state
    }

    private fun handleHelloOk(payload: JsonObject?) {
        synchronized(lock) {
            val protocol = payload?.get("protocol")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asBigDecimal.intValueExact() }.getOrNull() }
            if (protocol == null || protocol !in activeCompatibility.minProtocol..activeCompatibility.maxProtocol ||
                (!activeCompatibility.isLegacyCustom && payload?.string("type") != "hello-ok")
            ) {
                failWithTransport("PROTOCOL_MISMATCH: invalid hello or unsupported gateway protocol")
                return
            }
            if (!activeCompatibility.isLegacyCustom) {
                val auth = payload?.obj("auth")
                val scopes = auth?.get("scopes")?.takeIf { it.isJsonArray }?.asJsonArray
                if (auth?.string("role") != OpenClawProtocol.ROLE || scopes == null || scopes.size() != 0) {
                    failWithTransport("AUTH_SCOPE_MISMATCH: expected node role with empty scopes")
                    return
                }
                val token = auth?.string("deviceToken")?.takeIf { it.isNotBlank() }
                if (token != null) {
                    val saved = runCatching {
                        store.saveDeviceToken(checkNotNull(activeEndpoint), checkNotNull(activeDeviceId), OpenClawProtocol.ROLE, token)
                    }.isSuccess
                    if (!saved) {
                        failWithTransport("DEVICE_TOKEN_STORAGE: unable to persist paired node token")
                        return
                    }
                }
                // Additional bootstrap operator tokens are deliberately not consumed by this node.
            }
            Log.d(TAG, "connected to gateway")
            reconnectAttempts = 0
            pendingConnectId = null
            handshakeJob?.cancel()
            handshakeJob = null
            _connectionState.value = OpenClawConnectionState.Connected
            if (activeCompatibility.isLegacyCustom) {
                _chatConnectionState.value = OpenClawConnectionState.Connected
                startTick()
            } else {
                val methods = payload?.obj("features")?.get("methods")?.takeIf { it.isJsonArray }?.asJsonArray
                if (methods?.any { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "node.event" } != true) {
                    _chatConnectionState.value = OpenClawConnectionState.Error(OpenClawErrorReason.Transport("CHAT_UNAVAILABLE: gateway does not advertise node.event"))
                    return
                }
                val agentId = payload?.obj("snapshot")?.obj("sessionDefaults")?.string("defaultAgentId")
                    ?.takeIf { it.isNotBlank() && ':' !in it } ?: "main"
                val socket = checkNotNull(webSocket)
                nodeChat = OpenClawNodeChat(
                    lock, scope, "agent:$agentId:${OpenClawProtocol.SESSION_KEY}",
                    send = { frame -> socket === webSocket && send(socket, frame) },
                    emit = { _chatEvents.tryEmit(it) },
                    onState = { _chatConnectionState.value = it },
                    requestTimeoutMs = handshakeTimeoutMs,
                    onBusy = { _isChatBusy.value = it },
                ).also { it.start() }
            }
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

    private fun handleEvent(socket: WebSocket, name: String, json: JsonObject) {
        when (name) {
            "chat" -> {
                val payload = json.obj("payload") ?: return
                if (!activeCompatibility.isLegacyCustom) {
                    nodeChat?.handleChat(payload)
                    return
                }
                val state = payload.string("state")
                val content = payload.obj("message")?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                val text = content?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("text") }
                    ?.joinToString("") ?: ""
                _chatEvents.tryEmit(OpenClawChatEvent(text, isFinal = state == "final"))
            }
            "node.invoke.request", "node.invoke" -> {
                val params = if (activeCompatibility.isLegacyCustom) {
                    json.obj("params") ?: json.obj("payload")
                } else json.obj("payload")
                val request = parseInvoke(params) // event form: the invoke id is params.id
                if (request == null) {
                    Log.w(TAG, "ignoring malformed or misaddressed node invoke")
                    return
                }
                dispatchInvoke(socket, request)
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
            dispatchInvoke(socket, request)
            return
        }
        send(socket, errorResponse(id, OpenClawProtocol.ERROR_UNSUPPORTED, "Unknown method: $method"))
    }

    /** @param forcedId the frame id for `req` frames (authoritative); null for the event form. */
    private fun parseInvoke(params: JsonObject?, forcedId: String? = null): OpenClawNodeInvokeRequest? {
        if (params == null) return null
        if (!activeCompatibility.isLegacyCustom && params.string("nodeId") != activeDeviceId) return null
        val id = forcedId ?: params.string("id") ?: return null
        val command = params.string("command") ?: return null
        if (id.isBlank() || command.isBlank()) return null
        val paramsKey = if (activeCompatibility.isLegacyCustom) "paramsjson" else "paramsJSON"
        val rawElement = params.get(paramsKey)?.takeUnless { it.isJsonNull }
        val rawParams = rawElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val inlineParams = if (activeCompatibility.isLegacyCustom) params.obj("params") else null
        val parsed = rawParams?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
        val commandParams = inlineParams ?: parsed?.takeIf { it.isJsonObject }?.asJsonObject
        // Malformed canonical parameters must never turn into a camera capture with defaults.
        if (!activeCompatibility.isLegacyCustom && rawElement != null &&
            (rawParams == null || parsed == null || (!parsed.isJsonNull && !parsed.isJsonObject))
        ) return null
        val timeout = (params.get("timeoutMs") ?: params.get("timeoutms"))
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }
        return OpenClawNodeInvokeRequest(id = id, command = command, params = commandParams, timeoutMs = timeout)
    }

    private fun dispatchInvoke(socket: WebSocket, request: OpenClawNodeInvokeRequest) {
        val responseNodeId = if (activeCompatibility.isLegacyCustom) clientInfo.nodeId else checkNotNull(activeDeviceId)
        val legacy = activeCompatibility.isLegacyCustom
        if (request.command !in OpenClawProtocol.COMMANDS) {
            sendInvokeResult(socket, responseNodeId, legacy, OpenClawNodeInvokeResult.failure(
                request.id, OpenClawProtocol.ERROR_UNKNOWN_COMMAND, "Command was not declared by this node",
            ))
            return
        }
        val handler = router
        if (handler == null) {
            sendInvokeResult(socket, responseNodeId, legacy, OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_NO_ROUTER, "No command router installed"))
            return
        }
        scope.launch {
            if (synchronized(lock) { socket !== webSocket }) return@launch
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
            sendInvokeResult(socket, responseNodeId, legacy, result)
        }
    }

    // ---- outbound ----

    /** `chat.send`; [imageJpegBase64] is a base64 (no line breaks) JPEG attachment. */
    fun sendChatMessage(text: String, imageJpegBase64: String? = null): Boolean {
        synchronized(lock) {
            if (_chatConnectionState.value != OpenClawConnectionState.Connected) return false
            if (!activeCompatibility.isLegacyCustom) return nodeChat?.sendMessage(text, imageJpegBase64) ?: false
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
    }

    private fun sendInvokeResult(socket: WebSocket, responseNodeId: String, legacy: Boolean, result: OpenClawNodeInvokeResult) {
        val params = JsonObject().apply {
            addProperty("id", result.id)
            addProperty("nodeId", responseNodeId)
            addProperty("ok", result.ok)
            // Large payloads (JPEG) travel as a JSON *string* — identical to iOS.
            result.payload?.let { addProperty(if (legacy) "payloadjson" else "payloadJSON", gson.toJson(it)) }
            result.error?.let { error ->
                add("error", JsonObject().apply {
                    error.code?.let { addProperty("code", it) }
                    error.message?.let { addProperty("message", it) }
                })
            }
        }
        synchronized(lock) {
            if (socket === webSocket && _connectionState.value == OpenClawConnectionState.Connected) {
                send(socket, request(UUID.randomUUID().toString(), "node.invoke.result", params))
            }
        }
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
