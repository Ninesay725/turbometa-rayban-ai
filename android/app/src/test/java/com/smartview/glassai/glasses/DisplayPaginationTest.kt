package com.smartview.glassai.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPaginationTest {
    @Test fun shortTextIsOnePage() {
        assertEquals(listOf("Hello 世界"), paginate("Hello 世界"))
        assertEquals(listOf("12345"), paginate("12345", 5))
    }

    @Test fun blankTextIsOneEmptyPage() {
        for (text in listOf("", "  \n\t ", "\u3000")) {
            assertEquals(listOf(""), paginate(text))
        }
    }

    @Test fun breaksAtTheLastSentenceEndInsideTheWindow() {
        assertEquals(listOf("A. B!", " C,", " def ", "ghi"), paginate("A. B! C, def ghi", 6))
    }

    @Test fun breaksAtNewlineBeforeComma() {
        assertEquals(listOf("ab\n", "cd,", "efghij"), paginate("ab\ncd,efghij", 7))
    }

    @Test fun fallsBackToCommaBeforeSpace() {
        assertEquals(listOf("ab,", " cd ", "efgh"), paginate("ab, cd efgh", 7))
    }

    @Test fun fallsBackToSpaceBeforeHardCut() {
        assertEquals(listOf("ab cd ", "efghij"), paginate("ab cd efghij", 6))
    }

    @Test fun hardCutsUnbrokenTextWithoutLosingCharacters() {
        assertEquals(listOf("abcd", "efgh", "ij"), paginate("abcdefghij", 4))
        assertEquals(listOf("a", "b"), paginate("ab", 1))
    }

    @Test fun noPageExceedsMaxChars() {
        val text = "中文内容，English words. 下一句！".repeat(100)
        val pages = paginate(text)
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.length <= 280 })
        assertEquals(text, pages.joinToString(""))
    }

    @Test fun boundaryWhitespaceAndBlankLinesArePreserved() {
        val text = "  one \n\n two,    three  four  "
        val pages = paginate(text, 7)
        assertTrue(pages.all { it.isNotEmpty() && it.length <= 7 })
        assertEquals(text, pages.joinToString(""))
    }

    @Test fun explicitLinesAndWordWrappingBothConsumeTheLineBudget() {
        assertEquals(2, conservativeDisplayLines("123456 1234", 10))
        assertEquals(2, conservativeDisplayLines("12345678901", 10))
        assertEquals(2, conservativeDisplayLines("A\r\nB", 10))
        assertEquals(3, conservativeDisplayLines("A\n\nB", 10))
        assertEquals(2, conservativeDisplayLines("\tX", 4))
        assertEquals(2, conservativeDisplayLines("界😀", 1))
        assertEquals(listOf("hello ", "world"), paginate("hello world", 100, maxLines = 1, columns = 10))
    }

    @Test fun layoutPagingKeepsUnicodeAndExplicitWhitespaceAtEveryBoundary() {
        val text = "A\n\n\nB\t界😀\r\nC\u2028D".repeat(20)
        val pages = paginate(text, maxChars = 12, maxLines = 2, columns = 4)
        assertEquals(text, pages.joinToString(""))
        pages.forEach {
            assertTrue(it.length <= 12)
            assertTrue(conservativeDisplayLines(it, 4) <= 2)
            assertFalse(it.first().isLowSurrogate())
            assertFalse(it.last().isHighSurrogate())
            assertFalse(it.endsWith("\r"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroLineBudget() { paginate("text", maxLines = 0) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroColumns() { paginate("text", columns = 0) }

    @Test fun chineseSentencePunctuationCounts() {
        for (mark in listOf("。", "！", "？")) {
            assertEquals(listOf("你好$mark", "世界你好", "啊"), paginate("你好${mark}世界你好啊", 4))
        }
        assertEquals(listOf("你好，", "世界你好", "啊"), paginate("你好，世界你好啊", 4))
    }

    @Test fun neverSplitsASurrogatePairAtAHardCut() {
        val text = "a😀bc😀d"
        val pages = paginate(text, 2)
        assertEquals(listOf("a", "😀", "bc", "😀", "d"), pages)
        assertEquals(text, pages.joinToString(""))
        for (page in pages) {
            assertTrue(page.length <= 2)
            assertFalse(page.first().isLowSurrogate())
            assertFalse(page.last().isHighSurrogate())
        }
    }

    @Test fun preservesSupplementaryCharactersAroundPunctuation() {
        assertEquals(listOf("😀!", "好😀", "啊"), paginate("😀!好😀啊", 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroPageSize() { paginate("text", 0) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativePageSizeEvenForBlankText() { paginate("", -1) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAPageSizeThatCannotHoldOneSurrogatePair() { paginate("😀", 1) }
}
