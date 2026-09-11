# Phase C verification — 2026-09-11 Codex run

## Current result

Implementation Tasks 1–8 are complete and reviewed. The current code builds in both variants. Task 9 software verification is complete with one explicitly excluded SDK stress case; physical Display acceptance is pending. This is not approval to merge/release Android 2.0.0, and E/D are not yet implemented.

- `testDebugUnitTest`: **355 tests, zero failures/errors/skips**.
- `testReleaseUnitTest`: **344 tests, zero failures/errors/skips**. The 11-test difference is the debug-only MockDeviceKit/preview suite.
- `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, final aggregate run 57 seconds. Logs: `/tmp/codex-phase-c-final.log` (Git Bash temp). XML reports are under `android/app/build/test-results`.
- API 31 Pixel_5, `am instrument`: **OK (23 tests)**, 17.444 seconds. There are 24 declared cases: the separately reproduced rapid session-restart stress case is annotated Ignore. This is 23 passing plus 1 known SDK exclusion, not 24 passing. Raw final runner output is in `evidence/phase-c-instrumented-final.txt`.
- `git diff --check` passed with the repository's normal line-ending settings. Both resource XML files have 474 unique keys with identical sets.

These are this Codex continuation's actual results. The handoff's A/B 154/variant and 21 instrumented results remain historical. Intermediate compile failures during concurrent interface work and layout-assertion migration were corrected before this final run.

## Actual emulator UI checks

1. Cold launch reached Home; no physical device connected. Ordinary-device/no-Display attachment is also covered by instrumented checks.
2. Settings shows the Display toggle and capability explanation. Toggled true → false and observed the switch; restored original true. Fresh-instance encrypted preference/default behavior passed in the separate isolated instrumentation fixture.
3. Debug Settings opens Glasses display preview. Status card renders inside a 600-unit scaled black viewport; all three feature buttons visible.
4. Selected Quick Vision sample; first page shows result, page indicator and Next/Again/Done. Local Next reaches page 2/9 with Prev/Next/Again/Done, all within viewport. Screenshots record both states. Preview runs the same pure card tree with approximated glyphs; it does not prove SDK lens rendering.
5. MockDeviceKit: enabled, paired one ordinary Ray-Ban Meta, set Power/Worn/Unfolded true, left/re-entered. One paired card and all three true values remained. Disabled the test kit afterwards.
6. Release compiled with the release preview stub; debug-only source and dependency boundaries are retained. Release UI was not installed over the debug app merely to repeat a source-gated visibility check.
7. App Language is historically set to Chinese while API31 still renders English; this previously recorded locale issue remains Phase D, not a new Display regression. Long Chinese/multiline layout has pure model coverage; physical font wrapping remains H2.

Screenshots: `evidence/preview-status.png`, `preview-qv.png`, `preview-qv-middle.png`, `settings-display.png`, `mock-reentry.png`.

## SDK failure — remains open

The first full instrumented run completed five tests then hung in the new immediate session-only stop/restart case. A fresh-process targeted rerun, with two seconds between STARTED and stopping, **also hung** after the second STARTING timed out. Neither run has a successful JUnit completion. Both were stopped after diagnostic capture.

`SdkGlassesSession` follows the public API and waits for public STOPPED before recreation. Cached 0.9.0 bytecode confirms public STOPPED is published before health-listener removal and before the internal asynchronous stop acknowledgement is handled. No stronger public completion signal exists. The dump proves Main waiting on health lock/thread 50, then channel lock/thread 19, then a native mutex whose owner is not identified. This is an SDK/MDK internal synchronization failure, not proof of the exact native cycle or physical-hardware applicability.

See `sdk-restart-review.md` and preserved `evidence/phase-c-hang-trace.txt`, `phase-c-session-log.txt`, `phase-c-instrumented.txt`, `phase-c-settled-restart.txt`. The pre-stop wait is not a production fix or proven remedy. The stress test is retained and explicitly ignored to let the remaining regression suite run without wedging its process. Re-enable it for SDK/hardware assessment. No speculative production delay, internal API use, or unsupported claim that moving the call to another thread fixes the SDK was added.

This is a release risk: rapid feature exits/reentries need a phone+glasses check and, if reproduced, a supported SDK fix or a separately designed recovery approach. Nothing has been published upstream; the issue remains a local report. The earlier DAT decoder race is also still a separate known SDK item.

## Review corrections actually landed

- Shared display lifecycle: capability/setting gate, late metadata, idempotent attachment, terminal states, remove-before-session-stop, owner-aware waiting, Main guards and complete test reset.
- Serial sender: one stream of sends/clears, finite protected SDK operation, coalesced partial replies, final reply priority, one fallback, stale-display/sequence fencing.
- Feature ownership: feature-scoped sink cleanup cannot clear newer content; Page preserves ownership. SDK tap closures recheck originating display and content sequence on Main.
- Live AI: run invalidation is synchronous on End and provider replacement, including Connecting. Callbacks hop to Main and check the captured generation; old service-state observers are cancelled.
- Quick Vision: Again during result speech/dwell serially cancels/joins the old run before capture-owner reuse. TTS queue rejection, stop/cancellation, duplicate callbacks and 60-second timeout are covered by 15 coordinator/speech tests. Display claim outlives capture and releases last.
- LeanEat/OpenClaw: disposal/leave cleanup, retained-controller identity, cancelled/late results, real frame-provider composition, session-only chat acquisition.
- Layout: explicit lines/wrapping budget in addition to UTF-16 ceiling, preserved paged content, safe ellipses on nonpaged text; preview metrics aligned to cached SDK. Phone preview simplifies four-button labels for fit; actual SDK width checks remain hardware pending.
- Test hygiene: OpenClaw instrumented service uses an in-memory store; the display preference fixture uses its own encrypted prefixed files and has no actual-store setup/teardown. Historical API-key tests retain their existing restoration behavior. A reproduced legacy OpenClaw recorder race was fixed by waiting on the recorder's latch before cancellation, with no production protocol change.

## Pending

All physical checks H1–H13 in `hardware-checklist.md`, including sleep/L0 Back, actual Chinese rendering, concurrent camera bandwidth, microphone and provider credentials. The owner has hardware but no phone was attached in this continuation. No hardware result is inferred from screenshots, fakes or previous reports.