# iOS OpenClaw Integration (commit cd26fb2) — Reverse-Engineering Report for Android Port

Repo: `D:/Coding/Workspaces/Android/turbometa-rayban-ai`
Commit under study: `cd26fb2` ("feat: v2.0.0 — OpenClaw 集成、Meta Ray-Ban Display 支持、稳定性优化"), parent `8df5edc`.
All iOS paths are relative to `CameraAccess/`. All Android paths are relative to `android/app/src/main/java/com/smartview/glassai/` unless stated.

Files that make up the feature (all added in cd26fb2, see `git show --stat cd26fb2`):

| File | Lines | Role |
|---|---|---|
| `Services/OpenClaw/OpenClawNodeService.swift` | 620 | Gateway WebSocket client, handshake, chat, invoke dispatch, keepalive, reconnect, token Keychain |
| `Services/OpenClaw/OpenClawDeviceIdentity.swift` | 138 | Ed25519 key pair, device id, v3 signature, Keychain persistence |
| `Services/OpenClaw/OpenClawModels.swift` | 168 | Frame/param structs, `AnyCodableValue` JSON wrapper |
| `Services/OpenClaw/OpenClawCommandRouter.swift` | 185 | `camera.snap` / `camera.list` / `device.status` / `device.info` → DAT `StreamSessionViewModel` |
| `Services/OpenClaw/OpenClawASRService.swift` | 327 | Alibaba DashScope Fun-ASR realtime speech-to-text over WebSocket |
| `Views/OpenClawChatView.swift` | 411 | Chat screen (voice / snap / text) |
| `Views/OpenClawSettingsView.swift` | 158 | Gateway host/port/token settings + status |
| `Views/SettingsView.swift` (hunks) | — | "Integrations" section row + sheet |
| `Views/TurboMetaHomeView.swift` (hunks) | — | Home feature card + auto-connect |
| `Views/MainAppView.swift` (hunks) | — | Router wiring + auto-connect |
| `Info.plist` (hunk) | — | `NSAllowsLocalNetworking` |
| `en.lproj/Localizable.strings`, `zh-Hans.lproj/Localizable.strings` | +24 each | 23 new keys |
| `README.md` §"OpenClaw 集成" (lines 581-662), `README_EN.md` §"OpenClaw Integration" (lines 521-583) | — | User setup guide |

A gitignored `openclaw-ref/` directory was added to `.gitignore` in this commit ("OpenClaw reference (not part of this project)"), i.e. the author had a local clone of the OpenClaw gateway source while writing this. It is **not** present on disk now, so everything below is derived from the app code itself, not from the gateway spec.

---

## 1. What OpenClaw is, from the app's perspective

- OpenClaw (https://openclaw.ai) is an open-source, self-hosted personal AI assistant. The user runs an **OpenClaw Gateway** process on a computer (`openclaw gateway install`, config at `~/.openclaw/openclaw.json`) — `README.md:596-604`.
- The phone app is a **client of that Gateway** over a plain **WebSocket** on the LAN (or via Tailscale for WAN — `README.md:653-661`).
- The app connects with `role: "operator"` and `client.mode: "node"` — i.e. it is simultaneously an *operator* (can call `chat.send` and receive `chat` events) and a *node* (exposes device commands that the AI can invoke through `node.invoke`). `OpenClawNodeService.swift:311-314, 336-359`.
- Capabilities the node advertises: `caps: ["camera"]`, `commands: ["camera.snap","camera.list","device.status","device.info"]` — `OpenClawNodeService.swift:69-78`.
- The gateway must be told to bind to LAN (`"gateway": {"bind": "lan"}`) and to allow node commands (`"gateway": {"nodes": {"allowCommands": ["camera.snap","camera.clip","camera.list","device.status","device.info"]}}`) — `README.md:608-637`. (`camera.clip` appears in the README allow-list but is **not** implemented by the app.)
- First connection requires **device pairing** on the gateway side: `openclaw devices list` / `openclaw devices approve` — `README.md:647-651`; the app surfaces this as `.waitingForPairing` when it receives error code `NOT_PAIRED` (`OpenClawNodeService.swift:441-445`).
- Auth material: an optional **Gateway Token** (found "in the OpenClaw Dashboard URL", `README.md:643`) plus a per-install **Ed25519 device identity** that signs every connect.
- The README roadmap lists "OpenClaw Node 模式（AI 主动调用眼镜拍照）" as *in progress* (`README.md:691`) even though the node-invoke path is implemented; treat the node side as less battle-tested than the chat side.
- Limitation called out in README: the DAT SDK cannot access the glasses camera in background; the app must be foregrounded for `camera.snap` (`README.md:592`).

---

## 2. Wire protocol (Gateway ⇄ app)

### 2.1 Transport

| Aspect | Value | Source |
|---|---|---|
| Scheme | hard-coded `"ws"` (no TLS option) | `OpenClawNodeService.swift:189` |
| URL | `ws://<gatewayHost>:<gatewayPort>` — no path; if a token is stored: `?token=<token>` appended raw (not URL-encoded) | `:190-194` |
| Default host / port | `"127.0.0.1"` / `18789` | `:43-44`, `:616-620` |
| Session config | `URLSessionConfiguration.default`, `timeoutIntervalForRequest = 10`, `connectionProxyDictionary = [:]` (bypass system proxy, "直连局域网 Gateway") | `:202-205` |
| Delegate queue | dedicated `OperationQueue` named `"openclaw-ws"` | `:206-208` |
| Max inbound message | `maximumMessageSize = 16 * 1024 * 1024` (16 MiB; needed because invoke results embed base64 JPEG) | `:211` |
| Message encoding | JSON text frames; binary frames are decoded as UTF-8 text too | `:252-265` |
| ATS | `NSAppTransportSecurity → NSAllowsLocalNetworking = true` added to `Info.plist` so plain `ws://` to LAN IPs works | `Info.plist` hunk `@@ -41,13 +41,18 @@` |
| Receive loop | starts in `didOpenWithProtocol` (first `receive`), then recursive `receiveMessage()` | `:583-596`, `:224-237` |
| Send | `JSONSerialization` → `.string(text)` | `:239-248` |

### 2.2 Frame envelope

Three frame kinds (`OpenClawModels.swift:10-35`). The service only ever *builds* dictionaries by hand (`[String: Any]`), the Codable structs in `OpenClawModels.swift` are mostly unused (see §2.10).

```jsonc
// request  (both directions)
{ "type": "req", "id": "<UUID>", "method": "<name>", "params": { ... } }
// response (both directions)
{ "type": "res", "id": "<id of req>", "ok": true|false, "payload": {...}, "error": {"code": "...", "message": "..."} }
// event    (gateway → app)   -- gateway uses "event", app also tolerates "evt"
{ "type": "event", "event": "<name>", "payload": { ... } }
```

Dispatch order in `handleMessage` (`OpenClawNodeService.swift:267-302`):
1. `type=="event" && event=="connect.challenge"` → `handleChallenge(payload.nonce)`.
2. `type=="res"`: `ok==true` → `handleHelloOk` (**for every OK response, not just connect**); `ok==false` → `handleResponse`.
3. `type in ("evt","event")` → `handleEvent(name = json.event ?? json.method)`.
4. `type in ("req","request")` → `handleRequest`.
5. anything else → log "Unknown message type".

### 2.3 Handshake sequence

```
app                                      gateway
 |------- TCP/WS open (ws://host:port?token=T) ---->|
 |<------ {"type":"event","event":"connect.challenge","payload":{"nonce":"..."}} ----|
 |------- {"type":"req","id":U1,"method":"connect","params":{...see below...}} ---->|
 |<------ {"type":"res","id":U1,"ok":true,"payload":{...}}   -> state = connected, start tick |
 |   or   {"type":"res","id":U1,"ok":false,"error":{"code":"NOT_PAIRED",...}} -> waitingForPairing |
```

`connect` params exactly as built in `handleChallenge` (`OpenClawNodeService.swift:306-370`):

```json
{
  "type": "req",
  "id": "<UUID>",
  "method": "connect",
  "params": {
    "minProtocol": 3,
    "maxProtocol": 3,
    "client": {
      "id": "openclaw-ios",
      "displayName": "Ray-Ban Meta Glasses",
      "version": "2.0.0",
      "mode": "node",
      "platform": "ios",
      "modelIdentifier": "<UIDevice.current.model, e.g. 'iPhone'>"
    },
    "role": "operator",
    "scopes": ["operator.read", "operator.write"],
    "caps": ["camera"],
    "commands": ["camera.snap", "camera.list", "device.status", "device.info"],
    "auth": { "token": "<gateway token>" },          // {} when no token stored
    "device": {
      "id": "<sha256(pubkey) hex, 64 chars>",
      "publicKey": "<base64url(raw 32-byte Ed25519 pubkey), no padding>",
      "signature": "<base64url(Ed25519 sig over v3 payload), no padding>",
      "signedAt": 1711700000000,                    // Int64 ms since epoch
      "nonce": "<nonce from connect.challenge>"
    }
  }
}
```

Constants: `protocolVersion = 3` (`:64`), `clientId = "openclaw-ios"`, `clientMode = "node"`, `platform = "ios"`, `role = "operator"`, `scopes = ["operator.read","operator.write"]` (`:310-315`). The token is sent **twice**: as `?token=` query and in `params.auth.token`.

On `ok:true` (`handleHelloOk`, `:372-380`): `connectionState = .connected`, `reconnectAttempts = 0`, `startTickWatchdog()`. The response payload (protocol number etc.) is ignored.

### 2.4 Device identity (Ed25519) — `OpenClawDeviceIdentity.swift`

| Item | Detail | Source |
|---|---|---|
| Key type | `CryptoKit.Curve25519.Signing.PrivateKey` (Ed25519) | `:14, :83` |
| Generation | first `loadOrCreate()`: try Keychain; if absent/invalid generate new and save `privateKey.rawRepresentation` (32-byte seed) | `:74-86` |
| Persistence | Keychain generic password, `service = "com.smartview.glassai.openclaw.device"`, `account = "ed25519_private_key"`, `kSecAttrAccessible = AfterFirstUnlock` | `:71-72`, `:114-128` |
| `deviceId` | lowercase hex of `SHA256(publicKey.rawRepresentation)` (64 chars) | `:89-92` |
| `publicKeyBase64Url` | base64url(raw 32-byte public key), `+`→`-`, `/`→`_`, `=` stripped | `:93`, `:133-138` |
| Signature payload (v3) | `"v3|<deviceId>|<clientId>|<clientMode>|<role>|<scopes joined by ','>|<signedAtMs>|<token or ''>|<nonce>|<platform normalized>|<deviceFamily normalized or ''>"` joined with `|`, UTF-8 | `:34-48` |
| Normalization | `normalizeForAuth`: trim, lowercase, keep only `[a-z0-9._-]` (alphanumerics via `CharacterSet.alphanumerics` plus `.`,`_`,`-`); nil/empty → `""` | `:57-65` |
| Signature output | base64url(Ed25519 signature 64 bytes), no padding; `""` on failure | `:49-52` |
| Caller values | `clientId="openclaw-ios"`, `clientMode="node"`, `role="operator"`, `scopes=["operator.read","operator.write"]`, `token = nil if empty`, `platform="ios"`, `deviceFamily=nil` | `OpenClawNodeService.swift:319-329` |

Concrete example of the signed string (token "abc", nonce "n1", signedAt 1711700000000):
`v3|9f86d0…(64 hex)|openclaw-ios|node|operator|operator.read,operator.write|1711700000000|abc|n1|ios|`

Note the trailing `|` because `deviceFamily` is empty. The identity is created lazily (`private lazy var deviceIdentity = OpenClawDeviceIdentityStore.loadOrCreate()`, `OpenClawNodeService.swift:57`) and is stable across launches, so the gateway's "approve device" is a one-time operation per install.

`nodeId` (used only in `node.invoke.result.nodeId`) is a *different* identifier: `"rayban-" + first 8 chars of identifierForVendor`, lowercased (`OpenClawNodeService.swift:82-87`). The router has its own default `nodeId = "rayban-node"` (`OpenClawCommandRouter.swift:16`) — it is constructed with the default in `MainAppView.swift:51`, but `sendInvokeResult` overwrites `nodeId` with the service's value anyway (`OpenClawNodeService.swift:481-485`). The settings screen shows the literal `"rayban-node"` (`OpenClawSettingsView.swift:103`) — cosmetic inconsistency.

### 2.5 Message catalog

#### Gateway → app

| `type` / name | Where handled | Payload fields used | Behavior |
|---|---|---|---|
| `event` `connect.challenge` | `:273-279` | `payload.nonce: String` | triggers `connect` request |
| `res` (`ok:true`, any id) | `:282-286` | — | `handleHelloOk` (sets connected, restarts tick) |
| `res` (`ok:false`) | `:431-447` | `id`, `error.code`, `error.message` | logs; `NOT_PAIRED` → `.waitingForPairing` |
| `event` `chat` | `:391-403` | `payload.state: "final"|other`, `payload.message.content: [{ "text": String, ... }]` | joins all `text` fields; delivers `onChatEvent("[[FINAL]]"+text)` when `state=="final"`, else `onChatEvent(text)` (replace-style delta) |
| `event` `node.invoke.request` / `node.invoke` | `:388-390`, `:451-455` | `params.id`, `params.command`, `params.params` or `params.paramsjson`, `params.timeoutms`/`timeoutMs` | routes to `OpenClawCommandRouter`, replies with `node.invoke.result` |
| `req` `node.invoke` | `:411-419` | same as above, but `id` is the frame id | same |
| `req` (other method) | `:420-428` | `id`, `method` | replies `{"type":"res","id":id,"ok":false,"error":{"code":"UNSUPPORTED","message":"Unknown method: <m>"}}` |
| `event` `tick`, `health` | `:404-405` | — | ignored |
| other events | `:406-407` | — | logged |

Example inbound invoke (as the app expects it — note gateway may send `paramsjson` string instead of `params` object, `:459-462`):
```json
{ "type": "event", "event": "node.invoke.request",
  "payload": null,
  "params": { "id": "inv-123", "command": "camera.snap",
              "params": { "maxWidth": 1600, "quality": 0.8, "format": "jpg" },
              "timeoutMs": 30000 } }
```
(The event handler reads `json["params"]`, not `json["payload"]`, for invoke — `:452`.)

Example chat stream event:
```json
{ "type": "event", "event": "chat",
  "payload": { "state": "delta", "message": { "role": "assistant",
               "content": [ { "type": "text", "text": "Hello, I can see…" } ] } } }
```

#### App → gateway

| Method | Built in | Params | When |
|---|---|---|---|
| `connect` | `:336-369` | see §2.3 | after challenge |
| `chat.send` | `sendChatMessage`, `:104-133` | `sessionKey: "turbometa-chat"` (constant, `:136`), `message: String`, `idempotencyKey: UUID`, optional `attachments: [{ "type":"image", "mimeType":"image/jpeg", "content": "<base64 JPEG, quality 0.7>" }]` | user sends text / voice text / snap |
| `node.invoke.result` | `sendInvokeResult`, `:480-508` | `id: <invoke id>`, `nodeId`, `ok: Bool`, `payloadjson: "<JSON string of payload>"` (payload is JSON-encoded into a *string*, "For large payloads (images)"), `error: {code?, message?}` | after router finishes |
| `tick` | `:522-536` | `ts: Int64 ms` | every 15 s while connected |
| `res` `UNSUPPORTED` | `:422-427` | — | unknown inbound `req` |

Example `chat.send` with image:
```json
{ "type":"req", "id":"…", "method":"chat.send",
  "params": { "sessionKey":"turbometa-chat", "message":"请看这张眼镜拍摄的照片",
              "idempotencyKey":"…",
              "attachments":[{"type":"image","mimeType":"image/jpeg","content":"/9j/4AAQ…"}] } }
```

Example `node.invoke.result` for `camera.snap`:
```json
{ "type":"req", "id":"…", "method":"node.invoke.result",
  "params": { "id":"inv-123", "nodeId":"rayban-1a2b3c4d", "ok":true,
              "payloadjson":"{\"format\":\"jpg\",\"base64\":\"/9j/…\",\"width\":1600,\"height\":1200}" } }
```
Error variant: `{ "id":"inv-123","nodeId":"…","ok":false,"error":{"code":"NO_FRAME","message":"No video frame available"} }`.

Responses to `chat.send`, `tick`, `node.invoke.result` are not correlated by id — any `ok:true` just re-triggers `handleHelloOk`; any `ok:false` is logged (and `NOT_PAIRED` flips state).

### 2.6 Keepalive

`startTickWatchdog()` (`:522-536`): a `Task` loop sleeping `tickInterval = 15` s (`:65`) then sending `{"type":"req","id":UUID,"method":"tick","params":{"ts":<ms>}}`. No pong/timeout detection — liveness is only inferred from socket close/error. Because every `ok:true` response calls `handleHelloOk` → `startTickWatchdog()` (which cancels and recreates the task), the 15 s timer is effectively **reset on every OK response** from the gateway.

### 2.7 Reconnection / backoff (`handleDisconnect`, `:540-565`; `scheduleReconnect`, `:567-578`)

- Triggered from: receive failure (`:230-235`), `didCloseWith` (`:598-601`), `didCompleteWithError` (`:603-611`, which first sets `.error(localizedDescription)`).
- Guard: returns immediately if state is already `.disconnected`.
- Cleans `webSocket`, `urlSession.invalidateAndCancel()`, cancels tick.
- If `shouldReconnect == false` (user called `disconnect()`, `:141-153`) → `.disconnected`, stop.
- Else `reconnectAttempts += 1`; if `> maxReconnectAttempts (5)` → `.error("连接失败，已重试 5 次")`, `shouldReconnect = false`.
- Delay = `min(2^attempts, 30)` → **2, 4, 8, 16, 30 s**; state set to `.disconnected`; a `Task` sleeps then calls `startConnection()` on main actor if still `shouldReconnect`.
- `reconnectAttempts` resets to 0 only on `handleHelloOk`.
- `connect()` (`:95-101`) is a no-op if already `.connected`/`.connecting`; it sets `shouldReconnect = true`, persists settings, and starts.
- Quirk: `didCloseWith` and `didCompleteWithError` can both fire for one failure, and the guard only checks `.disconnected` (the state is `.error` after `didCompleteWithError`), so attempts can be double-counted.

### 2.8 Connection state machine (`OpenClawConnectionState`, `:12-32`)

`disconnected` → `connecting` (startConnection) → `connected` (ok res) ; `connecting` → `waitingForPairing` (NOT_PAIRED) ; any → `error(String)` (transport error / max retries / invalid URL) ; `connected` → `disconnected` (disconnect()/drop).

### 2.9 Error codes

| Code | Direction | Source |
|---|---|---|
| `NOT_PAIRED` | gateway → app; sets `.waitingForPairing` | `OpenClawNodeService.swift:441-445` |
| `UNSUPPORTED` | app → gateway for unknown `req` | `:426` |
| `NO_ROUTER` | app → gateway when router not set | `:475` |
| `UNKNOWN_COMMAND` | router | `OpenClawCommandRouter.swift:34` |
| `NOT_READY` ("Stream not initialized") | router | `:43` |
| `STREAM_FAILED` ("Could not start camera stream") | router | `:57` |
| `NO_FRAME` ("No video frame available") | router | `:64` |
| `ENCODE_FAILED` ("Failed to encode JPEG") | router | `:86` |

### 2.10 Model file notes (`OpenClawModels.swift`)

- `OpenClawConnectParams` (`:48-57`) uses lowercase `minprotocol`/`maxprotocol` and is **unused**; the real frame uses `minProtocol`/`maxProtocol` (`OpenClawNodeService.swift:337-338`). Do not port the struct's field names.
- `OpenClawNodeInvokeRequest { id, command, params: [String: Any]?, timeoutMs: Int? }` (`:65-70`) and `OpenClawNodeInvokeResult: Codable { id, nodeId, ok, payload: [String: AnyCodableValue]?, error: OpenClawError? }` (`:72-78`) are the two types actually exchanged between service and router.
- `CameraSnapParams(from:)` defaults: `maxWidth = 1600`, `quality = 0.8`, `format = "jpg"` (`:82-92`). `format` is parsed but ignored (always JPEG).
- `AnyCodableValue` (`:96-168`) is a hand-rolled JSON enum used only to build the invoke payload; on Android use Gson `JsonObject`/`Map<String, Any?>` instead.

---

## 3. Command router (`OpenClawCommandRouter.swift`)

`@MainActor class OpenClawCommandRouter` holds `weak var streamViewModel: StreamSessionViewModel?` and `nodeId` (`:11-19`). Wired once in `MainAppView.swift:50-52` (`OpenClawNodeService.shared.setCommandRouter(OpenClawCommandRouter(streamViewModel: streamViewModel))`). Invocation runs on main actor (`OpenClawNodeService.swift:473-477`).

| Command | Implementation | DAT / app APIs used | Result payload |
|---|---|---|---|
| `camera.snap` | `:41-106` | if `!vm.isStreaming` → `await vm.handleStartStreaming()` (permission check + `StreamSession.start()`, `StreamSessionViewModel.swift:156-193`), then poll `vm.isStreaming` every 200 ms up to 5 s; read `vm.currentVideoFrame: UIImage?` (last decoded frame from `videoFramePublisher`, `StreamSessionViewModel.swift:112-126`); downscale to `maxWidth` with `UIGraphicsImageRenderer` if wider; JPEG at `quality` clamped to [0.1,1.0]; base64 | `{"format":"jpg","base64":"…","width":Int,"height":Int}` |
| `camera.list` | `:110-129` | `vm.hasActiveDevice` (from `AutoDeviceSelector.activeDeviceStream()`, `StreamSessionViewModel.swift:96-101`) | `{"cameras":[{"id":"rayban-main","name":"Ray-Ban Meta Camera","facing":"front","available":true}]}` or `{"cameras":[]}` |
| `device.status` | `:133-153` | `hasActiveDevice`, `isStreaming`, `streamingStatus` (`streaming`/`waiting`/`stopped` via `"\(…)"`), `currentVideoFrame != nil` | `{"deviceConnected":Bool,"isStreaming":Bool,"streamStatus":"streaming|waiting|stopped","hasVideoFrame":Bool?}` |
| `device.info` | `:157-172` | `UIDevice.current.systemVersion` | `{"deviceType":"Ray-Ban Meta","appName":"TurboMeta","appVersion":"1.5.0","sdkVersion":"0.5.0","platform":"iOS","osVersion":"17.x"}` (note stale `appVersion` "1.5.0" vs connect `"2.0.0"`) |
| other | `:33-35` | — | `UNKNOWN_COMMAND` |

Important behaviors:
- `camera.snap` does **not** use `StreamSession.capturePhoto()`; it grabs the latest preview frame (resolution = the stream's `video_quality` setting, 24 fps, `StreamSessionViewModel.swift:74-92`). It also **does not stop the stream** it started (the chat UI's own snap path does stop it — `OpenClawChatView.swift:288-309`).
- No `timeoutMs` handling; no concurrency guard (two invokes can race on stream start).
- `isStreaming` on iOS is `streamingStatus != .stopped` (`StreamSessionViewModel.swift:39-41`), i.e. true while *waiting* too; so a frame may still be nil right after the wait loop → `NO_FRAME`.
- Nothing here calls TTS, VisionAPIService, LiveAIManager or RTMP.

---

## 4. ASR service (`OpenClawASRService.swift`)

| Aspect | Value | Source |
|---|---|---|
| Engine | Alibaba Cloud DashScope **Fun-ASR realtime** (阿里云 Fun-ASR 实时语音识别) | header `:1-5`, `:16` |
| Endpoint | `wss://dashscope.aliyuncs.com/api-ws/v1/inference` (Beijing only — no Singapore/intl variant, unlike Omni/Vision which honor `AlibabaEndpoint`) | `:15` |
| Model | `"fun-asr-realtime"` | `:16` |
| Auth | HTTP header `Authorization: Bearer <apiKey>`; key from `APIKeyManager.shared.getAPIKey(for: .alibaba)` (= key for the currently selected Alibaba endpoint, `Utils/APIKeyManager.swift:55-58, 108-118`) | `:74-75`, `OpenClawChatView.swift:323` |
| Session config | `URLSessionConfiguration.default`, `connectionProxyDictionary = [:]` | `:77-79` |
| Task id | UUID lowercase without dashes (32 hex) | `:46` |
| Mode | streaming duplex; results arrive continuously | `:107, :137` |
| Audio source | `AVAudioSession` category `.playAndRecord`, mode `.default`, options `[.allowBluetooth, .defaultToSpeaker]` → system picks input; with `.allowBluetooth` a connected HFP headset (the glasses) becomes the input route when it is the active route. No explicit route selection UI. | `:225-227` |
| Capture | `AVAudioEngine.inputNode` tap, `bufferSize 4096`, native input format | `:232-237` |
| Resample | `AVAudioConverter` to `16000 Hz`, mono, Float32 standard format when input differs | `:263-270`, `:287-312` |
| Wire format | Float32 → **PCM16 little-endian mono 16 kHz**, sent as **binary WebSocket frames** (`.data`) | `:272-284`, `:146-152` |
| Language | not set (no `language_hints`); README: "当前主要优化了中文" | `:103-122`, `README.md:578` |
| Output | `onPartialResult(text)` for interim, `onFinalResult(text)` when `end_time > 0`, `onError(msg)` | `:32-34`, `:185-212` |
| Stop | `finish-task` then close socket after 0.5 s | `:50-67` |

Protocol messages (DashScope "inference" WS API):

```jsonc
// app → server, right after didOpen  (:100-126)
{ "header": { "action": "run-task", "task_id": "<32hex>", "streaming": "duplex" },
  "payload": { "task_group": "audio", "task": "asr", "function": "recognition",
               "model": "fun-asr-realtime",
               "parameters": { "format": "pcm", "sample_rate": 16000,
                               "vocabulary_id": "", "disfluency_removal_enabled": false },
               "input": {} } }
// app → server: raw PCM16 binary frames (no JSON wrapper)
// app → server on stop  (:128-144)
{ "header": { "action": "finish-task", "task_id": "<32hex>", "streaming": "duplex" },
  "payload": { "input": {} } }
// server → app  (:177-216)
{ "header": { "event": "task-started" } }                       // → start mic
{ "header": { "event": "result-generated" },
  "payload": { "output": { "sentence": { "text": "…", "end_time": 1234 /* or null/0 */ } } } }
{ "header": { "event": "task-finished" } }
{ "header": { "event": "task-failed", "error_message": "…" } }
```

Sequence: `start()` → WS connect → `didOpen` → `receiveMessage()` + `sendRunTask()` → `task-started` → `startRecording()` → stream PCM → user taps stop → `stop()` → `stopRecording()`, `finish-task`, close.

UI consumption (`OpenClawChatView.swift:322-357`): `asrPartial = partial`; on final `asrText += text; asrPartial = ""`. The displayed text is `asrText + asrPartial` (`:248-253`). Sentences accumulate until the user taps **Send** (`sendASRText`, `:359-366`) or **Cancel**. There is no auto-send despite the README's "自动发送给 AI" claim (`README.md:588`).

---

## 5. UI

### 5.1 Chat screen (`OpenClawChatView.swift`)

Message model (`:9-15`):
```swift
struct OpenClawChatMessage: Identifiable { let id = UUID(); let role: String /* "user" | "assistant" */; let text: String; let image: UIImage?; let timestamp = Date() }
```

State (`:18-32`): `streamViewModel: StreamSessionViewModel` (injected), `openClawService = OpenClawNodeService.shared`, `messages: [OpenClawChatMessage]`, `inputText`, `pendingResponse` (streaming assistant bubble), `isSending`, `isListening`, `asrText`, `asrPartial`, `asrService: OpenClawASRService?`, `showTextInput`. Messages are **in-memory only** (lost on dismiss; not stored in `ConversationStorage`).

Layout, top to bottom:
1. Orange banner with spinner + `openclaw.status.connecting` when `connectionState != .connected` (`:38-47`).
2. `ScrollView`/`LazyVStack` of `ChatBubble` + a trailing bubble for `pendingResponse` (`:50-69`); auto-scroll on `messages.count` change.
3. Bottom panel:
   - ASR preview box (`displayASRText`; shows `openclaw.chat.listening` placeholder while listening and empty) + after stop, **Cancel** (`cancel`) / **Send** (`openclaw.chat.sendvoice`, purple) buttons (`:76-118`).
   - Three action buttons (`:121-180`): **Snap & Send** (`camera.fill`, `openclaw.chat.snap`; disabled when `isSending` or not connected), **big mic** (72 pt circle, purple→indigo; red→orange + pulsing ring + `stop.fill` when listening; disabled when not connected), **Text** toggle (`keyboard`, `openclaw.chat.text`).
   - Text bar when `showTextInput` (`:184-201`): `TextField(openclaw.chat.placeholder)` with `.submitLabel(.send)`, send button `arrow.up.circle.fill` disabled when empty or not connected.
4. Nav bar: title `"OpenClaw"`, leading `xmark` dismiss, trailing green/gray dot + `gear` `NavigationLink → OpenClawSettingsView()` (`:206-227`).

Lifecycle: `onAppear` sets `onChatEvent` handler and auto-connects if not connected and a token exists (`:229-235`). `onDisappear` stops listening, flushes `pendingResponse` into messages, clears handler (`:236-243`).

Actions:
- `sendText()` (`:273-280`): trims, appends user bubble, `flushPendingResponse()`, clears field, `sendChatMessage(text)`.
- `snapAndSend()` (`:284-310`): `isSending = true`; if not streaming → `handleStartStreaming()` and poll `currentVideoFrame != nil` (200 ms, ≤5 s); on no frame → assistant bubble `openclaw.chat.noframe`; else text = `inputText` or `openclaw.chat.photoprompt` ("请看这张眼镜拍摄的照片" / "Please look at this photo from my glasses"), append user bubble with image, `sendChatMessage(text, image: frame)`; **stops the stream** if it started it.
- `startListening()` (`:322-349`): requires Alibaba key else assistant bubble `openclaw.chat.noapikey`; creates `OpenClawASRService`, hooks callbacks, `start()`. `stopListening()` (`:351-357`) keeps `asrText` for review. `sendASRText()` (`:359-366`).
- Chat event handler (`:257-269`): `"[[FINAL]]"`-prefixed → clear pending, append assistant bubble; else `pendingResponse = text` (replace).

`ChatBubble` (`:378-411`): user = right-aligned, purple→indigo gradient, white text; assistant = left, `systemGray5`; optional image 200×150 max, corner 12.

### 5.2 Settings screen (`OpenClawSettingsView.swift`)

| Section | Rows | Source |
|---|---|---|
| header `"OpenClaw"` | `Status` row: colored dot + text (`connected`→green `openclaw.status.connected`; `connecting`→orange; `waitingForPairing`→yellow `openclaw.status.pairing`; `disconnected`→gray; `error(msg)`→red, shows `msg`); when `waitingForPairing` an extra row with `exclamationmark.triangle.fill` + `openclaw.pairing.hint` | `:21-46`, `:130-148` |
| header `"Gateway"`, footer `openclaw.gateway.help` | `Host` `TextField("127.0.0.1")` (no autocap, `.URL` keyboard); `Port` `TextField("18789")` (`.numberPad`); `SecureField("Gateway Token")` | `:49-73` |
| actions | if connected: destructive `openclaw.disconnect` (`wifi.slash`) → `disconnect()`; else `openclaw.connect` (`wifi`) → `saveAndConnect()`, disabled when host empty | `:76-99` |
| header `openclaw.capabilities`, footer `openclaw.capabilities.desc` | `InfoRow("Node ID", connected ? "rayban-node" : "-")`, `InfoRow("Commands", "camera.snap, device.status, device.info")` | `:102-109` |
| toolbar | `done` button dismisses | `:113-119` |

`onAppear` loads `host = gatewayHost`, `portText = "\(gatewayPort)"`, `token = loadGatewayToken() ?? ""` (`:120-124`). `saveAndConnect()` (`:150-157`): `gatewayHost = host`, `gatewayPort = Int(portText) ?? 18789`, `if !token.isEmpty { saveGatewayToken(token) }` (an emptied field does **not** delete the stored token), `connect()` (which persists host/port/enabled via `saveSettings()`, `OpenClawNodeService.swift:216-220`).

### 5.3 Persistence keys

| Store | Key | Type / default | Source |
|---|---|---|---|
| `UserDefaults` | `openclaw_enabled` | Bool, default `false`; **never set to true anywhere** (only read at init and re-written unchanged in `saveSettings`) → the `MainAppView` auto-connect branch is dead code | `OpenClawNodeService.swift:42, 217`; `MainAppView.swift:55-57` |
| `UserDefaults` | `openclaw_host` | String, default `"127.0.0.1"` | `:43, 218` |
| `UserDefaults` | `openclaw_port` | Int, `0` → `18789` | `:44, 219, 616-620` |
| Keychain | service `com.smartview.glassai.openclaw`, account `gateway_token` | UTF-8 token; empty string deletes | `:60-61, 155-182` |
| Keychain | service `com.smartview.glassai.openclaw.device`, account `ed25519_private_key` | 32-byte raw seed, `AfterFirstUnlock` | `OpenClawDeviceIdentity.swift:71-72, 114-128` |
| Keychain (existing) | service `com.smartview.glassai.apikey`, account `alibaba-beijing-api-key` / `alibaba-singapore-api-key` | ASR key | `Utils/APIKeyManager.swift:13-17` |

Effective auto-connect triggers: `TurboMetaHomeView.onAppear` when `connectionState == .disconnected && loadGatewayToken() != nil` (`TurboMetaHomeView.swift:161-165`) and `OpenClawChatView.onAppear` (`:231-234`). A gateway without a token therefore never auto-connects.

### 5.4 Integration into existing screens

- `SettingsView.swift:307-333`: new section header `settings.integrations` with a row `link.circle.fill` (purple) + `"OpenClaw"` + status dot/text (`openClawStatusColor/Text`, `:408-424`; `waitingForPairing` shows yellow but text falls back to `openclaw.status.disconnected`) → `.sheet(OpenClawSettingsView())` (`:398-400`). About section now `2.0.0` / SDK `0.5.0` (`:336-337`).
- `TurboMetaHomeView.swift:86-93`: row-2 `FeatureCard(title: "OpenClaw", subtitle: connected ? home.openclaw.connected : home.openclaw.subtitle, icon: "link.circle.fill", gradient: [.purple, .indigo])` replacing LeanEat, which moved to a `FeatureCardWide` in row 5 (`:117-124`). Card opens `.fullScreenCover { OpenClawChatView(streamViewModel:) }` (`:151-153`).
- `MainAppView.swift:50-57`: after `quickVisionManager.setStreamViewModel`, creates and installs the command router; conditional auto-connect on `isEnabled`.

### 5.5 String resources added (`en.lproj/Localizable.strings:428-450`, `zh-Hans.lproj/Localizable.strings:428-450`)

| Key | en | zh-Hans |
|---|---|---|
| `settings.integrations` | Integrations | 集成 |
| `openclaw.status.connected` | Connected | 已连接 |
| `openclaw.status.connecting` | Connecting... | 连接中... |
| `openclaw.status.pairing` | Waiting for pairing | 等待配对 |
| `openclaw.status.disconnected` | Not connected | 未连接 |
| `openclaw.connect` | Connect to Gateway | 连接 Gateway |
| `openclaw.disconnect` | Disconnect | 断开连接 |
| `openclaw.pairing.hint` | Run 'openclaw devices approve' in your terminal to complete pairing | 请在 OpenClaw 终端执行 openclaw devices approve 完成配对 |
| `openclaw.gateway.help` | Enter the address and port of the device running OpenClaw Gateway. Default is 127.0.0.1:18789 | 输入运行 OpenClaw Gateway 的设备地址和端口。本机默认为 127.0.0.1:18789 |
| `openclaw.capabilities` | Device Capabilities | 设备能力 |
| `openclaw.capabilities.desc` | When connected, OpenClaw AI can capture photos through the glasses, check device status, etc. | 连接后，OpenClaw AI 可以通过眼镜拍照、获取设备状态等 |
| `home.openclaw.subtitle` | OpenClaw | OpenClaw |
| `home.openclaw.connected` | Connected | 已连接 |
| `openclaw.chat.placeholder` | Type a message... | 输入消息... |
| `openclaw.chat.photoprompt` | Please look at this photo from my glasses | 请看这张眼镜拍摄的照片 |
| `openclaw.chat.snap` | Snap & Send | 拍照发送 |
| `openclaw.chat.sending` | Sending... | 发送中... (unused) |
| `openclaw.chat.noframe` | Cannot get glasses frame, please check connection | 无法获取眼镜画面，请确认眼镜已连接 |
| `openclaw.chat.noapikey` | Please configure Alibaba API Key in Settings first | 请先在设置中配置阿里云 API Key |
| `openclaw.chat.sendvoice` | Send | 发送 |
| `openclaw.chat.text` | Text | 键盘 |
| `openclaw.chat.listening` | Listening... | 正在聆听... |

Also reused existing keys `cancel` ("Cancel"/"取消", line 10) and `done` ("Done"/"完成", line 12). Non-localized literals in UI: `"OpenClaw"`, `"Status"`, `"Host"`, `"Port"`, `"Gateway Token"`, `"Gateway"`, `"Node ID"`, `"Commands"`, and the Chinese error `"连接失败，已重试 5 次"` (`OpenClawNodeService.swift:556`).

---

## 6. Third-party dependencies

None added for OpenClaw. `project.pbxproj` diff only adds the seven source files, sets signing/team, `SWIFT_STRICT_CONCURRENCY = minimal`, and switches the existing HaishinKit (RTMP) package to the fork `https://github.com/Turbo1123/HaishinKit.swift` branch `fix-concurrency` (unrelated to OpenClaw). Frameworks used: `Foundation` (`URLSessionWebSocketTask`, `JSONSerialization`), `CryptoKit` (`Curve25519.Signing`, `SHA256`), `Security` (Keychain), `AVFoundation` (`AVAudioEngine`, `AVAudioConverter`, `AVAudioSession`), `UIKit` (`UIImage.jpegData`, `UIGraphicsImageRenderer`, `UIDevice`), `MWDATCore` (import only in router).

---

## 7. Interaction with existing services

- **Direct coupling is limited to two objects**: `StreamSessionViewModel` (frames, stream start/stop, device presence) and `APIKeyManager` (Alibaba key). `grep OpenClaw` outside the OpenClaw folder hits only `MainAppView`, `SettingsView`, `TurboMetaHomeView`.
- **LiveAIManager / TTSService / VisionAPIService / RTMPStreamingService**: not referenced by any OpenClaw file. Indirect interactions: (a) `OpenClawASRService` and LiveAI/TTS all reconfigure the shared `AVAudioSession` and use `AVAudioEngine`, so running OpenClaw voice input concurrently with Live AI or TTS playback would conflict; (b) `camera.snap` (node path) and `snapAndSend` (UI path) start the single shared `StreamSession`, which is also what LiveAI/RTMP/QuickVision use, so an invoke arriving while another feature streams will just reuse the running stream, and the chat path's "stop if I started it" logic can stop a stream another feature relies on if it raced.
- Commit-wide stability fixes that the port should mirror (not OpenClaw-specific but shipped together):
  - WebSocket services now `urlSession?.invalidateAndCancel()` on disconnect (leak fix) and use `[weak self]` in send/receive closures — `GeminiLiveService.swift`, `LiveTranslateService.swift`, `OmniRealtimeService.swift` hunks.
  - Session configuration is sent from `didOpenWithProtocol` instead of a 0.5 s delay (`OmniRealtimeService.swift`, `LiveTranslateService.swift` hunks) — same pattern `OpenClawNodeService` uses for starting the receive loop.
  - `StreamSessionViewModel` frame listener drops frames while one is being converted (`isProcessingFrame`, `StreamSessionViewModel.swift:66, 112-126`) — the Android `WearablesViewModel.handleVideoFrame` (`viewmodels/WearablesViewModel.kt:411-441`) has no such guard.
  - `TTSService` reuses its playback engine; `RTMPStreamingService` adds an `NSLock`; RTMP stream key moved from `UserDefaults` to Keychain (`RTMPStreamingViewModel.swift` hunk) — the Android app already keeps `rtmp_url` in `EncryptedSharedPreferences` (`utils/APIKeyManager.kt:217-224`).
  - `StreamView.onDisappear` now calls `viewModel.cleanup()`; `StreamSessionError` gained `.hingesClosed` / `.thermalCritical` (DAT 0.5.0).

---

## 8. Android porting map

### 8.1 Existing Android building blocks to reuse

| Need | Existing Android piece | Source |
|---|---|---|
| WebSocket client | OkHttp 4.12.0 `OkHttpClient.newWebSocket(request, WebSocketListener)`; pattern with `readTimeout(0)` in `OmniRealtimeService` | `gradle/libs.versions.toml` `okhttp = "4.12.0"`; `services/OmniRealtimeService.kt:103-146` |
| JSON | Gson 2.10.1 (`gson.toJson(map)`, `gson.fromJson(text, JsonObject::class.java)`) | `libs.versions.toml` `gson = "2.10.1"`; `OmniRealtimeService.kt:363-412` |
| Secure storage | `EncryptedSharedPreferences` (`turbometa_secure_prefs`, AES256_GCM) wrapped by `APIKeyManager.getInstance(context)`; already stores non-secret settings (`ai_model`, `video_quality`, `rtmp_url`) too | `utils/APIKeyManager.kt:17-56, 188-224` |
| Plain prefs | `context.getSharedPreferences("language_prefs", MODE_PRIVATE)` pattern | `managers/LanguageManager.kt:33-51` (DataStore is a declared dependency but unused in code) |
| Alibaba key | `apiKeyManager.getAPIKey(APIProvider.ALIBABA, endpoint)` / `getAPIKey()` for current endpoint | `utils/APIKeyManager.kt:94-102, 161-163`; `managers/APIProviderManager.kt` (`AlibabaEndpoint.BEIJING/SINGAPORE`, `APIProvider.ALIBABA`) |
| Glasses frames | `WearablesViewModel.currentFrame: StateFlow<Bitmap?>`, `hasActiveDevice: StateFlow<Boolean>`, `streamState: StateFlow<StreamState>` (`Stopped/Waiting/Streaming/Error`), `startStream()` (non-suspending, creates `Wearables.startStreamSession(app, deviceSelector, StreamConfiguration(videoQuality, 24))`), `stopStream()`, `takePhoto()` (DAT `capturePhoto()`), `onFrameReceived` callback | `viewmodels/WearablesViewModel.kt:80-99, 253-337, 343-368, 373-405` |
| Wait-for-frame pattern | `QuickVisionScreen`: `startStream()`, `takePhoto()`, poll `capturedPhoto` ≤30×, fallback `currentFrame`, `stopStream()` | `ui/screens/QuickVisionScreen.kt:181, 206-231` |
| Mic capture | `AudioRecord(MediaRecorder.AudioSource.MIC or VOICE_COMMUNICATION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)` loop on `Dispatchers.IO` | `OmniRealtimeService.kt:163-228`; `GeminiLiveService.kt:40-41, 264-274` (already 16 kHz) |
| Glasses mic (HFP) | `BluetoothAudioManager.switchAudioSource(AudioSource.BLUETOOTH_MIC)` → `startBluetoothSco()` (`MODE_IN_COMMUNICATION`); HFP forces 16 kHz | `managers/BluetoothAudioManager.kt:35-38, 171-209`; `OmniRealtimeService.kt:163-168` |
| Navigation | `sealed class Screen(route)`, `NavHost` in `TurboMetaNavigation` | `ui/navigation/Navigation.kt:25-38, 106-247` |
| Home card | private `FeatureCard(modifier,title,subtitle,icon,gradientColors,isPlaceholder,isLoading,onClick)` and `FeatureCardWide(...)`; `checkCameraPermissionAndNavigate {}` helper | `ui/screens/HomeScreen.kt:275-296, 350-365, 392-402` |
| Settings section | private `SettingsSection(title){}` + `SettingsItem(icon,title,subtitle,onClick,isDestructive,subtitleColor)`; About shows `"1.5.0"` | `ui/screens/SettingsScreen.kt:611-680, 419-425` |
| Strings | `res/values/strings.xml`, `res/values-zh-rCN/strings.xml`; `cancel` exists (`values/strings.xml:204`), no generic `done`/`send`/`connect` | — |
| Runtime permissions | `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `INTERNET` requested in `MainActivity.PERMISSIONS` | `MainActivity.kt:33-38`; `AndroidManifest.xml:6-21` |

### 8.2 Proposed Kotlin classes (package `com.smartview.glassai`)

```
services/openclaw/OpenClawModels.kt
    enum class OpenClawConnectionState { … }  -> sealed class: Disconnected, Connecting, WaitingForPairing, Connected, Error(msg)
    data class OpenClawNodeInvokeRequest(id: String, command: String, params: JsonObject?, timeoutMs: Int?)
    data class OpenClawNodeInvokeResult(id: String, nodeId: String, ok: Boolean, payload: JsonObject?, error: OpenClawError?)
    data class OpenClawError(code: String?, message: String?)
    data class CameraSnapParams(maxWidth: Int = 1600, quality: Double = 0.8, format: String = "jpg") { companion fun from(JsonObject?) }
    data class OpenClawChatMessage(id: String = UUID, role: String, text: String, image: Bitmap?, timestamp: Long)

services/openclaw/OpenClawDeviceIdentity.kt
    class OpenClawDeviceIdentity(deviceId: String, publicKeyBase64Url: String, private val signer: …) { fun sign(clientId, clientMode, role, scopes, signedAtMs, token, nonce, platform, deviceFamily): String }
    object OpenClawDeviceIdentityStore { fun loadOrCreate(context): OpenClawDeviceIdentity }   // EncryptedSharedPreferences key "openclaw_ed25519_private_key" (base64 of 32-byte seed)
    fun base64UrlEncode(bytes) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

services/openclaw/OpenClawNodeService.kt
    class OpenClawNodeService private constructor(appContext) { companion getInstance(context) }   // mirrors APIKeyManager singleton style
    val connectionState: StateFlow<OpenClawConnectionState>
    var gatewayHost: String; var gatewayPort: Int
    fun connect(); fun disconnect(); fun saveGatewayToken(String); fun loadGatewayToken(): String?
    fun sendChatMessage(text: String, image: Bitmap? = null)
    var onChatEvent: ((text: String, isFinal: Boolean) -> Unit)?   // replace the "[[FINAL]]" string prefix hack with a Boolean
    fun setCommandRouter(router: OpenClawCommandRouter)
    // internals: OkHttp WebSocket, CoroutineScope(Dispatchers.IO + SupervisorJob()), tick Job (15 s), reconnect Job (2/4/8/16/30 s, max 5)

services/openclaw/OpenClawCommandRouter.kt
    class OpenClawCommandRouter(private val frames: GlassesFrameProvider, private val nodeId: String)
    interface GlassesFrameProvider { val hasActiveDevice: Boolean; val isStreaming: Boolean; val streamStatus: String; val currentFrame: Bitmap?; suspend fun ensureStreaming(timeoutMs: Long): Boolean }
    -> implemented by WearablesViewModel (or a thin adapter) so the router is testable without the ViewModel
    suspend fun handleCommand(req: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult

services/openclaw/FunASRService.kt   (name it after the engine; it is not OpenClaw-specific)
    class FunASRService(apiKey: String, endpoint: AlibabaEndpoint, context: Context?)  // consider honoring endpoint; iOS hard-codes Beijing
    var onPartialResult/onFinalResult/onError; fun start(); fun stop(); fun switchAudioSource(BluetoothAudioManager.AudioSource)

viewmodels/OpenClawViewModel.kt (AndroidViewModel)
    messages: StateFlow<List<OpenClawChatMessage>>, pendingResponse, inputText, isSending, isListening, asrText, asrPartial, showTextInput
    fun sendText(); suspend fun snapAndSend(wearablesViewModel); fun toggleListening(); fun sendASRText(); fun cancelASR()

ui/screens/OpenClawChatScreen.kt, ui/screens/OpenClawSettingsScreen.kt
ui/navigation/Navigation.kt: Screen.OpenClaw("openclaw"), Screen.OpenClawSettings("openclaw_settings")
```

### 8.3 Behavior-by-behavior mapping and iOS-only assumptions to replace

| iOS behavior | Android replacement |
|---|---|
| `URLSessionWebSocketTask` + delegate `didOpen/didClose/didCompleteWithError` | `OkHttpClient.Builder().readTimeout(0, MILLISECONDS).connectTimeout(10, SECONDS).proxy(Proxy.NO_PROXY)` (mirrors `connectionProxyDictionary = [:]`); `WebSocketListener.onOpen/onMessage(String)/onMessage(ByteString)/onClosed/onFailure`. No receive loop needed. |
| `maximumMessageSize = 16 MiB` | OkHttp has no inbound cap by default for text frames; nothing to do. |
| `ws://` only + `NSAllowsLocalNetworking` | `ws://` is cleartext; with `targetSdk 34` Android blocks it unless you add `android:usesCleartextTraffic="true"` on `<application>` or, better, a `res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="true"/>` (or restrict to private ranges) referenced by `android:networkSecurityConfig`. Current manifest has neither (`AndroidManifest.xml:23-31`). OkHttp honors the platform cleartext policy and throws `UnknownServiceException: CLEARTEXT communication … not permitted`. Consider also accepting `wss://` via a scheme toggle. |
| Token as `?token=` query (raw) | Same, but URL-encode with `Uri.encode(token)`; also put it in `params.auth.token`. |
| Keychain for token / private key | `EncryptedSharedPreferences` via `APIKeyManager` (add `saveOpenClawToken/getOpenClawToken`, `saveOpenClawDeviceKey/getOpenClawDeviceKey`) — Android Keystore does not support Ed25519 so the seed must live in encrypted prefs. |
| `UserDefaults` `openclaw_host/openclaw_port/openclaw_enabled` | Same keys in `turbometa_secure_prefs` (consistent with `rtmp_url`) or a plain `openclaw_prefs` SharedPreferences. Drop `openclaw_enabled` or actually set it on successful connect (iOS never sets it). |
| `CryptoKit Curve25519.Signing` | Add a library: **Tink** `com.google.crypto.tink:tink-android` (`Ed25519Sign.KeyPair.newKeyPair()` → `getPrivateKey()`/`getPublicKey()` raw 32-byte arrays; `Ed25519Sign(privateKey).sign(bytes)`) or **BouncyCastle** `org.bouncycastle:bcprov-jdk18on` (`Ed25519PrivateKeyParameters(seed)`, `Ed25519Signer`). Do not rely on `java.security.Signature.getInstance("Ed25519")` — not guaranteed on Android's Conscrypt. `deviceId = MessageDigest.getInstance("SHA-256").digest(pubKey).toHex()`. |
| `identifierForVendor` → `nodeId "rayban-xxxxxxxx"` | `Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID).take(8).lowercase()`; keep the `"rayban-"` prefix. |
| `client.id = "openclaw-ios"`, `platform = "ios"`, `modelIdentifier = UIDevice.model` | `"openclaw-android"`, `"android"`, `Build.MODEL`; keep `mode = "node"`, `role`, `scopes`, `caps`, `commands`, protocol 3 identical. `platform` feeds the signature (normalized), so the same string must be used in both the signed payload and the JSON. |
| `device.info` payload | `platform:"Android"`, `osVersion: Build.VERSION.RELEASE`, `sdkVersion: "0.4.0"` (or the mwdat version from `libs.versions.toml`), fix `appVersion` to `BuildConfig.VERSION_NAME`. |
| `DispatchQueue.main` / `@MainActor` | `withContext(Dispatchers.Main)` for StateFlow updates is unnecessary (StateFlow is thread-safe); run router on `Dispatchers.Main.immediate` only if it touches the ViewModel. |
| `Task.sleep` polling loops | `withTimeoutOrNull(5000) { wearablesViewModel.currentFrame.filterNotNull().first() }`. |
| `UIImage.jpegData(compressionQuality:)` / `UIGraphicsImageRenderer` resize | `Bitmap.createScaledBitmap(bmp, w, h, true)` + `bmp.compress(JPEG, (quality*100).toInt(), stream)`; base64 via `Base64.encodeToString(bytes, Base64.NO_WRAP)` (NO_WRAP is essential — iOS `base64EncodedString()` has no line breaks). |
| `isStreaming` = `streamingStatus != .stopped` | Use `streamState.value is StreamState.Streaming` for "frames available"; note Android's `WearablesViewModel.isStreaming` (`:98-99`) is a *navigation flag*, not stream state. |
| `handleStartStreaming()` does the DAT permission check | Android `startStream()` does not; call `wearablesViewModel.checkCameraPermission()` first (`WearablesViewModel.kt:244-247`) or route through the `HomeScreen.checkCameraPermissionAndNavigate` style flow; a permission request needs an Activity (`Wearables.RequestPermissionContract`, `MainActivity.kt:60-77`), so a background node-invoke cannot prompt — return an error code (e.g. `PERMISSION_REQUIRED`) instead. |
| Foreground requirement | Android can keep streaming in a foreground service (upstream 0.9.0 `samples/CameraAccess/.../stream/StreamingService.kt:32-38`, wake lock + `foregroundServiceType`); the app already declares `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MICROPHONE` and two FGS (`AndroidManifest.xml:16-17, 68-78`). A `dataSync`/`camera` FGS could make node-mode `camera.snap` work with the app backgrounded — this is a product decision (see §9). |
| `AVAudioSession .playAndRecord + .allowBluetooth` | Explicit choice: `AudioRecord` at 16 kHz mono PCM16 from `MediaRecorder.AudioSource.MIC` (phone) or `VOICE_COMMUNICATION` after `BluetoothAudioManager.startBluetoothSco()` (glasses HFP mic). Reuse the LiveAI screen's audio-source toggle (`ui/screens/LiveAIScreen.kt:632-660`). No resampling needed (request 16 kHz directly). |
| `AVAudioConverter` Float32→Int16 | Not needed; `AudioRecord` with `ENCODING_PCM_16BIT` yields little-endian PCM16 directly; send `webSocket.send(ByteString.of(buf, 0, n))`. |
| Fun-ASR endpoint hard-coded Beijing | Use `wss://dashscope.aliyuncs.com/api-ws/v1/inference` for `BEIJING` and `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference` for `SINGAPORE` (mirrors `AlibabaEndpoint.websocketURL` pattern, `managers/APIProviderManager.kt:44-48`); verify that `fun-asr-realtime` is available on the intl endpoint before enabling. |
| `"[[FINAL]]"` prefix protocol between service and view | Replace with a typed callback `(text, isFinal)` or a `SharedFlow<ChatEvent>`. |
| `OpenClawChatMessage` in `@State` | `MutableStateFlow<List<OpenClawChatMessage>>` in the ViewModel; keep in-memory (parity) or optionally persist via `ConversationStorage`. |
| SwiftUI `fullScreenCover` | `navController.navigate(Screen.OpenClaw.route)`; settings as a nested route. |
| `.localized` keys | `R.string.openclaw_*` (see §8.4). |
| Chinese literal `"连接失败，已重试 5 次"` | `R.string.openclaw_error_max_retries` with `%d`. |

### 8.4 String resources to add (`values/strings.xml`, `values-zh-rCN/strings.xml`)

`settings_integrations`, `openclaw_status_connected`, `openclaw_status_connecting`, `openclaw_status_pairing`, `openclaw_status_disconnected`, `openclaw_connect`, `openclaw_disconnect`, `openclaw_pairing_hint`, `openclaw_gateway_help`, `openclaw_capabilities`, `openclaw_capabilities_desc`, `feature_openclaw_title` ("OpenClaw"), `feature_openclaw_subtitle`, `feature_openclaw_connected`, `openclaw_chat_placeholder`, `openclaw_chat_photoprompt`, `openclaw_chat_snap`, `openclaw_chat_noframe`, `openclaw_chat_noapikey`, `openclaw_chat_sendvoice`, `openclaw_chat_text`, `openclaw_chat_listening`, plus `openclaw_host`, `openclaw_port`, `openclaw_token`, `openclaw_node_id`, `openclaw_commands`, `done`, `openclaw_error_max_retries`. Values: copy the en/zh columns from §5.5.

### 8.5 Manifest / Gradle changes

- `AndroidManifest.xml`: `android:networkSecurityConfig="@xml/network_security_config"` (cleartext for LAN) — required for `ws://`.
- `build.gradle.kts` dependencies: add Ed25519 provider (Tink or BouncyCastle). Nothing else — OkHttp/Gson/security-crypto already present.
- Optional: bump `mwdat` from `0.4.0`; if you move to 0.9.0 the streaming API is entirely different (`Wearables.createSession(selector)` → `DeviceSession.start()` → `session.addCamera(StreamConfiguration)` → `camera.stream.start()`, frames on `camera.stream.videoStream`, `camera.stream.capturePhoto(): DatResult<PhotoData, CaptureError>`, `StreamState` enum with `STREAMING/STOPPED/CLOSED`; `Wearables.startStreamSession` and `DeviceSession.addStream` are removed) — `CHANGELOG.md:8-32, 97-105, 155-167`; `skills/camera-streaming/SKILL.md:27-43, 85-105`; `samples/CameraAccess/.../camera/CameraViewModel.kt:158-168, 268-305, 433-458`. The router only needs a `Bitmap` provider, so keep the DAT upgrade decoupled from the OpenClaw port.

### 8.6 Keep-identical checklist (protocol compatibility)

1. Frame envelopes `req/res/event`, field names `type,id,method,params,ok,payload,error.code,error.message`.
2. `connect` params structure incl. `minProtocol/maxProtocol = 3`, `client{id,displayName,version,mode,platform,modelIdentifier}`, `role`, `scopes`, `caps`, `commands`, `auth{token}`, `device{id,publicKey,signature,signedAt,nonce}`.
3. Signature string `v3|deviceId|clientId|clientMode|role|scopes(',')|signedAtMs|token|nonce|platform|deviceFamily` with `normalizeForAuth` on platform/deviceFamily, Ed25519 over UTF-8 bytes, base64url no padding; `deviceId = sha256hex(rawPubKey)`; `publicKey = base64url(rawPubKey)`.
4. `chat.send` params (`sessionKey`, `message`, `idempotencyKey`, `attachments[{type,mimeType,content}]`) and `chat` event parsing (`payload.state`, `payload.message.content[].text`).
5. Invoke inbound shapes (`event:node.invoke.request` with `params{id,command,params|paramsjson,timeoutMs|timeoutms}` and `req:node.invoke`) and outbound `node.invoke.result` with `payloadjson` string.
6. `tick` every 15 s with `ts`; `NOT_PAIRED` → pairing state; `UNSUPPORTED` reply for unknown requests.
7. Command result payload shapes for `camera.snap`, `camera.list`, `device.status`, `device.info`.

---

## 9. Open questions / decisions for the product owner

1. Should Android implement node-mode `camera.snap` in a foreground service so the AI can take photos while the app is backgrounded (possible on Android, impossible on iOS)?
2. Should the Fun-ASR service honor the Singapore endpoint (iOS hard-codes Beijing), and should Fun-ASR results auto-send (README claims auto-send, code requires a tap)?
3. Keep chat history in memory only (iOS parity) or persist to `ConversationStorage`/Records tab?
4. Offer a `wss://` option and/or a "trust self-signed" toggle for Tailscale/remote setups, or stay `ws://`-only like iOS?
5. Should the Android port target DAT 0.4.0 (current) or jump to 0.9.0 (breaking streaming API)?
6. Default glasses-mic vs phone-mic for ASR (iOS lets the OS route pick; Android must choose explicitly).

## 10. Known iOS quirks worth *not* porting

- `openclaw_enabled` never becomes true (dead auto-connect branch, `MainAppView.swift:55-57`).
- Every `ok:true` response is treated as "hello ok" and restarts the tick timer (`OpenClawNodeService.swift:282-286, 372-380`).
- Emptying the token field does not delete the stored token (`OpenClawSettingsView.swift:153-155`).
- `device.info.appVersion` says `1.5.0` while connect says `2.0.0` (`OpenClawCommandRouter.swift:165` vs `OpenClawNodeService.swift:342`).
- Settings shows Node ID `"rayban-node"` while the real id is `rayban-<8 hex>` (`OpenClawSettingsView.swift:103` vs `OpenClawNodeService.swift:85`).
- Node-mode `camera.snap` starts the stream and never stops it (`OpenClawCommandRouter.swift:46-60`).
- Reconnect attempts may double-count because two delegate callbacks fire for one failure (`OpenClawNodeService.swift:598-611`).
- Token is appended to the URL without percent-encoding (`OpenClawNodeService.swift:192-193`).
