package com.smartview.glassai.glasses

/** Metrics from the pinned DAT 0.9 TextStyle; shared with the phone preview. */
enum class NodeTextStyle(val fontSize: Int, val lineHeight: Int) {
    HEADING(40, 48), BODY(28, 36), META(22, 28),
}

/** Pure conservative layout budgets, not a claim of measured hardware glyph fit. */
object DisplayLayout {
    const val VIEWPORT = 600
    const val PADDING = 24
    const val GAP = 12
    const val BUTTON_HEIGHT = 88 // Pinned NovaRendererProfile, including button groups.
    const val HEADER_LINES = 2
    const val USER_LINES = 2
    const val CONTENT_WIDTH = VIEWPORT - PADDING * 2
    // Leave 48 units for a header glyph plus its 8-unit gap. Icon artwork remains approximate.
    const val HEADER_WIDTH = CONTENT_WIDTH - 56
    val bodyColumns: Int get() = CONTENT_WIDTH / NodeTextStyle.BODY.fontSize
    val metaColumns: Int get() = CONTENT_WIDTH / NodeTextStyle.META.fontSize

    /** Reserve the indicator even for page one, so discovering more pages cannot reduce its budget. */
    fun bodyLines(hasUser: Boolean = false, paged: Boolean = false, buttons: Boolean = true, bodyBlocks: Int = 1): Int {
        val userHeight = if (hasUser) USER_LINES * NodeTextStyle.META.lineHeight else 0
        val indicatorHeight = if (paged) NodeTextStyle.META.lineHeight else 0
        val buttonHeight = if (buttons) BUTTON_HEIGHT else 0
        val children = 1 + bodyBlocks + (if (hasUser) 1 else 0) + (if (paged) 1 else 0) + (if (buttons) 1 else 0)
        return (VIEWPORT - PADDING * 2 - HEADER_LINES * NodeTextStyle.HEADING.lineHeight -
            userHeight - indicatorHeight - buttonHeight - GAP * (children - 1)) / NodeTextStyle.BODY.lineHeight
    }
}
enum class NodeTextColor { PRIMARY, SECONDARY }
enum class NodeButtonStyle { PRIMARY, SECONDARY, OUTLINE }
enum class NodeBackground { NONE, CARD }
enum class NodeAlignment { START, CENTER, END, STRETCH }

sealed interface DisplayNode {
    data class Column(
        val children: List<DisplayNode>, val gap: Int = 0, val padding: Int = 0,
        val paddingTop: Int? = null, val paddingBottom: Int? = null, val paddingStart: Int? = null, val paddingEnd: Int? = null,
        val background: NodeBackground = NodeBackground.NONE,
        val alignment: NodeAlignment = NodeAlignment.START, val crossAlignment: NodeAlignment = NodeAlignment.START,
        val flexGrow: Float = 0f,
    ) : DisplayNode
    data class Row(
        val children: List<DisplayNode>, val gap: Int = 0, val padding: Int = 0,
        val paddingTop: Int? = null, val paddingBottom: Int? = null, val paddingStart: Int? = null, val paddingEnd: Int? = null,
        val background: NodeBackground = NodeBackground.NONE,
        val alignment: NodeAlignment = NodeAlignment.START, val crossAlignment: NodeAlignment = NodeAlignment.START,
        val flexGrow: Float = 0f,
    ) : DisplayNode
    data class Text(val text: String, val style: NodeTextStyle = NodeTextStyle.BODY, val color: NodeTextColor = NodeTextColor.PRIMARY, val flexGrow: Float = 0f) : DisplayNode
    data class Icon(val icon: DisplayIcon, val outline: Boolean = false) : DisplayNode
    data class Button(val label: String, val style: NodeButtonStyle = NodeButtonStyle.PRIMARY, val icon: DisplayIcon? = null, val action: DisplayAction) : DisplayNode
    data class ButtonGroup(val buttons: List<Button>, val alignment: NodeAlignment = NodeAlignment.CENTER) : DisplayNode
}

/** Localized labels supplied by resources in production and fixed values in JVM tests. */
interface DisplayStrings {
    val liveAi: String; val quickVision: String; val leanEat: String; val openClaw: String
    val connecting: String; val listening: String; val processing: String; val speaking: String; val connected: String
    val needsApiKey: String; val disconnected: String
    val prev: String; val next: String; val done: String; val again: String; val snap: String; val end: String
    val looking: String; val analyzing: String
    val calories: String; val protein: String; val fat: String; val carbs: String; val kcal: String; val gram: String; val healthScore: String
}

/** A single root, with transcript limits applied only when deriving the render tree. */
fun DisplayCard.toNode(strings: DisplayStrings): DisplayNode.Column = DisplayNode.Column(
    padding = DisplayLayout.PADDING,
    gap = DisplayLayout.GAP,
    children = buildList {
        when (val card = this@toNode) {
            is DisplayCard.Status -> {
                add(header(DisplayIcon.SMART_GLASSES, card.deviceName))
                val lines = DisplayLayout.bodyLines(bodyBlocks = 2) / 2
                add(boundedBody("${strings.liveAi} · ${if (card.liveAiReady) strings.connected else strings.needsApiKey}", lines, NodeTextColor.SECONDARY))
                add(boundedBody("${strings.openClaw} · ${if (card.openClawConnected) strings.connected else strings.disconnected}", lines, NodeTextColor.SECONDARY))
                addButtons(listOf(
                    DisplayNode.Button(strings.liveAi, NodeButtonStyle.PRIMARY, DisplayIcon.META_AI, DisplayAction.StartLiveAI),
                    DisplayNode.Button(strings.quickVision, NodeButtonStyle.SECONDARY, DisplayIcon.EYE, DisplayAction.StartQuickVision),
                    DisplayNode.Button(strings.leanEat, NodeButtonStyle.SECONDARY, DisplayIcon.FORK_KNIFE, DisplayAction.StartLeanEat),
                ))
            }
            is DisplayCard.Notice -> {
                add(header(card.icon, card.title))
                add(boundedBody(card.body, DisplayLayout.bodyLines(buttons = false)))
            }
            is DisplayCard.LiveAI -> {
                val (icon, label) = when (card.phase) {
                    LiveAIPhase.CONNECTING -> DisplayIcon.TWO_ARROWS_CLOCKWISE to strings.connecting
                    LiveAIPhase.LISTENING -> DisplayIcon.SPEECH_BUBBLE to strings.listening
                    LiveAIPhase.PROCESSING -> DisplayIcon.THREE_DOT_SPEECH_BUBBLE to strings.processing
                    LiveAIPhase.SPEAKING -> DisplayIcon.SPEAKER_WITH_THREE_ARCS to strings.speaking
                    LiveAIPhase.IDLE -> DisplayIcon.META_AI to strings.connected
                }
                add(header(icon, label))
                addUserText(card.userText)
                add(DisplayNode.Text(card.assistantText.boundedDisplayText(
                    DisplayCard.LIVE_AI_ASSISTANT_CHARS, DisplayLayout.bodyLines(hasUser = !card.userText.isNullOrBlank()),
                    DisplayLayout.bodyColumns, keepTail = true,
                )))
                addButtons(listOf(DisplayNode.Button(strings.end, NodeButtonStyle.OUTLINE, DisplayIcon.X, DisplayAction.EndLiveAI)))
            }
            is DisplayCard.QuickVision -> {
                val pages = card.resultPages()
                val page = card.page.coerceIn(0, pages.lastIndex)
                add(header(DisplayIcon.EYE, card.modeName))
                add(DisplayNode.Text(pages[page]))
                addPageIndicator(page, pages.size)
                addButtons(pageButtons(page, pages.size, strings) { card.copy(page = it) } + listOf(
                    DisplayNode.Button(strings.again, NodeButtonStyle.SECONDARY, DisplayIcon.TWO_ARROWS_CLOCKWISE, DisplayAction.StartQuickVision),
                    doneButton(strings),
                ))
            }
            is DisplayCard.LeanEat -> {
                val details = card.detailPages(strings.kcal)
                val count = 1 + details.size
                val page = card.page.coerceIn(0, count - 1)
                add(header(DisplayIcon.FORK_KNIFE, strings.leanEat))
                if (page == 0) {
                    // Share the total body budget across totals, macros and health score.
                    val lines = DisplayLayout.bodyLines(paged = true, bodyBlocks = 3)
                    add(boundedBody("${strings.calories} ${card.totalCalories} ${strings.kcal}", 2))
                    add(boundedBody("${strings.protein} ${card.totalProtein}${strings.gram} · ${strings.fat} ${card.totalFat}${strings.gram} · ${strings.carbs} ${card.totalCarbs}${strings.gram}", lines - 4, NodeTextColor.SECONDARY))
                    add(boundedBody("${strings.healthScore} ${card.healthScore}/100", 2))
                } else {
                    add(DisplayNode.Text(details[page - 1]))
                }
                addPageIndicator(page, count)
                addButtons(pageButtons(page, count, strings) { card.copy(page = it) } + doneButton(strings))
            }
            is DisplayCard.OpenClaw -> {
                val pages = card.replyPages()
                val page = card.page.coerceIn(0, pages.lastIndex)
                add(header(if (card.isFinal) DisplayIcon.CODE else DisplayIcon.THREE_DOT_SPEECH_BUBBLE, strings.openClaw))
                addUserText(card.userText)
                add(DisplayNode.Text(if (card.replyText.isBlank()) {
                    strings.processing.boundedDisplayText(DisplayCard.PAGE_CHARS,
                        DisplayLayout.bodyLines(hasUser = !card.userText.isNullOrBlank(), paged = true), DisplayLayout.bodyColumns)
                } else pages[page]))
                addPageIndicator(page, pages.size)
                addButtons(pageButtons(page, pages.size, strings) { card.copy(page = it) } + listOf(
                    DisplayNode.Button(strings.snap, NodeButtonStyle.PRIMARY, DisplayIcon.VIDEO_CAMERA, DisplayAction.OpenClawSnap),
                    doneButton(strings, NodeButtonStyle.OUTLINE),
                ))
            }
            is DisplayCard.WeChat -> {
                add(header(DisplayIcon.ENVELOPE_OPEN, card.sender))
                add(boundedBody(card.preview, DisplayLayout.bodyLines()))
                addButtons(listOf(doneButton(strings)))
            }
            is DisplayCard.Music -> {
                add(header(DisplayIcon.MUSIC_NOTE, card.title))
                add(boundedBody(card.artist, DisplayLayout.bodyLines(), NodeTextColor.SECONDARY))
                addButtons(listOf(
                    DisplayNode.Button(strings.prev, NodeButtonStyle.OUTLINE, DisplayIcon.TRIANGLE_LEFT_VERTICAL_LINE, DisplayAction.MusicPrev),
                    DisplayNode.Button("", NodeButtonStyle.PRIMARY, if (card.isPlaying) DisplayIcon.TWO_LINES_PARALLEL else DisplayIcon.TRIANGLE_RIGHT, DisplayAction.MusicPlayPause),
                    DisplayNode.Button(strings.next, NodeButtonStyle.OUTLINE, DisplayIcon.TRIANGLE_RIGHT_VERTICAL_LINE, DisplayAction.MusicNext),
                ))
            }
        }
    },
)

private fun header(icon: DisplayIcon, title: String) = DisplayNode.Row(
    children = listOf(DisplayNode.Icon(icon), DisplayNode.Text(title.boundedDisplayText(
        DisplayCard.PAGE_CHARS, DisplayLayout.HEADER_LINES, DisplayLayout.HEADER_WIDTH / NodeTextStyle.HEADING.fontSize,
    ), NodeTextStyle.HEADING)),
    gap = 8, crossAlignment = NodeAlignment.CENTER,
)

private fun MutableList<DisplayNode>.addUserText(text: String?) {
    if (!text.isNullOrBlank()) add(DisplayNode.Text(text.boundedDisplayText(
        DisplayCard.USER_TEXT_CHARS, DisplayLayout.USER_LINES, DisplayLayout.metaColumns,
    ), NodeTextStyle.META, NodeTextColor.SECONDARY))
}

private fun boundedBody(text: String, lines: Int, color: NodeTextColor = NodeTextColor.PRIMARY) =
    DisplayNode.Text(text.boundedDisplayText(DisplayCard.PAGE_CHARS, lines, DisplayLayout.bodyColumns), color = color)

private fun MutableList<DisplayNode>.addPageIndicator(page: Int, count: Int) {
    if (count > 1) add(DisplayNode.Text("${page + 1}/$count", NodeTextStyle.META, NodeTextColor.SECONDARY))
}

private fun MutableList<DisplayNode>.addButtons(buttons: List<DisplayNode.Button>) {
    add(DisplayNode.Row(
        children = listOf(DisplayNode.ButtonGroup(buttons)), gap = 8,
        alignment = NodeAlignment.CENTER, crossAlignment = NodeAlignment.CENTER,
    ))
}

private fun pageButtons(page: Int, count: Int, strings: DisplayStrings, cardAt: (Int) -> DisplayCard): List<DisplayNode.Button> = buildList {
    if (page > 0) add(DisplayNode.Button(strings.prev, NodeButtonStyle.OUTLINE, DisplayIcon.ARROW_LEFT, DisplayAction.Page(cardAt(page - 1))))
    if (page < count - 1) add(DisplayNode.Button(strings.next, NodeButtonStyle.OUTLINE, DisplayIcon.ARROW_RIGHT, DisplayAction.Page(cardAt(page + 1))))
}

private fun doneButton(strings: DisplayStrings, style: NodeButtonStyle = NodeButtonStyle.PRIMARY) =
    DisplayNode.Button(strings.done, style, DisplayIcon.CHECKMARK, DisplayAction.BackToMenu)
