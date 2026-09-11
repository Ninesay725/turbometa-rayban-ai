package com.smartview.glassai.glasses

class FakeControllerRegistry : GlassesControllerRegistry {
    var liveAi: LiveAiController? = null
    var openClaw: OpenClawController? = null
    var liveAiUnregisterCalls = 0
    var openClawUnregisterCalls = 0

    override fun registerLiveAi(controller: LiveAiController) { liveAi = controller }
    override fun unregisterLiveAi(controller: LiveAiController) {
        liveAiUnregisterCalls++
        if (liveAi === controller) liveAi = null
    }

    override fun registerOpenClaw(controller: OpenClawController) { openClaw = controller }
    override fun unregisterOpenClaw(controller: OpenClawController) {
        openClawUnregisterCalls++
        if (openClaw === controller) openClaw = null
    }
}
