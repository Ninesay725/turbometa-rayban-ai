package com.smartview.glassai.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNodeTest {
    private val strings = FixedDisplayStrings()
    private val foodCard = DisplayCard.LeanEat(
        480, 24, 16, 60, 85, listOf(LeanEatFood("米饭", "一碗", 240)), listOf("多吃蔬菜"),
    )

    @Test fun explicitNewlinesArePagedBeforeTheyCanDisplaceControls() {
        val text = List(20) { "中".repeat(13) }.joinToString("\n")
        assertEquals(279, text.length)
        val card = DisplayCard.QuickVision("识别", text)
        assertTrue("20 BODY lines cannot be one page", card.pageCount() > 1)
        val nodes = (0 until card.pageCount()).map { card.copy(page = it).toNode(strings) }
        assertEquals(text, nodes.joinToString("") { body(it).first().text })
        nodes.forEach { node ->
            assertTrue(body(node).first().text.lines().size <= 8)
            assertTrue(buttons(node).any { it.action == DisplayAction.BackToMenu })
        }
    }

    @Test fun noticeBoundsExplicitLinesInsteadOfRepeatingAnOverflowingFallback() {
        val text = List(20) { "中".repeat(13) }.joinToString("\n")
        val card = DisplayCard.Notice("Notice", text)
        val rendered = body(card.toNode(strings)).single().text
        assertTrue(rendered.lines().size <= 12)
        assertTrue(rendered.endsWith("…"))
        assertTrue(card.fallbackNotice(strings).body.lines().size <= 12)
    }

    @Test fun pagedCardsPreserveAllContentAndReserveHeaderUserIndicatorAndButtons() {
        val title = List(20) { "标题" }.joinToString("\n")
        val user = List(20) { "问题😀" }.joinToString("\n")
        val text = List(20) { "中".repeat(13) }.joinToString("\n") + "\n" + "界😀".repeat(200)
        val localized = strings.copy(openClaw = title, leanEat = title)
        val quick = DisplayCard.QuickVision(title, text)
        val claw = DisplayCard.OpenClaw(user, text, true)
        val food = foodCard.copy(foods = emptyList(), suggestions = listOf(text))
        val quickNodes = (0 until quick.pageCount(localized)).map { quick.copy(page = it).toNode(localized) }
        val clawNodes = (0 until claw.pageCount(localized)).map { claw.copy(page = it).toNode(localized) }
        val foodNodes = (1 until food.pageCount(localized)).map { food.copy(page = it).toNode(localized) }
        for (nodes in listOf(quickNodes, clawNodes, foodNodes)) {
            assertEquals(text, nodes.joinToString("") { node -> body(node).single { it.style == NodeTextStyle.BODY }.text })
            nodes.forEach { node ->
                assertTrue("Reserved blocks must fit the model", modeledHeight(node) <= 600)
                assertTrue(body(node).last().text.contains('/'))
                assertTrue(buttons(node).any { it.action == DisplayAction.BackToMenu })
            }
        }
        clawNodes.forEach {
            assertEquals(2, conservativeDisplayLines(body(it).first().text, 25))
            assertTrue(buttons(it).any { button -> button.action == DisplayAction.OpenClawSnap })
        }
    }

    @Test fun nonpagedContentIsBoundedWithoutRemovingTheControlsOrChangingSourceData() {
        val title = List(20) { "标题" }.joinToString("\n")
        val text = List(30) { "界😀".repeat(10) }.joinToString("\n") + "最新"
        val cards = listOf(
            DisplayCard.Notice(title, text), DisplayCard.Status(title, true, true),
            DisplayCard.LiveAI(LiveAIPhase.SPEAKING, text, text, false),
            DisplayCard.WeChat(title, text), DisplayCard.Music(title, text, true),
            foodCard.copy(totalCalories = Int.MAX_VALUE, totalProtein = Int.MAX_VALUE,
                totalFat = Int.MAX_VALUE, totalCarbs = Int.MAX_VALUE),
        )
        cards.forEach { card ->
            val node = card.toNode(strings)
            assertTrue("${card::class.simpleName} exceeds the model", modeledHeight(node) <= 600)
            if (card !is DisplayCard.Notice) assertTrue(buttons(node).isNotEmpty())
        }
        val live = cards.filterIsInstance<DisplayCard.LiveAI>().single()
        val tail = body(live.toNode(strings)).last().text
        assertTrue(tail.startsWith("…"))
        assertTrue(tail.endsWith("最新"))
        assertEquals(text, live.assistantText)
        assertEquals(text, (cards.first() as DisplayCard.Notice).body)
    }

    @Test fun statusCardHasThreeStartButtons() {
        val node = DisplayCard.Status("Ray-Ban", true, false).toNode(strings)
        assertHeader(node, DisplayIcon.SMART_GLASSES, "Ray-Ban")
        assertEquals(listOf(
            DisplayNode.Text("liveAi · connected", color = NodeTextColor.SECONDARY),
            DisplayNode.Text("openClaw · disconnected", color = NodeTextColor.SECONDARY),
        ), body(node))
        assertEquals(listOf(
            DisplayNode.Button("liveAi", NodeButtonStyle.PRIMARY, DisplayIcon.META_AI, DisplayAction.StartLiveAI),
            DisplayNode.Button("quickVision", NodeButtonStyle.SECONDARY, DisplayIcon.EYE, DisplayAction.StartQuickVision),
            DisplayNode.Button("leanEat", NodeButtonStyle.SECONDARY, DisplayIcon.FORK_KNIFE, DisplayAction.StartLeanEat),
        ), buttons(node))
    }

    @Test fun statusReadinessUsesLocalizedLabels() {
        val localized = strings.copy(liveAi = "实时助手", needsApiKey = "请配置密钥", openClaw = "助手", connected = "已连接")
        assertEquals(listOf("实时助手 · 请配置密钥", "助手 · 已连接"),
            body(DisplayCard.Status("眼镜", false, true).toNode(localized)).map { it.text })
    }

    @Test fun noticeCardHasNoButtons() {
        val node = DisplayCard.Notice("Warning", "Try again", DisplayIcon.EXCLAMATION_TRIANGLE).toNode(strings)
        assertHeader(node, DisplayIcon.EXCLAMATION_TRIANGLE, "Warning")
        assertEquals(listOf(DisplayNode.Text("Try again")), body(node))
        assertEquals(2, node.children.size)
    }

    @Test fun liveAiKeepsTheNewestTailWithinItsLineAndCharacterLimits() {
        val card = DisplayCard.LiveAI(LiveAIPhase.SPEAKING, null, "old" + "x".repeat(320), false)
        assertEquals(listOf(DisplayNode.Text("…" + "x".repeat(170))), body(card.toNode(strings)))
        assertEquals(323, card.assistantText.length)
    }

    @Test fun liveAiReservesTwoLinesForTheUserTranscript() {
        val node = DisplayCard.LiveAI(LiveAIPhase.PROCESSING, "q".repeat(125), "answer", false).toNode(strings)
        assertEquals(DisplayNode.Text("q".repeat(49) + "…", NodeTextStyle.META, NodeTextColor.SECONDARY), body(node).first())
    }

    @Test fun transcriptLimitsDoNotSplitSurrogatePairs() {
        val node = DisplayCard.LiveAI(LiveAIPhase.SPEAKING,
            "q".repeat(48) + "😀" + "tail".repeat(20), "old".repeat(200) + "😀" + "a".repeat(131), true).toNode(strings)
        assertEquals(listOf("q".repeat(48) + "😀…", "…😀" + "a".repeat(131)), body(node).map { it.text })
    }

    @Test fun liveAiPhaseIconsAndLabels() {
        val expected = listOf(
            Triple(LiveAIPhase.CONNECTING, DisplayIcon.TWO_ARROWS_CLOCKWISE, "connecting"),
            Triple(LiveAIPhase.LISTENING, DisplayIcon.SPEECH_BUBBLE, "listening"),
            Triple(LiveAIPhase.PROCESSING, DisplayIcon.THREE_DOT_SPEECH_BUBBLE, "processing"),
            Triple(LiveAIPhase.SPEAKING, DisplayIcon.SPEAKER_WITH_THREE_ARCS, "speaking"),
            Triple(LiveAIPhase.IDLE, DisplayIcon.META_AI, "connected"),
        )
        for ((phase, icon, label) in expected) {
            assertHeader(DisplayCard.LiveAI(phase, null, "", false).toNode(strings), icon, label)
        }
    }

    @Test fun liveAiCardHasOnlyTheEndButton() {
        for (isFinal in listOf(false, true)) {
            val node = DisplayCard.LiveAI(LiveAIPhase.IDLE, null, "", isFinal).toNode(strings)
            assertEquals(listOf(DisplayNode.Button("end", NodeButtonStyle.OUTLINE, DisplayIcon.X, DisplayAction.EndLiveAI)), buttons(node))
        }
    }

    @Test fun blankUserTranscriptsAreOmitted() {
        for (user in listOf(null, "", "  \n")) {
            assertEquals(listOf("answer"), body(DisplayCard.LiveAI(LiveAIPhase.IDLE, user, "answer", true).toNode(strings)).map { it.text })
            assertEquals(listOf("answer"), body(DisplayCard.OpenClaw(user, "answer", true).toNode(strings)).map { it.text })
        }
    }

    @Test fun quickVisionFirstPageHasNoPrev() {
        val card = DisplayCard.QuickVision("识物", "a".repeat(280) + "b".repeat(280) + "c")
        val node = card.toNode(strings)
        assertHeader(node, DisplayIcon.EYE, "识物")
        assertEquals(listOf(DisplayNode.Text("a".repeat(152)), indicator("1/4")), body(node))
        assertEquals(listOf(next(card.copy(page = 1)), again(), done()), buttons(node))
    }

    @Test fun quickVisionMiddlePageHasPrevAndNextWithPageActions() {
        val card = DisplayCard.QuickVision("Vision", "x".repeat(561), page = 1)
        assertEquals(listOf(prev(card.copy(page = 0)), next(card.copy(page = 2)), again(), done()), buttons(card.toNode(strings)))
        assertEquals(indicator("2/4"), body(card.toNode(strings)).last())
    }

    @Test fun quickVisionLastPageHasNoNext() {
        val card = DisplayCard.QuickVision("Vision", "x".repeat(561), page = 3)
        assertEquals(listOf(prev(card.copy(page = 2)), again(), done()), buttons(card.toNode(strings)))
        assertEquals(listOf(DisplayNode.Text("x".repeat(105)), indicator("4/4")), body(card.toNode(strings)))
    }

    @Test fun quickVisionPageIsClampedInBothDirections() {
        val card = DisplayCard.QuickVision("Vision", "x".repeat(561))
        assertEquals(card.toNode(strings), card.copy(page = Int.MIN_VALUE).toNode(strings))
        assertEquals(card.copy(page = 3).toNode(strings), card.copy(page = Int.MAX_VALUE).toNode(strings))
    }

    @Test fun singlePageQuickVisionHasNoIndicatorOrPageButtons() {
        val node = DisplayCard.QuickVision("Vision", "short", page = 99).toNode(strings)
        assertEquals(listOf(DisplayNode.Text("short")), body(node))
        assertEquals(listOf(again(), done()), buttons(node))
    }

    @Test fun leanEatPageZeroShowsTotalsAndHealthScore() {
        val localized = strings.copy(leanEat = "轻食", calories = "热量", protein = "蛋白质", fat = "脂肪", carbs = "碳水", kcal = "千卡", gram = "克", healthScore = "健康评分")
        val node = foodCard.toNode(localized)
        assertHeader(node, DisplayIcon.FORK_KNIFE, "轻食")
        assertEquals(listOf(
            DisplayNode.Text("热量 480 千卡"),
            DisplayNode.Text("蛋白质 24克 · 脂肪 16克 · 碳水 60克", color = NodeTextColor.SECONDARY),
            DisplayNode.Text("健康评分 85/100"),
            indicator("1/2"),
        ), body(node))
        assertEquals(listOf(next(foodCard.copy(page = 1)), done()), buttons(node))
    }

    @Test fun leanEatFoodsPagesFollowTheTotals() {
        val card = foodCard.copy(page = 1)
        val node = card.toNode(strings.copy(kcal = "千卡"))
        assertEquals(listOf(DisplayNode.Text("米饭 一碗 240 千卡\n多吃蔬菜"), indicator("2/2")), body(node))
        assertEquals(listOf(prev(card.copy(page = 0)), done()), buttons(node))
    }

    @Test fun leanEatOmitsBlankDetailsAndClampsToTotals() {
        val card = foodCard.copy(foods = emptyList(), suggestions = listOf("  ", ""))
        val node = card.copy(page = 20).toNode(strings)
        assertEquals(3, body(node).size)
        assertEquals(listOf(done()), buttons(node))
        assertEquals(node, card.copy(page = -10).toNode(strings))
    }

    @Test fun leanEatPagesSuggestionsWithoutFoodsAndClampsNavigation() {
        val card = foodCard.copy(foods = emptyList(), suggestions = listOf("x".repeat(561)), page = 2)
        val node = card.toNode(strings)
        assertEquals(listOf(DisplayNode.Text("x".repeat(152)), indicator("3/5")), body(node))
        assertEquals(listOf(prev(card.copy(page = 1)), next(card.copy(page = 3)), done()), buttons(node))
        assertEquals(card.copy(page = 4).toNode(strings), card.copy(page = 99).toNode(strings))
        assertEquals(card.copy(page = 0).toNode(strings), card.copy(page = -99).toNode(strings))
    }

    @Test fun leanEatNavigationCountsTheLocalizedDetailPages() {
        val card = foodCard.copy(foods = listOf(LeanEatFood("Rice", "bowl", 1)), suggestions = emptyList())
        assertEquals(2, card.pageCount())
        val localized = strings.copy(kcal = List(12) { "单位" }.joinToString("\n"))
        val count = card.pageCount(localized)
        assertTrue(count > card.pageCount())
        assertEquals(indicator("1/$count"), body(card.toNode(localized)).last())
        val details = (1 until count).map { card.copy(page = it).toNode(localized) }
        assertEquals("Rice bowl 1 ${localized.kcal}", details.joinToString("") { body(it).first().text })
        assertEquals(listOf(prev(card.copy(page = count - 2)), done()), buttons(details.last()))
    }

    @Test fun openClawPendingUsesTheThreeDotIconAndProcessingText() {
        val node = DisplayCard.OpenClaw(null, "  ", false).toNode(strings.copy(openClaw = "助手", processing = "处理中"))
        assertHeader(node, DisplayIcon.THREE_DOT_SPEECH_BUBBLE, "助手")
        assertEquals(listOf(DisplayNode.Text("处理中")), body(node))
    }

    @Test fun openClawFinalHasSnapAndDone() {
        val node = DisplayCard.OpenClaw("question", "answer", true).toNode(strings)
        assertHeader(node, DisplayIcon.CODE, "openClaw")
        assertEquals(listOf(DisplayNode.Text("question", NodeTextStyle.META, NodeTextColor.SECONDARY), DisplayNode.Text("answer")), body(node))
        assertEquals(listOf(snap(), done(NodeButtonStyle.OUTLINE)), buttons(node))
    }

    @Test fun openClawPagesAndTruncatesTheUserTranscript() {
        val card = DisplayCard.OpenClaw("q".repeat(119) + "😀", "x".repeat(561), true, 1)
        val node = card.toNode(strings)
        assertEquals(DisplayNode.Text("q".repeat(49) + "…", NodeTextStyle.META, NodeTextColor.SECONDARY), body(node).first())
        assertEquals(indicator("2/5"), body(node).last())
        assertEquals(listOf(prev(card.copy(page = 0)), next(card.copy(page = 2)), snap(), done(NodeButtonStyle.OUTLINE)), buttons(node))
        assertEquals(card.copy(page = 0).toNode(strings), card.copy(page = -20).toNode(strings))
        assertEquals(card.copy(page = 4).toNode(strings), card.copy(page = 20).toNode(strings))
    }

    @Test fun defaultWeChatAndMusicCardsRetainTheirTextAndControls() {
        val weChat = DisplayCard.WeChat("小明", "你好").toNode(strings)
        assertHeader(weChat, DisplayIcon.ENVELOPE_OPEN, "小明")
        assertEquals(listOf(DisplayNode.Text("你好")), body(weChat))
        assertEquals(listOf(done()), buttons(weChat))
        for (playing in listOf(true, false)) {
            val music = DisplayCard.Music("Song", "Artist", playing).toNode(strings)
            assertHeader(music, DisplayIcon.MUSIC_NOTE, "Song")
            assertEquals(listOf(DisplayNode.Text("Artist", color = NodeTextColor.SECONDARY)), body(music))
            assertEquals(listOf(
                DisplayNode.Button("prev", NodeButtonStyle.OUTLINE, DisplayIcon.TRIANGLE_LEFT_VERTICAL_LINE, DisplayAction.MusicPrev),
                DisplayNode.Button("", NodeButtonStyle.PRIMARY, if (playing) DisplayIcon.TWO_LINES_PARALLEL else DisplayIcon.TRIANGLE_RIGHT, DisplayAction.MusicPlayPause),
                DisplayNode.Button("next", NodeButtonStyle.OUTLINE, DisplayIcon.TRIANGLE_RIGHT_VERTICAL_LINE, DisplayAction.MusicNext),
            ), buttons(music))
        }
    }

    @Test fun wechatPagingKeepsMergedContentMetadataAndActionsWithinTheViewport() {
        val text = List(20) { "中".repeat(13) }.joinToString("\n") + "\r\n😀 final  "
        val card = DisplayCard.WeChat("群聊\n".repeat(15), text, 3, 1_789_142_400_000L)
        val nodes = (0 until card.pageCount()).map { card.copy(page = it).toNode(strings) }
        assertTrue(nodes.size > 1)
        assertEquals(text, nodes.joinToString("") { body(it).single { it.style == NodeTextStyle.BODY }.text })
        nodes.forEachIndexed { index, node ->
            val metadata = body(node).first().text
            assertTrue(metadata.matches(Regex("×3 · [0-2][0-9]:[0-5][0-9]")))
            assertEquals(indicator("${index + 1}/${nodes.size}"), body(node).last())
            assertTrue("Header, metadata, body, page and controls fit", modeledHeight(node) <= 600)
            val expected = buildList {
                if (index > 0) add(prev(card.copy(page = index - 1)))
                if (index < nodes.lastIndex) add(next(card.copy(page = index + 1)))
                add(done())
            }
            assertEquals(expected, buttons(node))
        }
        assertEquals(nodes.first(), card.copy(page = Int.MIN_VALUE).toNode(strings))
        assertEquals(nodes.last(), card.copy(page = Int.MAX_VALUE).toNode(strings))
    }

    @Test fun unknownWechatTimeIsOmittedAndCountIsBoundedWithoutDroppingText() {
        val card = DisplayCard.WeChat("Sam", "hello", count = Int.MAX_VALUE)
        assertEquals(listOf(indicator("×3"), DisplayNode.Text("hello")), body(card.toNode(strings)))
        assertEquals(listOf(done()), buttons(card.toNode(strings)))
        val timeOnly = card.copy(count = -1, timestamp = 1_789_142_400_000L)
        assertTrue(body(timeOnly.toNode(strings)).first().text.startsWith("×1 · "))
        assertEquals(listOf(DisplayNode.Text("hello")), body(card.copy(count = 1, timestamp = -1).toNode(strings)))
    }

    @Test fun musicArtAndAppReserveTheirSpaceWithoutChangingTheThreeActions() {
        val title = List(20) { "标题" }.joinToString("\n")
        val artist = List(20) { "音乐人😀" }.joinToString("\n")
        for (hasArt in listOf(false, true)) for (hasApp in listOf(false, true)) {
            val jpeg = if (hasArt) byteArrayOf(1, 2, 3) else null // Decode validity is a boundary concern.
            val app = if (hasApp) "播放器\n".repeat(20) else ""
            val card = DisplayCard.Music(title, artist, true, app, jpeg)
            val node = card.toNode(strings)
            assertTrue("art=$hasArt app=$hasApp", modeledHeight(node) <= 600)
            assertEquals(listOf(DisplayAction.MusicPrev, DisplayAction.MusicPlayPause, DisplayAction.MusicNext),
                buttons(node).map { it.action })
            val artistNode = body(node).single { it.style == NodeTextStyle.BODY }
            val expectedMaxLines = when { hasArt && hasApp -> 1; hasArt -> 2; hasApp -> 8; else -> 9 }
            assertTrue(conservativeDisplayLines(artistNode.text, 19) <= expectedMaxLines)
            assertTrue(artistNode.text.endsWith("…"))
            assertEquals(artist, card.artist)
            assertEquals(app, card.app)
            if (hasApp) assertTrue(conservativeDisplayLines(body(node).last().text, 25) <= 1)
            if (hasArt) {
                val frame = node.children.filterIsInstance<DisplayNode.Column>().single()
                val image = frame.children.single() as DisplayNode.Image
                assertEquals(240, image.size)
                assertEquals(image.size, 600 - node.padding * 2 - frame.paddingStart!! - frame.paddingEnd!!)
                assertEquals(NodeAlignment.STRETCH, frame.crossAlignment)
                assertTrue(jpeg === image.jpeg)
            } else assertTrue(node.children.none { it is DisplayNode.Column })
        }
    }

    @Test fun absentOrEmptyArtYieldsTheSameUsableTextCard() {
        val card = DisplayCard.Music("Song", "Artist", false, "Player")
        assertEquals(card.toNode(strings), card.copy(artJpeg = byteArrayOf()).toNode(strings))
        assertEquals(listOf(DisplayNode.Text("Artist", color = NodeTextColor.SECONDARY), indicator("Player")),
            body(card.toNode(strings)))
        assertEquals(3, buttons(card.toNode(strings)).size)
    }

    @Test fun imageSizeCannotExceedItsReservedMaximumOrBeNonpositive() {
        assertEquals(240, DisplayNode.Image(byteArrayOf()).size)
        assertEquals(120, DisplayNode.Image(byteArrayOf(), 120).size)
        for (size in listOf(Int.MIN_VALUE, 0, 241, Int.MAX_VALUE)) {
            assertTrue(runCatching { DisplayNode.Image(byteArrayOf(), size) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun everyNodeTreeUsesOnlySupportedNodes() {
        val cards = listOf(
            DisplayCard.Status("Glasses", true, true), DisplayCard.Notice("Notice", "Body"),
            DisplayCard.LiveAI(LiveAIPhase.LISTENING, "Question", "Answer", true),
            DisplayCard.QuickVision("Vision", "Result"), foodCard, DisplayCard.OpenClaw(null, "Reply", true),
            DisplayCard.WeChat("Sam", "Hello"), DisplayCard.Music("Song", "Artist", true),
            DisplayCard.Music("Song", "Artist", true, "Player", byteArrayOf(1)),
        )
        for (card in cards) {
            val node = card.toNode(strings)
            assertEquals(24, node.padding)
            assertEquals(12, node.gap)
            assertEquals(0f, node.flexGrow, 0f)
            assertTrue(node.children.first() is DisplayNode.Row)
            checkTree(node)
            if (card !is DisplayCard.Notice) {
                val row = node.children.last() as DisplayNode.Row
                assertEquals(8, row.gap)
                assertEquals(NodeAlignment.CENTER, row.alignment)
                assertEquals(NodeAlignment.CENTER, row.crossAlignment)
                assertEquals(1, row.children.size)
                assertTrue(row.children.single() is DisplayNode.ButtonGroup)
                assertFalse(buttons(node).isEmpty())
            }
        }
    }

    private fun assertHeader(node: DisplayNode.Column, icon: DisplayIcon, title: String) {
        assertEquals(DisplayNode.Row(listOf(DisplayNode.Icon(icon), DisplayNode.Text(title, NodeTextStyle.HEADING)), gap = 8, crossAlignment = NodeAlignment.CENTER), node.children.first())
    }

    private fun body(node: DisplayNode.Column) = node.children.filterIsInstance<DisplayNode.Text>()
    private fun buttons(node: DisplayNode.Column) = ((node.children.last() as DisplayNode.Row).children.single() as DisplayNode.ButtonGroup).buttons
    private fun indicator(text: String) = DisplayNode.Text(text, NodeTextStyle.META, NodeTextColor.SECONDARY)
    private fun prev(card: DisplayCard) = DisplayNode.Button("prev", NodeButtonStyle.OUTLINE, DisplayIcon.ARROW_LEFT, DisplayAction.Page(card))
    private fun next(card: DisplayCard) = DisplayNode.Button("next", NodeButtonStyle.OUTLINE, DisplayIcon.ARROW_RIGHT, DisplayAction.Page(card))
    private fun again() = DisplayNode.Button("again", NodeButtonStyle.SECONDARY, DisplayIcon.TWO_ARROWS_CLOCKWISE, DisplayAction.StartQuickVision)
    private fun done(style: NodeButtonStyle = NodeButtonStyle.PRIMARY) = DisplayNode.Button("done", style, DisplayIcon.CHECKMARK, DisplayAction.BackToMenu)
    private fun snap() = DisplayNode.Button("snap", NodeButtonStyle.PRIMARY, DisplayIcon.VIDEO_CAMERA, DisplayAction.OpenClawSnap)

    private fun checkTree(node: DisplayNode) {
        when (node) {
            is DisplayNode.Column -> node.children.forEach(::checkTree)
            is DisplayNode.Row -> node.children.forEach(::checkTree)
            is DisplayNode.ButtonGroup -> node.buttons.forEach(::checkTree)
            is DisplayNode.Text, is DisplayNode.Icon, is DisplayNode.Image, is DisplayNode.Button -> Unit
        }
    }

    // The line counter is separately checked against hand-written wrap/CRLF fixtures. Fixed SDK
    // metrics here check the assembled tree's allocation, not actual font or hardware rendering.
    private fun modeledHeight(node: DisplayNode): Int = when (node) {
        is DisplayNode.Column -> node.children.sumOf(::modeledHeight) + node.gap * (node.children.size - 1).coerceAtLeast(0) +
            (node.paddingTop ?: node.padding) + (node.paddingBottom ?: node.padding)
        is DisplayNode.Row -> (node.children.maxOfOrNull(::modeledHeight) ?: 0) +
            (node.paddingTop ?: node.padding) + (node.paddingBottom ?: node.padding)
        is DisplayNode.Text -> when (node.style) {
            NodeTextStyle.HEADING -> conservativeDisplayLines(node.text, 12) * 48
            NodeTextStyle.BODY -> conservativeDisplayLines(node.text, 19) * 36
            NodeTextStyle.META -> conservativeDisplayLines(node.text, 25) * 28
        }
        is DisplayNode.Icon -> 48
        is DisplayNode.Image -> node.size
        is DisplayNode.Button, is DisplayNode.ButtonGroup -> 88
    }
}
