package com.smartview.glassai.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android bitmap/SDK boundary only: no registration, device session or real media app needed. */
@RunWith(AndroidJUnit4::class)
class DisplayImageInstrumentedTest {
    @Test fun validSquareJpegIsDecodedWithoutMutatingItsBytes() {
        val jpeg = encoded(240, 240)
        val original = jpeg.copyOf()
        val decoded = decodeDisplayImage(DisplayNode.Image(jpeg))!!
        try {
            assertEquals(240, decoded.width)
            assertEquals(240, decoded.height)
            assertFalse(decoded.isRecycled)
            assertTrue(Color.blue(decoded.getPixel(120, 120)) > 240)
            assertArrayEquals(original, jpeg)
        } finally { decoded.recycle() }
    }

    @Test fun portraitAndLandscapeArtAreLetterboxedInsideTheRequestedSquare() {
        for ((width, height) in listOf(60 to 120, 120 to 60)) for (size in listOf(120, 240)) {
            val decoded = decodeDisplayImage(DisplayNode.Image(encoded(width, height), size))!!
            try {
                assertEquals(size, decoded.width)
                assertEquals(size, decoded.height)
                assertEquals(Color.BLACK, decoded.getPixel(0, 0))
                assertTrue(Color.blue(decoded.getPixel(size / 2, size / 2)) > 240)
                // The short dimension is half the square, preserving the 1:2 source aspect ratio.
                val outside = if (width < height) decoded.getPixel(size / 8, size / 2)
                    else decoded.getPixel(size / 2, size / 8)
                assertEquals(Color.BLACK, outside)
            } finally { decoded.recycle() }
        }
    }

    @Test fun invalidNonJpegOrOutOfBudgetImagesAreOmitted() {
        val cases = listOf(
            byteArrayOf(), byteArrayOf(1, 2, 3), encoded(16, 16).copyOf(10),
            encoded(16, 16, Bitmap.CompressFormat.PNG), encoded(241, 100), encoded(100, 241),
            ByteArray(256 * 1024 + 1),
        )
        cases.forEach { assertNull(decodeDisplayImage(DisplayNode.Image(it))) }
    }

    @Test fun artDecodeFailureKeepsTextAndControlsInTheMusicCard() {
        val strings = ResourceDisplayStrings(ApplicationProvider.getApplicationContext<Context>())
        for ((jpeg, decodable) in listOf(null to false, byteArrayOf(1, 2, 3) to false, encoded(120, 240) to true)) {
            val node = DisplayCard.Music("Song", "Artist", true, "Player", jpeg).toNode(strings)
            // Exercise our boundary directly: ContentScope's constructor is Kotlin-internal.
            // Production receives that scope from sendContent; the test needs no SDK internals.
            val image = node.children.filterIsInstance<DisplayNode.Column>().singleOrNull()
                ?.children?.single() as DisplayNode.Image?
            val decoded = image?.let(::decodeDisplayImage)
            assertEquals(decodable, decoded != null)
            decoded?.recycle()
            assertTrue(node.children.filterIsInstance<DisplayNode.Text>().any { it.text == "Artist" })
            assertTrue(node.children.filterIsInstance<DisplayNode.Text>().any { it.text == "Player" })
            val buttons = (node.children.last() as DisplayNode.Row).children.single() as DisplayNode.ButtonGroup
            assertEquals(listOf(DisplayAction.MusicPrev, DisplayAction.MusicPlayPause, DisplayAction.MusicNext),
                buttons.buttons.map { it.action })
        }
    }

    private fun encoded(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return try {
            ByteArrayOutputStream().use { bytes ->
                check(bitmap.compress(format, 90, bytes))
                bytes.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
