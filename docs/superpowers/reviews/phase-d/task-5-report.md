Phase D Task 5 — camera, preview and timer

Implementation is complete within the assigned worker scope. The parent reports the first integrated compile accepted these files, all CameraSessionController tests passed, and Camera/AnalysisPhotoRoute wiring now supplies initialPhoto plus owner-scoped permissions/lifecycle/fresh capture. The parent's first 490-test debug run had three failures attributed to other workers. These are parent-reported results, not runs performed by this worker. The accepted owner-aware helpers were also checked read-only; this worker did not edit WearablesViewModel.

Public signatures for parent integration:

```kotlin
@Composable fun CameraScreen(
    wearablesViewModel: WearablesViewModel,
    onBackClick: () -> Unit,
    onAnalyzePhoto: (Bitmap) -> Unit,
    onNutritionPhoto: (Bitmap) -> Unit,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
)

@Composable fun LeanEatScreen(
    viewModel: LeanEatViewModel = viewModel(),
    currentFrame: Bitmap? = null,
    onBackClick: () -> Unit,
    onTakePhoto: () -> Unit,
    initialPhoto: Bitmap? = null,
)

@Composable fun VisionScreen(
    viewModel: VisionViewModel = viewModel(),
    currentFrame: Bitmap? = null,
    onBackClick: () -> Unit,
    onTakePhoto: () -> Unit,
    initialPhoto: Bitmap? = null,
)
```

LeanEat/Vision retain currentFrame for source compatibility but no longer use live frames as analysis input. Parent supplies each fresh capture through initialPhoto. Loading the photo resets old analysis and shows its preview without uploading or starting analysis. Parent owns direct Home LeanEat/Vision route permission, stream lifecycle and capture; no duplicate route lifecycle was added to these two screens.

New production files:

- `android/app/src/main/java/com/smartview/glassai/ui/screens/CameraScreen.kt` — live camera, 1/5/10/15-minute controls, explicit retry/stop, preview/retake, RAM photo callbacks, and user-triggered sharing.
- `android/app/src/main/java/com/smartview/glassai/ui/screens/CameraSessionController.kt` — pure Main-confined timer/start/capture policy with injectable clock and suspending operations.
- `android/app/src/main/java/com/smartview/glassai/ui/screens/CameraViewModel.kt` — destination-scoped preview/controller retention across rotation and analysis handoff.
- `android/app/src/main/java/com/smartview/glassai/ui/screens/CameraPhotoSharing.kt` — temporary JPEG encoding on IO, existing FileProvider, read-only URI grant and ClipData. Only Share creates a file; expired camera-share cache files are pruned during a later explicit share.
- `android/app/src/main/res/values/strings_camera.xml` and `values-zh-rCN/strings_camera.xml` — 18 matching keys. Existing four timer labels, capture, recognition, share, retake and back strings are reused.

Existing production files changed: LeanEatScreen.kt and VisionScreen.kt add the initialPhoto input described above; LeanEatViewModel.kt and VisionViewModel.kt release their image references without recycling the bitmap still held by Camera. Vision also cancels old analysis on reset/retake/disposal and rejects results after cancellation. Other existing behavior and public parameters remain intact.

Camera lifetime: a remembered identity owner is paired with the destination's START/STOP/disposal through LifecycleStartEffect. Its START work calls the parent's suspend startStream(owner, permissionCallback), which checks Bluetooth and DAT camera authorization. False starts and interrupted permission requests require explicit Retry; returning from a permission Activity cannot automatically reopen the prompt. STOP/disposal cancels start, capture and timer jobs before owner-scoped stopStream(owner). A retired lifecycle callback cannot release a newer visit. Current stream errors/stops are observed and require retry.

The default stop budget is five minutes. Choosing 1/5/10/15 starts a fresh deadline without restarting DAT. The deadline begins after authorization launches the stream and includes SDK start/pause time. Expiry and manual stop remain stopped across background/foreground until explicit restart. A capture has a 15-second bound and consumes only the parent's fresh, cancellable capturePhoto(owner) result; no takePhoto return or global capturedPhoto polling is used. Late/canceled work cannot change a successor's preview or busy state. Successful capture stops the stream before the preview is used. The preview survives STOP/START and RAM handoff; Retake starts a new attempt. No bitmap/transcript data uses saved state, automatic storage, or automatic uploads.

Tests authored (none run by this worker):

- `android/app/src/test/java/com/smartview/glassai/ui/screens/CameraSessionControllerTest.kt`: 15 JVM cases covering all timer budgets, deadline reset, cancellation of old timers, owner replacement, denied/interrupted permissions, fresh/immediate photo results, duplicate capture taps, capture timeout, late result rejection, timer/capture overlap, retained preview/retake and explicit restart behavior. Parent reports all passed.
- `android/app/src/androidTest/java/com/smartview/glassai/ui/screens/CameraPhotoInstrumentedTest.kt`: two platform cases for read-only FileProvider JPEG sharing and both analysis ViewModels clearing without recycling the shared preview. The tests do not launch a share target or camera session or invoke cloud analysis, and delete their own temporary share image. Execution is still pending.
- Parent-requested follow-up in existing `android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt`: exactly one method, `ownedWearablesViewModelCapturesFreshPhotoAndReleasesStream`, plus its imports. It uses the existing plant.png fixture and real DAT/MockDeviceKit setup; constructs the public Application-based WearablesViewModel; starts with an identity owner and Granted callback; waits for streaming; calls the fresh capture helper; checks a non-null, non-recycled bitmap distinct from the previous photo and current preview frame; stops that owner and verifies camera claim/owner release and public STOPPED. Finally cleanup stops the owner and clears ViewModelStore, including its long-lived registration/device/error collectors. The existing explicit two-second decoder-settle accommodation is used before capture/stop, with cleanup coverage for an earlier failure. There is no restart attempt, no change to the ignored stress test, and no claim that STOPPED acknowledges private SDK transport teardown. This new method has not been compiled or executed by this worker; parent execution is pending. It exercises SDK + MDK, not physical glasses.

The pure tests were authored before the controller implementation; RED/GREEN was not executed because the user reserves all Gradle runs for the parent. No Gradle, adb, build, test run, or commit was performed by this worker.

Static validation completed: resource XML parsing across both locales, duplicate-name checks, parity for all 18 camera keys, resolution of all 30 CameraScreen string references, owned-file whitespace/diff review, and inspection of the actual parent helper signatures. This is not a compilation or API31 device-pass claim.

Remaining controller verification: run the outstanding integrated variant/platform checks; exercise API31 permission deny/grant/interrupted request, camera START/STOP/rotation, four timer choices and expiry/restart, fresh capture, preview return from both analysis routes, and share chooser with test imagery. Actual glasses behavior and the known DAT rapid-restart transport hang remain hardware-pending.

No Navigation, Home, Settings, WearablesViewModel, QuickVision service, manifest, router or display model edits were made by this worker.
