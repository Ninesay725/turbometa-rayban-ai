# OpenClaw loopback protocol fixture

This fixture is an independent Node.js protocol oracle for the Android OpenClaw client. It follows the relevant contracts in the **published `v2026.9.4`** source. It is not an OpenClaw server, does not run inference, and never reads OpenClaw configuration or credentials.

Requires Node 22+; no package installation is needed.

```powershell
node --test tools/openclaw-stub-gateway/gateway.test.mjs
node tools/openclaw-stub-gateway/gateway.mjs
```

The manual fixture listens **only on `127.0.0.1:18789`**. Configure the app with host `127.0.0.1`, port `18789`, `ws`, token **`stub-shared-token`**, and **Current gateway** compatibility. For a USB-connected Android device or emulator, the test operator can forward that loopback endpoint with `adb reverse tcp:18789 tcp:18789`.

- Default: validate the node handshake and signature, issue a synthetic paired token, invoke `device.status`, acknowledge `node.event/chat.subscribe`, and return a synthetic streamed/final reply to `node.event/agent.request`.
- `--protocol3`: emulate an official v3 node endpoint; select **Allow older node protocol** in the app. Signature and role requirements are unchanged.
- `--pairing-once`: reject the first connection for each identity with `NOT_PAIRED` and close the socket. An explicit app reconnect simulates approval.
- `--camera`: replace `device.status` with `camera.snap` at width 640; this intentionally requests a camera capture on the connected app.

Issued tokens live only in the fixture process. After restarting the fixture, use the app's explicit re-pair action to forget its old fixture token. A rejected token must not silently fall back to the shared token.

The fixture rejects query-string credentials, mismatched signatures/nonces, unknown client IDs, operator roles/scopes, undeclared extra commands, lowercase `payloadjson`, and mismatched result node IDs. It does not reproduce gateway model selection, command approval policy, every event/schema, TLS, or real inference failures. Do not use real credentials or expose it beyond loopback. Legacy custom-v3 behavior is covered separately by `OpenClawNodeServiceTest`.

Primary contracts: [version](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/version.ts), [signature](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-client/src/device-auth.ts), [node schemas](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/schema/nodes.ts), [node chat handlers](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server-node-events.ts).
