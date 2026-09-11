package com.smartview.glassai.services

import android.content.Context
import com.smartview.glassai.managers.APIProvider
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Feature-owned speech. Cancellation propagates; false means no completed audible utterance. */
class TTSService internal constructor(
    private val configuration: () -> CloudSpeechConfig?,
    private val cloud: CloudSpeech,
    private val system: SystemSpeech,
    private val playback: Pcm16Playback,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val timeoutMs: Long = 60_000,
) {
    constructor(context: Context) : this(
        configuration = context.applicationContext.let { app ->
            {
                val providers = APIProviderManager.getInstance(app)
                val provider = providers.currentProvider.value
                val region = providers.alibabaEndpoint.value
                if (provider == APIProvider.OPENROUTER) null else {
                    val key = APIKeyManager.getInstance(app).getAPIKey(APIProvider.ALIBABA, region)
                    cloudSpeechConfig(provider, region, key)
                }
            }
        },
        cloud = HttpCloudSpeech(),
        system = AndroidSystemSpeech(context.applicationContext),
        playback = AudioTrackPcm16Playback(),
    )

    private val lock = Any()
    private val execution = Mutex()
    private var active: Job? = null
    private var closed = false

    suspend fun speak(text: String, languageCode: String): Boolean = coroutineScope {
        ensureActive()
        val run = coroutineContext.job
        synchronized(lock) {
            if (closed) return@coroutineScope false
            active?.cancel()
            active = run
        }
        try {
            execution.withLock {
                withContext(dispatcher) {
                    ensureActive()
                    try {
                        withTimeoutOrNull(timeoutMs) {
                            val chunks = splitSpeechText(text)
                            if (chunks.isEmpty()) return@withTimeoutOrNull false
                            val config = configuration()
                            val language = speechLanguage(languageCode)
                            var useCloud = config != null
                            for (chunk in chunks) {
                                ensureActive()
                                if (chunk.isBlank()) continue
                                if (useCloud) {
                                    try {
                                        playback.play(cloud.audio(chunk, language, config!!))
                                        continue
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) {
                                        ensureActive()
                                        playback.stop()
                                        // Retry the failed chunk only; previous completed chunks are not repeated.
                                        useCloud = false
                                    }
                                }
                                if (!system.speak(chunk, languageCode)) return@withTimeoutOrNull false
                            }
                            true
                        } ?: false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    finally {
                        playback.stop()
                        system.stop()
                    }
                }
            }
        } finally {
            synchronized(lock) { if (active === run) active = null }
        }
    }

    fun stop() {
        synchronized(lock) {
            active?.cancel()
            playback.stop()
            system.stop()
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stop()
            system.close()
        }
    }
}

internal fun cloudSpeechConfig(provider: APIProvider, region: AlibabaEndpoint, key: String?): CloudSpeechConfig? {
    if (provider != APIProvider.ALIBABA || key.isNullOrBlank()) return null
    val url = when (region) {
        AlibabaEndpoint.BEIJING -> "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        AlibabaEndpoint.SINGAPORE -> "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    }
    return CloudSpeechConfig(url, key)
}
