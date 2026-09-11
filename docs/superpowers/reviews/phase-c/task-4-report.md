# Phase C Task 4 — router, status provider and Activity navigation

Implemented the assigned S6 router/status code and Application/Activity/navigation call sites in the shared `android-v2` workspace. No Gradle invocation, commits, dependency changes or access to `android/local.properties`. `GlassesDisplayIntegration.kt` remained exclusively Popper's file; its completed S6 additions were inspected read-only and match these call sites.

## Files written by this worker

- `android/app/src/main/java/com/smartview/glassai/glasses/GlassesActionRouter.kt` — `NavigationRequest`, `LiveAiController`, `OpenClawController`, `GlassesControllerRegistry` and the router in one file, matching S6.
- `android/app/src/main/java/com/smartview/glassai/glasses/AppStatusProvider.kt` — active device name, current API-key readiness and current OpenClaw connection flag. Injected lookups run only in `currentStatus()`.
- `android/app/src/main/java/com/smartview/glassai/MainActivity.kt` — passes `GlassesDisplayIntegration.navigationRequests` to navigation and calls `ensureStarted(application)` immediately after `wearablesViewModel.startMonitoring()` in the existing Bluetooth-granted path.
- `android/app/src/main/java/com/smartview/glassai/TurboMetaApplication.kt` — calls `GlassesDisplayIntegration.install(this)` immediately after `OpenClawIntegration.install(this)`; existing DAT initialization and permission contracts are preserved.
- `android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` — required `SharedFlow<NavigationRequest>` parameter; collects through the Activity's lifecycle outside the NavHost, and maps all three requests to the specified routes with `launchSingleTop = true`. Task 8 preview routing in the same file is recorded in `task-7-report.md`.
- `android/app/src/test/java/com/smartview/glassai/glasses/GlassesActionRouterTest.kt` — 21 tests.
- `android/app/src/test/java/com/smartview/glassai/glasses/AppStatusProviderTest.kt` — 5 tests.
- `android/app/src/test/java/com/smartview/glassai/glasses/FakeControllerRegistry.kt` — shared feature-test registry with identity-based unregister semantics and unregister counters.

## Routing and lifecycle behavior

The router implements all ten S6 actions. Quick Vision invokes the injected service starter, Page shows the carried card, BackToMenu shows status, EndLiveAI uses the registered controller, and OpenClawSnap uses its controller or requests OpenClaw navigation. The three Music actions only log. Registration replaces the previous instance; unregister uses `===` so an equal but different controller cannot clear the registered one. Debug main-thread guards cover dispatch and registry mutation and tolerate Android's null Looper stubs in local unit tests.

Navigation is collected inside `LaunchedEffect(navigationRequests, lifecycleOwner)` with `repeatOnLifecycle(Lifecycle.State.STARTED)`. STOP cancels the subscription. The router logs and drops navigation requests when subscription count is zero and logs a full-buffer drop when `tryEmit` fails. No Activity foregrounding or replay is added. This is the user's explicit lifecycle tightening of S6's plain LaunchedEffect example.

Review follow-up: the controller identified synchronous `startForegroundService` rejection from a glasses callback. The StartQuickVision branch now catches `ForegroundServiceStartNotAllowedException` and `SecurityException`, logs the rejected tap and returns normally. This keeps the process callback from crashing on those two expected failures, leaves the current card alone, and adds no navigation, retry, delayed action or Activity foregrounding. Android documents the first exception as a rejected foreground-service start in its [API reference](https://developer.android.com/reference/android/app/ForegroundServiceStartNotAllowedException). The guard covers synchronous callback rejection; asynchronous errors inside the service itself remain the service owner's responsibility.

Controller/Popper ruling: keep the integration's existing service-start lambda; the guard is in this worker's owned router file, with two new regression tests. No overlapping integration edit or additional string key is necessary. Catching the specific platform exceptions also leaves unrelated programming and coroutine-cancellation failures visible.

## Exact integration contract for the controller / Popper

These additions are already present in Popper's `android/app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayIntegration.kt` at handoff; do not add a second implementation:

1. A process `MutableSharedFlow<NavigationRequest>(replay = 0, extraBufferCapacity = 4)`, exposed as `val navigationRequests: SharedFlow<NavigationRequest>` via `asSharedFlow()`.
2. `fun router(app: Application): GlassesActionRouter`, a singleton using `sink = displayManager(app)` and that same mutable navigation flow. Its Quick Vision callback is exactly:

   ```kotlin
   app.startForegroundService(Intent(app, QuickVisionService::class.java).apply {
       action = QuickVisionService.ACTION_CAPTURE_AND_ANALYZE
   })
   ```

3. `statusProviderFactory` creates the new provider with these lazy lookups:

   ```kotlin
   AppStatusProvider(
       sessionManager = GlassesSessionManager.getInstance(app),
       hasLiveAiKey = {
           APIProviderManager.getInstance(app)
               .getLiveAIAPIKey(APIKeyManager.getInstance(app)).isNotBlank()
       },
       openClawState = { OpenClawNodeService.getInstance(app).connectionState.value },
   )
   ```

4. `fun install(app: Application)` sets that factory inside `runCatching` and invokes startup only with `BLUETOOTH_CONNECT` granted. It does not eagerly read the API key.
5. `fun ensureStarted(app: Application)` is idempotent and permission-gated, assigns `displayManager(app).actionHandler = router(app)::dispatch`, and observes OpenClaw's connected boolean with `map` and `distinctUntilChanged` on Main. It calls `showStatus()` only when `currentCard.value == null && sessions.displayState.value == GlassesDisplayState.STARTED`.

No missing S6 integration method or mismatched constructor name was found in the final source review. Feature hooks remain owned by their respective workers; they can now use the real registry and the shared fake.

## Tests added versus run

Added **26 JUnit tests; none run by this worker**. The 21 router tests cover all actions, Live AI/OpenClaw controller replacement and identity-safe unregister, no-controller behavior, no-subscriber drops, cancel/restart non-replay, an actual `LifecycleRegistry` + `repeatOnLifecycle(STARTED)` stop/start regression, and rejected Quick Vision startup for both platform exceptions. Each service-rejection test also dispatches a subsequent BackToMenu to check that routing remains usable. The 5 status tests cover name changes/disconnection, all four readiness-flag combinations, transitional OpenClaw states, and lazy construction without key/state reads.

Actually performed: read-only review of router/status sources and all changed production call sites against S6; scoped `git diff --check`; trailing-whitespace checks on all five new Kotlin files; annotation counts of 21 + 5; search confirming the only production `TurboMetaNavigation` call supplies the new argument and no duplicate controller-registry declarations exist. The service-rejection guard and regression tests were added after the controller's initial integrated compile attempt and need inclusion in its next build/test pass.

The controller still needs to compile and run both variant JVM suites, including the new lifecycle regression, and validate foreground/background navigation on the emulator/device. These static checks are not a compilation or runtime pass claim. Gradle, instrumented execution and commits remain controller-owned.
