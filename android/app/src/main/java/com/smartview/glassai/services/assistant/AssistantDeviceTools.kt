package com.smartview.glassai.services.assistant

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.smartview.glassai.TurboMetaApplication
import com.smartview.glassai.bridge.NotificationBridgeRuntime
import com.smartview.glassai.glasses.*
import com.smartview.glassai.services.openclaw.OpenClawIntegration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** The custom model controls only these app actions, in a visible, user-started assistant session. */
class AssistantDeviceTools(private val app: Application) : AssistantTools {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val origin = Any()
    private val owner = "CustomAssistant-${System.identityHashCode(this)}"
    private var entered = false
    private var claimed = false
    private var startJob: Job? = null
    private var displayJob: Job? = null
    private var sink: GlassesDisplaySink? = null
    private var privateReply = false
    private var privateDisplay: GlassesDisplay? = null
    private var privateEpoch: Long? = null
    private var ownCard: DisplayCard.QuickVision? = null
    private val sessions get() = GlassesSessionManager.getInstance(app)
    private val isForeground get() = (app as? TurboMetaApplication)?.isInForeground == true
    private fun bluetoothGranted() = ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun enter() {
        entered = true
        if (claimed || !bluetoothGranted()) return
        GlassesDisplayIntegration.ensureStarted(app)
        sessions.startMonitoring()
        sink = GlassesDisplayIntegration.displayManager(app).ownedSink(origin)
        if (displayJob == null) displayJob = scope.launch {
            sessions.privateDisplayEpoch.collect { epoch ->
                if (privateReply && privateEpoch != null && privateEpoch != epoch) {
                    privateDisplay = null
                    privateEpoch = null
                    sink?.clear()
                }
            }
        }
        claimed = true
        startJob = scope.launch {
            if (sessions.acquireAndStart(owner, 12_000) != SessionStartResult.STARTED) {
                sessions.release(owner); claimed = false
            }
        }
    }

    fun leave() {
        entered = false
        startJob?.cancel(); startJob = null
        displayJob?.cancel(); displayJob = null
        clearReply()
        if (claimed) { sessions.release(owner); claimed = false }
        scope.coroutineContext.cancelChildren()
    }

    /** Preserve normal answers for follow-up paging, but retire private data between turns. */
    fun beginTurn(notificationsOnly: Boolean) {
        if (privateReply) clearReply()
        privateReply = notificationsOnly
        privateDisplay = null
        privateEpoch = null
    }

    suspend fun capture(): ByteArray = withContext(Dispatchers.Main.immediate) {
        requireVisible()
        if (!bluetoothGranted()) throw AssistantException("请先授予蓝牙权限并连接眼镜")
        enter()
        when (val result = OpenClawIntegration.frameProvider(app).snapshot(1280, 0.75, 15_000)) {
            is SnapshotResult.Ok -> { requireVisible(); result.frame.jpeg }
            SnapshotResult.PermissionRequired -> throw AssistantException("请先在助手页面点击拍照并授予眼镜相机权限")
            else -> throw AssistantException("暂时无法取得眼镜画面，请检查连接或其他相机功能是否正在使用")
        }
    }

    fun showReply(text: String) {
        if (!entered || !isForeground) return
        if (privateReply) {
            // Capture the display identity at notification read, never replay a late summary on
            // a different or resumed session. Phone-only summaries don't queue private lens data.
            if (!bluetoothGranted() || privateDisplay == null || sessions.currentDisplay() !== privateDisplay ||
                privateEpoch != sessions.privateDisplayEpoch.value ||
                sessions.sessionState.value != DeviceSessionState.STARTED || sessions.displayState.value != GlassesDisplayState.STARTED) return
            // WeChat's existing private-content lifecycle drops both queued and last-sent snapshots.
            ownCard = null
            sink?.show(DisplayCard.WeChat("通知摘要", text.take(12_000)))
        } else {
            ownCard = DisplayCard.QuickVision("自定义 AI", text.take(12_000))
            sink?.show(ownCard!!)
        }
    }

    fun clearReply() {
        privateReply = false; privateDisplay = null; privateEpoch = null; ownCard = null
        sink?.clear()
    }

    override suspend fun execute(name: String, arguments: JsonObject): AssistantToolResult = withContext(Dispatchers.Main.immediate) {
        requireVisible()
        validateAssistantArguments(name, arguments)
        when (name) {
            "camera_capture" -> AssistantToolResult("{\"captured\":true}", capture())
            "music_control" -> AssistantToolResult(JsonObject().apply {
                addProperty("result", NotificationBridgeRuntime.controlForAssistant(arguments["action"].asString, arguments.get("package_name")?.asString))
            }.toString())
            "notifications_read" -> {
                val notifications = NotificationBridgeRuntime.notificationsForAssistant(arguments.get("package_name")?.asString)
                privateReply = true
                privateDisplay = if (bluetoothGranted() && sessions.sessionState.value == DeviceSessionState.STARTED &&
                    sessions.displayState.value == GlassesDisplayState.STARTED) sessions.currentDisplay() else null
                privateEpoch = if (privateDisplay != null) sessions.privateDisplayEpoch.value else null
                AssistantToolResult(JsonArray().apply {
                    notifications.forEach { item -> add(JsonObject().apply {
                        addProperty("app", item.packageName); addProperty("title", item.title)
                        addProperty("text", item.text); addProperty("timestamp", item.timestamp)
                    }) }
                }.toString())
            }
            "display_card" -> {
                if (!bluetoothGranted()) throw AssistantException("眼镜蓝牙权限尚未开启")
                enter()
                if (sessions.sessionState.value != DeviceSessionState.STARTED || sessions.displayState.value != GlassesDisplayState.STARTED) {
                    throw AssistantException("眼镜显示尚未就绪，请检查 Display 开关与连接")
                }
                privateReply = false
                when (arguments["card"].asString) {
                    "answer" -> showReply(arguments["text"].asString)
                    "status" -> { ownCard = null; GlassesDisplayIntegration.displayManager(app).showStatus() }
                    "music" -> {
                        // The runtime publishes only opted-in, currently permitted media sessions.
                        val music = NotificationBridgeRuntime.music.value ?: throw AssistantException("当前没有可显示的音乐")
                        ownCard = null
                        sink?.show(DisplayCard.Music(music.title, music.artist, music.isPlaying, music.packageName, music.artJpeg))
                    }
                    "next_page", "previous_page" -> {
                        val card = GlassesDisplayIntegration.displayManager(app).ownedCard(origin) as? DisplayCard.QuickVision
                            ?: throw AssistantException("没有自己的回答卡片可以翻页")
                        val offset = if (arguments["card"].asString == "next_page") 1 else -1
                        ownCard = card.copy(page = (card.page + offset).coerceIn(0, card.pageCount() - 1))
                        sink?.showPage(ownCard!!)
                    }
                }
                AssistantToolResult("{\"displayed\":true}")
            }
            else -> throw AssistantException("未开放的助手操作")
        }
    }

    private fun requireVisible() {
        if (!entered || !isForeground) throw AssistantException("请在手机打开自定义 AI 页面后再操作")
    }
}
