# Task 6 report — `OpenClawViewModel`, chat + settings screens, navigation, Home card, Settings "Integrations", cleartext config, strings

**Status:** DONE_WITH_CONCERNS
**Branch:** `android-v2` — commit `ac4c0ea` (14 files, +1483/−10)
**Base:** `1c84af2`

---

## 1. What I implemented

All 12 brief steps, in order.

### Step 6.1 — Strings (both locales)
45 new keys appended before `</resources>` in `app/src/main/res/values/strings.xml` and
`app/src/main/res/values-zh-rCN/strings.xml`. 42 of them are the brief's verbatim list; the other
3 are the ASR-error localizations the task pointer asked for (see §3.4).

### Step 6.2 — Cleartext config, manifest, colors
- Created `app/src/main/res/xml/network_security_config.xml` with the brief's exact content
  (global `cleartextTrafficPermitted="true"` + system trust anchors), so a LAN / Tailscale
  `ws://` gateway works while every cloud endpoint stays https/wss.
- `AndroidManifest.xml`: `android:networkSecurityConfig="@xml/network_security_config"` added to the
  **`<application>`** element only (the identical `android:theme` on `<activity>` is untouched).
- `ui/theme/Color.kt`: `OpenClawColor = 0xFF7E57C2`, `OpenClawColorEnd = 0xFF3F51B5`.

### Steps 6.3 – 6.5 — TDD for the ViewModel
`app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt`: the brief's 9 tests
verbatim + 3 added for the pointers the parent task made binding (total **12**, which matches the
brief's "T6 12" expectation in Step 6.12).

### Steps 6.6 – 6.8 — UI
- `ui/components/OpenClawStatus.kt` — `openClawStatusText` / `openClawStatusColor` (brief verbatim).
- `ui/screens/OpenClawChatScreen.kt` — chat, brief verbatim except the `Send` icon (see §5).
- `ui/screens/OpenClawSettingsScreen.kt` — host / port / scheme (`ws://` ⇄ `wss://`) / token,
  status + pairing hint, Connect(force)/Disconnect, node id + commands (brief verbatim).

### Steps 6.9 – 6.11 — Navigation, Home card, Settings section
- `Screen.OpenClaw("openclaw")`, `Screen.OpenClawSettings("openclaw_settings")` + both `composable`
  blocks; `HomeScreen(onNavigateToOpenClaw = …)` and `SettingsScreen(onNavigateToOpenClawSettings = …)`
  wired in `Navigation.kt`.
- `HomeScreen.kt`: the WordLearn "Coming Soon" placeholder card in Row 2 is now the OpenClaw card
  (`Icons.Default.Link`, OpenClaw gradient, subtitle flips to `feature_openclaw_connected` when
  Connected); Home auto-connects on first composition when a token is stored and the state is
  `Disconnected`. The unused `automirrored.filled.MenuBook` import was removed; `WordLearnColor` and
  the `feature_wordlearn_*` strings stay for `records_wordlearn`.
- `SettingsScreen.kt`: new "Integrations" section immediately above "Data", one row whose subtitle
  and subtitle colour are the live OpenClaw status.

### Extra file
`services/HttpClients.kt` (Task 7's content, created here so the ViewModel compiles) — shared
`HttpClients.websocket` OkHttpClient.

---

## 2. `OpenClawViewModel` public API

```kotlin
class OpenClawViewModel internal constructor(
    application: Application,
    service: OpenClawNodeService,
    frames: GlassesFrameProvider,
    sessionManager: () -> GlassesSessionManager,
    asrFactory: (String, AlibabaEndpoint, BluetoothAudioManager.AudioSource) -> SpeechRecognizerSession,
    alibabaKey: () -> String?,
    alibabaEndpoint: () -> AlibabaEndpoint,
    bluetoothAudioManager: BluetoothAudioManager?,
    strings: (Int) -> String,
    decodeImage: (ByteArray) -> Bitmap?,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AndroidViewModel

constructor(application: Application)   // the reflective one `viewModel()` uses

companion object { const val OWNER = "OpenClawChat" }

// state
val connectionState: StateFlow<OpenClawConnectionState>
val messages: StateFlow<List<OpenClawChatMessage>>
val pendingResponse: StateFlow<String?>
val inputText: StateFlow<String>
val showTextInput: StateFlow<Boolean>
val isSending: StateFlow<Boolean>
val isListening: StateFlow<Boolean>
val asrText: StateFlow<String>          // accumulated final sentences
val asrPartial: StateFlow<String>       // interim sentence
val asrError: StateFlow<String?>
val currentAudioSource: StateFlow<BluetoothAudioManager.AudioSource>
val isBluetoothAvailable: StateFlow<Boolean>

// screen lifecycle
fun enterScreen()            // acquire(OWNER) + connectIfNeeded()
fun leaveScreen()            // stopListening() + flushPendingResponse() + release(OWNER)
fun connectIfNeeded()

// chat
fun onChatEvent(text: String, isFinal: Boolean)
fun flushPendingResponse()
fun onInputChanged(text: String)
fun toggleTextInput()
fun sendText()
fun snapAndSend()

// voice
fun startListening()
fun stopListening()
fun sendAsrText()
fun cancelAsr()
fun switchAudioSource(source: BluetoothAudioManager.AudioSource)

// test hooks (internal / @VisibleForTesting)
internal val suppressedSends: Int
internal fun localizeAsrError(raw: String): String
```

---

## 3. How each pointer was applied

### 3.1 `chatEvents` subscribed **before** `connect()`
`chatEvents` is `SharedFlow` with replay 0, so any event emitted before the first collector is lost.
The collector is started in the ViewModel's `init` block:

```kotlin
init {
    // Subscribed BEFORE any connect() this ViewModel issues: chatEvents has replay 0, so a
    // `chat` event that lands between connect() and the first collector would be dropped.
    chatJob = viewModelScope.launch { service.chatEvents.collect { … } }
}
```

`enterScreen()` (the only place the ViewModel dials) runs strictly after construction, from the
chat screen's `DisposableEffect`. `viewModelScope` uses `Dispatchers.Main.immediate`, so on the main
thread the collector registers synchronously inside the `launch` call itself — no window at all.

### 3.2 Sending gated on `Connected`
`OpenClawNodeService.sendChatMessage()` returns `socket.send(...)`, i.e. `true` for any live socket
including one that has not completed the `connect` hello. The ViewModel funnels **all three** send
paths (`sendText`, `sendAsrText`, `snapAndSend`) through one private `deliver()`:

```kotlin
private fun deliver(text: String, imageJpegBase64: String? = null) {
    if (connectionState.value != OpenClawConnectionState.Connected) {
        suppressedSends++
        Log.w(TAG, "chat.send dropped: not connected (${connectionState.value})")
        return
    }
    if (!service.sendChatMessage(text, imageJpegBase64)) Log.w(TAG, "chat.send dropped: no socket")
}
```

The local bubble is still appended (the user sees what they said); only the wire send is suppressed.
The chat screen additionally disables Snap & Send, the mic button, voice-Send and text-Send off
`Connected`, so the gate is a backstop rather than the only defence.

### 3.3 Transport failures in the UI
Task 3 never constructs `OpenClawErrorReason.Transport`, so a dropped socket surfaces as
`Reconnecting(n)` → `Error(MaxRetries(5))`. `openClawStatusText` renders both:
`openclaw_status_reconnecting` ("Reconnecting (attempt %1$d)…" / "重连中（第 %1$d 次）…") with the
live attempt count, and `openclaw_error_max_retries` ("Connection failed after %1$d retries"). The
`Transport` branch is still implemented (the `when` is exhaustive) for when Task 3 starts emitting it.
The chat screen also shows a spinner in the amber banner for `Connecting` and `Reconnecting`.

### 3.4 ASR error localization
`FunASRService` reports three fixed English texts that are wire/log level. The ViewModel maps them
to resources before formatting them into `openclaw_chat_asr_failed`; anything else (a DashScope
server message) passes through untranslated:

| raw (FunASRService) | resource | en | zh-rCN |
|---|---|---|---|
| `Microphone unavailable` | `openclaw_asr_error_mic` | Microphone unavailable | 麦克风不可用 |
| `Connection failed` | `openclaw_asr_error_connection` | Connection failed | 连接失败 |
| `ASR task failed` | `openclaw_asr_error_task` | Speech recognition service error | 语音识别服务错误 |

```kotlin
internal fun localizeAsrError(raw: String): String = ASR_ERROR_STRINGS[raw]?.let { str(it) } ?: raw
```

### 3.5 Bluetooth SCO in lockstep with the recognizer
`FunASRService.switchAudioSource()` only swaps the PCM source, so SCO is the caller's job (same as
Live AI / `OmniRealtimeService.switchAudioSource`). Three touch points, all no-ops when
`bluetoothAudioManager == null` (the JVM tests):

1. `switchAudioSource(source)` → `bluetoothAudioManager.switchAudioSource(source)`, which *starts*
   SCO for `BLUETOOTH_MIC` (when `isBluetoothScoAvailable()`) and *stops* it for `PHONE_MIC`, then
   `asr?.switchAudioSource(source)` so the running recognizer re-opens its `AudioRecord` on
   `VOICE_COMMUNICATION` / `MIC`.
2. `startListening()` calls `bluetoothAudioManager.startBluetoothSco()` when the selected source is
   already `BLUETOOTH_MIC` (idempotent — it no-ops when SCO is up), so a glasses mic chosen before
   the mic button is pressed still has a live SCO route when the recogniser opens.
3. `stopListening()` / the recognizer's own `onFinished` both end in `finishListening()`, which calls
   `bluetoothAudioManager.stopBluetoothSco()` — an open SCO route otherwise keeps the phone in
   `MODE_IN_COMMUNICATION` and mutes system media. `leaveScreen()` and `onCleared()` reach it too
   (`onCleared` also calls `bluetoothAudioManager.cleanup()`).

### 3.6 RECORD_AUDIO requested before ASR
Requested lazily from the chat screen, the same way `LiveAIScreen` does it — never at launch:

```kotlin
val micPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
    if (granted) viewModel.startListening()
}
fun toggleListening() {
    if (isListening) { viewModel.stopListening(); return }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == GRANTED
    if (granted) viewModel.startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
}
```

### 3.7 Node id
The settings screen shows `service.nodeId` (the real `rayban-<8 hex of ANDROID_ID>` from
`OpenClawNodeService.nodeIdFor`) when Connected, `-` otherwise. The iOS literal `"rayban-node"`
appears nowhere.

### 3.8 Shared glasses session
`enterScreen()` → `GlassesSessionManager.acquire("OpenClawChat")`, `leaveScreen()` → `release(...)`,
driven by the chat screen's `DisposableEffect`. `sessionHeld` makes both idempotent so a second
`enterScreen()` cannot leak a claim and `onCleared()` cannot double-release.

---

## 4. Verification

### TDD evidence
1. `OpenClawViewModelTest.kt` was written (Step 6.3) before `OpenClawViewModel.kt` (Step 6.4).
2. Real red/green cycle recorded for the Connected gate — the gate condition was temporarily
   replaced with `if (false)` and the suite rerun:

```
> Task :app:testDebugUnitTest FAILED
OpenClawViewModelTest > sendingIsGatedOnConnectedSoAPreHelloSocketNeverSeesChatSend FAILED
12 tests completed, 1 failed
BUILD FAILED in 4s
```

   Gate restored → `BUILD SUCCESSFUL`, `tests="12" skipped="0" failures="0" errors="0"`
   (timestamp `2026-09-11T01:34:48.988Z`).

### The 12 ViewModel tests
| test | covers |
|---|---|
| `deltaEventsReplacePendingAndFinalAppendsAssistantBubble` | replace-style deltas, final → bubble |
| `sendTextAppendsUserBubbleFlushesPendingAndClearsInput` | pending flush + trim + clear |
| `snapWithoutFrameShowsTheNoFrameBubble` | `SnapshotResult.NoFrame` → localized bubble |
| `snapWithFrameAppendsUserBubbleWithImageAndPrompt` | photo prompt + bitmap + `isSending` reset |
| `listeningWithoutAlibabaKeyShowsTheNoApiKeyBubble` | no key → bubble, recognizer never built |
| `voiceFlowAccumulatesFinalSentencesAndSendsThem` | partial/final accumulation, review, send |
| `recognizerErrorShowsTheLocalizedFailureAndCancelClearsIt` | error text + cancel clears everything |
| `switchingTheAudioSourceReachesTheRunningRecognizer` | fallback flow + `asr.switchAudioSource` |
| `enteringTheScreenAcquiresTheSharedSessionAndLeavingReleasesIt` | acquire/release ownerCount |
| `sendingIsGatedOnConnectedSoAPreHelloSocketNeverSeesChatSend` **(added)** | all 3 send paths gated |
| `asrTransportErrorsAreMappedToLocalizedText` **(added)** | 3 literals mapped, others pass through |
| `listeningStopsWhenTheScreenIsLeft` **(added)** | `leaveScreen` stops the recognizer + releases |

### Build / test results
```
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 1m 17s
```
- Unit tests: **133** across 12 classes, `skipped=0 failures=0 errors=0` (121 baseline + 12 new).
- `system-out` / `system-err`: empty in every `TEST-*.xml`.
- `:app:compileDebugAndroidTestKotlin` green (no instrumented test referenced the changed screens).
- Warnings from the files I touched: **none** after switching `Icons.Default.Send` →
  `Icons.AutoMirrored.Filled.Send` (see §5). All remaining `w:` lines are pre-existing
  (`QuickVisionService`, `ModeSettingsScreen`, `RecordsScreen`, `APIKeyManager`, …).

### String-key parity check
```
$ diff <(grep -o 'name="[^"]*"' values/strings.xml | sort) \
       <(grep -o 'name="[^"]*"' values-zh-rCN/strings.xml | sort)
PARITY OK: 442 keys in each locale, identical sets

$ grep -c 'name="openclaw_\|name="feature_openclaw\|name="audio_source_\|name="settings_integrations"\|name="done"' values/strings.xml values-zh-rCN/strings.xml
values/strings.xml:45
values-zh-rCN/strings.xml:45
```
(Unsorted `diff` shows only the four pre-existing ordering differences — `feature_quickvision_*`,
`gallery` — that were already in the tree; the key *sets* are identical.)

---

## 5. Files changed

Created:
- `android/app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/components/OpenClawStatus.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/OpenClawChatScreen.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/OpenClawSettingsScreen.kt`
- `android/app/src/main/java/com/smartview/glassai/services/HttpClients.kt`
- `android/app/src/main/res/xml/network_security_config.xml`
- `android/app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt`

Modified:
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/smartview/glassai/ui/theme/Color.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/res/values-zh-rCN/strings.xml`

No plumbing was touched: `OpenClawNodeService`, `OpenClawCommandRouter`, `OpenClawIntegration`,
`SessionFrameProvider`, `GlassesSessionManager`, `FunASRService` and `BluetoothAudioManager` are
byte-identical to `1c84af2`.

---

## 6. Deviations from the brief

1. **Send icon.** `Icons.Default.Send` produced a new deprecation warning
   (`'val Icons.Filled.Send' is deprecated. Use the AutoMirrored version`). Since the task requires
   "no new warnings from your files", the chat screen uses
   `androidx.compose.material.icons.automirrored.filled.Send` instead. Visually identical in LTR,
   correct in RTL.
2. **Three extra string keys** (`openclaw_asr_error_mic` / `_connection` / `_task`) in both locales,
   required by the parent task's ASR-localization pointer. Everything else is the brief's exact text.
3. **`deliver()` helper** replaces the brief's three inline
   `if (!service.sendChatMessage(...)) Log.w(...)` calls, to implement the Connected gate the parent
   task made binding. Behaviour is otherwise identical (same bubbles, same clearing, same logging).
4. **SCO calls added** to `startListening()` and the new private `finishListening()` (brief's
   ViewModel had none) — the binding pointer in §3.5.
5. **`stopListening()` hardened against re-entrancy**: `asr` is nulled *before* `stop()` is called,
   and the recognizer's own `onFinished` routes to `finishListening()` (flags + SCO only) instead of
   calling `stop()` again from inside FunASRService's callback. The brief's `onError`/`onFinished`
   only set `_isListening = false`; here `onError` performs a full `stopListening()` so the SCO
   route and the recognizer are actually released on failure. The brief's test expectations
   (`stopCalls == 1` after `onError` + `cancelAsr`) still hold exactly.
6. **`alibabaEndpoint` production lambda** calls `APIProviderManager.getInstance(application)` before
   reading `APIProviderManager.staticAlibabaEndpoint`. The static getter reads a *companion* `prefs`
   field that is only bound in the instance `init {}`; without an instance it silently answers
   `BEIJING` even when the user picked Singapore. One-line, no behaviour change for Beijing users.
7. **Extra tests**: 12 instead of the brief's 9 (the brief's Step 6.12 already expected 12).

---

## 7. Concerns

1. **Glasses-mic chip resets to phone after each utterance.** `stopListening()` stops SCO (required
   by the pointer), and `BluetoothAudioManager`'s SCO_AUDIO_STATE_DISCONNECTED receiver then flips
   `currentAudioSource` back to `PHONE_MIC`. So the chip returns to "Phone mic" between utterances
   and the user re-taps "Glasses mic" for the next one. Correct (SCO really is down) but a UX
   wrinkle worth a look on device in Task 9. Keeping SCO open across turns would be the alternative,
   at the cost of holding `MODE_IN_COMMUNICATION` and muting media the whole time the screen is open.
2. **`BluetoothAudioManager.switchAudioSource(BLUETOOTH_MIC)` does not set `currentAudioSource`
   itself** — it only calls `startBluetoothSco()` and waits for the SCO_CONNECTED broadcast. If the
   glasses never complete SCO, the chip stays on "Phone mic" and the recognizer stays on the phone
   mic (`asr?.switchAudioSource(BLUETOOTH_MIC)` will have been called, so the `AudioRecord` opens on
   `VOICE_COMMUNICATION` without an SCO route). This is pre-existing Live AI behaviour (unchanged
   plumbing), but it is the most likely on-device failure mode for the glasses mic. Task 9 should
   check it; a fix belongs in `BluetoothAudioManager`, not here.
3. **Global cleartext.** `network_security_config.xml` permits cleartext for *all* hosts, per the
   brief, because LAN and Tailscale `100.64.0.0/10` addresses are not "private" domains the
   `domain-config` form can enumerate. Every cloud endpoint the app uses is https/wss, so nothing
   silently downgrades, but the app no longer refuses a plaintext URL if one is ever configured
   elsewhere (e.g. the RTMP/vision base URL fields).
4. **Chat history is process-local and per-ViewModel** (spec §3 decision 3). Rotating the device
   keeps it (ViewModel survives configuration change), but backing out of the chat screen pops the
   NavBackStackEntry and clears it. That matches the spec; flagging it because it can read as a bug.
5. **`Reconnecting` never auto-recovers into the chat once `MaxRetries` is hit**: the chat screen's
   only way back is the settings screen's Connect button (`connect(force = true)`) — `connectIfNeeded()`
   deliberately refuses to dial out of `Error`/`Reconnecting` so it cannot defeat the backoff. If
   Task 9 finds that annoying, a "Retry" affordance in the amber banner is the cheap fix.
6. **No manual smoke test was run.** The emulator has no glasses and no gateway, so `assembleDebug`
   + the 133 JVM tests are the whole verification; Task 9 owns the on-device pass.

---

## Fix round 1

**Finding:** *Glasses-mic selection reverts after every utterance, and the next utterance silently
records from the phone.* Controller ruling implemented in full (items 1–4).

### What changed

`android/app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt`

| where | change |
|---|---|
| `:52`, `:83` | constructor takes `audioRoute: OpenClawAudioRoute?` instead of `bluetoothAudioManager: BluetoothAudioManager?`; production passes `BluetoothAudioRoute(BluetoothAudioManager(application))` |
| `:97` | `SCO_WAIT_MS = 3_000L` (the bounded route wait) |
| `:140-141` | new `asrNotice: StateFlow<String?>` — the non-fatal voice notice |
| `:143-151` | new `_desiredAudioSource` / `desiredAudioSource: StateFlow<AudioSource>`, default `PHONE_MIC`. **`currentAudioSource` is gone**: it was `bluetoothAudioManager.currentAudioSource`, i.e. the live route, and that is exactly what snapped the chip back |
| `:154` | `isBluetoothAvailable` now reads `audioRoute.bluetoothAvailable` |
| `:163`, `:167` | `listenJob` (the pending listen) and `scoHeld` (is this ViewModel holding the link) |
| `:190-192` | `leaveScreen()` → `stopListening()` **+ `releaseSco()`** |
| `:295-307` | `startListening()` is now the entry point only: clears transcript/error/notice, sets `isListening`, launches `beginListening(key)` |
| `:315-345` | new `beginListening()` — desired `BLUETOOTH_MIC` ⇒ `holdSco()`, then `awaitScoRoute()`; on timeout it logs, sets `_asrNotice` to `openclaw_asr_sco_timeout`, and uses `PHONE_MIC` **for that utterance only** (`_desiredAudioSource` untouched) |
| `:347-352` | new `awaitScoRoute()` — `true` immediately if the link is already up, else `withTimeoutOrNull(SCO_WAIT_MS) { scoConnected.first { it } }` |
| `:355-357` | `stopListening()` cancels `listenJob` (it may still be in the route wait) |
| `:365-373` | `finishListening()` **no longer stops SCO** — the comment records why |
| `:375-386` | new `holdSco()` / `releaseSco()`, both idempotent via `scoHeld` |
| `:388-393` | `sendAsrText()` also clears `_asrError` and `_asrNotice` (folded minor 4) |
| `:404-405` | `cancelAsr()` also clears `_asrNotice` |
| `:417-424` | `switchAudioSource()` sets `_desiredAudioSource`, then `holdSco()` for `BLUETOOTH_MIC` / `releaseSco()` for `PHONE_MIC`; still forwards to a running recognizer |
| `:436` | `onCleared()` → `audioRoute?.cleanup()` |
| `:440-468` | **the seam** (see below) |

`android/app/src/main/java/com/smartview/glassai/ui/screens/OpenClawChatScreen.kt`
- `:112-113` collects `asrNotice` and `desiredAudioSource` (was `currentAudioSource`).
- `:214` the transcript card also shows when only a notice is pending.
- `:222-230` the notice renders **above** the transcript in `colorScheme.tertiary` at 12 sp, so it
  never replaces the live text the way `asrError` does.
- `:268`, `:275` both `FilterChip`s select off `audioSource` (= `desiredAudioSource`).

`android/app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt`
- `:18` redundant `import androidx.compose.material.icons.filled.Link` removed (folded minor 4); the
  `filled.*` wildcard on the next line still resolves `Icons.Default.Link`.

`app/src/main/res/values/strings.xml:508` / `values-zh-rCN/strings.xml:508`
- `openclaw_asr_sco_timeout` — en "Glasses microphone not ready, using phone microphone",
  zh "眼镜麦克风未就绪，改用手机麦克风".

### Plumbing change: none to `BluetoothAudioManager`

The ruling allowed adding `scoConnected: StateFlow<Boolean>` to `BluetoothAudioManager` if no signal
existed. **It was not needed** — the manager already exposes `isBluetoothScoConnected: StateFlow<Boolean>`
(`managers/BluetoothAudioManager.kt:53-54`), driven by the same `SCO_AUDIO_STATE_CONNECTED/DISCONNECTED`
receiver at `:66-81`. `BluetoothAudioManager.kt` is byte-identical to `1c84af2`.

**The one seam introduced** (the brief's allowance, since the ViewModel took the concrete manager and
the JVM tests could only pass `null`): `interface OpenClawAudioRoute` + `class BluetoothAudioRoute`,
both at the bottom of `OpenClawViewModel.kt:440-468`. Five members, each a straight delegate to an
existing manager API:

| `OpenClawAudioRoute` | delegates to |
|---|---|
| `bluetoothAvailable: StateFlow<Boolean>` | `manager.isBluetoothScoAvailable` |
| `scoConnected: StateFlow<Boolean>` | `manager.isBluetoothScoConnected` |
| `startSco()` / `stopSco()` | `manager.startBluetoothSco()` / `stopBluetoothSco()` |
| `cleanup()` | `manager.cleanup()` |

`BluetoothAudioManager.switchAudioSource()` is deliberately **not** used: it early-returns when its
own `currentAudioSource` already equals the target, so it cannot re-arm a dropped link, and its
`PHONE_MIC` branch writes the very flow the chip used to read.

### RED → GREEN evidence

**RED (tests as written, before any production change)** — `./gradlew :app:testDebugUnitTest`:

```
e: OpenClawViewModelTest.kt:... Unresolved reference 'OpenClawAudioRoute'.
e: OpenClawViewModelTest.kt:... Unresolved reference 'desiredAudioSource'.
e: OpenClawViewModelTest.kt:... Unresolved reference 'asrNotice'.
e: OpenClawViewModelTest.kt:393:35 Unresolved reference 'openclaw_asr_sco_timeout'.
> Task :app:compileDebugUnitTestKotlin FAILED
BUILD FAILED in 1s
```

Because a compile failure is weak per-test evidence, each new test was then re-RED-ed against the
finished production code by re-introducing the exact defect it guards (mutation runs, 18 tests in
`OpenClawViewModelTest`):

| new test | mutation | RED |
|---|---|---|
| `desiredSourceSurvivesFinishListening` | `finishListening()` stops SCO again (the reviewed defect) | `AssertionError: expected:<0> but was:<1>` (stopCalls) |
| `leaveScreenStopsSco` | `releaseSco()` removed from `leaveScreen()` (run in isolation — the defect above masks it) | `AssertionError: expected:<1> but was:<0>` |
| `startListeningWaitsForScoBeforeStartingAsr` | `if (!awaitScoRoute())` → `if (false)` | `AssertionError: expected:<0> but was:<1>` (asr.startCalls before the CONNECTED signal) |
| `scoTimeoutFallsBackToPhoneMicForThatUtterance` | same | `AssertionError: expected:<0> but was:<1>` |
| `switchingBackToThePhoneMicStopsSco` | `PHONE_MIC -> releaseSco()` → `-> Unit` | `AssertionError: expected:<1> but was:<0>` |
| `sendAsrTextClearsTheAsrError` | `_asrError.value = null` removed from `sendAsrText()` | `AssertionError: expected null, but was:<str:2131624389>` |

```
> Task :app:testDebugUnitTest FAILED
OpenClawViewModelTest > desiredSourceSurvivesFinishListening FAILED
OpenClawViewModelTest > switchingBackToThePhoneMicStopsSco FAILED
OpenClawViewModelTest > sendAsrTextClearsTheAsrError FAILED
OpenClawViewModelTest > scoTimeoutFallsBackToPhoneMicForThatUtterance FAILED
OpenClawViewModelTest > startListeningWaitsForScoBeforeStartingAsr FAILED
18 tests completed, 5 failed
```
```
(M2 in isolation)
OpenClawViewModelTest > desiredSourceSurvivesFinishListening FAILED
OpenClawViewModelTest > leaveScreenStopsSco FAILED
18 tests completed, 2 failed
```

All mutations reverted → GREEN.

`desiredSourceSurvivesFinishListening` is the one that reproduces the user-visible report end to
end: select the glasses mic, speak, stop, **speak again**, and assert both recognizers were built
with `BLUETOOTH_MIC` and that the route was never torn down in between.

Test seam in `OpenClawViewModelTest.kt`: `FakeAudioRoute` (`:71-82`) implements `OpenClawAudioRoute`
with a `MutableStateFlow` for the SCO link the test flips the way the manager's receiver would, plus
start/stop/cleanup counters; `newViewModel(audioRoute = …)` (`:105`) defaults to `null`, so the 12
original tests are unchanged apart from `switchingTheAudioSourceReachesTheRunningRecognizer` now
asserting on `desiredAudioSource`. The timeout test advances the `UnconfinedTestDispatcher`'s
scheduler (`dispatcher.scheduler.advanceUntilIdle()`) so the 3 s wait elapses in virtual time — the
suite still runs in ~1 s.

### Verification

```
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 1m 18s
```
- Unit tests: **139** across 12 classes, `skipped=0 failures=0 errors=0` (133 baseline + 6 new).
- `system-out` / `system-err`: empty in every `TEST-*.xml`.
- No `w:` line from `OpenClawViewModel.kt`, `OpenClawChatScreen.kt`, `HomeScreen.kt` or
  `BluetoothAudioManager.kt`; the remaining warnings are the same pre-existing ones
  (`ModeSettingsScreen`, `QuickVisionScreen`, `RecordsScreen`, `APIKeyManager`, …).
- String-key parity: `diff` of the sorted key sets is empty — **443 keys in each locale**
  (442 + `openclaw_asr_sco_timeout`).

### Concerns

1. **SCO is now held for the whole chat session when the glasses mic is selected.** That is the
   point of the fix, but it also means the phone stays in `MODE_IN_COMMUNICATION` and system media
   is muted from the moment the chip is tapped until the screen is left or the chip goes back to
   "Phone mic" — matching Live AI (`OmniRealtimeViewModel:342-351`), and a deliberate trade against
   the defect. Task 9 should confirm on device that leaving the chat restores media volume.
2. **The chip now shows intent, not reality.** If SCO never connects the chip still reads "Glasses
   mic" while that utterance records from the phone; the only signal is the `openclaw_asr_sco_timeout`
   line above the transcript. A route indicator on the chip (driven by `audioRoute.scoConnected`) is
   the natural follow-up if Task 9 finds the notice too quiet — the flow is already exposed through
   the seam, so it would be a UI-only change.
3. **`_isListening` goes true before the recognizer starts.** During the ≤ 3 s route wait the mic
   button is red and the card says "Listening…" while nothing is captured yet. Correct for cancel
   semantics (`stopListening()` cancels `listenJob`), but a user who speaks instantly into a cold
   SCO link loses the first word — unavoidable with the wait, and worse without it.
4. **Nothing re-arms SCO if the link drops mid-session.** `scoHeld` stays true, so `holdSco()`
   no-ops and the next utterance pays the 3 s timeout and the phone-mic fallback. Re-tapping the
   chip is the recovery (a `PHONE_MIC` → `BLUETOOTH_MIC` round trip calls `holdSco()` again).
   Watching `scoConnected` for a false transition and re-calling `startSco()` was out of scope here.
5. **Still no on-device pass** — the emulator has no glasses and no SCO. The 139 JVM tests plus the
   builds are the whole verification; Task 9 owns the real microphone check.
