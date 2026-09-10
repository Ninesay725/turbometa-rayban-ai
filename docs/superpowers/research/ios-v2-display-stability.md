# iOS v2.0.0 (cd26fb2): "Meta Ray-Ban Display 支持" and "稳定性优化" — what they really are, and the Android port list

Repo: `D:/Coding/Workspaces/Android/turbometa-rayban-ai`
Commits analysed: `8df5edc` (DAT 0.4.0 -> 0.5.0 bump), `cd26fb2` (v2.0.0), `2890ad0` (README anchors), `78ea2f3` (Info.plist build vars).
OpenClaw files (`CameraAccess/Services/OpenClaw/*`, `Views/OpenClawChatView.swift`, `Views/OpenClawSettingsView.swift`) are out of scope here (another agent covers them); they are only mentioned where a non-OpenClaw file was touched to wire them in.

---

## 0. TL;DR

1. **"Meta Ray-Ban Display 支持" is a label on the SDK bump, not app code.** The iOS app never imports `MWDATDisplay`, never calls `addDisplay`, never references `DisplayableView`/`Display` capability, and has zero device-type detection or capability gating (grep evidence in §2). Per the upstream iOS CHANGELOG, *Meta Ray-Ban Display glasses support* landed in **DAT iOS 0.4.0** (2026-02-03), which the repo already used since `cacf273`; **0.5.0** (2026-03-11) added `VideoCodec.hvc1`, attestation, `StreamSessionError.thermalCritical`, and removed `@MainActor` from `MWDATCamera` — none of which the app adopts except the new error case string. The only Display-adjacent code change in the whole commit is the `formatStreamingError` switch in `StreamSessionViewModel.swift` (`.audioStreamingError` removed; `.hingesClosed`, `.thermalCritical` added). So "Display support" == "the Display glasses model works as a plain camera device through `AutoDeviceSelector` because the SDK supports it."
2. **"稳定性优化" is 12 concrete, mostly small fixes** (listed in §4): `URLSession.invalidateAndCancel()` on WebSocket disconnect (3 services), `[weak self]` in send/receive closures, session config sent from `didOpenWithProtocol` instead of a 0.5 s `asyncAfter` (2 services), an `AVAudioConverterInputBlock` "provide input once" guard in `LiveTranslateService`, an `NSLock` in `RTMPStreamingService.feedFrame`, `stopStreaming()` in `RTMPStreamingViewModel.deinit`, RTMP stream key moved from `UserDefaults` to Keychain (with migration), per-frame drop-if-busy flag in `StreamSessionViewModel`, `TTSService` no longer tears down/rebuilds `AVAudioEngine` per utterance, `LiveAIManager` polling loops replaced by a cancellable `waitForCondition`, three force-unwrapped `URL(string:)!` replaced by `guard`, and a 0.4 s delay when switching from the photo sheet to the AI-recognition/LeanEat sheet.
3. **One change is a probable regression:** `StreamView.onDisappear` now calls `viewModel.cleanup()` on the *shared* `StreamSessionViewModel` (owned as `@StateObject` by `MainAppView`), which nils all DAT listener tokens and cancels the device monitor. After the user opens and closes the LeanEat/StreamView screen once, LiveAI/QuickVision/RTMP/OpenClaw views that reuse that VM will stop receiving frames/state/`hasActiveDevice` updates (§4.13).
4. **Android is on DAT 0.4.0, which already has Display-glasses support** (`DeviceType.META_RAYBAN_DISPLAY` exists in the cached `mwdat-core-0.4.0.aar`; Android CHANGELOG 0.4.0: "Meta Ray-Ban Display glasses support"). But the Android app **cannot compile against 0.4.0 as committed**: `Wearables.startRegistration`/`startUnregistration` take `android.app.Activity` in 0.4.0 (verified with `javap`), while `WearablesViewModel.kt:192,197` pass `getApplication()` (an `Application`). The 0.3.0 -> 0.4.0 bump (`cacf273`) touched only `libs.versions.toml`, with no Kotlin changes. This must be fixed first (§6.0).
5. Android porting: most iOS stability fixes have **no Android equivalent gap** (OkHttp sends config in `onOpen`, `EncryptedSharedPreferences` already holds the RTMP URL, `onCleared` already stops RTMP, TTS is created once). Real Android gaps: main-thread per-frame JPEG work with no frame skipping (`WearablesViewModel.handleVideoFrame`, `RTMPStreamingViewModel.handleVideoFrame`), non-volatile / unsynchronised `isStreaming` + `encoder` in `RTMPStreamingService.kt`, a fresh `OkHttpClient` per service instance, and the Activity-typed registration call. Details in §6.

---

## 1. Commit inventory

### 1.1 `8df5edc` — "feat: 升级 iOS DAT SDK 至 v0.5.0" (2026-03-29 15:31)
Three one-line edits, no Swift changes:
- `CameraAccess.xcodeproj/project.pbxproj:929` `version = 0.4.0;` -> `0.5.0;` (`XCRemoteSwiftPackageReference "meta-wearables-dat-ios"`, `kind = exactVersion`).
- `README.md:266`, `README_EN.md:205`: "Meta Wearables DAT SDK v0.4.0" -> "v0.5.0".
Note: at this commit the Swift code still contained `case .audioStreamingError:` (`StreamSessionViewModel.swift:276` at 8df5edc) which does not exist in 0.5.0 — the tree at 8df5edc would not compile; cd26fb2 fixes that.

### 1.2 `cd26fb2` — "feat: v2.0.0 — OpenClaw 集成、Meta Ray-Ban Display 支持、稳定性优化" (2026-03-29 20:45)
42 files, +3662/-132. Commit body (verbatim, Chinese kept):
```
- 新增 OpenClaw 集成：WebSocket 连接 Gateway，支持拍照发送、语音转录对话
- 新增阿里云 Fun-ASR 实时语音识别服务
- 新增 Meta Ray-Ban Display 机型支持（DAT SDK v0.5.0）
- 新增 Ed25519 设备签名（OpenClaw Node 模式）
- 修复 WebSocket 服务 URLSession 未 invalidate 导致的内存泄漏
- 修复 RTMPStreamingService 线程安全问题
- 修复视频帧处理内存压力（跳帧优化）
- 修复 TTSService 每次播放重建引擎的问题
- 修复 WebSocket 配置发送时序（改用 didOpen 回调）
- 修复 RTMP 流密钥明文存储（迁移至 Keychain）
- 修复 Sheet 切换竞态条件
- 修复强制解包 URL 崩溃风险
- 更新首页布局：新增 OpenClaw 卡片，LeanEat 移至宽卡片
- 更新设置页面：版本号 2.0.0，SDK 版本 0.5.0
- 更新中英文 README：OpenClaw 配置教程、Tailscale 外网方案、Meta 开发者注册指南
```
Non-OpenClaw files touched (classification summary; per-hunk detail in §3):

| File | Class |
|---|---|
| `.claude/CLAUDE.md`, `.claude/commands/build.md`, `.claude/rules/dat-conventions.md`, `.claude/settings.json`, `.claude/skills/*.md` (7 files) | Unrelated: DAT iOS 0.5.0 ships "AI coding agents config files" (CHANGELOG 0.5.0); these are copied verbatim into the repo. They document the 0.5.0 API (e.g. `try Wearables.shared.startRegistration()` synchronous, `.claude/skills/permissions-registration.md:22-23`). |
| `.gitignore` | Unrelated (ignores `openclaw-ref/`). |
| `CameraAccess.xcodeproj/project.pbxproj` | Build/stability (HaishinKit fork + `SWIFT_STRICT_CONCURRENCY=minimal`) + OpenClaw file refs + **accidental personal signing config**. |
| `Assets.xcassets/AppIcon.appiconset/Contents.json` | UX (adds iPad 20/29/40 @2x icon slots). |
| `Info.plist` | OpenClaw (`NSAllowsLocalNetworking`) + **hardcoded Meta credentials** (reverted in 78ea2f3). |
| `Managers/LiveAIManager.swift` | Stability (cancellable wait helper). |
| `Services/GeminiLiveService.swift` | Stability (URLSession invalidate, weak self). |
| `Services/LiveTranslateService.swift` | Stability (URLSession invalidate, weak self, didOpen config, converter input guard). |
| `Services/OmniRealtimeService.swift` | Stability (URLSession invalidate, weak self, didOpen config). |
| `Services/QuickVisionService.swift`, `Services/VisionAPIService.swift` | Stability (no force-unwrap URL). |
| `Services/RTMPStreamingService.swift` | Stability (NSLock in feedFrame). |
| `Services/TTSService.swift` | Stability (no force-unwrap URL; engine reuse). |
| `TurboMetaApp.swift` | Logging only. |
| `ViewModels/RTMPStreamingViewModel.swift` | Stability (deinit stop) + security (Keychain). |
| `ViewModels/StreamSessionViewModel.swift` | Stability (frame skip) + **SDK-API adaptation (the only "Display" code)**. |
| `ViewModels/WearablesViewModel.swift` | Stability/UX (Task-wrapped registration, richer error text). |
| `Views/MainAppView.swift` | OpenClaw wiring only. |
| `Views/SettingsView.swift` | UX (OpenClaw row, version 2.0.0 / SDK 0.5.0 text). |
| `Views/StreamView.swift` | Stability (sheet race) + **regression risk (cleanup on disappear)**. |
| `Views/TurboMetaHomeView.swift` | UX (OpenClaw card replaces LeanEat in grid; LeanEat becomes wide card) + OpenClaw auto-connect. |
| `en.lproj/Localizable.strings`, `zh-Hans.lproj/Localizable.strings` | UX text: 24 new keys, all OpenClaw/integrations. No Display-related strings. |
| `README.md`, `README_EN.md` | Docs. |

### 1.3 `2890ad0` — "docs: 添加 OpenClaw 教程锚点跳转链接" (20:51)
`README.md:44,51`, `README_EN.md:42,49`: appends `👉 [使用教程](#-openclaw-集成)` / `👉 [Setup Guide](#-openclaw-integration)` to the two OpenClaw bullets. Docs only.

### 1.4 `78ea2f3` — "fix: 移除 Info.plist 中的 Meta App ID 和 ClientToken，改用构建变量" (20:52)
`CameraAccess/Info.plist:44-47`:
```xml
<key>MetaAppID</key>
<string>$(META_APP_ID)</string>        <!-- was 1628869938453260 -->
<key>ClientToken</key>
<string>$(CLIENT_TOKEN)</string>       <!-- was AR|1628869938453260|6d7c18911eba7ac249d31d18870f4993 -->
```
**Risk:** neither `META_APP_ID` nor `CLIENT_TOKEN` is defined anywhere in `project.pbxproj`, `.claude/settings.json`, or any xcconfig (grep returns nothing). Xcode substitutes undefined build settings with empty strings, so a fresh clone builds with `MetaAppID = ""` and registration will fail unless the developer either defines User-Defined build settings or pastes literal values (which is what the README "步骤 1" instructs, `README.md:143-162`). Also: the real App ID / ClientToken remain in git history at `cd26fb2:CameraAccess/Info.plist:44-47` — they should be rotated in the Wearables Developer Center.

---

## 2. Does the iOS app use `MWDATDisplay` / Display capability? — **No.**

Evidence (HEAD):
```
grep -rn "MWDATDisplay|addDisplay|DisplayableView|DisplaySession|displayCapab|import MWDAT" CameraAccess --include=*.swift
```
returns only `import MWDATCore` / `import MWDATCamera` / `import MWDATMockDevice` (`TurboMetaApp.swift:19,23`, `StreamSessionViewModel.swift:17-18`, `WearablesViewModel.swift:17,21`, `Views/*.swift`, `ViewModels/MockDeviceKit/*.swift`). The word "Display" appears only in `MockDisplaylessGlasses` (`MockDeviceViewModel.swift:62,68,75,85`, unchanged since the initial commit), `priceDisplay`, `photoDisplayView`. There is:
- no `DeviceType` / `device.deviceType` check anywhere in the app (`grep -rn "deviceType\|DeviceType" CameraAccess` -> nothing),
- no capability gating, no per-model resolution/codec branch: `StreamSessionViewModel.swift:88-92` builds one `StreamSessionConfig(videoCodec: VideoCodec.raw, resolution: <low|medium|high from UserDefaults "video_quality">, frameRate: 24)` for whatever `AutoDeviceSelector(wearables:)` picks (`:72`),
- no new UI for a display, no new strings (all 24 new `Localizable.strings` keys are `openclaw.*`, `settings.integrations`, `home.openclaw.*`).

What the SDK actually provides (upstream `mwdat-ios-marketplace/CHANGELOG.md`):
- **0.4.0 (2026-02-03):** "Meta Ray-Ban Display glasses support.", `hingesClosed` in `StreamSessionError`, `UnregistrationError`, `networkUnavailable`, `WearablesHandleURLError`, `MWDATCore` types `Sendable`, fixes for streaming status races.
- **0.5.0 (2026-03-11):** `VideoCodec.hvc1` (background-continuing HEVC), app attestation, `StreamSessionError.thermalCritical`, AI agent config files; removed `@MainActor` requirement from `MWDATCamera`, `HingeState`, `DeviceState`, `nanopb`; fixed high-res 720x1280 request; sample photo flow.
- The `MWDATDisplay` module itself only appears in **0.7.0**; the app pins `exactVersion 0.5.0`, so it could not use it even if it wanted to.

Version-dependency table (docs export lines 3577-3658): DAT 0.4.0 and 0.5.0 both require Meta AI app V254 and **Meta Ray-Ban Display firmware V21**; the only difference is Ray-Ban Meta firmware V20 -> V22. So upgrading 0.4.0 -> 0.5.0 did not change which Display firmware works.

**Conclusion:** the marketing bullet "新增 Meta Ray-Ban Display 机型支持（DAT SDK v0.5.0）" means "Display glasses stream video/photos like any other model via `AutoDeviceSelector`". The one code adaptation needed by the bump is the `StreamSessionError` switch (§3, `StreamSessionViewModel.swift:267-288`).

---

## 3. Hunk-by-hunk classification (non-OpenClaw files)

Line numbers are post-commit (HEAD) unless noted.

### 3.1 `CameraAccess/ViewModels/StreamSessionViewModel.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:66` `private var isProcessingFrame = false`; `:111-126` `videoFramePublisher.listen { ... Task { @MainActor [weak self] in guard let self, !self.isProcessingFrame else { return }; self.isProcessingFrame = true; defer { self.isProcessingFrame = false }; if let image = videoFrame.makeUIImage() { self.currentVideoFrame = image ... } } }` | **Stability — "修复视频帧处理内存压力（跳帧优化）"** | Before: every frame spawned a `Task { @MainActor }` that ran `videoFrame.makeUIImage()`; if conversion is slower than 24 fps the main-actor task queue grows without bound (each pending task retains its `VideoFrame`) -> memory pressure. After: while one frame is being converted, subsequent frames are dropped at the door (flag is main-actor isolated, so the check-then-set is race-free). |
| `:267-288` `formatStreamingError(_ error: StreamSessionError)`: removed `case .audioStreamingError`, added `case .hingesClosed: "Glasses hinges are closed. Please open them to continue."` and `case .thermalCritical: "Device temperature is too high. Streaming paused."` | **SDK adaptation (the only Display-adjacent code)** | `hingesClosed` is a 0.4.0 API, `thermalCritical` is 0.5.0. `.audioStreamingError` no longer exists (the 8df5edc tree didn't compile). Strings are English-only (not localized). |
| `:290-303` `func cleanup() async` | unchanged (pre-existing since initial commit); newly *called* from StreamView — see §3.2. |

### 3.2 `CameraAccess/Views/StreamView.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:82-86` `.onDisappear { Task { await viewModel.cleanup() } }` (was `if viewModel.streamingStatus != .stopped { await viewModel.stopSession() }`) | **Stability intent / regression risk** | `cleanup()` stops the session, cancels `deviceMonitorTask`, and sets all four `AnyListenerToken`s to nil (`StreamSessionViewModel.swift:293-301`). The VM is shared (`MainAppView.swift:23,31` `@StateObject private var streamViewModel`; `TurboMetaHomeView.swift:133-153` passes the same instance to LiveAIView, SimpleLiveStreamView, RTMPStreamingView, StreamView (presented by the LeanEat card, `:142-144`), QuickVisionView, LiveTranslateView, OpenClawChatView). Subscriptions are only created in `init` (`:104-150`); nothing re-subscribes. Before cd26fb2, `cleanup()` had **no callers** (`git grep cleanup 8df5edc -- CameraAccess/Views` is empty). See §4.13. |
| `:95-107` `onAIRecognition: { viewModel.showPhotoPreview = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { viewModel.showVisionRecognition = true } }` and same for `onLeanEat` -> `showLeanEat` | **Stability — "修复 Sheet 切换竞态条件"** | SwiftUI cannot present a second `.sheet` while the first is still dismissing ("Attempt to present ... while a presentation is in progress"); the second sheet silently never appears. Fix is a 0.4 s delay so the dismissal animation finishes. Pure SwiftUI presentation issue. |

### 3.3 `CameraAccess/Services/GeminiLiveService.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:147-155` `disconnect()`: after `webSocket?.cancel(with: .goingAway, reason: nil); webSocket = nil` adds `urlSession?.invalidateAndCancel(); urlSession = nil` | **Stability — "修复 WebSocket 服务 URLSession 未 invalidate 导致的内存泄漏"** | `URLSession(configuration:delegate:delegateQueue:)` (`:137` pre-commit) retains its delegate (`self`) strongly until `invalidateAndCancel()`/`finishTasksAndInvalidate()` is called. Without it, every `connect()` leaked one `URLSession` + the service object. |
| `:326-331` `webSocket?.send(message) { [weak self] error in ... self?.onError?(...) }` | Stability (retain-cycle hygiene) | Completion handler previously captured `self` strongly. |
| `:407-409` `DispatchQueue.main.async { [weak self] in guard let self else { return } ...` in `handleServerEvent` | Stability | Same. Session config was already sent from `didOpenWithProtocol` here (pre-existing), and the `hasProvidedInput` converter guard already existed (`8df5edc:GeminiLiveService.swift:275-283`). |

### 3.4 `CameraAccess/Services/LiveTranslateService.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:141-143` removed `DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.configureSession() }` after `receiveMessage()`; `:610-615` `didOpenWithProtocol` now does `DispatchQueue.main.async { self.configureSession() }` | **Stability — "修复 WebSocket 配置发送时序（改用 didOpen 回调）"** | Fixed 0.5 s guess could fire before the socket was open (config lost -> server never emits `session.updated`, audio ignored) or waste time. Now `session.update` is sent exactly when `URLSessionWebSocketDelegate.urlSession(_:webSocketTask:didOpenWithProtocol:)` fires. |
| `:146-153` `disconnect()` adds `urlSession?.invalidateAndCancel(); urlSession = nil` | Stability (leak) | as §3.3 |
| `:321-333` in the resampling path: `var hasProvidedInput = false; let inputBlock: AVAudioConverterInputBlock = { inNumPackets, outStatus in if hasProvidedInput { outStatus.pointee = .noDataNow; return nil }; hasProvidedInput = true; outStatus.pointee = .haveData; return inputBuffer }` | **Stability (audio correctness)** — not in commit message | `AVAudioConverter.convert(to:error:withInputFrom:)` calls the input block repeatedly until the output buffer is full; returning the same buffer with `.haveData` every time duplicates/loops the input chunk (garbled or repeated audio, over-long output). The guard hands the buffer over once and then signals `.noDataNow`. Gemini already had this pattern; `OmniRealtimeService` has no `AVAudioConverter` at all (records at 24 kHz natively). |
| `:404-410` `[weak self]` in send completion; `:468-471` `[weak self] guard let self` in main-queue event handler | Stability | as §3.3 |

### 3.5 `CameraAccess/Services/OmniRealtimeService.swift`
Same three changes as LiveTranslate minus the converter guard: `:167-169` removed 0.5 s `asyncAfter` config; `:172-179` `disconnect()` invalidates `urlSession`; `:308-314` `[weak self]` send; `:386-389` `[weak self]` handler; `:566-571` `didOpenWithProtocol` -> `DispatchQueue.main.async { self.configureSession() }`.

### 3.6 `CameraAccess/Services/RTMPStreamingService.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:49` `private let lock = NSLock()`; `:172-181` `feedFrame`: `lock.lock(); let streaming = isStreaming; let stream = rtmpStream; if streaming { totalFrames += 1 }; lock.unlock(); guard streaming, let stream else { return }` | **Stability — "修复 RTMPStreamingService 线程安全问题"** | `feedFrame` is called from the video listener (arbitrary thread; DAT 0.5.0 dropped `@MainActor` from `MWDATCamera`), while `isStreaming`/`rtmpStream`/`totalFrames` are mutated on `MainActor` by `handleConnectionStatus`/`handleStreamStatus`/`stopStreaming` (`:128-169, 271-306`). The class is `@unchecked Sendable` (`:40`). The lock makes the snapshot + counter increment atomic. Note: only `feedFrame` takes the lock; the writers (`stopStreaming` `:131`, `:146-147`, `:162`; `createStreamAndPublish` `:258`) still write without it — the fix is partial (a torn read is now impossible, but writes aren't serialized against each other). |

### 3.7 `CameraAccess/ViewModels/RTMPStreamingViewModel.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:8` `import Security`; `:129-132` `deinit { statsTimer?.invalidate(); streamingService.stopStreaming() }` | Stability | Guarantees RTMP connection/encoder tasks are torn down when the VM dies (previously only the timer). |
| `:289-296` `saveSettings()` no longer writes `rtmp_stream_key` to `UserDefaults`; calls `saveStreamKeyToKeychain(streamKey)`. `:298-316` `loadSavedSettings()` reads `streamKey = loadStreamKeyFromKeychain() ?? ""`, then migrates: if `UserDefaults.standard.string(forKey: "rtmp_stream_key")` is non-empty -> save to Keychain, adopt if current empty, `removeObject(forKey:)`. `:318-336` `saveStreamKeyToKeychain` (`kSecClassGenericPassword`, `kSecAttrService = "com.smartview.glassai.rtmp"`, `kSecAttrAccount = "stream_key"`; `SecItemDelete` then `SecItemAdd` unless empty). `:338-352` `loadStreamKeyFromKeychain` via `SecItemCopyMatching` with `kSecReturnData: true`. | **Security/stability — "修复 RTMP 流密钥明文存储（迁移至 Keychain）"** | Keys that remain in `UserDefaults`: `rtmp_url`, `rtmp_platform`, `rtmp_bitrate`. |

### 3.8 `CameraAccess/Services/TTSService.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:190-193` `guard let url = URL(string: baseURL) else { throw TTSError.invalidResponse }` (was `URL(string: baseURL)!`) | Stability — "修复强制解包 URL 崩溃风险" | Defensive only; `baseURL` is a constant. |
| `:225-241` replaced `configureAudioSession(); stopPlaybackEngine(); setupPlaybackEngine(); startPlaybackEngine()` with `playerNode?.stop(); playerNode?.reset(); if !isPlaybackEngineRunning { startPlaybackEngine() }; playerNode?.play()` | **Stability — "修复 TTSService 每次播放重建引擎的问题"** | Previously every utterance detached/re-created `AVAudioEngine` + `AVAudioPlayerNode` and re-set the `AVAudioSession` category, which is slow and produced audio-session churn (can steal the session from Live AI / cause first-chunk drop-outs). Now the engine built in `init` (`:39-42`, `setupPlaybackEngine()`) is reused; `startPlaybackEngine()` (`:84-96`) still configures the session the first time. |

### 3.9 `CameraAccess/Services/QuickVisionService.swift:131-134`, `CameraAccess/Services/VisionAPIService.swift:120-123`
`let url = URL(string: "\(baseURL)/chat/completions")!` -> `guard let url = URL(string: ...) else { throw QuickVisionError.invalidResponse }` / `throw VisionAPIError.invalidImage`. Stability (defensive). `baseURL` comes from `APIProviderManager` (Alibaba Beijing/Singapore or OpenRouter constants), so the crash was theoretical.

### 3.10 `CameraAccess/Managers/LiveAIManager.swift`
| Hunk | Class | Detail |
|---|---|---|
| `:103-105` `let streamReady = await waitForCondition(timeout: 5.0) { streamViewModel.streamingStatus == .streaming }`; `:124-126` `let connected = await waitForCondition(timeout: 10.0) { self.isConnected }`; `:416-425` `private func waitForCondition(timeout: TimeInterval, condition: @escaping () -> Bool) async -> Bool { let deadline = Date().addingTimeInterval(timeout); while !condition() { if Date() >= deadline { return false }; try? await Task.sleep(nanoseconds: 100_000_000); if Task.isCancelled { return false } }; return true }` | Stability (refactor) | Replaces two `while ... < 50 { try await Task.sleep }` counters. Behavioural differences: deadline is wall-clock (immune to sleep overrun), cancellation returns `false` instead of throwing `CancellationError` out of the `try await Task.sleep`, and the helper is reusable. Class is `@MainActor` (`:12`), so reading `streamingStatus` is safe. |

### 3.11 `CameraAccess/ViewModels/WearablesViewModel.swift:104-125`
`connectGlasses()` / `disconnectGlasses()` now `Task { do { try await wearables.startRegistration() } catch { let msg = "Registration failed: \(error) | \(error.localizedDescription)"; print("❌ [WearablesVM] \(msg)"); showError(msg) } }` (and `startUnregistration()` with `showError(error.localizedDescription)`). Class: UX/diagnostics. Note: in DAT 0.5.0 `startRegistration()` is synchronous throwing (`.claude/skills/permissions-registration.md:22-23`, docs "Step 4"); `try await` on it compiles with a warning ("no 'async' operations occur"). It becomes truly async in later SDKs (0.9.0 sample `WearablesViewModel.swift:107` uses `try await`). Net effect: error text now includes the raw error value instead of `error.description`.

### 3.12 `CameraAccess/TurboMetaApp.swift:35-41`
`Wearables.configure()` success now `print("✅ ... configured successfully")`; failure prints `"❌ [TurboMeta] Wearables.configure() failed: \(error) | \(error.localizedDescription)"` in all builds (was `NSLog` under `#if DEBUG`). Logging only.

### 3.13 `CameraAccess/Views/TurboMetaHomeView.swift`
| Hunk | Class |
|---|---|
| `:21-22` `@State showOpenClaw`, `@ObservedObject openClawService = OpenClawNodeService.shared`; `:86-93` grid slot 4 becomes `FeatureCard(title: "OpenClaw", subtitle: connected ? "home.openclaw.connected" : "home.openclaw.subtitle", icon: "link.circle.fill", gradient: [.purple, .indigo]) { showOpenClaw = true }`; `:117-124` new "Row 5 - LeanEat" `FeatureCardWide(title: "home.leaneat.title", subtitle: "home.leaneat.subtitle", icon: "chart.bar.fill", gradient: [AppColors.leanEat, ...]) { showLeanEat = true }`; `:151-153` `.fullScreenCover(isPresented: $showOpenClaw) { OpenClawChatView(streamViewModel:) }`; `:161-165` auto-connect if `openClawService.loadGatewayToken() != nil` | UX — "更新首页布局：新增 OpenClaw 卡片，LeanEat 移至宽卡片" (+ OpenClaw wiring) |

### 3.14 `CameraAccess/Views/SettingsView.swift`
`:26` `@State showOpenClawSettings`; `:307-333` new `Section` header `"settings.integrations".localized` with an "OpenClaw" row showing a status dot (`openClawStatusColor`: `.connected -> .green`, `.connecting -> .orange`, `.waitingForPairing -> .yellow`, else `.gray`) and text (`openclaw.status.connected|connecting|disconnected`); `:337-338` `InfoRow(title: "settings.version", value: "2.0.0")` (was `1.5.0`), `InfoRow(title: "settings.sdkversion", value: "0.5.0")` (was `0.3.0` — it had been stale through the 0.4.0 bump); `:398-400` `.sheet(isPresented: $showOpenClawSettings) { OpenClawSettingsView() }`; `:408-424` the two computed status properties. UX text.

### 3.15 `CameraAccess/Views/MainAppView.swift:49-57`
OpenClaw only: builds `OpenClawCommandRouter(streamViewModel:)`, `OpenClawNodeService.shared.setCommandRouter(router)`, auto `connect()` if `isEnabled`.

### 3.16 `CameraAccess/Info.plist` (cd26fb2 state, then 78ea2f3)
- `:44-47` cd26fb2 replaced `MetaAppID = 0` / `ClientToken = $(CLIENT_TOKEN)` with the literal App ID / ClientToken (credential leak); 78ea2f3 changed to `$(META_APP_ID)` / `$(CLIENT_TOKEN)` (undefined build settings — see §1.4). `TeamID = $(DEVELOPMENT_TEAM)` unchanged. `AppLinkURLScheme = turbometa://` unchanged.
- `:52-55` new `NSAppTransportSecurity { NSAllowsLocalNetworking = true }` — needed for OpenClaw `ws://` LAN connections (OpenClaw scope).

### 3.17 `CameraAccess.xcodeproj/project.pbxproj`
| Hunk | Class |
|---|---|
| `:952-958` HaishinKit dependency changed from `https://github.com/shogo4405/HaishinKit.swift` `upToNextMajorVersion 2.0.0` to fork `https://github.com/Turbo1123/HaishinKit.swift` `kind = branch; branch = "fix-concurrency"` | Build stability (Swift-6 strict-concurrency compile errors in HaishinKit 2.x; RTMPStreamingService is `@unchecked Sendable` for the same reason). Not a runtime fix. |
| `:859`, `:914` `SWIFT_STRICT_CONCURRENCY = minimal;` added to project Debug/Release; `:768`, `:795` removed `SWIFT_UPCOMING_FEATURE_MEMBER_IMPORT_VISIBILITY = YES;` from the test targets | Build (silences concurrency diagnostics; drops the member-import-visibility upcoming feature) |
| `:694` `CODE_SIGN_IDENTITY = "Apple Development"`, `:698` `DEVELOPMENT_TEAM = 4CXJZWV2GC`, `:709` `PRODUCT_BUNDLE_IDENTIFIER = com.glassai.app1` (was `com.smartview.glassai.app`), `:728` `DEVELOPMENT_TEAM = 2X7K2P6UKK` (tests) | **Unrelated / accidental** — personal signing config and a changed bundle id committed to the public repo. The bundle id must match the Wearables Developer Center app config; changing it to `com.glassai.app1` is coupled to the leaked App ID. `MARKETING_VERSION` stays `1.4.0` (`:708`) even though Settings shows 2.0.0. |
| OpenClaw `PBXBuildFile`/`PBXFileReference`/group entries | OpenClaw |

### 3.18 `Localizable.strings` (en + zh-Hans), 24 keys each, all OpenClaw
`settings.integrations` ("Integrations"/"集成"), `openclaw.status.connected|connecting|pairing|disconnected`, `openclaw.connect`, `openclaw.disconnect`, `openclaw.pairing.hint`, `openclaw.gateway.help`, `openclaw.capabilities`, `openclaw.capabilities.desc`, `home.openclaw.subtitle` ("OpenClaw"), `home.openclaw.connected` ("Connected"/"已连接"), `openclaw.chat.placeholder|photoprompt|snap|sending|noframe|noapikey|sendvoice|text|listening`. Nothing for Display glasses; the new `hingesClosed`/`thermalCritical` messages are hardcoded English.

### 3.19 `README.md` / `README_EN.md` (cd26fb2)
- Header block rewritten from v1.5.0 to v2.0.0 (verbatim in §5).
- iOS build section: "本项目不提供预编译安装包（IPA）下载"; new "步骤 1：注册 Meta Wearables 开发者" (create project at wearables.developer.meta.com -> App configuration -> *Application ID integration -> iOS integration* -> copy `MetaAppID` and `ClientToken` into the `MWDAT` dict of `Info.plist`), "步骤 2：编译运行".
- Android section adds: "⚠️ Android 版本目前停留在 v1.5.0，暂未包含 v2.0 的 OpenClaw 集成和 Meta Ray-Ban Display 支持。" / "⚠️ Android is currently at v1.5.0 and does not yet include v2.0 features (OpenClaw, Meta Ray-Ban Display)."
- Hardware requirement: "RayBan Meta 智能眼镜（Stories 或最新款）" -> "Ray-Ban Meta 智能眼镜 或 **Meta Ray-Ban Display**（新增支持）" (`README.md:316`, `README_EN.md:252`).
- New "🔗 OpenClaw 集成" section (install, `gateway.bind = "lan"`, `nodes.allowCommands`, pairing, Tailscale).
- Roadmap: done list adds "OpenClaw 集成", "Meta Ray-Ban Display 支持", "DAT SDK v0.5.0 升级", "阿里云实时语音识别（Fun-ASR）"; in-progress adds "OpenClaw Node 模式（AI 主动调用眼镜拍照）", "Android v2.0 更新".

---

## 4. Stability fixes — mechanism summary (for porting)

| # | Fix (commit wording) | iOS mechanism | Root cause |
|---|---|---|---|
| 4.1 | WebSocket URLSession 未 invalidate 内存泄漏 | `urlSession?.invalidateAndCancel(); urlSession = nil` in `disconnect()` of Gemini/Translate/Omni | `URLSession` with a delegate retains the delegate until invalidated. |
| 4.2 | (implicit) retain cycles | `[weak self]` in `webSocket.send` completion and in `DispatchQueue.main.async` event handlers | Closures kept the service alive past `disconnect()`. |
| 4.3 | WebSocket 配置发送时序 | `configureSession()` from `didOpenWithProtocol` (Translate, Omni); Gemini already did | 0.5 s `asyncAfter` guess could precede socket open. |
| 4.4 | (not in message) converter loop | `hasProvidedInput` guard in `AVAudioConverterInputBlock` (LiveTranslate) | Input block re-supplied the same buffer -> duplicated audio. |
| 4.5 | RTMPStreamingService 线程安全 | `NSLock` snapshot of `isStreaming`/`rtmpStream` + `totalFrames++` in `feedFrame` | Cross-thread reads vs `MainActor` writes. |
| 4.6 | (implicit) RTMP teardown | `RTMPStreamingViewModel.deinit` -> `streamingService.stopStreaming()` | VM deallocation left connection/tasks alive. |
| 4.7 | RTMP 流密钥明文存储 | Keychain generic password `com.smartview.glassai.rtmp`/`stream_key`, `UserDefaults` migration | Secret in plist-backed prefs. |
| 4.8 | 视频帧处理内存压力（跳帧） | `isProcessingFrame` main-actor flag drops frames while converting | Unbounded per-frame `Task` backlog. |
| 4.9 | TTSService 每次播放重建引擎 | keep `AVAudioEngine`, `playerNode.stop()/reset()`, start engine only if not running | Engine/session churn per utterance. |
| 4.10 | (implicit) LiveAI wait loops | `waitForCondition(timeout:)` with deadline + cancellation | Counter loops threw on cancel, no reuse. |
| 4.11 | 强制解包 URL 崩溃风险 | `guard let url = URL(string:)` in QuickVision/TTS/VisionAPI | `!` unwrap. |
| 4.12 | Sheet 切换竞态条件 | 0.4 s delay before presenting 2nd sheet | SwiftUI presentation-in-progress. |
| 4.13 | (intended: resource cleanup) | `StreamView.onDisappear -> cleanup()` | **Regression risk**: shared VM loses all DAT subscriptions after StreamView closes; `LiveAIManager.waitForCondition` will then time out with `streamNotReady`, `OpenClawCommandRouter.swift:111,137,139` reads stale `hasActiveDevice`/`streamingStatus`, `hasActiveDevice` freezes. Recommended iOS fix: revert to `stopSession()` in `onDisappear` and call `cleanup()` only from the owner (`MainAppView`) when the VM is discarded, or make `cleanup()` re-subscribable. |

---

## 5. README v2.0.0 changelog section (verbatim, HEAD)

`README.md:30-58`:
```
## 🎉 重磅更新 v2.0.0

<div align="center">

### 🔗 OpenClaw 集成 + Meta Ray-Ban Display 支持

**语音对话、拍照识别、OpenClaw AI 助手 - 你的眼镜，连接一切！**

✅ **iOS v2.0.0** | 📱 **Android v1.5.0**

☕ **喜欢这个项目？** [**请我喝杯咖啡**](#-请我喝杯咖啡) 支持开发！

</div>

### 🆕 v2.0 新功能

- 🔗 **OpenClaw 集成**：将眼镜连接到 [OpenClaw](https://openclaw.ai) AI 助手，支持拍照发送、语音转录对话 👉 [使用教程](#-openclaw-集成)
- 🕶️ **Meta Ray-Ban Display 支持**：新增对 Meta Ray-Ban Display 机型的支持（DAT SDK v0.5.0）
- 🎙️ **阿里云实时语音识别**：OpenClaw 聊天支持 Fun-ASR 语音转文字
- 🛡️ **稳定性提升**：修复多个内存泄漏和线程安全问题

### 🎯 核心功能

- 🔗 **OpenClaw AI 助手**：连接 OpenClaw Gateway，通过眼镜拍照与 AI 对话 👉 [查看配置教程](#-openclaw-集成)
- 🎬 **RTMP 直播推流**：支持推流到任意 RTMP 平台，YouTube、Twitch、B站、抖音、TikTok、Facebook Live 等
- 👁️ **Quick Vision 快速识图**：Siri 语音唤醒，无需解锁手机即可识别眼前物体
- 🤖 **Live AI 实时对话**：通过眼镜摄像头和麦克风进行多模态实时 AI 对话
- 🍽️ **LeanEat 营养分析**：拍照即可获得食物营养成分和健康评分
- 🌐 **实时翻译**：18 种语言互译
```

`README_EN.md:26-53`:
```
## 🎉 Major Update v2.0.0

<div align="center">

### 🔗 OpenClaw Integration + Meta Ray-Ban Display Support

**Voice chat, photo recognition, OpenClaw AI assistant — your glasses, connected to everything!**

✅ **iOS v2.0.0** | 📱 **Android v1.5.0**

☕ **Enjoying this project?** [**Buy me a coffee**](https://buymeacoffee.com/turbo1123) to support development!

</div>

### 🆕 v2.0 New Features

- 🔗 **OpenClaw Integration**: Connect your glasses to [OpenClaw](https://openclaw.ai) AI assistant — snap photos & voice chat 👉 [Setup Guide](#-openclaw-integration)
- 🕶️ **Meta Ray-Ban Display Support**: Added support for Meta Ray-Ban Display glasses (DAT SDK v0.5.0)
- 🎙️ **Real-time Speech Recognition**: OpenClaw chat supports Alibaba Fun-ASR voice-to-text
- 🛡️ **Stability Improvements**: Fixed memory leaks and thread safety issues

### 🎯 Core Features

- 🔗 **OpenClaw AI Assistant**: Connect to OpenClaw Gateway, chat with AI using glasses photos 👉 [Setup Guide](#-openclaw-integration)
- 🎬 **RTMP Live Streaming**: Stream to any RTMP platform — YouTube, Twitch, Bilibili, Douyin, TikTok, Facebook Live, etc.
- 👁️ **Quick Vision**: Siri voice activation - identify objects without unlocking your phone
- 🤖 **Live AI**: Real-time multimodal AI conversation via glasses camera and microphone
- 🍽️ **LeanEat**: Take a photo to get nutrition analysis and health scores
```

---

## 6. Android porting list

Android baseline: `android/gradle/libs.versions.toml:4` `mwdat = "0.4.0"` (`mwdat-core`, `mwdat-camera`; mockdevice commented out in `app/build.gradle.kts:84-86`), `versionName = "1.5.0"` (`build.gradle.kts:16`), package `com.smartview.glassai`. Kotlin code was written against 0.3.0 (`863221a:libs.versions.toml:4`); no Kotlin file changed after the 0.4.0 bump (`git log cacf273..HEAD -- android` is empty).

Verified 0.4.0 API surface (javap on the Gradle-cached AARs `~/.gradle/caches/modules-2/files-2.1/com.meta.wearable/mwdat-core/0.4.0/.../mwdat-core-0.4.0.aar` and `mwdat-camera-0.4.0.aar`):
- `com.meta.wearable.dat.core.Wearables`: `initialize(Context): DatResult`, `startRegistration(android.app.Activity)`, `startUnregistration(android.app.Activity)`, `registrationState: StateFlow<RegistrationState>` (sealed: `Available|Registered|Registering|Unavailable|Unregistering`), `devices: StateFlow<Set<DeviceIdentifier>>`, `devicesMetadata: Map<DeviceIdentifier, StateFlow<DeviceMetadata>>`, `getDeviceSessionState(DeviceIdentifier): StateFlow<SessionState>`, `checkPermissionStatus(Permission): DatResult<PermissionStatus, PermissionError>`.
- `DeviceMetadata(name: String, available: Boolean, deviceType: DeviceType, firmwareInfo: String, compatibility: DeviceCompatibility)`; `DeviceType { UNKNOWN, RAYBAN_META, OAKLEY_META_HSTN, OAKLEY_META_VANGUARD, META_RAYBAN_DISPLAY }` with `description`; `DeviceCompatibility { UNDEFINED, COMPATIBLE, DEVICE_UPDATE_REQUIRED, SDK_UPDATE_REQUIRED }`.
- `AutoDeviceSelector(comparator: Comparator<DeviceMetadata>, filter: (DeviceMetadata) -> Boolean)` with defaults; `activeDevice(Flow<Set<DeviceIdentifier>>): Flow<DeviceIdentifier?>`.
- `com.meta.wearable.dat.camera`: `Wearables.startStreamSession(Context, DeviceSelector, StreamConfiguration): StreamSession`; `StreamSession { state: StateFlow<StreamSessionState>; videoStream: Flow<VideoFrame>; suspend capturePhoto(): kotlin.Result<PhotoData>; close() }`; `StreamSessionState { STARTING, STARTED, STREAMING, STOPPING, STOPPED, CLOSED }`; `StreamConfiguration(videoQuality: VideoQuality, frameRate: Int)`; `VideoQuality { HIGH, MEDIUM, LOW }`; `VideoFrame(buffer: ByteBuffer, width, height, presentationTimeUs)`; `StreamError { STREAM_ERROR, HINGE_CLOSED, PERMISSIONS_DENIED }` exists but **`StreamSession` exposes no error flow in 0.4.0** (typed `Stream.errorStream` only arrives in 0.7.0).

### 6.0 (Blocker, unrelated to iOS diff but discovered while checking parity) — Android does not compile against 0.4.0
- `android/app/src/main/java/com/smartview/glassai/viewmodels/WearablesViewModel.kt:190-198`:
  ```kotlin
  fun startRegistration() { Wearables.startRegistration(getApplication()) }
  fun startUnregistration() { Wearables.startUnregistration(getApplication()) }
  ```
  `getApplication()` yields `Application`; 0.4.0 requires `Activity` (changelog 0.4.0: "accept an Activity instead of a Context"; "The registration dialog now opens in place, instead of jumping to Meta AI app"). Type mismatch -> compile error.
- Change: `fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)`; thread an `Activity` from Compose: in `HomeScreen.kt:54` there is already `val context = LocalContext.current`; use `(context as? Activity)` (or a `findActivity()` helper) at the call sites `HomeScreen.kt:168` and `:256` (`onConnect = { wearablesViewModel.startDeviceSearch() }`) and in `WearablesViewModel.disconnect()` (`:200-207`). Upstream sample pattern: `activity?.let { viewModel.startRegistration(it) }` (`samples/CameraAccess/.../ui/HomeScreen.kt:113`).
- Everything else in the app matches 0.4.0 (`startStreamSession(Context, ...)`, `capturePhoto(): kotlin.Result` used with `onSuccess/onFailure`, `RegistrationState.Unavailable()`).

### 6.1 "Display support" on Android
| Aspect | Android status | Change |
|---|---|---|
| SDK capability | Already present: DAT Android 0.4.0 = "Meta Ray-Ban Display glasses support" + `AutoDeviceSelector.filter` "Defaults to filter out incompatible devices"; app uses `AutoDeviceSelector()` (`WearablesViewModel.kt:102`, `RTMPStreamingViewModel.kt:70`, QuickVisionService). Display glasses will be auto-selected as camera devices exactly as on iOS. Firmware requirement for Display glasses is V21 for both 0.4.0 and 0.5.0. | None required for functionality. |
| Error strings (`hingesClosed`/`thermalCritical`) | No public error stream on `StreamSession` in 0.4.0; the app maps `StreamSessionState` only (`WearablesViewModel.kt:301-334`). | Not portable at 0.4.0. If desired, show a generic "stream stopped" message when state goes `STREAMING -> STOPPED` unexpectedly. |
| Device naming | `ConnectionState.Registered(device.toString())` (`WearablesViewModel.kt:133`) shows the raw `DeviceIdentifier`. | Optional UX: look up `Wearables.devicesMetadata[device]?.value?.let { "${it.name} (${it.deviceType.description})" }` so Display glasses are identified by model. |
| Strings | `strings.xml:18-19,24,54,65,234` hardcode "Ray-Ban Meta" (e.g. `home_subtitle` "Your AI Assistant for Ray-Ban Meta", `home_connect_glasses` "Connect Ray-Ban Meta", `rayban_glasses`, `disconnect_confirm`, `liveai_connect_first`, `device_required_desc`); same in `values-zh-rCN`. | UX text: broaden to "Meta 眼镜 / Meta glasses" or mention Display, mirroring `README.md:316`. |
| README | Android note says v1.5.0 lacks Display support — factually the SDK already supports it. | Update README once Android ships a 0.4.0-compatible build. |
| Manifest App ID | `AndroidManifest.xml:34-36` `com.meta.wearable.mwdat.APPLICATION_ID = "0"` (Developer-Mode only). | Add an Android "Register with Meta Wearables" README step analogous to the iOS one (Application ID integration -> Android integration) and a `manifestPlaceholders` build variable. |
| Settings "About" | `SettingsScreen.kt:419-424` version `"1.5.0"`, no SDK-version row (string `settings_sdk_version` exists at `strings.xml:175` but is unused). | Bump `versionName`/subtitle to 2.0.0 when releasing; add SDK row "0.4.0" (or read from `BuildConfig`). |

### 6.2 Stability parity table

| iOS fix | Android file / lines | Same gap? | Android change |
|---|---|---|---|
| 4.1 URLSession leak | `OmniRealtimeService.kt:103-105`, `GeminiLiveService.kt:102-105`: `private val client = OkHttpClient.Builder()...build()` **per service instance**; `OmniRealtimeViewModel.kt:130-138, 175-182` creates a new service on every `initializeService()`/`refreshService()` (`:413-418`) and provider change (`:95-107`). `disconnect()` (`Omni:148-158`, `Gemini:151-163`) closes the socket and cancels `scope` but never shuts the client down. | Partial (not a hard leak — OkHttp dispatcher threads idle out after 60 s — but each instance owns its own `Dispatcher`/`ConnectionPool`/threads). | Share one `OkHttpClient` (companion `val client` or app singleton) across both services, or add `fun release() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }` called from `OmniRealtimeViewModel.onCleared()`/`refreshService()`. |
| 4.2 weak-self closures | Kotlin lambdas; `WebSocketListener` anonymous object holds the service until socket closes. | No equivalent gap. | None. |
| 4.3 config on open | `OmniRealtimeService.kt:124-128` `onOpen { ... sendSessionUpdate() }`; `GeminiLiveService.kt:126-130` `onOpen { ... configureSession() }`. | No gap (already correct). | None. |
| 4.4 converter guard | Android records directly at the target rate (`Omni:184-193` 24 kHz phone / 16 kHz BT; `Gemini:264-271` 16 kHz); no resampler. | No gap. | None. |
| 4.5 RTMP thread safety | `RTMPStreamingService.kt:73` `private var isStreaming = false` (not `@Volatile`), read on the encoder loop thread (`:216-247`, `Dispatchers.IO`) and in `feedFrame` (`:316-330`, `:346-399`, called from `viewModelScope` main thread in `RTMPStreamingViewModel.kt:251-256`), written by `stopStreaming()` (`:404-442`) which is invoked from the RTMP callback thread (`onConnectionFailedRtmp` `:135-139`) or main. `encoder` (`:66`) is stopped/released (`:414-419`) while `feedFrame`/the loop may be inside `dequeueInputBuffer`/`dequeueOutputBuffer` -> `IllegalStateException` swallowed by `catch (e: Exception)` (`:241-245`, `:396-398`). | **Yes** (data race + use-after-release on `MediaCodec`). | Mark `isStreaming` `@Volatile`; guard `encoder` access with a lock (`private val codecLock = Any()`; `synchronized(codecLock)` in `feedFrame`, the loop body, and `stopStreaming`), or run all encoder calls on `Dispatchers.Default.limitedParallelism(1)` like the upstream sample (`samples/CameraAccess/.../camera/CameraViewModel.kt:94`). Also avoid calling `stopStreaming()` synchronously inside `onConnectionFailedRtmp` (post to `scope`). |
| 4.6 RTMP teardown on VM death | `RTMPStreamingViewModel.kt:334-339` `onCleared()` -> `stopStreaming(); rtmpService.release(); statsJob?.cancel()`. | No gap. | None. |
| 4.7 Stream key secure storage | Android has no separate key field; the full URL (`rtmp://server/app/streamkey`) is stored under `rtmp_url` in `EncryptedSharedPreferences` (`APIKeyManager.kt:34, 50-56, 218-224`, AES256-GCM). | No storage gap. Minor UI leak: `RTMPStreamingScreen.kt:275` shows `rtmpUrl.take(50)` on screen (may include the key). | Optional: mask the path segment after the last `/` when rendering. |
| 4.8 Frame skip / memory pressure | `WearablesViewModel.kt:293-298` `videoJob = viewModelScope.launch { session.videoStream.collect { handleVideoFrame(it) } }` — runs **on the main thread** and does full I420->NV21 + `YuvImage.compressToJpeg` + `BitmapFactory.decodeByteArray` per frame (`:411-441`). Same in `RTMPStreamingViewModel.kt:204-209, 228-286` (`feedFrame` with `dequeueInputBuffer(10000)` = 10 ms blocking + preview JPEG per frame, all on main). QuickVisionService copies similarly. Since `collect` is sequential there is no unbounded task backlog (the iOS bug), but the main thread is saturated at 24 fps -> jank/ANR risk. | **Different but real gap** (main-thread work, no conflation). | `videoJob = viewModelScope.launch(frameDispatcher) { session.videoStream.conflate().collect { handleVideoFrame(it) } }` with `private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)`; keep the existing defensive `buffer.get(byteArray)` copy (buffer may be reused by the SDK); `_currentFrame.value = bitmap` is thread-safe (`StateFlow`). `onFrameReceived` consumers (`LiveAIScreen`/`OmniRealtimeViewModel.updateVideoFrame`) only store a `Bitmap` reference, so they are thread-agnostic. Add the same to `RTMPStreamingViewModel` (preview via `conflate()`, encoder feed unconflated). |
| 4.9 TTS engine reuse | `QuickVisionService.kt:111` creates `TextToSpeech` once in `onCreate`, `:167-168` `shutdown()` in `onDestroy`; `QuickVisionScreen.kt:103-124` `DisposableEffect(Unit)` creates once, shuts down on dispose. | No gap. | None. |
| 4.10 Cancellable waits | `QuickVisionScreen.kt:184-188` `while (streamState !is Streaming && streamWait < 50) { delay(100); streamWait++ }`; `:209-213` photo wait; `QuickVisionService.kt:256-258` `while (!frameReceived && elapsed < 8000) delay(100)`. `delay` is already cancellable, so functionally fine. | Cosmetic. | Optional: `withTimeoutOrNull(5_000) { wearablesViewModel.streamState.first { it is StreamState.Streaming } } != null`. |
| 4.11 Force-unwrapped URL | `VisionAPIService.kt:93-96` `Request.Builder().url("$baseURL/chat/completions")` inside `try { ... } catch (e: Exception)` (`:121`) -> returns `Result.failure`; `LeanEatService.kt:21,74` constant; Omni/Gemini `.url(...)` at `Omni:118-121`/`Gemini:121-123` are outside any try but use constant hosts. | No crash gap. | None (optionally wrap `connect()` in `runCatching`). |
| 4.12 Sheet race | Compose navigation (`Navigation.kt`); `SimpleLiveStreamScreen.kt` has no photo-preview -> AI hand-off (grep for `Photo|Dialog` is empty); photo flows are screen-local (`VisionScreen`, `LeanEatScreen`). | Not applicable. | None. |
| 4.13 Shared VM cleanup | `WearablesViewModel` is Activity-scoped (`MainActivity.kt:41` `by viewModels()`), `onCleared()` (`:478-491`) runs only when the Activity is destroyed; `stopStream()` (`:343-368`) is what screens call. | No gap — Android already has the correct ownership model. Do **not** port the iOS `cleanup()`-on-disappear. | None. |
| 3.11 Registration error surfacing | `Wearables.startRegistration(Activity)` returns `Unit` in 0.4.0; errors come via `registrationState` (already collected `WearablesViewModel.kt:143-167`). `Wearables.initialize(this)` result is ignored at `MainActivity.kt:123` (0.4.0 returns `DatResult`; `ALREADY_INITIALIZED` exists since 0.3.0). | Minor. | `Wearables.initialize(this).onFailure { error, _ -> Log.e(TAG, "initialize failed: ${error.description}") }` mirroring `TurboMetaApp.swift:35-41`. |
| Home layout (OpenClaw card, LeanEat wide) | `HomeScreen.kt:275-380`: grid = LiveAI, QuickVision, LeanEat, WordLearn(placeholder, `onClick = {}`); wide = LiveStream, RTMP. | UX only; depends on the OpenClaw port. | When OpenClaw lands: replace the WordLearn placeholder card with OpenClaw (`Icons`/purple gradient), move LeanEat to a `FeatureCardWide` after RTMP. |
| Build hygiene | pbxproj personal signing/team/bundle-id leak; Info.plist undefined `$(META_APP_ID)`/`$(CLIENT_TOKEN)`. | Android analogue: `APPLICATION_ID = "0"` hardcoded. | Use `manifestPlaceholders["metaAppId"]` from `local.properties`/Gradle property; document in README. |

---

## 7. Risks / open questions

1. **iOS regression (§4.13):** `StreamView.onDisappear -> cleanup()` on the shared `StreamSessionViewModel`. Needs a device test: open LeanEat (StreamView), close it, then open Live AI / Quick Vision — expected symptom: no frames, `hasActiveDevice` stuck, `LiveAIError.streamNotReady`.
2. **iOS build from clean clone:** `$(META_APP_ID)` / `$(CLIENT_TOKEN)` are not defined in `project.pbxproj` (post-78ea2f3). Leaked real credentials remain in history at `cd26fb2:CameraAccess/Info.plist:44-47` and should be rotated. Committed `DEVELOPMENT_TEAM`/`PRODUCT_BUNDLE_IDENTIFIER = com.glassai.app1` look accidental.
3. **iOS MARKETING_VERSION** is still `1.4.0` (`project.pbxproj:708`) while Settings displays 2.0.0.
4. **Android does not compile at 0.4.0** (§6.0) — the "Android v1.5.0" APK on GitHub releases must have been built at DAT 0.3.0. Any Android v2.0 work must start by fixing `startRegistration(Activity)`.
5. `RTMPStreamingService.swift` locking is one-sided (only `feedFrame` locks); writers on `MainActor` are unlocked. Acceptable in practice (single writer actor) but not a complete fix.
6. The HaishinKit dependency now points at a personal fork branch (`Turbo1123/HaishinKit.swift@fix-concurrency`); builds depend on that branch staying available.
7. Unverified: whether Display glasses need `VideoCodec.hvc1`/other config for best results — the app uses `.raw` for all models; the DAT docs do not list model-specific camera constraints for Display glasses (docs search returned only Display-rendering pages).
