# TurboMeta Ray-Ban AI - Android

**Version 2.0.0** — Meta Wearables DAT SDK 0.9.0

Ray-Ban Meta 智能眼镜 AI 助手 Android 版本。

> **🎬 NEW: RTMP Live Streaming (Experimental) | RTMP 直播推流（实验性）**
>
> Push live video from Ray-Ban Meta glasses to **any RTMP-compatible platform** - YouTube Live, Twitch, Bilibili, Douyin, TikTok, Facebook Live, and more!
>
> 将 Ray-Ban Meta 眼镜的实时视频推送到**任意支持 RTMP 的直播平台** - YouTube Live、Twitch、B站、抖音、TikTok、Facebook Live 等！

## Features | 功能

### Live AI | 实时 AI 对话
- Real-time voice conversation with AI through Ray-Ban Meta glasses
- Supports Alibaba Qwen Omni and Google Gemini Live
- 通过 Ray-Ban Meta 眼镜与 AI 进行实时语音对话
- 支持阿里云通义千问 Omni 和 Google Gemini Live

### Quick Vision | 快速识图
- Take photos with glasses and get AI analysis
- Wake word detection: Say "Jarvis" to trigger Quick Vision
- 用眼镜拍照并获取 AI 分析
- 唤醒词检测：说 "Jarvis" 触发快速识图

### Multi-Provider Support | 多提供商支持
- **Vision API**: Alibaba Dashscope / OpenRouter (Gemini, Claude, etc.)
- **Live AI**: Alibaba Qwen Omni / Google Gemini Live
- **视觉 API**: 阿里云 Dashscope / OpenRouter (Gemini, Claude 等)
- **实时 AI**: 阿里云通义千问 Omni / Google Gemini Live

### 🎬 RTMP Live Streaming (Experimental) | RTMP 直播推流（实验性）
- Stream first-person view from glasses to any RTMP server
- Compatible with all major platforms: YouTube, Twitch, Bilibili, Douyin, TikTok, Facebook Live, etc.
- H.264 hardware encoding for smooth streaming
- Adjustable bitrate (1-4 Mbps)
- Real-time preview on phone
- 将眼镜的第一人称视角推流到任意 RTMP 服务器
- 兼容所有主流直播平台：YouTube、Twitch、B站、抖音、TikTok、Facebook Live 等
- H.264 硬件编码，流畅推流
- 可调节码率（1-4 Mbps）
- 手机实时预览

---

### 🔗 OpenClaw Integration | OpenClaw 集成

Chat with your self-hosted [OpenClaw](https://openclaw.ai) assistant from the glasses: text, voice (Alibaba Fun-ASR) and
**Snap & Send**; in *node mode* the AI can call `camera.snap`, `camera.list`, `device.status`, `device.info` on its own.
The app must be in the foreground for `camera.snap` (the DAT SDK cannot stream from the background).

从眼镜与自建 [OpenClaw](https://openclaw.ai) 助手对话：文字、语音（阿里云 Fun-ASR）和**拍照发送**；节点模式下 AI 可主动调用
`camera.snap`、`camera.list`、`device.status`、`device.info`。`camera.snap` 需要 App 在前台。

**Gateway setup | Gateway 配置** (`~/.openclaw/openclaw.json` on the machine running `openclaw gateway`):

```json
{
  "gateway": {
    "bind": "lan",
    "nodes": {
      "allowCommands": ["camera.snap", "camera.list", "device.status", "device.info"]
    }
  }
}
```

1. Restart the gateway, then in the app open **Settings → Integrations → OpenClaw** (or the gear on the chat screen).
2. Enter the gateway **Host** (LAN IP) and **Port** (default `18789`), choose `ws://` (LAN) or `wss://` (behind TLS), and paste the
   **Gateway Token** from the OpenClaw dashboard URL. Tap **Connect to Gateway**.
3. First connection shows **Waiting for pairing**: on the gateway machine run `openclaw devices list` then
   `openclaw devices approve <device-id>` (the device id is the Ed25519 identity the app generated once). The app reconnects automatically.
4. Away from home: run [Tailscale](https://tailscale.com) on both machines and use the tailnet IP as the host.
5. Voice input needs an Alibaba DashScope API key (Settings → API Key); the phone/glasses microphone toggle works like Live AI.
6. Voice input follows the Alibaba region setting. `fun-asr-realtime` is confirmed on the Beijing endpoint; on the **Singapore** (intl) endpoint its availability has not been verified — if the mic reports "Speech recognition failed", switch the Alibaba endpoint to Beijing.
7. `camera.snap` needs a frame from the glasses. While Live AI / Live Stream / RTMP are running it returns the live frame; while nobody streams it borrows the camera briefly. During a wake-word Quick Vision capture (a few seconds) the camera is busy and the command answers `NO_FRAME` — the AI simply retries.
8. **Security note — cleartext is allowed app-wide.** The OpenClaw gateway is normally a plain `ws://` server on your own LAN, and Android's network security config cannot whitelist an address *range*, only fixed hosts — the gateway address is typed by you at runtime, so `cleartextTrafficPermitted="true"` is unavoidable here. Every cloud endpoint the app uses (DashScope, Gemini, OpenRouter, Fun-ASR) is `https://` / `wss://` regardless. Use `wss://` for OpenClaw when the gateway is exposed beyond your LAN, or put it behind Tailscale. Please do not "fix" this by removing the flag — it breaks LAN gateways.

1. 重启 Gateway，然后在 App 打开 **设置 → 集成 → OpenClaw**（或聊天页右上角齿轮）。
2. 填写 Gateway **地址**（局域网 IP）与**端口**（默认 `18789`），选择 `ws://`（局域网）或 `wss://`（TLS 反代），粘贴 OpenClaw 仪表盘 URL 中的
   **Gateway 令牌**，点击 **连接 Gateway**。
3. 首次连接显示**等待配对**：在 Gateway 机器上执行 `openclaw devices list`，再 `openclaw devices approve <device-id>`。App 会自动重连。
4. 外网访问：两端安装 [Tailscale](https://tailscale.com)，地址填 tailnet IP。
5. 语音输入需要阿里云 DashScope API Key（设置 → API Key）；手机 / 眼镜麦克风切换与 Live AI 一致。
6. 语音识别跟随阿里云地域设置。`fun-asr-realtime` 已确认在北京节点可用；**新加坡**（intl）节点尚未验证——若麦克风提示「语音识别失败」，请把阿里云节点切回北京。
7. `camera.snap` 需要眼镜画面：Live AI / 直播 / RTMP 运行时返回实时画面；无人使用相机时会短暂借用相机；唤醒词 Quick Vision 拍照的几秒内相机被占用，命令返回 `NO_FRAME`，AI 重试即可。
8. **安全说明 — 全局允许明文流量。** OpenClaw Gateway 通常是局域网内的 `ws://` 服务，而 Android 的网络安全配置只能白名单固定域名、无法白名单 IP 段，地址又由用户运行时填写，因此 `cleartextTrafficPermitted="true"` 是必需的。所有云端接口（DashScope、Gemini、OpenRouter、Fun-ASR）仍然全部走 `https://` / `wss://`。Gateway 暴露到局域网之外时请改用 `wss://` 或用 Tailscale；请勿删除该配置，否则局域网 Gateway 会无法连接。

---

## ⚠️ Important Notes | 重要说明

### Wake Word Detection (Picovoice) | 唤醒词检测

The wake word detection feature ("Jarvis") uses **Picovoice Porcupine**. To use this feature:

唤醒词检测功能（"Jarvis"）使用 **Picovoice Porcupine**。使用此功能需要：

1. **Register at Picovoice Console | 注册 Picovoice 账号**
   - Go to https://console.picovoice.ai/
   - Create a free account
   - 访问 https://console.picovoice.ai/
   - 创建免费账号

2. **Get Access Key | 获取 Access Key**
   - After registration, get your Access Key from the console
   - 注册后，从控制台获取 Access Key

3. **Configure in App | 在 App 中配置**
   - Go to Settings → Quick Vision → Picovoice Access Key
   - Enter your Access Key
   - 进入 设置 → 快速识图 → Picovoice Access Key
   - 输入你的 Access Key

4. **⚠️ Microphone Always On | 麦克风常开**
   - Wake word detection requires the microphone to be always listening
   - This runs as a foreground service with a notification
   - Battery optimization should be disabled for best performance
   - 唤醒词检测需要麦克风一直处于监听状态
   - 这会作为前台服务运行，并显示通知
   - 建议关闭电池优化以获得最佳体验

### Google Gemini Live | Google Gemini Live

⚠️ **Not Fully Tested | 未完全测试**

- Google Gemini Live has not been fully tested due to limited access
- If you encounter issues, please provide feedback
- Google Gemini Live 由于条件限制未能完全测试
- 如遇问题，请反馈

---

## Release Notes | 更新日志

### v2.0.0 (2026-09-10)

- **OpenClaw integration** (node mode, Ed25519 device identity, Fun-ASR voice, Snap & Send) | **OpenClaw 集成**
- **DAT SDK 0.9.0** with a shared glasses session; Meta Ray-Ban Display glasses work as camera devices | **DAT SDK 0.9.0**，共享眼镜会话
- Stability: RTMP error reporting, first-frame timeout, encrypted stream key, WebSocket cleanup, capture budget | 稳定性修复
- Settings → About shows the SDK version; minimum Android 12 | 设置页显示 SDK 版本；最低 Android 12

### v1.4.0 (2024-12-31)

#### New Features | 新功能

- **🎬 RTMP Live Streaming (Experimental) | RTMP 直播推流（实验性）**
  - Stream first-person view from Ray-Ban Meta glasses to any RTMP server
  - Works with all major live streaming platforms worldwide
  - H.264 hardware encoding with adjustable bitrate
  - Real-time preview on phone while streaming
  - Timestamp smoothing for stable frame rate
  - 将 Ray-Ban Meta 眼镜的第一人称视角推流到任意 RTMP 服务器
  - 兼容全球所有主流直播平台
  - H.264 硬件编码，支持码率调节
  - 推流时手机可实时预览
  - 时间戳平滑处理，帧率稳定

#### Supported Platforms | 支持的平台

- YouTube Live
- Twitch
- Bilibili (B站)
- Douyin (抖音)
- TikTok
- Facebook Live
- Any RTMP-compatible server (MediaMTX, nginx-rtmp, etc.)
- 任意支持 RTMP 的服务器（MediaMTX、nginx-rtmp 等）

---

### v1.3.0 (2024-12-31)

#### New Features | 新功能

- **Wake Word Detection | 唤醒词检测**
  - Say "Jarvis" to trigger Quick Vision without touching the phone
  - Powered by Picovoice Porcupine
  - 说 "Jarvis" 触发快速识图，无需触摸手机
  - 基于 Picovoice Porcupine

- **Vision Model Selection | 视觉模型选择**
  - Choose from multiple vision models
  - Alibaba: Qwen VL Flash/Plus/Max, Qwen 2.5 VL 72B
  - OpenRouter: Search and select from all available models
  - Filter by vision-capable models
  - 支持选择多种视觉模型
  - 阿里云: Qwen VL Flash/Plus/Max, Qwen 2.5 VL 72B
  - OpenRouter: 搜索并选择所有可用模型
  - 可筛选仅显示视觉模型

- **App Language | 应用语言**
  - Switch app interface language (System/Chinese/English)
  - Auto-syncs output language when switching
  - 切换应用界面语言（跟随系统/中文/英文）
  - 切换时自动同步输出语言

#### Improvements | 改进

- **Quick Vision Flow | 快速识图流程**
  - Optimized capture flow: TTS → Start stream → Capture → Stop stream → Analyze → TTS result
  - Added debounce for wake word (prevents multiple triggers)
  - 优化拍照流程：TTS → 启动流 → 拍照 → 停止流 → 分析 → TTS 结果
  - 添加唤醒词防抖（防止多次触发）

- **Bilingual Support | 双语支持**
  - Full English/Chinese translation for all UI elements
  - AI prompts follow output language setting
  - 所有界面元素支持中英文
  - AI 提示词跟随输出语言设置

- **Default Models | 默认模型**
  - Alibaba: qwen-vl-flash (fast response)
  - OpenRouter: google/gemini-2.0-flash-001
  - 阿里云: qwen-vl-flash（快速响应）
  - OpenRouter: google/gemini-2.0-flash-001

#### Bug Fixes | 修复

- Fixed language switching not taking effect
- Fixed hardcoded Chinese strings in various screens
- Fixed Live AI reconnection issues
- 修复语言切换不生效的问题
- 修复多处界面硬编码中文
- 修复 Live AI 重连问题

---

## Setup | 配置

### API Keys | API 密钥

1. **Alibaba Dashscope** (for Vision & Live AI)
   - Get API Key: https://help.aliyun.com/zh/model-studio/get-api-key

2. **OpenRouter** (for Vision with various models)
   - Get API Key: https://openrouter.ai/keys

3. **Google AI Studio** (for Gemini Live)
   - Get API Key: https://aistudio.google.com/apikey

4. **Picovoice** (for Wake Word Detection)
   - Get Access Key: https://console.picovoice.ai/

---

## Requirements | 要求

- Android 12 (API 31) or higher
- Meta AI app **V282+**, Ray-Ban Meta firmware **V126+** (Meta Ray-Ban Display firmware **V125+**)
- The **DAT Wearables App** installed on the glasses (Meta AI app → Developer Mode) — required by DAT SDK 0.9.0
- Ray-Ban Meta / Meta Ray-Ban Display glasses paired via the Meta AI app
- Android 12（API 31）或更高版本
- Meta AI 应用 **V282+**，Ray-Ban Meta 固件 **V126+**（Meta Ray-Ban Display 固件 **V125+**）
- 眼镜上已安装 **DAT Wearables App**（Meta AI 应用 → 开发者模式）——DAT SDK 0.9.0 要求
- 通过 Meta AI 应用配对的 Ray-Ban Meta / Meta Ray-Ban Display 眼镜

---

## Build | 构建

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install to device
./gradlew installDebug
```

---

## Feedback | 反馈

If you encounter any issues, especially with:
- Google Gemini Live (not fully tested)
- Wake word detection
- Language switching

Please report issues or provide feedback.

如遇到任何问题，特别是：
- Google Gemini Live（未完全测试）
- 唤醒词检测
- 语言切换

请反馈问题或提供建议。

---

## License

MIT License

## DAT credentials (optional) | DAT 凭据（可选）

The build reads two optional keys from `android/local.properties` (git-ignored) and injects them into
`AndroidManifest.xml` as `com.meta.wearable.mwdat.APPLICATION_ID` / `CLIENT_TOKEN`.
When absent both default to `0`, which is what Meta AI **Developer Mode** expects.

```properties
mwdat_application_id=YOUR_APPLICATION_ID
mwdat_client_token=YOUR_CLIENT_TOKEN
```

构建会从 `android/local.properties` 读取这两个可选键并写入清单；未设置时默认为 `0`（Meta AI 开发者模式）。
