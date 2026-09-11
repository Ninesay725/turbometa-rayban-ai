# Task 4 report — `GlassesFrameProvider` + `OpenClawCommandRouter`

Commit: **2a859a5** `feat(android): OpenClawCommandRouter (camera.snap/list, device.status/info) over SessionFrameProvider — manager latestFrame with capturer fallback and foreground gate; BuildConfig.MWDAT_VERSION`
Branch: `android-v2` (parent `aa5a786`). Working tree clean after the commit.

## What was implemented

1. **`glasses/GlassesFrameProvider.kt` (new)** — `FrameSnapshot`, the `SnapshotResult` hierarchy, the
   `GlassesFrameProvider` interface, and `SessionFrameProvider`, the production implementation with the
   three-tier frame strategy (manager `latestFrame` → wait for the current owner's first frame →
   `GlassesPhotoCapturer` fallback under owner `"OpenClawSnap"`), plus the foreground gate and the
   off-Main JPEG scale/encode (`encodeBitmap`).
2. **`services/openclaw/OpenClawCommandRouter.kt` (modified)** — appended `OpenClawDeviceInfoSource`
   (with `fromBuild()`) and `OpenClawCommandRouter`, implementing `OpenClawCommandHandler` for
   `camera.snap`, `camera.list`, `device.status`, `device.info` with iOS-identical payload shapes,
   error codes and error messages. Written in the import form (not the fully-qualified form) as the
   brief's step 4.5 requires for the committed version.
3. **`services/openclaw/OpenClawIntegration.kt` (new)** — `install(app)`, `runCatching`-wrapped,
   installs the router into the `OpenClawNodeService` singleton.
4. **`app/build.gradle.kts`** — `buildConfigField("String", "MWDAT_VERSION", ...)` fed from
   `libs.versions.mwdat.get()` (currently `0.9.0`), inside `defaultConfig` right after the
   `mwdat_client_token` placeholder.
5. **`TurboMetaApplication.kt`** — `@Volatile startedActivities` counter driven by
   `ActivityLifecycleCallbacks`, exposed as `isInForeground`; `OpenClawIntegration.install(this)` at
   the end of `onCreate()`.
6. **`viewmodels/RTMPStreamingViewModel.kt`** — pointer 2 (see below).
7. Tests: `app/src/test/.../services/openclaw/OpenClawCommandRouterTest.kt` (7 tests),
   `app/src/test/.../glasses/SessionFrameProviderTest.kt` (7 tests), both verbatim from the brief.

## Exact public API produced (for Task 6 / 7 / 8 / 9)

```kotlin
// com.smartview.glassai.glasses
data class FrameSnapshot(val jpeg: ByteArray, val width: Int, val height: Int)

sealed class SnapshotResult {
    data class Ok(val frame: FrameSnapshot) : SnapshotResult()
    data class NotReady(val detail: String) : SnapshotResult()
    data class StreamFailed(val detail: String) : SnapshotResult()
    object NoFrame : SnapshotResult()
    object PermissionRequired : SnapshotResult()
    object EncodeFailed : SnapshotResult()
}

interface GlassesFrameProvider {
    val hasActiveDevice: Boolean
    val isStreaming: Boolean          // true while streamStatus != "stopped"
    val streamStatus: String          // "streaming" | "waiting" | "stopped"
    val hasFrame: Boolean
    suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult
}

class SessionFrameProvider(
    private val sessionManager: () -> GlassesSessionManager,
    private val isForeground: () -> Boolean,
    private val checkPermission: suspend () -> CameraPermissionCheck,
    private val encode: (Bitmap, Int, Double) -> FrameSnapshot?,
    private val capture: suspend (GlassesSessionManager) -> PhotoCaptureOutcome<Bitmap>,
    private val encodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GlassesFrameProvider {
    companion object {
        const val OWNER = "OpenClawSnap"
        fun create(app: Application): SessionFrameProvider
        fun encodeBitmap(bitmap: Bitmap, maxWidth: Int, quality: Double): FrameSnapshot?
    }
}

// com.smartview.glassai.services.openclaw
data class OpenClawDeviceInfoSource(val appVersion: String, val sdkVersion: String, val osVersion: String) {
    companion object { fun fromBuild(): OpenClawDeviceInfoSource }
}

class OpenClawCommandRouter(
    private val frames: GlassesFrameProvider,
    private val deviceInfo: OpenClawDeviceInfoSource,
    private val snapTimeoutMs: Long = DEFAULT_SNAP_TIMEOUT_MS,   // 5_000L
) : OpenClawCommandHandler {
    companion object { const val DEFAULT_SNAP_TIMEOUT_MS = 5_000L }
}

object OpenClawIntegration { fun install(app: Application) }

// com.smartview.glassai
BuildConfig.MWDAT_VERSION                       // "0.9.0", from libs.versions.mwdat
TurboMetaApplication.isInForeground: Boolean
```

This matches the brief's **Interfaces** block exactly. The only symbols added beyond it are two
**private, file-level** extensions in `GlassesFrameProvider.kt` (`GlassesSessionManager.liveFrame()`
and `GlassesSessionManager.awaitLiveFrame(timeoutMs)`) — not visible outside the file, so no
downstream task is affected. Verified against the downstream briefs: Task 6 uses
`SessionFrameProvider.create(application)` and a `GlassesFrameProvider` fake; Task 7's anchor
(`PhotoCaptureOutcome.NoImage -> SnapshotResult.NoFrame`) exists verbatim; Task 8 uses
`BuildConfig.MWDAT_VERSION`; Task 9 uses `SessionFrameProvider.Companion::encodeBitmap` and
`SessionFrameProvider.OWNER` — both resolve.

### Wire shapes (iOS parity)

| Command | Payload |
|---|---|
| `camera.snap` ok | `{format:"jpg", base64:<JPEG>, width, height}` |
| `camera.list` | `{cameras:[{id:"rayban-main",name:"Ray-Ban Meta Camera",facing:"front",available:true}]}` or `{cameras:[]}` |
| `device.status` | `{deviceConnected,isStreaming,streamStatus,hasVideoFrame}` |
| `device.info` | `{deviceType:"Ray-Ban Meta",appName:"TurboMeta",appVersion,sdkVersion,platform:"Android",osVersion}` |

Errors: `NO_FRAME` / `NOT_READY` ("Stream not initialized") / `STREAM_FAILED` ("Could not start camera
stream") / `PERMISSION_REQUIRED` / `ENCODE_FAILED` ("Failed to encode JPEG") / `UNKNOWN_COMMAND`
("Unknown command: <cmd>"). The Android-specific detail (`NotReady.detail`, `StreamFailed.detail`) is
logged at `W` and never sent, so the gateway sees exactly the iOS text.

## How each binding pointer was applied

**Pointer 1 — `latestFrame` can be STALE (TOCTOU in `publishFrame` vs `stopCamera`).**
Applied, and this is the one intentional change to the brief's step-4.4 body. Instead of reading
`manager.latestFrame.value` directly, every read goes through a private extension:

```kotlin
private fun GlassesSessionManager.liveFrame(): Bitmap? =
    if (currentCameraOwner != null && sessionState.value == DeviceSessionState.STARTED) latestFrame.value else null

private suspend fun GlassesSessionManager.awaitLiveFrame(timeoutMs: Long): Bitmap? =
    withTimeoutOrNull(timeoutMs) { latestFrame.first { liveFrame() != null } }
```

`liveFrame()` backs `hasFrame`, `streamStatus`, the `snapshot()` fast path, and both wait paths
(the "owner has not published yet" wait and the `CameraBusy` wait). Consequence: a frame that a
borrower's worker wrote *after* the main thread cleared `latestFrame` is no longer served; with no
live owner `camera.snap` falls through to `GlassesPhotoCapturer` and takes a fresh photo instead, and
`device.status` reports `hasVideoFrame:false` rather than advertising a dead frame. The reasoning is
recorded in KDoc on `liveFrame()` citing the pointer. All seven brief-supplied provider tests pass
unchanged (each of them either has a live owner + STARTED session, or no owner at all), so the gate
adds safety without changing any specified behaviour.

**Pointer 2 — `RTMPStreamingViewModel` never published, so `latestFrame` was null during a broadcast.**
Applied. `RTMPStreamingViewModel.updatePreview()` now does
`sessionManager.publishFrame(OWNER, bitmap)` right after `_previewFrame.value = bitmap`, using the
same `OWNER = "RTMPStreamingViewModel"` constant it passes to `acquire`/`addCamera`/`stopCamera`, i.e.
exactly the `WearablesViewModel.handleVideoFrame()` shape. `updatePreview` runs on that ViewModel's
`frameDispatcher` (`Dispatchers.Default.limitedParallelism(1)`), and `publishFrame` is the one manager
method documented as safe off Main, so the threading contract holds.
**Verification: by inspection only.** `RTMPStreamingViewModel` is an `AndroidViewModel(application)`
with no injection seam (`rtmpService` is constructed inline and `sessionManager` comes from
`GlassesSessionManager.getInstance(application)` via `by lazy`), and there is no JVM test for it in the
suite; the brief supplied no scaffolding for one and building one would mean a refactor outside this
task's file list. A KDoc block on `updatePreview` records the intent and the double-lossy trade-off
(the published bitmap is the quality-50 preview decode, same accepted trade-off as Live AI).

**Pointer 3 — no background `camera.snap` (spec decision 3).**
Applied as the brief specifies: `snapshot()` returns `SnapshotResult.NotReady("App is in background")`
before touching the manager when `isForeground()` is false, which the router maps to `NOT_READY` /
"Stream not initialized". `isForeground` is backed by `TurboMetaApplication.isInForeground`, a
`@Volatile` started-Activity counter fed by `ActivityLifecycleCallbacks` (no
`androidx.lifecycle:lifecycle-process` dependency and no catalog entry needed — the brief uses the
Application callbacks, so nothing was added to `libs.versions.toml`). Covered by
`backgroundAppIsNotReady`.

**Pointer 4 — manager methods are main-thread-by-convention.**
The only manager call in this task that *mutates* manager state is
`GlassesPhotoCapturer.capture()` (acquire → ensureSessionStarted → addCamera → stopCamera → release),
and `SessionFrameProvider.create()` wraps it in `withContext(Dispatchers.Main.immediate)`. Everything
else `SessionFrameProvider` touches is a read: `activeDevice.value`, `latestFrame.value` /
`latestFrame.first{}` (StateFlow) and `currentCameraOwner` (`@Volatile`) — safe from the
`Dispatchers.Default` scope `OpenClawNodeService` invokes the router on. JPEG scaling and encoding run
on `encodeDispatcher` (`Dispatchers.Default` in production, `UnconfinedTestDispatcher` in tests), never
on Main and never on the caller's thread. Recorded in KDoc on `liveFrame()`.

**Base64** uses `java.util.Base64.getEncoder()`, which emits no line breaks (the `NO_WRAP` equivalent);
`snapReturnsJpegBase64WithDimensions` asserts the exact encoding. minSdk is 31, so `java.util.Base64`
(API 26+) needs no desugaring.

## TDD evidence

- **Red (step 4.3):** `./gradlew :app:testDebugUnitTest --tests OpenClawCommandRouterTest --tests SessionFrameProviderTest`
  → `BUILD FAILED`, `:app:compileDebugUnitTestKotlin` with `Unresolved reference 'GlassesFrameProvider'`,
  `'SessionFrameProvider'`, `'FrameSnapshot'`, `'SnapshotResult'`, `'OpenClawCommandRouter'`,
  `'OpenClawDeviceInfoSource'` (plus the cascading `asString`/`message` errors on the unresolved types).
- **Green (step 4.8):** same command → `BUILD SUCCESSFUL`;
  `TEST-...OpenClawCommandRouterTest.xml`: `tests="7" skipped="0" failures="0" errors="0"`,
  `TEST-...SessionFrameProviderTest.xml`: `tests="7" skipped="0" failures="0" errors="0"` (14 total,
  the brief's minimum).

## Build / test results

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` (full suite) | BUILD SUCCESSFUL — **109 tests, 0 failures, 0 errors, 0 skipped** (95 baseline + 14 new) |
| system-out / system-err in every `TEST-*.xml` | empty (scripted scan of all CDATA blocks) |
| `:app:assembleDebug :app:assembleRelease` | BUILD SUCCESSFUL in 53 s, exit 0 |
| Kotlin warnings (`^w:`) in that build | **0** overall, none from any file in this task |
| `:app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |

## Files changed (8, +667 / −1)

| File | Change |
|---|---|
| `android/app/build.gradle.kts` | +4 — `MWDAT_VERSION` buildConfigField |
| `android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` | +24 — foreground counter, `OpenClawIntegration.install` |
| `android/app/src/main/java/com/smartview/glassai/glasses/GlassesFrameProvider.kt` | new, 204 lines |
| `android/app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouter.kt` | +117 — device-info source + router |
| `android/app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawIntegration.kt` | new, 26 lines |
| `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` | +11/−1 — pointer 2 |
| `android/app/src/test/java/com/smartview/glassai/glasses/SessionFrameProviderTest.kt` | new, 141 lines |
| `android/app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawCommandRouterTest.kt` | new, 140 lines |

No new string resources, so nothing to add to either locale. No new catalog entries or dependencies.

## Deviations from the brief

1. **Stale-frame gate in `SessionFrameProvider`** (pointer 1 above): `manager.latestFrame.value` and the
   two `withTimeoutOrNull { latestFrame.first { it != null } }` waits were replaced by `liveFrame()` /
   `awaitLiveFrame()`, which additionally require `currentCameraOwner != null` and
   `sessionState.value == STARTED`. Mandated by the controller's binding pointer; no brief-specified
   behaviour or test changed.
2. **`RTMPStreamingViewModel` was added to the commit's `git add` list** (pointer 2). The commit
   *message* is the brief's verbatim; the path list gained
   `android/app/src/main/java/com/smartview/glassai/viewmodels/RTMPStreamingViewModel.kt` because the
   brief's list predates that controller decision.
3. Nothing else — step 4.4/4.5/4.6/4.7 bodies and both test files are otherwise the brief's text
   (4.5 committed in the import form the brief asks for).

## Concerns / notes for later tasks

1. **`SessionFrameProvider.create()` is not covered by a JVM test** — it is the one path that wires
   `WearablesRegistrationGateway`, `GlassesSessionManager.getInstance`, `GlassesPhotoCapturer` and
   `Dispatchers.Main.immediate` together, and none of that is reachable on the JVM. Task 9's
   `SessionFrameProviderEncodeInstrumentedTest` plus the instrumented provider test cover `encodeBitmap`
   and the capturer path on device; the `create()` wiring itself is only exercised end-to-end by the app.
2. **Pointer 2 is inspection-verified, not test-verified** (no seam in `RTMPStreamingViewModel`). If a
   regression guard matters, the cheapest option is extracting the preview/publish step behind an
   injectable collaborator in a later task; flagged rather than done because it is outside this task's
   file list.
3. **`device.status` / `camera.list` construct the session manager on first call.** The getters call
   `sessionManager()` → `GlassesSessionManager.getInstance(app)`, which builds the singleton (and its
   `Dispatchers.Main.immediate` scope + `startMonitoring()`) from the node service's
   `Dispatchers.Default` thread. That is safe (`scope.launch` from any thread), and it cannot happen at
   app start because `install()` only captures the lambda — but it does mean a `device.status` before
   any Bluetooth permission is granted will create the manager and start a device-observer job that
   logs a failure. Behaviour matches what the brief intends ("so installing this at app start does not
   create the manager before the Bluetooth runtime permissions are granted"); noting it because the
   first *command* rather than the first UI screen can now be the creator.
4. **Forward reference in KDoc:** `SessionFrameProvider`'s class doc says the Quick Vision `NO_FRAME`
   window is "documented in android/README.md". That README section does not exist yet — Task 8
   step 8.7 adds it (its brief explicitly lists "README notes on the Quick Vision `NO_FRAME` window").
   No action needed here, just do not drop it from Task 8.
5. **`data class FrameSnapshot(val jpeg: ByteArray, …)`** has array identity `equals`/`hashCode`. No test
   or production path compares `FrameSnapshot`/`SnapshotResult.Ok` by value, so this is inert today; a
   future test that does `assertEquals(SnapshotResult.Ok(...), result)` would fail surprisingly.

## Fix round 1

Commit: **a8d0136** `fix(android): OpenClaw frame provider waits for the device flow, never races a
claimed camera, fails closed when backgrounded; regression tests for the stale-frame gate`
Branch `android-v2` (parent `2a859a5`). 6 files changed, +108 / −22. Working tree clean afterwards.

### What changed

| Finding | Change | Where |
|---|---|---|
| 1 — `hasActiveDevice` null on the first command | `val hasActiveDevice` **replaced** on the interface by `suspend fun awaitActiveDevice(timeoutMs: Long = DEFAULT_DEVICE_WAIT_MS): Boolean`, with `DEFAULT_DEVICE_WAIT_MS = 1_500L` in a new `GlassesFrameProvider` companion | `GlassesFrameProvider.kt:45`, `:57` |
| 1 | `SessionFrameProvider` implements it as `withTimeoutOrNull(timeoutMs) { sessionManager().activeDevice.first { it != null } } != null` (the `GlassesPhotoCapturer.kt:76-80` shape) | `GlassesFrameProvider.kt:143-144` |
| 1 | `camera.list` / `device.status` now call `frames.awaitActiveDevice()`; both private handlers became `suspend` (`handleCommand` already was). Payload shapes, keys, order and error codes untouched | `OpenClawCommandRouter.kt:96-98`, `:109-111` |
| 1 | `install()` calls `GlassesSessionManager.getInstance(app).startMonitoring()` before wiring the router, so the device flow is live before the first command; KDoc records why, and the now-stale "nothing here touches the session manager" comment in `TurboMetaApplication.onCreate()` was corrected | `OpenClawIntegration.kt:24` (+ KDoc `:14-18`), `TurboMetaApplication.kt:36-40` |
| 2 — capturer could win the camera race | fallback gate `if (manager.currentCameraOwner != null)` → `if (manager.ownerCount > 0)`: any outstanding claim (including a feature between `acquire()` and `addCamera()`) takes the wait-then-`NO_FRAME` path; the capturer runs only with zero claims | `GlassesFrameProvider.kt:168-172` |
| 2 | Both directions documented in the class KDoc (numbered list rewritten + a paragraph on why `ownerCount` and not `cameraOwner`); the QuickVision "known limit" paragraph updated — same outcome (`NO_FRAME`, gateway retries), but the mechanism is now the claim wait, not a `CameraBusy` round trip | `GlassesFrameProvider.kt:61-84` |
| 3 — the `liveFrame()` gate had no test | `staleFrameAfterStopCameraIsNotServed` + `fallbackIsSkippedWhileAnotherOwnerHoldsAClaim` added | `SessionFrameProviderTest.kt:105-153` |
| minor 4 | `isForeground = { … ?: true }` → `?: false` (fail closed), with a comment citing spec §1 | `GlassesFrameProvider.kt:104-106` |
| minor 5 | `awaitLiveFrame` predicate `{ liveFrame() != null }` → `{ it != null && liveFrame() != null }`, so `first()` cannot return the emitted `null` when a frame lands between the emission and the predicate call; KDoc explains it | `GlassesFrameProvider.kt:226-234` |

Test-side follow-on (forced by the interface change, no test body touched):
`OpenClawCommandRouterTest.FakeFrameProvider` keeps its `var hasActiveDevice` and exposes it as
`override suspend fun awaitActiveDevice(timeoutMs: Long): Boolean = hasActiveDevice`
(`OpenClawCommandRouterTest.kt:18-19`). All 7 router tests pass unchanged.

### RED / GREEN

`./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.SessionFrameProviderTest'`

- **RED (both tests added, production untouched):** `BUILD FAILED` — `9 tests completed, 1 failed`

  ```
  SessionFrameProviderTest > fallbackIsSkippedWhileAnotherOwnerHoldsAClaim FAILED
      java.lang.AssertionError at SessionFrameProviderTest.kt:140
  ```

  XML: `java.lang.AssertionError: expected:<0> but was:<1>` — that is `assertEquals(0, captureCalls)`:
  with the old `currentCameraOwner != null` gate the snap entered the capturer while owner "B" held a
  claim, exactly the race in finding 2. (The returned value was already `NoFrame` only because the
  fake capturer's default outcome is `NoImage`; the capture *call* is what the test pins.)
- `staleFrameAfterStopCameraIsNotServed` was **green on arrival**, by construction: it is the
  regression guard finding 3 asked for on the existing `liveFrame()` deviation, and
  `GlassesSessionManager.stopCamera()` / `stopSession()` / `teardownAfterDeviceStop()` each clear
  `_latestFrame` on the same (main) thread that clears `cameraOwner`, so no JVM test can manufacture a
  genuinely orphaned bitmap without adding a production seam. It does fail if either half of the gate
  (`currentCameraOwner != null`, `sessionState == STARTED`) is dropped from `liveFrame()`, and it pins
  `streamStatus == "stopped"` / `hasFrame == false` after a stop — the wire-visible half of the
  deviation. Honest summary: 1 of the 2 new tests was RED→GREEN, the other is a green-on-arrival guard.
- **GREEN (after the production fixes):** `BUILD SUCCESSFUL in 4s`;
  `TEST-…SessionFrameProviderTest.xml`: `tests="9" skipped="0" failures="0" errors="0"`.

### Verification

| Command | Output |
|---|---|
| `./gradlew :app:testDebugUnitTest --tests '…SessionFrameProviderTest'` | RED first (above), then `BUILD SUCCESSFUL in 4s`, `tests="9" failures="0" errors="0"` |
| `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL`; scripted scan of all 10 `TEST-*.xml`: **`classes=10 tests=111 skipped=0 failures=0 errors=0`** (109 + 2), every `system-out`/`system-err` empty |
| `./gradlew :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL in 53s`, 92 actionable tasks, exit 0; `grep -c '^w:'` → **0** Kotlin warnings (none from the touched files) |

Re-run after the final KDoc-only edit: `:app:testDebugUnitTest` → `BUILD SUCCESSFUL` (UP-TO-DATE,
identical bytecode) and the assemble trio → `BUILD SUCCESSFUL`, `^w:` count 0.

### Concerns

1. **Task 6's brief needs a one-line patch.** `task-6-brief.md:213` gives its `FakeFrames` an
   `override val hasActiveDevice = true`; that member no longer exists. The replacement is
   `override suspend fun awaitActiveDevice(timeoutMs: Long) = true`. No other downstream task
   referenced the property (Task 7/8/9 do not).
2. **`install()` now creates `GlassesSessionManager` in `Application.onCreate()`**, i.e. before the
   Bluetooth runtime permissions are granted (`MainActivity.initializeSDK()` gates only the
   *ViewModel's* monitoring). `WearablesViewModel` already built the singleton at Activity creation,
   so this only moves creation slightly earlier, and `startMonitoring()`'s `.catch {}` keeps a failing
   device flow from crashing — but if `deviceSelector.activeDeviceFlow()` ever *throws*
   pre-permission, the collector terminates, `deviceJob` stays non-null and nothing restarts it, so
   `activeDevice` would stay null for the whole process. Worth a real-device check in Task 9 (grant
   the permissions after first launch, then run `camera.list`); the resilient fix would be to let
   `startMonitoring()` re-arm after a terminal failure, which is outside this fix round.
3. **`awaitActiveDevice` can cost up to 1.5 s on `device.status` / `camera.list` when no glasses are
   connected** — the flow emits `null` immediately only once the observer job has run; before that the
   command waits out the budget. That is the intended trade for finding 1 and stays well inside the
   gateway's node timeout, but the two cheap status commands are no longer guaranteed instant.
4. **The QuickVision `NO_FRAME` window changed mechanism, not outcome.** Task 8 step 8.7's README note
   should describe it as "another feature holds a claim → the snap waits, then answers NO_FRAME",
   not "the capturer gets CameraBusy"; the class KDoc in `GlassesFrameProvider.kt` now reads that way.
5. Concerns 1, 2, 4 and 5 of the original report (no JVM coverage for `create()`, pointer 2
   inspection-verified only, the README forward reference, `FrameSnapshot` array identity) are
   unchanged by this round.

## Fix round 2

Commit: **5dc880e** `test(android): pin the OpenClaw live-frame gate on session state; harden
GlassesSessionManager.startMonitoring against a throwing device flow`
Branch `android-v2` (parent `54ef3cd`, itself a docs-only commit on `a8d0136`). 4 files changed,
+126 / −13. Working tree clean afterwards.

Re-review gave one open finding (the stale-frame test doesn't pin the gate it claims to) and one
hardening note (a synchronous throw from the device flow can wedge `startMonitoring()` forever).
Both addressed; nothing else touched.

### What changed

| Item | Change | Where |
|---|---|---|
| 1 | KDoc on `staleFrameAfterStopCameraIsNotServed` rewritten: it now says up front that it is a state-transition guard, **not** a pin on the two-condition gate, because `stopCamera()` clears `cameraOwner` and `_latestFrame` together so a naive `liveFrame() = latestFrame.value` would already pass it; points to the new test for the actual pin | `SessionFrameProviderTest.kt:105-113` |
| 2 | New test `frameIsNotServedWhileSessionIsPaused`: owner "A" acquires, session STARTED, `addCamera`, `publishFrame` (asserts `hasFrame==true`/`streamStatus=="streaming"` first), then `factory.last.stateFlow.value = DeviceSessionState.PAUSED` (no new fake helper needed — `FakeGlassesSession.stateFlow` was already a public `MutableStateFlow`), then asserts `hasFrame==false`, `streamStatus=="waiting"`, and `snapshot(320, 0.6, 200)` returns `SnapshotResult.NoFrame` with `captureCalls==0` | `SessionFrameProviderTest.kt:126-160` (added `import com.meta.wearable.dat.core.session.DeviceSessionState`) |
| 3 | `GlassesSessionManager.startMonitoring()` rewritten: the flow construction + collection is wrapped in `runCatching { ... }.onFailure { ... }` inside the launched coroutine (rethrows `CancellationException`, otherwise logs `"device monitoring failed to start"` and nulls `deviceJob`). The coroutine is started `CoroutineStart.LAZY` and only `.start()`-ed **after** `deviceJob` is assigned — see "mutation/ordering hazard" below for why that's load-bearing, not decorative | `GlassesSessionManager.kt:153-186` (new imports: `CancellationException`, `CoroutineStart`) |
| 3 | `FakeDatDeviceObserver` gained `var throwOnFlow = false`; `activeDeviceInfoFlow()` throws (`error("activeDeviceInfoFlow() boom (throwOnFlow)")`) when set instead of returning `device` | `FakeDat.kt:123-131` |
| 3 | New test `startMonitoringSurvivesAThrowingDeviceFlow`: constructs a manager with `observer.throwOnFlow = true`, calls `startMonitoring()` (must not throw), asserts `hasSession==false` / `activeDevice.value==null`, then clears the flag, sets `observer.device.value = rayban`, calls `startMonitoring()` again and asserts `activeDevice.value == rayban` (i.e. the second call actually retried instead of no-op'ing) | `GlassesSessionManagerTest.kt:541-570` |

### Ordering hazard found while implementing item 3 (worth flagging)

The re-reviewer's literal instruction — wrap in `runCatching{}.onFailure{ deviceJob = null }` inside
`deviceJob = scope.launch { ... }` — does not actually work under an immediate/unconfined dispatcher
(`Dispatchers.Main.immediate` in production, `UnconfinedTestDispatcher` in tests). When the coroutine
body throws **before its first suspension point** (exactly this case: `activeDeviceInfoFlow()` throws
synchronously, before `.collect{}` ever suspends), `launch {}` runs the entire body inline and returns
only once it has completed — so the failure handler's `deviceJob = null` executes *before* the outer
`deviceJob = scope.launch { ... }` assignment has stored anything, and that assignment then
immediately overwrites the null with a reference to the now-completed job. Every later
`startMonitoring()` call sees `deviceJob != null` and returns early forever — the opposite of "a later
startMonitoring() can retry". This was not theoretical: implementing the literal instruction and
running the test reproduced it exactly (see RED #2 below). Fixed by starting the coroutine
`CoroutineStart.LAZY` and calling `.start()` only after `deviceJob = job` has already run, which
removes the ordering dependency regardless of dispatcher eagerness.

### Mutation check (item 2) — RED / GREEN

`./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.SessionFrameProviderTest'`

- **Baseline (test added, production gate intact):** `BUILD SUCCESSFUL`, `tests="10" failures="0"`.
- **RED (condition removed):** `liveFrame()` temporarily changed to
  `if (currentCameraOwner != null /* MUTATION-REMOVED: && sessionState.value == DeviceSessionState.STARTED */)`
  → `BUILD FAILED`:
  ```
  SessionFrameProviderTest > frameIsNotServedWhileSessionIsPaused FAILED
      java.lang.AssertionError at SessionFrameProviderTest.kt:156
  10 tests completed, 1 failed
  ```
  XML stack: `assertFalse(provider.hasFrame)` at `SessionFrameProviderTest.kt:171` (source line 156
  in the test method) — with the STARTED check gone, `currentCameraOwner != null` alone still made
  `liveFrame()` return the (still-present) frame while PAUSED, so `hasFrame` stayed `true`. Exactly
  one test failed; every other test in the class (including `staleFrameAfterStopCameraIsNotServed`)
  stayed green, confirming the mutation is caught precisely by the new test and nothing else.
- **GREEN (condition restored):** `BUILD SUCCESSFUL` (`FROM-CACHE`, byte-identical to the pre-mutation
  build), `tests="10" skipped="0" failures="0" errors="0"`.

### RED / GREEN (item 3)

`./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesSessionManagerTest'`

- **RED #1 (production untouched, test added):** `BUILD FAILED`:
  ```
  GlassesSessionManagerTest > startMonitoringSurvivesAThrowingDeviceFlow FAILED
      java.lang.AssertionError at GlassesSessionManagerTest.kt:554
  31 tests completed, 1 failed
  ```
  XML: `assertEquals(rayban, manager.activeDevice.value)` failed with `expected:<GlassesDeviceInfo(...)>
  but was:<null>` — the *second* `startMonitoring()` call was silently swallowed by the unfixed
  `if (deviceJob != null) return` guard (the first call's launched job had failed but `deviceJob` was
  never reset), matching the finding exactly. No stray uncaught-exception noise appeared in
  system-out/system-err — the first two assertions (`hasSession==false`, `activeDevice.value==null`)
  already passed, confirming the throw doesn't crash the process either way; only the retry was
  broken.
- **RED #2 (literal fix per the ruling's instruction, no `CoroutineStart.LAZY`):** same test, same
  failure, same line (`GlassesSessionManagerTest.kt:554` → `assertEquals` at :572) — this is the
  ordering-hazard reproduction described above; recorded to justify the `CoroutineStart.LAZY` change
  as necessary rather than decorative.
- **GREEN (LAZY-start fix applied):** `BUILD SUCCESSFUL`; `tests="31" skipped="0" failures="0"
  errors="0"` (30 baseline + 1 new).

### Verification

| Command | Result |
|---|---|
| `--tests SessionFrameProviderTest` (mutation check) | RED → GREEN as above; final `tests="10" failures="0" errors="0"` |
| `--tests GlassesSessionManagerTest` (RED → GREEN) | RED (x2) → GREEN as above; final `tests="31" failures="0" errors="0"` |
| `:app:testDebugUnitTest` (full suite) | `BUILD SUCCESSFUL`; scripted scan of all 10 `TEST-*.xml`: **`classes=10 tests=113 skipped=0 failures=0 errors=0`** (111 + 2), every `system-out`/`system-err` an empty `CDATA` block |
| `:app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL in 1m 5s`, 92 actionable tasks; `grep -ci warning` on the full log → **0** |

`android/local.properties` was never read or printed during this round.

### Concerns

1. **The ordering hazard is dispatcher-shape-general, not test-only.** It reproduces identically
   under `Dispatchers.Main.immediate` (production) because that dispatcher is also "run inline if
   already on the target thread", the same eagerness that triggers it under
   `UnconfinedTestDispatcher`. Anyone touching `startMonitoring()` again should keep the
   assign-before-start ordering (`deviceJob = job` before `job.start()`) intact — reintroducing
   `deviceJob = scope.launch { ... }` as a single expression silently reopens the wedge.
2. **`resetForTests()` does not touch `deviceJob`.** Pre-existing behavior, unchanged by this round:
   the device-observer job is treated as a process-lifetime singleton concern, not a per-test-reset
   one. Not in scope for this fix; noting only because `startMonitoring()` now has more internal
   state transitions than before.
3. Concerns 1–5 from the original report and concerns 1–4 from fix round 1 are otherwise unchanged
   by this round (fix round 1's concern 2 — the throwing-device-flow risk — is the finding this round
   closes).
