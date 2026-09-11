# Phase C Task 3 — SDK renderer and serial display manager

Implementation written on the shared `android-v2` workspace based on `25e6ae4`.
**Build and JVM execution are deliberately deferred to the controller's single integrated build.**
No Gradle commands, commits, dependency changes, or reads of `local.properties` were performed.

## Files owned by this task

- `android/app/src/main/java/com/smartview/glassai/glasses/DisplayCards.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/ResourceDisplayStrings.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayManager.kt`
- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayIntegration.kt` (also contains `DeviceNameStatusProvider`)
- `android/app/src/test/java/com/smartview/glassai/glasses/DisplayIconMappingTest.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/GlassesDisplayManagerTest.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/RecordingDisplaySink.kt`
- Both locale `strings.xml` files: only S11 rows 10–18 added by this task.

The controller additionally assigned this task the full S6 additions in `GlassesDisplayIntegration.kt`.
Router, `AppStatusProvider`, MainActivity, Application, and navigation implementation remain owned by the Task 4 agent; their files were not edited here. Task 1 gateway/fakes and Task 2 pure model files were consumed without modification.

## Implementation

- Exhaustive root/nested node interpreter. Root Column/Row uses one SDK flex box; only nested containers forward `flexGrow`. All padding edges, alignments, styles, text colors, icons, and button actions are forwarded. Button-group STRETCH maps to CENTER. No images or video.
- All 26 resource properties resolve lazily from the Application context.
- A sequenced `MutableStateFlow` plus `collectLatest` serializes card sends and clears. Intermediate requests and 600 ms streaming waits can be cancelled; a started SDK operation **and its result bookkeeping** run inside `NonCancellable`. Final Live AI cards bypass the delay. Success records the accepted card and Live AI completion time on Main.
- Display lookup/layout happen on Main, only the SDK operation runs on the send dispatcher, and every SDK action hops to Main before reading the handler. Public display mutators and the handler setter have the debug Main guard.
- STARTED sends the retained feature or a fresh Status. Clear forgets the feature and is never replayed as a clear after reattachment. No session ownership or attachment calls originate from the display manager/integration.
- INVALID_SESSION_STATE waits for the next STARTED. RENDERING_FAILED gets one fallback attempt. DEVICE_DISCONNECTED and UNEXPECTED_ERROR are logged without timer retries.

### Race handling

1. Request sequence is checked both when the collector resumes after joining an old send and after a streaming delay. An intermediate value captured while `collectLatest` waits cannot escape conflation.
2. A fallback is enqueued only if the failed request is still the newest pending request, the display remains STARTED, and the request was not already a fallback. The private Card request carries a defaulted `isFallback` flag; the active feature remains the original card.
3. Every completed send checks the captured display's identity against the currently attached display before bookkeeping. A late result from a detached capability cannot publish stale `lastSent`, update the coalescing clock, or enqueue a fallback onto its replacement.
4. Clear uses the same non-cancellable serial operation path, so an in-flight clear finishes before a later card can reach the display.

The SDK's finite response timeout remains responsible for bounding each protected send; no manager retry loop or unbounded non-cancellable collector was introduced.

## S6 integration extension

- Manager and router are double-checked process singletons. Navigation uses `replay = 0`, `extraBufferCapacity = 4`.
- The production factory creates `AppStatusProvider`; API-key and OpenClaw status reads remain inside its supplied lambdas. The Task 3 `DeviceNameStatusProvider` remains available and tested.
- `install()` catches initialization failures and checks BLUETOOTH_CONNECT before starting. `ensureStarted()` also checks permission and is idempotent; it installs the action handler and one Main-scope observer.
- The OpenClaw observer maps to connected/not-connected and uses `distinctUntilChanged()`. It refreshes only when no feature is active and the display is STARTED.
- Quick Vision uses `startForegroundService` with the existing `ACTION_CAPTURE_AND_ANALYZE` action. Main/Application/Nav hookups are supplied by the Task 4 agent.

## Tests authored — not executed here

32 JVM test methods: 3 icon mapping tests and 29 manager/status-provider tests.

Coverage includes STARTED gating/menu/resend, three-card conflation, immediate first streaming send at virtual time zero, the precise 600 ms boundary, final flush with no trailing replay, non-Live-AI interruption, all four SDK failures, fallback failure stopping after two attempts, feature retention and detach/reattach, session owner count/camera-claim invariants, Main callback dispatch, IO send/Main layout and bookkeeping, ordered clear, superseded fallback versus both newer card and clear, detached failure/success, and cancellation-safe final-card timing.

Race tests use a controllable **send dispatcher**, so they exercise the real manager and Task 1 gateway fakes without subclassing or changing `FakeGlassesDisplay`. Content blocks are recorded and never invoked on JVM Android JSON stubs. No `FakeDat.kt` hooks are required.

## Verification performed

- Inspected `classes.jar` from cached `mwdat-display-0.9.0.aar`, artifact hash directory `4bd4794363b2dbadec791dea174a42be790a18d4`, using `javap` on extracted class files.
- Confirmed actual builder signatures/Kotlin parameter metadata, root versus nested `flexGrow`, all style/alignment mappings, and all **34** app icon names against the AAR catalog.
- Confirmed the actual DisplayError members: DEVICE_DISCONNECTED, INVALID_SESSION_STATE, RENDERING_FAILED, UNEXPECTED_ERROR. No stale `CAPABILITY_DENIED` display enum reference is used.
- Confirmed the SDK `waitForDisplayResponse` invokes coroutines `withTimeout` (the send-operation path passes 5000 ms).
- XML parsed successfully. At this task's verification point both locales had **459** unique string keys, identical key sets, and exact S11 row 10–18 translations. All 26 resource getters resolve existing keys. Later tasks may add their assigned keys.
- Whitespace checks on all seven owned Kotlin files and `git diff --check` for both resource files were clean apart from Git's informational LF/CRLF warnings.
- Read-only searches found no session ownership calls or image/video/VideoQuality use in the three Task 3 display implementation files.

The requested static Meta reference URL returned a login page; Wearables MCP and the cached AAR supplied the API cross-check. DSL JSON rendering, actual hardware behavior, runtime permission integration, and JVM/build pass status are **not claimed** by this report. The controller should run both unit variants after the pending S6 classes and other task files are integrated.
