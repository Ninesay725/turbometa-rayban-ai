# Phase D — bounded plan review

Reviewed `2026-09-11-android-v2-phase-d-parity.md` against approved design §9 and both Phase D continuation research notes.
The approved scope/model choices stand. Resolve these three cross-task handoffs before Task 4 UI implementation; this is a plan/interface review, not a verdict on workers' unfinished code.

## 1. P1 — Specify the parent-owned camera ownership/cancellation API

**Plan:** lines 11, 76, 83, 91.
The plan promises screen-owned streaming and capture cancellation but supplies only the shared WearablesViewModel.
Its existing `OWNER` is a single constant (line 87); `startStream()`/`stopStream()` (362/500) cannot identify the calling screen.
Thus an outgoing camera/translation screen's cleanup can stop a successor's stream during navigation.
`takePhoto()` (540) launches an independent viewModelScope job, returns the previous photo, and is not cancelled by `cancelStreamJobs()`.
Watching capturedPhoto instead of its return value does not prevent a retired capture publishing into a later screen.

**Required handoff:** parent publishes backward-compatible helper signatures for owner/token-scoped stream start/stop and a fresh, cancellable capture result (or equivalent operation handle).
Both camera and translation workers must use the same contract; cancellation/retirement must fence late capture/decode publication.
**Acceptance:** start A, retire A, start B, deliver A's delayed capture/cleanup; B remains active and receives no A photo.

## 2. P1 — Assign CameraScreen's DAT permission request boundary

**Plan:** lines 71–83.
Translation receives `onRequestWearablesPermission`, but CameraScreen's published signature has no permission callback and the plan names no parent route gate.
Current `startStream()` does not check/request DAT CAMERA permission; only `navigateToStreaming(callback)` performs the request flow.
Automatic streaming on a fresh grant state therefore has no agreed request path between the camera worker and parent Nav integration.

**Required handoff:** explicitly choose a parent route gate or pass the existing suspend permission callback into CameraScreen.
Check Android Bluetooth permission before DAT access, then check/request DAT CAMERA before stream acquisition; denial must leave the stream unowned and offer an explicit retry.
Permission completion must be fenced by the current visible/start attempt: leaving during a system prompt must not start camera or translation recording afterward.
**Acceptance:** first-use grant, denial, and leave-before-grant, including translation's RECORD_AUDIO/camera prompts.

## 3. P2 — Define bounded translation readiness and error cleanup

**Plan:** lines 10, 50, 61–76; translation research “Events and parser requirements” and “Android reuse and cancellation decisions.”
The plan requires session.updated but does not define READY relative to validated settings and successful source.start, or a connection/configuration deadline.
The shared WebSocket client has no read timeout (`HttpClients.kt:15`); a live socket that never acknowledges configuration can remain CONNECTING indefinitely after the UI acquires SCO.
Without a shared READY meaning, the UI can also start visual enhancement or report recording before capture has actually started.

**Required handoff:** protocol worker makes READY mean the current attempt's requested configuration was acknowledged/validated and source.start returned true.
Bound connect/configuration waiting; timeout, rejected/mismatched configuration, or start=false must retire transport/capture/playback and publish ERROR.
UI treats ERROR as terminal for its attempt, cancelling image work and releasing only its SCO/camera ownership; a late acknowledgement cannot revive it.
**Acceptance:** session.created without session.updated, mismatched acknowledgement, source.start=false, and stop/restart followed by an old acknowledgement.

No production/plan edits, Gradle, tests, adb, or live cloud/device checks performed. Only this report was written.
Translation UI implementation is waiting for the parent's handoff after these critical review points are communicated.
