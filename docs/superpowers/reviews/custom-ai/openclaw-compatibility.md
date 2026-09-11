# OpenClaw compatibility sidecar

Reviewed 2026-09-11 in the shared `android-v2` checkout, initially at `f470179`. No commits, pushes, Gradle runs, user gateway upgrades, or gateway configuration/secret reads were performed by this sidecar. Other contributors' changes and existing untracked files were preserved.

## Released version and evidence

GitHub's official releases API resolved `releases/latest` to **`v2026.9.4`**, a non-draft, non-prerelease release published **2026-09-11T03:46:22Z**. Its annotated tag resolves to commit **`3a9d69db306cd7f081e06254cb89c4bcc14a7107`**. The tag name and publication date differ; neither has been inferred from the calendar. [Release](https://github.com/openclaw/openclaw/releases/tag/v2026.9.4), [release API](https://api.github.com/repos/openclaw/openclaw/releases/latest), [tag object](https://api.github.com/repos/openclaw/openclaw/git/tags/8bec206f3c1f787e1e9c45cfd34d3de2a78c7b8e).

The live [handshake documentation](https://docs.openclaw.ai/gateway/protocol/handshake) was a discovery lead. Implementation decisions below come from the **release tag**, not live `main`. The live example's `ios-node` must not be copied into Android: the released closed registry explicitly includes **`openclaw-android`**, which this client already used correctly.

| Contract | Release source and decision |
| --- | --- |
| Protocol version | [version.ts](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/version.ts): current/general minimum 4; authenticated node minimum 3. [Admission](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server/ws-connection/connect-admission.ts) limits previous-protocol admission to node role and node mode. Default sends 4–4; explicit compatibility sends 3–4. |
| Client identity | [client-info.ts](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/client-info.ts): retain `openclaw-android`, mode `node`, platform `android`; do not substitute an ID from a documentation example. |
| Auth proof | [device-auth.ts](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-client/src/device-auth.ts), [proof verifier](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server/ws-connection/connect-device-proof.ts), [signature binding](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server/ws-connection/handshake-auth-helpers.ts): protocol 4 still uses the **v3** Ed25519 payload. Bind client, role, scopes, timestamp, token, nonce, platform and family; normalize metadata with JavaScript trim + ASCII-only lowercase, preserving punctuation/spaces/non-ASCII case. Never retry with an unsigned or v2 proof. |
| Token selection | [connect-auth.ts](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-client/src/connect-auth.ts) and [frames.ts](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/schema/frames.ts): use `auth.deviceToken` for cached credentials and sign that token; initial enrollment uses the configured shared `auth.token`. No official-mode URL token. |
| Pairing and hello | [hello emission](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server/ws-connection/connect-hello.ts), [error details](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/connect-error-details.ts): validate the matching hello's type/protocol/node role/empty scopes, save its node token, recognize `NOT_PAIRED` or `details.code=PAIRING_REQUIRED`, retain pairing wait after close, reconnect explicitly. |
| Node identity and results | [node connect](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server/ws-connection/connect-node-session.ts), [node schemas](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/schema/nodes.ts): modern result `nodeId` is the signed device identity, not `rayban-…`; parse `paramsJSON` and emit `payloadJSON`. [Actual invoke builder](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/node-invoke-request.ts) emits JSON null for omitted parameters despite the stricter schema; accept absent/null/encoded-null parameters as no arguments. Reject malformed non-null parameters. |
| Node chat authorization | [Role policy](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/role-policy.ts) separates node methods from operator methods. [Core descriptors](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/methods/core-descriptors.ts) classify `node.event` as node-only and `chat.send` as operator-write. Use `node.event` with `chat.subscribe` and `agent.request`; no operator connection/token/scopes are needed for this path. |
| Node chat behavior | [nodes.event.ts](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server-methods/nodes.event.ts) checks current connection/pairing before [server-node-events.ts](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server-node-events.ts) dispatches agent text/images or subscriptions. The latter supports `sessionKey`, `message`, image `attachments`, `deliver` and `receipt`. Send `deliver=false`, `receipt=false` so no messaging-channel delivery is requested. |
| Chat output | [subscriptions](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server-node-subscriptions.ts), [chat emission](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server-chat.ts), [chat schema](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/schema/logs-chat.ts): subscribe before allowing sends; accumulate protocol-4 `deltaText`, honor `replace`, retain v3 snapshot deltas, finish on final/error/aborted, and filter session/run/duplicate sequence. A final can share its sequence with a flushed delta. |
| Heartbeat and startup | [Frame schema](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/schema/frames.ts) defines `tick` as a server event, not a client RPC. Keep OkHttp ping/pong, disable custom client ticks in official modes. [Startup error contract](https://github.com/openclaw/openclaw/blob/v2026.9.4/packages/gateway-protocol/src/startup-unavailable.ts) allows bounded retries for explicit retryable `UNAVAILABLE` / `startup-sidecars`. |

## Profiles and behavior

| Saved profile | Handshake | Chat and wire behavior |
| --- | --- | --- |
| `CURRENT` (default) | 4–4, `node`, scopes `[]` | Official node events for chat; canonical JSON field names, identity node ID, paired tokens. |
| `ALLOW_NODE_V3` | 3–4, `node`, scopes `[]` | Same signed auth and role; official older-node overlap is explicitly allowed. Not a promise to support every historical v3 gateway. |
| `LEGACY_CUSTOM_V3` | 3–3, `operator`, read/write scopes | Retains the former custom combined node/operator port, URL token, client tick RPC, lowercase JSON fields, `rayban-…` node ID, `chat.send` and replace-style chat events. Explicit selection only; not an official protocol-4 solution. |

Configuration is captured for each socket. Protocol/auth rejection never selects a weaker profile, broadens scopes, changes transport scheme, removes a proof, or retries a revoked device token with the shared secret. Cached node tokens take precedence for paired reconnects. Additional `hello.auth.deviceTokens` operator/bootstrap grants are not consumed.

HTTP redirects are disabled on both the LAN factory and the actual WebSocket client derived from any injected client (`followRedirects=false`, `followSslRedirects=false`). A redirect response cannot open a second origin and answer its challenge using the original endpoint's shared or device credential. An actual gateway address change must be configured explicitly.

Only the existing camera/device command set is advertised and dispatched. No custom AI, execution, shell, notification or other command was added. Async invoke results remain attached to their originating socket and cannot leak onto a replacement connection. Pre-hello invokes and callbacks from replaced sockets are ignored.

## Chat readiness and limits

`connectionState` remains the existing UI-facing flow, but Connected now means **node authentication plus acknowledged chat subscription**. `chatConnectionState` aliases that flow. `nodeConnectionState` independently exposes the authenticated camera transport. Chat subscription/turn errors leave the node connected and usable for camera RPCs. A forced Connect retries a failed chat subscription on that same socket; a full disconnect/reconnect repeats the signed handshake and subscription.

Chat uses the gateway snapshot's default agent with a `turbometa-chat-<UUID>` session key generated for each subscription. Turns within an active subscription share gateway context. A resubscription or socket reconnect starts fresh gateway context while the existing visible local history remains intact. On same-socket recovery, the client sends the released `chat.unsubscribe` event for the previous key before subscribing to the new key. This deliberate boundary prevents an uncertain old turn's late final from being accepted as a new turn's answer: the release binds `runId` to `sessionId`, so client request IDs cannot distinguish those turns. Both subscription operations and that run binding are in the pinned [node ingress source](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/server-node-events.ts).

Protocol-4 deltas are converted back into the existing replace-style `OpenClawChatEvent` API, preserving parent UI behavior. Only one node-agent turn is in flight at a time. Text is limited to the upstream 20,000-character ingress limit; accumulated display text is bounded. No inference request is automatically replayed after a timeout or reconnect.

The released `agent.request` ingress acknowledges before detached inference completes and does not return a durable, client-correlated run receipt. Some upstream validation/inference failures only log server-side. This client therefore distinguishes RPC acknowledgement from a final reply and reports an error after a bounded two-minute wait without a terminal event. This is a documented limitation of the chosen supported node path, not a claim of full operator UI parity. It does not implement history synchronization, durable request deduplication/resumption, model selection, tools/widgets or arbitrary operator RPCs. Existing image attachments are preserved.

Chat timer continuations check their own coroutine's cancellation **inside** the shared monitor before changing state. This covers a delay that already completed but then waited for the monitor while its turn/subscription completed and a new turn began. Handshake and reconnect delays likewise recheck their coroutine's active state after acquiring the monitor.

## Persistence and parent integration

`SecureOpenClawSettingsStore` keeps new data in **`openclaw_protocol.xml`**, encrypted with the already-installed AndroidX security library. Device-token keys bind canonical endpoint (including scheme/port), Ed25519 device ID and role. The existing seed and shared token storage remain untouched. Parent was notified to exclude this file from cloud backup **and device transfer**, alongside the existing secure preferences.

Parent-owned UI wiring:

- Provide explicit labels/help for the three profiles above; default remains CURRENT.
- Re-pair: `disconnect()`, `forgetPairedDeviceToken()`, `connect(force = true)`. Forget removes only the selected endpoint/device/node credential; it does not erase the identity or alter the shared token. Approval still occurs on the gateway.
- Use `connectionState`/`chatConnectionState` for send readiness; use `nodeConnectionState` where camera-only readiness is intended. Surface `pairingRequestId` if present and existing Transport errors for chat/auth failures.
- Busy UI wiring is now implemented under the parent's explicit ownership expansion: `OpenClawViewModel.isChatBusy` exposes the service flow; text input/send/IME, voice Send and Snap are disabled during a turn or local capture. Starting the mic is also gated while busy, including late permission callbacks; stopping an already-running recording remains available. Node-agent ingress deliberately serializes turns because it does not return a correlated run receipt. Busy clears on final/error/timeout/disconnect.

The ViewModel now clears a typed/voice draft and adds its user bubble/display card only after `sendChatMessage` returns true. A disconnected, busy or synchronously rejected send preserves the original draft and pending reply, with no ghost bubble. Snapshot readiness is checked before capture, after capture, and again at delivery after decoding. A capture from a previous screen generation remains cancelled/ignored. An accepted image send clears only the draft it actually used. Service acceptance is local queue acceptance, not proof of completed inference; gateway errors and the bounded terminal timeout remain visible through chat state.

Gateway command approval is still authoritative. The released [node command policy](https://github.com/openclaw/openclaw/blob/v2026.9.4/src/gateway/node-command-policy.ts) excludes `camera.snap` from runtime defaults unless allowed through the gateway's command policy, in addition to applicable pairing approval. Successful chat/hello is not evidence that camera capture is approved. This sidecar makes no server policy changes.

## Changed files and validation

Production, under `android/app/src/main/java/com/smartview/glassai/services/openclaw/`:

- `OpenClawNodeService.kt`
- `OpenClawModels.kt`
- `OpenClawDeviceIdentity.kt`
- `OpenClawSettingsStore.kt`
- new `OpenClawNodeChat.kt` (parent explicitly expanded scope for preserving current chat)

Matching JVM tests/fixtures: `OpenClawCompatibilityTest.kt` (new), `OpenClawNodeChatTest.kt` (new), `OpenClawNodeServiceTest.kt`, `OpenClawModelsTest.kt`, `OpenClawDeviceIdentityTest.kt`, `ScriptedGateway.kt`, `InMemoryOpenClawSettingsStore.kt`. Matching androidTest updates: `OpenClawNodeServiceInstrumentedTest.kt`, `InMemoryOpenClawSettingsStore.kt`.

The parent subsequently expanded ownership to `android/app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt`, `android/app/src/main/java/com/smartview/glassai/ui/screens/OpenClawChatScreen.kt`, and matching `android/app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt` for the busy/draft fix above. Existing accepted-send tests now establish a real CURRENT scripted handshake/subscription. Focused regressions cover busy text/voice/camera and late mic callbacks through a final gateway reply, oversized service rejection retaining both drafts, capture completion after another caller starts a turn, and disconnection during image decoding. Offline-send tests now require retained drafts and no bubble/capture.

Other owned files: this review and `tools/openclaw-stub-gateway/{gateway.mjs,gateway.test.mjs,README.md}`. No other ViewModels/screens, APIKeyManager, strings, navigation, manifest, backup XML, integration/router or unrelated files were edited by this sidecar.

Validation performed here:

- `node --test tools/openclaw-stub-gateway/gateway.test.mjs`: **4/4 pass**, including real loopback WebSocket pairing/token reconnect, canonical node result, and node-role chat final reply.
- `git diff --check` restricted to the owned tracked source/test groups: pass (only existing Git line-ending conversion warnings).
- The final busy ViewModel/screen/test group also passes `git diff --check`; no Kotlin/Gradle run was performed here. Parent was notified that the entire source/test group is ready for its single full-suite/build run.
- Parent subsequently reported **607 Debug JVM tests and 595 Release JVM tests passing, plus both APKs built**, before the three review fixes below. Those results do not validate the subsequent fixes.
- Review fixes add two-server redirect regressions for shared and paired credentials, the exact timeout A → force-connect resubscription → request B → late A final sequence, and deterministic subscription/reply timer races where an expired continuation is confirmed waiting on the shared JVM monitor before cancellation. Existing protocol/VM fixtures now read the negotiated subscription session key. The stub's unsubscribe/resubscribe test passes; the updated Kotlin group is compile-ready but its execution remains parent-owned and pending.
- Independent Node Ed25519 golden vector included in the Kotlin identity tests.
- Parent reported the first deliberate Kotlin RED compile before new persistence/profile APIs existed. Later parent compile reached the deliberately missing `nodeConnectionState` test before the chat implementation was complete. Subsequent parent execution and the current review-fix boundary are recorded above; instrumentation on the preceding APK is not validation of these latest fixes.

Parent commands from `android/` (not run here):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.*"
.\gradlew.bat :app:testDebugUnitTest --tests "com.smartview.glassai.viewmodels.OpenClawViewModelTest"
.\gradlew.bat :app:compileDebugAndroidTestKotlin
# With the existing DAT MockDeviceKit emulator/device prerequisites:
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.smartview.glassai.services.openclaw.OpenClawNodeServiceInstrumentedTest
```

Version limits: source-verified against the published tag plus local fixtures, **not live-tested against an installed released gateway**. `Get-Command openclaw` found no executable, so `--version` was not run. No user remote gateway was identified, inspected, authorized for upgrade or upgraded. Future protocol versions, remote pairing, TLS deployments and real-model inference still require integration validation by the gateway owner.

## Parent final validation

The post-review tree compiled and passed Debug 616 / Release 604 JVM tests, built both APK variants, and passed the API 31 instrumented suite (69 run, one pre-existing SDK stress ignore). The management-API test compile issue was fixed using Thread.State.BLOCKED plus the timeout continuation stack frame. Redirect, subscription isolation and stale-timeout fixes were re-reviewed without remaining scoped findings. This remains source/fixture/emulator verification, not live validation or upgrade of the user's gateway. See [final ledger](progress.md).
