package com.smartview.glassai.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCardTest {
    private val strings = FixedDisplayStrings(liveAi = "实时助手", leanEat = "轻食", openClaw = "龙虾助手")

    @Test fun bridgeCardDefaultsKeepTheOldConstructorsUsable() {
        val wechat = DisplayCard.WeChat("Sam", "hello")
        assertEquals(1, wechat.count)
        assertEquals(0L, wechat.timestamp)
        assertEquals(0, wechat.page)
        assertEquals(1, wechat.pageCount())
        val music = DisplayCard.Music("Song", "Artist", true)
        assertEquals("", music.app)
        assertNull(music.artJpeg)
    }

    @Test fun mergedWechatPreviewPreservesAllThreeMessagesAndWhitespaceThroughPagination() {
        val text = "  Sam: " + "hello ".repeat(60) + "\r\n\r\n小明：" + "你好😀".repeat(60) +
            "\nAlex: " + List(20) { "line $it" }.joinToString("\n") + "  "
        val card = DisplayCard.WeChat("Friends", text, count = 3, timestamp = 1_789_142_400_000L)
        val pages = card.previewPages()
        assertTrue(pages.size > 3)
        assertEquals(pages.size, card.pageCount(strings))
        assertEquals(text, pages.joinToString(""))
        pages.forEach {
            assertTrue(it.length <= DisplayCard.PAGE_CHARS)
            assertTrue(conservativeDisplayLines(it, 19) <= 7)
            assertFalse(it.first().isLowSurrogate())
            assertFalse(it.last().isHighSurrogate())
        }
        assertEquals(text, card.plainText())
        assertEquals(text, card.preview)
    }

    @Test fun liveAiPlainTextIsTheAssistantText() {
        val text = "answer".repeat(100)
        val card = DisplayCard.LiveAI(LiveAIPhase.SPEAKING, "question", text, true)
        assertEquals(text, card.plainText())
        assertEquals(text, card.assistantText)
    }

    @Test fun mainTextIsPreservedForEveryOtherCard() {
        assertEquals("Ray-Ban", DisplayCard.Status("Ray-Ban", true, false).plainText())
        assertEquals("Looking", DisplayCard.Notice("Vision", "Looking").plainText())
        assertEquals("a tree", DisplayCard.QuickVision("Vision", "a tree").plainText())
        assertEquals("reply", DisplayCard.OpenClaw("question", "reply", true).plainText())
        assertEquals("hello", DisplayCard.WeChat("Sam", "hello").plainText())
        assertEquals("Artist", DisplayCard.Music("Song", "Artist", true).plainText())
        val card = DisplayCard.LeanEat(200, 12, 3, 30, 88, listOf(LeanEatFood("Rice", "1 bowl", 200)), listOf("Eat slowly"))
        assertTrue(card.plainText().contains("Rice 1 bowl 200 kcal"))
        assertTrue(card.plainText().contains("Eat slowly"))
        assertTrue(card.plainText().contains("88/100"))
    }

    @Test fun fallbackNoticeUsesTheStringsTitleForLiveAi() {
        assertEquals(
            DisplayCard.Notice("实时助手", "reply"),
            DisplayCard.LiveAI(LiveAIPhase.SPEAKING, "question", "reply", false).fallbackNotice(strings),
        )
    }

    @Test fun fallbackNoticeUsesModeNameForQuickVision() {
        assertEquals(DisplayCard.Notice("识物", "tree"), DisplayCard.QuickVision("识物", "tree").fallbackNotice(strings))
    }

    @Test fun fallbackNoticeUsesLeanEatTitle() {
        val fallback = emptyLeanEat().fallbackNotice(strings)
        assertEquals("轻食", fallback.title)
        assertEquals(DisplayIcon.I_CIRCLE, fallback.icon)
        assertTrue(fallback.body.contains("0"))
    }

    @Test fun statusAndOpenClawFallbackTitlesAreCorrect() {
        assertEquals("Glasses", DisplayCard.Status("Glasses", false, false).fallbackNotice(strings).title)
        assertEquals("龙虾助手", DisplayCard.OpenClaw(null, "reply", true).fallbackNotice(strings).title)
    }

    @Test fun fallbackNoticeBodyIsCappedByLinesBeforeTheCharacterCeiling() {
        val card = DisplayCard.QuickVision("Vision", "x".repeat(300))
        assertEquals("x".repeat(227) + "…", card.fallbackNotice(strings).body)
    }

    @Test fun fallbackNeverLeavesADanglingSurrogate() {
        val body = DisplayCard.QuickVision("Vision", "x".repeat(226) + "😀" + "end".repeat(100)).fallbackNotice(strings).body
        assertEquals("x".repeat(226) + "😀…", body)
        assertFalse(body.last().isHighSurrogate())
    }

    @Test fun pageCountMatchesPagination() {
        assertEquals(4, DisplayCard.QuickVision("Vision", "x".repeat(561)).pageCount())
        assertEquals(4, DisplayCard.OpenClaw(null, "x".repeat(561), true, 20).pageCount())
        assertEquals(5, DisplayCard.OpenClaw("question", "x".repeat(561), true).pageCount())
        assertEquals(1, DisplayCard.QuickVision("Vision", "").pageCount())
        assertEquals(1, emptyLeanEat().pageCount())
        assertEquals(1, emptyLeanEat().copy(suggestions = listOf("", "  ")).pageCount())
        assertEquals(2, emptyLeanEat().copy(foods = listOf(LeanEatFood("Rice", "bowl", 200))).pageCount())
        assertEquals(5, emptyLeanEat().copy(suggestions = listOf("x".repeat(561))).pageCount())
        assertEquals(1, DisplayCard.LiveAI(LiveAIPhase.IDLE, null, "x".repeat(1000), true).pageCount())
    }

    @Test fun noticeFallbackIsItself() {
        val card = DisplayCard.Notice("Error", "Try again", DisplayIcon.EXCLAMATION_TRIANGLE)
        assertSame(card, card.fallbackNotice(strings))
    }

    @Test fun weChatAndMusicFallbacks() {
        assertEquals(DisplayCard.Notice("Sam", "hello"), DisplayCard.WeChat("Sam", "hello").fallbackNotice(strings))
        assertEquals(DisplayCard.Notice("Song", "Artist"), DisplayCard.Music("Song", "Artist", true).fallbackNotice(strings))
    }

    @Test fun displayIconHasNoDuplicatesAndAtLeastThirtyMembers() {
        assertTrue(DisplayIcon.entries.size >= 30)
        assertEquals(DisplayIcon.entries.size, DisplayIcon.entries.map { it.name }.toSet().size)
    }

    private fun emptyLeanEat() = DisplayCard.LeanEat(0, 0, 0, 0, 0, emptyList(), emptyList())
}
