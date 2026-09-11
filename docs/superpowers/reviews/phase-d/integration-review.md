# Phase D — bounded integration review

**All three P2 findings are closed by source reinspection. No unresolved finding remains in this bounded review.** The parent's new handoff tests have been inspected but execution is pending its next build. Only this review document was changed by the reviewer.

Scope: AnalysisPhotoRoute/Nav and the new QuickVisionService/Screen TTS wiring, including the parent's `keepDisplaySession = true` LeanEat patch. The original findings below describe the pre-fix snapshot; closure evidence follows.

## P2 — Terminal error speech is cancelled before completion or fallback (closed)

[QuickVisionService.kt:275](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt:275) launches error speech as a sibling job, then waits only the display dwell. Without an attached display, the analysis-failure branch reaches cleanup after **500 ms** (lines 282–284). Cleanup cancels the speech job and calls `speech.stop()` (140–143). `failAndDwell` has the same issue with a 2-second fallback dwell (297–302).

Concrete trigger: Alibaba is selected, vision analysis fails, and the error TTS request takes more than 500 ms to produce audio. The service finishes before the error is audible. A later cloud failure cannot reach system fallback because cancellation correctly propagates through TTSService. The new cloud request makes the old fixed-delay announcement path insufficient; the success path already awaits `speakAndWait`.

Await bounded terminal-error speech before dwell/cleanup, preserving explicit stop/rerun cancellation. Regression: delayed cloud audio or failure beyond 500 ms, no display; the error must complete through cloud/system speech before normal cleanup, while explicit stop must cancel without fallback.

## P2 — LeanEat drops late display availability for the whole visible entry (closed)

[AnalysisPhotoRoute.kt:54](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/ui/navigation/AnalysisPhotoRoute.kt:54) waits only 1.5 seconds for `isDisplayAvailable`, then permanently abandons the session-only claim until another lifecycle START. Display metadata arriving later is supported by the shared manager and its existing `lateMetadataAttachesOnceAndResumeIsIdempotent` test.

Concrete trigger: enter LeanEat while metadata is still unavailable; capability arrives after 1.5 seconds; capture succeeds and its camera claim is released at line 91. No analysis display claim exists, so the session stops and the later nutrition result cannot display. A handed-in photo has the same problem without a camera claim to begin with. This leaves approved decision 1's LeanEat page/result lifetime incomplete.

Keep the availability wait active for the visible entry, cancellable on STOP, or re-evaluate before dropping the camera claim/result publication. Retain the Bluetooth/capability/enabled gates. Regression: capability becomes available after the initial 1.5 seconds; result display retains a session after camera release; STOP cancels a pending acquisition and releases only this entry.

## P2 — A photo handoff is not consumed by its receiving destination (closed)

[Navigation.kt:192](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/ui/navigation/Navigation.kt:192) and line 201 both read the same unqualified `photoHandoff`. It stays populated until the receiving screen is disposed, rather than being consumed when accepted.

Concrete sequence: Camera hands photo A to Vision; an independent `NavigationRequest.LeanEat` is handled while Vision remains composed. The incoming LeanEat destination can remember A before Vision's outgoing disposal clears the slot. LeanEat then starts with Vision's old image and skips its direct-entry camera path. Clearing the shared slot afterward does not clear the already remembered image. This does not automatically upload the image, but presents the wrong source for the user's next analysis.

Bind the pending handoff to its intended destination/entry and consume it once. Regression: Camera → Vision(A), then independent LeanEat navigation during the transition; LeanEat receives no photo A. A targeted Camera → LeanEat(A) must still receive A exactly once.

## Initial inspection without another finding

- AnalysisPhotoRoute uses the fresh owner-scoped capture result. STOP cancels route work and releases only its stream owner; the earlier VM permission/capture fixes remain intact.
- The added LeanEat claim is session-only, uses a unique entry owner, gates Bluetooth and the manager's capability/enabled availability, and cancels/releases on STOP. Vision keeps the default `false` behavior. The initial issue was the short availability window above.
- QuickVision success speech remains inside the run; replacement joins retired run cleanup before starting. Screen speech uses an instance per composition, cancels on stop/disposal, and fences old `isSpeaking` cleanup by generation. TTSService serializes old/new playback cleanup and rethrows cancellation before fallback. No additional defect found in these new paths.

## Initial evidence and test boundary

Read the production composition and existing TTSService, QuickVisionRunCoordinator, QuickVisionDisplayPolicy, DisplaySessionLifecycle, and Wearables lease regressions. The existing tests cover individual cancellation/ordering/fallback contracts; they do not exercise these Nav/route/service call sites. No new tests authored: the affected composition is inline Compose code or private Android Service wiring, without a current injectable integration entry point; duplicating it in a pure test would not guard production. Suggested regressions are stated above for the parent to connect to an actual seam.

**Executed by this reviewer: zero tests, Gradle or adb commands.** The parent's report that all seven new owner/VM tests pass is acknowledged; other workers' outstanding failures and full-suite results are outside this review.

## Closure reinspection — 2026-09-11

- **Terminal error speech:** both the analysis-failure branch at [QuickVisionService.kt:275](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt:275) and `failAndDwell` at line 298 now await `speakAndWait` before dwell. That helper cancels/joins the status utterance and awaits TTSService inside the active run. Its 60-second speech bound, cancellation propagation before fallback, and coordinator cleanup ordering remain intact. Normal cleanup can no longer cut the terminal announcement off after the former 500 ms/2-second delay.
- **LeanEat display lifetime:** [AnalysisPhotoRoute.kt:57](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/ui/navigation/AnalysisPhotoRoute.kt:57) now suspends on `isDisplayAvailable.first { it }` without the 1.5-second cutoff. A late capability can acquire the session-only claim while the entry remains started. STOP/disposal cancels that exact job and releases its unique owner at lines 68–69; the Bluetooth/capability/enabled gates and camera ownership are unchanged. The existing acquisition bound remains 12 seconds after availability.
- **Photo handoff:** [PhotoHandoff.kt:9](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/ui/navigation/PhotoHandoff.kt:9) matches the destination and clears the pending value before returning it. An unrelated receiver gets null without consuming the addressed photo. Nav puts the matching route at lines 284/288, and both receivers consume once inside `remember` at lines 192/200. The old disposal-based shared-slot cleanup is gone, so an outgoing destination cannot clear a newer pending handoff.

The parent added three tests in [PhotoHandoffTest.kt](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/test/java/com/smartview/glassai/ui/navigation/PhotoHandoffTest.kt): unrelated navigation cannot receive/consume a Vision image; explicit nutrition handoff is consumed once; and a newer addressed image replaces the pending one. These exercise the production helper. Nav's use of the helper and the route/service lifecycle fixes were verified by source inspection, not runtime tests.

**Closure evidence:** no app or test edits and no test execution by this reviewer. The three new tests are parent-authored and pending the parent's next build; this closure does not claim they or a full suite have passed.

## API 31 locale evidence boundary — 2026-09-11

The parent confirmed `language_prefs.xml` held `app_language = zh-CN` before force-stop and cold-started the full2 Phase D APK successfully into Chinese. The reviewer inspected [language-after-cold-start.png](D:/Coding/Workspaces/Android/turbometa-rayban-ai/docs/superpowers/reviews/phase-d/language-after-cold-start.png): the Home screen visibly shows Chinese labels including 首页, 连接眼镜, 快速识图 and 设置. The persisted-value check and cold-start execution were performed by the parent; the screenshot inspection was performed by this reviewer.

The first English restart is not a RED for the `MainActivity.attachBaseContext` fix: the parent found the saved value still `en` and confirmed the process had been killed too early after selecting Chinese. Once the new preference was confirmed persisted, cold start restored Chinese. The bounded source investigation found no additional confirmed LanguageManager/AppCompat context-attachment defect.

Language-switch Activity recreation can still coincide with UIAutomator returning a null root. The parent reports the earlier window diagnostic showed an `EXITING` window with the main thread idle, and restarting restored normal operation. Record this separately as an observed recreation/automation limitation with cause unconfirmed; it is not evidence of failed language persistence or a confirmed main-thread hang. The successful cold-start check does not establish that live-switch automation is reliable.

This read-only locale investigation is closed. Only this report was updated; no production changes, Gradle, adb or tests were run by the reviewer.
