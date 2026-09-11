# Phase D continuation ledger

Base `33e261e`, Android only on `android-v2`. A/B are historical, C/E were implemented and tested in this Codex continuation. Physical/cloud validation remains separate.

- Scope approved by existing design §9 and `2026-09-11-android-v2-phase-d-parity.md`.
- Research: current official Alibaba protocol/voice docs and local iOS reference reviewed; exact approved Qwen3 model names retained. Real model/region acceptance is pending.
- Plan review: all three findings accepted. Parent adds owner-scoped `WearablesViewModel.startStream(owner, permissionCallback)`, `stopStream(owner)`, and cancellable fresh `capturePhoto(owner)`; Bluetooth and DAT CAMERA checked before acquisition. Camera/translation screens use stable tokens, cancel permission/capture work on exit. Protocol READY requires acknowledged configuration and successful microphone start, with a bounded deadline and terminal error cleanup.
- Task 1 speech/playback: implemented; exact HTTP/SSE, bounded PCM/system backend, cancellation before fallback. Parent integrated Quick Vision service/screen and terminal speech completion. HTTP cancellation failures were reproduced and corrected; reports retain RED/GREEN evidence.
- Task 2 language/voice models and isolated preferences: implemented and tested, including platform persistence and incompatible voice/language repair.
- Task 3 translation protocol/fixtures: implemented; current ACK and actual source-start gates, bounded media/text queues, response identity and immediate cleanup. Fixture request mutation was corrected after its observed test failure.
- Task 4 translation UI/settings/permission lifecycle: implemented. Four integration findings fixed and independently re-reviewed: owner/generation image fence after encode, route-loss and actual microphone-start gating, foreign SCO protection with unchanged defaults for legacy callers, and authoritative text snapshot before close. Added 15 regressions passed.
- Task 5 camera hub/preview/timer: implemented. Parent provided address-specific one-shot RAM handoff and direct analysis route ownership. Fresh owned capture and canceled permission/capture paths are covered. API31 mock preview, actual one-minute expiry/restart, photo, both handoffs, retained preview and share chooser passed.
- Task 6 integration/locale: implemented. On the existing E APK, saved Chinese still cold-started in English; restoring before `AppCompatActivity.attachBaseContext` was verified on the new D APK with confirmed persisted zh-CN. UI automation's transient null root during Activity/picker transitions is not reported as an ANR. Parent also fixes the explicit Phase B `onAuthErrorRtmp` carry-over, with production callback tests; that last scoped verification is recorded separately below.
- Fresh integrated checkpoint: **509 debug / 497 release JVM passes**, both APKs and Android-test APK built, **56 instrumented passes +1 explicitly ignored SDK stress test** on API31. Targeted speech/preferences had previously passed11/11. These counts are snapshots, not added totals. See `task-7-report.md` for exact APK/test timing and hardware limits.

Integration review corrections: terminal error speech awaits completion before dwell; nutrition observes late Display availability throughout its visible lifetime; photos are delivered only to the addressed destination and consumed once. Three new handoff tests and seven owner/VM tests passed. Final privacy inspection removed legacy Quick Vision result/prompt/error-body logs; provider HTTP failures expose only status codes.

No authored-but-unrun test is called a pass, and no test is claimed RED merely because its source was written first. Physical/cloud acceptance remains in `hardware-checklist.md`.

No credential files read, no live cloud requests sent, no iOS edits, no main merge/release or external issue posted.

## Software closeout

All seven Phase D software tasks are complete. The RTMP carry-over is fixed through the actual production callback seam, with 13 additional regressions and a scoped independent pinned-library/lock-order review. The final compile's private getter collision was corrected without changing the public API. **Final: 522 debug /510 release JVM passes, both APK builds, final installed-APK instrumented56 passes with1 retained SDK-stress ignore.** Final source/log/privacy/resource checks are documented in `task-7-report.md` and linked transcripts. No further source edits followed those final runs.

The branch remains `android-v2`; physical/cloud acceptance and the known SDK stress defect remain open. `docs/ANDROID_2_PROGRESS.md` links the C/E/D reports and the next hardware steps. Original untracked tooling configuration and handoff files remain preserved separately from this work.
