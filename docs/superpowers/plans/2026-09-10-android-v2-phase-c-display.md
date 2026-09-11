# Android 2.0 Phase C: On-Glasses Display Cards — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render TurboMeta's results on the lenses of Meta Ray-Ban Display glasses (spec §7): a pure, JVM-tested card model (`DisplayCard` → `DisplayNode`), one SDK boundary file that turns nodes into the DAT 0.9.0 Display DSL, a `GlassesDisplayManager` that coalesces, paginates and resends cards, a `GlassesActionRouter` that turns glasses taps into phone-side navigation and services, hooks in Live AI / Quick Vision / LeanEat / OpenClaw, the `glasses_display_enabled` setting with a Home status row, a debug-only phone preview of every card, and a hardware checklist for the product owner — all on the shared `GlassesSessionManager` session without ever keeping a session alive for the display alone (spec §3 decision 1).

**Architecture:** `GlassesSessionManager` gains the display lifecycle itself (no more `DisplayAttacher` seam): whenever the shared `DeviceSession` is STARTED, the active device `isDisplayCapable` and the setting is on, it calls `session.addDisplay()` through a new `GlassesDisplay` gateway seam, mirrors `DisplayState` into `displayState`, and removes the display before every session stop. `GlassesDisplayManager` is the single exit for glasses content (`GlassesDisplaySink.show/showStatus/clear`): it keeps the current `DisplayCard`, derives a pure `DisplayNode` with localized `DisplayStrings`, sends it through `ContentScope.render()` in `DisplayCards.kt` (the only file that builds DSL content) while `displayState == STARTED`, coalesces streaming Live AI cards to one send per 600 ms, resends on every STARTED, and degrades `RENDERING_FAILED` to a text-only `Notice`. Button taps arrive from the SDK on an undocumented thread, hop to Main inside the manager and reach `GlassesActionRouter`, which emits `NavigationRequest`s collected by `TurboMetaNavigation`, starts `QuickVisionService`, pages cards, or calls the registered Live AI / OpenClaw controllers.

**Tech Stack:** Kotlin 2.2.21, AGP 8.11.1, Gradle 8.14.1, compileSdk/targetSdk 36, minSdk 31, JVM 17 target, Compose BOM 2026.05.01, DAT SDK 0.9.0 (`mwdat-core/camera/display` — `mwdat-display` is already a dependency in `app/build.gradle.kts:120`; `mwdat-mockdevice` debug/androidTest), kotlinx-coroutines-test 1.10.2, JUnit 4.13.2, AndroidX test runner (`am instrument`). No new dependencies.

## Global Constraints

- Toolchain is exactly Phase A's: Kotlin `2.2.21`, AGP `8.11.1`, Gradle wrapper 8.14.1, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 31`, `JavaVersion.VERSION_17` / `JvmTarget.JVM_17`; DAT `mwdat = "0.9.0"`. Do not change any of these and add no dependency.
- Run Gradle only from Git Bash inside `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android` as `./gradlew …` (never `gradlew.bat`); JDK 21 is on the host, the build targets 17.
- All work is on branch `android-v2` (base `25e6ae4`, working tree clean). Commit after every task with the exact `git` commands given; never squash tasks together.
- Never print, cat, or commit `android/local.properties`.
- Every new user-visible string goes into BOTH `android/app/src/main/res/values/strings.xml` and `android/app/src/main/res/values-zh-rCN/strings.xml` with the exact text given in this plan (Shared Interfaces → "New string keys"); a key present in one file and missing in the other is a task failure. Both files hold 450 `<string>` entries at the base commit and must keep parity after every task.
- `GlassesSessionManager` public mutators (`acquire/release/ensureSession/ensureSessionStarted/acquireAndStart/addCamera/stopCamera/stopSession/setDisplayEnabled/reattachDisplay/resetForTests`) are main-thread-only, now enforced by a debug-only `checkMain()` guard. The one documented exception stays `publishFrame()` (StateFlow + volatile only, called from the frame worker). `GlassesDisplayManager.show/showStatus/clear` and `GlassesActionRouter.dispatch` are main-thread-only as well.
- Test counts in this plan are MINIMUMS ("at least N"), never exact numbers; adding tests is always allowed. Phase B's baseline is 154 JVM tests per variant and 21 instrumented tests; nothing may go below it.
- JVM unit tests run with `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:95-96`): `android.util.Log` returns 0, `android.os.Looper.getMainLooper()` returns null, `org.json`/`Bitmap`/`Context.getString()` return defaults or null. Therefore the pure card layer (Task 2) imports nothing from `android.*` or `com.meta.*`, ViewModel tests inject `strings: (Int) -> String`, and no JVM test executes an SDK `ContentScope` block.
- `./gradlew :app:connectedDebugAndroidTest` is blocked on this host (Windows `winnat` reserves TCP 9577-9676); instrumented tests run through `adb shell am instrument` exactly as in Task 9 / the Verification story.
- Phase C additions:
  - `DisplayCards.kt` is the ONLY file that builds DSL content or references `IconName`, `TextStyle`, `TextColor`, `ButtonStyle`, `ButtonGroupAlignment`, `IconStyle`, `Direction`, `Alignment`, `FlexBoxBackground`, `FlexBoxScope`, `ButtonGroupScope`. The opaque receiver type `com.meta.wearable.dat.display.views.ContentScope` may additionally appear (as `import …views.ContentScope` and nothing else from that package) in `DatGateway.kt`, `WearablesDatAdapter.kt`, `GlassesDisplayManager.kt` and the test fake `FakeDat.kt`, because `Display.sendContent` takes `ContentScope.() -> Unit`. Reviewer check: `grep -rn "dat.display.views" app/src` must show only `DisplayCards.kt` plus `views.ContentScope` imports in those four files.
  - The pure card layer (`DisplayCard.kt`, `DisplayNode.kt`, `DisplayPagination.kt`) imports only `kotlin.*` and `com.smartview.glassai.glasses.*`. `com.smartview.glassai.models.FoodNutritionResponse` never enters it (its `healthScoreColor`/`healthRatingColor` getters are Compose `Color`, `healthScoreText` is hard-coded Chinese): `DisplayCard.LeanEat` carries plain ints/strings and `LeanEatCardMapper.kt` (package `glasses`, Task 2, deliberately outside the pure layer) maps a `FoodNutritionResponse` into it. Reviewer check (I7): `grep -l "import com.smartview.glassai.models"` over the pure files is empty.
  - No images and no video on cards in Phase C (`DisplayNode` has no `Image`; `FlexBoxScope.image()` and `ContentScope.video()` are never called). Text-only cards are the spec §11 mitigation for camera + display sharing Bluetooth bandwidth; images return in Phase E (music cover art) once the hardware checklist has measured the streaming impact.
  - SDK `onClick` callbacks never touch the manager, a ViewModel or a `StateFlow` directly: `DisplayCards.render()` calls the `dispatch` lambda it was given, and `GlassesDisplayManager.dispatchFromSdk()` hops to `Dispatchers.Main.immediate` before calling the router.
  - `addDisplay()` is only ever called while `sessionState == STARTED`; whenever a display is attached, `removeDisplay()` is called before `session.stop()` (`stopSession()`), on the device-side STOPPED (`teardownAfterDeviceStop()`) and when the setting is switched off — and never when no display is attached (`detachDisplay()` is a no-op while `display == null`, so Ray-Ban Meta, MockDeviceKit and every Phase A/B test path see zero `removeDisplay()` calls). The display never keeps a session alive: nobody calls `acquire()` for the display except the two documented session-only owners (`"QuickVisionDisplay"` in Task 5, `"OpenClawChat"` which already exists).
  - Out of scope for Phase C (do not implement, do not stub): images/bitmaps on cards, video, Music/WeChat card *content* (Phase E — Phase C only declares their data classes and placeholder nodes), automatic `VideoQuality` downgrade while a card is on screen (spec §3 decision 8: measure first, hardware checklist item H6), bringing `MainActivity` to the foreground from a glasses tap while no Activity collects `navigationRequests` (the tap is dropped — accepted Phase C limitation, Phase E scope), the RTMP `onAuthErrorRtmp` service-stop gap (Phase D), TTS / translate / camera hub (Phase D).

## File Structure

| Path (relative to `android/`) | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt` | Modify | delete `DisplayAttacher` and `GlassesSession.nativeSession`; add `GlassesDisplay`, `DisplaySendResult`, `DisplayAddResult`; `GlassesSession.addDisplay()/removeDisplay()` |
| `app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt` | Modify | `SdkGlassesDisplay`; `SdkGlassesSession.addDisplay/removeDisplay` over the SDK extension functions; drop `nativeSession` |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionState.kt` | Modify | `GlassesDisplayState` gains `STOPPING`, `CLOSED` |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt` | Modify | display lifecycle inside the manager, `isDisplayAvailable`, `setDisplayEnabled`, `currentDisplay()`, `acquireAndStart` (owner re-check), `checkMain()`, fuller `resetForTests()`, debug stop-path timing log |
| `app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt` | Modify | `glasses_display_enabled` key (default true) getter/setter |
| `app/src/main/java/com/smartview/glassai/glasses/DisplayCard.kt` | Create | sealed `DisplayCard` (+ `LeanEatFood`), `LiveAIPhase`, `DisplayAction`, `DisplayIcon`, text limits, `plainText()`, `fallbackNotice()` |
| `app/src/main/java/com/smartview/glassai/glasses/DisplayNode.kt` | Create | pure render tree `DisplayNode` + node enums, `DisplayStrings`, `DisplayCard.toNode()` |
| `app/src/main/java/com/smartview/glassai/glasses/DisplayPagination.kt` | Create | `paginate(text, maxChars)` |
| `app/src/main/java/com/smartview/glassai/glasses/LeanEatCardMapper.kt` | Create | `FoodNutritionResponse.toLeanEatCard()` — the only glasses-side file importing `models.*` (outside the pure layer) |
| `app/src/main/java/com/smartview/glassai/glasses/DisplayCards.kt` | Create | the SDK boundary: `ContentScope.render(node, dispatch)`, `DisplayIcon.toIconName()` |
| `app/src/main/java/com/smartview/glassai/glasses/ResourceDisplayStrings.kt` | Create | `DisplayStrings` over `R.string.*` |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayManager.kt` | Create | `GlassesDisplaySink`, `DisplayStatusProvider`, `GlassesDisplayManager` (serial sender, coalescing, resend, degrade) |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayIntegration.kt` | Create (Task 3) / Modify (Task 4) | process singletons: `displayManager(app)`, `router(app)`, `navigationRequests`, `install(app)`, `ensureStarted(app)` |
| `app/src/main/java/com/smartview/glassai/glasses/GlassesActionRouter.kt` | Create | `NavigationRequest`, `LiveAiController`, `OpenClawController`, `GlassesControllerRegistry`, `GlassesActionRouter` |
| `app/src/main/java/com/smartview/glassai/glasses/AppStatusProvider.kt` | Create | production `DisplayStatusProvider` (device name, Live AI key, OpenClaw state) |
| `app/src/main/java/com/smartview/glassai/glasses/LiveAiCardMapper.kt` | Create | pure `OmniRealtimeViewModel` state → `DisplayCard.LiveAI` |
| `app/src/main/java/com/smartview/glassai/services/QuickVisionDisplayPolicy.kt` | Create | pure dwell / claim decisions for the Quick Vision card |
| `app/src/main/java/com/smartview/glassai/MainActivity.kt` | Modify | passes `GlassesDisplayIntegration.navigationRequests`; `ensureStarted()` after the Bluetooth grant |
| `app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` | Modify | `GlassesDisplayIntegration.install(this)` after `OpenClawIntegration.install(this)` |
| `app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` | Modify | `navigationRequests` collection, `Screen.GlassesDisplayPreview` route |
| `app/src/main/java/com/smartview/glassai/viewmodels/OmniRealtimeViewModel.kt` | Modify | injectable sink/registry, Live AI cards, `LiveAiController` |
| `app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` | Modify | display claim, Notice/QuickVision cards, result dwell |
| `app/src/main/java/com/smartview/glassai/viewmodels/LeanEatViewModel.kt` | Modify | injectable sink/strings/analyzer, LeanEat cards |
| `app/src/main/java/com/smartview/glassai/services/LeanEatService.kt` | Modify | implements `LeanEatAnalyzer` |
| `app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt` | Modify | sink/registry, `acquireAndStart`, OpenClaw cards, `OpenClawController` |
| `app/src/main/java/com/smartview/glassai/viewmodels/SettingsViewModel.kt` | Modify | `isDisplayEnabled` + `setDisplayEnabled` |
| `app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` | Modify | exposes `displayState`, `isDisplayCapable`, `isDisplayAvailable` |
| `app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` | Modify | "Glasses Display" section with the toggle; Developer entry for the preview |
| `app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` | Modify | display status row in `DeviceStatusCard` |
| `app/src/main/res/values/strings.xml`, `values-zh-rCN/strings.xml` | Modify | 20 new keys (9 in Task 3, 9 in Task 7, 2 in Task 8) |
| `app/src/debug/java/com/smartview/glassai/debug/GlassesDisplayPreviewEntry.kt` | Create | debug entry (`isAvailable = true`) |
| `app/src/release/java/com/smartview/glassai/debug/GlassesDisplayPreviewEntry.kt` | Create | release stub (`isAvailable = false`) |
| `app/src/debug/java/com/smartview/glassai/debug/GlassesDisplayPreviewScreen.kt` | Create | Compose renderer for `DisplayNode` in a 600×600 black box, card picker, page stepper, Snackbar + real router dispatch |
| `app/src/debug/java/com/smartview/glassai/debug/DisplayPreviewSamples.kt` | Create | one sample per `DisplayCard` subtype |
| `app/src/debug/java/com/smartview/glassai/debug/MockDeviceState.kt` | Create | process-level optimistic power/don/fold flags for the MockDeviceKit screen |
| `app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitViewModel.kt` | Modify | reads/writes `MockDeviceState` |
| `app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt` | Modify | delete `RecordingDisplayAttacher` and `nativeSession`; add `FakeGlassesDisplay`; display members on `FakeGlassesSession` |
| `app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt` | Modify | rewrite the two attacher tests and the two `attacher.detachCalls` assertions; new display / `acquireAndStart` / `resetForTests` tests |
| `app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` | Modify (Task 5) | composing test: session-only `"QuickVisionDisplay"` claim vs the real capturer |
| `app/src/test/java/com/smartview/glassai/glasses/FixedDisplayStrings.kt` | Create | `DisplayStrings` with fixed labels for JVM tests |
| `app/src/test/java/com/smartview/glassai/glasses/DisplayPaginationTest.kt`, `DisplayCardTest.kt`, `DisplayNodeTest.kt`, `LeanEatCardMapperTest.kt` | Create | pure-layer tests + the LeanEat mapper test |
| `app/src/test/java/com/smartview/glassai/glasses/DisplayIconMappingTest.kt`, `GlassesDisplayManagerTest.kt` | Create | icon mapping totality/injectivity; manager behaviour with `FakeGlassesDisplay` |
| `app/src/test/java/com/smartview/glassai/glasses/RecordingDisplaySink.kt`, `FakeControllerRegistry.kt` | Create | sink / registry fakes for router and ViewModel tests |
| `app/src/test/java/com/smartview/glassai/glasses/GlassesActionRouterTest.kt`, `AppStatusProviderTest.kt` | Create | router and status provider tests |
| `app/src/test/java/com/smartview/glassai/glasses/LiveAiCardMapperTest.kt`, `app/src/test/java/com/smartview/glassai/services/QuickVisionDisplayPolicyTest.kt` | Create | Task 5 pure tests |
| `app/src/test/java/com/smartview/glassai/viewmodels/LeanEatViewModelTest.kt` | Create | LeanEat hook tests with fakes |
| `app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt`, `WearablesViewModelTest.kt` | Modify | new constructor args; OpenClaw card / `acquireAndStart` tests; display exposure tests |
| `app/src/testDebug/java/com/smartview/glassai/debug/DisplayPreviewSamplesTest.kt`, `MockDeviceStateTest.kt` | Create | debug-variant JVM tests (run by `testDebugUnitTest` only) |
| `app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` | Modify | `startMonitoring()` after reset; display-never-attached assertions; `acquireAndStart` across STOPPING |
| `app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt` | Modify | `startMonitoring()` after reset; in-memory settings store (test hygiene) |
| `app/src/androidTest/java/com/smartview/glassai/services/openclaw/InMemoryOpenClawSettingsStore.kt` | Create | copy of the JVM fake for the instrumented test |
| `app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt` | Modify | `glasses_display_enabled` default + persistence |
| `docs/superpowers/reviews/phase-c/hardware-checklist.md` | Create | product-owner hardware checklist with a results table |
| `docs/superpowers/reviews/phase-c/task-9-report.md` | Create | Phase C verification record |
| `android/CHANGELOG.md`, `android/README.md`, `README.md` (repo root) | Modify | Display sections |

---

## Shared Interfaces

Everything below is the contract between tasks. Signatures are exact; a drafter may add `private`/`internal` helpers but must not rename, re-type or move anything listed here. Packages: everything under `glasses/` is `package com.smartview.glassai.glasses`; test fakes are in the same package under `app/src/test`.

### S1. Gateway seam (Task 1 produces; Tasks 3, 9 consume)

```kotlin
// DatGateway.kt — add (imports: com.meta.wearable.dat.display.types.DisplayState,
// com.meta.wearable.dat.display.types.DisplayError, com.meta.wearable.dat.display.views.ContentScope)

/** Outcome of Display.sendContent()/clearDisplay(). */
sealed class DisplaySendResult {
    object Sent : DisplaySendResult()
    data class Failed(val error: DisplayError) : DisplaySendResult()
}

/** Outcome of DeviceSession.addDisplay(). */
sealed class DisplayAddResult {
    data class Success(val display: GlassesDisplay) : DisplayAddResult()
    data class Failure(val error: DeviceSessionError) : DisplayAddResult()
}

/** One Display capability. Wraps com.meta.wearable.dat.display.Display (a Closeable interface). */
interface GlassesDisplay {
    val state: StateFlow<DisplayState>
    /** Replaces the whole lens content; only meaningful while [state] is STARTED. */
    suspend fun sendContent(block: ContentScope.() -> Unit): DisplaySendResult
    suspend fun clearDisplay(): DisplaySendResult
    /**
     * Same as [close()] on 0.9.0 hardware: Display.stop() is Capability.stop(), which calls terminate() —
     * the DisplaySessionImpl goes STOPPED then CLOSED and the capability is removed from the session
     * (a later removeDisplay() answers CAPABILITY_NOT_FOUND). Kept on the seam for D2; the manager never calls it.
     */
    fun stop()
    fun close()
}

interface GlassesSession {
    // …existing members unchanged (state, errors, start, stop, addCamera). `nativeSession` (lines 57-58) is DELETED:
    // its only reader was the deleted test (GlassesSessionManagerTest.kt:455); the display goes through addDisplay() below…
    /** Wraps DeviceSession.addDisplay(DisplayConfiguration()). Only valid while STARTED. */
    fun addDisplay(): DisplayAddResult
    /** Wraps DeviceSession.removeDisplay(); null on success, else the SDK error. */
    fun removeDisplay(): DeviceSessionError?
}
```

`DisplayAttacher` (interface + `None`) is deleted from `DatGateway.kt`; `RecordingDisplayAttacher` is deleted from `FakeDat.kt`; the `displayAttacher` constructor parameter of `GlassesSessionManager` is deleted; `GlassesSession.nativeSession` is deleted from the interface, `SdkGlassesSession` (`WearablesDatAdapter.kt:66`) and `FakeGlassesSession` (`FakeDat.kt:65`).

```kotlin
// WearablesDatAdapter.kt — add
class SdkGlassesDisplay(private val display: Display) : GlassesDisplay   // fold() of the DatResults; stop()/close() delegate
// SdkGlassesSession — add
override fun addDisplay(): DisplayAddResult   // session.addDisplay(DisplayConfiguration()).fold(Success(SdkGlassesDisplay(it)), Failure(error))
override fun removeDisplay(): DeviceSessionError?   // session.removeDisplay().fold(null, error)
```

```kotlin
// GlassesSessionState.kt — replace the enum (1:1 with DisplayState plus NOT_ATTACHED)
enum class GlassesDisplayState { NOT_ATTACHED, STARTING, STARTED, STOPPING, STOPPED, CLOSED }
```

### S2. Test fakes for the seam (Task 1 produces; Tasks 3–7, 9 consume)

```kotlin
// FakeDat.kt — add
class FakeGlassesDisplay : GlassesDisplay {
    val stateFlow = MutableStateFlow(DisplayState.STARTING)
    var sendCalls = 0
    /** Recorded but never executed on the JVM (ContentScope internals use org.json stubs). */
    val sentBlocks = mutableListOf<ContentScope.() -> Unit>()
    /** One-shot failures consumed in order by sendContent(). */
    val scriptedSendFailures = ArrayDeque<DisplayError>()
    var clearCalls = 0
    var stopCalls = 0
    var closeCalls = 0
    override val state: StateFlow<DisplayState> = stateFlow
    override suspend fun sendContent(block: ContentScope.() -> Unit): DisplaySendResult
    override suspend fun clearDisplay(): DisplaySendResult       // clearCalls++, Sent
    override fun stop()                                           // stopCalls++, CLOSED (models Capability.stop() == terminate(); see S1)
    override fun close()                                          // closeCalls++, CLOSED
    fun emitStarted()                                             // STARTED
    fun emitStopped()                                             // STOPPING then STOPPED (the display stays attached — see S3 "self-reported STOPPED")
}

// FakeGlassesSession — add
var addDisplayCalls = 0
var removeDisplayCalls = 0
var nextAddDisplayFailure: DeviceSessionError? = null
/** One-shot: consumed by the next removeDisplay(). */
var nextRemoveDisplayFailure: DeviceSessionError? = null
val displays = mutableListOf<FakeGlassesDisplay>()
val display: FakeGlassesDisplay get() = displays.last()
override fun addDisplay(): DisplayAddResult      // counts; failure if scripted; else new FakeGlassesDisplay (STARTING) appended
/**
 * ALWAYS counts (so a manager that calls it with no display attached is caught by `removeDisplayCalls == 0`
 * assertions), returns and consumes nextRemoveDisplayFailure, and sets `displays.lastOrNull()?.stateFlow` to
 * CLOSED when a display exists. Never throws when `displays` is empty.
 */
override fun removeDisplay(): DeviceSessionError?
```

### S3. `GlassesSessionManager` additions (Task 1 produces; Tasks 3–7, 9 consume)

```kotlin
class GlassesSessionManager internal constructor(
    private val sessionFactory: DatSessionFactory,
    private val deviceObserver: DatDeviceObserver,
    private val scope: CoroutineScope,
    /** Read lazily, at most once, the first time a display-capable device is seen; afterwards [setDisplayEnabled] is the only writer. Production: APIKeyManager.isGlassesDisplayEnabled(). */
    private val displayEnabled: () -> Boolean = { true },
)

/** Display capability state, 1:1 from DisplayState; NOT_ATTACHED while no display is attached. */
val displayState: StateFlow<GlassesDisplayState>
/** activeDevice?.isDisplayCapable == true && the glasses_display_enabled setting. */
val isDisplayAvailable: StateFlow<Boolean>
/** The attached display, or null. Main thread. */
fun currentDisplay(): GlassesDisplay?
/** Persisted by the caller (SettingsViewModel); true triggers maybeAttachDisplay(), false detachDisplay(). Main thread. */
fun setDisplayEnabled(enabled: Boolean)
/**
 * acquire(owner) (session-only, no camera intent) then the ensureSessionStarted(timeoutMs) sequence, with one
 * addition: after every suspension point (awaitPreviousSessionStopped(), the SESSION_ALREADY_EXISTS retry delay)
 * it re-checks `owner in owners` and returns NOT_STARTED WITHOUT calling createSessionIfNeeded() when the owner
 * already released — so an owner that leaves during the STOPPING wait never leaves an owner-less session behind
 * (spec §3 decision 1, I1). The call session-only owners make so they get a session even across a STOPPING window
 * (Phase B Minor 10).
 */
suspend fun acquireAndStart(owner: String, timeoutMs: Long): SessionStartResult
/**
 * detachDisplay() then maybeAttachDisplay() — the same path as setDisplayEnabled(false); setDisplayEnabled(true).
 * Main thread. Declared in Task 1 (one line, tested) but NOT called by any Phase C production code: it is the
 * pre-decided fallback for a display that sleeps into STOPPED (see the "self-reported STOPPED" bullet below and H7/H8).
 */
fun reattachDisplay()
/** How many times addDisplay() was attempted since construction/reset (instrumented test hook). */
@VisibleForTesting internal val displayAttachAttempts: Int
```

Semantics (binding):
- `maybeAttachDisplay()` is private and idempotent: no-op unless `session != null && sessionState == STARTED && display == null && isDisplayAvailableNow()`. It runs on (1) every STARTED in `onSessionState` (including PAUSED→STARTED resumes), (2) every `activeDevice` emission while `sessionState == STARTED` (metadata can arrive after STARTED), (3) `setDisplayEnabled(true)`, (4) `reattachDisplay()`. On `DisplayAddResult.Failure` it logs, emits the error on `sessionError`, leaves `displayState` at NOT_ATTACHED and does not retry until the next trigger. On success it stores the display and launches `displayStateJob` mapping `display.state` → `displayState`.
- `detachDisplay()` is private and is a NO-OP unless a display is attached: its first line is `val d = display ?: return` (and it needs `session != null` only to call `removeDisplay()`; with the display non-null the session is always the one that created it). Then: cancels `displayStateJob`, nulls `display`, calls `session.removeDisplay()`, on a non-null error `runCatching { d.close() }`, then sets `displayState = NOT_ATTACHED`. Error logging: `SESSION_ALREADY_STOPPED` and `CAPABILITY_NOT_FOUND` are EXPECTED answers (after a device-side stop the SDK's `DeviceSession.handleExternalTermination()` has already cleared the capability map and closed the display; after `Display.stop()`/`close()` the capability is already gone) and are logged at `Log.d`; every other `DeviceSessionError` is logged at `Log.e`. `close()` is called in both cases (harmless: `Capability.terminate()` is guarded by its `terminated` flag). Triggers: (1) first thing in `stopSession()` before the camera stop, (2) first thing in `teardownAfterDeviceStop()`, (3) `setDisplayEnabled(false)`, (4) `resetForTests()` (which then calls `stopSession()` — thanks to the guard at most ONE real `removeDisplay()` happens per attached display, and none when nothing was attached). Consequence for the baseline: Ray-Ban Meta, MockDeviceKit and every Phase A/B JVM test that stops/releases a session make zero `removeDisplay()` calls.
- A display that reports STOPPED or CLOSED on its own (L0 back gesture, display disconnect, possibly display sleep) is NOT re-added automatically (spec §7); `displayState` simply mirrors it until the next detach/attach trigger. Stated consequence (verified in the 0.9.0 bytecode: `DisplaySessionImpl` only ever sets STOPPED from `handleChannelDisconnected`/`disconnectActiveDevice`, never re-enters STARTING/STARTED and never terminates itself): after a self-reported STOPPED the `Display` stays in the SDK session's capability map and `display != null` blocks a re-add (so there is no `CAPABILITY_ALREADY_ADDED` risk), but every later `sendContent` answers `INVALID_SESSION_STATE`, i.e. the next feature's `show()` silently does nothing until a session-level detach/attach trigger runs. Pre-decided fallback, NOT wired in Phase C: if hardware item H7 shows that display sleep surfaces as `STOPPED` (rather than staying STARTED and waking on the next send), a follow-up commit `fix(android): phase-c verification — reattach on STOPPED` makes `GlassesDisplayManager.show()/showStatus()` call `sessionManager.reattachDisplay()` exactly once when `displayState.value == STOPPED && currentDisplay() != null` (the card is then resent by the STARTED collector; the L0 back gesture of H8 is unaffected because no feature calls `show()` then).
- `stopSession()` and `stopCamera()` log the wall time of the SDK `camera.stop()` and `session.stop()` calls (`SystemClock.elapsedRealtime()` deltas, `Log.d(TAG, "camera.stop() took N ms")` / `"session.stop() took N ms"`) inside `if (BuildConfig.DEBUG)` — Phase B Recommendation 2, read by hardware item H13. On the JVM `SystemClock.elapsedRealtime()` returns 0 (`isReturnDefaultValues`), which is harmless.
- `checkMain()` is private and called at the top of every public mutator listed in Global Constraints: `if (BuildConfig.DEBUG) { val main = Looper.getMainLooper(); if (main != null) check(Looper.myLooper() === main) { "GlassesSessionManager: <fn> must be called on the main thread" } }` — inert on the JVM (no Looper) and in release builds.
- `resetForTests()` additionally: `detachDisplay()`, `deviceJob?.cancel(); deviceJob = null`, `_activeDevice.value = null`, `_lastSessionError.value = null`, the lazily read display-enabled value is forgotten, `displayAttachAttempts = 0`. Consequence: every instrumented `setUp()` calls `manager.startMonitoring()` after `getInstance()`.
- `getInstance(context)` wires `displayEnabled = { APIKeyManager.getInstance(context.applicationContext).isGlassesDisplayEnabled() }` and nothing else changes; `Wearables.initialize()` remains the Application's job.

```kotlin
// APIKeyManager.kt — add (companion: private const val KEY_GLASSES_DISPLAY_ENABLED = "glasses_display_enabled")
fun isGlassesDisplayEnabled(): Boolean          // default true
fun setGlassesDisplayEnabled(enabled: Boolean)
```

### S4. Pure card layer (Task 2 produces; Tasks 3–8 consume)

```kotlin
// DisplayCard.kt
enum class LiveAIPhase { CONNECTING, LISTENING, PROCESSING, SPEAKING, IDLE }

/** App-level icon subset; every member maps 1:1 (same name) to a DAT IconName in DisplayCards.kt. */
enum class DisplayIcon {
    SMART_GLASSES, META_AI, STAR_CIRCLE_TRIANGLE_AI, MAGIC_WAND,
    SPEECH_BUBBLE, THREE_DOT_SPEECH_BUBBLE, SPEECH_BUBBLE_OFF,
    EYE, FOUR_CORNER_FRAME, VIDEO_CAMERA,
    FORK_KNIFE, PIZZA_SLICE, HEART, CODE,
    CHECKMARK, CHECKMARK_CIRCLE, X, EXCLAMATION_TRIANGLE, EXCLAMATION_CIRCLE, I_CIRCLE,
    ARROW_LEFT, ARROW_RIGHT, TRIANGLE_LEFT_VERTICAL_LINE, TRIANGLE_RIGHT_VERTICAL_LINE, TRIANGLE_RIGHT, TWO_LINES_PARALLEL,
    TWO_ARROWS_CLOCKWISE, LIGHT_BULB, THREE_HORIZONTAL_LINES, THREE_DOTS_HORIZONTAL,
    SPEAKER_WITH_THREE_ARCS, SPEAKER_OFF, MUSIC_NOTE, ENVELOPE_OPEN, HEADPHONES,
}

sealed interface DisplayAction {
    data object StartLiveAI : DisplayAction
    data object StartQuickVision : DisplayAction
    data object StartLeanEat : DisplayAction
    data object EndLiveAI : DisplayAction
    /** Show [card] (already carrying the target page index). */
    data class Page(val card: DisplayCard) : DisplayAction
    data object BackToMenu : DisplayAction
    data object OpenClawSnap : DisplayAction
    data object MusicPlayPause : DisplayAction
    data object MusicNext : DisplayAction
    data object MusicPrev : DisplayAction
}

sealed interface DisplayCard {
    /** L0 menu. */
    data class Status(val deviceName: String, val liveAiReady: Boolean, val openClawConnected: Boolean) : DisplayCard
    /** "Looking…", errors, degrade path. No buttons. */
    data class Notice(val title: String, val body: String, val icon: DisplayIcon = DisplayIcon.I_CIRCLE) : DisplayCard
    data class LiveAI(val phase: LiveAIPhase, val userText: String?, val assistantText: String, val isFinal: Boolean) : DisplayCard
    data class QuickVision(val modeName: String, val resultText: String, val page: Int = 0) : DisplayCard
    /** Pure data only (no models.FoodNutritionResponse — it carries Compose Color getters); built by LeanEatCardMapper.kt. Grams/kcal are whole numbers (the mapper applies toInt()). */
    data class LeanEat(
        val totalCalories: Int, val totalProtein: Int, val totalFat: Int, val totalCarbs: Int, val healthScore: Int,
        val foods: List<LeanEatFood>, val suggestions: List<String>, val page: Int = 0,
    ) : DisplayCard
    data class OpenClaw(val userText: String?, val replyText: String, val isFinal: Boolean, val page: Int = 0) : DisplayCard
    /** Phase E fills these in; Phase C declares them so the sealed hierarchy does not change. */
    data class WeChat(val sender: String, val preview: String) : DisplayCard
    data class Music(val title: String, val artist: String, val isPlaying: Boolean) : DisplayCard

    companion object {
        const val PAGE_CHARS = 280
        const val LIVE_AI_ASSISTANT_CHARS = 320   // assistantText.takeLast()
        const val USER_TEXT_CHARS = 120           // userText.take() (LiveAI and OpenClaw)
    }
}

/** One food line on the LeanEat card. */
data class LeanEatFood(val name: String, val portion: String, val calories: Int)

/** The card's main text without layout (used by the degrade path and tests). */
fun DisplayCard.plainText(): String
/** Text-only replacement sent once after RENDERING_FAILED: Notice(title = card title, body = plainText().take(PAGE_CHARS), icon = I_CIRCLE). Titles: Status→deviceName, Notice→title, LiveAI→strings.liveAi, QuickVision→modeName, LeanEat→strings.leanEat, OpenClaw→strings.openClaw, WeChat→sender, Music→title. */
fun DisplayCard.fallbackNotice(strings: DisplayStrings): DisplayCard.Notice
/** Number of pages the card has under PAGE_CHARS (1 for un-paged cards). */
fun DisplayCard.pageCount(): Int
```

```kotlin
// LeanEatCardMapper.kt — package com.smartview.glassai.glasses, Task 2, NOT part of the pure layer (it is the one glasses-side
// file that imports com.smartview.glassai.models.FoodNutritionResponse / FoodItem; it reads only foods, totalCalories,
// totalProtein, totalFat, totalCarbs, healthScore, suggestions, FoodItem.name/portion/calories — never healthScoreColor/
// healthScoreText/healthRatingColor). Doubles become whole numbers with toInt().
fun FoodNutritionResponse.toLeanEatCard(page: Int = 0): DisplayCard.LeanEat
```

```kotlin
// DisplayPagination.kt
/**
 * Splits [text] into pages of at most [maxChars] characters, preferring (in order) the last
 * sentence end (。！？.!? or '\n') inside the window, then the last comma (，,), then the last
 * space, then a hard cut. Pages are trimmed; blank text yields listOf(""). Never returns an empty list.
 */
fun paginate(text: String, maxChars: Int = DisplayCard.PAGE_CHARS): List<String>
```

```kotlin
// DisplayNode.kt
enum class NodeTextStyle { HEADING, BODY, META }
enum class NodeTextColor { PRIMARY, SECONDARY }
enum class NodeButtonStyle { PRIMARY, SECONDARY, OUTLINE }
enum class NodeBackground { NONE, CARD }
enum class NodeAlignment { START, CENTER, END, STRETCH }

sealed interface DisplayNode {
    data class Column(
        val children: List<DisplayNode>, val gap: Int = 0, val padding: Int = 0,
        val paddingTop: Int? = null, val paddingBottom: Int? = null, val paddingStart: Int? = null, val paddingEnd: Int? = null,
        val background: NodeBackground = NodeBackground.NONE,
        val alignment: NodeAlignment = NodeAlignment.START, val crossAlignment: NodeAlignment = NodeAlignment.START,
        val flexGrow: Float = 0f,
    ) : DisplayNode
    data class Row(/* same fields as Column */) : DisplayNode
    data class Text(val text: String, val style: NodeTextStyle = NodeTextStyle.BODY, val color: NodeTextColor = NodeTextColor.PRIMARY, val flexGrow: Float = 0f) : DisplayNode
    data class Icon(val icon: DisplayIcon, val outline: Boolean = false) : DisplayNode
    data class Button(val label: String, val style: NodeButtonStyle = NodeButtonStyle.PRIMARY, val icon: DisplayIcon? = null, val action: DisplayAction) : DisplayNode
    data class ButtonGroup(val buttons: List<Button>, val alignment: NodeAlignment = NodeAlignment.CENTER) : DisplayNode
}

/** Localized labels the cards need; production = ResourceDisplayStrings, tests = FixedDisplayStrings. */
interface DisplayStrings {
    val liveAi: String; val quickVision: String; val leanEat: String; val openClaw: String
    val connecting: String; val listening: String; val processing: String; val speaking: String; val connected: String
    val needsApiKey: String; val disconnected: String
    val prev: String; val next: String; val done: String; val again: String; val snap: String; val end: String
    val looking: String; val analyzing: String
    val calories: String; val protein: String; val fat: String; val carbs: String; val kcal: String; val gram: String; val healthScore: String
}

/** Always returns a DisplayNode.Column (the single root flexBox). Applies the text limits. */
fun DisplayCard.toNode(strings: DisplayStrings): DisplayNode.Column
```

Layout rules `toNode` must follow (the preview screen and hardware checklist verify them):
- Root: `Column(padding = 24, gap = 12)`. Every card starts with `Row(gap = 8, crossAlignment = CENTER) { Icon, Text(title, HEADING) }`. Buttons always live in `Row(gap = 8, alignment = CENTER, crossAlignment = CENTER) { ButtonGroup(...) }` as the last child (the DisplayAccess sample's shape).
- Status: title = `deviceName`, icon `SMART_GLASSES`; two `Text(BODY, SECONDARY)` lines `"${liveAi} · ${if (liveAiReady) connected else needsApiKey}"` and `"${openClaw} · ${if (openClawConnected) connected else disconnected}"`; buttons `[liveAi PRIMARY META_AI → StartLiveAI, quickVision SECONDARY EYE → StartQuickVision, leanEat SECONDARY FORK_KNIFE → StartLeanEat]`.
- Notice: icon = `icon`, title, `Text(body, BODY)`; no buttons.
- LiveAI: icon by phase (CONNECTING→`TWO_ARROWS_CLOCKWISE`, LISTENING→`SPEECH_BUBBLE`, PROCESSING→`THREE_DOT_SPEECH_BUBBLE`, SPEAKING→`SPEAKER_WITH_THREE_ARCS`, IDLE→`META_AI`), title = phase label (`connecting/listening/processing/speaking/connected`); optional `Text(userText.take(120), META, SECONDARY)`; `Text(assistantText.takeLast(320), BODY)`; buttons `[end OUTLINE X → EndLiveAI]`.
- QuickVision: icon `EYE`, title = `modeName`; `pages = paginate(resultText)`, `p = page.coerceIn(0, pages.lastIndex)`; `Text(pages[p], BODY)`; if `pages.size > 1` → `Text("${p + 1}/${pages.size}", META, SECONDARY)`; buttons: `prev OUTLINE ARROW_LEFT → Page(copy(page = p - 1))` only if `p > 0`, `next OUTLINE ARROW_RIGHT → Page(copy(page = p + 1))` only if `p < pages.lastIndex`, `again SECONDARY TWO_ARROWS_CLOCKWISE → StartQuickVision`, `done PRIMARY CHECKMARK → BackToMenu`.
- LeanEat (all values are the card's own ints/strings — no `FoodNutritionResponse` here): icon `FORK_KNIFE`, title = `strings.leanEat`; page 0 = totals: `Text("${strings.calories} ${totalCalories} ${strings.kcal}", BODY)`, `Text("${strings.protein} ${totalProtein}${strings.gram} · ${strings.fat} ${totalFat}${strings.gram} · ${strings.carbs} ${totalCarbs}${strings.gram}", BODY, SECONDARY)`, `Text("${strings.healthScore} ${healthScore}/100", BODY)`; pages 1..n = `paginate(foods.joinToString("\n") { "${it.name} ${it.portion} ${it.calories} ${strings.kcal}" } + "\n" + suggestions.joinToString("\n"))` (omitted entirely when that text is blank); indicator and prev/next as QuickVision; `done PRIMARY CHECKMARK → BackToMenu`.
- Root `flexGrow` is always `0f`: the SDK's `ContentScope.flexBox` (the root) has no `flexGrow` parameter (only `FlexBoxScope.flexBox` has one), so `render()` ignores it on the root and `toNode` never sets it (`DisplayNodeTest.everyNodeTreeUsesOnlyColumnRowTextIconButtonButtonGroup` also asserts `root.flexGrow == 0f`).
- OpenClaw: icon `CODE` when `isFinal`, `THREE_DOT_SPEECH_BUBBLE` otherwise, title = `openClaw`; optional `Text(userText.take(120), META, SECONDARY)`; `pages = paginate(replyText)`; `Text(pages[p].ifBlank { processing }, BODY)`; indicator/prev/next as QuickVision; `snap PRIMARY VIDEO_CAMERA → OpenClawSnap`, `done OUTLINE CHECKMARK → BackToMenu`.
- WeChat (placeholder): icon `ENVELOPE_OPEN`, title = `sender`, `Text(preview, BODY)`, `done PRIMARY CHECKMARK → BackToMenu`.
- Music (placeholder): icon `MUSIC_NOTE`, title = `title`, `Text(artist, BODY, SECONDARY)`, buttons `[prev OUTLINE TRIANGLE_LEFT_VERTICAL_LINE → MusicPrev, "" PRIMARY (TWO_LINES_PARALLEL if isPlaying else TRIANGLE_RIGHT) → MusicPlayPause, next OUTLINE TRIANGLE_RIGHT_VERTICAL_LINE → MusicNext]`.

```kotlin
// app/src/test/…/glasses/FixedDisplayStrings.kt
/** Every label equals its property name ("liveAi", "prev", …) unless overridden. */
data class FixedDisplayStrings(override val liveAi: String = "liveAi", /* … every DisplayStrings member with its name as default … */) : DisplayStrings
```

### S5. SDK boundary and display manager (Task 3 produces; Tasks 4–8 consume)

```kotlin
// DisplayCards.kt — the ONLY DSL-building file
/** Exhaustive interpreter. [node] must be a Column or Row (one root flexBox), else IllegalArgumentException. ButtonGroup → buttonGroup(alignment) { button(label, style, iconName) { dispatch(action) } }; Button → button(...); Icon → icon(name, FILLED/OUTLINE); Text → text(content, style, color, flexGrow); root Column/Row → ContentScope.flexBox(direction, gap, alignment, crossAlignment, padding, paddingTop/Bottom/Start/End, background) — the root's flexGrow is IGNORED (the SDK root builder has no such parameter); nested Column/Row → FlexBoxScope.flexBox(same + flexGrow). */
fun ContentScope.render(node: DisplayNode, dispatch: (DisplayAction) -> Unit)
/** Total and injective; every DisplayIcon maps to the IconName of the same name. */
fun DisplayIcon.toIconName(): IconName
```

```kotlin
// ResourceDisplayStrings.kt
class ResourceDisplayStrings(context: Context) : DisplayStrings
// keys: feature_liveai_title, feature_quickvision_title, feature_leaneat_title, openclaw_title, connecting, liveai_listening,
// processing, liveai_speaking, liveai_connected, display_status_needs_api_key, disconnected, display_prev, display_next,
// display_done, display_again, display_snap, display_end, display_looking, display_analyzing, leaneat_calories, leaneat_protein,
// leaneat_fat, leaneat_carbs, leaneat_kcal, leaneat_gram, leaneat_health_score  (the display_* keys arrive in Task 7; Task 3
// adds them to BOTH strings files itself with the texts from the "New string keys" table so it compiles — Task 7 then finds them present)
```

```kotlin
// GlassesDisplayManager.kt
/** What feature code talks to. Main thread. */
interface GlassesDisplaySink {
    fun show(card: DisplayCard)
    /** Back to the L0 menu: forgets the feature card and sends Status (built by the DisplayStatusProvider). */
    fun showStatus()
    /** Forgets the feature card and clears the lenses. */
    fun clear()
}

fun interface DisplayStatusProvider {
    fun currentStatus(): DisplayCard.Status
}

class GlassesDisplayManager internal constructor(
    private val sessionManager: GlassesSessionManager,
    private val scope: CoroutineScope,                 // production: SupervisorJob() + Dispatchers.Main.immediate
    private val strings: DisplayStrings,
    private val statusProvider: DisplayStatusProvider,
    private val mainDispatcher: CoroutineDispatcher,   // production: Dispatchers.Main.immediate; tests: the test dispatcher
    private val sendDispatcher: CoroutineDispatcher,   // production: Dispatchers.IO (the sample sends on IO); tests: the test dispatcher
    private val clock: () -> Long,                     // production: SystemClock::elapsedRealtime; tests: { testScheduler.currentTime }
) : GlassesDisplaySink {
    companion object {
        const val LIVE_AI_MIN_INTERVAL_MS = 600L
        /** Delegates to GlassesDisplayIntegration.displayManager(context.applicationContext as Application). */
        fun getInstance(context: Context): GlassesDisplayManager
    }
    /** Set by GlassesDisplayIntegration to GlassesActionRouter::dispatch (Task 4); null → actions are logged and dropped. */
    var actionHandler: ((DisplayAction) -> Unit)?
    /** The active feature card; null while the L0 Status menu is (or should be) shown. */
    val currentCard: StateFlow<DisplayCard?>
    /** The last card the display accepted (Sent); null until the first success. Test/diagnostic read. */
    val lastSent: StateFlow<DisplayCard?>
    override fun show(card: DisplayCard)
    override fun showStatus()
    override fun clear()
    /** The lambda handed to render(): hops to [mainDispatcher] then calls [actionHandler]. Never touches state on the caller's thread. */
    @VisibleForTesting internal fun dispatchFromSdk(action: DisplayAction)
}
```

Manager semantics (binding):
- One serial sender: `pending: MutableStateFlow<SendRequest?>` with `private sealed class SendRequest { data class Card(val card: DisplayCard, val seq: Long); data class Clear(val seq: Long) }` (`seq` so re-enqueuing the same card is a new value) collected with `collectLatest` — intermediate requests are dropped and a newer request cancels an in-flight WAIT (the STARTED gate or the Live AI delay), never an in-flight send (next bullets). A request is sent only when `sessionManager.displayState.value == STARTED` and `sessionManager.currentDisplay() != null`; otherwise it is left for the STARTED collector.
- STARTED collector: on every `displayState` transition to STARTED → enqueue `currentCard.value ?: statusProvider.currentStatus()` (this is also the resend after display sleep/INVALID_SESSION_STATE and the automatic L0 menu of spec decision 1).
- Live AI coalescing: `private var lastLiveAiSentAt: Long? = null`. For `LiveAI` cards with `isFinal == false`: if `lastLiveAiSentAt == null` send immediately (the first streaming card is never delayed); else `delay((600 - (clock() - lastLiveAiSentAt)).coerceAtLeast(0))` inside `collectLatest`, so a newer card replaces the waiting one; `isFinal == true` sends immediately. Every sent LiveAI card (final or not) sets `lastLiveAiSentAt = clock()`. Other card types are never delayed.
- Send, in this order on the manager's scope (Main): `val display = sessionManager.currentDisplay() ?: return` (main-thread read, S3); `val node = card.toNode(strings)` (pure, cheap, on Main); then `withContext(NonCancellable) { val result = withContext(sendDispatcher) { display.sendContent { render(node, ::dispatchFromSdk) } }; onSendResult(card, result) }` — the SDK's `waitForDisplayResponse` is a `withTimeout`, i.e. genuinely cancellable, so the send AND its bookkeeping run under `NonCancellable`: conflation happens only before a send starts, and every completed send is accounted for (`lastSent`, fallback). `onSendResult` runs back on Main. Results: `Sent` → `lastSent = card`. `Failed(INVALID_SESSION_STATE)` → keep `currentCard`, do nothing (the next STARTED resends). `Failed(RENDERING_FAILED)` → log and enqueue `card.fallbackNotice(strings)` exactly once per original card (a failing fallback is logged and dropped; `currentCard` stays the original so paging still works). `Failed(DEVICE_DISCONNECTED | UNEXPECTED_ERROR)` → log, drop.
- `show(card)`: `currentCard = card`, enqueue `Card`. `showStatus()`: `currentCard = null`, enqueue `Card(statusProvider.currentStatus())`. `clear()`: `currentCard = null`, enqueue `Clear` — it goes through the SAME serial sender (ordered with sends, so `clear(); show(card)` always ends with the card on the lens) and runs `withContext(NonCancellable) { withContext(sendDispatcher) { display.clearDisplay() } }` only while STARTED with a display; otherwise it is dropped (nothing to clear) and, unlike a `Card`, it is NOT resent by the STARTED collector.
- When the display detaches (NOT_ATTACHED/CLOSED) `currentCard` is kept, so a feature card survives a display drop inside one feature; every feature hook calls `showStatus()` when its feature ends (invariant I4) so a stale card never greets the next session.

```kotlin
// GlassesDisplayIntegration.kt (Task 3 part)
object GlassesDisplayIntegration {
    /** Process singleton; creates GlassesSessionManager.getInstance(app) on first use (so never before the Bluetooth grant). */
    fun displayManager(app: Application): GlassesDisplayManager
    /** Replaced by Task 4 with AppStatusProvider; Task 3 ships DeviceNameStatusProvider (device name from activeDevice, both flags false). */
    internal var statusProviderFactory: (Application) -> DisplayStatusProvider
}
class DeviceNameStatusProvider(private val sessionManager: GlassesSessionManager) : DisplayStatusProvider
```

```kotlin
// app/src/test/…/glasses/RecordingDisplaySink.kt
class RecordingDisplaySink : GlassesDisplaySink {
    val shown = mutableListOf<DisplayCard>()
    var statusCalls = 0
    var clearCalls = 0
    val last: DisplayCard? get() = shown.lastOrNull()
}
```

### S6. Router, navigation and wiring (Task 4 produces; Tasks 5–8 consume)

```kotlin
// GlassesActionRouter.kt
sealed interface NavigationRequest {
    data object LiveAI : NavigationRequest
    data object LeanEat : NavigationRequest
    data object OpenClaw : NavigationRequest
}

interface LiveAiController { fun end() }
interface OpenClawController { fun snapAndSend() }

/** Registration API the ViewModels use (register in init, unregister in onCleared); unregister ignores a controller that is not the registered one. */
interface GlassesControllerRegistry {
    fun registerLiveAi(controller: LiveAiController)
    fun unregisterLiveAi(controller: LiveAiController)
    fun registerOpenClaw(controller: OpenClawController)
    fun unregisterOpenClaw(controller: OpenClawController)
}

/**
 * Also the GlassesControllerRegistry (one process singleton, no separate `controllers` object — see Self-Review).
 * Navigation limitation (accepted for Phase C, Phase E owns the fix): [navigation] has replay = 0, so a glasses tap
 * that arrives while no Activity collects `navigationRequests` (app backgrounded, e.g. a Status-card tap during a
 * wake-word Quick Vision dwell) is dropped with a Log.w and does NOT bring MainActivity forward.
 */
class GlassesActionRouter(
    private val sink: GlassesDisplaySink,
    private val navigation: MutableSharedFlow<NavigationRequest>,   // replay = 0, extraBufferCapacity = 4, tryEmit
    private val startQuickVision: () -> Unit,
) : GlassesControllerRegistry {
    /** Main thread. StartLiveAI → LiveAI; StartQuickVision → startQuickVision(); StartLeanEat → LeanEat; EndLiveAI → liveAi?.end() (else no-op); Page(card) → sink.show(card); BackToMenu → sink.showStatus(); OpenClawSnap → openClaw?.snapAndSend() ?: navigation OpenClaw; Music* → Log.i only. */
    fun dispatch(action: DisplayAction)
}
```

```kotlin
// AppStatusProvider.kt
class AppStatusProvider(
    private val sessionManager: GlassesSessionManager,
    private val hasLiveAiKey: () -> Boolean,                       // production: APIProviderManager.getInstance(app).getLiveAIAPIKey(APIKeyManager.getInstance(app)).isNotBlank()
    private val openClawState: () -> OpenClawConnectionState,      // production: OpenClawNodeService.getInstance(app).connectionState.value
) : DisplayStatusProvider   // Status(deviceName = activeDevice.value?.name ?: "", liveAiReady = hasLiveAiKey(), openClawConnected = openClawState() == Connected)
```

```kotlin
// GlassesDisplayIntegration.kt (Task 4 additions)
object GlassesDisplayIntegration {
    val navigationRequests: SharedFlow<NavigationRequest>
    fun router(app: Application): GlassesActionRouter              // singleton; startQuickVision = startForegroundService(QuickVisionService ACTION_CAPTURE_AND_ANALYZE) exactly like PorcupineWakeWordService.triggerQuickVision()
    /** Application.onCreate: never throws, no I/O; wires router + status provider factory; calls ensureStarted(app) only when BLUETOOTH_CONNECT is already granted (same gate as OpenClawIntegration). */
    fun install(app: Application)
    /** Idempotent: creates displayManager(app), sets actionHandler = router(app)::dispatch, and starts the OpenClaw-state observer (Connected/Disconnected transitions → showStatus() when currentCard == null && displayState == STARTED). MainActivity calls it right after the Bluetooth grant. */
    fun ensureStarted(app: Application)
}
```

`TurboMetaNavigation(wearablesViewModel, onRequestWearablesPermission, navigationRequests: SharedFlow<NavigationRequest>)` collects in `LaunchedEffect(navigationRequests)` and navigates `Screen.LiveAI / Screen.LeanEat / Screen.OpenClaw` with `launchSingleTop = true`. `MainActivity` passes `GlassesDisplayIntegration.navigationRequests` and calls `GlassesDisplayIntegration.ensureStarted(application)` inside `startWearablesMonitoring()` after `wearablesViewModel.startMonitoring()`.

```kotlin
// app/src/test/…/glasses/FakeControllerRegistry.kt
class FakeControllerRegistry : GlassesControllerRegistry {
    var liveAi: LiveAiController? = null
    var openClaw: OpenClawController? = null
    var liveAiUnregisterCalls = 0
    var openClawUnregisterCalls = 0
}
```

### S7. Feature hooks (Tasks 5–6 produce)

```kotlin
// LiveAiCardMapper.kt (pure; ViewState is OmniRealtimeViewModel.ViewState, loadable on the JVM)
object LiveAiCardMapper {
    /** Connecting→CONNECTING, Recording→LISTENING, Processing→PROCESSING, Speaking→SPEAKING, Idle/Connected/Error→IDLE; assistantText = currentTranscript.ifBlank { lastAssistantMessage ?: "" }; isFinal = currentTranscript.isBlank(); userText = userTranscript.takeIf { it.isNotBlank() }. */
    fun map(viewState: OmniRealtimeViewModel.ViewState, userTranscript: String, currentTranscript: String, lastAssistantMessage: String?): DisplayCard.LiveAI
}

// OmniRealtimeViewModel
class OmniRealtimeViewModel internal constructor(application: Application, private val sink: GlassesDisplaySink, private val controllers: GlassesControllerRegistry) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, GlassesDisplayIntegration.displayManager(application), GlassesDisplayIntegration.router(application))
}
// init: registers `object : LiveAiController { override fun end() = disconnect() }`; one collector of combine(viewState, userTranscript, currentTranscript, messages) → sink.show(LiveAiCardMapper.map(...)) while isConnected || viewState is Connecting; the transition to Idle (disconnect) → sink.showStatus(); onCleared unregisters.

// QuickVisionDisplayPolicy.kt
object QuickVisionDisplayPolicy {
    const val RESULT_DWELL_MS = 15_000L
    const val ERROR_DWELL_MS = 5_000L
    /** The success path's pre-Phase-C delay (`delay(500)`, QuickVisionService.kt:299); the service passes it as [fallbackMs] there. */
    const val DEFAULT_DWELL_MS = 500L
    /**
     * RESULT_DWELL_MS (success) / ERROR_DWELL_MS (failure) only while displayState == STARTED && claimHeld;
     * otherwise [fallbackMs] — the caller's pre-Phase-C delay: DEFAULT_DWELL_MS on the success path,
     * 2_000 (QuickVisionService.FAIL_DWELL_FALLBACK_MS) in failAndFinish (`delay(2000)`, :320).
     */
    fun dwellMs(displayState: GlassesDisplayState, claimHeld: Boolean, success: Boolean, fallbackMs: Long): Long
    fun wantsDisplayClaim(isDisplayAvailable: Boolean): Boolean = isDisplayAvailable
}
// QuickVisionService: const val DISPLAY_OWNER = "QuickVisionDisplay"; private const val FAIL_DWELL_FALLBACK_MS = 2_000L

// LeanEatViewModel
fun interface LeanEatAnalyzer { suspend fun analyzeFood(image: Bitmap): Result<FoodNutritionResponse> }   // in LeanEatViewModel.kt; LeanEatService : LeanEatAnalyzer
class LeanEatViewModel internal constructor(
    application: Application,
    private val sink: GlassesDisplaySink,
    private val strings: (Int) -> String,
    private val apiKey: () -> String?,
    private val analyzerFactory: (String) -> LeanEatAnalyzer,
) : AndroidViewModel(application) { constructor(application: Application) }
// analyzeFood(): Notice(strings(R.string.feature_leaneat_title), strings(R.string.display_analyzing), FORK_KNIFE) on Analyzing; response.toLeanEatCard() (LeanEatCardMapper.kt) on Result; Notice(title, message, EXCLAMATION_TRIANGLE) on Error; reset()/retakePhoto()/onCleared() → showStatus()

// OpenClawViewModel — internal constructor gains `sink: GlassesDisplaySink` and `controllers: GlassesControllerRegistry` (after `decodeImage`, before `decodeDispatcher`); public constructor passes the integration singletons.
// companion: const val SESSION_START_TIMEOUT_MS = 15_000L
// private var sessionStartJob: Job? = null
// enterScreen(): if (!sessionHeld) { sessionHeld = true; sessionStartJob = viewModelScope.launch { sessionManager().acquireAndStart(OWNER, SESSION_START_TIMEOUT_MS) } }; connectIfNeeded()
// leaveScreen(): …existing stopListening()/releaseSco()/flushPendingResponse()…; if (sessionHeld) { sessionHeld = false; sessionStartJob?.cancel(); sessionStartJob = null; sessionManager().release(OWNER) }; sink.showStatus()
//   (the cancel is the first guard against an owner-less session; the manager's own `owner in owners` re-check in acquireAndStart is the second)
// cards: onChatEvent(text, isFinal) → show(OpenClaw(userText = lastUserText, replyText = text, isFinal)); sendText()/sendAsrText()/snapAndSend() → lastUserText = text; show(OpenClaw(text, "", isFinal = false))
// registers `object : OpenClawController { override fun snapAndSend() = this@OpenClawViewModel.snapAndSend() }` in init; unregisters in onCleared
```

### S8. Phone UI (Task 7 produces)

```kotlin
// SettingsViewModel
val isDisplayEnabled: StateFlow<Boolean>                 // seeded from apiKeyManager.isGlassesDisplayEnabled()
fun setDisplayEnabled(enabled: Boolean)                  // apiKeyManager.setGlassesDisplayEnabled(enabled); GlassesSessionManager.getInstance(app).setDisplayEnabled(enabled); updates the flow

// WearablesViewModel (delegating getters next to isFirmwareUpdateRequired)
val displayState: StateFlow<GlassesDisplayState>         // sessionManager.displayState
val isDisplayCapable: StateFlow<Boolean>                 // activeDevice.map { it?.isDisplayCapable == true }.stateIn(viewModelScope, Eagerly, false)
val isDisplayAvailable: StateFlow<Boolean>               // sessionManager.isDisplayAvailable

// HomeScreen.DeviceStatusCard — new parameters
isDisplayCapable: Boolean, isDisplayAvailable: Boolean, displayState: GlassesDisplayState
// DisplayStatusRow shown only when isDisplayCapable: text = display_status_off when !isDisplayAvailable; else STARTING → display_status_preparing,
// STARTED → display_status_ready, STOPPING/STOPPED/CLOSED → display_status_stopped, NOT_ATTACHED → display_status_not_attached
```

### S9. Debug preview and MockDeviceKit state (Task 8 produces)

```kotlin
// app/src/debug/…/debug/GlassesDisplayPreviewEntry.kt  (release: isAvailable = false, Screen {} empty, same shape as MockDeviceKitEntry)
object GlassesDisplayPreviewEntry {
    const val isAvailable: Boolean = true
    @Composable fun Screen(onBackClick: () -> Unit)
}
// Navigation.kt: object GlassesDisplayPreview : Screen("glasses_display_preview"); SettingsScreen gains onNavigateToGlassesDisplayPreview: () -> Unit = {}

// app/src/debug/…/debug/DisplayPreviewSamples.kt
object DisplayPreviewSamples {
    /** One entry per DisplayCard subtype, in declaration order: label → card. */
    fun all(strings: DisplayStrings): List<Pair<String, DisplayCard>>
}

// app/src/debug/…/debug/GlassesDisplayPreviewScreen.kt
@Composable fun GlassesDisplayPreviewScreen(onBackClick: () -> Unit)
@Composable fun DisplayNodePreview(node: DisplayNode, onAction: (DisplayAction) -> Unit, modifier: Modifier = Modifier)   // 600×600 black box scaled to fit; HEADING 28sp / BODY 20sp / META 16sp; Icon → Material icon or a labelled box; Button → Button/OutlinedButton

// app/src/debug/…/debug/MockDeviceState.kt
data class MockDeviceFlags(val isPoweredOn: Boolean = false, val isDonned: Boolean = false, val isUnfolded: Boolean = false)
/** Process-level optimistic flags keyed by DeviceIdentifier string; survives screen re-entry (Phase B Task 9 D6). */
object MockDeviceState {
    val flags: StateFlow<Map<String, MockDeviceFlags>>
    fun get(deviceId: String): MockDeviceFlags
    fun update(deviceId: String, transform: (MockDeviceFlags) -> MockDeviceFlags)
    fun remove(deviceId: String)
    fun clear()
}
```

### S10. Owner names used with `GlassesSessionManager`

| Owner | Kind | Who |
|---|---|---|
| `"WearablesViewModel"` | camera | Live AI / Live Stream / Quick Vision screen stream (existing) |
| `"RTMPStreamingViewModel"` | camera | RTMP (existing) |
| `"QuickVisionService"` | camera (via `GlassesPhotoCapturer`) | wake-word capture (existing) |
| `"OpenClawSnap"` | camera (via `GlassesPhotoCapturer`) | `SessionFrameProvider` (existing) |
| `"OpenClawChat"` | session-only | `OpenClawViewModel.enterScreen()` (existing; now `acquireAndStart`) |
| `"QuickVisionDisplay"` | session-only | `QuickVisionService` while a Quick Vision card is on the lenses (new, Task 5) |

### S11. New string keys (Task 7 adds rows 1–18 unless Task 3 already added the ones it needs; Task 8 adds rows 19–20). Exact text.

| # | key | en | zh-CN |
|---|---|---|---|
| 1 | `display_section` | Glasses Display | 眼镜显示 |
| 2 | `display_enabled` | Show results on glasses display | 在眼镜屏幕上显示结果 |
| 3 | `display_enabled_desc` | Meta Ray-Ban Display only | 仅支持 Meta Ray-Ban Display |
| 4 | `display_status_unsupported` | Not supported by this device | 当前设备不支持显示 |
| 5 | `display_status_preparing` | Preparing display… | 正在准备显示… |
| 6 | `display_status_ready` | Display ready | 显示已就绪 |
| 7 | `display_status_off` | Display off | 眼镜显示已关闭 |
| 8 | `display_status_stopped` | Display stopped | 显示已停止 |
| 9 | `display_status_not_attached` | Display not attached | 显示未连接 |
| 10 | `display_status_needs_api_key` | Set an API key in Settings | 请先在设置中配置 API Key |
| 11 | `display_prev` | Prev | 上一页 |
| 12 | `display_next` | Next | 下一页 |
| 13 | `display_done` | Done | 完成 |
| 14 | `display_again` | Again | 再来一次 |
| 15 | `display_snap` | Snap | 拍照 |
| 16 | `display_looking` | Looking… | 正在识别… |
| 17 | `display_analyzing` | Analyzing… | 正在分析… |
| 18 | `display_end` | End | 结束 |
| 19 | `display_preview_title` | Glasses display preview | 眼镜显示预览 |
| 20 | `display_preview_subtitle` | Render display cards on the phone (debug only) | 在手机上预览眼镜卡片（仅调试版） |

Rows 4 and 10 are used by the Settings toggle subtitle (a connected non-display device shows row 4 instead of row 3) and by the Status card / `ResourceDisplayStrings`. Existing keys reused unchanged: `feature_liveai_title`, `feature_quickvision_title`, `feature_leaneat_title`, `openclaw_title`, `connecting`, `liveai_listening`, `processing`, `liveai_speaking`, `liveai_connected`, `disconnected`, `leaneat_calories`, `leaneat_protein`, `leaneat_fat`, `leaneat_carbs`, `leaneat_kcal`, `leaneat_gram`, `leaneat_health_score`, `settings_developer`.

---

## Task Scopes

Every task ends with `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest` green and `./gradlew :app:assembleDebug` green (Tasks 8 and 9 also `:app:assembleDebugAndroidTest`). Per-task TDD steps are written by the per-task drafters against this section; they must not widen a task's Files list.

### Task 1: Display lifecycle inside `GlassesSessionManager` (gateway seam, `acquireAndStart`, main-thread guard, settings key)

**Files:**
- Modify: `app/src/main/java/com/smartview/glassai/glasses/DatGateway.kt` (anchors: `interface GlassesSession` lines 53-62 — add the two members and delete the `nativeSession` KDoc + member at lines 57-58 (its only reader is the test deleted below; the display never goes through it); delete the `DisplayAttacher` block lines 100-115; add `DisplaySendResult`/`DisplayAddResult`/`GlassesDisplay` after `sealed class PhotoCaptureResult` line 39)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/WearablesDatAdapter.kt` (anchors: `class SdkGlassesSession` lines 60-78 — add `addDisplay/removeDisplay`, delete `override val nativeSession` line 66; add `class SdkGlassesDisplay` before it; imports `com.meta.wearable.dat.display.Display`, `com.meta.wearable.dat.display.addDisplay`, `com.meta.wearable.dat.display.removeDisplay`, `com.meta.wearable.dat.display.types.DisplayConfiguration`)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionState.kt` (anchor: line 17 `enum class GlassesDisplayState { NOT_ATTACHED, STARTING, STARTED, STOPPED }`)
- Modify: `app/src/main/java/com/smartview/glassai/glasses/GlassesSessionManager.kt` (anchors: constructor lines 47-52; `getInstance` lines 66-80; `val displayState` lines 127-129; `startMonitoring` collector lines 201-203; `fun acquire` lines 231-242; `fun release` 245-251; `fun ensureSession` 263-270; `fun ensureSessionStarted` 279-295; `fun addCamera` 362; `fun stopCamera` 388; `resetForTests` 415-427; `fun stopSession` 433-453 (`displayAttacher.detach()` at 436); `onSessionState` 455-464 (`displayAttacher.maybeAttach` at 460); `teardownAfterDeviceStop` 481-491 (`displayAttacher.detach()` at 482))
- Modify: `app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt` (anchors: key constants lines 43-47; OpenClaw getters lines 314-349 — append the display getter/setter after `deleteOpenClawToken`)
- Modify: `app/src/test/java/com/smartview/glassai/glasses/FakeDat.kt` (anchors: `class FakeGlassesSession` lines 50-97 — add the S2 members, delete `override val nativeSession` line 65; delete `RecordingDisplayAttacher` lines 143-153)
- Modify: `app/src/test/java/com/smartview/glassai/glasses/GlassesSessionManagerTest.kt` (anchors: fields + `newManager` lines 25-36 (drop `attacher`, add a `displayEnabled` parameter defaulting to `{ true }`); `displayAttacherIsCalledOnStartedWithActiveDevice` lines 424-440 and `defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice` 442-456 (rewrite / delete); explicit `displayAttacher = attacher` at lines 563 and 638 (remove); the two remaining `assertEquals(1, attacher.detachCalls)` assertions at line 85 (`sessionStopsOnlyWhenLastOwnerReleases`) and line 331 (`deviceStoppingSessionClearsStateAndNextAcquireRecreates`) — both tests run on the non-capable `rayban`, so replace each with `assertEquals(0, factory.last.removeDisplayCalls)`, `assertEquals(GlassesDisplayState.NOT_ATTACHED, manager.displayState.value)` and `assertNull(manager.currentDisplay())` (this is the JVM proof of the `detachDisplay()` no-display guard))
- Modify: `app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` (anchor: `manager = GlassesSessionManager.getInstance(targetContext)` line 96 — add `onMain { manager.startMonitoring() }` on the next line; the comment at line 242 mentioning `DisplayAttacher.None` is rewritten in Task 9)
- Modify: `app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt` (anchor: `manager = GlassesSessionManager.getInstance(targetContext)` line 99 — add `runBlocking(Dispatchers.Main) { manager.startMonitoring() }` on the next line; lines 104-111 are the settings-store snapshot block that Task 9 replaces)

**Interfaces:**
- Produces S1, S2, S3 (`GlassesDisplay`, `DisplaySendResult`, `DisplayAddResult`, `GlassesSession.addDisplay/removeDisplay` (and the deletion of `nativeSession`), `SdkGlassesDisplay`, `GlassesDisplayState` with 6 values, manager `displayState/isDisplayAvailable/currentDisplay/setDisplayEnabled/reattachDisplay/acquireAndStart/displayAttachAttempts`, `checkMain`, fuller `resetForTests`, the debug stop-path timing log, `APIKeyManager.isGlassesDisplayEnabled/setGlassesDisplayEnabled`, `FakeGlassesDisplay`, `FakeGlassesSession` display members).
- Consumes: existing manager internals (`session`, `_sessionState`, `_activeDevice`, `_sessionError`, `scope`), `DeviceSessionState`, `DeviceSessionError`, DAT `Display`/`DisplayState`/`DisplayError`/`DisplayConfiguration`/`addDisplay`/`removeDisplay`.

**Scope:**
- Delete the `DisplayAttacher` seam everywhere (interface, `None`, `RecordingDisplayAttacher`, constructor param, `getInstance` argument, the three call sites, the KDoc line 42 and the `displayState` getter). The manager now owns `_displayState`, `display`, `displayStateJob`, the lazily read enabled flag and `displayAttachAttempts` (decision D2).
- Implement `maybeAttachDisplay()` / `detachDisplay()` with exactly the triggers and outcomes in S3. Attach uses `session.addDisplay()` only after STARTED; a failure emits on `sessionError` (the existing `WearablesErrorToast` shows it through `GlassesErrorMessages.resId(DeviceSessionError)`, which already has an `else` branch) and never loops.
- `isDisplayAvailable` = `combine(_activeDevice, displayEnabledState)` → `device?.isDisplayCapable == true && enabledOrLazyRead`, `stateIn(scope, Eagerly, false)`; the injected lambda is invoked only once a display-capable device is present, never in `getInstance()`/`startMonitoring()` (keeps Application start free of `EncryptedSharedPreferences` I/O).
- `acquireAndStart(owner, timeoutMs)` = `checkMain(); acquire(owner); return ensureSessionStartedFor(owner, timeoutMs)` (Phase B Minor 10), where the existing body of `ensureSessionStarted(timeoutMs)` (lines 279-295) moves into `private suspend fun ensureSessionStartedFor(owner: String?, timeoutMs: Long)` and the public `ensureSessionStarted(timeoutMs)` becomes `ensureSessionStartedFor(null, timeoutMs)` (byte-identical behaviour for camera owners). With a non-null `owner`, `if (owner !in owners) return SessionStartResult.NOT_STARTED` runs right after `awaitPreviousSessionStopped()` (line 280) and again after the `delay(ALREADY_EXISTS_RETRY_DELAY_MS)` (line 284), BEFORE `createSessionIfNeeded()` — an owner that released during the wait must not get a session created on its behalf (I1; the `release()` → `stopSession()` in that window returns at `session ?: return`, line 434, so nothing else would stop it).
- `reattachDisplay()` = `checkMain(); detachDisplay(); maybeAttachDisplay()` — declared and tested here, called by nobody in Phase C (S3 pre-decided fallback).
- `checkMain()` at the top of `startMonitoring, acquire, release, ensureSession, ensureSessionStarted, acquireAndStart, addCamera, stopCamera, stopSession, setDisplayEnabled, reattachDisplay, resetForTests` (Phase A #18 / Phase B Minor 9). Not in `publishFrame`.
- `resetForTests()` extended per S3 (Phase B Minor 12; ledger T1/T4). Both instrumented `setUp()`s call `startMonitoring()` again (the hook now kills the device observer).
- Stop-path timing (Phase B Recommendation 2): in `stopSession()` (line 437 `camera?.stop()`, line 453 `current.stop()`) and `stopCamera()` wrap the SDK call as `val t0 = SystemClock.elapsedRealtime(); …stop(); if (BuildConfig.DEBUG) Log.d(TAG, "session.stop() took ${SystemClock.elapsedRealtime() - t0} ms")` (same for `camera.stop()`). Bookkeeping order is unchanged; no new suspension (I3).
- `APIKeyManager`: key + getter (default `true`) + setter.
- `WearablesDatAdapter.kt` is the only main-source file importing `com.meta.wearable.dat.display.Display`, `addDisplay`, `removeDisplay`, `DisplayConfiguration`.

**Tests (JVM, `GlassesSessionManagerTest`, at least 15 new/rewritten; all `runTest(UnconfinedTestDispatcher())`, `FakeGlassesSession(stopAsync)` where noted; "capable device" = `rayban.copy(deviceType = DeviceType.META_RAYBAN_DISPLAY, isDisplayCapable = true)`, the existing fixture shape at lines 427-430):**
- `displayIsAttachedOnStartedForADisplayCapableDevice` — capable device + enabled → after `emitStarted()`: `addDisplayCalls == 1`, `displayState == STARTING`, `currentDisplay() != null`, `displayAttachAttempts == 1`.
- `displayStateMirrorsTheSdkDisplayState` — `display.emitStarted()` → STARTED; `emitStopped()` → STOPPED and no re-add (`addDisplayCalls` stays 1).
- `displayIsNotAttachedForANonDisplayCapableDevice` — the rewritten `displayAttacherIsCalledOnStartedWithActiveDevice`: `rayban` (not capable) → `addDisplayCalls == 0`, NOT_ATTACHED, `isDisplayAvailable == false`.
- `displayIsNotAttachedWhenTheSettingIsOff` — `newManager(displayEnabled = { false })` + capable device → no attach; `isDisplayAvailable == false`.
- `settingLambdaIsReadLazilyAndOnlyOnce` — counting lambda: 0 reads before a capable device appears; 1 read after; STARTED does not read again.
- `deviceMetadataArrivingAfterStartedAttachesTheDisplay` — STARTED with `observer.device.value == null`, then the capable device → `addDisplayCalls == 1` (Phase A #10).
- `resumeFromPausedDoesNotAddASecondDisplay` — STARTED → PAUSED → STARTED → still `addDisplayCalls == 1` (idempotent; Phase B Minor 9 KDoc point).
- `addDisplayFailureIsEmittedAndNotRetriedUntilTheNextTrigger` — `nextAddDisplayFailure = CAPABILITY_DENIED` → `sessionError` collected once, NOT_ATTACHED, `attempts == 1`; a later PAUSED→STARTED with the failure cleared → `attempts == 2` and attached.
- `stopSessionRemovesTheDisplayBeforeStoppingTheSession` — order recorded on the fake (`removeDisplayCalls == 1` and `display.state == CLOSED` before `stopCalls == 1`), `displayState == NOT_ATTACHED`, `currentDisplay() == null`; a second `stopSession()`/`resetForTests()` afterwards keeps `removeDisplayCalls == 1` (the no-display guard).
- `deviceStopDetachesTheDisplay` — `emitStoppedByDevice()` → `removeDisplayCalls == 1`, NOT_ATTACHED.
- `removeDisplayFailureFallsBackToClose` — `nextRemoveDisplayFailure = SESSION_ALREADY_STOPPED` → `closeCalls == 1`, NOT_ATTACHED; second case `CAPABILITY_NOT_FOUND` behaves the same. Both are the EXPECTED answers on hardware (device-side stop / already-terminated capability, S3) and are logged at debug level — the test asserts the outcome, not the log.
- `settingOffDetachesAndSettingOnReattaches` — `setDisplayEnabled(false)` → removed + `isDisplayAvailable == false`; `setDisplayEnabled(true)` while STARTED → `addDisplayCalls == 2`.
- `reattachDisplayRemovesAndReaddsOnce` — attached + `display.emitStopped()` (STOPPED, still attached) → `reattachDisplay()` → `removeDisplayCalls == 1`, `addDisplayCalls == 2`, `displayState == STARTING`, `currentDisplay() === factory.last.displays[1]`.
- `acquireAndStartWaitsForTheStoppingSessionThenStarts` — `factory.stopAsync = true`; owner A acquires, STARTED, releases (STOPPING); `async { acquireAndStart("B", 5_000) }`; `factory.sessions[0].emitStoppedByDevice()`; `factory.last.emitStarted()` → result STARTED, `createCalls == 2`, `ownerCount == 1`, `hasCameraClaim == false`.
- `acquireAndStartAbortsWhenTheOwnerReleasedDuringTheWait` — `factory.stopAsync = true`; A acquires, STARTED, releases (STOPPING); `val r = async { acquireAndStart("B", 5_000) }`; `release("B")` (owners empty, `stopSession()` returns at `session ?: return`); `factory.sessions[0].emitStoppedByDevice()` → `r.await() == NOT_STARTED`, `factory.createCalls == 1`, `hasSession == false`, `ownerCount == 0`, `sessionState == STOPPED` (the owner-less-session hole of spec decision 1 / I1).
- `acquireAndStartReportsCreateFailed` — `factory.failure = NO_ELIGIBLE_DEVICE` → CREATE_FAILED and `lastSessionError` set.
- `resetForTestsClearsDeviceObserverActiveDeviceAndLastError` — after `resetForTests()`: `activeDevice == null`, `lastSessionError == null`, `isDatAppUpdateRequired == false`, `displayAttachAttempts == 0`; `startMonitoring()` then a device emission repopulates `activeDevice` (proves `deviceJob` was nulled).
- `defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice` is deleted (its premise no longer exists); `resetForTestsDropsOwnersSessionAndFrame` keeps passing; `sessionStopsOnlyWhenLastOwnerReleases` and `deviceStoppingSessionClearsStateAndNextAcquireRecreates` keep their names with the replaced assertions listed under Files (`removeDisplayCalls == 0` on `rayban`).
- Instrumented (Task 9 runs them): the two `startMonitoring()` insertions.

**Commit:** `refactor(android): display lifecycle inside GlassesSessionManager over a GlassesDisplay gateway seam; acquireAndStart for session-only owners; debug main-thread guard; glasses_display_enabled key`

**Risks / notes for the drafter:**
- The manager calls `session.addDisplay()`/`removeDisplay()` on the `GlassesSession` seam and never touches an SDK `DeviceSession`; `nativeSession` is deleted rather than left with a stale "Phase C uses it for addDisplay()" KDoc (nothing else reads it — `grep -rn nativeSession app/src` after this task is empty).
- `detachDisplay()`'s `val d = display ?: return` guard is load-bearing for the Phase A/B baseline: without it every `release()`/`stopSession()` on a display-less session would call `removeDisplay()` (an SDK misuse answered with `CAPABILITY_NOT_FOUND`) and the fake would have to cope with an empty `displays` list. On a device-side stop the SDK answers `SESSION_ALREADY_STOPPED` deterministically (the capability map is already cleared) — debug log, not an error.
- `combine(...).stateIn(scope, Eagerly, false)` inside the constructor runs before `startMonitoring()`; with `UnconfinedTestDispatcher` it is evaluated synchronously — assert `isDisplayAvailable.value` only after the device emission.
- `SdkGlassesDisplay.sendContent` cannot be exercised on the JVM or on MockDeviceKit (no display model) — hardware checklist H1/H2.
- `checkMain()` reads `com.smartview.glassai.BuildConfig.DEBUG`; `testReleaseUnitTest` compiles with `DEBUG = false`, so both variants must stay green.
- Do not touch `WearablesViewModel` beyond compiling (its `displayState` exposure is Task 7).

### Task 2: Pure card layer (`DisplayCard`, `DisplayNode`, `DisplayStrings`, `paginate`, `toNode`, `fallbackNotice`)

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/glasses/DisplayCard.kt`, `DisplayNode.kt`, `DisplayPagination.kt` (the pure layer) and `LeanEatCardMapper.kt` (outside it)
- Create: `app/src/test/java/com/smartview/glassai/glasses/FixedDisplayStrings.kt`, `DisplayPaginationTest.kt`, `DisplayCardTest.kt`, `DisplayNodeTest.kt`, `LeanEatCardMapperTest.kt`

**Interfaces:**
- Produces S4 in full (`LiveAIPhase`, `DisplayIcon` (35 members), `DisplayAction`, `DisplayCard` + `LeanEatFood` + companion constants, `plainText`, `fallbackNotice(strings)`, `pageCount`, `paginate`, `DisplayNode` + the five node enums, `DisplayStrings`, `toNode`, `FoodNutritionResponse.toLeanEatCard()`, `FixedDisplayStrings`).
- Consumes (only in `LeanEatCardMapper.kt`): `com.smartview.glassai.models.FoodNutritionResponse` / `FoodItem` (fields `foods`, `totalCalories: Int`, `totalProtein/totalFat/totalCarbs: Double`, `healthScore: Int`, `suggestions`; `FoodItem.name/portion/calories`) — read-only, never `healthScoreColor`/`healthScoreText`/`healthRatingColor`.

**Scope:**
- Three pure Kotlin files with zero `android.*` / `androidx.*` / `com.meta.*` / `com.smartview.glassai.models.*` imports (decision D3, I7). `LeanEatCardMapper.kt` is the fourth file: `fun FoodNutritionResponse.toLeanEatCard(page: Int = 0) = DisplayCard.LeanEat(totalCalories, totalProtein.toInt(), totalFat.toInt(), totalCarbs.toInt(), healthScore, foods.map { LeanEatFood(it.name, it.portion, it.calories) }, suggestions, page)` — whole numbers, no locale-dependent formatting; `toNode` then renders the card's ints verbatim.
- `paginate` with the S4 preference order; `PAGE_CHARS = 280`.
- `toNode` implements every layout rule in S4 for all 8 card subtypes; the text limits (`LIVE_AI_ASSISTANT_CHARS = 320` via `takeLast`, `USER_TEXT_CHARS = 120` via `take`) are applied here, not in the data classes.
- `fallbackNotice(strings)` and `plainText()` per S4; `pageCount()` = `paginate(...).size` for QuickVision/OpenClaw, `1 + text pages` for LeanEat, else 1.
- Images deliberately absent (Global Constraints).

**Tests (JVM, at least 34):**
- `LeanEatCardMapperTest` (≥ 2): `mapsTotalsFoodsAndSuggestionsWithWholeNumbers` (`FoodNutritionResponse(totalProtein = 12.7, …)` → `totalProtein == 12`, `foods.size == 3`, `suggestions` copied, `page == 0`), `emptyResponseMapsToAnEmptyCard` (constructor defaults → zeros and empty lists, `pageCount() == 1`). Builds the response with its constructor only (loading the class is fine on the JVM; its `Color` getters are never invoked).
- `DisplayPaginationTest` (≥ 9): `shortTextIsOnePage`, `blankTextIsOneEmptyPage`, `breaksAtTheLastSentenceEndInsideTheWindow`, `breaksAtNewlineBeforeComma`, `fallsBackToCommaThenSpaceThenHardCut` (three cases), `noPageExceedsMaxChars` (a 2 000-char mixed zh/en text), `pagesAreTrimmedAndNonEmpty`, `chineseSentencePunctuationCounts`.
- `DisplayCardTest` (≥ 9): `liveAiPlainTextIsTheAssistantText`, `fallbackNoticeUsesTheStringsTitleForLiveAi`, `fallbackNoticeUsesModeNameForQuickVision`, `fallbackNoticeUsesLeanEatTitle`, `fallbackNoticeBodyIsCappedAtPageChars`, `pageCountMatchesPagination` (QuickVision, OpenClaw, LeanEat with and without foods), `noticeFallbackIsItself`, `weChatAndMusicFallbacks`, `displayIconHasNoDuplicatesAndAtLeastThirtyMembers`.
- `DisplayNodeTest` (≥ 14): per card subtype assert the root is `Column(padding = 24, gap = 12, flexGrow = 0f)`, the first child is the icon/title `Row`, and the button list (labels + actions + styles in order): `statusCardHasThreeStartButtons`, `noticeCardHasNoButtons`, `liveAiCardTruncatesAssistantToTheLastThreeHundredTwenty`, `liveAiCardTruncatesUserTextToOneHundredTwenty`, `liveAiPhaseIconsAndLabels` (5 phases), `liveAiCardHasOnlyTheEndButton`, `quickVisionFirstPageHasNoPrev`, `quickVisionMiddlePageHasPrevAndNextWithPageActions` (`Page` carries `copy(page = p ± 1)`), `quickVisionLastPageHasNoNext`, `quickVisionPageIsClampedToTheLastPage`, `leanEatPageZeroShowsTotalsAndHealthScore` (a `DisplayCard.LeanEat` built directly with ints), `leanEatFoodsPagesFollowTheTotals`, `openClawPendingUsesTheThreeDotIconAndProcessingText`, `openClawFinalHasSnapAndDone`, `weChatAndMusicPlaceholdersRender`, `everyNodeTreeUsesOnlyColumnRowTextIconButtonButtonGroup` (walks all sample cards; also asserts every root's `flexGrow == 0f`, since the SDK ignores it there).

**Commit:** `feat(android): pure glasses display card model — DisplayCard, DisplayNode, DisplayStrings, pagination, card→node derivation and the LeanEat mapper (JVM-tested)`

**Risks / notes for the drafter:**
- `FoodNutritionResponse` (`models/FoodNutritionModels.kt`, imports `androidx.compose.ui.graphics.Color` and the theme colours at lines 3-7) must not appear in `DisplayCard.kt`/`DisplayNode.kt`/`DisplayPagination.kt` at all — not even as a constructor parameter type — because it would put an androidx type into the pure layer's public API. Only `LeanEatCardMapper.kt` imports it; loading the class on the JVM is fine, calling its `Color` getters is not; tests build it with the constructor.
- `data object` requires Kotlin 2.x — available (2.2.21).
- Keep `DisplayIcon` names byte-identical to the DAT `IconName` constants listed in S4; Task 3's injectivity test fails otherwise. Verified: all 35 names exist in `com.meta.wearable.dat.display.views.IconName` (0.9.0 AAR).
- Blueprint §8.5's card builders are superseded by S4; do not port them.

### Task 3: SDK boundary (`DisplayCards.render`), `GlassesDisplayManager`, integration singleton

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/glasses/DisplayCards.kt`, `ResourceDisplayStrings.kt`, `GlassesDisplayManager.kt`, `GlassesDisplayIntegration.kt` (Task 3 shape from S5)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml` (append rows 10–18 of S11 before `</resources>`, after `rtmp_connect_failed` at line 524 of each file; Task 7 adds the remaining rows)
- Create: `app/src/test/java/com/smartview/glassai/glasses/DisplayIconMappingTest.kt`, `GlassesDisplayManagerTest.kt`, `RecordingDisplaySink.kt`

**Interfaces:**
- Produces S5 (`ContentScope.render`, `DisplayIcon.toIconName`, `ResourceDisplayStrings`, `GlassesDisplaySink`, `DisplayStatusProvider`, `GlassesDisplayManager` + semantics, `GlassesDisplayIntegration.displayManager/statusProviderFactory`, `DeviceNameStatusProvider`, `RecordingDisplaySink`).
- Consumes S1–S4 (`GlassesSessionManager.displayState/currentDisplay/activeDevice`, `GlassesDisplay`, `DisplaySendResult`, `DisplayCard.toNode/fallbackNotice`, `DisplayStrings`, `FakeGlassesDisplay`, `FakeGlassesSession`).

**Scope:**
- `DisplayCards.kt` imports `com.meta.wearable.dat.display.views.*` and nothing else from the SDK; `render` is an exhaustive `when` over `DisplayNode` (root Column/Row → `ContentScope.flexBox(direction = COLUMN/ROW, gap, alignment, crossAlignment, padding, paddingTop, paddingBottom, paddingStart, paddingEnd, background)` — this overload has NO `flexGrow`, so the root's `flexGrow` is documented as ignored (S4/S5) and never `require`d; nested Column/Row → `FlexBoxScope.flexBox(... flexGrow)`; the parameter is `padding`, not `paddingAll`). Enum mappers `NodeTextStyle→TextStyle`, `NodeTextColor→TextColor`, `NodeButtonStyle→ButtonStyle`, `NodeBackground→FlexBoxBackground`, `NodeAlignment→Alignment` / `ButtonGroupAlignment` (STRETCH → `ButtonGroupAlignment.CENTER`) are `internal`.
- `GlassesDisplayManager` exactly as S5 (serial `collectLatest` sender over `SendRequest.Card/Clear`, STARTED resend, 600 ms Live AI coalescing keyed on `clock()` with `lastLiveAiSentAt: Long? = null`, `NonCancellable` send + bookkeeping, error matrix, `dispatchFromSdk` hop, `currentCard`/`lastSent`, `actionHandler`). `currentDisplay()` is read and `card.toNode(strings)` computed on the manager's thread (Main; pure, cheap) BEFORE `withContext(sendDispatcher)`; only `display.sendContent { render(node, …) }` runs on the send dispatcher.
- `GlassesDisplayIntegration.displayManager(app)`: double-checked singleton building `GlassesDisplayManager(GlassesSessionManager.getInstance(app), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), ResourceDisplayStrings(app), statusProviderFactory(app), Dispatchers.Main.immediate, Dispatchers.IO, SystemClock::elapsedRealtime)`; `GlassesDisplayManager.getInstance(context)` delegates to it.
- `ResourceDisplayStrings(context)` holds the Application context and resolves every property lazily — `override val prev: String get() = context.getString(R.string.display_prev)` and so on (26 cheap lookups per `toNode`, which runs on Main, never on the send path) — so a card built after an in-app language switch (Settings → App Language, `SettingsScreen.kt:378-380`) picks up the new locale as far as the Application context follows it; no per-process cache.

**Tests (JVM, at least 17):**
- `DisplayIconMappingTest` (≥ 3): `everyDisplayIconMapsToAnIconNameOfTheSameName` (`toIconName().name == icon.name` for all entries — totality), `mappingIsInjective` (`DisplayIcon.entries.map { it.toIconName() }.toSet().size == DisplayIcon.entries.size`), `iconCatalogStillContainsEveryMappedName` (`IconName.valueOf(icon.name)` never throws — guards an SDK bump).
- `GlassesDisplayManagerTest` (≥ 14), built on `GlassesSessionManager` + fakes with `runTest(UnconfinedTestDispatcher())`, the manager's `scope = backgroundScope`, `clock = { testScheduler.currentTime }` (starts at 0 — hence `lastLiveAiSentAt` must start as `null`, not `0`), a capable device, one test owner `manager.acquire("A")`, `emitStarted()` on the session and `display.emitStarted()`: `nothingIsSentBeforeTheDisplayIsStarted`, `statusCardIsSentWhenTheDisplayStartsAndNoCardIsActive` (`sendCalls == 1`, `lastSent is Status`, `currentCard == null`), `showSendsTheCardAndSetsCurrentCard`, `intermediateCardsAreDroppedBySerialSending` (three `show()` before the sender runs → 1 send of the last), `liveAiStreamingCardsAreCoalescedToOnePerSixHundredMs` (5 non-final cards within 300 ms → exactly 1 send, the first one, immediately; then `advanceTimeBy(600); runCurrent()` → the latest one sent, 2 sends total — `advanceTimeBy` alone does not run a task due at exactly +600 ms), `finalLiveAiCardBypassesTheCoalescer`, `invalidSessionStateKeepsTheCardAndResendsOnNextStarted` (scripted failure, `display.emitStopped(); display.emitStarted()` → the same card sent again), `renderingFailedSendsTheFallbackNoticeOnce` (`sentBlocks.size == 2`, `lastSent is Notice`, `currentCard` unchanged; a second RENDERING_FAILED on the fallback → no third send), `deviceDisconnectedDropsTheCard`, `showStatusForgetsTheFeatureCardAndSendsStatus`, `clearCallsClearDisplayAndForgetsTheCard` (also `clear(); show(card)` → `clearCalls == 1` and then `sendCalls == 1`, in that order, `lastSent == card`), `currentCardSurvivesDisplayDetachAndIsResentOnReattach` (detach via `setDisplayEnabled(false)`, re-enable, new display STARTED → the same card sent), `displayManagerNeverAcquiresTheSession` (after `show()`, `showStatus()`, `clear()` and a STARTED resend `manager.ownerCount == 1` — only the test's own owner — and `manager.hasCameraClaim == false`; the JVM half of I1), `dispatchFromSdkHopsToMainAndCallsTheHandler` (handler records the action; visible after `runCurrent()`), `dispatchWithoutHandlerIsDropped`.

**Commit:** `feat(android): DisplayCards SDK interpreter (the only DSL file), GlassesDisplayManager with serial sending, 600 ms Live AI coalescing, STARTED resend and RENDERING_FAILED degrade; display integration singleton`

**Risks / notes for the drafter:**
- `ContentScope`, `FlexBoxScope`, `ButtonGroupScope` are final classes whose builders serialise to `org.json` — `render()` cannot run on the JVM (JSONObject is a stub) and must not be called in unit tests; the fake records the block. Structure is verified on the phone preview (Task 8) and on hardware (H2).
- The DAT `button()` label parameter is a non-null `String`; Music's play/pause placeholder passes `""`.
- `Display.sendContent` is `suspend`; the SDK completes it on its own thread — never assume the continuation resumes on Main (hence `withContext(sendDispatcher)` and no state writes after the send except through the manager's own scope).
- `collectLatest` would otherwise cancel an in-flight `sendContent` (the SDK's `DisplaySessionImpl.waitForDisplayResponse` is a `withTimeout`, so the call is genuinely cancellable): the bytes may already be on the glasses while `lastSent`/fallback bookkeeping is skipped. Hence the send and `onSendResult` run under `withContext(NonCancellable)`; only the STARTED gate and the Live AI `delay` are cancellable. `NonCancellable` bounds nothing new: the SDK call has its own timeout.
- The 600 ms window is measured with `clock()`; with the real `SystemClock` it is elapsed time, with `TestScope` virtual time — never `System.currentTimeMillis()`.
- `ResourceDisplayStrings` reads from the Application context: after an in-app language switch it follows the locale exactly as far as `application.getString` does elsewhere (`OpenClawViewModel`'s `strings` has the same behaviour) — pre-existing, not a Phase C concern; the lazy getters merely avoid making it sticky for the process lifetime.

### Task 4: `GlassesActionRouter`, `NavigationRequest`, status provider, wiring

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/glasses/GlassesActionRouter.kt`, `AppStatusProvider.kt`
- Modify: `app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayIntegration.kt` (add `navigationRequests`, `router(app)`, `install(app)`, `ensureStarted(app)`; default `statusProviderFactory` → `AppStatusProvider`)
- Modify: `app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` (anchor: `OpenClawIntegration.install(this)` line 41 — add `GlassesDisplayIntegration.install(this)` after it)
- Modify: `app/src/main/java/com/smartview/glassai/MainActivity.kt` (anchors: `TurboMetaNavigation(` call lines 106-109 — pass `navigationRequests = GlassesDisplayIntegration.navigationRequests`; `startWearablesMonitoring()` lines 131-138 — add `GlassesDisplayIntegration.ensureStarted(application)` after `wearablesViewModel.startMonitoring()`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` (anchors: `fun TurboMetaNavigation(` lines 58-61 — new parameter; after `val navController = rememberNavController()` line 62 — the `LaunchedEffect`)
- Create: `app/src/test/java/com/smartview/glassai/glasses/GlassesActionRouterTest.kt`, `AppStatusProviderTest.kt`, `FakeControllerRegistry.kt`

**Interfaces:**
- Produces S6 (`NavigationRequest`, `LiveAiController`, `OpenClawController`, `GlassesControllerRegistry`, `GlassesActionRouter`, `AppStatusProvider`, `GlassesDisplayIntegration.navigationRequests/router/install/ensureStarted`, `FakeControllerRegistry`).
- Consumes S3, S5 (`GlassesDisplaySink`, `GlassesDisplayManager.actionHandler/currentCard`, `DisplayStatusProvider`), `QuickVisionService.ACTION_CAPTURE_AND_ANALYZE`, `OpenClawNodeService.getInstance(app).connectionState`, `APIProviderManager.getLiveAIAPIKey`, `TurboMetaNavigation`.

**Scope:**
- Router `dispatch` table exactly as S6 (decision D5); `Music*` → `Log.i` only. Registry semantics: `register` replaces; `unregister(c)` only clears when `c` is the registered instance.
- `AppStatusProvider` per S6; `GlassesDisplayIntegration.install(app)`: `runCatching { statusProviderFactory = { AppStatusProvider(...) }; if (hasBluetoothConnect(app)) ensureStarted(app) }.onFailure { Log.e }` — never throws, no I/O (the API-key and OpenClaw lookups happen inside `currentStatus()` on the first send, on Main, after the UI is up; `APIKeyManager.getInstance` has been constructed by Home by then).
- `ensureStarted(app)` idempotent (double-checked flag): `displayManager(app).actionHandler = router(app)::dispatch`; launches on `Dispatchers.Main.immediate` the `OpenClawNodeService.connectionState` collector (`map { it == Connected }.distinctUntilChanged()`) that calls `showStatus()` when `currentCard.value == null && sessionManager.displayState.value == STARTED`.
- `TurboMetaNavigation` collects `navigationRequests` (`LiveAI → Screen.LiveAI.route`, `LeanEat → Screen.LeanEat.route`, `OpenClaw → Screen.OpenClaw.route`, all `launchSingleTop = true`).

**Tests (JVM, at least 12):**
- `GlassesActionRouterTest` (≥ 10) with `RecordingDisplaySink`, `MutableSharedFlow<NavigationRequest>(extraBufferCapacity = 4)` collected into a list, and a counting `startQuickVision`: `startLiveAiEmitsTheLiveAiRequest`, `startLeanEatEmitsTheLeanEatRequest`, `startQuickVisionStartsTheService` (counter == 1, no navigation), `endLiveAiCallsTheRegisteredController`, `endLiveAiWithoutControllerIsANoOp`, `unregisteringAnotherControllerKeepsTheCurrentOne`, `pageShowsTheCarriedCard`, `backToMenuShowsStatus`, `openClawSnapPrefersTheControllerElseNavigates` (two cases), `musicActionsAreLoggedOnly` (no sink/navigation side effects for all three).
- `AppStatusProviderTest` (≥ 3): `deviceNameComesFromTheActiveDevice`, `emptyNameWhenNoDevice`, `flagsReflectKeyAndOpenClawState` (four combinations).

**Commit:** `feat(android): GlassesActionRouter with NavigationRequest routing, controller registry and AppStatusProvider; display integration installed at app start and navigation collection`

**Risks / notes for the drafter:**
- `TurboMetaNavigation` is only called from `MainActivity`; adding the required parameter (no default) keeps the wiring explicit.
- `ensureStarted` must not run before the Bluetooth grant (it creates `GlassesSessionManager`, whose observer would die on the ungranted SDK flows — Phase B final review I2c); `install` gates it exactly like `OpenClawIntegration.install`.
- `startForegroundService` needs no API branch (minSdk 31).
- The router is main-thread-only; the only caller besides the manager's `dispatchFromSdk` hop is the debug preview (Task 8), which calls it from Compose click handlers (Main).

### Task 5: Live AI and Quick Vision hooks

**Files:**
- Create: `app/src/main/java/com/smartview/glassai/glasses/LiveAiCardMapper.kt`, `app/src/main/java/com/smartview/glassai/services/QuickVisionDisplayPolicy.kt`
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/OmniRealtimeViewModel.kt` (anchors: `class OmniRealtimeViewModel(application: Application)` line 29 — internal + public constructors; `init` lines 93-109 — controller registration and the card collector; `fun disconnect()` lines 295-306 — `sink.showStatus()` after the state reset; `override fun onCleared()` lines 420-425 — unregister)
- Modify: `app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt` (anchors: companion `OWNER` line 60 — add `DISPLAY_OWNER`; `sessionManager` line 88 — add the lazy `displaySink` and `displayClaimHeld`; `cleanup()` lines 169-177 — release the display claim; `captureAndAnalyze()` lines 179-314: claim at the top of the launch (before the TTS wait), Notice after `speak(lookingText)` line 201, Notice at `broadcastStatus("analyzing")` line 264, `QuickVision(modeName, description)` in `onSuccess` before `speakAndWait` line 289, error Notice in `onFailure` lines 291-296, `delay(500)` line 299 → the policy dwell; `failAndFinish` lines 317-322 — error Notice + policy dwell; `finishService()` lines 442-448 — `showStatus()` before `cleanup()`)
- Create: `app/src/test/java/com/smartview/glassai/glasses/LiveAiCardMapperTest.kt`, `app/src/test/java/com/smartview/glassai/services/QuickVisionDisplayPolicyTest.kt`
- Modify: `app/src/test/java/com/smartview/glassai/glasses/GlassesPhotoCapturerTest.kt` (append one composing test; add a `displayGlasses = rayban.copy(deviceType = DeviceType.META_RAYBAN_DISPLAY, isDisplayCapable = true)` fixture next to `rayban` at lines 35-41; `newManager()` at lines 45-50 gets a `displayEnabled: () -> Boolean = { true }` parameter)

**Interfaces:**
- Produces S7 (`LiveAiCardMapper`, `OmniRealtimeViewModel` internal constructor, `QuickVisionDisplayPolicy`, `QuickVisionService.DISPLAY_OWNER`).
- Consumes S3 (`isDisplayAvailable`, `displayState`, `acquire/release`), S4 (`DisplayCard.Notice/LiveAI/QuickVision`, `DisplayIcon`), S5 (`GlassesDisplaySink`, `GlassesDisplayIntegration.displayManager`), S6 (`GlassesControllerRegistry`, `LiveAiController`, `GlassesDisplayIntegration.router`), `QuickVisionMode.getDisplayName(context)`, `R.string.display_looking/display_analyzing/feature_quickvision_title`.

**Scope (decision D6, Live AI half):**
- `LiveAiCardMapper.map` per S7 (pure; phase table, `takeIf`, `ifBlank`).
- `OmniRealtimeViewModel`: internal constructor `(application, sink, controllers)`; the public constructor passes `GlassesDisplayIntegration.displayManager(application)` and `GlassesDisplayIntegration.router(application)`. In `init`, register the `LiveAiController` and launch one collector over `combine(viewState, userTranscript, currentTranscript, messages)` that calls `sink.show(LiveAiCardMapper.map(state, user, current, lastAssistant))` whenever `isConnected.value || state is Connecting`; the manager throttles, so no VM-side debounce. `disconnect()` ends with `sink.showStatus()`; `onCleared()` unregisters the controller (invariant I4).
- Quick Vision half: at the top of the `captureAndAnalyze` job, `if (QuickVisionDisplayPolicy.wantsDisplayClaim(sessionManager.isDisplayAvailable.value)) { sessionManager.acquire(DISPLAY_OWNER); displayClaimHeld = true }` (session-only claim, no camera intent — keeps the capturer's `CameraBusy` path the only conflict and lets the display outlive the capturer's `release`). Cards: `Notice(modeName, display_looking, EYE)` after the "looking" TTS; `Notice(modeName, display_analyzing, EYE)` at analyzing; `DisplayCard.QuickVision(modeName, description)` on success; `Notice(modeName, <the spoken failure text>, EXCLAMATION_TRIANGLE)` on analysis failure and in `failAndFinish`. Dwell: line 299 `delay(500)` → `delay(QuickVisionDisplayPolicy.dwellMs(sessionManager.displayState.value, displayClaimHeld, success = true, fallbackMs = QuickVisionDisplayPolicy.DEFAULT_DWELL_MS))`; line 320 `delay(2000)` in `failAndFinish` → `delay(QuickVisionDisplayPolicy.dwellMs(sessionManager.displayState.value, displayClaimHeld, success = false, fallbackMs = FAIL_DWELL_FALLBACK_MS))` (so without a claim the failure path still dwells 2 000 ms exactly as in Phase B; with a claim on a STARTED display it dwells `ERROR_DWELL_MS`). `finishService()` calls `displaySink.showStatus()` then `cleanup()`, which releases `DISPLAY_OWNER` when held. `modeName = modeManager.currentMode.value.getDisplayName(this)`. The service's claim/dwell/release ordering is NOT extracted into a helper: the cross-seam ordering that matters (claim before the capturer, capturer release keeps the session, display released last) is proven by the composing test below against the real manager + capturer; the in-service sequencing stays emulator/hardware-verified (E3, H3/H4).

**Tests (JVM, at least 12):**
- `LiveAiCardMapperTest` (≥ 7): `connectingMapsToConnecting`, `recordingMapsToListening`, `processingMapsToProcessing`, `speakingMapsToSpeaking`, `idleConnectedAndErrorMapToIdle`, `streamingTranscriptIsNotFinal` (assistantText = current, `isFinal == false`), `doneTranscriptFallsBackToTheLastAssistantMessageAndIsFinal`, `blankUserTranscriptIsNull`.
- `QuickVisionDisplayPolicyTest` (≥ 4): `resultDwellsFifteenSecondsWhileStartedWithClaim`, `errorDwellsFiveSecondsWhileStartedWithClaim`, `fallbackIsReturnedWithoutClaimOrWhenNotStarted` (claim false → 500; STOPPED → 500; NOT_ATTACHED → 500 — all with `fallbackMs = DEFAULT_DWELL_MS`), `failAndFinishFallsBackToTwoSecondsWithoutClaim` (`success = false, claimHeld = false, fallbackMs = 2_000` → 2 000; the same with STARTED + claim → `ERROR_DWELL_MS`), `wantsClaimFollowsAvailability`.
- `GlassesPhotoCapturerTest.quickVisionDisplayClaimOutlivesTheCapturerAndReleasesTheDisplayLast` (≥ 1; the Phase B Recommendation 5 "compose the real classes across the seam" test, invariant I2): `observer.device.value = displayGlasses`; `factory.nextCaptureResult = Success(heicPhoto)`; `val manager = newManager()`; `manager.acquire("QuickVisionDisplay")` (session-only, the service's claim) → `factory.createCalls == 1`; `factory.last.emitStarted()` → `factory.last.addDisplayCalls == 1`; `factory.last.display.emitStarted()` → `manager.displayState.value == STARTED`; `val outcome = async { capturer(manager).capture() }`; `factory.last.cameras.single().stateFlow.value = STREAMING` → `outcome.await()` is `Captured("photo", fromVideoFrame = false)`; then assert `manager.hasSession`, `manager.ownerCount == 1`, `manager.hasCameraClaim == false`, `manager.currentCameraOwner == null`, `factory.last.stopCalls == 0`, `factory.last.removeDisplayCalls == 0`, `manager.displayState.value == STARTED`, `manager.currentDisplay() != null` (the capturer's `finally { stopCamera; release }` at `GlassesPhotoCapturer.kt:99-100` did not stop the shared session or the display); finally `manager.release("QuickVisionDisplay")` → `removeDisplayCalls == 1` recorded before `stopCalls == 1` (fake order), `displayState == NOT_ATTACHED`, `ownerCount == 0`, `hasSession == false`.
- Not JVM-testable: `OmniRealtimeViewModel` (its `init` constructs `APIKeyManager`/`EncryptedSharedPreferences`) and `QuickVisionService` — covered by the emulator checklist (Task 9 E3: Quick Vision on the mock device still finishes in ≈ 0.5 s, no claim taken because `isDisplayAvailable == false`) and hardware items H3/H4; the claim-vs-capturer ordering itself is the composing test above.

**Commit:** `feat(android): Live AI and Quick Vision display cards — LiveAiCardMapper, LiveAiController for EndLiveAI, session-only QuickVisionDisplay claim with a 15 s result dwell`

**Risks / notes for the drafter:**
- The Quick Vision claim must be taken before the capturer's `acquire(forCamera = true)` so the session created for the capture is shared, and released in `cleanup()` (also reached from `onDestroy`), never from the capture job's `finally` (the capturer's own `release` must not drop it).
- With `isDisplayAvailable == false` (mock device, non-Display glasses, setting off) the service behaviour is byte-identical to Phase B except the `showStatus()` call, which is a no-op send while no display is attached.
- `speakAndWait(description)` already blocks for the TTS duration; the 15 s dwell starts after it (spec: the card must be readable/pageable after the announcement).
- Live AI's `combine` collector fires on every transcript delta; that is intended — the manager coalesces.

### Task 6: LeanEat and OpenClaw hooks

**Files:**
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/LeanEatViewModel.kt` (anchors: `class LeanEatViewModel(application: Application)` line 20 — internal + public constructors, `LeanEatAnalyzer` declared above the class; `apiKeyManager`/`leanEatService` lines 22-23; `initializeService()` 53-58; `analyzeFood()` 66-107 (cards); `retakePhoto()` 109-114, `reset()` 164-169, `onCleared()` 176-179 (`showStatus()`))
- Modify: `app/src/main/java/com/smartview/glassai/services/LeanEatService.kt` (anchor: `class LeanEatService(private val apiKey: String)` line 18 — `: LeanEatAnalyzer`; `suspend fun analyzeFood` line 68 — `override`)
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt` (anchors: internal constructor lines 46-59 — add `sink`, `controllers` after `decodeImage`; public constructor 61-90; companion 92-109 — `SESSION_START_TIMEOUT_MS`; `init` 175-181 — register the controller; `enterScreen()` 186-192; `leaveScreen()` 194-202; `onChatEvent` 215-222; `sendText()` 238-245; `snapAndSend()` 248-279; `sendAsrText()` 400-410; `onCleared()` 444-450)
- Modify: `app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt` (anchor: `newViewModel` lines 109-125 — pass `sink = RecordingDisplaySink()` and `controllers = FakeControllerRegistry()` held in fields; tests at 260-263 and 298-305 keep their `ownerCount` assertions)
- Create: `app/src/test/java/com/smartview/glassai/viewmodels/LeanEatViewModelTest.kt`

**Interfaces:**
- Produces S7 (`LeanEatAnalyzer`, `LeanEatViewModel` internal constructor, `OpenClawViewModel` constructor additions + `SESSION_START_TIMEOUT_MS`, the OpenClaw card rules).
- Consumes S3 (`acquireAndStart`), S4, S5, S6 (`OpenClawController`, `GlassesControllerRegistry`), `TestBitmaps.stub()`, `FakeDat` (`FakeDatSessionFactory.stopAsync`).

**Scope (decision D6, LeanEat + OpenClaw half):**
- `LeanEatViewModel`: internal constructor `(application, sink, strings, apiKey, analyzerFactory)`; public constructor wires `GlassesDisplayIntegration.displayManager(application)`, `application::getString`, `{ APIKeyManager.getInstance(application).getAPIKey() }`, `{ key -> LeanEatService(key) }`. `analyzeFood()`: `sink.show(Notice(strings(R.string.feature_leaneat_title), strings(R.string.display_analyzing), FORK_KNIFE))` when entering Analyzing; `sink.show(response.toLeanEatCard())` (Task 2's `LeanEatCardMapper.kt`) on Result; `sink.show(Notice(title, message, EXCLAMATION_TRIANGLE))` on Error; `retakePhoto()`, `reset()` and `onCleared()` call `sink.showStatus()`.
- `OpenClawViewModel`: `enterScreen()` switches to `sessionStartJob = viewModelScope.launch { sessionManager().acquireAndStart(OWNER, SESSION_START_TIMEOUT_MS) }` guarded by `sessionHeld` (so it is launched at most once per screen entry; `acquire` still happens synchronously inside the launch before the first suspension); `leaveScreen()` cancels `sessionStartJob` BEFORE `release(OWNER)` (an owner that backs out during the previous session's STOPPING wait must not get a session created afterwards — the manager's `owner in owners` re-check is the second guard) and ends with `sink.showStatus()`; cards per S7 (`lastUserText` private field); controller registered in `init`, unregistered in `onCleared()`.
- No change to `OpenClawChatScreen`/`LeanEatScreen` composables (they construct the VMs through `viewModel()`, which uses the public constructors).

**Tests (JVM, at least 12):**
- `LeanEatViewModelTest` (≥ 5), `Dispatchers.setMain(UnconfinedTestDispatcher())`, `Application()` stub, `RecordingDisplaySink`, `strings = { "str:$it" }`, a fake analyzer returning a scripted `Result`, image = `TestBitmaps.stub()`: `analyzingShowsTheAnalyzingNotice`, `successShowsTheLeanEatCardMappedFromTheResponse` (`sink.last == response.toLeanEatCard()`), `failureShowsAnErrorNotice`, `retakeAndResetReturnToTheStatusMenu`, `missingApiKeyShowsNothingOnTheGlasses` (Error state on the phone, `shown` empty), `onClearedReturnsToStatus`.
- `OpenClawViewModelTest` additions (≥ 7): `enterScreenAcquiresAndStartsOnce` (two `enterScreen()` → `manager.ownerCount == 1`, `factory.createCalls == 1`), `enterScreenAfterAStoppingSessionStillGetsASession` (`factory.stopAsync = true`; a previous owner released → `enterScreen()` → `sessions[0].emitStoppedByDevice()` → `createCalls == 2`, STARTING), `leaveScreenDuringTheStoppingWaitCreatesNoSession` (`factory.stopAsync = true`; `manager.acquire("A"); factory.last.emitStarted(); manager.release("A")` → STOPPING; `vm.enterScreen()` → `manager.ownerCount == 1`, `factory.createCalls == 1`; `vm.leaveScreen()` → `ownerCount == 0`; `factory.sessions[0].emitStoppedByDevice()` → still `factory.createCalls == 1`, `manager.hasSession == false`, `manager.sessionState.value == STOPPED`), `chatDeltaShowsAPendingOpenClawCard` (`isFinal == false`, `replyText == delta`), `finalChatShowsAFinalCard`, `sendTextShowsTheUserTextWithAnEmptyReply`, `leaveScreenReturnsToStatus`, `controllerIsRegisteredAndSnapForwardsToSnapAndSend` (`registry.openClaw!!.snapAndSend()` → `isSending` flips; existing snap fake), `onClearedUnregistersTheController`.

**Commit:** `feat(android): LeanEat and OpenClaw display cards; OpenClaw chat acquires the session with acquireAndStart; OpenClawController for OpenClawSnap`

**Risks / notes for the drafter:**
- `LeanEatScreen` never starts the camera stream in Phase C (spec §9 fixes it in Phase D), so on hardware the LeanEat card can only be observed when another owner keeps the session alive; the JVM tests and the preview screen are the Phase C evidence, hardware item H5 is marked "best effort".
- `acquireAndStart` inside `viewModelScope.launch`: with `UnconfinedTestDispatcher` the `acquire()` runs synchronously in `enterScreen()`, which is what the existing `ownerCount == 1` assertions rely on; with the real Main dispatcher the ordering is the same (`viewModelScope` is bound to `Dispatchers.Main.immediate`, and `enterScreen()` is called from Main, so `acquire` runs before `launch` returns and precedes the first suspension).
- `OpenClawViewModelTest.setUp` builds the manager with the three-argument constructor; after Task 1 that still compiles (the fourth parameter defaults).

### Task 7: Settings toggle, Home display row, strings, `WearablesViewModel` exposure

**Files:**
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/SettingsViewModel.kt` (anchors: `apiKeyManager` line 31; add `_isDisplayEnabled`/`isDisplayEnabled`/`setDisplayEnabled` after the API-key status flows, ~line 62)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` (anchors: parameters lines 64-70 — no change in this task; state collection ~line 110 — `val isDisplayEnabled by viewModel.isDisplayEnabled.collectAsState()`; new `SettingsSection(title = stringResource(R.string.display_section))` between the Quick Vision section's closing brace (line 374) and `// AI Settings Section` (line 376) using the existing private `SettingsToggleItem` (line 1294; icon `Icons.Default.Visibility`-family glyph of the drafter's choice already imported in the file))
- Modify: `app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt` (anchor: delegating getters lines 150-159 — add `displayState`, `isDisplayCapable`, `isDisplayAvailable`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` (anchors: state collection lines 63-66 — three new `collectAsState()`; `DeviceStatusCard(` call lines 285-292 — three new arguments; `private fun DeviceStatusCard(` lines 615-778 — parameters and a `DisplayStatusRow` after the two `UpdateRequiredRow`s (lines 762-775); new `private fun DisplayStatusRow(text: String, ready: Boolean)` next to `UpdateRequiredRow` (line 780))
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml` (append rows 1–9 of S11 before `</resources>`; rows 10–18 exist since Task 3)
- Modify: `app/src/test/java/com/smartview/glassai/viewmodels/WearablesViewModelTest.kt` (append tests)
- Modify: `app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt` (append one test)

**Interfaces:**
- Produces S8 (`SettingsViewModel.isDisplayEnabled/setDisplayEnabled`, `WearablesViewModel.displayState/isDisplayCapable/isDisplayAvailable`, `DeviceStatusCard` parameters, `DisplayStatusRow`), S11 rows 1–9.
- Consumes S3 (`setDisplayEnabled`, `isDisplayAvailable`, `displayState`, `activeDevice`), `APIKeyManager.isGlassesDisplayEnabled/setGlassesDisplayEnabled`.

**Scope (decision D7):**
- Settings: section title `display_section`; toggle title `display_enabled`; subtitle `display_status_unsupported` when `wearablesViewModel`-independent check `GlassesSessionManager.getInstance(context).activeDevice.value?.isDisplayCapable == false`, otherwise `display_enabled_desc` (the Settings screen has no `WearablesViewModel`; read the manager's `activeDevice` via `collectAsState()`); `onCheckedChange = viewModel::setDisplayEnabled`. The toggle persists immediately (no Save button).
- Home: `DisplayStatusRow` only when `isDisplayCapable`; text per S8; a green `Success` tint when STARTED, `TextSecondaryLight` otherwise; icon `Icons.Default.Visibility`.
- `WearablesViewModel`: three delegating/derived properties; nothing else.
- Strings: rows 1–9 in both files with the exact texts; verify parity with `grep -c "<string name=" …` (both files must show the same count, 468 after this task: 450 + 9 from Task 3 + 9 here).

**Tests (JVM ≥ 3, instrumented ≥ 1):**
- `WearablesViewModelTest`: `displayStateFollowsTheManager` (capable device, STARTED session, `factory.last.display.emitStarted()` → `vm.displayState.value == STARTED`), `isDisplayCapableFollowsTheActiveDevice` (false → true → false on `device.value = null`), `isDisplayAvailableIsFalseWhenTheSettingIsOff` (manager built with `displayEnabled = { false }` in the test).
- `APIKeyManagerInstrumentedTest.glassesDisplayEnabledDefaultsToTrueAndPersists` (default true; `setGlassesDisplayEnabled(false)` → false after a fresh `APIKeyManager(context)`; restored to true in `finally`).
- `SettingsViewModel`/`SettingsScreen`/`HomeScreen` are not JVM-testable (Compose + `EncryptedSharedPreferences`) — emulator checklist E1/E2.

**Commit:** `feat(android): glasses display setting (Settings toggle), Home display status row, WearablesViewModel display exposure, en/zh strings`

**Risks / notes for the drafter:**
- `SettingsScreen` reads `EncryptedSharedPreferences` during first composition already (Phase B Minor 13); do not add a second `APIKeyManager.getInstance` call there — go through `SettingsViewModel`.
- `WearablesViewModelTest.setUp` builds the manager without the `displayEnabled` argument; the "setting off" test builds its own manager.
- String parity is a hard gate: run the `grep -c` check on both files before committing.

### Task 8: Debug preview screen and the MockDeviceKit toggle fix

**Files:**
- Create: `app/src/debug/java/com/smartview/glassai/debug/GlassesDisplayPreviewEntry.kt`, `GlassesDisplayPreviewScreen.kt`, `DisplayPreviewSamples.kt`, `MockDeviceState.kt`
- Create: `app/src/release/java/com/smartview/glassai/debug/GlassesDisplayPreviewEntry.kt` (stub, shape of the existing release `MockDeviceKitEntry.kt`)
- Modify: `app/src/debug/java/com/smartview/glassai/debug/MockDeviceKitViewModel.kt` (anchors at `25e6ae4`: `init` lines 56-69 — merge `MockDeviceState.get(deviceId)` into each rehydrated `MockDeviceInfo`; `disable()` 81-84 — `MockDeviceState.clear()`; `unpairDevice` 101-105 — `MockDeviceState.remove`; `execute()` 149-165 — `MockDeviceState.update(deviceId) { flags from updated }` after a successful block)
- Modify: `app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` (anchors: `sealed class Screen` lines 27-43 — `GlassesDisplayPreview`; Settings composable lines 192-213 — `onNavigateToGlassesDisplayPreview`; after the `MockDeviceKit` composable lines 264-270 — the preview route calling `GlassesDisplayPreviewEntry.Screen`)
- Modify: `app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` (anchors: parameters lines 64-70 — `onNavigateToGlassesDisplayPreview: () -> Unit = {}`; Developer section lines 438-447 — second `SettingsItem` guarded by `GlassesDisplayPreviewEntry.isAvailable`, `HorizontalDivider` between)
- Modify: `app/src/main/res/values/strings.xml`, `values-zh-rCN/strings.xml` (rows 19–20 of S11)
- Create: `app/src/testDebug/java/com/smartview/glassai/debug/DisplayPreviewSamplesTest.kt`, `MockDeviceStateTest.kt`

**Interfaces:**
- Produces S9 (`GlassesDisplayPreviewEntry`, `DisplayPreviewSamples.all`, `GlassesDisplayPreviewScreen`, `DisplayNodePreview`, `MockDeviceFlags`, `MockDeviceState`, `Screen.GlassesDisplayPreview`), S11 rows 19–20.
- Consumes S4 (`DisplayCard`, `DisplayNode`, `DisplayAction`, `toNode`, `pageCount`), S5 (`ResourceDisplayStrings`, `GlassesDisplayIntegration.displayManager`), S6 (`GlassesDisplayIntegration.router(app).dispatch`), `MockDeviceInfo`.

**Scope (decision D8):**
- `GlassesDisplayPreviewScreen`: top bar with back; a card picker (dropdown or chips) over `DisplayPreviewSamples.all(ResourceDisplayStrings(context))`; a page stepper (`page` state, `−`/`+` bounded by `card.pageCount()`, applied via `copy(page = …)` for the three paged cards); `DisplayNodePreview(card.toNode(strings))` inside a 600×600 black `Box` scaled with `Modifier.aspectRatio(1f).fillMaxWidth()`; every preview button `onClick` = show a `Snackbar` with `action::class.simpleName` (or `"Page(page=n)"`) AND `GlassesDisplayIntegration.router(application).dispatch(action)` so navigation / Quick Vision / paging really happen (paging updates the preview through `displayManager.currentCard`). Text approximations: HEADING 28 sp bold, BODY 20 sp, META 16 sp; `PRIMARY` white, `SECONDARY` 70 % white; icons as Material icons where an obvious match exists (`SMART_GLASSES→Visibility`, `META_AI→AutoAwesome`, `EYE→RemoveRedEye`, `FORK_KNIFE→Restaurant`, `CODE→Code`, `CHECKMARK→Check`, `X→Close`, `EXCLAMATION_TRIANGLE→Warning`, `I_CIRCLE→Info`, `ARROW_LEFT→ArrowBack`, `ARROW_RIGHT→ArrowForward`, `VIDEO_CAMERA→Videocam`, `MUSIC_NOTE→MusicNote`, `ENVELOPE_OPEN→Email`, `SPEAKER_WITH_THREE_ARCS→VolumeUp`, `TWO_ARROWS_CLOCKWISE→Refresh`), otherwise a 24 dp outlined box with the enum name in 8 sp; `Button`/`ButtonGroup` → `Button` (PRIMARY/SECONDARY) or `OutlinedButton` (OUTLINE) with the icon and label.
- `DisplayPreviewSamples.all`: eight entries in `DisplayCard` declaration order with realistic sample data (a 700-character Quick Vision result so paging is visible; a `DisplayCard.LeanEat` built directly with 3 `LeanEatFood`s and 2 suggestions — no `FoodNutritionResponse` in the debug source set either; an OpenClaw reply of 400 characters; Live AI SPEAKING with user + assistant text). Sample *data* is literal test text (not `R.string`), which is exempt from the two-locale rule; every piece of screen chrome uses `R.string` (rows 19–20 plus `display_prev/display_next`).
- `MockDeviceState` per S9; `MockDeviceKitViewModel` reads the flags on rehydration and writes them after every successful `execute()`, so the three toggles show the device's actual optimistic state after re-entry (Phase B ledger: the first "Unfolded" tap no longer sends `fold()`).

**Tests (JVM, `testDebug` source set, at least 5):**
- `DisplayPreviewSamplesTest` (≥ 3): `thereIsExactlyOneSamplePerCardSubtype` — NO reflection (`KClass.sealedSubclasses` needs `kotlin-reflect`, which is on no classpath here and may not be added): `samples.size == 8` and `samples.map { it.second::class }.toSet() == setOf(DisplayCard.Status::class, DisplayCard.Notice::class, DisplayCard.LiveAI::class, DisplayCard.QuickVision::class, DisplayCard.LeanEat::class, DisplayCard.OpenClaw::class, DisplayCard.WeChat::class, DisplayCard.Music::class)` (a Phase E card added to the sealed interface is caught by the `when` exhaustiveness in `DisplayNode.toNode`, not by this test); `everySampleRendersToAColumnRoot`, `pagedSamplesHaveMoreThanOnePage` (QuickVision, OpenClaw, LeanEat).
- `MockDeviceStateTest` (≥ 3): `updateCreatesFlagsForAnUnknownDevice`, `flagsSurviveAcrossReads` (simulates re-entry: `update` then `get`), `removeAndClearForget`.

**Commit:** `feat(android): debug GlassesDisplayPreviewScreen (600×600 card preview with live router dispatch) and MockDeviceKit toggles that survive re-entry`

**Risks / notes for the drafter:**
- `app/src/testDebug` is a new source set; Gradle picks it up automatically for `testDebugUnitTest` (`testReleaseUnitTest` ignores it). Do not reference debug classes from `app/src/test`.
- Compose Material icons: `material-icons-extended` is already a dependency (`app/build.gradle.kts:138` → `libs.androidx.compose.material.icons`; `Icons.Default.Restaurant` is used by `HomeScreen.kt:368`), so every glyph named in Scope is available; anything not in that list gets the labelled box.
- The preview dispatches through the real router: `StartQuickVision` really starts the foreground service (on the emulator it will announce "Glasses not connected" and stop — expected), `StartLiveAI` really navigates. Say so in a one-line hint on the screen (row 20 text already says "debug only").
- `MockDeviceState` is debug-only; the release source set never sees it.

### Task 9: Verification — unit suites, instrumented suite via `am instrument`, emulator checklist, hardware checklist, docs, report

**Files:**
- Modify: `app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` (anchors: `displayIsNeverAttachedForANonDisplayCapableDevice` lines 235-249 — rewrite the comment and assertions (`currentDisplay() == null`, `displayAttachAttempts == 0`, `isDisplayAvailable.value == false`); append `acquireAndStartFromASessionOnlyOwnerReachesStartedAcrossAStoppingWindow` and `acquireOffMainThrowsInDebug`)
- Modify: `app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt` (anchors: `store` field line 63 and the `savedHost/…/savedToken` fields 67-71 — replace with `InMemoryOpenClawSettingsStore`; `setUp` lines 104-111 — construct the in-memory store, drop the snapshot; `tearDown` lines 148-151 — drop the restore)
- Create: `app/src/androidTest/java/com/smartview/glassai/services/openclaw/InMemoryOpenClawSettingsStore.kt` (verbatim copy of `app/src/test/java/com/smartview/glassai/services/openclaw/InMemoryOpenClawSettingsStore.kt`)
- Create: `docs/superpowers/reviews/phase-c/hardware-checklist.md`, `docs/superpowers/reviews/phase-c/task-9-report.md`
- Modify: `android/CHANGELOG.md` (anchor: `## [2.0.0] - 2026-09-10` line 3 — a "### Meta Ray-Ban Display" block under 新功能), `android/README.md` (anchors: `## Features | 功能` line 13 — new `### Glasses Display | 眼镜显示` subsection; `### v2.0.0 (2026-09-10)` line 137 — a Display bullet), `README.md` (anchors: line 45 `🕶️ **Meta Ray-Ban Display 支持**` — mention on-lens cards on Android; line 174 Android note — add Display cards)
- No production code changes in this task (defects found here are fixed in a follow-up commit prefixed `fix(android): phase-c verification —`).

**Interfaces:** consumes everything above; produces the two review documents and the doc updates.

**Scope (decision D9):**
- Unit: `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest`; record totals per variant. Floors: release ≥ 152 + 15 + 34 + 17 + 12 + 12 + 12 + 3 = 257 (152 = Phase B's 154 minus the deleted `defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice` and minus the rewritten attacher test, which Task 1 counts inside its 15; then Tasks 1–7's minimums); debug ≥ release + 5 = 262 (`testDebug` adds `DisplayPreviewSamplesTest` + `MockDeviceStateTest`, which release does not compile).
- Instrumented on `emulator-5554` with MockDeviceKit (see Verification story for the exact commands): the whole `com.smartview.glassai` package (expected ≥ 24: 21 + `acquireAndStart…AcrossAStoppingWindow` + `acquireOffMainThrowsInDebug` + `APIKeyManagerInstrumentedTest.glassesDisplayEnabledDefaultsToTrueAndPersists`; the rewritten display case replaces an existing one), plus a targeted `-e class` run of `GlassesSessionManagerInstrumentedTest` to show (a) `displayState == NOT_ATTACHED`, `currentDisplay() == null`, `displayAttachAttempts == 0` after STARTED on the mock RAYBAN_META; (b) `acquireAndStart("OpenClawChat", 20_000)` issued while the previous session is STOPPING (`release` of the first owner, `isStoppingPreviousSession == true`) returns STARTED; (c) the OpenClaw instrumented class no longer touches the app's real settings (in-memory store; `APIKeyManager` untouched — assert `getOpenClawHost()` before/after equality in the test itself); (d) `acquireOffMainThrowsInDebug` (invariant I10 evidence): `val thrown = AtomicReference<Throwable?>(); Thread { thrown.set(runCatching { manager.acquire("off-main") }.exceptionOrNull()) }.apply { start(); join() }` → `thrown.get() is IllegalStateException` and `onMain { manager.ownerCount == 0 }` (the guard fires before `owners.add`; the androidTest APK runs against the debug app, so `BuildConfig.DEBUG` is true).
- Emulator manual checklist (debug APK, Pixel_5 API 31): E1 Settings → Glasses Display toggle persists across process restart and shows "Not supported by this device" as the subtitle while the mock device is active; E2 Home device card shows NO display row on the mock device; E3 Quick Vision via wake word / preview button on the mock device finishes as in Phase B (≈ 0.5 s after TTS, no 15 s dwell); E4 preview screen renders all eight cards, page stepper works on the three paged cards, every button shows its `DisplayAction` in the Snackbar and routes (Live AI / LeanEat / OpenClaw navigate, Quick Vision starts the service, Page/BackToMenu update the preview through `currentCard`, End/Snap/Music no-op without controllers); E5 MockDeviceKit toggles show the real optimistic state after leaving and re-entering the screen (power on + don, leave, re-enter → both on, "Unfolded" on; tapping "Unfolded" now sends `fold()` only when it was on).
- Hardware checklist document (`hardware-checklist.md`) for the product owner: prerequisites (Meta Ray-Ban Display FW ≥ V125, Meta AI ≥ V282, DAT Wearables App installed on the glasses, Developer Mode, not registered alongside the Meta sample app), then items H1 `addDisplay` succeeds after STARTED (Home row "Preparing…" → "Display ready"); H2 every card renders (use the preview screen's samples via Live AI / Quick Vision / OpenClaw / LeanEat flows; text legibility, pagination 280 chars); H3 every button routes (Status: 3 starts; Quick Vision: Prev/Next/Again/Done; OpenClaw: Snap/Done; Live AI: End); H4 Quick Vision result stays readable for 15 s then the L0 menu returns; H5 LeanEat card (best effort, needs a live stream); H6 camera + display simultaneously: Live AI frame rate before/after a card send, `RENDERING_FAILED` count in logcat (`GlassesDisplayManager`), decide on spec decision 8; H7 display sleep (25 s): record what `displayState` shows while asleep — if it stays STARTED the next card must wake the lens (resend-on-STARTED is not involved); if it shows STOPPED, the next feature's `show()` will do NOTHING in Phase C (S3: a self-reported STOPPED display keeps answering `INVALID_SESSION_STATE`) and the pre-decided `reattachDisplay()` fallback commit is triggered — mark the item "FAIL (expected, fallback scheduled)" rather than debugging on the spot; H8 L0 back gesture: record which of `DisplayState.STOPPED` / `DeviceSessionState.STOPPED` / `SESSION_ENDED_BY_DEVICE` fires, that the app does not restart the display, and that the Home row shows "Display stopped" until the next feature's session-level trigger (same STOPPED-but-attached semantics as H7 — the L0 back must NOT be auto-reattached per spec §7); H9 DAT-app-update-required path shows the Home update row; H10 hinge close → STOPPED → Home row "Display stopped" → reopen + re-enter a feature → recovery; H11 Live AI 600 ms coalescing reads smoothly (no flicker, final answer complete); H12 Settings toggle off while attached removes the display immediately; H13 stop-path timings (Phase B Recommendation 2): from `adb logcat -s GlassesSessionManager:D` record the `camera.stop() took N ms` / `session.stop() took N ms` lines for Live AI end, Quick Vision end and hinge close — anything ≥ 1 000 ms is the trigger for Phase A Recommendation 1 (SDK `stop()` calls on the manager's async stop path) in Phase D. Also note in H3 that Status-card taps with the app backgrounded are expected to be dropped in Phase C (S6 limitation). A second section "Phase B closing items (owner-verified, Recommendations 3/4)": Fun-ASR via the Beijing endpoint and via the Singapore endpoint (one utterance each, transcript arrives); the first real `openclaw devices approve` round trip against the owner's gateway (record the pairing output in `task-9-report.md`); Quick Vision inline error (analysis failure shows the inline error text, not only the toast); I2d fresh install with Bluetooth granted later (monitoring starts after the grant); D-7 re-run (Live Stream → unreachable `rtmp://` → error card → Close → Start → `connectRtmp` within 10 s; Stop → no ANR). Results table columns: item / expected / observed / pass-fail / logcat excerpt.
- Docs: CHANGELOG (Display cards, setting, preview screen, `acquireAndStart`, main-thread guard, hardware-only verification note), `android/README.md` Display subsection (what shows on the lenses, the setting, hardware requirements, "text-only in 2.0.0"), root README Android note.
- Report `task-9-report.md` in the Phase B Task 9 report's format: commands, per-run results table, deviations, emulator checklist results, links to the hardware checklist (unfilled — the owner fills it).

**Tests:** instrumented ≥ 3 new (the `acquireAndStart` case, `acquireOffMainThrowsInDebug`, and the `APIKeyManager` case from Task 7 are executed here), the rewritten display case, the hygiene rewrite; unit suites both variants; the emulator checklist E1–E5.

**Commit:** `test(android): Phase C verification — display-never-attached and acquireAndStart instrumented cases, OpenClaw instrumented test over an in-memory store, emulator checklist, hardware checklist, CHANGELOG/README, report`

**Risks / notes for the drafter:**
- `connectedDebugAndroidTest` stays blocked (winnat); use `am instrument` and paste the `INSTRUMENTATION_STATUS` summary into the report.
- The rewritten display case must not use `Thread.sleep`; it awaits `sessionState == STARTED` with `withTimeout` like its neighbours and reads `displayAttachAttempts` on Main.
- The hardware checklist is a document, not a test: nothing in Task 9 claims Display rendering was verified; the report states explicitly which items are hardware-only.
- The `acquireAndStart` instrumented case needs the previous session's STOPPED: after `manager.release(OWNER)` assert `manager.isStoppingPreviousSession` before calling `acquireAndStart(CHAT_OWNER, SESSION_TIMEOUT_MS)`; release `CHAT_OWNER` at the end (tearDown already does).

---

## Cross-task invariants

A reviewer checks each of these across the whole Phase C diff, not per task.

- **I1 — The display never keeps a session alive.** No code path calls `acquire()` on behalf of the display except the two session-only owners (`"QuickVisionDisplay"` during a Quick Vision run, `"OpenClawChat"` while the chat is open), and a session-only owner that releases while `acquireAndStart` is still waiting never gets a session created afterwards (`acquireAndStart` re-checks `owner in owners` after every suspension; `OpenClawViewModel.leaveScreen()` cancels `sessionStartJob` first). `GlassesDisplayManager` and `GlassesActionRouter` never call `acquire/acquireAndStart/ensureSession*`. Checks: `grep -n "acquire" GlassesDisplayManager.kt GlassesActionRouter.kt GlassesDisplayIntegration.kt` is empty; JVM tests `GlassesDisplayManagerTest.displayManagerNeverAcquiresTheSession` (`ownerCount` unchanged by `show/showStatus/clear`), `GlassesSessionManagerTest.acquireAndStartAbortsWhenTheOwnerReleasedDuringTheWait`, `OpenClawViewModelTest.leaveScreenDuringTheStoppingWaitCreatesNoSession`.
- **I2 — The display never blocks `camera.snap` or the capturer.** Display owners acquire without `forCamera`; `hasCameraClaim` stays false for them (Task 1 test `acquireAndStartWaitsForTheStoppingSessionThenStarts` asserts it; Task 5's claim is `acquire(DISPLAY_OWNER)` with the default). The Quick Vision half — the session-only claim taken BEFORE the capturer's `acquire(owner, forCamera = true)` (`GlassesPhotoCapturer.kt:95`), the capturer's `finally { stopCamera; release }` (`:99-100`) NOT stopping the shared session, the display staying attached through the analyze/dwell window, and the display owner releasing last — is proven by the composing test `GlassesPhotoCapturerTest.quickVisionDisplayClaimOutlivesTheCapturerAndReleasesTheDisplayLast` (real `GlassesSessionManager` + real `GlassesPhotoCapturer` + `FakeGlassesSession`; Phase B Recommendation 5).
- **I3 — A session-only owner never delays `stopSession()`.** `detachDisplay()` is synchronous bookkeeping plus one `removeDisplay()` call; `stopSession()` still returns after `current.stop()` with no new suspension; `acquireAndStart` only *waits* (in the caller's coroutine) for the previous STOPPED, it never postpones anyone's stop.
- **I4 — Every feature returns to the menu.** `OmniRealtimeViewModel.disconnect()`, `QuickVisionService.finishService()`, `LeanEatViewModel.reset()/retakePhoto()/onCleared()`, `OpenClawViewModel.leaveScreen()` all call `showStatus()`, so `currentCard` is null whenever no feature is active and the next STARTED shows the Status card (spec decision 1). Evidence: JVM for LeanEat (`retakeAndResetReturnToTheStatusMenu`, `onClearedReturnsToStatus`) and OpenClaw (`leaveScreenReturnsToStatus`); the Live AI and Quick Vision halves are NOT JVM-testable (Task 5) — their evidence is hardware items H3 (Live AI "End" → L0 menu) and H4 (Quick Vision dwell → L0 menu), named here on purpose.
- **I5 — Status card only while the display is STARTED.** The manager sends nothing (Status included) unless `sessionManager.displayState.value == STARTED && currentDisplay() != null`; there is no timer-based retry.
- **I6 — `addDisplay` only after STARTED; `removeDisplay` before `stop()`.** `maybeAttachDisplay()` checks `_sessionState.value == STARTED`; `stopSession()` and `teardownAfterDeviceStop()` call `detachDisplay()` as their first statement.
- **I7 — No Android class in the pure layer, in substance not just by import.** `DisplayCard.kt`, `DisplayNode.kt`, `DisplayPagination.kt`, `LiveAiCardMapper.kt`, `QuickVisionDisplayPolicy.kt` have no `import android.`, `import androidx.`, `import com.meta.` AND no `import com.smartview.glassai.models` (`FoodNutritionResponse` carries Compose `Color` getters, so referencing it would put an androidx type into the pure API — that is why `DisplayCard.LeanEat` holds plain ints and `LeanEatCardMapper.kt` is the only glasses-side importer of `models.*`); `grep -l` check in the Verification story; their tests run under `testReleaseUnitTest` too.
- **I8 — One DSL file.** `grep -rln "dat.display.views" app/src/main` = `DisplayCards.kt`, `DatGateway.kt`, `WearablesDatAdapter.kt`, `GlassesDisplayManager.kt`; the last three import only `views.ContentScope`. `grep -rn "IconName\|TextStyle\.\|ButtonStyle\.\|FlexBoxScope" app/src/main` matches only `DisplayCards.kt`.
- **I9 — SDK callbacks hop to Main.** The only lambda ever passed to `render()` is `GlassesDisplayManager::dispatchFromSdk`, which does `scope.launch(mainDispatcher) { … }` and touches no state before that launch.
- **I10 — Main-thread contract is enforced, not just documented.** Every public mutator of `GlassesSessionManager` starts with `checkMain()`; `publishFrame()` is the sole exception and says so in its KDoc. Evidence that the guard actually fires: the instrumented `acquireOffMainThrowsInDebug` (Task 9 (d)) — a JVM test cannot show it because `Looper.getMainLooper()` is null there.
- **I11 — Strings parity 100 %.** `grep -c "<string name=" app/src/main/res/values/strings.xml` equals the same count for `values-zh-rCN` after every task (450 → 459 after Task 3 → 468 after Task 7 → 470 after Task 8), and every key in S11 exists in both with the exact text.
- **I12 — No images, no video, no `VideoQuality` change.** `grep -rn "image(\|video(\|VideoQuality" DisplayCards.kt GlassesDisplayManager.kt` is empty; `StreamConfiguration` construction sites are untouched by Phase C.
- **I13 — Test floors.** `testReleaseUnitTest ≥ 257` (= 152 retained Phase B tests + Task 1 ≥ 15 + Task 2 ≥ 34 + Task 3 ≥ 17 + Task 4 ≥ 12 + Task 5 ≥ 12 + Task 6 ≥ 12 + Task 7 ≥ 3), `testDebugUnitTest ≥ 262` (release + the 5 `testDebug` tests; debug can never be below release), instrumented ≥ 24; none of Phase A/B's tests is deleted except `defaultAttacherNeverAttachesDisplayEvenForDisplayCapableDevice`, and only `displayAttacherIsCalledOnStartedWithActiveDevice` is rewritten (both named in Task 1).
- **I14 — Phase E surface is frozen.** `DisplayCard.WeChat(sender, preview)` and `DisplayCard.Music(title, artist, isPlaying)` exist, render placeholder nodes, and `DisplayAction.Music*` reach the router (logged) — Phase E adds behaviour without changing any sealed hierarchy or the router's signature.

## Verification story

**Proven on the JVM (both variants, `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest`):** the manager's attach/detach triggers and ordering (including zero `removeDisplay()` calls on display-less sessions), the enabled-setting gate, `reattachDisplay()`, `acquireAndStart` across a STOPPING window and its abort when the owner released during the wait, `resetForTests` completeness (Task 1); pagination, every card→node layout, text limits, fallback notices, the LeanEat mapper (Task 2); icon-mapping totality/injectivity, serial sending, 600 ms coalescing, STARTED resend, ordered `clear()`, the four `DisplayError` outcomes, the Main hop, the manager never acquiring (Task 3); the router table, controller registry, status provider (Task 4); the Live AI mapper, the Quick Vision dwell policy with both fallbacks, and the session-only display claim composed with the real capturer (Task 5); the LeanEat and OpenClaw ViewModel hooks with recorded sinks, including leave-during-STOPPING (Task 6); `WearablesViewModel` display exposure (Task 7); preview sample completeness and `MockDeviceState` (Task 8, `testDebug`).

**Proven on the emulator (MockDeviceKit, `emulator-5554`, Pixel_5 API 31 — no Display model exists, so `addDisplay` is never reached):** registration + shared session still work after the manager refactor; `addDisplay` is *not* attempted on a non-display device (counter stays 0); `acquireAndStart` from a session-only owner during STOPPING; the debug main-thread guard really throws off-Main (I10); `glasses_display_enabled` persistence; the OpenClaw handshake/snap tests over an in-memory store; the phone UI (toggle, hidden Home row, preview screen with live routing, MockDeviceKit toggles) via the E1–E5 checklist.

**Hardware only (product owner, Meta Ray-Ban Display, `docs/superpowers/reviews/phase-c/hardware-checklist.md` H1–H13 + the Phase B closing items):** `addDisplay` success and `DisplayState` transitions, actual card rendering and legibility, tap routing from the glasses (I4's Live AI / Quick Vision halves = H3/H4), camera + display bandwidth (frame rate, `RENDERING_FAILED` rate → spec decision 8), display sleep semantics (STARTED-and-wakes vs STOPPED → the `reattachDisplay()` fallback), L0 back gesture semantics, DAT-app-update path, hinge close recovery, the readability of the 600 ms Live AI cadence, the Settings toggle detaching live, stop-path wall times (H13), and the Phase B closing list (Fun-ASR both endpoints, real gateway approve round trip, Quick Vision inline error, I2d, D-7 re-run).

**Exact commands (Git Bash, from `D:/Coding/Workspaces/Android/turbometa-rayban-ai/android`):**

```bash
# unit suites, both variants, with totals
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest 2>&1 | tail -30
grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | awk -F'"' '{s+=$2} END {print "debug", s}'
grep -ho 'tests="[0-9]*"' app/build/test-results/testReleaseUnitTest/*.xml | awk -F'"' '{s+=$2} END {print "release", s}'

# string parity
grep -c "<string name=" app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml

# invariant greps (I7, I8, I12)
grep -rln "dat.display.views" app/src/main
grep -rn "IconName\|FlexBoxScope\|ButtonGroupScope" app/src/main --include=*.kt | grep -v DisplayCards.kt
grep -ln "import android\.\|import androidx\.\|import com\.meta\.\|import com\.smartview\.glassai\.models" app/src/main/java/com/smartview/glassai/glasses/DisplayCard.kt app/src/main/java/com/smartview/glassai/glasses/DisplayNode.kt app/src/main/java/com/smartview/glassai/glasses/DisplayPagination.kt app/src/main/java/com/smartview/glassai/glasses/LiveAiCardMapper.kt app/src/main/java/com/smartview/glassai/services/QuickVisionDisplayPolicy.kt
# I1 / nativeSession removal
grep -n "acquire" app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayManager.kt app/src/main/java/com/smartview/glassai/glasses/GlassesActionRouter.kt app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayIntegration.kt
grep -rn "nativeSession" app/src

# APKs
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest 2>&1 | tail -5

# instrumented (Gradle's connectedDebugAndroidTest is blocked by winnat on this host — Phase A/B Task 9 reports)
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-universal-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT
adb -s emulator-5554 shell pm grant com.smartview.glassai android.permission.CAMERA
adb -s emulator-5554 shell pm grant com.smartview.glassai android.permission.RECORD_AUDIO
adb -s emulator-5554 shell am instrument -w -r -e package com.smartview.glassai \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner
# targeted re-run of the Phase C manager cases
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.smartview.glassai.glasses.GlassesSessionManagerInstrumentedTest \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.smartview.glassai.services.openclaw.OpenClawNodeServiceInstrumentedTest \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner
# logcat filter for the hardware checklist
adb logcat -s GlassesSessionManager:D GlassesDisplayManager:D GlassesActionRouter:D
```

## Self-Review

### Spec §7 coverage

| Spec item | Task |
|---|---|
| `glasses/DisplayCard.kt`: Status, Notice, LiveAI, QuickVision, LeanEat, OpenClaw, WeChat, Music (last two filled in Phase E) | Task 2 (S4; WeChat/Music placeholders, I14) |
| `glasses/DisplayCards.kt`: one builder per card over the documented DSL, > 280 chars paginated | Task 2 (`paginate`, `toNode`) + Task 3 (`render` — one interpreter instead of eight builders; same DSL surface) |
| images via `bitmap` ≤ 600 px | Deliberately deferred (Global Constraints; spec §11 mitigation; hardware item H6 decides) |
| `GlassesDisplayManager`: coalesce, paginate, resend; Live AI 600 ms | Task 3 (S5 semantics; `LIVE_AI_MIN_INTERVAL_MS`) |
| `GlassesActionRouter`: the ten actions | Task 4 (S6 dispatch table) |
| Hooks: `OmniRealtimeViewModel` transcripts, `QuickVisionService` status/results, `LeanEatViewModel.analyzeFood`, `OpenClawViewModel` chat events | Tasks 5–6 |
| Setting `glasses_display_enabled` default on; Home device card shows Display state + update guidance | Task 1 (key) + Task 7 (toggle, row; the update rows already exist from Phase A) |
| Debug `GlassesDisplayPreviewScreen` 600×600 | Task 8 |
| Errors: INVALID_SESSION_STATE → wait for STARTED and resend; RENDERING_FAILED → log + text-only card; L0 back STOPPED not restarted | Task 3 (error matrix) + Task 1 (no auto re-add; the STOPPED-but-attached consequence is stated in S3 with `reattachDisplay()` as the pre-decided sleep fallback; H7/H8) |
| Verification: unit tests for pagination/card derivation; hardware checklist items | Tasks 2–3 + Task 9 (`hardware-checklist.md` H1–H13 + Phase B closing items) |
| §10: `isDisplayCapable == false` never calls `addDisplay` (instrumented) | Task 9 (rewritten display case with `displayAttachAttempts`) |

### Phase A/B review items → tasks

| Item | Task |
|---|---|
| Phase B Minor 9 / Phase A #18: debug main-thread guard | Task 1 (`checkMain`) |
| Phase B Minor 9 / Phase A #10: attach on every STARTED incl. PAUSED→STARTED; device metadata after STARTED | Task 1 (triggers + two tests) |
| Phase B Minor 10: `acquireAndStart` for session-only owners | Task 1 (+ Task 6 uses it, Task 9 instrumented case) |
| Phase B Minor 12 / ledger T1, T4: `resetForTests` completeness | Task 1 |
| Phase B Recommendation 1: camera claim vs session claim as the contract | Task 5 (`QuickVisionDisplay` session-only), I1/I2 |
| Phase A Recommendation 3: idempotent `maybeAttach`, real attacher wired in `getInstance` | Task 1 (the manager is the attacher) |
| Phase B Task 9 D6: MockDeviceKit toggles misreport after re-entry | Task 8 (`MockDeviceState`) |
| Phase B fix-wave note: instrumented OpenClaw test writes the app's real prefs | Task 9 (in-memory store) |
| Phase A #8: `getInstance(context)` ignores `context` | Task 1 (`context` now feeds the `displayEnabled` lambda) |
| Phase B Minor 8 / ledger T3: `dispatchInvoke` swallows `CancellationException` (triage row marked PHASE-C) | Nothing to schedule — already delivered by the Phase B fix wave: `OpenClawNodeService.kt:556` rethrows `CancellationException` with a comment citing Minor 8 (verified at `25e6ae4`); the triage row is stale |
| Phase B Recommendation 2: log `camera.stop()`/`session.stop()` wall time in debug builds | Task 1 (`BuildConfig.DEBUG`-guarded `Log.d` deltas in `stopSession()`/`stopCamera()`) + Task 9 H13 |
| Phase B Recommendation 3: record the first real `openclaw devices approve` round trip | Task 9 (`hardware-checklist.md` "Phase B closing items"; output pasted into `task-9-report.md`) |
| Phase B Recommendation 4: the Phase B closing list (Fun-ASR Beijing + Singapore, Quick Vision inline error, I2d, D-7 re-run) | Task 9 (`hardware-checklist.md` "Phase B closing items", owner-verified) |
| Phase B Recommendation 5: at least one test composing the real classes across a seam per cross-task invariant | Task 5 (`GlassesPhotoCapturerTest.quickVisionDisplayClaimOutlivesTheCapturerAndReleasesTheDisplayLast`, I2); Task 1/6 (`acquireAndStart` abort + `leaveScreenDuringTheStoppingWaitCreatesNoSession`, I1) |

### Decisions this plan made where the brief left room (recorded for the reviewer)
- `GlassesActionRouter` doubles as the `GlassesControllerRegistry` (D5 lists a `controllers` parameter): the registrations are only ever read by `dispatch`, so one process singleton (`GlassesDisplayIntegration.router(app)`) is handed to the ViewModels as the registry — no second object to wire, no ordering question between "registry created" and "router created". The router's public surface (`dispatch` + the four register/unregister methods) is what Phase E extends.
- `DisplayCard.LeanEat` carries plain ints/strings plus `LeanEatFood` (D3 wrote `LeanEat(response: FoodNutritionResponse, …)`): `FoodNutritionResponse` declares Compose `Color` getters, so it would put an androidx type into the pure layer's public API and break I7 in substance. The intent of D3 (a JVM-tested LeanEat card derived from the analysis response) is kept via `LeanEatCardMapper.kt` (`FoodNutritionResponse.toLeanEatCard()`, outside the pure layer, JVM-tested) and `LeanEatViewModel` calling it.
- `GlassesDisplay.stop()` stays on the seam (D2 lists it) although on 0.9.0 it is `Capability.stop()` = `terminate()` = `close()`: the KDoc says so, the fake ends in CLOSED for both, and no Phase C code calls `stop()` (the `removeDisplay()` fallback is `close()`).
- `detachDisplay()` is a no-op without an attached display and relies on `removeDisplay()` to close the capability (DAT: CLOSED = removed from the session), calling `close()` only when `removeDisplay()` fails; `SESSION_ALREADY_STOPPED`/`CAPABILITY_NOT_FOUND` are expected answers (debug log), everything else is an error log.
- `navigationRequests` keeps `replay = 0`: a tap while no Activity collects is dropped (logged), because replaying a stale `NavigationRequest` into the next Activity start would navigate the user somewhere they did not just ask for; foregrounding `MainActivity` from a glasses tap is Phase E scope.
- `reattachDisplay()` exists but is unused in Phase C: the spec §7 rule (self-reported STOPPED is not restarted) is kept literally, and the display-sleep case that may need it is decided by H7 with the fallback commit pre-written in S3.
- `fallbackNotice` takes `DisplayStrings` (the degrade title for LiveAI/LeanEat/OpenClaw is a localized feature name); the manager already holds the strings.
- `ContentScope` as an opaque type is allowed in the three seam/manager files (the `Display.sendContent` signature forces it); the "one DSL file" rule is defined precisely in Global Constraints and I8.
- Task 3 adds the nine strings it needs (S11 rows 10–18) so it compiles on its own; Task 7 adds the rest.
- Music placeholder buttons use `prev`/`next` labels and an empty label for play/pause; Phase E replaces them.
- `GlassesDisplayIntegration` holds all display singletons (manager, router, navigation flow); `GlassesDisplayManager.getInstance` is a thin delegate so both call styles in the brief work.
- The Quick Vision failure Notice dwells 5 s (a 500 ms flash would be unreadable); the success dwell is the brief's 15 s.
