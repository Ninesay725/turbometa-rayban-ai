# Phase E plan review

**Changes requested before app implementation: two contract gaps.** Reviewed the Phase E plan against approved design §3/§8 and the committed C interfaces. This is a plan review, not a finding against unreviewed E code.

## P1 — Unavailable updates must invalidate previously readable previews

**Plan locations:** lines 15, 30–34, 53 and 60–62.

The plan clears content on removal/disable/disconnection, but does not define what happens when an existing notification key is updated from readable details to empty/redacted content or a summary. `parse(sbn)` can return an empty list, which loses the key at the pure inbox boundary; a merge/dedupe `update(emptyList())` can retain that key's previous private text. Adding a summary to the existing merged inbox can likewise leave old details beside it. The phone's `messages` flow or a later merged glasses card could then reveal details after that notification stopped supplying them, contrary to §8's unavailable/summary-only behavior.

**Required plan correction:** make the service authoritative for same-key invalidation even when parsing returns no messages. On an unavailable/summary-only replacement, remove prior detailed entries for that key before publishing the allowed replacement, and update/retire its displayed content without clearing another feature's card. State explicitly whether ordinary readable updates merge or replace; an additional public interface is unnecessary if the service uses `remove(sbn.key)` where required.

**Focused acceptance:** readable key K → empty/redacted K, and readable K → summary-only K, leave no prior K details in runtime messages or subsequent merged cards. Preserve unrelated keys within the three-message bound; later readable K can appear normally.

## P2 — Done lacks a temporary-session retirement contract

**Plan locations:** lines 17, 45, 48 and 60–62; approved design §3 decision 1 and §8 Done behavior.

The listed retirement triggers cover timer/replacement/disable/disconnect, but omit the user leaving the notification card. Reusing Done currently means `BackToMenu → sink.showStatus()` (`GlassesActionRouter.kt:77`); neither that action nor `MusicController` tells the bridge to release its temporary session claim. If the original feature leaves during the notification dwell, then the user presses Done, following the stated wiring keeps the session alive for the remaining timer despite leaving the last feature. Owner-safe card cleanup alone does not release a `GlassesSessionManager` owner.

**Required plan correction:** define a bridge-owned dismissal/supersession hook, or observe the existing current-card flow, to cancel the dwell and release its claim exactly once on Done or replacement by another feature. Paging the same notification must preserve the claim. This needs no C sender redesign or new navigation action.

**Focused acceptance:** after the original session owner leaves, Done releases the notification's final claim immediately; with another owner present, only the notification claim is released. A retired timer cannot release a newer dwell or clear its card.

## Scope and verification

The default-off consent model, no incoming-notification session creation, camera-free music owner, START/STOP page handling, editable media allowlist and real-device verification limits are consistent with the approved scope. The listener connection gate and enabled-listener access to media controllers match the public [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) and [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)) contracts. No further blocking platform/API issue found in this bounded review.

No app/plan edits, tests, Gradle, emulator actions or commits performed; only this report was written. The parent reports C commit `f0302ac`, 355 debug / 344 release unit passes and 23 instrumentation passes with one SDK-hang exclusion; these are baseline evidence, not Phase E validation. The requested DAT reference URL was fetched and supplied no usable API text; the plan correctly reserves image-overload verification against pinned 0.9.0 artifacts.
