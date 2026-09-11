package com.smartview.glassai.glasses

import org.junit.Assert.*
import org.junit.Test

class LiveAiRunGateTest {
    @Test fun lateSetupAndTranscriptCannotReactivateAnEndedOrReplacedRun() {
        val gate = LiveAiRunGate()
        val first = gate.begin()
        assertTrue(gate.accepts(first))
        gate.end()
        assertFalse(gate.accepts(first))
        val second = gate.begin()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }
}
