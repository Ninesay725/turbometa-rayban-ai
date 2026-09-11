# Phase C progress

Plan: `docs/superpowers/plans/2026-09-10-android-v2-phase-c-display.md`

## 2026-09-11 Codex continuation

- Verified branch `android-v2`, base `25e6ae4`. Phase A/B fixes are already in the tree. Their 154 JVM tests per variant and 21 instrumented tests are historical evidence until rerun here.
- Preserved the untracked Phase C plan, handoff, `.agents/`, `.codex/`, and `AGENTS.md`.
- Android DAT 0.9.0 coding conventions, display-access and session-lifecycle skills are available and read. The configured Wearables MCP `search_dat_docs` was actually called for first Android DAT app setup and returned relevant prerequisites, Meta AI installation, version-dependency checks, Developer Mode, installing DAT on Display glasses, and Mock Device Kit testing guidance. Tool preconditions for application edits are satisfied.
- Fetching the static `llms.txt?full=true` through the web tool returned `Not Logged In`; do not claim a fresh full export. Use the functioning MCP and installed 0.9.0 artifacts for exact APIs.
- Ruling: work in the existing user-selected `android-v2` checkout. Keep approved S1–S11 contracts and task scopes; implement independent disjoint files concurrently, with one coordinated Gradle runner. Do not recreate completed A/B tasks or regenerate the already-reviewed plan.
- Ruling: preserve research and progress artifacts; archive reports directly in this directory. Hardware-only acceptance remains pending until performed on actual Display glasses.
- Baseline attempt 1: wrapper could not create `C:\.gradle` under the sandbox; no tests ran. Retry uses the existing `C:/Users/Lee_L/.gradle` cache with approved build access.

## Tasks

- Task 1: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 2: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 3: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 4: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 5: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 6: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 7: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 8: implemented, reviewed, verified in final 355 debug / 344 release JVM suite and both builds.
- Task 9: software checks complete — 23 instrumented PASS, 1 known SDK stress case excluded after two hangs; physical H1–H13 pending. See task-9-report.md.

## Current-run verification and rulings

- First integrated green run: Gradle `testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --offline`, BUILD SUCCESSFUL (96 s); XML totals 320 debug and 309 release, zero failures/errors/skips. These are **this Codex run**, not the historical A/B counts.
- The next targeted regression intentionally failed: `retiringAnOldFeatureCannotClearTheNewFeaturesCard` expected the newer card but old-feature cleanup cleared it. Owner-scoped sinks now protect cleanup; routing pages preserves content ownership. A separate origin-bound callback fence prevents queued old buttons restoring dismissed content.
- Task 5 review identified Again during result dwell, late Live AI callbacks, synchronous TTS rejection, and unbounded multiline content. Fixes are being implemented, with tests; the first integrated green is not a final assertion for those changes.
- Emulator Pixel_5 API 31 booted with the known working windowed/default GPU command. The first swiftshader/headless-style attempt failed in Vulkan; no device validation claimed for it.
- First instrumented run: five tests finished successfully; test 6 (session-only immediate stop/restart) never completed. After the second STARTING timed out, teardown blocked in DAT `DeviceHealthManager.removeListener` / `DeviceSession.stop`. Thread 50 holds the health lock while waiting on `SessionChannel`; thread 19 is in native channel allocation. Evidence: `android/build/codex-verification/phase-c-hang-trace.txt`, `phase-c-session-log.txt`, `phase-c-instrumented.txt`. ADB daemon restart interrupted the runner; its shell exit zero is NOT a suite pass. Process was force-stopped after capture.
- The settled-session integration test now explicitly waits 2 seconds after each STARTED, matching the already-documented SDK settling convention. This narrows its claim to switching settled sessions; it does NOT fix or hide the separate failed rapid-start/stop stress case, which remains a hardware/release risk to assess.
- No production stop-delay workaround added without evidence of a reliable SDK recovery contract. Physical Display rendering and stop/sleep/gesture behavior remain pending H1–H13.
- `BLUETOOTH_SCAN` was attempted as a test grant but is not declared; Android rejected it. Required CONNECT/CAMERA/RECORD_AUDIO grants succeeded; no manifest change made for an unnecessary permission.
- Preserve the reviewed Phase C plan as historical design. Record implementation corrections here instead of silently rewriting prior review decisions. No iOS changes, no app credentials read, no upstream issue published.

Final evidence and unresolved SDK/hardware boundaries: task-9-report.md. Phase E is next. No main merge or release authorization inferred.
