# Phase E — WeChat notifications and music implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development with disjoint file scopes and one parent Gradle/adb runner. The approved design is already authorized; routine implementation choices need no renewed user approval.

**Goal:** Read opt-in WeChat notification previews on Display glasses and control NetEase Cloud Music / Qishui through Android's public media sessions.
**Architecture:** NotificationBridgeService is the only reader of notifications/media controllers. Pure models select/deduplicate content; a process runtime exposes state and delegates controls. Display cards reuse the C sender/session and owner-scoped cleanup. A phone page configures access and temporarily holds a session for music.
**Tech stack:** Existing Kotlin/Compose/coroutines/Android API31+ and DAT0.9.0; no new dependencies.
**Spec:** docs/superpowers/specs/2026-09-10-android-v2-design.md §3/§8/§10.
**Base:** f0302ac, android-v2. Phase C software built/tested, physical H1–H13 and SDK rapid-restart hang remain open.

## Global constraints and clarified defaults

- Android only; do not merge main, release or modify iOS. Preserve user-untracked config/handoff files. Never read/print local.properties or secrets.
- WeChat and music toggles default off. The system notification-access grant is a separate user action, explained in the phone UI. No silent enabling on the owner's phone.
- Only com.tencent.mm notifications are parsed; filter package and preference before reading content. No replies, history database, accessibility scraping, uploads or notification-body logs. Keep at most the latest three message previews in RAM; clear on removal/disable/listener disconnection.
- Spec's immediate/only-live wording does not authorize an always-on DAT session. Implement the explicit default: notifications display only in an already STARTED session with a ready display. Do not create a new session for an incoming notification. The phone's music page explicitly holds a session-only owner while open; it never claims the camera.
- A notification card can temporarily retain that already-running session for 10 seconds. Timer replacement/disable/disconnect releases once and cannot clear a later feature's card. Do not replay stale notification cards when a future session starts.
- Review correction: every posted update replaces prior inbox entries for the same notification key, including empty/redacted and summary-only updates. Remove by key before parsing/publishing; never retain formerly readable details after a redacted update.
- Review correction: observe current-card dismissal/supersession to retire the temporary notification claim immediately. Done and another feature's card end it; paging the same WeChat card retains it. Cleanup is owner-scoped, and a retired timer cannot affect a replacement.
- Media updates never start sessions; show a current track on the music page, or in an existing session if the current card is the menu/music. A track change must not continually overwrite active AI/notification content. No position polling.
- Package allowlist is editable; defaults com.netease.cloudmusic and com.luna.music. Actual Qishui package/control support is hardware-pending. Select playing sessions first, then most recent playback-state change; stable tie fallback.
- NotificationListenerService must wait for onListenerConnected before platform reads; remove every controller/listener callback on disable/disconnect/destroy. Platform callbacks run on Main on minSdk31; DAT mutators remain Main. Catch permission loss and retire callbacks; no silent retry loop.
- Media controls target the current selected token and supported PlaybackState actions. Do not launch/install third-party apps or send external messages.
- Album art is reduced to at most240 pixels on a worker; only RAM JPEG bytes enter the pure card model. Decode at the SDK boundary; no remote image fetch. Missing/invalid art still yields a text card. Metadata-only updates need not resend identical art.
- Preserve C content-origin button fencing and feature-scoped cleanup. Music control registration is identity-safe and cleared with listener disconnection.
- All real WeChat/NetEase/Qishui/device checks stay pending until actually executed. Public Android docs verified 2026-09-11: NotificationListenerService and MediaSessionManager references.

## Shared interfaces

Package `com.smartview.glassai.bridge`:

- `data class BridgeMessage(val notificationKey:String, val sender:String, val text:String, val timestamp:Long)`.
- `class WeChatInbox`: `fun update(messages:List<BridgeMessage>):List<BridgeMessage>`, `fun remove(notificationKey:String):List<BridgeMessage>`, `fun clear()`, `val messages:List<BridgeMessage>`. Bounded3, dedupe message identity, latest timestamp order. No Android imports.
- `data class MediaSnapshot(val id:String,val packageName:String,val title:String,val artist:String,val isPlaying:Boolean,val changedAt:Long,val artJpeg:ByteArray?=null,val canPlayPause:Boolean=true,val canNext:Boolean=true,val canPrevious:Boolean=true)`.
- `fun selectMedia(items:List<MediaSnapshot>,allowedPackages:Set<String>):MediaSnapshot?`.
- `object WeChatNotificationParser { fun parse(sbn:StatusBarNotification):List<BridgeMessage> }`: platform boundary only; MessagingStyle prefers its newest messages, else title/text/bigText. Empty/redacted content remains empty (service supplies localized unavailable notice), summary strings pass through without invented message details.

Parent-owned settings/runtime:

- `data class BridgeSettings(val wechatEnabled:Boolean=false,val musicEnabled:Boolean=false,val mediaPackages:Set<String>=DEFAULT_MEDIA_PACKAGES)`.
- `class BridgePreferences(context:Context)`: `val settings:StateFlow<BridgeSettings>`; `setWechatEnabled(Boolean)`, `setMusicEnabled(Boolean)`, `setMediaPackages(Set<String>)`. Process singleton via `getInstance(context)`; no API keys stored here.
- `object NotificationBridgeRuntime`: `listenerConnected:StateFlow<Boolean>`, `music:StateFlow<MediaSnapshot?>`, `messages:StateFlow<List<BridgeMessage>>`, `error:StateFlow<String?>`; `enterMusicPage(app:Application)`, `leaveMusicPage()`, `playPause()`, `next()`, `previous()`. Main-only mutation. `hasNotificationAccess(context):Boolean` and `openNotificationAccessSettings(context)` convenience helpers; no reads of notification contents outside service.
- `NotificationBridgeService : NotificationListenerService`: owns MediaSessionManager/listeners/controllers/WeChatInbox and reports through runtime. Runtime holds service only while connected and exposes no public data storage. Manifest exported service guarded by BIND_NOTIFICATION_LISTENER_SERVICE and notification listener action.

Existing Display API additions (backward-compatible defaults):

- `DisplayCard.WeChat(sender,preview,count:Int=1,timestamp:Long=0,page:Int=0)`; use existing Page/Done actions, retain all merged text through bounded pagination.
- `DisplayCard.Music(title,artist,isPlaying,app:String="",artJpeg:ByteArray?=null)`.
- `DisplayNode.Image(jpeg:ByteArray,size:Int=240)`; renderer decodes in its existing IO send path and calls the actual pinned SDK bitmap image API. Node/card remain Android-free. Every exhaustive when in pure tests/preview/renderer updated.
- `interface MusicController { fun playPause(); fun next(); fun previous() }`; router registerMusic/unregisterMusic identity checks and existing three Music actions. Parent wires runtime; no new navigation action required.

## Task 1 — notification parsing and media selection

Files: new bridge/BridgeModels.kt, bridge/WeChatNotificationParser.kt, corresponding JVM tests and androidTest parser tests.
- [x] Test bounded3/dedup/order/removal, summary/missing content, non-WeChat filtering, playing priority and allowed-package selection.
- [x] Implement smallest pure inbox/selector and Notification bundle extraction (public API only, API31-compatible).
- [x] Parent compile/run focused tests and the integrated suites; worker reports added vs actually-run distinctly.

## Task 2 — service, preferences, runtime and controls (parent critical path)

Files: bridge/BridgePreferences.kt, bridge/NotificationBridgeRuntime.kt, services/NotificationBridgeService.kt, AndroidManifest.xml, GlassesActionRouter.kt plus its tests.
- [x] Test notification dwell/retire ownership and media-control selection, disabled/no-live-session no-op, callback replacement/disconnect with an injectable policy seam where useful.
- [x] Implement settings and state hub, service binding/lifecycle, platform reads, callback cleanup, permission-loss handling, art encoding and action support checks.
- [x] Implement camera-free manual music owner and temporary notification claim; avoid replay and expired callback races.
- [x] Add manifest declaration, reuse only public APIs. No system-level MEDIA_CONTENT_CONTROL request.
- [x] Validate actual MediaSession control through a test-owned session on the emulator where feasible; do not substitute for real app support.

## Task 3 — complete WeChat/music cards and art (independent worker)

Files: DisplayCard.kt, DisplayNode.kt, DisplayCards.kt, debug preview node renderer/samples, pure card/layout tests and image-boundary instrumentation.
- [x] Extend defaulted DTOs and exhaustive renderers.
- [x] Verify bitmap image signature against cached0.9 AAR (MCP generic image docs currently emphasize URI; do not guess overloads).
- [x] Add bounded three-message pagination/count/time, music app/title/artist/art/buttons within600 units; text-only fallback remains usable.
- [x] Tests preserve content, old constructor compatibility, render node height, no-art/invalid-art behavior. Real lens rendering remains pending.

## Task 4 — phone control page and settings entry (independent worker)

Files: new ui/screens/NotificationBridgeScreen.kt, Navigation.kt, SettingsScreen.kt, HomeScreen.kt, resources both locales; optional small testable mapper/ViewModel only if needed.
- [x] Page explains limited notification previews and access grant, shows two opt-in toggles, editable music packages and current listener status.
- [x] Show current track and supported control affordances, latest messages, disabled/unavailable guidance. Enter/leave calls runtime music-page lifecycle without camera access; lifecycle STOP releases, START re-acquires only when appropriate (no duplicate owner leaks).
- [x] Settings entry and Home card navigate to this page. Preserve existing routes.
- [x] Default/off/access-revoked and configuration flows verified; resource parity, preview and page controls inspected on emulator.

## Task 5 — closing verification and docs

- [x] Compile/test both variants plus assemble both APKs and instrumentation; exactly one Gradle runner.
- [x] Run parser/media fixtures and existing MDK suite; retain C known SDK stress exclusion explicitly.
- [x] Run emulator UI checks with test data only and restore test-owned notification permission/preferences.
- [x] Publish hardware checklist (execution remains pending): real WeChat notification details on/off and grouped messages; NetEase/Qishui package identity, metadata/art and prev/play/pause/next; no camera interruption; notification disable clears/detaches; screen background releases music owner; DAT sleep/back/rapid restart risk.
- [x] Write phase-e ledger/report, update docs, commit on android-v2. No claim of third-party or glasses hardware passes without actual execution.

## Self-review

The service remains the sole platform reader; pure DTOs do not import Bitmap. Manual music sessions and short notification retention obey nonresident-session decision. The notification system grant and feature toggles are separate. The C SDK transport hang has no supported public fix and is not obscured by these tests. No new credentials or cloud service are needed for E. Third-party package behavior cannot be decided from docs alone and stays hardware-pending.
