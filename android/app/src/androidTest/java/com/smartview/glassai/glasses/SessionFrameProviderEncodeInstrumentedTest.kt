package com.smartview.glassai.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionFrameProviderEncodeInstrumentedTest {

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun decode(jpeg: ByteArray): Bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)!!

    @Test
    fun widerThanMaxWidthIsDownscaledKeepingTheAspectRatio() {
        val snap = SessionFrameProvider.encodeBitmap(bitmap(800, 400), maxWidth = 400, quality = 0.8)!!
        assertEquals(400, snap.width)
        assertEquals(200, snap.height)
        assertEquals(0xFF.toByte(), snap.jpeg[0])
        assertEquals(0xD8.toByte(), snap.jpeg[1])
        val decoded = decode(snap.jpeg)
        assertEquals(400, decoded.width)
        assertEquals(200, decoded.height)
    }

    @Test
    fun narrowerThanMaxWidthIsNotUpscaled() {
        val snap = SessionFrameProvider.encodeBitmap(bitmap(300, 200), maxWidth = 640, quality = 0.8)!!
        assertEquals(300, snap.width)
        assertEquals(200, snap.height)
        assertEquals(300, decode(snap.jpeg).width)
    }

    @Test
    fun qualityIsClampedInsteadOfThrowing() {
        val low = SessionFrameProvider.encodeBitmap(bitmap(64, 64), maxWidth = 0, quality = -3.0)!! // maxWidth 0: no scaling; quality -> 10
        val high = SessionFrameProvider.encodeBitmap(bitmap(64, 64), maxWidth = 64, quality = 7.0)!! // quality -> 100
        assertEquals(64, low.width)
        assertEquals(64, high.width)
        assertTrue("q100 (${high.jpeg.size} B) should not be smaller than q10 (${low.jpeg.size} B)", high.jpeg.size >= low.jpeg.size)
    }
}
