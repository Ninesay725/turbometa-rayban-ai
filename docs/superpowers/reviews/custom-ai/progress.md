# 进度 — 2026-09-11-custom-ai-assistant

基线 `f470179`，分支 `android-v2`。本轮应用实现、范围审查与可执行的本地验证已完成。原有 `.agents/`、`.codex/`、`AGENTS.md` 与交接目录保留。真实设备/服务见 [待验清单](hardware-checklist.md)。

## 已实现

- 自定义 AI：独立 Chat Completions 配置、加密密钥、图片/工具能力开关，中文聊天/翻译、拍照提问、系统听写、现有 TTS 朗读与共享 Display 卡片。
- 有界且可取消的模型工具循环，仅允许眼镜拍照、自己的卡片和已授权媒体控制；未知动作、非法参数、过深/非标准 JSON、重复调用及重定向均受限。
- 通知摘要：独立允许列表和授权；只读摘要请求不提供动作工具，不进入普通聊天上下文。内存缓存与已复制的摘要在权限/锁屏/系统隐藏/断连时按失效代数取消或清除。
- QQ 音乐：默认媒体包迁移，保留自定义列表，具体控制取决于 Android 活动媒体会话。
- OpenClaw：对照发布版 v2026.9.4 的协议 4 节点握手、绑定网关的配对凭据、节点事件聊天和增量回复；保留显式官方 3–4 / 旧版自定义 3 兼容项。认证不自动降级；配对后需显式重新连接；重连/重新订阅使用新服务器会话，防止迟到回复串入新请求。没有升级任何服务器。

## 本次最终实际验证

| 检查 | 本次结果 |
| --- | --- |
| Debug JVM | 616 tests，0 failures/errors/skips |
| Release JVM | 604 tests，0 failures/errors/skips |
| 构建 | assembleDebug、assembleRelease、assembleDebugAndroidTest 成功 |
| 模拟器 API 31 | [最终记录](final-instrumentation.txt)：OK (69 tests)，70 项中另 1 项历史 SDK 压力测试显式忽略 |
| 新协议 Node.js fixture | 4/4 通过；是本地假网关，不是真实部署 OpenClaw |
| 中文资源 | 642/642，键集相同，无重复键 |
| 实际页面 | 新入口、未配置提示、无密钥回环配置保存、发送问题、固定中文回复、清空：通过。[截图](screens/local-reply.png) |
| 设备链路测试 | 共享 DAT MockDeviceKit 相机 → 自定义工具 → 本地 HTTP 模型图片回传；协议 4 相机命令；通知解析/投递/权限失效与媒体会话：通过 |

最终 Gradle 输出：`/tmp/custom-ai-final-verification.log`（Git Bash 临时目录）。Debug APK SHA-256：`0D40B03886C9A623686575726D26D6B3377E289952DC2318695F9132102A991F`。仍有现有 Android/Compose/加密 API 的弃用警告，无构建错误。

界面发送测试发生在最后网关/通知边界修复之前；UI 文件在该轮后未改，最终 APK 再经全套构建/仪器测试。测试配置此前不存在，测试后删除新建的无密钥配置、回环转发和设备端临时截图/XML；专用本地假模型进程已停止。未读取用户模型密钥或 `android/local.properties`。通知测试只使用自有测试通知并恢复临时桥接设置。

## 修复与审查记录

- 初始新增组合 12 项中通知投递 1 项超时。测试修正了分组摘要变回子通知时被 Android 取消的问题并增加具名诊断；最终套件通过。该失败记录保留在 [中间记录](intermediate-instrumentation.txt)。
- 实际 RED：HTTP 内核未实现编译失败；非严格 JSON/数组工具类型断言失败后修复；Display 私有失效代数先测后实现。取消 HTTP 的测试原先因假服务延迟写导致关闭超时，换为 NO_RESPONSE 后通过。
- 审查修复：私有镜片暂停重放、自己的卡片翻页/所有权、快速授权关闭再开启、系统隐藏通知后已复制摘要失效、网关重定向凭据泄漏、过期回复与旧计时器竞争、聊天忙碌时保留输入。最终范围复审无未解决的关键发现。
- 最终计时器回归曾因 Android 单测编译类路径没有 java.lang.management 失败，改用 Thread.State.BLOCKED 和超时栈帧定位后，616/604 全部通过。
- [桥接完整报告](bridge.md)、[OpenClaw 官方依据与完整报告](openclaw-compatibility.md)、[用户设置说明](../../../CUSTOM_AI_GUIDE.md)。报告中的早期“待测试”是阶段记录，以本页最终结果为准。

## 不计为已验证

真实模型回答/翻译质量和账号权限、实际 OpenClaw 服务器版本/配对/命令策略、实体 Meta Ray-Ban Display 镜片/按钮/音频、真机 Meta AI 首次相机授权往返、网易云/汽水/QQ 真正媒体会话、真实应用通知。本地 fixture 不能证明这些能力在实际服务或硬件上可用。

已记录的 DAT 0.9.0 快速会话重启压力问题未宣称修复；原有显式忽略项保留。没有合并 main 或发布声称已通过真机验收的版本。
