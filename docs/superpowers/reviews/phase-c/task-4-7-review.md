# Phase C Task 4/7 final sidecar review

**Result: two findings remain — one callback correctness issue and one required-test gap.** Reviewed the live `android-v2` workspace against S5/S6/S8/S11, Tasks 4/7, and the cross-task invariants. This was source review only: no Gradle, compilation, tests, emulator work, or source edits. This report is the only file written by this review. `local.properties` was not read. Task 5 service behavior and emulator verification remain parent-owned.

## Findings

### 1. [P2] Invalidate callbacks from content that no longer owns the display

Locations: [GlassesDisplayManager.kt:207](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayManager.kt:207), [GlassesDisplayManager.kt:123](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayManager.kt:123), [GlassesActionRouter.kt:76](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/GlassesActionRouter.kt:76).

`dispatchFromSdk` correctly posts to Main, but forwards the action without checking the originating display or content lifetime. The Page action carries the old card, and the current router calls `showPage(card)`, which unconditionally overwrites `currentCard`. Preserving the content owner during paging does not validate that the page belongs to the content that still owns the display.

A deterministic source-level reproduction is: render feature A; queue its `Page(A.copy(page = 1))` callback onto a paused Main test dispatcher; call `showStatus()` as A exits, or show feature B; then drain Main. The queued callback reaches the real router and restores A. If display is disabled in the intervening window, the old card can instead become the card resent on the next STARTED. A queued old BackToMenu callback can similarly dismiss newer content. This requires no assumption that an SDK callback is issued after its listener is removed: the callback can already be waiting in our own dispatcher queue.

This crosses Task 3's callback boundary and Task 4's action handling, undermining I4's no-stale-feature guarantee. The new owner-aware cleanup changes seen during review address delayed cleanup, but do not close this callback path.

**Recommended fix:** bind production SDK callbacks to the originating display/content lifetime and validate that origin on Main before invoking the router. Invalidate obsolete actions when the feature/menu/content ownership changes or the display detaches. Keep ordinary current-card paging functional. Add a real manager + router regression that queues the callback, changes the active content or disables display, and then drains the dispatcher; assert the newer card/menu remains. The existing `dispatchFromSdkHopsToMainAndCallsTheHandler` tests thread transfer only, and `pageShowsTheCarriedCard` tests unconditional forwarding, so neither catches this race. The reproduction was not executed in this read-only review.

### 2. [P2, verification gap] The display preference test does not cover the approved default/fresh-instance contract

Location: [APIKeyManagerInstrumentedTest.kt:73](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt:73).

Task 7 requires default `true` and persistence through a fresh `APIKeyManager(context)`. `glassesDisplayEnabledPersists` writes false/true and reads through `getInstance(context)`, which returns the already-used singleton. It never exercises an absent preference and never constructs a new manager. The test would still pass if the default changed to false or the implementation only retained the value on the existing manager.

The production getter currently uses the intended `true` default and the setter writes SharedPreferences; this is a test-evidence gap, not a demonstrated production failure. Before claiming the Task 7 verification contract is complete, add an isolated absent-key/default case and a fresh-manager read after the write. Preserve the pre-test preference state, including absence if the fixture uses the real application store. The present save/restore logic is preferable to leaving the user's flag changed.

## Lifecycle, callback, and settings checks with no additional finding

- **Activity navigation:** `LocalLifecycleOwner` is obtained outside NavHost, so it is the Activity owner. `repeatOnLifecycle(STARTED)` cancels collection on STOP. The process flow has replay zero and a bounded extra buffer; the router drops navigation without subscribers and logs buffer overflow. All three routes use `launchSingleTop`. No Activity foregrounding was introduced. STARTED rather than RESUMED matches the approved limitation.
- **Background Quick Vision start:** the router catches synchronous `ForegroundServiceStartNotAllowedException` and `SecurityException`, with tests for both and a subsequent action. It neither navigates nor retries a rejected start. Errors after Android has accepted and dispatched the service start are not claimed covered here; the parent owns the service review.
- **Controller identity:** register replaces, unregister compares `===`, and real-router tests cover both Live AI and OpenClaw replacement/identity behavior. The Task 6 follow-up tests also compose the real router with retained OpenClaw ViewModels for leave/re-entry and older-instance cleanup.
- **Startup/permissions:** Application initializes DAT before installing OpenClaw/display integration. Display installation and `ensureStarted` check Bluetooth permission; MainActivity calls the latter after restarting wearable monitoring in the grant path. The process wiring installs one action handler and one OpenClaw-state collector. Router/integration/settings do not acquire a session.
- **Status refresh:** `AppStatusProvider` reads the current name and readiness flags lazily. Integration observes the connected boolean on Main and refreshes status only when no feature card is active and display is STARTED. Intermediate OpenClaw states are not marked connected. No new status overwrite path was found in that observer.
- **Settings:** the toggle persists, applies the setting to the shared manager, and updates its StateFlow. Disable detaches the display without stopping the session/camera; enable only attaches to an already STARTED session. The unsupported subtitle is limited to an explicitly non-display-capable active device.
- **Home/preview:** Home's capability gate and state-to-label mapping match S8. The debug preview entry, callback, and destination are availability-gated; the release entry reports unavailable. No Phase D streaming behavior was added by these paths.
- **Strings:** both locale XML files parsed successfully with 474 unique keys each, no duplicate keys, and no key-set differences. The twenty S11 strings inspected match the plan. The count exceeds the plan's historical baseline because other approved additions are present; parity holds.

## Test coverage and review limits

At the last read there were 21 router tests, 5 status-provider tests, and 13 WearablesViewModel tests. The three previously missing Task 7 cases arrived during this review and were inspected: `displayStateFollowsTheManager`, `isDisplayCapableFollowsTheActiveDevice`, and `isDisplayAvailableIsFalseWhenTheSettingIsOff`. They use the real manager with fakes, include false/true/null capability transitions, and verify that disabled display never attaches. **They are no longer a missing-test finding.**

The router's real `LifecycleRegistry`/`repeatOnLifecycle` test covers stopped subscriptions and restart without replay. It recreates the collection pattern rather than rendering `TurboMetaNavigation`; it therefore does not independently prove the composable's wiring. The manager callback test verifies the hop to the test's Main dispatcher, not obsolete-callback rejection. Integration's singleton installation and Settings/Home rendering remain source-reviewed, with runtime checks left to the parent's existing verification work.

No build or test-pass claim is made. No unrelated service/emulator changes were requested or made.

## Snapshot anchors

The shared workspace changed during review. These SHA-256 prefixes identify the last inspected versions relevant to the findings:

| File | SHA-256 prefix |
|---|---|
| `GlassesActionRouter.kt` | `DE0B3B4BCB99` |
| `GlassesDisplayManager.kt` | `4ED22F3D7B30` |
| `Navigation.kt` | `8C637B805821` |
| `WearablesViewModelTest.kt` | `ADF677C79567` |
| `APIKeyManagerInstrumentedTest.kt` | `2EC7AA5638F2` |

## Follow-up ruling — callback origin guard and isolated preference test

**The original production callback finding is closed by source inspection. The requested default/fresh-manager assertions are now present. Two test-fixture issues remain before an unconditional verification sign-off.** This ruling supersedes the original finding status above. No Gradle, tests, or emulator commands were run during this follow-up; only this report was changed.

### Accepted changes

- [GlassesDisplayManager.kt:163](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/GlassesDisplayManager.kt:163) now creates `actionCallback(display, request.seq)` when building the send and supplies it to `render`. After the Main hop, the callback checks that the originating display is still the current STARTED display and its request sequence is still current before invoking the handler. Replacement, status, clear, and new sends invalidate the old sequence; disabling/detaching invalidates the display check. This closes the queued old Page/Done path identified in finding 1. The unguarded `dispatchFromSdk` helper remains, but a source-wide caller search found no production call site using it; its callers are tests.
- [APIKeyManagerInstrumentedTest.kt:73](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt:73) uses a UUID-prefixed `ContextWrapper`, preserves that wrapper through `applicationContext`, and redirects preference opens to fixture names. It asserts the initial true value, writes false/true, and verifies each through a newly constructed `APIKeyManager(isolated)`. The method's `finally` deletes only recorded prefixed fixture files. The display-default/fresh-instance assertion gap from finding 2 is addressed, and the method itself no longer writes the user's display preference.
- The three Task 7 WearablesViewModel tests remain present. Their arrival was already reflected in the initial review's final coverage section.

### Remaining fixture corrections

1. **[P2] Initialize the fake session before accessing its display in the new callback regression.** [GlassesDisplayManagerTest.kt:54](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/test/java/com/smartview/glassai/glasses/GlassesDisplayManagerTest.kt:54) uses default `runTest` and constructs its session manager with `testScope.backgroundScope`. The device/session collectors are therefore queued. It calls `f.start()` before the first `runCurrent()`, although `f.start()` reads `factory.last.display`, whose fake getter is `displays.last()`. The queued collectors have not attached a display yet, so source inspection identifies an empty-list access before either old-Page or old-Done assertion. Advance the scheduler immediately after fixture construction and before `f.start()`, or use an unconfined outer test dispatcher while retaining the explicit Standard dispatcher for queued action handling. This is a source-derived failure, not an executed test result. The subsequent regression steps correctly target replacement and detach invalidation once setup is repaired.

2. **[P2, test isolation] The isolated method still inherits real-preference setup/teardown.** [APIKeyManagerInstrumentedTest.kt:33](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt:33) and [APIKeyManagerInstrumentedTest.kt:45](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/androidTest/java/com/smartview/glassai/utils/APIKeyManagerInstrumentedTest.kt:45) run for this test too. They open the actual singleton/store and write actual RTMP/OpenClaw settings afterward. In particular, when `savedSeed` is null, teardown generates and persists a real identity seed. Thus “the display test body is isolated” is supported, but “running this test cannot mutate actual preferences” is not yet supported. These shared hooks predate this revision; the new method did not introduce their behavior. Place the isolated case in a class without those real-store hooks, or bypass those hooks for this isolated case, to meet the stated no-actual-preference-mutation guarantee.

No additional production issue was found in the two requested changes. Runtime verification remains parent-owned after those fixture corrections.

Follow-up SHA-256 prefixes: `GlassesDisplayManager.kt` = `4DC2568136EA`; `GlassesDisplayManagerTest.kt` = `52265E6DEF54`; `APIKeyManagerInstrumentedTest.kt` = `D7247DA50097`.

### Final source closure

Both fixture concerns are closed in the current source:

- `GlassesDisplayManagerTest.queuedButtonsFromReplacedOrDetachedContentCannotRestoreOldCards` calls `runCurrent()` before `f.start()`. The earlier fixture-order concern is superseded.
- The isolated display preference case now lives in `DisplayPreferenceInstrumentedTest.kt`, with no actual-preference setup or teardown. It verifies the absent-key default and fresh-manager reads after false/true writes using UUID-prefixed preference files, and deletes only those fixture files. The moved case is absent from the historical `APIKeyManagerInstrumentedTest` class.

Together with the previously accepted callback-origin guard, this closes the review findings and fixture follow-ups at source level. This was a narrow confirmation of the two corrections, with no broader review or code edits. Tests were not run by this reviewer; runtime verification remains parent-owned.
