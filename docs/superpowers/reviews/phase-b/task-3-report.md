# Task 3 report — `OpenClawNodeService` (gateway WebSocket client)

Branch `android-v2`, base HEAD `0473885` (Task 2), commit **`aa5a786`**
`feat(android): OpenClawNodeService — gateway WebSocket client with iOS-identical handshake, chat.send, node.invoke routing, tick and backoff reconnect (MockWebServer tests)`

Status: **DONE**. All 7 brief steps done in order, TDD (RED → implement → GREEN). Zero protocol
deviations from the brief; one test-scaffolding experiment (MockWebServer stderr silencing) was
tried, proven unnecessary, and reverted so `ScriptedGateway.kt` is the brief's file verbatim.

---

## 1. What was implemented

1. **Step 3.1 — `services/openclaw/OpenClawCommandRouter.kt`** (new, contract only): the
   `OpenClawCommandHandler` interface — one `suspend fun handleCommand(request): OpenClawNodeInvokeResult`.
   Task 4 appends `class OpenClawCommandRouter … : OpenClawCommandHandler` to this same file.
2. **Step 3.2 — `test/.../ScriptedGateway.kt`** (new): MockWebServer-hosted gateway double.
   Sends `connect.challenge` on every accepted socket, answers `connect` per `connectReply`
   (`OK` / `NOT_PAIRED` / `SILENT`), queues every frame the app sends into `received`, counts
   `opens` / `closes`, and can push text or binary frames. Copied verbatim from the brief.
3. **Step 3.3 — `test/.../OpenClawNodeServiceTest.kt`** (new): 25 tests, verbatim from the brief.
4. **Step 3.4 — RED** confirmed (see §4).
5. **Step 3.5 — `services/openclaw/OpenClawNodeService.kt`** (new): the client itself, verbatim
   from the brief's Step 3.5 code. No edits were needed — it compiled and went green first try.
6. **Step 3.6 — GREEN**: 25/25 in the new class; 95/95 in the whole unit-test suite.
7. **Step 3.7 — Build + commit**: `assembleDebug` / `assembleRelease` green, single commit with the
   brief's exact message.

No `strings.xml` change was needed: this task adds no user-facing text. The literal English strings
in the file (`"No command router installed"`, `"Unknown method: <m>"`, `"Command timed out after
<n>ms"`, `"No video frame available"` in tests) are **wire** messages inside `error.message` of
frames sent to the gateway — they must stay identical to iOS and must not be localized. The
localized mapping of `OpenClawConnectionState` / `OpenClawErrorReason` belongs to the UI tasks.

---

## 2. Exact public API produced

### `com.smartview.glassai.services.openclaw.OpenClawCommandHandler` (OpenClawCommandRouter.kt)

```kotlin
interface OpenClawCommandHandler {
    suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult
}
```

### `com.smartview.glassai.services.openclaw.OpenClawNodeService` (OpenClawNodeService.kt)

```kotlin
class OpenClawNodeService(
    private val store: OpenClawSettingsStore,
    private val identity: Lazy<OpenClawDeviceIdentity>,          // resolved on first connect(), never at construction
    private val clientInfo: OpenClawClientInfo,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,                 // 15_000L
    private val reconnectDelaysMs: List<Long> = RECONNECT_DELAYS_MS,     // [2000, 4000, 8000, 16000, 30000]
    private val maxReconnectAttempts: Int = MAX_RECONNECT_ATTEMPTS,      // 5
    private val invokeTimeoutMs: Long = DEFAULT_INVOKE_TIMEOUT_MS,       // 30_000L
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // ---- state / events ----
    val connectionState: StateFlow<OpenClawConnectionState>   // Disconnected | Connecting | Reconnecting(attempt)
                                                             // | WaitingForPairing | Connected | Error(reason)
    val chatEvents: SharedFlow<OpenClawChatEvent>            // replay = 0, extraBufferCapacity = 64; (text, isFinal)
    val nodeId: String                                       // clientInfo.nodeId, e.g. "rayban-0123abcd"

    // ---- settings passthrough ----
    var gatewayHost: String        // setter trims
    var gatewayPort: Int
    var gatewayScheme: String      // setter coerces to "ws" unless exactly "wss"
    fun loadGatewayToken(): String?
    fun saveGatewayToken(token: String?)     // trims; blank/null deletes the stored token

    // ---- lifecycle ----
    fun setCommandRouter(router: OpenClawCommandHandler?)
    fun connect(force: Boolean = false)      // force = Settings button (cuts a running backoff);
                                             // false = auto-connect (respects it)
    fun disconnect()
    fun sendChatMessage(text: String, imageJpegBase64: String? = null): Boolean

    // ---- test hooks ----
    @VisibleForTesting internal fun buildUrl(): String?       // "ws(s)://host:port/[?token=…]" or null
    @VisibleForTesting internal val isTickRunning: Boolean

    companion object {
        const val TICK_INTERVAL_MS = 15_000L
        val RECONNECT_DELAYS_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val DEFAULT_INVOKE_TIMEOUT_MS = 30_000L
        fun getInstance(context: Context): OpenClawNodeService   // process singleton, no I/O at construction
        fun nodeIdFor(context: Context): String                  // "rayban-" + first 8 chars of ANDROID_ID, lowercase
        fun lanHttpClient(): OkHttpClient                        // NO_PROXY, 10 s connect, 0 read timeout
    }
}
```

Every member of the brief's **Interfaces** block is present with the stated signature. Consumers:

- **Task 4** installs its router with `setCommandRouter(router)` and implements
  `OpenClawCommandHandler.handleCommand`. The service calls it from `scope` (Dispatchers.Default)
  inside `withTimeoutOrNull(request.timeoutMs ?: 30_000)`; a thrown exception becomes
  `INTERNAL`, a blown budget becomes `TIMEOUT`, and the router is responsible for hopping to Main
  before touching `GlassesSessionManager`.
- **Task 6** observes `connectionState`, collects `chatEvents`, and calls
  `connect(force)` / `disconnect()` / `sendChatMessage(text, imageJpegBase64)`.

### Test fixture (`test/.../ScriptedGateway.kt`)

```kotlin
class ScriptedGateway(val nonce: String = "nonce-1") {
    enum class ConnectReply { OK, NOT_PAIRED, SILENT }
    val server: MockWebServer; val received: LinkedBlockingQueue<JsonObject>
    var socket: WebSocket?; var connectReply: ConnectReply; var opens: Int; var closes: Int
    fun start(count: Int = 1); fun enqueueUpgrade(); fun stop()
    fun send(text: String); fun sendBinary(text: String)
    fun await(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject
    fun awaitMethod(method: String, timeoutMs: Long = 5_000): JsonObject
}
```

---

## 3. Protocol conformance (research §8.6 keep-identical checklist)

| §8.6 item | Where implemented (`OpenClawNodeService.kt`) | Test that pins it |
|---|---|---|
| **1.** Envelopes `req`/`res`/`event`; fields `type,id,method,params,ok,payload,error.code,error.message` | `request(id,method,params)`, `errorResponse(id,code,message)`, `handleMessage` dispatch (`event`→challenge, `res`, `evt`/`event`, `req`/`request`) | `handshakeSendsTheIosConnectFrameAndBecomesConnected`, `unknownRequestGetsUnsupported`, `dispatchAcceptsTheIosAliasesAndBinaryFrames` |
| **2.** `connect` params: `minProtocol`/`maxProtocol` = 3, `client{id,displayName,version,mode,platform,modelIdentifier}`, `role`, `scopes`, `caps`, `commands`, `auth{token}` (`{}` with no token), `device{id,publicKey,signature,signedAt,nonce}` | `sendConnect(socket, nonce)` | `handshakeSendsTheIosConnectFrameAndBecomesConnected` (every field asserted); `withoutATokenAuthIsEmptyAndTheSignatureHasAnEmptyTokenField` (`auth` empty object) |
| **3.** v3 signature string, Ed25519 over UTF-8, base64url no padding; `deviceId = sha256hex(rawPubKey)`; `publicKey = base64url(rawPubKey)` | `signer.signConnect(CLIENT_ID, CLIENT_MODE, ROLE, SCOPES, signedAt, token, nonce, PLATFORM, deviceFamily = null)` → Task 2's `OpenClawDeviceIdentity` | `handshakeSendsTheIosConnectFrameAndBecomesConnected` (`Ed25519Verify` over `buildSignaturePayload(...)`); `withoutATokenAuthIsEmptyAndTheSignatureHasAnEmptyTokenField` (verifies against the literal `v3|…|1711700000000||nonce-1|android|`) |
| **4a.** `chat.send` params `sessionKey`/`message`/`idempotencyKey`/`attachments[{type,mimeType,content}]` | `sendChatMessage(...)` | `chatSendCarriesSessionKeyMessageIdempotencyKeyAndAttachment` |
| **4b.** `chat` event parsing `payload.state` + `payload.message.content[].text` (all parts joined) | `handleEvent("chat", …)` | `chatEventsAreDeliveredTyped` ("Hel"+"lo" → "Hello", delta/final); `dispatchAcceptsTheIosAliasesAndBinaryFrames` |
| **5a.** Invoke inbound `event node.invoke.request` / `node.invoke` with `params{id,command,params\|paramsjson,timeoutMs\|timeoutms}` | `handleEvent("node.invoke.request"/"node.invoke")` → `parseInvoke(params ?: payload)` | `invokeEventRoundTripsThroughTheRouter` (`paramsjson` + `timeoutms`), `malformedParamsJsonAndPayloadCarriedInvokesStillReachTheRouter` (unparseable `paramsjson` → null params; `payload`-carried invoke) |
| **5b.** Invoke inbound `req node.invoke` — the **frame id** is the invoke id | `handleRequest` → `parseInvoke(params, forcedId = frame id)` | `invokeRequestFrameUsesTheFrameIdAndReportsErrors`, `reqFrameIdWinsOverParamsId` (a `params.id` present in the frame is ignored) |
| **5c.** Outbound `node.invoke.result` with `id`, `nodeId`, `ok`, `payloadjson` **string**, `error{code,message}` | `sendInvokeResult(result)` | `invokeEventRoundTripsThroughTheRouter` (payload re-parsed from the string, no `error` key), `invokeRequestFrameUsesTheFrameIdAndReportsErrors` (no `payloadjson` key on failure) |
| **6a.** `tick` every 15 s with `ts` | `startTick()`; default `TICK_INTERVAL_MS = 15_000L` | `tickIsSentPeriodicallyWithTs` (injected 200 ms interval, `ts` asserted); `tickStopsOnDisconnect` |
| **6b.** `NOT_PAIRED` → pairing state | `handleResponse` → `WaitingForPairing` | `notPairedMovesToWaitingForPairing`; `connectFromWaitingForPairingClosesTheOldSocketFirst` |
| **6c.** `UNSUPPORTED` reply for unknown `req` | `handleRequest` fallthrough | `unknownRequestGetsUnsupported` (`"Unknown method: something.else"`), `dispatchAcceptsTheIosAliasesAndBinaryFrames` (via the `request` alias) |
| **7.** Command result payload shapes (`camera.snap`, `camera.list`, `device.status`, `device.info`) | **Task 4** (router) — out of scope here; the service only advertises them in `connect.commands` | `handshakeSendsTheIosConnectFrameAndBecomesConnected` asserts the `commands` array order |
| Backoff 2/4/8/16/30 s, max 5 | `handleDisconnect` + `RECONNECT_DELAYS_MS` / `MAX_RECONNECT_ATTEMPTS` | `reconnectsAfterTheGatewayClosesTheSocket`, `reconnectAttemptsResetAfterASuccessfulHello`, `connectDuringBackoffDoesNotDialEarlyButForceDoes`, `givesUpAfterMaxAttemptsWithMaxRetriesError` (cap of 5 = the production default) |
| No router installed → `NO_ROUTER` | `dispatchInvoke` | `invokeWithoutRouterAnswersNoRouter` |

### Deliberate Android-side deviations (spec decision 3 / research §10 "quirks not to port")

| Deviation | Where | Test |
|---|---|---|
| client id `openclaw-android`, platform `android`, displayName `Ray-Ban Meta Glasses` | `OpenClawProtocol` (Task 2), used by `sendConnect` | `handshakeSendsTheIosConnectFrameAndBecomesConnected` |
| version = `BuildConfig.VERSION_NAME` (not a hard-coded `"2.0.0"`; also removes the iOS 1.5.0/2.0.0 split) | `getInstance` → `OpenClawClientInfo.version` | injected as `"2.0.0"` in the test; asserted on the wire |
| `wss://` supported through the scheme setting | `buildUrl()`, `gatewayScheme` | `buildUrlHandlesWssIpv6AndTokenEncoding` |
| `?token=` **percent-encoded** (iOS appends raw) | `buildUrl()` via `HttpUrl.addQueryParameter` (RFC 3986: space → `%20`, `+` → `%2B`) | `buildUrlHandlesWssIpv6AndTokenEncoding`; `handshakeSends…` (`/?token=secret-token`); `withoutATokenAuthIsEmpty…` (`/`, no query) |
| Only the **pending connect's** `ok:true` is a hello (iOS: every `ok:true` reconnects the state and restarts the tick) | `pendingConnectId` matched in `handleResponse`; cleared in `handleHelloOk` / `handleDisconnect` / `startConnection` | `nonHelloResponsesDoNotChangeTheState` |
| Typed `(text, isFinal)` chat events instead of the `"[[FINAL]]"` string prefix | `OpenClawChatEvent` + `handleEvent("chat")` | `chatEventsAreDeliveredTyped` |
| node id `rayban-<first 8 of ANDROID_ID, lowercase>` (iOS: `identifierForVendor`) | `nodeIdFor(context)` | `invokeEventRoundTripsThroughTheRouter` asserts `nodeId` on the wire |
| Emptying the token **deletes** it | `saveGatewayToken` trims → Task 2 store deletes on blank | `savingABlankTokenDeletesIt` |
| A close **and** a failure for the same socket are counted **once** (iOS double-counts) | `handleDisconnect`: `if (socket !== webSocket) return` | `reconnectsAfterTheGatewayClosesTheSocket` (`opens == 2`, `requestCount == 2`); `reconnectAttemptsResetAfterASuccessfulHello` |
| The backoff wait is its own state `Reconnecting(attempt)` so auto-connect cannot defeat it | `connect(force)` guard + `handleDisconnect` | `connectDuringBackoffDoesNotDialEarlyButForceDoes` |
| A stale socket (e.g. the `WaitingForPairing` one) is closed before re-dialing | `startConnection()` | `connectFromWaitingForPairingClosesTheOldSocketFirst` (`closes == 1`, not orphaned) |
| A malformed address is a state, never a thrown exception | `buildUrl()` + `runCatching { Request.Builder().url(...) }` → `Error(InvalidUrl)` | `invalidHostIsReportedWithoutTouchingTheNetwork`, `buildUrlHandlesWssIpv6AndTokenEncoding` |
| `openclaw_enabled` does not exist | — (nothing implemented) | — |
| **No** background `camera.snap` | out of scope for this task (Task 4 router) | — |

---

## 4. TDD evidence

**RED** — `./gradlew :app:testDebugUnitTest --tests "…OpenClawNodeServiceTest"` run before
`OpenClawNodeService.kt` existed (the contract + gateway + test file were already in place):

```
e: …/OpenClawNodeServiceTest.kt:497:17 Unresolved reference 'connectionState'.
e: …/OpenClawNodeServiceTest.kt:507:17 Unresolved reference 'connect'.
e: …/OpenClawNodeServiceTest.kt:514:17 Unresolved reference 'saveGatewayToken'.
e: …/OpenClawNodeServiceTest.kt:515:28 Unresolved reference 'loadGatewayToken'.
…
* What went wrong:
Execution failed for task ':app:compileDebugUnitTestKotlin'.
BUILD FAILED in 4s
```

**GREEN** — same command after adding `OpenClawNodeService.kt`: `BUILD SUCCESSFUL in 7s`,
`TEST-…OpenClawNodeServiceTest.xml: tests="25" skipped="0" failures="0" errors="0"`, empty
`<system-err>` and `<system-out>`. No production edit was needed to make the brief's tests pass.

**Flakiness check** — the class was re-run 4 more times with `--rerun` (5 green runs total,
25/25 each). Slowest cases (seconds): `tickStopsOnDisconnect` 0.81, `connectDuringBackoffDoesNot
DialEarlyButForceDoes` 0.52 (its own 500 ms observation window), `disconnectStopsReconnecting`
0.42, `notPairedMovesToWaitingForPairing` 0.36, `nonHelloResponsesDoNotChangeTheState` 0.30,
`givesUpAfterMaxAttemptsWithMaxRetriesError` 0.17 (5 refused connects, well inside its 15 s
budget). No real 15 s wait anywhere: the tick interval, backoff delays, attempt cap and the clock
are all constructor-injected.

---

## 5. Build results

- `./gradlew :app:testDebugUnitTest --rerun-tasks`: **BUILD SUCCESSFUL**.
  **95 tests, 0 failures, 0 errors** across all 8 `TEST-*.xml` files (70 before this task + 25 new).
  Every file's `<system-err>` and `<system-out>` are empty (checked programmatically, not by eye).
- `./gradlew :app:assembleDebug :app:assembleRelease`: **BUILD SUCCESSFUL** (1 m 4 s on the first
  run; `compileReleaseKotlin` and `minifyReleaseWithR8` executed). No R8 missing-class or
  missing-rule output, no `missing_rules.txt` produced; no ProGuard change needed.
  Note: `packageRelease` stayed UP-TO-DATE because nothing references `OpenClawNodeService` yet, so
  R8 shrinks it out of the release dex — expected until Task 4/6 wire it in.
- **Warnings**: a forced non-incremental compile (`:app:compileDebugKotlin
  :app:compileDebugUnitTestKotlin --rerun -Pkotlin.incremental=false`) emits **24 `w:` lines, none
  of them from a file under `services/openclaw/`** — all are the pre-existing deprecations already
  recorded in the Task 2 report (`EncryptedSharedPreferences`/`MasterKey` in `APIKeyManager`,
  `BluetoothAudioManager`, `QuickVisionService`, `RTMPStreamingService`, `ModeSettingsScreen`,
  `QuickVisionScreen`, `RecordsScreen`, `Theme.kt`). This task's four files compile warning-free.

---

## 6. Files changed

Created (4 files, 1185 insertions, 0 deletions — commit `aa5a786`):

- `android/app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouter.kt`
- `android/app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawNodeService.kt`
- `android/app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceTest.kt`
- `android/app/src/test/java/com/smartview/glassai/services/openclaw/ScriptedGateway.kt`

No existing file was modified. No Gradle change was needed (Task 2 already declared Tink and
MockWebServer). `android/local.properties` was never read or printed.

---

## 7. Deviations

**None from the brief.** Both flagged risks resolved in favor of the brief as written:

1. **MockWebServer stderr noise.** The task allowed silencing it; I first added a `java.util.logging`
   `Level.OFF` guard to `ScriptedGateway`. I then **measured** it: with the guard fully disabled
   (`Level.ALL` in both places) the suite's `<system-err>` is still **0 characters**, so
   MockWebServer's INFO records never reach Gradle's stderr capture in this configuration. The
   guard was therefore removed and `ScriptedGateway.kt` is the brief's file byte-for-byte; the
   commit was amended (it had not been pushed) rather than adding a cleanup commit.
2. **No test scaffolding needed adapting.** The brief anticipated a possible `request.path`
   mismatch on `handshakeSendsTheIosConnectFrameAndBecomesConnected`; OkHttp reports exactly
   `/?token=secret-token`, so the assertion stands as written. Close-frame ordering also behaved:
   `ScriptedGateway.closes` counts only client-initiated closes (the server's own
   `WebSocket.close` does not re-enter its own `onClosing`), which is what
   `connectFromWaitingForPairingClosesTheOldSocketFirst` relies on.

**Test count**: the brief said "at least 26 tests pass"; the brief's own test file contains **25**
`@Test` methods, all of which pass. No test was dropped or renamed — the 26 appears to be an
off-by-one in the brief's prose. Suite total is 95.

---

## 8. Self-review

- **§8.6 coverage**: items 1–6 are each implemented and pinned by at least one named test (table in
  §3). Item 7 (command payload shapes) is Task 4's; this task only advertises the command list in
  `connect`, which is asserted.
- **Deviations are exactly the listed ones** (§3, second table). Nothing else diverges from iOS:
  the frame shapes, field names, ordering-insensitive JSON content, `payloadjson`-as-string, the
  `paramsjson`/`timeoutms` lowercase aliases, the `evt`/`request` type aliases, binary-frame
  decoding as UTF-8, and the `event`-vs-`req` invoke-id rule all match research §2.
- **No secret is logged.** All 17 `Log.*` calls were audited: none interpolate the token, the
  Ed25519 seed, the signature or the public key. `buildUrl()`'s result (which carries `?token=`) is
  never logged — the connect log line prints `scheme://host:port` only, built from the store fields.
  `handleResponse` logs `error.code`/`error.message`, not the request that produced them.
- **Strings**: no user-facing string added, so no `values/strings.xml` / `values-zh-rCN/strings.xml`
  change is required or made. Wire-level `error.message` text stays English by protocol.
- **Threading**: every mutable field is guarded by the single `lock`; `startConnection` and
  `startTick` are only ever called with it held (so an OkHttp `onFailure` that fires before
  `webSocket` is assigned blocks on the lock instead of being dropped). `connectionState` /
  `chatEvents` are thread-safe by construction. Nothing here touches `GlassesSessionManager`, a
  `Looper`, or any Android UI class — which is why the whole class is JVM-testable.
- **Git**: single commit on `android-v2`, brief's exact message, no attribution line, tree clean
  before and after.

---

## 9. Concerns

1. **The production backoff sequence is not pinned by an assertion.** Every reconnect test injects
   short delays (`listOf(100L)` / `listOf(50L)` / `listOf(3_000L)`), and `tickIsSentPeriodically
   WithTs` injects 200 ms. The 2/4/8/16/30 s ladder and the 15 s tick therefore live only in the
   `RECONNECT_DELAYS_MS` / `TICK_INTERVAL_MS` constants; only the **attempt cap** of 5 is pinned by
   a test (`givesUpAfterMaxAttemptsWithMaxRetriesError` uses the default `maxReconnectAttempts`).
   The brief's test list did not include such a test and the brief specifies a single commit, so I
   did not add one. A one-line assertion on the two companion constants would close this — cheap to
   fold into Task 4 or a later cleanup if desired.
2. **`assembleRelease` does not yet exercise this code.** R8 strips `OpenClawNodeService` because
   nothing references it; the first real release-shrinking signal (e.g. reflection/Gson-related
   rules, though none are expected — everything uses `JsonObject` directly, no `@SerializedName`
   models) arrives once Task 4/6 wire the service into the app. Worth re-checking the R8 log then.
3. **`getInstance` / `nodeIdFor` are not unit-tested** (they need a `Context`). They are exercised
   only by construction-time reasoning: `SecureOpenClawSettingsStore` is lazy, the identity is a
   `lazy {}`, and `lanHttpClient()` is covered by `lanHttpClientBypassesProxiesAndNeverTimesOut
   Reads`. If a Task-6/7 instrumented test is planned, asserting `nodeId` matches
   `rayban-[0-9a-f]{8}` on a real device would be a useful addition.
4. **`chatEvents` has `replay = 0`.** A Task 6 collector that subscribes after `connect()` can miss
   deltas emitted in the window before it attaches. That matches the iOS callback semantics and the
   brief's chosen flow shape, but the ViewModel should subscribe in `init`/`viewModelScope` before
   calling `connect`, not after.
5. **`private val chatEvents` in `OpenClawNodeServiceTest` is unused** (it is in the brief's test
   code verbatim; each test builds its own local collector list). Harmless — Kotlin emitted no
   warning for it — but a future cleanup could drop the field.
