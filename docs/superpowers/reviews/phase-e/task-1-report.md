# Phase E Task 1 — notification parsing and media selection

Implemented directly on `android-v2`, within the assigned five new files. No commits, Gradle, adb, notification posts, permission changes, or preference mutations. `local.properties` was not read. Concurrent edits outside this scope were left untouched.

## Files created

- `android/app/src/main/java/com/smartview/glassai/bridge/BridgeModels.kt`
- `android/app/src/main/java/com/smartview/glassai/bridge/WeChatNotificationParser.kt`
- `android/app/src/test/java/com/smartview/glassai/bridge/BridgeModelsTest.kt`
- `android/app/src/androidTest/java/com/smartview/glassai/bridge/WeChatNotificationParserInstrumentedTest.kt`
- `docs/superpowers/reviews/phase-e/task-1-report.md`

## Behavior and shared interfaces

The production declarations match the plan: `BridgeMessage`, `WeChatInbox.update/remove/clear/messages`, `MediaSnapshot` with its defaulted art/control fields, `selectMedia`, and `WeChatNotificationParser.parse(StatusBarNotification)`.

The inbox retains at most three previews, ordered by descending message timestamp, and deduplicates the full `(notificationKey, sender, text, timestamp)` identity. Removing a key deletes all its retained previews; evicted content is not kept elsewhere. Media selection filters the allowlist first, prefers playing sessions, then the latest playback-state change, with package name and session ID as a stable ascending tie fallback. Models contain no Android imports.

The parser rejects every package except exact `com.tencent.mm` before reading content. It extracts current MessagingStyle messages, deduplicates and returns the newest three, preserving their sender and message timestamp. Missing sender names fall back to the notification title. Without usable messages it prefers nonblank bigText, then text, using the notification title and post time. Summaries and supplied placeholders remain unchanged; missing/blank/redacted bodies return an empty list. Unreadable external bundles return empty without logging their exception or content. Historic messages and attachment contents are not read.

API compatibility was checked against the public Android references: [Message.getMessagesFromBundleArray](https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message#getMessagesFromBundleArray(android.os.Parcelable%5B%5D)) is available from API 30, and sender-person access from API 28. The Bundle getter used is available on minSdk 31. Instrumented fixtures use the [public StatusBarNotification constructor](https://developer.android.com/reference/android/service/notification/StatusBarNotification), including its legacy score argument; they do not use a system-only overload.

## Tests authored versus run

**Authored: 24 tests. Executed by this worker: 0.**

`BridgeModelsTest`: 12 JVM tests covering the three-message bound and latest ordering, cumulative-update deduplication, complete message identity, removal of all previews under an old key, no resurrection of evicted content, clear, input-list isolation, empty updates, allowed-package filtering, playing priority, recency selection, stable ties, and empty selection.

The requested P1 regression is included as `removingOldKeyBeforeAnEmptyUpdatePreservesOtherKeyAndLaterReadableContentReturns`: remove old key, update with empty parser results, preserve the other key, then accept a later readable preview under the old key.

`WeChatNotificationParserInstrumentedTest`: 12 AndroidJUnit4 tests using real Notification builders, MessagingStyle, Person, Bundle, CharSequence, and StatusBarNotification objects. They cover package filtering before content conversion, title/text/post time, full bigText precedence, blank bigText fallback, missing sender/body, grouped summary preservation, newest-three/deduplicated MessagingStyle extraction excluding history, blank message handling, summary fallback, malformed bundles, unreadable content, and cumulative parser-to-inbox updates followed by removal. Nothing is posted to the system; no notification-access grant or test-owned channel registration is required. No parser test relies on mocked Android SDK stubs.

The initial tests and the requested P1 regression were written before the production files. Because the user assigned execution exclusively to the parent runner, no red/green run was performed or claimed. Additional unreadable-content coverage was authored during source review. Parent should compile and run both test classes, including the instrumented parser suite on an API 31+ Android runtime.

## Integration notes and remaining verification

As clarified by the parent, the service must remove `sbn.key` before every posted replacement, then update from parser output. An empty parse must never merge the old key's details back in; the service owns the localized unavailable placeholder retaining that key. A summary similarly replaces prior details. The parser remains empty for redaction and does not synthesize that placeholder. Service preference filtering, replacement handling, disable/disconnect cleanup, display lifetime, and session ownership remain outside Task 1.

Source review checked shared signatures, public API availability, package-first filtering, no body logging, Android-free models, and the owned-file boundary. Compilation and test results are pending the parent runner. Real WeChat/NetEase/Qishui behavior and glasses checks remain unverified.
