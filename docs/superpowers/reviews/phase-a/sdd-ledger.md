# SDD ledger — plan: docs/superpowers/plans/2026-09-10-android-v2-phase-a-sdk-0.9.md

Branch: android-v2 (main checkout; no worktree because android/local.properties is git-ignored and required for the DAT Maven token)
Task 1: started (base 51e9131, implementer opus)
Task 1: implementer DONE_WITH_CONCERNS (commit 35cb7ca; concerns: edge-to-edge under targetSdk 36 + deprecated statusBarColor in Theme.kt:61-62; Compose icon/TabRow deprecations; KT-51221 warnings deferred to Task 4)
Task 1: complete (commits 51e9131..35cb7ca, review clean; ⚠️ branch containment verified by git log on android-v2, build success taken from implementer transcript)
Task 1: minor (deferred): targetSdk 36 enforces edge-to-edge; Theme.kt:61-62 uses deprecated statusBarColor/navigationBarColor — confirm layout under enforced edge-to-edge in a theming task
Task 1: minor (deferred): Compose BOM 2026.05.01 deprecates Icons.Filled.MenuBook/VolumeUp/VolumeDown (ModeSettingsScreen 381/405, QuickVisionScreen 501) and TabRow (RecordsScreen:98)
Task 2: started (base 35cb7ca, implementer sonnet)
Task 2: implementer DONE (commit f4bb252; 0.9.0 artifacts resolved; red build confined to the 4 migrated-later files)
Task 2: complete (commits 35cb7ca..f4bb252, review clean; ⚠️ commit body verified by git log)
Task 2: minor (deferred): MainActivity.initializeSDK()/sdkInitialized naming is stale (plan-mandated snippet) — rename in a cleanup pass
Task 3: started (base f4bb252, implementer opus)
Task 3: implementer DONE (commit 14b10d4; 23 tests green out-of-band; concerns: ensureSession untested, clearStopping self-cancel subtlety, main-thread contract convention-only)
Task 3: review — spec ✅, quality Needs fixes: Important: ensureSession() bypasses the STOPPED gate (GlassesSessionManager.kt:171-176), untested
Task 3: ruling — plan constraint 'exactly 23 tests' vs missing coverage: controller rules a 24th test is added (ensureSessionReturnsFalseWhilePreviousSessionIsStopping) and later count expectations become 24 / 34; plan + briefs 5,6,9,10 updated accordingly
Task 3: minor (deferred): stopSession() overwrites an earlier parked stoppingSession without observing STOPPED (:313-314) — unreachable once ensureSession is gated
Task 3: minor (deferred): awaitStartedResolvesTrueOnStartedAndFalseOnStopped satisfies its false branch via the session==null early return (test:338-350); tautological assertNull(factory.last.nativeSession) at test:409 — plan-mandated
Task 3: minor (deferred): sessionError SharedFlow replay=0/buffer=16 with tryEmit result discarded (:174,196,340) — Tasks 4/5 must subscribe before acquire
Task 3: minor (deferred): clearStopping() cancels the job it runs inside (:253-259); fragile if Phase C adds work after it
Task 3: minor (deferred): unused fake surface in FakeDat.kt:20-24,104 (startCalls/startError/captureResult/frames/errors/nextCaptureResult) — expected to be used by Task 4/5
Task 3: fix round 1/5 started (implementer resumed with the Important finding + 24th test ruling)
Task 3: fix round 1/5 implemented (commit 4fbe40c; 24/24 out-of-band; red-build grep glasses/ = 0) — scoped re-review dispatched
Housekeeping: removed stray untracked .agents/, .codex/config.toml, AGENTS.md (DAT repo install-skills template files created 06:22 by a research agent; not part of the plan)
Task 3: fix round 1/5 (2 addressed, 0 open — ensureSession gate; 24th regression test; commits 14b10d4..4fbe40c)
Task 3: complete (commits f4bb252..4fbe40c, review clean after 1 fix round; ⚠️ adapter has no JVM coverage by design — Task 9 instrumented tests cover it)
Task 4: started (base 4fbe40c, implementer opus)
Task 4: implementer DONE (commit a9a289a; red build confined to the 3 Task-5 files; concerns: disconnect() stops the shared session unconditionally; Paused not yet emitted (Task 6); decoding still on main (Task 6))
Task 4: review — spec ✅, quality Needs fixes: 3 Important (all plan-mandated): (1) stream.start() failure swallowed (WearablesViewModel.kt:421-426, stopStream() overwrites Error, setError never called); (2) no screen renders errorMessage/StreamState.Error — 5 of 10 new strings unreachable; (3) QuickVisionScreen 12 s poll < ViewModel chain (5 s stop-wait + 12 s + 1 s retry)
Task 4: ruling — (1) fix now: setError(localized StreamError) and keep Error state after stopStream; (3) fix now: QuickVisionScreen polls up to 20 s (200×100 ms) and exits early on StreamState.Error; (2) deferred to Task 6 by plan amendment (Task 6 already touches LiveAIScreen/SimpleLiveStreamScreen for Paused UI): surface WearablesViewModel.errorMessage as a Toast + clearError() in LiveAIScreen, QuickVisionScreen, SimpleLiveStreamScreen, HomeScreen
Task 4: minor (deferred): disconnect() calls stopSession() unconditionally (:245-248) — re-check when Task 5 adds QuickVisionService as an owner (pointer for Task 5 dispatch)
Task 4: minor (deferred): hard-coded English at WearablesViewModel.kt:278 and :290 (pre-existing)
Task 4: minor (deferred): early-error paths (:342,:350,:360) leave startJob completed and Error clobbered by a later stopStream(); dead hasBeenActive=false at :405; onCleared no-op cancels (:584-594)
Task 4: fix round 1/5 started (fresh implementer sonnet: findings 1 and 3)
Task 4: fix round 1/5 implemented (commit 3f272ff; findings 1 and 3) — scoped re-review dispatched
Task 4: minor (deferred → Task 6): startStream() success path does not clearError(), so a stale errorMessage could persist across a later successful start
Task 4: fix round 1/5 (2 addressed, 0 open — stream.start() failure surfaced + early-error paths setError; Quick Vision 20 s wait with early exit; commits a9a289a..3f272ff)
Task 4: complete (commits 4fbe40c..3f272ff, review clean after 1 fix round; finding 2 error rendering moved into Task 6 by plan amendment de37fb8)
Task 5: started (base 3f272ff, implementer opus)
Task 5: implementer DONE (commit 8bc45a6; assembleDebug green; 29/29 tests; deviation: capturer retries ensureSessionStarted+addCamera once on NoSession/SessionNotStarted; VideoFrame has a JVM-usable ctor contrary to brief line 172; RTMP frame work still on main)
Task 5: review — spec ✅, quality Approved but 2 Important (plan-mandated): (1) RTMP stream error clobbered: streamErrors sets UIState.Error, then STOPPED → stopStreaming() sets Idle unconditionally (RTMPStreamingViewModel.kt:236-240, :409); (2) capturer retry covers NoSession/SessionNotStarted but not SessionStartResult.NOT_STARTED when a session vanishes during ensureSessionStarted (GlassesPhotoCapturer.kt:99-101)
Task 5: ruling — fix both now in fix round 1: (1) stopStreaming() preserves an existing UIState.Error; (2) capturer also retries once on NOT_STARTED; add a 6th capturer test retriesOnceWhenSessionDisappearsDuringStart → counts become 30 / 35 (plan + briefs 6, 9, 10 updated)
Task 5: minor (deferred → Task 6): cameraErrorMessage duplicated in RTMPStreamingViewModel.kt:266-274 and WearablesViewModel.kt:469-476 — move CameraError mapping into GlassesErrorMessages and use it from both
Task 5: minor (deferred → Task 9): frame-fallback success branch (GlassesPhotoCapturer.kt:158-161) untested; VideoFrame has a JVM ctor so a JVM test is possible
Task 5: minor (deferred): aggregate capture latency unbounded with 2 borrow attempts (~36 s worst case); RTMP can stick in Connecting with no stream budget; RTMP frame path on main (Task 6 scope covers WearablesViewModel + RTMP guard); dead cameraState collection RTMPStreamingScreen.kt:51; getLocalizedString half-migrated in QuickVisionService.kt:451; convertI420toNV21 triplicated (pre-existing)
Task 5: fix round 1/5 started (fresh implementer sonnet)
Task 5: fix round 1/5 implemented (commit ec2cd52; 30/30; assembleDebug green) — scoped re-review dispatched
Task 5: fix round 1/5 (3 addressed, 0 open — RTMP Error preserved through stopStreaming; capturer retries once on NOT_STARTED when session vanished; 6th capturer test; commits 8bc45a6..ec2cd52)
Task 5: complete (commits 3f272ff..ec2cd52, review clean after 1 fix round; assembleDebug green; 30/30 unit tests)
Task 5: minor (deferred): Stop button clearError() is dead code (enabled guard) RTMPStreamingScreen.kt:284-290; stopStreaming() guard leaves Error on onDispose (latent; RTMP route is not save/restore so a fresh VM is created on re-entry)
Task 6: started (base ec2cd52, implementer opus)
Task 6: implementer DONE_WITH_CONCERNS (commit 99e36ee; 35/35; assembleDebug green; concerns: toast clearError races QuickVisionScreen's errorMessage.value read; RTMP VM fields videoWidth/videoHeight/frameTimestampBase cross-thread without @Volatile; feedFrame blocks ≤10 ms on frame worker; no runtime check)
Task 6: review — spec ❌ (2 Important, plan-mandated): (1) addendum toast clearError() runs before QuickVisionScreen's failure branch reads errorMessage.value (QuickVisionScreen.kt:74-78 vs :201) so the Task-4 inline error text is always the generic fallback; (2) RTMP collector on frameDispatcher now races stopStreaming() on Main inside RTMPStreamingService.feedFrame (non-volatile isStreaming/encoder, check-then-use, dequeueInputBuffer blocks ≤10 ms while release() can run) — RTMPStreamingService.kt:73,347-352,404-417
Task 6: ruling — fix both now: (1) QuickVisionScreen keeps a remembered lastGlassesError snapshot written by the toast effect before clearError() and the failure branch reads it; (2) RTMPStreamingService: @Volatile isStreaming/encoder + one lock shared by feedFrame and stopStreaming so release cannot run inside a codec call; also @Volatile on RTMPStreamingViewModel videoWidth/videoHeight/frameTimestampBase (minor 4 folded in). Spec §6 B2 'RTMP feedFrame 加锁' is thereby delivered early.
Task 6: minor (deferred): ghost frame after stopStream (WearablesViewModel.kt:471 vs in-flight worker write) — one-frame window; consider a generation counter
Task 6: minor (deferred): isProcessingFrame guard is unreachable under limitedParallelism(1) + sequential collect — real drop policy is the SDK's; do not rely on it for latency (plan-mandated)
Task 6: minor (deferred): else branch in resId(CaptureError) defeats sealed exhaustiveness (GlassesErrorMessages.kt:86); captureErrorsHaveDistinctStrings hard-codes 4 cases (plan-mandated)
Task 6: minor (deferred): toast block duplicated in 4 screens with redundant LocalContext; double toast possible during nav transitions — extract WearablesErrorToast composable (plan-mandated)
Task 6: minor (deferred): videoJob on frameDispatcher no longer subscribes synchronously before startStream (first frames may be missed; comment overstates)
Task 6: fix round 1/5 started (fresh implementer opus)
Task 6: fix round 1/5 implemented (commit 8e70250; 35/35; assembleDebug green; concern: output loop holds the lock across a 10 ms dequeueOutputBuffer) — scoped re-review dispatched
Task 6: fix round 1/5 (2 addressed, 0 open — lastGlassesError snapshot in QuickVisionScreen; RTMP encoderLock + @Volatile fields; commits 99e36ee..8e70250)
Task 6: complete (commits ec2cd52..8e70250, review clean after 1 fix round; 35/35 unit tests; assembleDebug green)
Task 6: minor (deferred): RTMP input/output codec queues now serialized through encoderLock (≤ ~20 ms/frame worst case) — measure on hardware; narrower lock around dequeueOutputBuffer is the fallback
Task 6: minor (deferred, pre-existing): RTMPStreamingService.stopStreaming() not serialized on rtmpClient (double disconnect possible); output loop busy-spins on codec error state; initEncoder leaks the codec if configure()/start() throws (:194-221); feedFrame(ByteArray) overload is dead code (:329); rtmpClient.disconnect() runs on Main
Task 6: minor (deferred): QuickVisionScreen.kt:209 could read (streamState as? StreamState.Error)?.message directly instead of the snapshot
Task 7: started (base 8e70250, implementer sonnet)
Task 7: implementer DONE (commit 65d6a0e; assembleDebug green; 35/35)
Task 7: complete (commits 8e70250..65d6a0e, review clean)
Task 7: minor (deferred): POST_NOTIFICATIONS dialog appears after monitoring starts (plan-mandated ordering); micGranted seeded once via remember, not re-checked after granting in system Settings (plan-mandated)
Task 8: started (base 65d6a0e, implementer opus)
Task 8: implementer DONE_WITH_CONCERNS (commit 69804bd; debug+release green; 35/35; extra: android/.gitignore un-ignores app/src/release/; paired devices not rehydrated on re-entry; non-persistable SAF URIs; no runtime check)
Task 8: review — spec ✅, quality Approved; 1 Important (plan-mandated): MockDeviceKitViewModel.kt:77-78 surfaces pairGlasses failure via error.toString() instead of the codebase's error.description convention
Task 8: ruling — fix now (one-liner) if MockDeviceKitError exposes description (DatError); otherwise keep toString and record
Task 8: minor (deferred): paired devices not rehydrated from mockDeviceKit.pairedDevices on re-entry (sample-verbatim); clearError() dead; unpairDevice without powerOff; toggles not gated on power state; SAF URIs non-persistable — all brief-verbatim, debug-only
Task 8: fix round 1/5 started (fresh implementer sonnet)
Task 8: fix round 1/5 implemented (commit 6d4297a; error.description; javap: MockDeviceKitError extends DatError) — scoped re-review dispatched
Task 8: fix round 1/5 (1 addressed, 0 open — error.description; commits 69804bd..6d4297a)
Task 8: complete (commits 65d6a0e..6d4297a, review clean after 1 fix round; debug + release assemble green; 35/35)
Task 9: started (base 6d4297a, implementer opus)
Task 9: implementer DONE_WITH_CONCERNS (commit e3cd466; 6/6 instrumented on Pixel_5 API 31 ×3 and API 36; 35/35 unit; ran via adb am instrument because AGP's UTP gRPC ports 9624-9633 are inside Windows' winnat reserved range 9577-9676 — needs admin 'net stop winnat && net start winnat'; SDK-side race VideoDecoder.activateDecoder()/MediaCodec.reset() vs Camera.stop() aborts natively ~1/3 on API 31, test adds 2 s settle; JUnit void-return deviation)
Task 9: review — spec ✅, quality Approved; 2 Important: (1) no Gradle-produced connectedDebugAndroidTest report (Windows winnat port reservation blocks AGP's UTP listener; plan-mandated Step 9.5); (2) GlassesSessionManager singleton has no per-test reset hook (release on unknown owner is a silent no-op)
Task 9: parked — (1) ruling: adb 'am instrument' with the identical Gradle-built APKs (3 consecutive 6/6 runs on API 31 + 6/6 on API 36) accepted as equivalent evidence for Phase A; the machine fix needs an admin shell ('net stop winnat && net start winnat') which is the user's call — surfaced to the user; (2) ruling: real but not load-bearing; a @VisibleForTesting reset hook touches src/main which Task 9 forbids — deferred to the final review / Phase B hygiene
Task 9: complete (commits 6d4297a..e3cd466, 2 parked)
Task 9: SDK finding to track: DAT 0.9.0 VideoDecoder.activateDecoder()/MediaCodec.reset() on the transport thread races Camera.stop() and aborts natively (~1/3 runs on API 31 emulator); test adds a 2 s settle; production risk when a capture starts while another owner's stream is still STARTING then stops — verify on hardware; consider making stopCamera() wait for STREAMING/STOPPED before stop, and report upstream
Task 10: started (base e3cd466, verifier opus; emulator-5554 Pixel_5 running with APKs installed)
Task 10: complete (no code changes; 14 PASS / 0 FAIL / 1 N/A of 15; SDK stop race did not reproduce in ~8 cycles; item 15 confirmed discovery works when the Bluetooth grant arrives after Wearables.initialize)
Task 10: defect D1 (medium): MockDeviceKit screen loses paired-device cards on re-entry while the SDK device stays paired — device becomes uncontrollable until Disable (Task 8 deferred minor now shown to block manual testing); fix: rehydrate from mockDeviceKit.pairedDevices (filterIsInstance<MockGlasses>)
Task 10: incident I1: one ANR during item 9 under host memory pressure (Gradle daemon); stream teardown runs on the main thread — consider moving camera/session stop off the UI thread or bounding it
Task 10: defects D2/D3 (low): mock_device_name resolved via Application context ignores in-app language switch; transient 'Active device: <raw id> (UNKNOWN)' on unpair/disable
Final review (fable): Ready to merge WITH FIXES. 0 Critical; 4 Important: (1) teardownAfterDeviceStop() drops the lent Camera without stop() (GlassesSessionManager.kt:351-358); (2) GlassesPhotoCapturer decodes HEIC on Main (:169-177); (3) RTMPStreamingService Error → Idle swallowed (:146-150,:460; VM :120-124); (4) ViewModels/adapter have no JVM coverage — PHASE-B. Ledger triage: D1 MUST-FIX-BEFORE-MERGE; most minors PHASE-B; several DROP. Report: .superpowers/sdd/2026-09-10-android-v2-phase-a-sdk-0.9/final-review-report.md
Final fix wave started (single implementer opus): Important #1 #2 #3 + D1/D2 rehydrate mock devices + minor #7 CAMERA→debug manifest, #9 device-flow catch, #11 adapter takeIf, #12 remove else in resId(CaptureError); unit tests → 36
Final fix wave: host restarted mid-wave; uncommitted partial work found for items 1, 2, 3(service side), 6 (4 files, +51/-9); emulator gone; resumed with a fresh implementer (opus) to finish items 3(VM side), 4, 5, 7, 8 + verification + commit
Final fix wave: implemented (commit 3433bf0; 36/36 unit; debug+release green; 6/6 instrumented first run; D1 verified on device; concerns: in-app language switch is a no-op on API 31 (pre-existing → Phase B); rehydrated mock cards start with toggles off; items 3b/7 land in untested VM/adapter code) — scoped re-review dispatched
Final fix wave: scoped re-review (opus) — all 8 findings ADDRESSED, no new Critical/Important breakage; residual minor (pre-existing → PHASE-B): RTMPStreamingService.stopStreaming() calls rtmpClient.disconnect() before the Error guard, so Error→Disconnected→Idle can be conflated and the VM maps Disconnected to a generic error; also disable()→enable() does not re-run mock rehydration; device-flow .catch terminates the flow with deviceJob never nulled
PHASE A COMPLETE: android-v2 head 3433bf0 (21 commits from base 1eb6d8a); assembleDebug/assembleRelease green; 36/36 unit; 6/6 instrumented (adb am instrument); manual checklist 14/15 PASS; final review 'with fixes' → fixes landed and re-reviewed clean
