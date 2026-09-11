package com.smartview.glassai.debug

import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.DisplayNode
import com.smartview.glassai.glasses.FixedDisplayStrings
import com.smartview.glassai.glasses.pageCount
import com.smartview.glassai.glasses.toNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPreviewSamplesTest {
    private val strings = FixedDisplayStrings()

    @Test fun thereIsOneSamplePerCardSubtypeInDeclarationOrder() {
        assertEquals(
            listOf(
                DisplayCard.Status::class, DisplayCard.Notice::class, DisplayCard.LiveAI::class,
                DisplayCard.QuickVision::class, DisplayCard.LeanEat::class, DisplayCard.OpenClaw::class,
                DisplayCard.WeChat::class, DisplayCard.Music::class,
            ),
            DisplayPreviewSamples.all(strings).map { it.second::class },
        )
    }

    @Test fun everySampleRendersToANonEmptyColumnRoot() {
        DisplayPreviewSamples.all(strings).forEach { (label, card) ->
            val node: DisplayNode = card.toNode(strings)
            assertTrue(label, node is DisplayNode.Column && node.children.isNotEmpty())
        }
    }

    @Test fun pagedSamplesExerciseNextAndPreviousPages() {
        val cards = DisplayPreviewSamples.all(strings).map { it.second }
        val quickVision = cards.filterIsInstance<DisplayCard.QuickVision>().single()
        val openClaw = cards.filterIsInstance<DisplayCard.OpenClaw>().single()
        val leanEat = cards.filterIsInstance<DisplayCard.LeanEat>().single()
        val wechat = cards.filterIsInstance<DisplayCard.WeChat>().single()
        assertTrue(quickVision.resultText.length >= 700)
        assertTrue(openClaw.replyText.length >= 400)
        listOf(quickVision, openClaw, leanEat, wechat).forEach { assertTrue(it.pageCount() > 1) }
        assertEquals(3, wechat.count)
        assertTrue(wechat.timestamp > 0)
        assertEquals(3, leanEat.foods.size)
        assertEquals(2, leanEat.suggestions.size)
    }

    @Test fun musicSampleUsesLocalJpegBytesAndKeepsTheAppName() {
        val card = DisplayPreviewSamples.all(strings).map { it.second }.filterIsInstance<DisplayCard.Music>().single()
        assertTrue(card.app.isNotBlank())
        val jpeg = card.artJpeg!!
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val frame = card.toNode(strings).children.filterIsInstance<DisplayNode.Column>().single()
        assertTrue(frame.children.single() is DisplayNode.Image)
    }
}
