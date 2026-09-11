# Phase C Task 5 — Quick Vision review fixes

Implemented the Quick Vision portions of accepted review findings 1, 2 and 4. Finding 3 (Omni/Gemini activation) and shared sink ownership remain parent-owned. No Gradle commands or commits were made; the tests below are authored but **not run** here.

## Files changed by this fix

- `android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt`
- `android/app/src/main/java/com/smartview/glassai/services/QuickVisionRunCoordinator.kt` (new; no Android/SDK dependencies)
- `android/app/src/test/java/com/smartview/glassai/services/QuickVisionRunCoordinatorTest.kt` (new)
- This report.

No changes were made to Omni, the display manager/router, the display policy, resources, or build configuration. The parent’s `GlassesDisplayManager.ownedSink(owner: Any)` implementation was read to check the integration signature.

## Rerun and release ordering

`QuickVisionRunCoordinator` now owns the run job. A second request is ignored during capture/analysis. Once a success result is published, `allowRerun()` permits Again during result speech or the subsequent dwell.

An accepted replacement performs `previous.cancelAndJoin()` before starting its capture. The old capturer finalizer, speech cleanup, and service cleanup therefore finish before the new run can reuse `QuickVisionService` or `QuickVisionDisplay`. Further taps while replacement is joining are ignored, preserving one pending replacement and no simultaneous captures.

The coordinator calls service cleanup in `finally`; normal completion alone calls the finish callback. A job-identity check suppresses an old run’s finish callback if Again was accepted just before the old run naturally completed. The coordinator’s child scope contains both the old job and its replacement, so stop/destruction cancels both even if the replacement has not begun its join yet.

Service destruction/explicit stop still performs synchronous, idempotent cleanup as a safety net. After stop, the coordinator accepts no new runs. The normal error paths now return after their existing dwell; the coordinator then cleans up and finishes the service. Capture outcome/error routing and no-display fallback durations are preserved.

## Content ownership

The service obtains `GlassesDisplayIntegration.displayManager(application).ownedSink(this)`. Its existing cleanup `showStatus()` is now conditional on this service still owning the content. Done or another feature taking ownership prevents late Quick Vision cleanup from clearing the newer card. Router Page ownership preservation is supplied by the parent.

## Bounded speech and cancellation

- Result utterances use distinct, increasing IDs and a `CompletableDeferred` outcome per utterance. SDK callbacks only complete that thread-safe outcome; cleanup resumes on the service’s Main coroutine.
- A synchronous listener-registration or `speak()` rejection returns REJECTED immediately, without awaiting a callback.
- Accepted speech completes on done, error, or stop. If no callback arrives, the wait times out after **60 seconds**, stops the engine, and continues to the ordinary result dwell/cleanup. Long narration is therefore deliberately capped at 60 seconds before the display dwell begins.
- External cancellation propagates. The speech wait’s `finally` removes its listener and stops its engine before `cancelAndJoin` allows the replacement to start. Duplicate or late callbacks complete only the old deferred and cannot finish a new utterance.
- TTS initialization now uses a `CompletableDeferred` with the existing 2-second timeout instead of an untracked latch-waiter thread. Late initialization cannot resume an expired continuation.

## Focused tests authored

15 methods in `QuickVisionRunCoordinatorTest` cover:

1. Duplicate requests rejected during capture.
2. Again waits through a delayed old finalizer and cleanup before capture 2.
3. Stop while joining prevents the queued capture.
4. Stop before the replacement begins also cancels the old run.
5. An old natural completion cannot stop the service after a replacement was accepted.
6. Again during speech stops speech, releases, then starts capture 2.
7. Again during dwell cancels the old timer.
8. Synchronous TTS rejection returns immediately.
9. Rejected speech still lets the run dwell and release its claim.
10. A synchronous completion callback before queue return is retained.
11. Done/error/stop callbacks all complete the wait.
12. Duplicate and late callbacks cannot complete another utterance.
13. The 60-second missing-callback boundary stops the engine.
14. Cancellation stops speech and remains cancellation.
15. Engine-thread completion resumes on the calling dispatcher.

These tests use the actual coordinator and speech-wait function, with controlled coroutines and callback functions. They do not instantiate Android Service/TTS or modify the shared DAT fakes. Device-level service/TTS behavior and the full owned-sink integration remain for the controller’s integrated verification.

## Checks performed

- Re-read the changed lifecycle paths and the parent’s landed `ownedSink` signature.
- Verified no old `captureJob`, latch waiter, `suspendCancellableCoroutine`, `return@launch`, or `failAndFinish` paths remain in the service.
- Confirmed the new helper has no Android/AndroidX/Meta imports, and counted 15 focused test methods.
- Whitespace checks passed for all three source/test files. `git diff --check` for the service passed with only Git’s informational LF/CRLF warning.
- No build/test pass, device behavior, or unrelated review-finding resolution is claimed by this report.
