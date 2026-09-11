package com.smartview.glassai.glasses

enum class LiveAIPhase { CONNECTING, LISTENING, PROCESSING, SPEAKING, IDLE }

/** App icons mapped by name at the SDK boundary. */
enum class DisplayIcon {
    SMART_GLASSES, META_AI, STAR_CIRCLE_TRIANGLE_AI, MAGIC_WAND,
    SPEECH_BUBBLE, THREE_DOT_SPEECH_BUBBLE, SPEECH_BUBBLE_OFF,
    EYE, FOUR_CORNER_FRAME, VIDEO_CAMERA,
    FORK_KNIFE, PIZZA_SLICE, HEART, CODE,
    CHECKMARK, CHECKMARK_CIRCLE, X, EXCLAMATION_TRIANGLE, EXCLAMATION_CIRCLE, I_CIRCLE,
    ARROW_LEFT, ARROW_RIGHT, TRIANGLE_LEFT_VERTICAL_LINE, TRIANGLE_RIGHT_VERTICAL_LINE, TRIANGLE_RIGHT, TWO_LINES_PARALLEL,
    TWO_ARROWS_CLOCKWISE, LIGHT_BULB, THREE_HORIZONTAL_LINES, THREE_DOTS_HORIZONTAL,
    SPEAKER_WITH_THREE_ARCS, SPEAKER_OFF, MUSIC_NOTE, ENVELOPE_OPEN, HEADPHONES,
}

sealed interface DisplayAction {
    data object StartLiveAI : DisplayAction
    data object StartQuickVision : DisplayAction
    data object StartLeanEat : DisplayAction
    data object EndLiveAI : DisplayAction
    /** Show [card], which already carries the target page index. */
    data class Page(val card: DisplayCard) : DisplayAction
    data object BackToMenu : DisplayAction
    data object OpenClawSnap : DisplayAction
    data object MusicPlayPause : DisplayAction
    data object MusicNext : DisplayAction
    data object MusicPrev : DisplayAction
}

sealed interface DisplayCard {
    /** L0 menu. */
    data class Status(val deviceName: String, val liveAiReady: Boolean, val openClawConnected: Boolean) : DisplayCard
    /** Progress, error or fallback text, without buttons. */
    data class Notice(val title: String, val body: String, val icon: DisplayIcon = DisplayIcon.I_CIRCLE) : DisplayCard
    data class LiveAI(val phase: LiveAIPhase, val userText: String?, val assistantText: String, val isFinal: Boolean) : DisplayCard
    data class QuickVision(val modeName: String, val resultText: String, val page: Int = 0) : DisplayCard
    /** Nutrition values are whole numbers, mapped before entering the pure card layer. */
    data class LeanEat(
        val totalCalories: Int, val totalProtein: Int, val totalFat: Int, val totalCarbs: Int, val healthScore: Int,
        val foods: List<LeanEatFood>, val suggestions: List<String>, val page: Int = 0,
    ) : DisplayCard
    data class OpenClaw(val userText: String?, val replyText: String, val isFinal: Boolean, val page: Int = 0) : DisplayCard
    data class WeChat(
        val sender: String, val preview: String, val count: Int = 1, val timestamp: Long = 0, val page: Int = 0,
    ) : DisplayCard
    /** Album art is an in-memory JPEG, bounded to 240 pixels by the platform reader. */
    data class Music(
        val title: String, val artist: String, val isPlaying: Boolean, val app: String = "", val artJpeg: ByteArray? = null,
    ) : DisplayCard

    companion object {
        const val PAGE_CHARS = 280
        const val LIVE_AI_ASSISTANT_CHARS = 320
        const val USER_TEXT_CHARS = 120
    }
}

data class LeanEatFood(val name: String, val portion: String, val calories: Int)

/** Main text without layout; nutrition uses canonical units when no locale is supplied. */
fun DisplayCard.plainText(): String = when (this) {
    is DisplayCard.Status -> deviceName
    is DisplayCard.Notice -> body
    is DisplayCard.LiveAI -> assistantText
    is DisplayCard.QuickVision -> resultText
    is DisplayCard.LeanEat -> listOf(
        "$totalCalories kcal",
        "$totalProtein g · $totalFat g · $totalCarbs g",
        "$healthScore/100",
        detailText("kcal"),
    ).filter { it.isNotBlank() }.joinToString("\n")
    is DisplayCard.OpenClaw -> replyText
    is DisplayCard.WeChat -> preview
    is DisplayCard.Music -> artist
}

/** Text-only fallback also obeys the no-buttons layout budget; already bounded Notices retain identity. */
fun DisplayCard.fallbackNotice(strings: DisplayStrings): DisplayCard.Notice {
    val title = when (this) {
        is DisplayCard.Status -> deviceName
        is DisplayCard.Notice -> this.title
        is DisplayCard.LiveAI -> strings.liveAi
        is DisplayCard.QuickVision -> modeName
        is DisplayCard.LeanEat -> strings.leanEat
        is DisplayCard.OpenClaw -> strings.openClaw
        is DisplayCard.WeChat -> sender
        is DisplayCard.Music -> this.title
    }
    val boundedTitle = title.boundedDisplayText(DisplayCard.PAGE_CHARS, DisplayLayout.HEADER_LINES,
        DisplayLayout.HEADER_WIDTH / NodeTextStyle.HEADING.fontSize)
    val body = plainText().boundedDisplayText(DisplayCard.PAGE_CHARS, DisplayLayout.bodyLines(buttons = false), DisplayLayout.bodyColumns)
    if (this is DisplayCard.Notice) return if (this.title == boundedTitle && this.body == body) this else copy(title = boundedTitle, body = body)
    return DisplayCard.Notice(boundedTitle, body)
}

/**
 * Unpaged cards have one page. Supply [strings] for the same localized LeanEat page count as
 * [toNode]; callers without strings retain canonical "kcal". Counts use the render line budgets.
 */
fun DisplayCard.pageCount(strings: DisplayStrings? = null): Int = when (this) {
    is DisplayCard.QuickVision -> resultPages().size
    is DisplayCard.OpenClaw -> replyPages().size
    is DisplayCard.LeanEat -> 1 + detailPages(strings?.kcal ?: "kcal").size
    is DisplayCard.WeChat -> previewPages().size
    else -> 1
}

internal fun DisplayCard.QuickVision.resultPages(): List<String> = paginate(
    resultText, maxLines = DisplayLayout.bodyLines(paged = true), columns = DisplayLayout.bodyColumns,
)

internal fun DisplayCard.OpenClaw.replyPages(): List<String> = paginate(
    replyText, maxLines = DisplayLayout.bodyLines(hasUser = !userText.isNullOrBlank(), paged = true), columns = DisplayLayout.bodyColumns,
)

internal val DisplayCard.WeChat.hasMetadata: Boolean get() = count > 1 || timestamp > 0

internal fun DisplayCard.WeChat.previewPages(): List<String> = paginate(
    preview, maxLines = DisplayLayout.bodyLines(paged = true, metaLines = if (hasMetadata) 1 else 0),
    columns = DisplayLayout.bodyColumns,
)

internal fun DisplayCard.LeanEat.detailText(kcal: String): String =
    listOf(foods.joinToString("\n") { "${it.name} ${it.portion} ${it.calories} $kcal" }, suggestions.joinToString("\n"))
        .filter { it.isNotBlank() }.joinToString("\n")

internal fun DisplayCard.LeanEat.detailPages(kcal: String): List<String> =
    detailText(kcal).let {
        if (it.isBlank()) emptyList() else paginate(it, maxLines = DisplayLayout.bodyLines(paged = true), columns = DisplayLayout.bodyColumns)
    }
