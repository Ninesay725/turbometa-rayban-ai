# DAT SDK 0.9.0 (Android): native abort when `Camera.stop()` races `VideoDecoder.activateDecoder()`

**Status:** DRAFT for the product owner — not posted. Verify the correct tracker first (the DAT
developer portal's support channel or the `facebook/meta-wearables-dat-android` GitHub issues), then
post only after an explicit go-ahead (command at the end).

## Environment
- `com.meta.wearable:mwdat-core` / `mwdat-camera` / `mwdat-mockdevice` **0.9.0**
- Emulator Pixel_5, Android 12 (API 31), x86_64. Not seen in two runs on API 36 (not evidence of absence).
- App: TurboMeta Android 2.0.0 (`android-v2`), Kotlin 2.2.21 / AGP 8.11.1 / compileSdk 36 / minSdk 31

## Symptom
About 1 run in 3, stopping a camera within ~300 ms of `StreamState.STREAMING` kills the whole process:

```
F/MediaCodec: frameworks/av/media/libstagefright/MediaCodec.cpp:815
              CHECK_EQ( mState,UNINITIALIZED) failed: 1 vs. 0
F/libc: Fatal signal 6 (SIGABRT) in tid 5761 (IOScheduler-dup), pid 5544 (com.smartview.glassai)
```

Java stack of the aborting thread — entirely inside the SDK, on its transport thread:

```
android.media.MediaCodec.reset(MediaCodec.java:1984)
  com.meta.wearable.dat.camera.internal.codec.VideoDecoder.activateDecoder
  com.meta.wearable.dat.camera.internal.codec.VideoDecoder.enqueue
  com.meta.wearable.dat.camera.internal.WarpEventCoordinator.handleCodecConfig
  …
  com.meta.wearable.acdc.sdk.fake.FakeLinkedDeviceImpl.processQueuedMessages
```

At the same moment the main thread is inside `Camera.stop()`. The app calls the API exactly as the
official `CameraAccess` sample's `stopStreaming()` does (`camera.stop()`, no prior `stream.stop()`).

## Reproduction (MockDeviceKit, no hardware needed)
1. `Wearables.createSession(AutoDeviceSelector())` → `start()` → wait for `STARTED`.
2. `session.addCamera(StreamConfiguration(VideoQuality.MEDIUM, 24))` → `stream.start()` → wait for `STREAMING`.
3. Within ~300 ms of `STREAMING`, call `camera.stop()` on the main thread.
4. Repeat ten times: the abort shows up in roughly three of them. A 2 s wait before step 3 makes it
   disappear — the SDK re-activates its decoder at about +16 ms, +54 ms and +105 ms after `STREAMING`
   and then stays quiet, which is why the delay hides (but does not fix) the race.

Our instrumented test reproduces it when its settle delay is set to 0:
`android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt`,
`capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams`, constant `STREAM_SETTLE_MS`.

## Expected
`Camera.stop()` should be safe at any time after `stream.start()`; `VideoDecoder.activateDecoder()` /
`MediaCodec.reset()` on the transport thread should be serialized against the stop path.

## Why we believe this is in the SDK
The `MediaCodec` is created, reset and released by SDK-internal classes only; the app never touches
it. Full analysis with timings: `docs/superpowers/reviews/phase-a/task-9-report.md`, Concern 1.

## Posting (product owner only, after explicit approval)
```bash
gh issue create --repo facebook/meta-wearables-dat-android \
  --title "0.9.0: native abort (MediaCodec.reset CHECK_EQ) when Camera.stop() races VideoDecoder.activateDecoder()" \
  --body-file docs/superpowers/reviews/phase-b/dat-sdk-upstream-report.md
```
