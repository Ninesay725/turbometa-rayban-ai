# Task 3 report — `GlassesSessionManager` + gateway interfaces + fakes + unit tests

**Status:** DONE
**Branch:** `android-v2`
**Commit:** `14b10d4` — `feat(android): add GlassesSessionManager (ref-counted DeviceSession owner, waits for STOPPED before re-create) with DAT gateway seams, fakes and unit tests`

---

## 1. What was implemented

All six files from the brief, in the brief's step order (3.1 → 3.8), with the code verbatim as
specified. No deviations were needed — see §5.

| Step | File | Contents |
|---|---|---|
| 3.1 | `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionState.kt` | `GlassesDeviceInfo`, `GlassesDisplayState`, `SessionStartResult`, `CameraError`, `CameraResult` |
| 3.2 | `android/app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt` | `SessionCreateResult`, `CameraAddResult`, `PhotoCaptureResult`, `GlassesCamera`, `GlassesSession`, `DatSessionFactory`, `DatDeviceObserver`, `DisplayAttacher` (+ `DisplayAttacher.None`) |
| 3.3 | `android/app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt` | `SdkGlassesCamera`, `SdkGlassesSession`, `WearablesDatAdapter` |
| 3.6 | `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt` | the process singleton |
| 3.4 | `android/app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt` | `FakeGlassesCamera`, `FakeGlassesSession(stopAsync)`, `FakeDatSessionFactory`, `FakeDatDeviceObserver`, `RecordingDisplayAttacher` |
| 3.4 | `android/app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt` | the 23 named tests |

Nothing outside these six files was touched (`git show --stat 14b10d4` = 6 files, 1171 insertions).
`DisplayAttacher` stays a no-op in Phase A (`DisplayAttacher.None`); `addDisplay` is never called and
`mwdat-display` is never imported by this package.

---

## 2. Exact public API produced (for Tasks 4/5 to check against)

Package `com.smartview.glassai.glasses`.

### Value types (`GlassesSessionState.kt`)

```kotlin
data class GlassesDeviceInfo(
    val id: String,
    val name: String,
    val deviceType: DeviceType,               // com.meta.wearable.dat.core.types.DeviceType
    val isDisplayCapable: Boolean,
    val compatibility: DeviceCompatibility,   // com.meta.wearable.dat.core.types.DeviceCompatibility
)

enum class GlassesDisplayState { NOT_ATTACHED, STARTING, STARTED, STOPPED }

enum class SessionStartResult { STARTED, CREATE_FAILED, NOT_STARTED }

sealed class CameraError {
    data class CameraBusy(val owner: String) : CameraError()
    object NoSession : CameraError()
    object SessionNotStarted : CameraError()
    data class Sdk(val error: DeviceSessionError) : CameraError()
}

sealed class CameraResult {
    data class Ready(val camera: GlassesCamera) : CameraResult()
    data class Failed(val error: CameraError) : CameraResult()
}
```

### Gateway seams (`DatGateway.kt`)

```kotlin
sealed class SessionCreateResult {
    data class Success(val session: GlassesSession) : SessionCreateResult()
    data class Failure(val error: DeviceSessionError) : SessionCreateResult()
}

sealed class CameraAddResult {
    data class Success(val camera: GlassesCamera) : CameraAddResult()
    data class Failure(val error: DeviceSessionError) : CameraAddResult()
}

sealed class PhotoCaptureResult {
    data class Success(val photo: PhotoData) : PhotoCaptureResult()
    data class Failure(val error: CaptureError) : PhotoCaptureResult()
}

interface GlassesCamera {
    val streamState: StateFlow<StreamState>          // com.meta.wearable.dat.camera.types.StreamState
    val videoFrames: Flow<VideoFrame>
    val streamErrors: Flow<StreamError>
    fun startStream(): StreamError?                  // null == success
    suspend fun capturePhoto(): PhotoCaptureResult
    fun stop()
}

interface GlassesSession {
    val state: StateFlow<DeviceSessionState>
    val errors: SharedFlow<DeviceSessionError>
    val nativeSession: DeviceSession?                // null in fakes; Phase C uses it for addDisplay()
    fun start()
    fun stop()
    fun addCamera(config: StreamConfiguration): CameraAddResult
}

interface DatSessionFactory { fun createSession(): SessionCreateResult }

interface DatDeviceObserver { fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?> }

interface DisplayAttacher {
    val displayState: StateFlow<GlassesDisplayState>
    fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?)
    fun detach()
    object None : DisplayAttacher   // Phase A default; both methods are no-ops
}
```

### Real adapters (`WearablesDatAdapter.kt`)

```kotlin
class SdkGlassesCamera(private val camera: Camera) : GlassesCamera
class SdkGlassesSession(private val session: DeviceSession) : GlassesSession   // nativeSession is non-null
class WearablesDatAdapter(
    private val deviceSelector: DeviceSelector = AutoDeviceSelector(),
) : DatSessionFactory, DatDeviceObserver
```

### Manager (`GlassesSessionManager.kt`)

```kotlin
class GlassesSessionManager internal constructor(
    private val sessionFactory: DatSessionFactory,
    private val deviceObserver: DatDeviceObserver,
    private val scope: CoroutineScope,
    private val displayAttacher: DisplayAttacher = DisplayAttacher.None,
) {
    companion object { fun getInstance(context: Context): GlassesSessionManager }

    val sessionState: StateFlow<DeviceSessionState>
    val displayState: StateFlow<GlassesDisplayState>
    val sessionError: SharedFlow<DeviceSessionError>
    val activeDevice: StateFlow<GlassesDeviceInfo?>
    val isFirmwareUpdateRequired: StateFlow<Boolean>
    val isDatAppUpdateRequired: StateFlow<Boolean>
    val hasSession: Boolean
    val isStoppingPreviousSession: Boolean
    val ownerCount: Int
    val currentCameraOwner: String?

    fun startMonitoring()
    fun acquire(owner: String)
    fun release(owner: String)
    fun ensureSession(): Boolean
    suspend fun ensureSessionStarted(timeoutMs: Long): SessionStartResult
    suspend fun awaitStarted(timeoutMs: Long): Boolean
    fun addCamera(owner: String, config: StreamConfiguration): CameraResult
    fun stopCamera(owner: String)
    fun stopSession()
}
```

Internal constants (private): `PREVIOUS_STOP_TIMEOUT_MS = 5_000L`, `ALREADY_EXISTS_RETRY_DELAY_MS = 1_000L`.

**Threading contract for Tasks 4/5:** every public function must be called on the main thread.
`getInstance()` builds the production scope as `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
`getInstance(context)` does not use `context` (the adapter needs none — `Wearables.initialize()` already
ran in `TurboMetaApplication.onCreate()`); the parameter is kept because the brief's Interfaces block
specifies it and Phase C's `DisplayAttacher` injection will need it.

---

## 3. TDD evidence

### RED — Step 3.5 (after writing the tests, before `GlassesSessionManager.kt` existed)

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:testDebugUnitTest --tests 'com.smartview.glassai.glasses.GlassesSessionManagerTest' \
  2>&1 | grep -E "^e: |BUILD" | head -20
```

Output (abridged) and breakdown:

```
> Task :app:compileDebugKotlin FAILED
e: .../services/QuickVisionService.kt:23:37 Unresolved reference 'StreamSession'.
e: .../services/QuickVisionService.kt:24:37 Unresolved reference 'startStreamSession'.
e: .../ui/screens/RTMPStreamingScreen.kt:36:43 Unresolved reference 'StreamSessionState'.
...
error files (unique) with counts:
     16 services/QuickVisionService.kt
      4 ui/screens/RTMPStreamingScreen.kt
     20 viewmodels/RTMPStreamingViewModel.kt
     39 viewmodels/WearablesViewModel.kt
errors mentioning glasses/: 0
lines mentioning GlassesSessionManagerTest or compileDebugUnitTestKotlin: 0
```

This is exactly what the brief predicts: `BUILD FAILED` at `:app:compileDebugKotlin`, errors confined
to the four unmigrated 0.4.0 call sites from Step 2.8, and the test source set never compiled
(`compileDebugUnitTestKotlin` never ran), so no test error is printed. The red run only proves the
module is still red; per the brief the in-Gradle green run is Task 5's Step 5.9.

### Step 3.7 — the brief's acceptance signal for "my files compile"

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e: " | grep -c "glassai/glasses/"
```

```
0
```

Expected `0`. ✔

### GREEN — extra out-of-band verification (not required by the brief)

Because these 23 tests are the contract Tasks 4 and 5 are checked against, I did not want to hand them
over unexecuted. I compiled **only** the `glasses/` package (main + test, one module, so `internal` is
visible exactly as AGP's friend-paths make it) with the same Kotlin **2.2.21** compiler and
`-jvm-target 17`, against the real DAT 0.9.0 AAR `classes.jar`s, `kotlinx-coroutines-core-jvm 1.10.2`,
`kotlinx-coroutines-test-jvm 1.10.2`, `junit 4.13.2` and `android-36/android.jar`, then ran JUnit 4.
This reads the repo files and writes only into the scratchpad; the repo is untouched.

Harness: `<scratchpad>/verify.sh` (kotlin-compiler-embeddable 2.2.21 launched directly). Two stubs
were needed to substitute for what AGP supplies in a real unit test:

* `android.util.Log` returning `0` — the exact behaviour of `testOptions.unitTests.isReturnDefaultValues = true`,
  which Task 1 already set in `app/build.gradle.kts`.
* `com.meta.wearable.dat.{core,camera}.R$string`, generated from each AAR's `R.txt`. AAR `R` fields are
  non-final, so `DeviceSessionError.<clinit>` reads them at runtime. **This is a harness artifact, not a
  product gap:** AGP's `app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar`
  already contains `com/meta/wearable/dat/core/R$string.class` and `com/meta/wearable/dat/camera/R$string.class`
  and is on the compile *and runtime* classpath — I verified that jar's contents on disk.

Result:

```
### kotlinc: compiling glasses/ main + test as one module
### compile OK
### running GlassesSessionManagerTest
PASS displayAttacherIsCalledOnStartedWithActiveDevice
PASS deviceStoppingSessionClearsStateAndNextAcquireRecreates
PASS releaseOfUnknownOwnerIsNoOp
PASS ensureSessionStartedReportsNotStartedWhenSessionStopsOrTimesOut
PASS createFailureIsReportedByEnsureSessionStartedOnly
PASS activeDeviceAndFirmwareFlagFollowObserver
PASS addCameraBeforeStartedFails
PASS acquireCreatesAndStartsOneSession
PASS awaitStartedTimesOut
PASS sessionStopsOnlyWhenLastOwnerReleases
PASS stopCameraDetachesAndLetsAnotherOwnerBorrow
PASS defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice
PASS sdkAddCameraFailureIsSurfaced
PASS stopSessionIsIdempotent
PASS ensureSessionStartedGivesUpWaitingForStoppedAfterTimeout
PASS sessionAlreadyExistsIsRetriedOnceByEnsureSessionStarted
PASS secondOwnerSharesTheSession
PASS awaitStartedResolvesTrueOnStartedAndFalseOnStopped
PASS addCameraWithoutSessionFails
PASS acquireAfterStopWaitsForStoppedBeforeCreatingSession
PASS releaseByCameraOwnerStopsTheCamera
PASS cameraIsLentToOneOwnerAndBusyForOthers
PASS sessionErrorsAreForwardedAndDatAppUpdateFlagged
---
Tests run: 23, Failures: 0, Ignored: 0
```

A second compile with warnings enabled (`-nowarn` removed) emitted **zero** Kotlin warnings for the
`glasses/` main and test sources.

---

## 4. SDK verification (`$SAMPLE` + AARs)

Every DAT 0.9.0 symbol the brief names was checked against the marketplace `CHANGELOG.md`, the
`CameraAccess` sample, **and** `javap` on the actual cached AARs
(`~/.gradle/caches/modules-2/files-2.1/com.meta.wearable/mwdat-{core,camera}/0.9.0/*.aar`):

| Symbol | Verified |
|---|---|
| `Wearables.createSession(DeviceSelector): DatResult<DeviceSession, DeviceSessionError>` | ✔ javap |
| `Wearables.devices: StateFlow<Set<DeviceIdentifier>>`, `Wearables.devicesMetadata: Map<DeviceIdentifier, StateFlow<Device>>` | ✔ javap |
| `DeviceSession.state` / `.errors` / `start()` / `stop()` | ✔ javap |
| `DeviceSessionState { IDLE, STARTING, STARTED, PAUSED, STOPPING, STOPPED }` (pkg `...core.session`) | ✔ javap |
| `addCamera(DeviceSession, StreamConfiguration): DatResult<Camera, DeviceSessionError>` (`com.meta.wearable.dat.camera.addCamera`) | ✔ javap `SessionCameraExtensionsKt` |
| `Camera.stream`, `Camera.stop()` | ✔ javap |
| `Stream.state`, `.videoStream`, `.errorStream`, `start(): DatResult<Unit, StreamError>`, `suspend capturePhoto(): DatResult<PhotoData, CaptureError>` (no args) | ✔ javap |
| `DatResult.fold(onSuccess, onFailure)` with the 2-arg `onFailure(error, cause)` | ✔ javap + `MockDeviceKitViewModel.kt:55-74` |
| `DeviceSelector.activeDeviceFlow()`, `AutoDeviceSelector()` no-arg ctor | ✔ javap |
| `Device.name/.deviceType/.compatibility/.isDisplayCapable()` | ✔ javap |
| `DeviceSessionError.{NO_ELIGIBLE_DEVICE, SESSION_ALREADY_EXISTS, CAPABILITY_DENIED, THERMAL_CRITICAL, DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED}` + `.description` | ✔ javap |
| `DeviceType.{UNKNOWN, RAYBAN_META, META_RAYBAN_DISPLAY}`, `DeviceCompatibility.{UNDEFINED, COMPATIBLE, DEVICE_UPDATE_REQUIRED}` | ✔ javap |
| `StreamConfiguration()` no-arg ctor; `StreamState.{STARTING, STOPPED}`; `CaptureError.NotStreaming` (object) | ✔ javap |
| `DeviceIdentifier.toString()` returns the raw `identifier` string (so `id.toString()` in the adapter is the id, not a data-class dump) | ✔ `javap -c` |

---

## 5. Deviations from the brief

**None.** Every package and member name in the brief matched the authoritative sources, so no
source-wins override was required. The brief's code was used verbatim in all six files.

---

## 6. Self-review

* **Completeness** — all eight steps executed in order; every checkbox's artifact exists. Test names,
  count (23) and file paths match the brief character-for-character (verified by listing the 23 `fun`
  names after `@Test`).
* **Signatures** — the manager's public surface was diffed against the brief's Interfaces block
  member by member; identical, including `ensureSession(): Boolean` and the `internal constructor`.
* **YAGNI** — nothing added beyond the brief. No helper, no extra overload, no logging framework,
  no extra dependency, no build-file change.
* **Scope** — `git show --stat` = exactly the six new files. Untracked `.agents/`, `.codex/` and
  `AGENTS.md` exist in the working tree; they are **not** mine and were deliberately left unstaged
  (the brief's `git add` names only the two `glasses` directories).
* **Test output pristine** — zero compiler warnings for the package; the 23 tests emit no stray
  output (`android.util.Log` returns `0` under `isReturnDefaultValues`, already configured).
* **Commit message** — the brief's exact text, no attribution lines. Committed on `android-v2`.
* `local.properties` was never read or printed. Only one Gradle invocation ran at a time.

---

## 7. Concerns

1. **`ensureSession()` has no test coverage.** It is in the brief's Interfaces block and is
   implemented, but none of the 23 tests exercise it (they all go through `acquire()` /
   `ensureSessionStarted()`). If Task 4/5 call it, its emit-on-failure behaviour
   (`_sessionState = STOPPED` + `sessionError.tryEmit`) is unverified. Low risk — three lines
   composed of already-tested helpers.
2. **`clearStopping()` cancels the very job it runs inside** when the outgoing session reports
   `STOPPED` through `stoppingJob`'s own collector. This works (the `first {}` has already returned
   and there is no further suspension point, so the `CancellationException` never materialises) and
   is covered by `acquireAfterStopWaitsForStoppedBeforeCreatingSession`, but it is subtle enough to
   be worth knowing about if Phase C extends the stop path.
3. **Main-thread contract is by convention, not enforced.** The manager's mutable fields (`session`,
   `stoppingSession`, `camera`, `cameraOwner`, `owners`) carry no lock; correctness relies on every
   caller being on `Dispatchers.Main.immediate`. That is the brief's chosen design and matches the
   ViewModel/`QuickVisionService` callers, so I did not add machinery — but Task 4/5 must not call
   `acquire`/`release`/`addCamera`/`stopCamera`/`stopSession` from a background dispatcher.
4. **The green run inside Gradle still belongs to Task 5.** My out-of-band run reproduces the real
   compiler, real SDK jars and real coroutines-test, but not AGP's exact test classpath. The one
   difference I had to stub — the AAR `R$string` classes — is provably present in AGP's
   `R.jar` (checked on disk), so I expect Step 5.9 to be green as-is.

---

## Fix round 1

Addresses the review's single Important finding: `ensureSession()` bypassed the STOPPED gate and had
no test. Both halves of concern #1 in §7 above are now closed.

### What changed

| File:line | Change |
|---|---|
| `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt:178` | **The fix.** `if (stoppingSession != null) return false` as the first statement of `ensureSession()`, so it can no longer call `createSessionIfNeeded()` while the SDK still holds the outgoing session in STOPPING (which would answer `SESSION_ALREADY_EXISTS`). |
| `android/app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt:167-176` | KDoc rewritten: states that the method refuses while the previous session is stopping, returning `false` **without** emitting, and directs callers that need a session after a stop to `ensureSessionStarted()` (which waits for STOPPED and retries once). `@return` now distinguishes the two false cases. |
| `android/app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt:150-173` | The 24th test, `ensureSessionReturnsFalseWhilePreviousSessionIsStopping`. |

No public signature changed (`fun ensureSession(): Boolean`, unchanged). The other 23 tests are
untouched. `git diff --numstat` for the fix = 9 insertions / 2 deletions (main) + 24 / 0 (test).

### Covering test

`ensureSessionReturnsFalseWhilePreviousSessionIsStopping` uses the existing async-stop fake
(`FakeDatSessionFactory.stopAsync = true` → `FakeGlassesSession.stop()` lands on STOPPING, not
STOPPED), exactly as `acquireAfterStopWaitsForStoppedBeforeCreatingSession` does:

1. `acquire("A")` → `emitStarted()` → `release("A")` — last owner leaves, so `stopSession()` parks S1
   in `stoppingSession`; `isStoppingPreviousSession` is true.
2. `ensureSession()` → asserts `false` **and** `factory.createCalls == 1` (no create attempted) and
   `hasSession == false`.
3. `first.emitStoppedByDevice()` → `isStoppingPreviousSession` is false.
4. `ensureSession()` → asserts `true`, `factory.createCalls == 2`, `factory.last.startCalls == 1`.

TDD order was honoured: with the test in place and the gate not yet added, the harness reported
`Tests run: 24, Failures: 1` — `FAIL ensureSessionReturnsFalseWhilePreviousSessionIsStopping`
(step 2's `assertFalse` fired, because the unguarded `ensureSession()` created a second session).
Adding the one-line gate turned it green with no other test affected.

### Commands run

**1. Unit tests — same out-of-band harness as §3 (`<scratchpad>/verify.sh`, kotlin-compiler-embeddable
2.2.21, `-jvm-target 17`, real DAT 0.9.0 `classes.jar`s, coroutines(-test) 1.10.2, JUnit 4.13.2,
`android-36/android.jar`; `android.util.Log` + AAR `R$string` stubs as documented in §3).** It was
re-run unmodified — a 23/23 baseline run before any edit confirmed the harness still reproduces.

```bash
cd '<scratchpad>' && bash verify.sh
```

```
### kotlinc: compiling glasses/ main + test as one module
### compile OK
### javac: stubs
### running GlassesSessionManagerTest
PASS displayAttacherIsCalledOnStartedWithActiveDevice
PASS deviceStoppingSessionClearsStateAndNextAcquireRecreates
PASS releaseOfUnknownOwnerIsNoOp
PASS ensureSessionStartedReportsNotStartedWhenSessionStopsOrTimesOut
PASS createFailureIsReportedByEnsureSessionStartedOnly
PASS activeDeviceAndFirmwareFlagFollowObserver
PASS addCameraBeforeStartedFails
PASS acquireCreatesAndStartsOneSession
PASS awaitStartedTimesOut
PASS ensureSessionReturnsFalseWhilePreviousSessionIsStopping
PASS sessionStopsOnlyWhenLastOwnerReleases
PASS stopCameraDetachesAndLetsAnotherOwnerBorrow
PASS defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice
PASS sdkAddCameraFailureIsSurfaced
PASS stopSessionIsIdempotent
PASS ensureSessionStartedGivesUpWaitingForStoppedAfterTimeout
PASS sessionAlreadyExistsIsRetriedOnceByEnsureSessionStarted
PASS secondOwnerSharesTheSession
PASS awaitStartedResolvesTrueOnStartedAndFalseOnStopped
PASS addCameraWithoutSessionFails
PASS acquireAfterStopWaitsForStoppedBeforeCreatingSession
PASS releaseByCameraOwnerStopsTheCamera
PASS cameraIsLentToOneOwnerAndBusyForOthers
PASS sessionErrorsAreForwardedAndDatAppUpdateFlagged
---
Tests run: 24, Failures: 0, Ignored: 0
```

**24/24, output pristine** — no stray logging, no stack traces. The warnings-enabled variant
(`verify-warn.sh`, `-nowarn` removed) still emits **zero** Kotlin warnings for the `glasses/` main and
test sources and also runs 24/24.

**2. Step 3.7 red-build check (Git Bash, single Gradle invocation, nothing else running):**

```bash
cd /d/Coding/Workspaces/Android/turbometa-rayban-ai/android
./gradlew :app:compileDebugKotlin 2>&1 | tee red2.log | grep -E "^(e: |BUILD)"
grep -E "^e: " red2.log | grep -c "glassai/glasses/"
```

```
> Task :app:compileDebugKotlin FAILED
BUILD FAILED in 2s

errors mentioning glassai/glasses/: 0
```

Error breakdown unchanged from §3 — still confined to the four unmigrated 0.4.0 call sites that
Tasks 4/5 own:

```
     16 services/QuickVisionService.kt
      4 ui/screens/RTMPStreamingScreen.kt
     20 viewmodels/RTMPStreamingViewModel.kt
     39 viewmodels/WearablesViewModel.kt
```

### Notes

* `local.properties` was never read or printed; only one Gradle invocation ran at a time.
* The gate is deliberately silent (no `Log.w`) and exactly one line, per the controller's ruling.
* §7 concerns 2 (`clearStopping()` cancels its own job) and 3 (main-thread contract by convention)
  are unchanged and still stand for Phase C / Tasks 4-5.
