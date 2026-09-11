package com.smartview.glassai.glasses

import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.meta.wearable.dat.display.types.DisplayError
import com.smartview.glassai.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What feature code talks to. Main thread. */
interface GlassesDisplaySink {
    fun show(card: DisplayCard)
    /** Paging keeps the originating feature's content ownership. */
    fun showPage(card: DisplayCard) = show(card)
    /** Forget the feature card and return to the L0 Status menu. */
    fun showStatus()
    /** Forget the feature card and clear the lenses. */
    fun clear()
}

fun interface DisplayStatusProvider {
    fun currentStatus(): DisplayCard.Status
}

/** One serial sender for the capability already attached by the shared session manager. */
class GlassesDisplayManager internal constructor(
    private val sessionManager: GlassesSessionManager,
    private val scope: CoroutineScope,
    private val strings: DisplayStrings,
    private val statusProvider: DisplayStatusProvider,
    private val mainDispatcher: CoroutineDispatcher,
    private val sendDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : GlassesDisplaySink {
    companion object {
        const val LIVE_AI_MIN_INTERVAL_MS = 600L
        private const val TAG = "GlassesDisplayManager"

        fun getInstance(context: Context): GlassesDisplayManager =
            GlassesDisplayIntegration.displayManager(context.applicationContext as Application)
    }

    private sealed class SendRequest {
        abstract val seq: Long
        data class Card(
            val card: DisplayCard, override val seq: Long, val isFallback: Boolean = false,
            val privateContent: Boolean = card is DisplayCard.WeChat,
        ) : SendRequest()
        data class Clear(override val seq: Long) : SendRequest()
    }

    private val pending = MutableStateFlow<SendRequest?>(null)
    private var sequence = 0L
    private var contentOwner: Any? = null
    private var lastLiveAiSentAt: Long? = null
    private val _currentCard = MutableStateFlow<DisplayCard?>(null)
    val currentCard: StateFlow<DisplayCard?> = _currentCard.asStateFlow()
    private val _lastSent = MutableStateFlow<DisplayCard?>(null)
    private var lastSentPrivate = false
    /** The last card accepted by the attached display; clear is not a card send. */
    val lastSent: StateFlow<DisplayCard?> = _lastSent.asStateFlow()

    /** Installed by integration on Main. Missing handlers cause taps to be logged and dropped. */
    var actionHandler: ((DisplayAction) -> Unit)? = null
        set(value) {
            checkMain()
            field = value
        }

    init {
        scope.launch(mainDispatcher) {
            pending.collectLatest { request ->
                // collectLatest can be joining a non-cancellable send while more values arrive.
                // Its captured next value can already be obsolete when that join finishes.
                if (request == null || request.seq != pending.value?.seq) return@collectLatest
                when (request) {
                    is SendRequest.Card -> sendCard(request)
                    is SendRequest.Clear -> clearDisplay()
                }
            }
        }
        scope.launch(mainDispatcher) {
            sessionManager.displayState.collect { state ->
                if (state == GlassesDisplayState.STARTED) {
                    enqueue(_currentCard.value ?: statusProvider.currentStatus())
                }
            }
        }
    }

    override fun show(card: DisplayCard) {
        checkMain()
        forgetPrivateSnapshot()
        contentOwner = null
        _currentCard.value = card
        enqueue(card)
    }

    /** A feature's cleanup must not clear content subsequently shown by another feature. */
    fun ownedSink(owner: Any): GlassesDisplaySink = object : GlassesDisplaySink {
        override fun show(card: DisplayCard) {
            this@GlassesDisplayManager.show(card)
            contentOwner = owner
        }
        override fun showStatus() {
            checkMain()
            if (contentOwner === owner) this@GlassesDisplayManager.showStatus()
        }
        override fun clear() {
            checkMain()
            if (contentOwner === owner) this@GlassesDisplayManager.clear()
        }
    }

    override fun showPage(card: DisplayCard) {
        checkMain()
        forgetPrivateSnapshot()
        _currentCard.value = card
        enqueue(card)
    }

    override fun showStatus() {
        checkMain()
        forgetPrivateSnapshot()
        contentOwner = null
        _currentCard.value = null
        enqueue(statusProvider.currentStatus())
    }

    override fun clear() {
        checkMain()
        forgetPrivateSnapshot()
        contentOwner = null
        _currentCard.value = null
        pending.value = SendRequest.Clear(++sequence)
    }

    private fun enqueue(card: DisplayCard, isFallback: Boolean = false, privateContent: Boolean = card is DisplayCard.WeChat) {
        pending.value = SendRequest.Card(card, ++sequence, isFallback, privateContent)
    }

    private fun forgetPrivateSnapshot() {
        // Keep normal send timing/history semantics, but notification previews are ephemeral.
        if (lastSentPrivate) { _lastSent.value = null; lastSentPrivate = false }
    }

    private fun startedDisplay(): GlassesDisplay? =
        if (sessionManager.displayState.value == GlassesDisplayState.STARTED) {
            sessionManager.currentDisplay()
        } else null

    private suspend fun sendCard(request: SendRequest.Card) {
        if (startedDisplay() == null) return // The next STARTED enqueues the current card.
        val card = request.card
        if (card is DisplayCard.LiveAI && !card.isFinal) {
            lastLiveAiSentAt?.let { last ->
                delay((LIVE_AI_MIN_INTERVAL_MS - (clock() - last)).coerceAtLeast(0))
            }
        }
        if (request.seq != pending.value?.seq) return
        val display = startedDisplay() ?: return
        val node = card.toNode(strings) // Session lookup and pure layout stay on Main.
        val onAction = actionCallback(display, request.seq)
        // Only this finite SDK operation and its bookkeeping are protected. DAT owns the
        // response timeout. The serial collector still cancels/coalesces all pre-send waits.
        withContext(NonCancellable) {
            val result = withContext(sendDispatcher) {
                display.sendContent { render(node, onAction) }
            }
            // Detached capabilities may finish late. They cannot update or enqueue for a new one.
            if (sessionManager.currentDisplay() === display) onSendResult(request, result)
        }
    }

    private fun onSendResult(request: SendRequest.Card, result: DisplaySendResult) {
        when (result) {
            DisplaySendResult.Sent -> {
                if (!request.privateContent || request.seq == pending.value?.seq) {
                    _lastSent.value = request.card
                    lastSentPrivate = request.privateContent
                }
                if (request.card is DisplayCard.LiveAI) lastLiveAiSentAt = clock()
            }
            is DisplaySendResult.Failed -> when (result.error) {
                DisplayError.INVALID_SESSION_STATE -> Unit // Wait for the next STARTED.
                DisplayError.RENDERING_FAILED -> {
                    Log.e(TAG, "Display rendering failed; fallback=${request.isFallback}")
                    // A failing old render must never replace a newer card, menu, or clear.
                    if (!request.isFallback && request.seq == pending.value?.seq && startedDisplay() != null) {
                        enqueue(request.card.fallbackNotice(strings), isFallback = true, privateContent = request.privateContent)
                    }
                }
                DisplayError.DEVICE_DISCONNECTED, DisplayError.UNEXPECTED_ERROR ->
                    Log.e(TAG, "Display send failed: ${result.error}")
            }
        }
    }

    private suspend fun clearDisplay() {
        val display = startedDisplay() ?: return // Clear is never replayed on STARTED.
        withContext(NonCancellable) {
            val result = withContext(sendDispatcher) { display.clearDisplay() }
            if (sessionManager.currentDisplay() === display && result is DisplaySendResult.Failed) {
                Log.e(TAG, "Display clear failed: ${result.error}")
            }
        }
    }

    /** SDK callbacks read or change no manager state until they have hopped to Main. */
    @VisibleForTesting
    internal fun dispatchFromSdk(action: DisplayAction) {
        scope.launch(mainDispatcher) {
            val handler = actionHandler
            if (handler != null) handler(action) else Log.d(TAG, "No display action handler; dropping ${action.javaClass.simpleName}")
        }
    }

    /** Capture on Main while building a card, then check again after a queued SDK tap. */
    internal fun actionCallback(
        display: GlassesDisplay? = startedDisplay(),
        originSequence: Long = sequence,
    ): (DisplayAction) -> Unit {
        checkMain()
        return { action ->
            scope.launch(mainDispatcher) {
                if (display != null && display === startedDisplay() && originSequence == sequence) {
                    actionHandler?.invoke(action)
                }
            }
        }
    }

    private fun checkMain() {
        if (BuildConfig.DEBUG) {
            val main = Looper.getMainLooper()
            if (main != null) check(Looper.myLooper() === main) {
                "GlassesDisplayManager must be called on the main thread"
            }
        }
    }
}
