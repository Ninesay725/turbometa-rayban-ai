# Phase E verification and closeout — 2026-09-11

Implemented on `android-v2` above C commit `f0302ac`. No iOS edits, main merge, release or external issue posting. The app uses only Android's public notification/media APIs; both feature switches default off. Real third-party apps and Display hardware remain pending.

## Current executed results

| Check | This run |
| --- | --- |
| Debug JVM suite | 388 tests, 0 failures/errors/skips |
| Release JVM suite | 376 tests, 0 failures/errors/skips |
| Debug and release APKs | Both assembled successfully |
| Android test APK | Assembled successfully |
| Focused API31 bridge/image fixtures | 19/19 passed; includes a real test-owned MediaSession and pause/previous/next callbacks |
| Complete API31 instrumented suite | 42 passed, one separate known SDK stress test ignored (43 declared) |
| Strings | 514 names in each locale, no duplicates |
| Whitespace | `git diff --check` passed |

Build transcript: `/tmp/codex-e-final.log` (1m06s, before the test-only teardown change); the final test APK was rebuilt in `/tmp/codex-e-fixture.log`. Source review closure is in `task-2-review.md`. Device runner transcripts are `bridge-instrumentation.txt` and `full-instrumentation.txt`; these are fresh executions, not Phase A/B/C evidence.

The first lease test run failed compilation because the implementation did not exist. The two direct private-cache regressions then failed by assertion before their fix. The fallback-origin variants were added with the final fix and passed in the suite; no separate red claim is made for those. During integration a wrong DAT import and an SDK-internal constructor in a test fixture were corrected. The fixture now tests the decoder without accessing SDK internals.

The first instrumentation attempt rejected one Kotlin expression-bodied test for not returning JVM void; 16 unrelated parser/image tests passed and the class initialization failed. The explicit Unit signature fixed it. That failure transcript is retained separately; it is not counted as a pass.

## Correctness fixes verified

- A posted WeChat update first removes all older entries under its notification key, including when the new details are empty/hidden or summary-only. The service filters package and preference before reading the bundle. Inbox contents are bounded to three and never persisted/uploaded/logged.
- Done, supersession, removal/disable/disconnect and the ten-second dwell retire a camera-free temporary claim. Cleanup cannot clear another feature's card. No notification starts a new DAT session or is replayed into a future session.
- The display sender forgets accepted private snapshots on replacement/cleanup. A private-origin bit survives conversion to fallback Notice, and an old non-cancellable send cannot repopulate private history. Existing nonprivate send timing is preserved.
- Music selection prefers playing allowed sessions and reads fresh playback states at command time. Control support is checked again on the selected controller. Retired controller callbacks and old artwork jobs are fenced. Identical embedded bitmap identity reuses its JPEG; no art URI is fetched.
- Display loss retires the music-owned card so a stale track is not replayed on reattachment. The latest phone-side track remains available. Page START/STOP retains/releases only its session claim, never a camera claim.

## Emulator UI and fixture hygiene

Actually inspected: Home entry opens the new page; cold restored defaults show both switches off, notification access not allowed and listener disconnected; the access button opens Android's explicit notification settings with the master grant off; returning to the app works; the lower page shows the two default music packages and disabled/empty guidance. `page-default.png`, `page-packages.png` and UI XML files record those observations. Full physical music/notification interaction is not inferred from these screenshots.

An initial media fixture left its temporary music switch/package in emulator preferences despite its in-process finally block. The test now commits restoration before exit; the runner also backs up only the new non-secret `notification_bridge.xml`, force-stops the test process before restoration, handles absence, removes only its own backup, and verifies restored bytes. It never reads credential preferences or local.properties. Temporary notification access is restored in finally. The fixture-created bridge preference was reset on this emulator; the next 19-test run passed and confirmed the file absent afterwards. The subsequent cold UI had both switches off and the expected default packages.

The existing app-language defect on API31 remains assigned to D; this UI pass is English. Rotation, actual-lens spacing and simultaneous third-party audio/camera behavior remain hardware checks. The C rapid-session-restart SDK lock hang stays explicitly excluded and unresolved. See `hardware-checklist.md`; passing normal cases does not remove that release risk.

## Next

Phase E software is ready as the foundation for D. Continue with camera hub, cloud TTS and live translation under approved §9; retain hardware and SDK risks, and keep `android-v2` separate from main.
