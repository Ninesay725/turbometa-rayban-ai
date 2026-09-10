# TurboMeta Android 2.0 设计文档

日期：2026-09-10　分支：`android-v2`　状态：已由产品负责人确认（所有取舍按推荐值）

研究依据：`docs/DAT_SETUP_CHECKLIST.md`，以及六份研究报告（iOS OpenClaw 逆向、iOS 2.0 Display/稳定性改动、Android 现状、DAT 0.4→0.9 迁移映射、Display 蓝图、功能差距矩阵）。本文只记录结论，不重复报告内容。

---

## 1. 目标与非目标

**目标**

1. Android 端 DAT SDK 从 0.4.0 升级到 0.9.0，并修复现有注册崩溃。
2. 将 iOS v2.0.0 的功能（OpenClaw 集成、稳定性修复、版本与文档）移植到 Android。
3. Android 端支持 Meta Ray-Ban Display：既作为相机设备正常工作，也在镜片上渲染 TurboMeta 的结果卡片。
4. 补齐 Android 相对 iOS 的主要功能缺口（相机中心页与相关 bug、云端 TTS、实时翻译）。
5. 新增两项 Android 独有能力：在眼镜上看微信消息；控制并显示网易云音乐、汽水音乐的播放。

**非目标**

- iOS 端任何改动（产品负责人明确不管 iOS）。
- OpenClaw 后台拍照（App 不在前台时响应 `camera.snap`）。
- 常驻眼镜会话（见 §3 决策 1）。
- 微信消息回复、语音消息与图片内容读取（无公开 API，见 §8）。

---

## 2. 研究得出的关键事实

- iOS 2.0.0 的"Display 支持"只是 SDK 0.4→0.5 的版本标签，iOS 代码不使用 `MWDATDisplay`。镜片渲染是 Android 的全新增量。
- Android 现有代码在 0.4.0 上运行时崩溃：`Wearables.startRegistration(getApplication())` 编译为对 `Application` 的 `checkcast Activity`。
- 0.9.0 删除了 `Wearables.startStreamSession`/`StreamSession`，改为 `createSession → start → STARTED → addCamera → camera.stream.start()`；每台眼镜同时只允许一个 `DeviceSession`（`SESSION_ALREADY_EXISTS`）；Camera 与 Display 可挂在同一个会话上（限制是每种能力各一个）。
- 0.9.0 的 AAR 用 Kotlin 2.2.x 编译，Kotlin 2.0.0 无法读取其元数据；示例工程使用 AGP 8.11.1、Gradle 8.14.1、compileSdk 36、JVM 17。
- MockDeviceKit 0.9.0 没有 Display 机型，镜片渲染只能真机验证。
- 版本依赖：0.9.0 需要 Meta AI 应用 V282，Ray-Ban Meta 固件 V126，Meta Ray-Ban Display 固件 V125，且眼镜上需安装 DAT Wearables App（Meta AI 开发者模式里安装）。

---

## 3. 已确认的决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | 会话生命周期 | 进入功能页（Live AI、Quick Vision、LeanEat、相机中心、OpenClaw 聊天、音乐/微信卡片）时建会话，离开时停会话。不做常驻会话。Display 的状态菜单卡片只在会话存活期间显示。 |
| 2 | 工具链 | 一步对齐 Meta 0.9.0 示例：Kotlin 2.2.21、AGP 8.11.1、Gradle 8.14.1、compileSdk/targetSdk 36、JVM 17、activity-compose ≥ 1.10、Compose BOM 2026.05.01。补上官方 `gradlew.bat`。 |
| 3 | OpenClaw 范围 | 完整节点模式，协议与 iOS 逐字段一致；客户端标识 `openclaw-android` / `platform: "android"`；额外支持 `wss://`；ASR 麦克风沿用 Live AI 的手机/眼镜切换；聊天记录只存内存。 |
| 4 | 阶段 D 范围 | 相机中心页（含 LeanEat 取流修复、Vision 页面路由修复）、云端 TTS、实时翻译。其余缺口列入后续。 |
| 5 | 版本与分支 | `android-v2` 分支开发，各阶段独立提交；全部完成后合并 `main`，发布 Android 2.0.0。 |
| 6 | 测试硬件 | 产品负责人有 Meta Ray-Ban Display 与安卓手机。阶段 A/B 先用模拟器 + MockDeviceKit 验证，阶段 C/E 真机验证。 |
| 7 | 设备选择 | 保留 `AutoDeviceSelector`（不加过滤），当活动设备 `isDisplayCapable()` 时自动挂 Display。不做设备选择器。 |
| 8 | 视频质量 | Display 卡片在屏时不自动降质，先真机测量再定。 |
| 9 | 微信与音乐实现方式 | 基于 `NotificationListenerService` 与 `MediaSessionManager`（系统公开 API），不逆向任何第三方 App。见 §8。 |

---

## 4. 总体架构

```
                    ┌──────────────────────────────────────────────┐
                    │ GlassesSessionManager (进程单例)              │
   Wearables ─────► │  DeviceSession ── Camera(Stream) ── Display   │
                    │  sessionState / displayState / errors         │
                    └───────┬──────────────┬───────────────┬────────┘
                            │              │               │
             帧、拍照        │       卡片渲染 │        点击回调 │
                            ▼              ▼               ▼
   WearablesViewModel  GlassesDisplayManager      GlassesActionRouter
   QuickVisionService   (DisplayCard → sendContent)  (导航 / 启动服务 / 媒体控制)
   RTMPStreamingViewModel
   OpenClawCommandRouter
                            ▲
   OpenClawNodeService ─────┘ (camera.snap 取当前帧)
   NotificationBridgeService ──► DisplayCard.WeChat / DisplayCard.Music
```

**单一会话持有者。** `GlassesSessionManager` 是唯一调用 `Wearables.createSession` 的地方，负责：会话创建与启动、在 `STARTED` 后按需挂 Camera 与 Display、状态与错误的对外 `StateFlow`/`SharedFlow`、停止与清理。它带引用计数：每个功能页 `acquire()`/`release()`，计数归零时停会话。

**能力借用。** 需要相机的组件调用 `sessionManager.addCamera(config)` 得到 `Camera`，自己订阅 `camera.stream.videoStream/state/errorStream`，用完调用 `stopCamera()`。同一时刻只允许一个相机借用者，第二个借用者收到 `CameraBusy`。

**显示只走一个出口。** 所有镜片内容都通过 `GlassesDisplayManager.show(card)`，它做合并（只发最新卡片）、分页、在 `DisplayState.STARTED` 时重发上一张卡片。卡片模型 `DisplayCard` 是纯 Kotlin，可单元测试；只有 `DisplayCards.kt` 一个文件引用 `dat.display.views`。

**点击回调路由回手机。** 眼镜按钮的 `onClick` 在手机上触发 `DisplayAction`，由 `GlassesActionRouter` 转成导航、启动前台服务或媒体控制。回调线程未文档化，一律只更新 `StateFlow` 或启动协程。

---

## 5. 阶段 A：工具链与 SDK 0.9.0

**范围**

1. Gradle 版本目录与构建脚本升级（决策 2），`mwdat = 0.9.0`，新增 `mwdat-display`，`debugImplementation(mwdat-mockdevice)`。
2. `AndroidManifest.xml`：`APPLICATION_ID`/`CLIENT_TOKEN` 改为 `${mwdat_application_id}`/`${mwdat_client_token}` 占位符，值来自 `local.properties`（缺省 `0`）；新增 `CAMERA` 权限与 `uses-feature`（MockDeviceKit 手机相机源）；不添加已失效的 `DAM_ENABLED`。
3. `Wearables.initialize` 移到 `TurboMetaApplication.onCreate()`；`MainActivity` 不再以 `RECORD_AUDIO` 作为初始化前提，麦克风权限在 Live AI/ASR/唤醒词需要时再申请；补申请 `POST_NOTIFICATIONS`。
4. 新建 `glasses/GlassesSessionManager.kt`（§4），以及 `glasses/GlassesSessionState.kt`。
5. 迁移三处相机调用到 SessionManager：`WearablesViewModel`（保留其对外的 `StreamState` sealed class 与 `currentFrame` 契约，SDK 的 `StreamState` 用别名导入）、`QuickVisionService`（改用 `capturePhoto()`，取流超时放宽到 12 s，并处理 `CameraBusy`）、`RTMPStreamingViewModel`。
6. 注册：`RegistrationState` 枚举、`registrationErrorStream` 在 `startMonitoring()` 里先订阅；`startRegistration/startUnregistration/disconnect` 接收 `Activity`，Compose 侧用 `LocalActivity.current`。
7. 设备元数据：订阅 `devicesMetadata[id]`，暴露设备名、类型、`isDisplayCapable`、`compatibility`；`DEVICE_UPDATE_REQUIRED` 与 `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` 分别提供 `openFirmwareUpdate`/`openDATGlassesAppUpdate` 按钮。
8. 帧处理：`videoStream` 在 `Dispatchers.Default` 收集，忙时丢帧（`isProcessingFrame` 标志），跳过 `isCompressed`/`isCodecConfig` 帧；`PAUSED` 映射为独立的 `Paused` UI 状态（"轻触眼镜恢复"）。
9. 流错误映射：`StreamError`/`DeviceSessionError` 各分支给出本地化文案（zh/en）。
10. MockDeviceKit 调试页（仅 debug 构建，设置页入口）：启用/禁用、配对 `GlassesModel.RAYBAN_META`、开关机、佩戴/摘下、折叠、选择视频/图片源、手机相机源、captouch tap。

**验证**：`assembleDebug` 成功；模拟器 `Pixel_5` 上用 MockDeviceKit 走通注册、取流、拍照、Quick Vision 流程；`./gradlew test` 通过新增的单元测试。

---

## 6. 阶段 B：iOS 2.0 功能平移

**B1 OpenClaw**（包 `services/openclaw/`，UI 包 `ui/screens/`）

- `OpenClawNodeService`：OkHttp WebSocket，`Proxy.NO_PROXY`，连接超时 10 s；握手、`chat.send`、`node.invoke` 收发、15 s `tick`、指数退避重连（2/4/8/16/30 s，最多 5 次）、`NOT_PAIRED` → 等待配对。协议字段见研究报告 §2；`[[FINAL]]` 字符串前缀改为类型化回调 `(text, isFinal)`。
- `OpenClawDeviceIdentity`：Tink `Ed25519Sign`；32 字节种子 base64 存 `EncryptedSharedPreferences`；`deviceId = sha256hex(pubKey)`；v3 签名串与 iOS 完全一致；`platform = "android"` 同时用于 JSON 与签名。
- `OpenClawCommandRouter`：`camera.snap/camera.list/device.status/device.info`，通过 `GlassesFrameProvider` 接口取帧（由 `GlassesSessionManager` 适配），`device.info` 的 `appVersion` 取 `BuildConfig.VERSION_NAME`、`sdkVersion` 取版本目录。
- `FunASRService`：DashScope `fun-asr-realtime`，按当前 Alibaba 端点选择北京/新加坡；`AudioRecord` 16 kHz PCM16，麦克风来源复用 `BluetoothAudioManager`。
- UI：`OpenClawChatScreen`、`OpenClawSettingsScreen`（Host/Port/Token/协议 ws|wss）、首页卡片（替换 WordLearn 占位）、设置页"集成"分区；`network_security_config.xml` 允许明文；新增 30 条字符串（zh/en）。

**B2 稳定性对齐**：WebSocket 服务断开时关闭并释放 `OkHttpClient` 资源、监听器使用弱引用；RTMP `feedFrame` 加锁，ViewModel 清理时停止推流；推流密钥拆为独立加密字段，屏幕上不再显示密钥；码率持久化。

**B3 版本与文档**：`versionName 2.0.0`，设置页 About 增加 SDK 版本行，更新 `android/README.md`、`android/CHANGELOG.md`、根 README 的 Android 段落。

---

## 7. 阶段 C：Display 能力

- `glasses/DisplayCard.kt`：`Status`、`Notice`、`LiveAI`、`QuickVision`、`LeanEat`、`OpenClaw`、`WeChat`、`Music`（后两者在阶段 E 填充）。
- `glasses/DisplayCards.kt`：每种卡片一个 `ContentScope` 构建函数，只用文档化的 DSL；文本超过 280 字分页；图片用 `bitmap` 且长边 ≤ 600。
- `glasses/GlassesDisplayManager.kt`：§4 所述合并、分页、重发；Live AI 增量文本 600 ms 合并。
- `glasses/GlassesActionRouter.kt`：`StartLiveAI/StartQuickVision/StartLeanEat/EndLiveAI/Page/BackToMenu/OpenClawSnap/MusicPlayPause/MusicNext/MusicPrev`。
- 挂接点：`OmniRealtimeViewModel` 的转录回调、`QuickVisionService` 的状态与结果、`LeanEatViewModel.analyzeFood`、`OpenClawViewModel` 的聊天事件。
- 设置：`glasses_display_enabled`（默认开）；首页设备卡显示 Display 状态与更新引导。
- 调试：`GlassesDisplayPreviewScreen` 在手机上以 600×600 黑底预览任意卡片（仅 debug）。
- 错误：`INVALID_SESSION_STATE` 等待 `STARTED` 后重发；`RENDERING_FAILED` 记录并降级为纯文本卡；L0 返回手势导致的 `STOPPED` 不自动重启。

**验证**：单元测试覆盖分页与卡片派生；真机检查清单：`addDisplay` 在 `STARTED` 后成功、每种卡片渲染、每个按钮路由、相机与显示同时工作时的帧率、显示休眠后重发、眼镜端应用需更新的引导路径。

---

## 8. 阶段 E：微信消息与音乐控制

**共同机制。** 新建 `services/NotificationBridgeService`（继承 `NotificationListenerService`），用户在系统设置授予"通知使用权"。它是唯一读取系统通知与媒体会话的组件，向 `GlassesDisplayManager` 推卡片，向 `GlassesActionRouter` 提供媒体控制。

**微信消息。** 微信没有公开消息 API，唯一不违反平台规则且稳定的方式是读取微信通知：

- 监听包名 `com.tencent.mm` 的通知，提取 `EXTRA_TITLE`（联系人/群名）、`EXTRA_TEXT`（消息预览）、`EXTRA_MESSAGES`（若为 MessagingStyle）、时间。
- 卡片 `DisplayCard.WeChat(sender, preview, count, timestamp)`；连续消息合并显示最近 3 条；点击"完成"回到状态菜单。
- 限制（已告知）：只能看到通知里的内容；微信"通知显示详情"关闭或免打扰时无内容；语音、图片只显示占位文字；不支持回复；微信合并通知时只能拿到"[N 条]"摘要。
- 设置开关 `wechat_bridge_enabled`（默认关），并可选择只在会话存活时显示或一收到就显示（后者需要会话已存在，遵循决策 1，因此默认为"仅会话存活时"）。

**音乐。** 用 `MediaSessionManager.getActiveSessions(componentName)` 取得网易云音乐（`com.netease.cloudmusic`）与汽水音乐（`com.luna.music`）的 `MediaController`：

- 读取 `MediaMetadata`（标题、歌手、专辑、封面 Bitmap）与 `PlaybackState`，封面缩放到 240 px 后作为 `image(bitmap=)`。
- 卡片 `DisplayCard.Music(app, title, artist, isPlaying, art)`，按钮：上一曲、播放/暂停、下一曲，通过 `transportControls` 下发。
- 优先级：正在播放的会话优先；两者都活跃时取最近有播放状态变化的一个。
- 汽水音乐的包名需在真机上用 `adb shell pm list packages` 核实（研究未覆盖第三方包名），实现时以可配置的包名白名单处理，默认包含上述两个。

**验证**：真机上分别播放两款 App，确认元数据与封面显示、三个按钮生效；发送微信消息确认卡片出现；关闭"通知显示详情"确认降级行为。

---

## 9. 阶段 D：其他功能补齐（阶段 C 之后）

1. **相机中心页 `CameraScreen`**：自动取流、计时自动停止（1/5/10/15 分钟，复用已有字符串）、`capturePhoto`、预览页（分享、AI 识别、营养分析）。修复 `LeanEatScreen` 从不启动取流、`Screen.Vision` 路由不可达。
2. **云端 TTS `TTSService`**：DashScope `qwen3-tts-flash` SSE，PCM16 24 kHz 经 `AudioTrack` 播放；按语言选 Cherry/Ethan；OpenRouter 或无阿里 Key 时回退系统 TTS；Quick Vision 状态语与结果播报统一走它。
3. **实时翻译**：`LiveTranslateService`（`qwen3-livetranslate-flash-realtime`）、18 种语言与 8 种音色模型、翻译页与设置页、持久化 `translate_*` 键；首页卡片替换"Coming Soon"。

---

## 10. 测试策略

- **单元测试**（JVM）：`DisplayCard` 派生与分页、OpenClaw 签名串拼接与 base64url、帧封装解析（`params`/`paramsjson`）、`GlassesSessionManager` 引用计数状态机（DAT 接口用假实现）。
- **仪器测试**（模拟器 + MockDeviceKit）：注册、取流、拍照、Quick Vision 服务经共享会话取帧、`isDisplayCapable == false` 时不调用 `addDisplay`。
- **真机清单**：阶段 C 与 E 的清单见各节。
- 每个阶段结束都要 `assembleDebug` 通过并提交。

---

## 11. 风险与应对

| 风险 | 应对 |
|---|---|
| 工具链升级牵动 RTMP 库（rtplibrary 2.2.6）、Picovoice、Compose | 阶段 A 第一步只做版本升级并构建，问题在迁移前暴露 |
| 0.9.0 帧像素布局未文档化（假定 I420） | 沿用现有 `width*height*3/2` 校验日志，真机核对 |
| 相机与显示同时占用蓝牙带宽 | 卡片先纯文本，封面 ≤ 240 px；真机测量后决定是否降质 |
| Display 只能真机验证 | 手机端预览页先迭代布局；真机清单逐项核对 |
| 微信通知内容受系统与微信设置影响 | 设置页提供检测与引导；不承诺完整消息内容 |
| 汽水音乐包名/媒体会话行为未核实 | 包名白名单可配置；真机核实 |
| 开发者模式下只能注册一个第三方 App | 测试时不与 Meta 示例 App 同时注册 |

---

## 12. 交付顺序

A（工具链 + SDK + 会话管理 + Mock 调试页）→ B（OpenClaw + 稳定性 + 版本文档）→ C（Display）→ E（微信 + 音乐）→ D（相机中心、TTS、实时翻译）→ 合并 main，发布 2.0.0。

阶段 E 排在 D 之前，因为它直接建立在 C 的卡片体系上，且是产品负责人新提出的重点。
