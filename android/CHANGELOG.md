# Changelog

## [2.0.0] - 2026-09-10

### 新功能
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
