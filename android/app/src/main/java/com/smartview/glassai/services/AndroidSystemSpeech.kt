package com.smartview.glassai.services

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Real platform boundary: initialized lazily and owned by one TTSService instance. */
internal class AndroidSystemSpeech(private val context: Context) : SystemSpeech {
    private class Run(val job: Job, val id: String)
    private class Engine {
        val ready = CompletableDeferred<Boolean>()
        var tts: TextToSpeech? = null
        var owner: Run? = null
    }
    private val lock = Any()
    private val execution = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private var active: Run? = null
    private var engine: Engine? = null
    private var closed = false

    override suspend fun speak(text: String, languageCode: String): Boolean = coroutineScope {
        ensureActive()
        val run = Run(coroutineContext.job, "speech_${sequence.incrementAndGet()}")
        synchronized(lock) {
            if (closed || text.isBlank()) return@coroutineScope false
            active?.job?.cancel()
            active = run
        }
        try {
            execution.withLock {
                var slot: Engine? = null
                try {
                    withContext(Dispatchers.Main.immediate) {
                        ensureActive()
                        slot = getEngine()
                        val current = slot ?: return@withContext false
                        if (withTimeoutOrNull(3_000) { current.ready.await() } != true) return@withContext false
                        ensureActive()
                        val tts = current.tts ?: return@withContext false
                        val locale = Locale.forLanguageTag(languageCode.replace('_', '-'))
                            .takeIf { it.language.isNotBlank() && it.language != "auto" } ?: Locale.getDefault()
                        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return@withContext false
                        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        tts.setSpeechRate(1.1f)
                        // The await is cancellable; an uncompleted child Job would keep the
                        // outer scope alive after its timeout when an engine omits callbacks.
                        val completion = CompletableDeferred<Boolean>()
                        val listener = object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) { if (utteranceId == run.id) completion.complete(true) }
                            override fun onError(utteranceId: String?) { if (utteranceId == run.id) completion.complete(false) }
                            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                if (utteranceId == run.id) completion.complete(false)
                            }
                        }
                        synchronized(lock) {
                            ensureActive()
                            if (closed) return@withContext false
                            current.owner = run
                            if (tts.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS ||
                                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, run.id) != TextToSpeech.SUCCESS) {
                                completion.complete(false)
                            }
                        }
                        withTimeoutOrNull(60_000) { completion.await() } ?: false
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { slot?.let { stopOwner(it, run) } }
                }
            }
        } finally {
            synchronized(lock) { if (active === run) active = null }
        }
    }

    /** Main only; callbacks complete a detached readiness promise, never mutable engine state. */
    private fun getEngine(): Engine? {
        val slot = synchronized(lock) {
            if (closed) return null
            engine ?: Engine().also { engine = it }
        }
        if (slot.tts == null) {
            val tts = TextToSpeech(context) { status -> slot.ready.complete(status == TextToSpeech.SUCCESS) }
            synchronized(lock) {
                if (closed || engine !== slot) {
                    tts.shutdown()
                    slot.ready.complete(false)
                    return null
                }
                slot.tts = tts
            }
        }
        return slot
    }

    private fun stopOwner(slot: Engine, run: Run) = synchronized(lock) {
        if (slot.owner === run) {
            slot.owner = null
            runCatching { slot.tts?.setOnUtteranceProgressListener(null) }
            runCatching { slot.tts?.stop() }
        }
    }

    override fun stop() {
        val owner = synchronized(lock) {
            active?.job?.cancel()
            engine?.let { slot -> slot.owner?.let { run -> run.job.cancel(); slot to run } }
        }
        if (owner != null) onMain { stopOwner(owner.first, owner.second) }
    }

    override fun close() {
        val slot = synchronized(lock) {
            if (closed) return
            closed = true
            active?.job?.cancel()
            engine?.owner?.job?.cancel()
            engine.also { engine = null }
        }
        if (slot != null) onMain {
            slot.ready.complete(false)
            slot.owner?.let { stopOwner(slot, it) }
            runCatching { slot.tts?.shutdown() }
            slot.tts = null
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
