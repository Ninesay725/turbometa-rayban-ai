package com.smartview.glassai.ui.screens

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.GlassesDisplaySink
import com.smartview.glassai.viewmodels.LeanEatViewModel
import com.smartview.glassai.viewmodels.VisionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local bitmap and provider checks only; no camera session, cloud request or share target launch. */
@RunWith(AndroidJUnit4::class)
class CameraPhotoInstrumentedTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Suppress("DEPRECATION")
    @Test fun shareIntentGrantsOnlyReadAccessToTheTemporaryJpeg() = runBlocking {
        val photo = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        var sharedUri: Uri? = null
        try {
            val intent = createCameraShareIntent(app, photo)
            val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            sharedUri = uri
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("image/jpeg", intent.type)
            assertEquals("content", uri.scheme)
            assertEquals("${app.packageName}.fileprovider", uri.authority)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val decoded = app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            assertNotNull(decoded)
            try {
                assertEquals(24, decoded!!.width)
                assertEquals(16, decoded.height)
            } finally { decoded?.recycle() }
            assertFalse(photo.isRecycled)
        } finally {
            sharedUri?.let { app.contentResolver.delete(it, null, null) }
            photo.recycle()
        }
    }

    @Test fun analysisViewModelsDoNotRecycleTheSharedCameraPreviewOnClear() {
        val photo = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val store = ViewModelStore()
                val sink = object : GlassesDisplaySink {
                    override fun show(card: DisplayCard) = Unit
                    override fun showStatus() = Unit
                    override fun clear() = Unit
                }
                val leanEat = LeanEatViewModel(app, sink, { "unused" }, { null }, { error("No analysis expected") })
                val vision = VisionViewModel(app)
                store.put("leanEat", leanEat)
                store.put("vision", vision)
                leanEat.setCapturedImage(photo)
                vision.setCapturedImage(photo)
                assertSame(photo, leanEat.capturedImage.value)
                assertSame(photo, vision.capturedImage.value)
                assertFalse(leanEat.isAnalyzing.value)
                assertFalse(vision.isAnalyzing.value)
                store.clear()
                assertNull(leanEat.capturedImage.value)
                assertNull(vision.capturedImage.value)
            }
            assertFalse(photo.isRecycled)
            assertEquals(Color.GREEN, photo.getPixel(0, 0))
        } finally { photo.recycle() }
    }
}
