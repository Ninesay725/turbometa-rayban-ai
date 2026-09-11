# Phase D — Wearables owner-helper review

**The P2 is closed by source verification; no unresolved finding remains in this bounded review.** Review scope is Phase D plan-review findings 1/2: the new owner-scoped stream/capture helpers, permission ordering, cancellation, and compatibility with legacy cleanup. No unrelated A/B/C work was reopened.

## P2 — A retired DAT permission check could launch a system prompt (closed)

At the original snapshot, `WearablesViewModel.kt:385–388`, `startStream(owner, ...)` awaited `registration.checkCameraPermission()` and immediately invoked the request callback on Denied. Cancellation and owner/generation checks occurred only afterward, at lines 396–397.

Concrete sequence: A begins a suspended DAT check; A's owner is stopped; B starts and is streaming; A's check completes Denied. A still opens its permission prompt over B before eventually returning false. The existing fence prevents A from acquiring the camera, but does not prevent the retired prompt itself. `stopStream(owner)` retires the token without cancelling that externally owned permission-check coroutine, so this sequence does not require a non-cooperative fake or a hypothetical SDK exception.

The parent added `currentCoroutineContext().ensureActive()` and an owner/generation check immediately after the DAT check, before branching into the request callback (current lines 385–391). Source reinspection confirmed both guards and the retained post-request check at lines 399–400. A retired check now returns before opening the prompt; its `finally` remains fenced from successor cleanup.

## Remaining inspected behavior

- Missing Bluetooth permission returns before the DAT permission check, prompt, or session acquisition in the cold-start path.
- Once a request callback has begun, cancellation/owner fencing after its return prevents a retired grant from acquiring a camera. Failed attempts clean up only their matching generation.
- Old-owner `stopStream(owner)` and legacy `stopStream()` cannot stop a newer owned screen. The parent's existing regression covers that transition.
- Public capture runs in the caller's coroutine, and stream retirement cancels its scope job. Cancellation plus generation/camera-identity checks fence success and error publication; the identity check in `finally` preserves a newer capture's busy marker. No additional important defect found in these paths by source inspection.

## Tests authored versus run

Only the new `android/app/src/test/java/com/smartview/glassai/viewmodels/WearablesLeaseTest.kt` was added; the parent's existing test file and all production files were untouched. It reuses `FakeDatSessionFactory`, `FakeDatDeviceObserver`, `FakeRegistrationGateway` and `TestBitmaps`, with a delegated gateway to delay one real permission-check boundary.

Three tests authored: Bluetooth denial before DAT access; the retired-check/prompt regression; and caller-cancelled capture completing late while another capture is pending. The latter checks no stale bitmap/error, no camera stop, retained ownership, the newer capture's busy marker, and its fresh result.

**Executed by this reviewer: zero.** No Gradle, adb, commits or live hardware checks. The parent reported a genuine pre-fix RED: three targeted tests, one failure in `retiredDatCheckCannotOpenAPermissionPromptOverTheSuccessor`, with production compiled. During closure verification, read-only inspection of the debug `WearablesLeaseTest` XML found three tests and zero failures, timestamp `2026-09-11T13:03:46.490Z`. Full both-variant build/test completion remains with the parent runner; this is not a full-suite pass claim.
