# Phase E implementation ledger

Base `f0302ac`, branch `android-v2`, 2026-09-11. Approved route C → E → D remains unchanged. No iOS changes or main merge/release. Pre-existing untracked configuration/handoff files preserved.

## Design and current work

The E plan is `docs/superpowers/plans/2026-09-11-android-v2-phase-e-bridge.md`. Bounded review found two contract gaps, both adopted: posted notifications replace same-key content even when redacted; Done/supersession retires the short notification session claim immediately. The reviewer report remains as historical evidence.

- Task 1: pure inbox/media selector and real Android notification parser authored by Darwin, parent integration pending.
- Task 2: parent implements preferences, listener, runtime, camera-free claims and media controls; independent review pending.
- Task 3: Dalton implements complete WeChat/music cards, local artwork boundary and preview/tests.
- Task 4: Tesla implements phone permission/toggle/media page and navigation/resources.
- Task 5: parent owns all builds/adb, reports and integration.

## This-turn verification (not historical Phase C results)

- `NotificationDisplayLeaseTest` first run failed compilation with four unresolved references to the not-yet-created class (expected test-first RED). Log `/tmp/codex-e-lease-red.log`.
- First integrated app compile caught a wrong DAT `DeviceSessionState` import; corrected to the existing pinned `core.session` package.
- Next integrated app and androidTest compile passed; JVM test compilation caught two exhaustive `Image` branches while Task 3 was still being edited. No suite pass claimed at this point.

## Known limits carried forward

C's MDK immediate-session restart stress test remains excluded with a documented SDK internal-lock hang, not fixed or silently counted as a pass. Physical Display rendering, real WeChat/NetEase/Qishui behavior and the owner's phone are not exercised by emulator fixtures. No real notifications or credentials are used in test data. No issue has been posted externally.

## Closeout

Tasks 1–4 implemented; all Task 2 review findings closed, including private fallback snapshots, obsolete track replay and stale media selection. Final suites: 388 debug / 376 release JVM passes, both APK builds and test APK pass; API31 bridge fixtures19/19 and complete suite42 passes with the existing SDK-stress exclusion. UI defaults/access navigation/package fields inspected. Test-only preference leakage found and corrected with verified runner restoration. Details and historical failures are retained in `task-5-report.md` and transcripts. Hardware checklist remains pending. Continue D; no main merge/release.
