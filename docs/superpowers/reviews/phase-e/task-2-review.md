# Phase E Task 2 — actionable review findings

## 1. P1 — Cleanup leaves private WeChat content in `lastSent`

**Locations:** `glasses/GlassesDisplayManager.kt:129–140,175–179`; bridge cleanup calls at `bridge/NotificationBridgeRuntime.kt:61,91` and `bridge/NotificationDisplayLease.kt:33–35`.

After a WeChat card is accepted, disabling previews clears the inbox and current card but leaves the complete private card in the process-wide `lastSent` StateFlow. Notification removal and display retirement also leave this cache intact. If the display is unavailable, no replacement Status send can overwrite it, so the data persists indefinitely. Same-key redaction also leaves the previously accepted readable details cached until another send succeeds. `lastSent` is not itself the sender's replay source; the confirmed problem here is retained private content.

**Minimal correction:** invalidate `lastSent` when content is replaced or cleared through the existing manager methods; no new bridge API is needed. Also fence the successful-send assignment by the current request sequence. Resetting the field alone is insufficient: a superseded non-cancellable send on the same attached display can finish after cleanup and put the private card back into it. Preserve the existing attached-display identity check and actual-send timing bookkeeping.

**Acceptance:** accept a readable WeChat card, detach the display, then disable/remove/redact it; neither currentCard nor lastSent retains the old details. Separately delay an old successful send, clear/replace its content, then complete it; it must not repopulate lastSent.

## 2. P2 — An old music card remains eligible for replay after display loss

**Locations:** `bridge/NotificationBridgeRuntime.kt:66–69,114–128`; existing replay behavior at `glasses/GlassesDisplayManager.kt:91–95`.

Display loss retires only the notification lease. If music A was showing, its card stays in the display manager. A metadata update to B while the display is unavailable updates `track`, but `showMusicIfAppropriate()` returns before replacing A. On the next STARTED, the manager can enqueue and begin sending A before the runtime's separate collector publishes B. The serial SDK send is non-cancellable, so the later update does not prevent that obsolete card from reaching the new display.

**Correction:** retire the music-owned display card when readiness is lost, using the existing owner-scoped sink so another feature's card is preserved. Retain the latest phone-side music snapshot and publish that snapshot when the display becomes ready. Ensure unavailable-display updates cannot leave an obsolete music card queued for a future STARTED.

**Acceptance:** show A, detach, publish B, then attach/start a new display; its accepted cards must contain no A. Repeat with another feature owning the current card and verify the bridge does not clear it.

## 3. P2 — Controls refresh actions but retain a stale selected session

**Location:** `services/NotificationBridgeService.kt:169–175` (`control`); selection is assigned separately in `refreshMedia()`.

The action path checks the cached controller's current actions and allowlist, but never revalidates selection against the other sessions' current playback states. For example, A is selected while both sessions are paused; B starts playing, and a tap runs before B's queued callback updates selection. Play/pause then starts A instead of pausing the now-playing B. Reading A's latest actions does not resolve the wrong target.

**Correction:** resolve the selected token from fresh playback states of the currently observed allowed controllers at command time, then validate the chosen controller's current actions before dispatch. Keep this selection check separate from display/artwork publication so a control tap does not trigger encoding or unnecessary card sends.

**Acceptance:** select A, change B's readable playback state to PLAYING without delivering its callback, then invoke a control. Only B receives the supported command. Cover a removed/disallowed target and a latest action mask that no longer supports the command.

## Follow-up review — 2026-09-11

**Finding 1 remains partially open: WeChat fallback Notices bypass the privacy checks.** The four content mutation methods now forget cached `WeChat` cards, and superseded successful WeChat sends cannot restore them. Preserving nonprivate accepted-send history and LiveAI timing is appropriate. The two new manager regressions cover direct WeChat cleanup while detached and a superseded direct WeChat completion.

However, `DisplayCard.kt:74,87,92–94` converts a WeChat card into a `Notice` carrying its sender and preview when rendering fails. `GlassesDisplayManager.kt:198` queues that Notice without retaining its private origin. Both the cache-reset check at line 153 and the success fence at lines 187–188 classify it as nonprivate. Consequently, an accepted fallback retains notification details after detached cleanup, and an in-flight fallback can repopulate those details after clear/redaction.

**Remaining minimal correction:** preserve private origin internally on the send request, including its fallback, and alongside the accepted snapshot; use that flag for cache invalidation and stale-success fencing. This needs no public API or card-model change and can preserve all genuinely nonprivate history/timing behavior. Extend the two regressions with `WeChat → RENDERING_FAILED → Notice`: first accept the fallback then detach/clean up; separately clear while its success is delayed. Neither case may retain private text in lastSent.

**Finding 2 closed by source review; execution pending.** `NotificationBridgeRuntime.kt:67–71` now retires the music-owned card whenever display readiness is lost. The existing owned sink makes this conditional on content ownership. The phone-side latest track remains available, and the ready path publishes it instead of keeping the old music card for the sender's next STARTED.

**Finding 3 closed by source review; execution pending.** The service's actual command path now calls `resolveMediaTarget` with fresh playback states and the current allowlist, then reads the chosen controller's latest state/actions before dispatch. It does not call artwork/display refresh from the command path. `MediaControlTargetTest.readsCurrentPlaybackWithoutWaitingForACallback` exercises the relevant mutable-fake selection change, allowlist change, and empty candidate set. Existing action-mask assertions remain relevant.

The additional artwork cache reuses accepted JPEG bytes for identical bitmap identity and is cleared by stopMedia; controller callbacks now verify their registration identity before acting. No further actionable issue identified in those bounded changes.

Only this report was edited. No Gradle, tests, adb, or production edits were performed in this follow-up. The parent reports that both new direct-WeChat regressions reached assertion RED before the fix; that execution was not independently rerun here. Parent integration/fixture results remain pending and are not implied by source-review closure.

## Final bounded closure — 2026-09-11

**Finding 1, including fallback privacy, is closed by source review; execution remains pending.** `SendRequest.Card.privateContent` now records WeChat origin (`GlassesDisplayManager.kt:56–58`), and fallback enqueue explicitly inherits that flag (line 201). Successful private requests must still match the pending sequence before populating the accepted snapshot (lines 189–192). `lastSentPrivate` preserves that classification after conversion to Notice, so all four content mutation methods can invalidate the private snapshot through `forgetPrivateSnapshot` (lines 153–155). The attached-display identity guard, nonprivate accepted-send history, and LiveAI send timing remain intact.

Inspected `privateFallbackIsForgottenOnDetachedCleanup` and `privateFallbackFinishingAfterClearCannotRestoreDetails` (`GlassesDisplayManagerTest.kt:105–124`). They cover an accepted fallback followed by detached cleanup and a queued fallback completing after clear, respectively, with assertions that lastSent contains no retained private card.

All three findings in this report are now closed at source-review level. No further actionable issue found in this bounded reread. Only this closure was appended; no production edits, tests, Gradle, or adb were performed. Test execution and integrated validation remain with the parent.
