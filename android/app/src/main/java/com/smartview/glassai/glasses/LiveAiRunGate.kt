package com.smartview.glassai.glasses

/** Main-thread run identity; callbacks must recheck after hopping onto Main. */
internal class LiveAiRunGate {
    var generation = 0L
        private set
    var active = false
        private set
    fun begin(): Long { active = true; return ++generation }
    fun end() { active = false; generation++ }
    fun accepts(token: Long) = active && token == generation
}
