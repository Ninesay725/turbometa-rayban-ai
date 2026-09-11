# Changelog

## Android 2.0.0 development — custom AI and gateway compatibility

- Added a separate custom AI screen for user-configured OpenAI-compatible Chat Completions endpoints, with encrypted credentials, explicit image/tool capabilities, cancellable requests, lens replies and shared TTS.
- Added bounded app tools for glasses capture, our Display cards and permitted active music sessions. Notification summaries require separate app selection and consent, have no action tools, and are excluded from ordinary conversation history.
- Added QQ Music (`com.tencent.qqmusic`) to default music packages while preserving custom selections.
- Updated OpenClaw against released `v2026.9.4`: protocol 4 node authentication, endpoint-bound paired credentials, supported node-event chat with incremental deltas, and explicit older/custom gateway profiles. This does not upgrade an installed gateway.
- See [custom AI setup and limits](../docs/CUSTOM_AI_GUIDE.md) and the [current validation ledger](../docs/superpowers/reviews/custom-ai/progress.md). Real model services, gateway policy/pairing and physical glasses remain separate verification steps.

## Android 2.0.0 development — camera, speech and translation

- RTMP authentication failures now release the attempt and allow retry; retired callbacks are fenced under the stop lock, with 13 production callback regressions. Quick Vision no longer logs prompts, analysis text or provider error bodies.

- Camera hub with timed streams, fresh cancellable photo capture, RAM preview, explicit sharing and analysis/nutrition handoff. Page-specific camera ownership fences delayed permission and capture callbacks.
- Shared Quick Vision speech through regional `qwen3-tts-flash` HTTP SSE, bounded PCM16 playback and system TTS fallback. Cancelling speech never starts fallback playback.
- Foreground-only `qwen3-livetranslate-flash-realtime`: validated language/voice settings, explicit microphone permission, bounded configuration wait, optional camera enhancement, and RAM-only history.
- Restore custom app locales before Activity context attachment on Android 12 and earlier. Current-build emulator verification is recorded separately from historical reports.
- Physical Display, real WeChat/music apps, actual cloud entitlement and microphone routing remain subject to the hardware checklists; the known DAT rapid-session-restart stress case remains unresolved and excluded explicitly.

## Android 2.0.0 development — notifications and music

- Added opt-in WeChat notification previews and public MediaSession controls for configurable NetEase/Qishui packages.
- Added notification-access guidance, phone controls and in-memory preview list; features default off.
- Completed paged WeChat cards and bounded local album artwork. Notification cards use an existing live session only and release on Done/expiry.
- Added parser, media-session, lifecycle and private-cache regressions. Real third-party/display checks and the documented DAT restart stress issue remain pending.

## [2.0.0] - 2026-09-10

### 新功能
- **Meta Ray-Ban Display 镜片卡片**：Live AI 转录、Quick Vision 结果、LeanEat 营养分析和 OpenClaw 回复共用现有眼镜会话；长结果可翻页，Live AI 增量更新按 600 ms 合并。
- 设置增加眼镜显示开关；调试版增加手机卡片预览。首轮卡片仅使用文本，微信与音乐桥接安排在后续阶段。
- Display 会话启动后挂载、停止前移除；会话占用不阻塞拍照，退出等待中的功能不会留下后台会话。真实镜片渲染与按钮仍需按 Phase C 硬件清单验收。
- **OpenClaw 集成**：完整节点模式。App 以 `openclaw-android` 身份连接自建 OpenClaw Gateway（`ws://` 或 `wss://`），
  支持文字 / 语音（阿里云 Fun-ASR 实时识别）/ 拍照发送，AI 可通过 `camera.snap`、`camera.list`、
  `device.status`、`device.info` 主动调用眼镜。设备身份为 Ed25519（Tink），与 iOS 协议逐字段一致。
- **首页 OpenClaw 卡片**、设置页「集成」分区、OpenClaw 设置页（地址 / 端口 / 协议 / 令牌 / 配对提示）。
- **设置页 About** 新增 SDK 版本行。

### 稳定性
- RTMP：连接失败原因不再被 Disconnected 覆盖；眼镜 10 秒内无画面时报错而不是一直「连接中」；
  推流密钥拆为独立加密字段并不再显示在屏幕上；码率持久化。
- WebSocket 服务（Live AI Omni / Gemini / Fun-ASR）共用一个 OkHttpClient，断开时完整释放资源，监听器使用弱引用。
- 唤醒词拍照的总耗时上限 15 秒。
- Live AI 返回前台时重新检查麦克风权限。
- 眼镜错误只弹一次提示（提示统一挂在导航根部）。
- OpenClaw：WebSocket 增加 20 秒 ping 保活，手机离开 Wi-Fi 后的半开连接不再长时间显示「已连接」；握手被 Gateway 拒绝（除 NOT_PAIRED 之外的错误）现在报传输错误并停止重连，不再永远停在「连接中」。
- OpenClaw：聊天页打开时的会话占用不再阻止拍照发送 / `camera.snap`（区分「相机占用」与「会话占用」），并发的两次拍照串行执行。
- RTMP：推流失败后自动归还相机，再次开始不会卡在「连接中」，停止时不再 ANR。
- 蓝牙 SCO 唤起失败时也会恢复普通音频模式（不再遗留媒体静音）。
- OpenClaw 设置页：返回 / 完成也会保存地址、端口、协议与令牌。

### 技术特性
- Meta Wearables DAT SDK 0.9.0（Kotlin 2.2.21 / AGP 8.11.1 / compileSdk 36），共享 `GlassesSessionManager` 会话。
- 需要 Meta AI 应用 V282+、Ray-Ban Meta 固件 V126+、Meta Ray-Ban Display 固件 V125+，且眼镜上已安装 DAT Wearables App。
- 最低 Android 12（API 31）。

## [1.0.0] - 2024-12-27

### 新功能
- **Live AI**: 实时语音对话，支持阿里云百炼 Omni Realtime API
- **LeanEat**: 食物拍照营养分析
- **直播预览**: 眼镜摄像头实时画面
- **对话记录**: 自动保存对话历史
- **中文本地化**: 完整的中文界面支持

### 技术特性
- 集成 Meta Wearables DAT SDK v0.3.0
- 基于 Jetpack Compose + Material 3 构建
- 支持 Android 8.0+ (API 26+)
- 相机权限检查（匹配 SDK 示例流程）
- 正确的 Session 生命周期管理

### 已知问题
- 直播推流功能尚未完成
- 实时翻译功能开发中
- WordLearn 功能开发中
