package com.smartview.glassai.glasses

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Looper
import android.util.Log
import com.smartview.glassai.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow

sealed interface NavigationRequest {
    data object LiveAI : NavigationRequest
    data object LeanEat : NavigationRequest
    data object OpenClaw : NavigationRequest
}

interface LiveAiController { fun end() }
interface OpenClawController { fun snapAndSend() }

/** Register in ViewModel init; unregister in onCleared without clearing a newer instance. */
interface GlassesControllerRegistry {
    fun registerLiveAi(controller: LiveAiController)
    fun unregisterLiveAi(controller: LiveAiController)
    fun registerOpenClaw(controller: OpenClawController)
    fun unregisterOpenClaw(controller: OpenClawController)
}

/**
 * Main-thread action routing and controller registration for the process.
 * [navigation] must have replay = 0 and extraBufferCapacity = 4. Navigation taps without
 * a STARTED Activity collector are dropped; bringing the Activity forward is Phase E scope.
 */
class GlassesActionRouter(
    private val sink: GlassesDisplaySink,
    private val navigation: MutableSharedFlow<NavigationRequest>,
    private val startQuickVision: () -> Unit,
) : GlassesControllerRegistry {
    private var liveAi: LiveAiController? = null
    private var openClaw: OpenClawController? = null

    override fun registerLiveAi(controller: LiveAiController) {
        checkMain()
        liveAi = controller
    }

    override fun unregisterLiveAi(controller: LiveAiController) {
        checkMain()
        if (liveAi === controller) liveAi = null
    }

    override fun registerOpenClaw(controller: OpenClawController) {
        checkMain()
        openClaw = controller
    }

    override fun unregisterOpenClaw(controller: OpenClawController) {
        checkMain()
        if (openClaw === controller) openClaw = null
    }

    fun dispatch(action: DisplayAction) {
        checkMain()
        when (action) {
            DisplayAction.StartLiveAI -> navigate(NavigationRequest.LiveAI)
            DisplayAction.StartQuickVision -> {
                // A glasses tap can arrive while Android forbids foreground-service startup.
                // Log the rejected tap without foregrounding the Activity or retrying it later.
                try {
                    startQuickVision()
                } catch (error: ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "Quick Vision foreground-service start denied; dropping tap", error)
                } catch (error: SecurityException) {
                    Log.w(TAG, "Quick Vision service permission denied; dropping tap", error)
                }
            }
            DisplayAction.StartLeanEat -> navigate(NavigationRequest.LeanEat)
            DisplayAction.EndLiveAI -> liveAi?.end()
            is DisplayAction.Page -> sink.showPage(action.card)
            DisplayAction.BackToMenu -> sink.showStatus()
            DisplayAction.OpenClawSnap -> openClaw?.snapAndSend() ?: navigate(NavigationRequest.OpenClaw)
            DisplayAction.MusicPlayPause, DisplayAction.MusicNext, DisplayAction.MusicPrev ->
                Log.i(TAG, "Music action deferred to Phase E: $action")
        }
    }

    private fun navigate(request: NavigationRequest) {
        if (navigation.subscriptionCount.value == 0) {
            Log.w(TAG, "No started Activity collects navigation; dropping $request")
        } else if (!navigation.tryEmit(request)) {
            Log.w(TAG, "Navigation buffer full; dropping $request")
        }
    }

    private fun checkMain() {
        if (BuildConfig.DEBUG) {
            val main = Looper.getMainLooper()
            if (main != null) check(Looper.myLooper() === main) {
                "GlassesActionRouter must be called on the main thread"
            }
        }
    }

    private companion object {
        const val TAG = "GlassesActionRouter"
    }
}
