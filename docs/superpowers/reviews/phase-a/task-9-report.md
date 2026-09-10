# Task 9 report — Instrumented tests on the emulator with MockDeviceKit (spec §10 仪器测试)

Branch: `android-v2` · Commit: `e3cd466` · Base: `6d4297a`

## Result in one line

**6/6 instrumented tests pass on `Pixel_5` (API 31) — 3 consecutive full-suite runs — and 6/6 on
`Medium_Phone_API_36.0` (API 36).** Unit tests still 35/35, `assembleDebug` green.

**One deviation that matters:** `./gradlew :app:connectedDebugAndroidTest` cannot run *on this
machine* — AGP's UTP test-results gRPC listener is hardcoded to TCP ports **9624–9633**, and Windows
has reserved **9577–9676**, so all 10 bind attempts fail before any test starts. The tests
themselves were run against the identical Gradle-built APKs via `adb shell am instrument`. Details,
proof, and the one-command fix are in "The Gradle blocker" below.

## What I implemented

All of Steps 9.1–9.6.

1. **Step 9.1 — assets.** `plant.mp4` (2 231 432 bytes, H.265) and `plant.png` (2 347 789 bytes)
   copied from `$SAMPLE/app/src/androidTest/assets/` into
   `android/app/src/androidTest/assets/`. Byte sizes match the brief exactly and md5 matches the
   source (`898f959967986a16491e03e2152e4f4b`, `92bae363c9abcacb6d1fa02357fbdc5c`).
2. **Step 9.2 — test.** `GlassesSessionManagerInstrumentedTest.kt`, the brief's code with the three
   deviations listed below. All six named `@Test`s present.
3. **Step 9.3 — compile.** `./gradlew :app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL in 5s`,
   zero warnings. Every symbol the brief names resolved on the first try; no missing dependency.
4. **Step 9.4 — emulator.** Booted with the brief's exact command (see below).
5. **Step 9.5 — run.** See "Test results".
6. **Step 9.6 — commit.** The brief's exact `git add` / `git commit` command and message.

No `src/main` changes (`git status` before the commit showed only the untracked
`android/app/src/androidTest/`).

### API verification done before writing the test

The task context says the CODE is authoritative if the brief's names drift. I checked every symbol
against the source and the 0.9.0 AARs (`javap`) rather than trusting the brief:

- `GlassesSessionManager`: `acquire/release/ensureSessionStarted/addCamera/stopCamera/stopSession`,
  `sessionState`, `displayState`, `activeDevice`, `isFirmwareUpdateRequired`, `hasSession`,
  `ownerCount`, `currentCameraOwner` — all as the brief assumes.
- `GlassesPhotoCapturer(sessionManager, owner, config, decodePhoto, decodeFrame, …)` — matches;
  `PhotoCaptureOutcome<out T>` is generic, and `Captured` / `CameraUnavailable` are data classes, so
  the brief's `assertEquals` on whole outcomes works.
- `mwdat-core 0.9.0`: `Wearables.getRegistrationState(): StateFlow<RegistrationState>`,
  `RegistrationState.REGISTERED`, `DeviceType.RAYBAN_META`, `DeviceCompatibility.COMPATIBLE` — all
  exist.
- `mwdat-camera 0.9.0`: `StreamConfiguration(VideoQuality, int, boolean)` — I checked the
  synthetic default-args constructor bytecode and **`compressVideo` defaults to `false`**, which is
  what makes `videoFrames.first { !it.isCompressed && !it.isCodecConfig }` able to succeed.
  `VideoFrame.isCompressed/isCodecConfig/width/height` confirmed.
- `mwdat-mockdevice 0.9.0`: members re-confirmed against Task 8's `javap` table.

## Emulator, boot and grant commands

Primary image — **`Pixel_5`, API 31, x86_64, `google_apis`** (`emulator-5554`). The brief's exact
boot command, run from Git Bash:

```bash
"/c/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_5 \
  -no-snapshot-load -no-boot-anim -camera-back virtualscene -camera-front emulated
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
adb devices          # emulator-5554  device
adb shell input keyevent 82
```

I deliberately did **not** use `-no-window`: Task 10 is a manual UI checklist (Live Stream preview,
taps, dialogs), so a headless emulator would break it. `ro.product.cpu.abi` = `x86_64`,
`ro.build.version.sdk` = `31`.

Install + grants (Gradle normally does this; here it is explicit because of the UTP blocker):

```bash
adb install -r -t app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.smartview.glassai android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.smartview.glassai android.permission.CAMERA
```

The universal APK installed on x86_64 without touching `splits.abi` — the brief's
`INSTALL_FAILED_NO_MATCHING_ABIS` fallback was **not** needed, and `app/build.gradle.kts` is
unmodified. The DAT native libraries loaded and ran fine on the x86_64 image; no ARM-translation
problem on either AVD.

Secondary image — **`Medium_Phone_API_36.0`, API 36, x86_64, `google_apis_playstore`**
(booted on `-port 5556`, same flags). Used only to confirm the suite is not API-31-specific; killed
afterwards (see "Emulator left running").

## Test results

Per-test, from the final `Pixel_5` full-suite run (`suite-p5-3.txt`); the run order below is
JUnit's, not declaration order:

| # | Test | Result |
|---|------|--------|
| 1 | `sharedSessionStreamsAndCapturesAPhoto` | PASS |
| 2 | `mockDeviceRegistersAndBecomesTheActiveDevice` | PASS |
| 3 | `displayIsNeverAttachedForANonDisplayCapableDevice` | PASS |
| 4 | `capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams` | PASS |
| 5 | `capturerTakesThePhotoThroughTheSharedSession` | PASS |
| 6 | `capturerFallsBackToVideoFrameWhenPhotoIsUndecodable` | PASS |

```
Time: 5.158
OK (6 tests)
```

Repeatability (the point of the exercise, given the flake described below):

| Image | Full-suite runs | Result |
|---|---|---|
| `Pixel_5` API 31 | 3 consecutive | `OK (6 tests)` each — 5.272 s / 5.497 s / 5.158 s |
| `Medium_Phone_API_36.0` API 36 | 2 consecutive | `OK (6 tests)` each — 8.601 s / 5.253 s |
| `Pixel_5`, isolated `capturerIsRefusedWithCameraBusy…` after the fix | 5 consecutive | 5 PASS / 0 FAIL |

There is **no** `app/build/reports/androidTests/connected/` HTML/XML report, because the Gradle task
never got past starting its results listener (next section). The raw instrumentation transcripts are
the record; they live in the session scratchpad
(`suite-p5-1..3.txt`, `suite-api36-1..2.txt`, `instrument-run1.txt`, `logcat-crash.txt`).

The tests exercise the real SDK through the shared session, as required — logcat from a run shows
the manager's own transitions driving real DAT objects:

```
D/GlassesSessionManager: acquire(WearablesViewModel) owners=[WearablesViewModel] stoppingPrevious=false
D/GlassesSessionManager: session state: IDLE / STARTING / STARTED
D/GlassesSessionManager: addCamera(WearablesViewModel) ok
D/CCodec: allocate(c2.android.hevc.decoder)          <- real HEVC decode of plant.mp4
D/GlassesSessionManager: acquire(QuickVisionService) owners=[WearablesViewModel, QuickVisionService]
W/GlassesSessionManager: addCamera(QuickVisionService) refused: camera held by WearablesViewModel
E/GlassesPhotoCapturer: addCamera refused: CameraBusy(owner=WearablesViewModel)
```

## The Gradle blocker: `connectedDebugAndroidTest` cannot start on this machine

```
> Task :app:connectedDebugAndroidTest
Unable to start the gRPC server.
> Task :app:connectedDebugAndroidTest FAILED
> java.util.concurrent.ExecutionException: java.lang.IllegalArgumentException:
    Unable to start the UTP test results listener gRPC server.
```

This fires **before any test runs** and is unrelated to the test code, the APKs, the emulator or the
DAT SDK. Root cause, established rather than guessed:

1. `--info` shows the bind failing **10 times**: `Failed to bind and start the gRPC server.
   Retrying with a different port number.` ×10. So it is not a one-off port collision.
2. I decompiled AGP 8.11.1
   (`com/android/build/gradle/internal/testing/utp/UtpTestResultListenerServer$Companion`).
   `startServer$default` supplies the defaults **base port `9624` (`sipush 9624`) and `10` attempts
   (`bipush 10`)**, and the loop is `port = basePort + i`, catching only `java.io.IOException`.
   So AGP tries exactly **9624 … 9633** and nothing else. `UtpTestResultListenerServerRunner` has no
   system property, Gradle property or env override — the `bipush 112` default-args mask confirms
   both values are always defaulted.
3. Windows has those ports reserved:
   ```
   $ netsh interface ipv4 show excludedportrange protocol=tcp
     Start Port    End Port
           9577        9676        <-- contains 9624-9633
   ```
4. Direct proof that this is the cause:
   ```
   9624 BIND_FAIL: An attempt was made to access a socket in a way forbidden by its access permissions
   9628 BIND_FAIL: ...
   9633 BIND_FAIL: ...
   9700 BIND_OK
   ```

The `9577–9676` entry is a *dynamic* reservation (no `*`), created at boot by Hyper-V/WSL/Docker's
`winnat`. Clearing it means restarting a system network service from an elevated shell — a
machine-wide system settings change, so **I did not do it**. The user can, in an **admin** shell:

```
net stop winnat
net start winnat
netsh interface ipv4 show excludedportrange protocol=tcp   # confirm 9624-9633 is free
```

then `./gradlew :app:connectedDebugAndroidTest` should work unchanged and produce the HTML report
the brief expects. (The reservation may land on a different range after the next reboot, so this can
also resolve itself.)

**What I did instead**, so the task is genuinely verified rather than skipped: ran the *same* Gradle
artifacts (`app-universal-debug.apk` + `app-debug-androidTest.apk`, built by
`:app:assembleDebugAndroidTest`) through the platform's own runner:

```bash
adb shell am instrument -w -r \
  -e class com.smartview.glassai.glasses.GlassesSessionManagerInstrumentedTest \
  com.smartview.glassai.test/androidx.test.runner.AndroidJUnitRunner
```

This is the same `AndroidJUnitRunner` and the same code UTP would have driven; only Gradle's
result-collection transport is bypassed.

## Deviations from the brief

1. **`capturerIsRefusedWithCameraBusyWhileAnotherOwnerStreams` uses a block body**, not
   `= onMain { … }`. **This was mandatory, not cosmetic.** The brief's expression body makes the
   method's return type the block's last expression — `withTimeout(…) { …first { STOPPED } }`, i.e.
   `DeviceSessionState`. JUnit 4 rejects non-`void` `@Test` methods
   (`FrameworkMethod.validatePublicVoid` → "Method X() should be void"), so the class would have
   failed with an initialization error before running anything. The other five tests are unaffected
   (they end in `assert*` calls, which are `Unit`). I verified the fix at the bytecode level — all
   six methods compile to `public final void`. Bodies and assertions are otherwise the brief's, verbatim.
2. **`delay(STREAM_SETTLE_MS)` (2 s) added after `STREAMING` in that same test**, plus its
   documenting constant. This is the timing adjustment the task context allows; it raises a wait,
   weakens no assertion, and touches only the test. Rationale is a real SDK race — see "Concerns".
3. **`tearDown()` additionally waits for `manager.activeDevice` to return to `null`** (bounded by
   `REGISTRATION_TIMEOUT_MS`, after `unpairDevice` + `disable`). `GlassesSessionManager` is a
   process singleton shared by all six tests; without this, the next test's `awaitActiveDevice()`
   can observe the *previous* test's already-unpaired device, because `activeDevice` is a StateFlow
   whose reset is asynchronous. This is a correctness guard for the shared singleton, not a
   loosened assertion.
4. **Assets step used `md5sum` verification** in addition to the brief's `ls -la` byte-size check.
5. The brief's three "known failure signatures" (no-matching-ABI, `NO_ELIGIBLE_DEVICE`,
   `STREAMING` timeout from a wrong `plant.mp4`) **were all absent**; none of their fixes was applied,
   and `app/build.gradle.kts` is untouched.

## Timing adjustments

Only one: `STREAM_SETTLE_MS = 2_000L` (deviation 2). Every `*_TIMEOUT_MS` constant is the brief's
original value, and no app constant (`GlassesPhotoCapturer.DEFAULT_*`, `GlassesSessionManager`'s
timeouts) was changed.

## Files changed

Created (all under the new `androidTest` source set; nothing else in the repo was modified):

- `android/app/src/androidTest/assets/plant.mp4` (2 231 432 B, byte-identical to the sample)
- `android/app/src/androidTest/assets/plant.png` (2 347 789 B, byte-identical to the sample)
- `android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt` (286 lines)

Commit `e3cd466`: `3 files changed, 286 insertions(+)`.

## Verification summary

| Check | Result |
|---|---|
| `:app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL`, no warnings |
| 6 instrumented tests | `OK (6 tests)` — 3× on Pixel_5 API 31, 2× on API 36 |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; XML parsed: `tests=35 failures=0 errors=0 skipped=0` |
| `:app:assembleDebug` | `BUILD SUCCESSFUL` |
| `src/main` untouched | `git status` showed only untracked `app/src/androidTest/` |
| Assets byte-identical | md5 matches the sample for both files |

## Concerns

1. **A real thread-safety race in DAT SDK 0.9.0 — the most important finding here, and it is not a
   test artifact.** Before the settle delay, the CameraBusy test aborted the whole app process about
   **1 run in 3** on API 31:

   ```
   F/MediaCodec: frameworks/av/media/libstagefright/MediaCodec.cpp:815
                 CHECK_EQ( mState,UNINITIALIZED) failed: 1 vs. 0
   F/libc: Fatal signal 6 (SIGABRT) in tid 5761 (IOScheduler-dup), pid 5544 (artview.glassai)
   ```

   The aborting Java stack is **entirely inside the SDK**, on its own transport thread:

   ```
   android.media.MediaCodec.reset(MediaCodec.java:1984)
     com.meta.wearable.dat.camera.internal.codec.VideoDecoder.activateDecoder
     com.meta.wearable.dat.camera.internal.codec.VideoDecoder.enqueue
     com.meta.wearable.dat.camera.internal.WarpEventCoordinator.handleCodecConfig
     …
     com.meta.wearable.acdc.sdk.fake.FakeLinkedDeviceImpl.processQueuedMessages
   ```

   while the main thread was simultaneously in `GlassesSessionManager.stopCamera` →
   `Camera.stop()`. So `VideoDecoder.activateDecoder()` (handling a codec-config on the transport
   thread) and `Camera.stop()` (app thread) race over the same `MediaCodec`, and the platform kills
   the process. **The app is not misusing the API**: I checked the official CameraAccess sample —
   its `stopStreaming()` also calls `camera.stop()` with no prior `stream.stop()`, exactly like
   `GlassesSessionManager.stopCamera`. The SDK re-activates its decoder several times in the first
   ~300 ms after `STREAMING` (allocations at +16 ms, +54 ms, +105 ms, then quiet), which is why
   letting the stream settle before teardown makes it reliable — but **the delay hides the race, it
   does not fix it**. Production can plausibly hit it: a wake-word capture attempted during Live
   Stream, followed by the user closing Live Stream, is precisely this sequence. Worth raising with
   Meta and worth a defensive look in Phase B. It reproduces on API 31; I did not see it on API 36,
   but two runs is not evidence of absence.
2. **No Gradle-produced test report exists** for this task (see the blocker section). Anyone
   re-running Task 9 on a machine without the port reservation will get the normal
   `app/build/reports/androidTests/connected/debug/index.html`. The pass/fail evidence here comes
   from `am instrument` transcripts in the session scratchpad, which are not committed.
3. **The `GlassesSessionManager` singleton leaks state across tests.** `getInstance()` caches the
   instance for the process, so all six tests share one manager, one `AutoDeviceSelector` and one
   monitoring job, while MockDeviceKit is enabled/disabled per test. Deviation 3 papers over the
   worst symptom (a stale `activeDevice`). If a future test needs true isolation it will need either
   a `@VisibleForTesting` reset hook on the singleton or `AndroidJUnitRunner`'s per-test process
   isolation.
4. **`tearDown()` releases three owner names blindly** (`InstrumentedTest`, `WearablesViewModel`,
   `QuickVisionService`) — the brief's design. `release()` on an unknown owner is a no-op, so this is
   safe, but it means a test that acquires under a fourth name would leak a claim into the next test.
5. **Suite runtime is ~5 s**, far below the generous 10–20 s timeouts, because MockDeviceKit's
   transport is in-process. Those timeouts are therefore untested headroom rather than measured
   bounds; on a slower/loaded CI machine they are the right order of magnitude but unproven.
6. **API 36 coverage is thin** — 2 full-suite runs, no repeated single-test stress. Pixel_5 (API 31)
   is the image I would call verified.

## Emulator left running

**Yes — `Pixel_5` (API 31, x86_64, `google_apis`) is still running as `emulator-5554`**, booted,
unlocked, with both `com.smartview.glassai` and `com.smartview.glassai.test` installed and
`BLUETOOTH_CONNECT` + `CAMERA` granted. Task 10 can skip its Step 10.2.

I killed the API 36 emulator deliberately: Task 10's commands use bare `adb install` / `adb shell`
with no `-s`, which fail with "more than one device/emulator" when two are attached; and Task 10
step 13 needs `adb root`, which works on Pixel_5's `google_apis` image but not on API 36's
`google_apis_playstore` image. I confirmed `adb root` succeeds on `emulator-5554`
(`restarting adbd as root`) and then ran `adb unroot` to hand it over in its default state.
