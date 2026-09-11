# Phase D Task 4 — translation UI implementation

Implemented directly in the shared workspace within the assigned scope. No Gradle, adb, commits, live cloud calls, microphone recording, or hardware checks were run by this worker.

## Parent integration signatures

Package `com.smartview.glassai.ui.screens`:

```kotlin
@Composable
fun LiveTranslateScreen(
    wearablesViewModel: WearablesViewModel,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
)

@Composable
fun LiveTranslateSettingsScreen(onBackClick: () -> Unit)
```

The translation page creates `LiveTranslateViewModel` through its context-based factory and the current navigation entry's ViewModelStore. Both screen signatures match the plan; the parent owns Nav/Home route wiring.

## Files added

- `android/app/src/main/java/com/smartview/glassai/viewmodels/LiveTranslateViewModel.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveTranslateScreen.kt`
- `android/app/src/main/java/com/smartview/glassai/ui/screens/LiveTranslateSettingsScreen.kt`
- `android/app/src/main/res/values/strings_translate.xml`
- `android/app/src/main/res/values-zh-rCN/strings_translate.xml`
- `android/app/src/test/java/com/smartview/glassai/viewmodels/LiveTranslateViewModelTest.kt`
- This report.

## Behavior and ownership

Each explicit Start snapshots validated settings and the selected Alibaba region/key, independent of the current vision/chat provider. Missing credentials do not request microphone permission or create audio routing. Opening either page or changing settings never starts recording; settings changes apply on the next Start.

The VM requests RECORD_AUDIO through the screen, rechecks it after permission/routing waits and on RESUME, and fences retired attempts. Requested glasses input waits at most three seconds for SCO, then releases the pending route and visibly falls back to the phone. An existing communication route is not deliberately taken over. Connection/source instances belong to individual attempts; ERROR, Stop, lifecycle STOP, disposal and ViewModel clearing retire the service and owned route/camera/image jobs. Resuming does not auto-start.

Dalton confirmed the concrete no-argument `LiveTranslateService` and all planned StateFlow/method signatures. A small UI-owned interface/adapter permits JVM fakes without modifying the service. UI recording/image work begins only at READY; protocol acknowledgement validation, source.start success, ten-second connection/configuration timeout and first-accepted-audio image gating remain enforced by that service.

Visual enhancement uses the parent's `startStream(owner, permission)` / `stopStream(owner)` with a stable private screen token. The helper schedules startup, so the adapter additionally waits for actual Streaming (bounded to 18 seconds). It uses only new `currentFrame` references; no capturePhoto/snapshot borrowing loop. JPEG encoding runs on Default, reduces the longest side to 640 pixels, rejects payloads above 500,000 bytes, and feeds at most twice per second. Cancelled/retired encoding cannot publish. Camera failures leave visible guidance while audio continues.

Translation/current-original text is selectable. Stopped-session history is newest-first, RAM-only and bounded to 50 entries; clearing history is explicit. Settings expose all 18 sources, 11 supported targets and eight voices, disabling incompatible voices and using the models worker's atomic validation for changes/swaps. Spoken output, image enhancement and phone-mic preferences use the six existing translation keys through TranslatePreferences.

## Verification and remaining parent execution

Added **17 JVM tests**, written before the initial production implementation and then extended for delayed encoding/duplicate Start. Cases cover idle settings, missing key, denied/late/revoked microphone permission, three-second SCO fallback, READY/image gates, ERROR cleanup, pending visual permission cancellation, retired service callbacks, settings snapshots, bounded/idempotent history, camera failure, image size/rate, late encoding and duplicate Start.

No RED/GREEN execution is claimed: tests and compilation were intentionally not run because the parent owns the single Gradle runner. Static verification confirmed valid XML, 40 unique matching translation keys per locale, no trailing whitespace in the six added source/resource/test files, and only owner-scoped shared camera calls. The actual service constructor/API and parent camera helper signatures were reread after they landed.

Parent should run the focused VM tests and integrated builds, then inspect both locale pages and permission/STOP/settings transitions on the emulator. Real SCO input/output routing, lenses, exact-model cloud acceptance and image enhancement remain hardware/account validation items, not fixture-proven behavior.

## Accepted integration review follow-up

All four findings were implemented with 15 additional regressions. See [translation-integration-fix-report.md](translation-integration-fix-report.md) for camera lease fencing, SCO loss/ownership, authoritative stopped-history snapshots, changed files and parent rerun scope. The focused suite now contains 32 test methods; both locales have 41 translation keys. These changes have static checks only and are not covered by the parent's earlier full-suite result.
