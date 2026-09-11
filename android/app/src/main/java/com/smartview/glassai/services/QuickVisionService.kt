package com.smartview.glassai.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.FrameConversions
import com.smartview.glassai.glasses.GlassesPhotoCapturer
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.PhotoCaptureOutcome
import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.DisplayIcon
import com.smartview.glassai.glasses.GlassesDisplayIntegration
import com.smartview.glassai.glasses.GlassesDisplaySink
import com.smartview.glassai.MainActivity
import com.smartview.glassai.R
import com.smartview.glassai.data.QuickVisionStorage
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.managers.QuickVisionModeManager
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Quick Vision Service
 * Background service for capturing and analyzing images from the glasses camera
 * Flow: Start stream → Capture photo → Stop stream → Analyze with Vision API → TTS announce
 * 1:1 port from iOS QuickVisionService
 */
class QuickVisionService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "QuickVisionService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "quick_vision_channel"
        private const val OWNER = "QuickVisionService"
        const val DISPLAY_OWNER = "QuickVisionDisplay"
        private const val FAIL_DWELL_FALLBACK_MS = 2_000L

        // Service actions
        const val ACTION_CAPTURE_AND_ANALYZE = "com.smartview.glassai.CAPTURE_AND_ANALYZE"
        const val ACTION_STOP = "com.smartview.glassai.STOP_QUICK_VISION"

        // Broadcast actions
        const val ACTION_ANALYSIS_COMPLETE = "com.smartview.glassai.ANALYSIS_COMPLETE"
        const val ACTION_QUICK_VISION_STATUS = "com.smartview.glassai.QUICK_VISION_STATUS"
        const val EXTRA_RESULT = "analysis_result"
        const val EXTRA_ERROR = "analysis_error"
        const val EXTRA_STATUS = "status"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val ttsInitialized = CompletableDeferred<Unit>()
    private var utteranceSequence = 0L
    private lateinit var apiKeyManager: APIKeyManager
    private lateinit var providerManager: APIProviderManager
    private lateinit var visionService: VisionAPIService
    private lateinit var quickVisionStorage: QuickVisionStorage
    private lateinit var modeManager: QuickVisionModeManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var systemLocale: Locale = Locale.getDefault()  // 系统语言，用于状态提示
    private var outputLocale: Locale = Locale.US  // 输出语言，用于AI回复

    // DAT SDK: the camera is borrowed from the process-wide session owner through GlassesPhotoCapturer
    private val sessionManager: GlassesSessionManager by lazy { GlassesSessionManager.getInstance(this) }
    private val runs = QuickVisionRunCoordinator(scope, ::cleanup, ::finishService)
    private val displaySink: GlassesDisplaySink by lazy {
        GlassesDisplayIntegration.displayManager(application).ownedSink(this)
    }
    private var displayClaimHeld = false
    private var hasDisplayCard = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()

        apiKeyManager = APIKeyManager.getInstance(this)
        providerManager = APIProviderManager.getInstance(this)
        visionService = VisionAPIService(apiKeyManager, providerManager, this)
        quickVisionStorage = QuickVisionStorage.getInstance(this)
        modeManager = QuickVisionModeManager.getInstance(this)

        // Initialize TTS
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        Log.d(TAG, "TTS onInit called with status: $status")
        if (status == TextToSpeech.SUCCESS) {
            // 保存系统语言（用于状态提示）
            systemLocale = Locale.getDefault()

            // 保存输出语言（用于AI回复）
            val language = apiKeyManager.getOutputLanguage()
            outputLocale = when (language) {
                "zh-CN" -> Locale.CHINESE
                "en-US" -> Locale.US
                "ja-JP" -> Locale.JAPANESE
                "ko-KR" -> Locale.KOREAN
                "es-ES" -> Locale("es", "ES")
                "fr-FR" -> Locale.FRENCH
                else -> Locale.US
            }

            // 初始使用系统语言（状态提示用）
            val result = tts?.setLanguage(systemLocale)
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

            tts?.setSpeechRate(1.1f)

            if (!isTtsReady) {
                tts?.setLanguage(Locale.getDefault())
                isTtsReady = true
            }
            Log.d(TAG, "TTS initialized - system: $systemLocale, output: $outputLocale")
        }
        ttsInitialized.complete(Unit)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand with action: ${intent?.action}")
        startForeground(NOTIFICATION_ID, createNotification(getLocalizedString("looking")))

        broadcastStatus("started")

        when (intent?.action) {
            ACTION_CAPTURE_AND_ANALYZE -> captureAndAnalyze()
            ACTION_STOP -> stopService()
            else -> captureAndAnalyze()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        runs.stop()
        cleanup()
        scope.cancel()
        tts?.stop()
        tts?.shutdown()

        // Broadcast finished so PorcupineWakeWordService can reset isProcessing
        broadcastStatus("finished")
        super.onDestroy()
    }

    private fun cleanup() {
        // A rerun invokes this only after the capturer's finally has completed. Destruction can
        // also call it as a synchronous safety net; no new run is accepted after runs.stop().
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)
        if (hasDisplayCard) {
            displaySink.showStatus()
            hasDisplayCard = false
        }
        if (displayClaimHeld) {
            displayClaimHeld = false
            sessionManager.release(DISPLAY_OWNER)
        }
    }

    private fun captureAndAnalyze() {
        Log.d(TAG, "captureAndAnalyze called")
        runs.request {
            try {
                // Wait for TTS to initialize
                withTimeoutOrNull(2000) { ttsInitialized.await() }

                // 1. Announce "looking"
                val lookingText = getLocalizedString("looking")
                Log.d(TAG, "Speaking: $lookingText")
                speak(lookingText)

                // Metadata can arrive after this service creates the manager. Wait before deciding
                // whether the result needs a session-only claim; the capturer then sees this device.
                val device = withTimeoutOrNull(GlassesPhotoCapturer.DEFAULT_DEVICE_WAIT_MS) {
                    sessionManager.activeDevice.first { it != null }
                }
                if (device == null) {
                    failAndDwell("no_device")
                    return@request
                }
                if (QuickVisionDisplayPolicy.wantsDisplayClaim(sessionManager.isDisplayAvailable.value)) {
                    sessionManager.acquire(DISPLAY_OWNER)
                    displayClaimHeld = true
                }
                showNotice(getString(R.string.display_looking), DisplayIcon.EYE)

                // 2. Borrow the shared session + camera and take the photo (see GlassesPhotoCapturer)
                Log.d(TAG, "Acquiring shared glasses session...")
                broadcastStatus("streaming")

                val capturer = GlassesPhotoCapturer(
                    sessionManager = sessionManager,
                    owner = OWNER,
                    config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
                    decodePhoto = ::decodePhoto,
                    decodeFrame = ::convertVideoFrameToBitmap,
                )
                val image: Bitmap = when (val outcome = capturer.capture()) {
                    is PhotoCaptureOutcome.Captured -> {
                        Log.d(
                            TAG,
                            "Image ready (fromVideoFrame=${outcome.fromVideoFrame}): " +
                                "${outcome.image.width}x${outcome.image.height}"
                        )
                        outcome.image
                    }
                    PhotoCaptureOutcome.NoDevice -> {
                        Log.e(TAG, "No device connected")
                        failAndDwell("no_device")
                        return@request
                    }
                    PhotoCaptureOutcome.SessionFailed,
                    PhotoCaptureOutcome.SessionTimeout -> {
                        Log.e(TAG, "Shared session unavailable: $outcome")
                        failAndDwell("error")
                        return@request
                    }
                    is PhotoCaptureOutcome.CameraUnavailable -> {
                        Log.e(TAG, "addCamera refused: ${outcome.error}")
                        // Another feature (e.g. the Live AI screen) holds the camera: say so and leave it alone
                        failAndDwell(if (outcome.error is CameraError.CameraBusy) "camera_busy" else "error")
                        return@request
                    }
                    is PhotoCaptureOutcome.StreamStartFailed -> {
                        Log.e(TAG, "stream.start failed: ${outcome.error.description}")
                        failAndDwell("error")
                        return@request
                    }
                    PhotoCaptureOutcome.StreamTimeout -> {
                        Log.e(TAG, "Stream did not reach STREAMING within the budget")
                        failAndDwell("error")
                        return@request
                    }
                    PhotoCaptureOutcome.Timeout -> {
                        Log.e(TAG, "Capture exceeded its total budget")
                        failAndDwell("error")
                        return@request
                    }
                    PhotoCaptureOutcome.NoImage -> {
                        Log.e(TAG, "No image captured")
                        failAndDwell("no_image")
                        return@request
                    }
                }

                // 3. The capturer has released its camera/claim; the display claim survives analysis.
                Log.d(TAG, "Analyzing captured image: ${image.width}x${image.height}")
                broadcastStatus("analyzing")
                showNotice(getString(R.string.display_analyzing), DisplayIcon.EYE)
                updateNotification(getLocalizedString("analyzing"))

                val language = apiKeyManager.getOutputLanguage()
                val result = visionService.quickVision(image, language)

                result.fold(
                    onSuccess = { description ->
                        Log.d(TAG, "Analysis result: $description")

                        // Save record with thumbnail
                        val prompt = modeManager.getPrompt()
                        val currentMode = modeManager.currentMode.value
                        val visionModel = providerManager.selectedModel.value
                        quickVisionStorage.saveRecord(
                            bitmap = image,
                            prompt = prompt,
                            result = description,
                            mode = currentMode,
                            visionModel = visionModel
                        )
                        Log.d(TAG, "Record saved with thumbnail")

                        broadcastResult(description)
                        broadcastStatus("complete")
                        runs.allowRerun()
                        hasDisplayCard = true
                        displaySink.show(DisplayCard.QuickVision(modeManager.currentMode.value.getDisplayName(this@QuickVisionService), description))
                        speakAndWait(description, useOutputLocale = true)  // AI reply uses the output language
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Analysis failed: ${error.message}")
                        speak(getLocalizedString("analysis_failed"))
                        broadcastError(error.message ?: "Unknown error")
                        broadcastStatus("error")
                        showNotice(getLocalizedString("analysis_failed"), DisplayIcon.EXCLAMATION_TRIANGLE)
                    }
                )

                delay(QuickVisionDisplayPolicy.dwellMs(sessionManager.displayState.value,
                    displayClaimHeld, success = result.isSuccess,
                    fallbackMs = QuickVisionDisplayPolicy.DEFAULT_DWELL_MS))
            } catch (e: CancellationException) {
                // A stop or Again cancels this run. The coordinator waits for its finalizers
                // and cleanup before allowing the next run to borrow the same owner name.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in captureAndAnalyze: ${e.message}", e)
                failAndDwell("error")
            }
        }
    }

    /** Announces [key] and dwells; the coordinator then releases the run and finishes the service. */
    private suspend fun failAndDwell(key: String) {
        speak(getLocalizedString(key))
        broadcastStatus("error")
        showNotice(getLocalizedString(key), DisplayIcon.EXCLAMATION_TRIANGLE)
        delay(QuickVisionDisplayPolicy.dwellMs(sessionManager.displayState.value,
            displayClaimHeld, success = false, fallbackMs = FAIL_DWELL_FALLBACK_MS))
    }

    private fun showNotice(body: String, icon: DisplayIcon) {
        hasDisplayCard = true
        displaySink.show(DisplayCard.Notice(modeManager.currentMode.value.getDisplayName(this), body, icon))
    }

    private fun decodePhoto(photo: PhotoData): Bitmap? = FrameConversions.decodePhoto(photo)

    private fun convertVideoFrameToBitmap(videoFrame: VideoFrame): Bitmap? =
        FrameConversions.frameToBitmap(videoFrame, FrameConversions.CAPTURE_JPEG_QUALITY)

    private fun speak(text: String, useOutputLocale: Boolean = false) {
        if (!isTtsReady || text.isBlank()) return
        // 根据需要切换语言
        tts?.setLanguage(if (useOutputLocale) outputLocale else systemLocale)
        Log.d(TAG, "Speaking: $text (locale: ${if (useOutputLocale) outputLocale else systemLocale})")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "qv_${System.currentTimeMillis()}")
    }

    private suspend fun speakAndWait(text: String, useOutputLocale: Boolean = false) {
        if (!isTtsReady || text.isBlank()) return
        val engine = tts ?: return
        val utteranceId = "qv_result_${++utteranceSequence}"
        val outcome = awaitQuickVisionSpeech(
            start = { complete ->
                engine.setLanguage(if (useOutputLocale) outputLocale else systemLocale)
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        if (id == utteranceId) complete(QuickVisionSpeechOutcome.DONE)
                    }
                    override fun onError(id: String?) {
                        if (id == utteranceId) complete(QuickVisionSpeechOutcome.ERROR)
                    }
                    override fun onStop(id: String?, interrupted: Boolean) {
                        if (id == utteranceId) complete(QuickVisionSpeechOutcome.STOPPED)
                    }
                }
                engine.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS &&
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
            },
            stop = {
                // Runs on the service's Main coroutine, also after timeout or cancellation.
                // Removing the old listener before stopping prevents it from affecting a rerun.
                engine.setOnUtteranceProgressListener(null)
                engine.stop()
            },
        )
        if (outcome != QuickVisionSpeechOutcome.DONE) Log.w(TAG, "Result speech ended: $outcome")
    }

    private fun getLocalizedString(key: String): String {
        // 使用系统语言来显示状态提示
        val isChinese = systemLocale.language == "zh"
        val isJapanese = systemLocale.language == "ja"
        val isKorean = systemLocale.language == "ko"

        return when (key) {
            "looking" -> when {
                isChinese -> "正在识别"
                isJapanese -> "確認中"
                isKorean -> "확인 중"
                else -> "Looking"
            }
            "analyzing" -> when {
                isChinese -> "正在分析..."
                isJapanese -> "分析中..."
                isKorean -> "분석 중..."
                else -> "Analyzing..."
            }
            "no_device" -> when {
                isChinese -> "眼镜未连接"
                isJapanese -> "メガネが接続されていません"
                isKorean -> "안경이 연결되어 있지 않습니다"
                else -> "Glasses not connected"
            }
            "no_image" -> when {
                isChinese -> "无法获取图像"
                isJapanese -> "画像を取得できません"
                isKorean -> "이미지를 캡처할 수 없습니다"
                else -> "Unable to capture image"
            }
            "error" -> when {
                isChinese -> "发生错误"
                isJapanese -> "エラーが発生しました"
                isKorean -> "오류가 발생했습니다"
                else -> "An error occurred"
            }
            "analysis_failed" -> when {
                isChinese -> "图像分析失败"
                isJapanese -> "画像分析に失敗しました"
                isKorean -> "이미지 분석 실패"
                else -> "Image analysis failed"
            }
            "camera_busy" -> getString(R.string.glasses_camera_busy)
            else -> key
        }
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(ACTION_QUICK_VISION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast status: $status")
    }

    private fun broadcastResult(result: String) {
        val intent = Intent(ACTION_ANALYSIS_COMPLETE).apply {
            putExtra(EXTRA_RESULT, result)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(error: String) {
        val intent = Intent(ACTION_ANALYSIS_COMPLETE).apply {
            putExtra(EXTRA_ERROR, error)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun stopService() {
        finishService()
    }

    private fun finishService() {
        Log.d(TAG, "Finishing service")
        runs.stop()
        cleanup()
        broadcastStatus("finished")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(message: String) {
        mainHandler.post {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification(message))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quick Vision",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Quick Vision image analysis"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick Vision")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
