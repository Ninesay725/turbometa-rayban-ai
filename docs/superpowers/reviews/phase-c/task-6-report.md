# Phase C Task 6 — LeanEat / OpenClaw display hooks

Implemented directly in the shared workspace on `android-v2`, against the approved plan's S4/S5/S6/S7 and Task 6. No commits and no Gradle, compilation, JVM tests, or device tests were run by this task. Tests below are **added, not run**; the parent coordinates verification.

## Paths changed

Modified:

- `android/app/src/main/java/com/smartview/glassai/viewmodels/LeanEatViewModel.kt`
- `android/app/src/main/java/com/smartview/glassai/services/LeanEatService.kt`
- `android/app/src/main/java/com/smartview/glassai/viewmodels/OpenClawViewModel.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/LeanEatScreen.kt` — minimal disposal hook authorized in the follow-up review
- `android/app/src/test/java/com/smartview/glassai/viewmodels/OpenClawViewModelTest.kt`

Created:

- `android/app/src/test/java/com/smartview/glassai/viewmodels/LeanEatViewModelTest.kt`
- `android/app/src/test/java/com/smartview/glassai/glasses/FakeControllerRegistry.kt`
- `docs/superpowers/reviews/phase-c/task-6-report.md`

Task 3's `RecordingDisplaySink` is consumed without modification. No strings, navigation, build configuration, gateway/provider files, or other agents' files were edited. `local.properties` was not read.

## Behavior

- LeanEat has the S7 injectable constructor and `LeanEatAnalyzer` seam; its public constructor uses the process display manager, application strings, stored API key, and `LeanEatService`. It displays the analyzing notice, mapped nutrition card, and error notice. Missing API keys remain phone-only errors. Reset, retake, and clearing the ViewModel return to status.
- LeanEat owns and cancels its analysis job on reset, retake, and clearing. `LeanEatScreen` now calls `reset()` from `DisposableEffect(viewModel).onDispose`: a navigation back-stack entry can keep the ViewModel and its analysis job alive after the screen leaves composition. Cancellation is rethrown, and cancellation checks prevent a late result/error from replacing another feature's card. An old request's cleanup cannot clear a newer analysis's busy flag. The service implements the analyzer and preserves cancellation. Incoming frame updates no longer cancel analysis via `setCapturedImage()`; camera/stream semantics remain Phase D work.
- OpenClaw registers its controller in initialization and on each screen entry, and unregisters on leave (also reached by clearing). This lets `OpenClawSnap` navigate when the ViewModel is retained but the screen is closed, and lets re-entry restore direct Snap & Send. Unregister uses the registry's existing identity semantics, so an older ViewModel leaving or clearing cannot remove a newer controller. The controller retains its active-screen guard for callbacks already holding the old reference. Text, ASR, and successful snaps display the user text with an empty pending reply; chat deltas/finals display their corresponding reply and finality.
- Entry calls `acquireAndStart(OWNER, 15_000)` once per visit, without camera intent. Exit cancels the start job before releasing the session-only claim and calls `showStatus()`. Chat events can still update phone history while closed, but do not publish display cards.
- Exit also cancels an in-flight snap. Cancellation checks after capture and decoding prevent late completion from sending a message or card; a visit generation prevents old cleanup from clearing a later snap's busy flag. Existing process-wide snapshot provider wiring, camera arbitration, Main-thread state updates, decode dispatcher, Connected send gate, and SCO handling are retained.

## Tests added — not run

30 new JVM tests: 12 LeanEat tests and 18 OpenClaw additions. All 19 pre-existing OpenClaw test methods remain; its file now declares 37 tests. ViewModelStore cleanup is used instead of adding production teardown methods.

`LeanEatViewModelTest`:

- `analyzingShowsTheAnalyzingNotice`
- `successShowsTheLeanEatCardMappedFromTheResponse` — literal expected nutrition values, independent of the mapper
- `failureShowsAnErrorNotice`
- `thrownFailureShowsAnErrorNotice`
- `retakeAndResetReturnToTheStatusMenu`
- `missingApiKeyShowsNothingOnTheGlasses`
- `onClearedReturnsToStatus`
- `onClearedDuringAnalysisCannotRestoreTheFeatureCard`
- `resetDuringAnalysisCannotRestoreAStaleResult` — retains the ViewModel, resets it as screen disposal does, then shows another feature's card before completing the old analysis
- `incomingFramesDoNotCancelTheCurrentAnalysis`
- `retakeDuringAnalysisCannotRestoreAnError`
- `cancelledAnalysisCannotClearTheNewAnalysisBusyFlag`

`OpenClawViewModelTest` additions:

- `enterScreenAcquiresAndStartsOnce`
- `leavingAfterTheStartTimeoutStillReleasesTheSessionClaim`
- `enterScreenAfterAStoppingSessionStillGetsASession`
- `leaveScreenDuringTheStoppingWaitCreatesNoSession`
- `leaveAndReenterDuringTheStoppingWaitAcquiresOnlyForTheNewEntry`
- `chatDeltaShowsAPendingOpenClawCard`
- `finalChatShowsAFinalCard`
- `sendTextShowsTheUserTextWithAnEmptyReply`
- `sendAsrTextShowsTheRecognizedTextAndAssociatesTheReply`
- `leaveScreenReturnsToStatusAndLateChatCannotRestoreTheCard`
- `controllerIsRegisteredAndSnapForwardsToSnapAndSend`
- `aControllerTapAfterLeavingCannotStartAnotherSnap`
- `snapTapNavigatesAfterLeavingAndSnapsAgainAfterReentry`
- `leavingAndClearingAnOlderViewModelKeepsTheNewController`
- `snapCompletionFromThePreviousEntryCannotRestoreItsCard`
- `leavingCancelsASnapWithoutLettingItsLateCompletionClearTheNextSnapBusyFlag`
- `onClearedUnregistersTheControllerAndReturnsToStatus`
- `realProviderSnapBorrowsCameraWhileChatKeepsSessionAndDisplayAlive`

The last test composes the real OpenClaw ViewModel, `GlassesSessionManager`, `SessionFrameProvider`, and `GlassesPhotoCapturer`, with the DAT gateway faked and bitmap conversion injected. It checks that the chat's session-only claim permits capture, the capturer releases its camera and claim, the chat keeps the same session/display alive, and leaving removes the display before stopping the session. This addresses Phase B review's request for cross-seam integration coverage (I1/I2/I4/I6), without mocking the provider's arbitration logic.

The two controller lifecycle tests added after review use the real `GlassesActionRouter` with real ViewModels. They cover navigation fallback after leave, restored snapping on re-entry, and identity-safe unregistration during an older ViewModel's repeated leave and eventual clearing. The retained-callback test also checks that leave removes the controller from the registry.

## Verification and concerns

- Scoped `git diff --check` passed after the final source edits. New test/fake files were checked for trailing spaces. Source review confirmed the current shared constructor/interface signatures and retention of the 19 existing OpenClaw tests. These checks do not establish compilation or passing tests.
- Parent must run both JVM variants, including these tests and the retained Phase B SCO/snapshot tests, after all shared interfaces are ready. No SDK display DSL runs in these JVM tests.
- The LeanEat Compose disposal hook was source-reviewed only; the JVM tests exercise its `reset()` behavior with a retained ViewModel and late completion, not Compose disposal itself. No Phase D camera startup or stream changes were added.
- LeanEat still does not create a glasses session; hardware display requires another owner, as the approved plan specifies. Its existing blocking HTTP request can finish after cancellation, but its canceled ViewModel job cannot publish the result/error.
- OpenClaw's existing chat event contract exposes text/finality without a per-visit request identifier. Display updates are suppressed while closed; a gateway reply arriving after re-entering the same ViewModel is treated as a current chat event. Request/run attribution remains an existing service-contract limitation.
- The required Meta `llms.txt?full=true` fetch returned a login page. The parent's first-app Wearables query was acknowledged, and this task also actually called `search_dat_docs` before edits. Implementation follows the approved seams and installed Android display skill; no new SDK API assumptions were introduced.
