Phase E Task 4 — phone notification and music UI

Implemented against the approved Phase E plan and the parent-owned BridgePreferences / NotificationBridgeRuntime interfaces. Compilation and device execution remain with the controller.

Files added or edited by this worker:

- `android/app/src/main/java/com/smartview/glassai/ui/screens/NotificationBridgeScreen.kt` — new phone page.
- `android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt` — `notification_bridge` route, Home and Settings callbacks, Back handling; both entry callbacks use `launchSingleTop`.
- `android/app/src/main/java/com/smartview/glassai/ui/screens/HomeScreen.kt` — notification/music feature card.
- `android/app/src/main/java/com/smartview/glassai/ui/screens/SettingsScreen.kt` — notification/music row in Integrations.
- `android/app/src/main/res/values/strings.xml` and `values-zh-rCN/strings.xml` — 40 matching `bridge_*` strings, appended without replacing existing rows.
- This report.

The page explains that both preferences default off and Android notification access is a separate grant. Only the explicit settings button invokes `openNotificationAccessSettings`; unavailable/denied settings launches receive a localized message. Access is refreshed on RESUME. Notification access and notification-service connectivity have distinct labels; neither is presented as a glasses connection status. Existing Home permission checks and Activity callback navigation were left intact.

Both toggles write the shared preferences directly. The editable allowlist trims/deduplicates one package per line, matches the parent's current package validation, rejects invalid drafts, and allows an empty set. Only this package draft uses saved instance state. Current track, artist, package, play/pause state, supported previous/play-pause/next controls, and up to three latest message previews are read from runtime StateFlows. Disabled, access-required, disconnected, empty, and runtime-error states have localized guidance. Message/track contents are never written to saved state, logs, files, preferences, or network by this page.

Lifecycle review: a single `LifecycleStartEffect(app)` uses the destination's LocalLifecycleOwner to call `enterMusicPage(app)` on START and `leaveMusicPage()` in `onStopOrDispose`. It is outside the lazy list and independent of preference/content changes, so scrolling, metadata updates, and toggles do not recreate the page claim. The parent runtime gates the claim on music enablement and listener availability. The paired effect covers STOP plus disposal before STOP, as documented in the [AndroidX LifecycleStartEffect reference](https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/LifecycleStartEffect.composable). The project already includes lifecycle-runtime-compose 2.10.0; no dependency or platform API above minSdk31 was introduced. No camera ownership is requested by the page. This is a source/API review, not an executed API31 lifecycle test.

Parent integration strings are available in both locales, without format arguments:

- `bridge_content_unavailable`
- `bridge_access_lost`
- `bridge_session_unavailable`

Validation performed: reviewed the actual shared model/preferences/runtime declarations; parsed both resource XML files; checked duplicate resource names and bridge-key parity; checked owned Kotlin bridge string references; reviewed the scoped diff and whitespace. No tests were added in this UI-only worker scope. No Gradle, adb, build, instrumentation, or test execution was performed, and no commit was created.

Remaining controller verification: integrated debug/release compilation; API31 cold/default-off page entry; grant/revoke access and return from Android settings; enable/disable music while the page remains STARTED; background/foreground, Back, rotation, and repeated entry while confirming the music owner releases/reacquires without camera ownership; persisted allowlist edits and empty/invalid drafts; keyboard/scroll layout in both locales; fixture track action support and RAM message clearing. Actual WeChat, NetEase, Qishui, and glasses behavior remains hardware-pending. The known SDK rapid-restart issue is outside this UI change.

No router, display model, runtime, service, manifest, or other worker-owned files were edited.
