# Phase D — translation integration fixes

Implemented all four accepted findings in `translation-integration-review.md`. No Gradle, adb, commits, cloud calls, or hardware operations were run. The parent's earlier 491 debug / 479 release / 11 platform passes predate these fixes and do not validate them.

## Corrections

1. **Camera lease:** `ScreenTranslationVisual.start()` saves the parent's `streamLease(owner)` after owner-scoped startup. Startup completion and each frame read verify that lease. Frames come only from `currentFrame(owner, lease)`. The production `readOwnedTranslationImage` helper checks ownership before reading and again on Main after background encoding, before bytes can be delivered. Lease loss follows the existing camera-unavailable path and owner-scoped cleanup; audio may continue. No shared WearablesViewModel changes were made.
2. **SCO loss:** the attempt observes its connected flow through configuration and recording. Loss stops its connection, source, images and route, clears the glasses-input claim, and shows localized retry guidance. Actual `PcmAudioSource.start` also checks the latest route/permission so ACK cannot beat a queued disconnection observer. Stop fences the attempt and cancels the observer before cleanup. A retired route cannot fail its successor.
3. **Foreign SCO:** the translation route rechecks permission and communication-route availability after waiting for headset availability and immediately before requesting SCO. A busy route stops visibly without creating another capture source. The coordinated `BluetoothAudioManager(context, ownedScoOnly = true)` option ignores unrequested CONNECTED broadcasts and never stops an unrequested route during cleanup. Other callers retain the default ownership behavior. ERROR, profile loss and Stop now publish disconnected state. The small production ownership policy and route wrapper have isolated JVM coverage.
4. **Latest history:** `finish()` snapshots the current connection's authoritative text before cancelling collectors or closing the service. It bounds both text fields to 16,000 characters and uses that snapshot for stopped UI and history. The existing identity fence, single history entry per attempt and 50-entry RAM cap remain.

## Files changed for this fix

- `android/app/src/main/java/com/smartview/glassai/viewmodels/LiveTranslateViewModel.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveTranslateScreen.kt`
- `android/app/src/main/java/com/smartview/glassai/managers/BluetoothAudioManager.kt` (coordinated opt-in ownership mode)
- Both locales' dedicated `strings_translate.xml` (one microphone-route guidance key each)
- `android/app/src/test/java/com/smartview/glassai/viewmodels/LiveTranslateViewModelTest.kt`
- `android/app/src/test/java/com/smartview/glassai/viewmodels/TranslationAudioRouteTest.kt` (new)
- `android/app/src/test/java/com/smartview/glassai/ui/screens/LiveTranslateVisualLeaseTest.kt` (new)
- This report and the Task 4 report link.

Public screen signatures, Nav/Home, shared VM, translation service/protocol and settings screen are unchanged by this fix.

## Regressions and verification

Added **15 tests**; the three focused classes now contain **32 test methods** (24 VM + 5 route/ownership + 3 visual lease). These cover route loss before ACK and after READY, capture/image cleanup, retired route events, busy-route UI, lease loss with audio continuing, Stop before text collection, immediate ERROR before text collection, late retired text, foreign CONNECTED during availability wait, cancellation before SCO request, requested-route cleanup, denied Bluetooth permission, default-versus-owned policy, rejecting another owner's frame, and rejecting encoding completed after lease replacement.

Only static checks ran: the two XML files parse, have 41 unique matching keys each, and the eight changed Kotlin/XML files have no trailing whitespace. Parent helper signatures and the service's Main-dispatched source-start path were reread. No compilation or test success is claimed for these changes.

Parent should rerun the three focused classes and affected integrated checks. Real Bluetooth routing and cloud/image behavior still require their separate hardware/account validation.

## Fresh parent verification — 2026-09-11

The parent reports that the final full build passed with **509 debug / 497 release JVM tests**, with **zero failures, errors or skips**. Both APK builds and androidTest checks passed. This run covers all **15 translation integration regressions** above plus the parent's **three PhotoHandoff regressions**, superseding the earlier pending-rerun status.

Dalton's scoped rereview of all four accepted findings reported **no new material findings**. These are fresh parent-reported results; this worker did not rerun builds or tests. Only this report was appended for this update; no source changes were made.

Real SCO routing and cloud behavior remain pending hardware/account validation.
