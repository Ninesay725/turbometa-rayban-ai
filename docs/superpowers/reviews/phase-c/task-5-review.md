# Phase C Task 5 — read-only review

Reviewed the current shared working tree on 2026-09-11 against S7, Task 5, and invariants I1–I5/I7/I9/I12. No production/test edits or Gradle commands were made. The controller's running integrated build was not interrupted. Findings below come from source/control-flow inspection and the cached DAT 0.9.0 AAR, not a claimed device reproduction.

## Findings and corrections

### 1. [P2] Quick Vision's Again button is rejected throughout the result window

**Location:** [QuickVisionService.kt:202](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt:202).

The new `captureJob?.isActive == true` guard also rejects the result card's Again action. The result is published at lines 327–329, inside that job; speech and then the 15-second dwell keep the same job active until lines 340–343. The Task 2 button emits `StartQuickVision` ([DisplayNode.kt:80](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/DisplayNode.kt:80)), and the router starts this service with the same capture action. `onStartCommand()` reaches the guard and returns without taking another photo. When the job finally finishes, cleanup replaces the result with Status, so the normal result-card lifetime provides no usable Again action.

**Correction:** distinguish capture/analysis from result speech/dwell. During the result phase, explicitly queue a rerun or cancel and finish the old run before starting the next capture. Preserve the protection against simultaneous captures under the same owner name; do not merely remove the guard. A cancelled old capturer must finish its `finally` before a new run reuses that owner.

**Check:** publish a successful result, dispatch Again during its dwell, and assert that a second capture starts with no concurrent camera/display claims and without the old run clearing the new result.

### 2. [P2] Quick Vision's late cleanup clears a newer feature's card

**Location:** [QuickVisionService.kt:189](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt:189).

`hasDisplayCard` records that this service once published content; it does not establish that the shared sink still contains this service's content. A reachable sequence is: Quick Vision result → Done → Status → Start Live AI/LeanEat → new feature card → old Quick Vision dwell expires. `BackToMenu` only calls `sink.showStatus()` ([GlassesActionRouter.kt:66](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/GlassesActionRouter.kt:66)); it neither ends the Quick Vision job nor resets its flag. Cleanup then calls `showStatus()` unconditionally for the historical flag and overwrites the new feature. Releasing the Quick Vision claim afterward does not undo the overwrite; another feature can still own the session.

The same ownership weakness exists in Live AI's `hasDisplayCard` cleanup if another producer has since replaced its card. The sender's request-sequence protection cannot help: this cleanup is a **new** Status request.

**Correction:** tie clearing to the active feature/run, or explicitly terminate the previous feature's result phase on menu exit before activating another feature. A local boolean saying “I have shown something” is insufficient. If using ownership/generation bookkeeping at the shared sink, preserve it across Page actions and clear only the owning run.

**Check:** display Quick Vision, return to the menu, publish another feature's card, then complete/destroy Quick Vision. The newer card must remain, while `QuickVisionDisplay` is released exactly once.

### 3. [P2] Late Gemini setup completion can restore Live AI after disconnect

**Location:** [OmniRealtimeViewModel.kt:110](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/viewmodels/OmniRealtimeViewModel.kt:110), with the callback at [line 246](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/viewmodels/OmniRealtimeViewModel.kt:246).

The display collector's only activity gate is `connected || state == Connecting`. `disconnect()` resets state inside a launched coroutine but does not independently invalidate the active Live AI run or its callbacks. Gemini's socket listener forwards messages without checking that the socket is still current ([GeminiLiveService.kt:170](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/GeminiLiveService.kt:170)). A previously delivered `setupComplete` message processed after disconnect still invokes `onConnected` ([line 419](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/GeminiLiveService.kt:419)), which sets the ViewModel's `_isConnected` back to true. The new collector consequently publishes Live AI again after the user has ended it.

This can persist: Gemini's own connection StateFlow was already reset to false by disconnect, and a later failure writing false again need not emit another value to correct the ViewModel flag. The socket/callback weakness predates Task 5; the new glasses collector exposes it as stale content after exit. An End action while the phone remains on the Live AI screen is sufficient to keep this ViewModel/collector alive; no ViewModel-destruction assumption is needed.

**Correction:** gate display publication by an explicit active run/generation, invalidate it synchronously at disconnect, and ignore callbacks from ended/replaced runs. Restore Status directly on the end path with appropriate ownership protection from finding 2; do not rely solely on the next combined-state emission. Preserve the existing onCleared cleanup.

**Check:** start connecting, end the run, then deliver its late Gemini setup-complete callback. No new Live AI card may appear. Repeat after beginning a different feature.

### 4. [P2] Synchronous TTS failure can retain the new display claim indefinitely

**Location:** [QuickVisionService.kt:329](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt:329), [speakAndWait at line 384](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/services/QuickVisionService.kt:384), especially line 409.

Success waits for speech before reaching dwell/cleanup. `speakAndWait` resumes only from `onDone`/`onError`, ignores the synchronous result of `tts.speak`, and has no timeout. If the engine disconnects after successful initialization, `isTtsReady` remains true but queuing speech can fail before any utterance callback is registered with the engine. Android's implementation returns the supplied ERROR result directly when no service is bound/connected; `speak` uses that path. See the [AOSP TextToSpeech implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/tts/TextToSpeech.java) (`speak`, `runAction`, and `Connection.runAction`).

The waiting bug is pre-existing, but Phase C now retains `QuickVisionDisplay` during it: the capture claim has already been released, while the new display claim, stale result and active-job guard remain until explicit service destruction/stop. The 15-second policy does not bound this wait because it runs afterward.

**Correction:** resume/fail immediately when speech queuing does not return SUCCESS, and give accepted speech a bounded completion policy with cancellation/stop cleanup. Then continue to the appropriate dwell and normal claim release. Keep cancellation distinct from an analysis error.

**Check:** successful analysis with a TTS adapter returning ERROR and emitting no callback must still reach cleanup and release `QuickVisionDisplay`. Also test stopping during accepted speech.

## Confirmed renderer/layout defect requested by the controller

### 5. [P2] A valid sub-280-character Chinese page can exceed the whole 600-unit display

**Locations:** [DisplayPagination.kt:16](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/DisplayPagination.kt:16), [DisplayNode.kt:73](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/main/java/com/smartview/glassai/glasses/DisplayNode.kt:73). The corresponding unbounded BODY usage also affects OpenClaw/detail pages and the Notice fallback.

This is demonstrable without guessing Chinese glyph width. Use:

```kotlin
val text = List(20) { "中".repeat(13) }.joinToString("\n")
// 260 Chinese characters + 19 newline characters = 279 UTF-16 units.
val card = DisplayCard.QuickVision("识别", text)
```

`paginate` returns the whole string as one page because its length is below 280; its `end == content.length` branch does not split on any internal newline. The card puts that page in one BODY text node. The actual cached 0.9.0 AAR defines **BODY = 28sp text / 36sp line height**, **HEADING = 40sp / 48sp**, and **META = 22sp / 28sp**. `RendererProfile.emitText` forwards the original text plus those fixed size/line-height values; the app does not implement line-aware repagination, scrolling, or font reduction.

At the plan's 600×600 logical canvas and normal font scale, the BODY alone requests **20 × 36 = 720 logical units**, before 48 units of root vertical padding, the heading, gaps, and buttons. Even treating the final line conservatively as 28 instead of 36 gives 712. All content cannot be shown at the configured size on one page. Whether the hardware clips, overlaps or rejects it is not claimed here; the layout-budget violation is established. A failing fallback reuses this text within the same 280-unit limit, so it is not a demonstrated fit remedy either.

**Evidence collected:** `javap -c -p` against extracted classes from cached `mwdat-display/0.9.0/4bd4794363b2dbadec791dea174a42be790a18d4/mwdat-display-0.9.0.aar`, inspecting `internal.view.TextStyle` and `RendererProfile.emitText`. A read-only string calculation confirmed length 279 and 20 explicit lines. No JVM SDK content block or hardware rendering was executed.

**Correction:** enforce a line/height budget per card in addition to the maximum character count, reserve room for header/user text/page indicator/buttons, and give long Notice/fallback content a safe clipping or pagination policy. Add this explicit-line fixture before choosing a smaller character constant: a character limit alone still permits many short lines. For plain Chinese text without explicit newlines, measure actual glyph wrapping; this review does not invent an exact width or claim an unmeasured pixel count.

**Preview verification gap:** [GlassesDisplayPreviewScreen.kt:229](D:/Coding/Workspaces/Android/turbometa-rayban-ai/android/app/src/debug/java/com/smartview/glassai/debug/GlassesDisplayPreviewScreen.kt:229) currently uses HEADING 28/34, BODY 20/26, META 16/20, all smaller than the AAR values above. The cached Nova renderer also specifies an 88-unit button height. Therefore the current preview is not reliable evidence of SDK fit. Align the preview metrics before using it to approve dense Chinese layouts. This is a Task 8 follow-up, not a production change made by this review.

## Lifecycle paths inspected without an additional finding

- The service waits for device metadata before evaluating display availability and takes the session-only display claim before constructing/running the capturer. Although later than the plan's original “before TTS” anchor, it still establishes the required claim-before-camera ordering and avoids deciding against a cold-start device before metadata arrives.
- `acquire(DISPLAY_OWNER)` uses no camera intent. Capturer `finally` releases its camera and OWNER, while cleanup releases DISPLAY_OWNER last. `onDestroy` also calls cleanup. Repeated cleanup is guarded by `displayClaimHeld` and `hasDisplayCard`.
- All explicit capture failure outcomes call `failAndFinish` and return. Thrown analysis/storage exceptions reach the generic error path. A returned analysis failure shows the spoken failure Notice and uses `success = result.isSuccess`, so a STARTED claimed display receives the 5-second error dwell, not 15 seconds.
- No-display/disabled-display success retains the 500 ms fallback. Returned analysis failure also retains its pre-Phase-C 500 ms post-fold delay; `failAndFinish` retains 2000 ms. That difference is intentional, not a review finding. No display claim is taken when unavailable.
- Cancellation from cleanup is rethrown; successful/error cards are not deliberately emitted from that cancellation handler. Stopping during cancellable analysis prevents the suspended success continuation from publishing later.
- The mapper's phase/default/text logic matches S7. Including `isConnected` as a fifth combine input improves disconnect observation relative to reading it opportunistically. On normal state-driven disconnect, its null-card branch restores Status; the callback reactivation case above is the missing protection.

## Test assessment

Parameterized/table assertions are accepted; routine method-count differences are not findings. Existing policy tests cover every display state for claimed success/failure and unclaimed success. Add the explicitly requested unclaimed failure assertion (`STARTED`, claim=false, success=false, fallback=2000 → 2000) when adjusting tests.

The composing test uses the real manager and capturer, checks the remaining owner, camera owner, STARTED display, no early session stop, and final `removeDisplay`→`stop` ordering. Strengthen it with `assertFalse(manager.hasCameraClaim)` after capture, `currentDisplay() != null`/no removal before final release, and final detached/no-session assertions. `currentCameraOwner == null` alone does not prove camera-intent bookkeeping was released.

The existing mapper/policy/composition tests do not exercise service reruns, display ownership during feature switches, synchronous TTS rejection, or late provider callbacks. Those are the behavioral checks associated with findings 1–4; this report makes no claim about the outcome of the controller's running suite.
