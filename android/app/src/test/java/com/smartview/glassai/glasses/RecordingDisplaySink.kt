package com.smartview.glassai.glasses

class RecordingDisplaySink : GlassesDisplaySink {
    val shown = mutableListOf<DisplayCard>()
    var statusCalls = 0
    var clearCalls = 0
    val last: DisplayCard? get() = shown.lastOrNull()

    override fun show(card: DisplayCard) { shown += card }
    override fun showStatus() { statusCalls++ }
    override fun clear() { clearCalls++ }
}
