# Phase C Task 3 — focused review

**No concrete important defects found in the reviewed Task 3 implementation.** No production/test changes, Gradle runs, emulator operations or Git writes were performed. This report is the only file written by this reviewer.

Reviewed `GlassesDisplayManager.kt`, `DisplayCards.kt`, `GlassesDisplayIntegration.kt`, resource-string getters, manager/icon tests and the consumed display seam against S5, Task 3 and the relevant S3 terminal-state contract. Resolved Phase A/B work was not reopened.

**Snapshot boundary:** `showPage`/`ownedSink` additions appeared in the shared manager file during final verification. Their new ownership semantics are concurrent work and are outside this bounded review; the assessment below covers the S5 sender/renderer/integration paths inspected before those additions.

## Findings supporting the assessment

- **Cancellation and ordering:** the single `collectLatest` sender protects both SDK execution and result bookkeeping with `NonCancellable`. Card sends and clears share that sender. Sequence checks in the collector and `sendCard()` reject obsolete requests after an earlier send finishes or a streaming delay resumes. Successful Live AI sends update the coalescing clock inside the protected block, including final sends superseded while in flight.
- **Stale replay:** `sendCard()` and `onSendResult()` fence results by attached-display identity and latest request sequence. An old rendering failure cannot overwrite a newer card, menu or clear; a detached capability cannot publish late success or enqueue its fallback on a replacement display. Fallback requests carry their own flag, preventing recursive fallback attempts while retaining the original feature card.
- **Terminal states:** sends are gated on STARTED before and after the streaming delay. STOPPED/CLOSED do not trigger reattachment or timer retries. A subsequent STARTED sends the retained feature or a fresh Status; clear itself is not replayed. This follows the plan's deliberate terminal-display policy, including the hardware-dependent sleep limitation.
- **Typed failures:** all four pinned `DisplayError` values are handled as specified. Read-only inspection of the cached DAT 0.9.0 bytecode confirmed the 5,000 ms response wait, conversion of response timeout/error to `RENDERING_FAILED`, and the send-operation exception handler returning `UNEXPECTED_ERROR`. No unsupported exception-escape defect was inferred from the absence of an extra manager-level catch.
- **Renderer/integration:** root versus nested container parameters, styles, alignments, icon mapping and dispatch callbacks match the node contract. Callbacks hop to Main before accessing the handler. Integration installs one manager/router/connection observer and refreshes Status only with no active feature and a STARTED display. It introduces no display-only session owner.

## Verification limits

Read the existing controller-generated XML reports: `GlassesDisplayManagerTest` has 29 tests and `DisplayIconMappingTest` has 3, with zero failures/errors/skips in both debug and release reports. The controller separately reported integrated totals of 320 debug / 309 release; this reviewer did not rerun those suites.

The reviewed tests exercise cancellation/order, superseded and detached results, all four typed failures, coalescing, Main/IO boundaries and STARTED replay. They intentionally record rather than execute SDK content blocks. Actual DSL rendering and physical display sleep/back-gesture behavior remain hardware verification, as already specified by the plan.

## Appendix — owner-scoped sinks and Omni RunGate follow-up

This follow-up includes the previously excluded `ownedSink`/`showPage` API additions and the new `LiveAiRunGate` wiring in `OmniRealtimeViewModel`. It supersedes the earlier exclusion for those additions. Only this appendix was written; no code changes or Gradle/test runs were performed.

### Ownership and paging assessment

The parent-authorized API extension adds `GlassesDisplayManager.ownedSink(owner: Any)` and `GlassesDisplaySink.showPage(card)` with a compatible default. An owned `show()` publishes the card and records the owner; owned `showStatus()`/`clear()` compare owner identity (`===`) before invoking global cleanup. Direct manager `show`/`showStatus`/`clear` reset ownership. The manager's `showPage()` preserves it, and `GlassesActionRouter` now routes Page actions through that method. Consequently paging does not prevent the originating feature from dismissing its card, and old-feature cleanup cannot dismiss a newer owner's card. The underlying sequenced sender and late-result fences remain intact.

The earlier Quick Vision wiring gap is resolved in the inspected source: `QuickVisionService` now retains `displayManager(application).ownedSink(this)` at lines 94–95. Omni, LeanEat and OpenClaw also receive scoped sinks. No additional important defect found in the owner-wrapper/cleanup implementation.

### P2 — Replaced service callbacks could retain an accepted run (closed)

**Inspected locations:** `OmniRealtimeViewModel.kt:131–139`, `:146–152`, `:172`, `:230`, `:282–286`, `:377–378`.

The new gate correctly invalidates callbacks after `disconnect()`/`onCleared()` and rejects an earlier connect generation after a new `begin()`. Its check runs inside the Main coroutine, so callbacks queued before End are rejected when delivered afterward. Reinitialization also cancels the old state-observer jobs.

At the preceding snapshot, one replacement path reused an accepted generation: a provider change while the ViewModel was **Connecting** normally had `_isConnected == false`, so the provider observer skipped `disconnect()` and called `initializeService()` directly. That replaced/disconnected the service without advancing or ending `runs`. Callback lambdas captured only `runs.generation`, and `deliverForRun()` checked no service identity. A queued transcript or Gemini `onConnected` from the replaced service could therefore still pass `runs.accepts(run)` and set `_isConnected = true`/Connected or republish stale text on the glasses. Cancelling state-observer jobs did not cancel these independently launched callback deliveries. The old Gemini listener could deliver such events: its `onMessage` delegates to `handleServerEvent`, and `setupComplete` invokes the stored `onConnected` callback.

**Correction options originally proposed:** bind callbacks/collectors to specific service instances and recheck identity after hopping to Main, or invalidate the run before every active service replacement. The controller implemented the latter; a separate service-identity change is not required to close this finding for the verified initialization paths.

**Regression scope:** queued setup/transcript callbacks from a service replaced while Connecting must not mark the replacement connected or publish a card. `LiveAiRunGateTest` covers begin/end token replacement; the provider-observer wiring is verified by source inspection here, not by an executed ViewModel integration test.

### Follow-up evidence

The recorded genuine RED was independently read from the controller's debug XML: `retiringAnOldFeatureCannotClearTheNewFeaturesCard` expected the newer QuickVision card but received null. Source now also contains `pagingPreservesTheFeatureThatMayDismissTheCard` and `LiveAiRunGateTest.lateSetupAndTranscriptCannotReactivateAnEndedOrReplacedRun`. At this inspection, the focused debug XML still held the pre-implementation RED and no RunGate XML existed, so this appendix makes no post-change GREEN claim. The earlier 320/309 integrated results predate these additions.

### Closure verification — active provider replacement

Bounded source reinspection confirms the P2 is closed. The provider observer now calls `disconnect()` whenever `runs.active`, including Connecting, before `initializeService()` (`OmniRealtimeViewModel.kt:137–140`). `disconnect()` calls `runs.end()` synchronously before scheduling cleanup (`:382–386`), and `end()` both deactivates the run and advances its generation. Old callbacks, including ones already queued on Main, therefore fail the check inside `deliverForRun()` (`:378–379`).

All four `initializeService()` call sites were checked: initial construction is inactive (`:144`); explicit connect starts a new generation before replacement (`:359–360`); refresh disconnects before initialization (`:503–507`); provider changes end any active generation first (`:137–140`). Replacement cancels old observer jobs before service teardown (`:147–151`). Provider change does not call `connect()` or `runs.begin()`, so it introduces no implicit reconnect. No additional important defect found within this closure review.

Read-only controller XML inspection now shows the RunGate test and both stale-owner cleanup/paging tests passing in debug and release (report timestamps 2026-09-11T12:12:42–45Z). These reports do not prove a post-fix ViewModel integration run. The same manager reports each contain one separate failure, `queuedButtonsFromReplacedOrDetachedContentCannotRestoreOldCards` (`NoSuchElementException: List is empty`); this is recorded as existing test evidence, not diagnosed or reopened in this bounded provider-replacement review. No full-suite GREEN claim is made. No tests were added or run by this reviewer; only this report was changed.
