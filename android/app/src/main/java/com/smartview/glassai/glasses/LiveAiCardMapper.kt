package com.smartview.glassai.glasses

import com.smartview.glassai.viewmodels.OmniRealtimeViewModel.ViewState

object LiveAiCardMapper {
    fun map(viewState: ViewState, userTranscript: String, currentTranscript: String,
            lastAssistantMessage: String?): DisplayCard.LiveAI = DisplayCard.LiveAI(
        phase = when (viewState) {
            ViewState.Connecting -> LiveAIPhase.CONNECTING
            ViewState.Recording -> LiveAIPhase.LISTENING
            ViewState.Processing -> LiveAIPhase.PROCESSING
            ViewState.Speaking -> LiveAIPhase.SPEAKING
            else -> LiveAIPhase.IDLE
        },
        userText = userTranscript.takeIf { it.isNotBlank() },
        assistantText = currentTranscript.ifBlank { lastAssistantMessage.orEmpty() },
        isFinal = currentTranscript.isBlank(),
    )
}
