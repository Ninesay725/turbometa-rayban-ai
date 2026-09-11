# Changelog

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
