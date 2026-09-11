package com.smartview.glassai.glasses

import android.content.Context
import com.smartview.glassai.R

/** Resolve against the current Application resources on each render, without caching a locale. */
class ResourceDisplayStrings(context: Context) : DisplayStrings {
    private val context = context.applicationContext

    override val liveAi: String get() = context.getString(R.string.feature_liveai_title)
    override val quickVision: String get() = context.getString(R.string.feature_quickvision_title)
    override val leanEat: String get() = context.getString(R.string.feature_leaneat_title)
    override val openClaw: String get() = context.getString(R.string.openclaw_title)
    override val connecting: String get() = context.getString(R.string.connecting)
    override val listening: String get() = context.getString(R.string.liveai_listening)
    override val processing: String get() = context.getString(R.string.processing)
    override val speaking: String get() = context.getString(R.string.liveai_speaking)
    override val connected: String get() = context.getString(R.string.liveai_connected)
    override val needsApiKey: String get() = context.getString(R.string.display_status_needs_api_key)
    override val disconnected: String get() = context.getString(R.string.disconnected)
    override val prev: String get() = context.getString(R.string.display_prev)
    override val next: String get() = context.getString(R.string.display_next)
    override val done: String get() = context.getString(R.string.display_done)
    override val again: String get() = context.getString(R.string.display_again)
    override val snap: String get() = context.getString(R.string.display_snap)
    override val end: String get() = context.getString(R.string.display_end)
    override val looking: String get() = context.getString(R.string.display_looking)
    override val analyzing: String get() = context.getString(R.string.display_analyzing)
    override val calories: String get() = context.getString(R.string.leaneat_calories)
    override val protein: String get() = context.getString(R.string.leaneat_protein)
    override val fat: String get() = context.getString(R.string.leaneat_fat)
    override val carbs: String get() = context.getString(R.string.leaneat_carbs)
    override val kcal: String get() = context.getString(R.string.leaneat_kcal)
    override val gram: String get() = context.getString(R.string.leaneat_gram)
    override val healthScore: String get() = context.getString(R.string.leaneat_health_score)
}
