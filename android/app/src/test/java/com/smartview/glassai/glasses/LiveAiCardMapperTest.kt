package com.smartview.glassai.glasses

import com.smartview.glassai.viewmodels.OmniRealtimeViewModel.ViewState
import org.junit.Assert.*
import org.junit.Test

class LiveAiCardMapperTest {
    @Test fun partialUsesCurrentTextAndFinalUsesCompletedReply() {
        val partial = LiveAiCardMapper.map(ViewState.Speaking, "question", "part", "old")
        assertEquals("part", partial.assistantText)
        assertFalse(partial.isFinal)
        val final = LiveAiCardMapper.map(ViewState.Connected, "", "", "answer")
        assertEquals("answer", final.assistantText)
        assertTrue(final.isFinal)
        assertNull(final.userText)
    }
    @Test fun phasesMatchLiveAiState() {
        listOf(ViewState.Connecting to LiveAIPhase.CONNECTING, ViewState.Recording to LiveAIPhase.LISTENING,
            ViewState.Processing to LiveAIPhase.PROCESSING, ViewState.Speaking to LiveAIPhase.SPEAKING,
            ViewState.Idle to LiveAIPhase.IDLE, ViewState.Error("error") to LiveAIPhase.IDLE).forEach { (state, phase) ->
            assertEquals(phase, LiveAiCardMapper.map(state, "", "", null).phase)
        }
    }
}
