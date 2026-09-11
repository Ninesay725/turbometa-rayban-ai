# Phase D verification — Codex continuation, 2026-09-11

## Scope and evidence boundary

Android only, based on `33e261e` on `android-v2`. Phase A/B results are historical. Phase C/E and the results below were executed in this Codex continuation. The original handoff and uncommitted configuration files were preserved. No credential file was opened, no live cloud request was sent, and no iOS code was changed.

Implemented the camera hub and fresh photo handoff, shared cloud/system speech, real-time translation settings/protocol/UI, and API31 locale restoration. See the phase plan, task reports and two integration reviews for the interfaces and corrected findings. The exact approved Qwen3 model IDs remain in use; fixture compatibility is not live account/model acceptance.

## Final build and JVM results

- After the RTMP carry-over and privacy fixes: **522 debug / 510 release JVM tests**, zero failures/errors/skips; both APKs and Android-test APK built successfully in1m4s. [Final build](build-closeout.txt), [final totals](unit-summary-final.json), [universal debug APK SHA256](apk-sha256.txt).
- The 13 added tests exercise the real production RTMP callback implementation: authentication/connection failure tears down and permits retry, old callbacks cannot affect a successor, Error survives cleanup, and user Stop cannot be overwritten by a waiting callback. Codec/network timing remains hardware-pending. [Implementation and independent review](task-6-rtmp-followup.md).
- The first RTMP integration compile caught a private Boolean getter/public-method JVM name collision. Renaming only the private property preserved the public API; the final build above passes. [Failure transcript](rtmp-getter-compile-failure.txt).
- Final privacy review closed legacy result/prompt/error-body logging in the Quick Vision call chain. New speech/translation cancellation behavior remains covered; [review](privacy-review.md).
- Reinstalled these exact final APKs and reran the full API31 suite: **56 passes +1 explicit ignored SDK stress test**, `OK (56 tests)`,35.665s. [Final device transcript](full-instrumentation-final.txt). Temporary notification access/preferences were restored by the runner. This supersedes the preceding device snapshot for final-source verification.

## Earlier integration checkpoints

- Parent executed both variant unit suites, both app APK builds and `assembleDebugAndroidTest` after the final source fixes. **BUILD SUCCESSFUL**, 1m18s; [build transcript](build-verification.txt).
- **509 debug / 497 release tests**, zero failures/errors/skips; [machine-readable totals](unit-summary.json). Debug-only Display/Mock coverage explains the variant difference. No exact test-count requirement was used to limit regression coverage.
- Both locales have **575 unique string keys**, identical sets and no duplicate names. `git diff --check` passed.
- Earlier actual failures are preserved honestly: one retired-permission owner regression ([RED transcript](owner-regression-red.txt)), two HTTP cancellation tests exposing an IOException/cancellation race, and one translation fixture that mutated its expected request when creating its ACK. Their corrections are documented in the owner, speech and protocol reports. Tests merely authored before implementation are not claimed as observed RED runs.
- A prior D snapshot passed 491/479; that result is superseded for the final source by 509/497, not added to it.

## Device results

Pixel_5 API31 x86_64 emulator only. No physical phone or glasses was attached.

- Initial targeted run on the preceding D APK: **11/11 passed**, no skips — four real Android AudioTrack/system-TTS boundary cases and seven isolated translation-preference cases. [Transcript](speech-preferences-instrumentation.txt). This proves completion/stop/reuse behavior on the emulator, not human audibility or glasses routing.
- Full instrumentation on the 509/497 source snapshot: **56 successful test events, one explicit ignored SDK stress case**, `OK (56 tests)`, 29.384s. [Transcript](full-instrumentation.txt). Includes the new owned WearablesViewModel → real DAT/MockDeviceKit fresh-photo path, FileProvider read-only JPEG and non-recycled preview cases. The runner restored its exact prior notification preference/grant state. RTMP's separately recorded final carry-over fix is not covered by this snapshot.
- Manual camera smoke on the preceding D APK: configured the repository plant video/photo in MockDeviceKit; live preview, selected one-minute countdown, actual expiry/stop, explicit restart, fresh shutter, retained preview, Vision handoff/back, nutrition handoff/back and Android share chooser all passed. No analysis request or share recipient was selected. The first attempt correctly failed because the manual setup had folded the mock glasses; logs established this and unfolding/wearing allowed the same flow to pass. This was test setup, not a camera-code fix. Five/ten/fifteen-minute deadline behavior has JVM coverage, not a real-time emulator wait.
- Translation: opening the page/settings did not start a translation; Start with the region's key visibly unconfigured produced the localized missing-key guidance and stopped state. Source/target, voice, spoken-output and optional-image controls rendered. Actual cloud/microphone routing was not exercised by this UI check.
- Locale: after confirming the non-secret saved `app_language=zh-CN`, a cold start displayed Chinese Home and navigation. An initial immediate force-stop happened before selection persistence and still had `en`; it is not a failed cold-start regression. UI automation briefly returned a null root during Activity/picker transitions; a later fresh dump or cold start succeeded. No stale dump was accepted as evidence, and no ANR is inferred from a null root.

Screenshots: [live camera](camera-live.png), [timer expired](camera-timer-expired.png), [captured preview](camera-photo-preview.png), [Vision](camera-vision-handoff.png), [nutrition](camera-nutrition-handoff.png), [share chooser](camera-share-chooser.png), [missing key](translation-missing-key.png), [Chinese cold start](language-after-cold-start.png). These use only the repository's plant fixtures, not personal photos.

## Remaining release acceptance

All real lens, WeChat, NetEase/汽水, microphone/SCO and Alibaba account checks remain [pending](hardware-checklist.md). The retained ignored DAT0.9 rapid session-restart stress case still exposes the SDK/MockDeviceKit internal cleanup hang recorded in Phase C. Ordinary camera/session tests do not close that issue. No speculative production delay or internal SDK workaround was added.

No main merge, public release or upstream issue posting is claimed. The development APK and committed branch are for the next hardware acceptance pass.
