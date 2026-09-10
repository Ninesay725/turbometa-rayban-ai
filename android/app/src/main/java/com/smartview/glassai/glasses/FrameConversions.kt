package com.smartview.glassai.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The one place that turns DAT 0.9.0 frames into app images (spec §5.8, Phase A review Minor #15).
 * Frames are I420 (Y plane, then U plane, then V plane, each chroma plane width/2 x height/2).
 *
 * Only [i420ToNv21] and [copyI420] run on the JVM in unit tests; everything else needs
 * android.graphics and is exercised on the emulator.
 */
object FrameConversions {
    private const val TAG = "FrameConversions"

    /** JPEG quality for live previews (WearablesViewModel / RTMP preview). */
    const val PREVIEW_JPEG_QUALITY = 50

    /** JPEG quality for frames that are analyzed or sent to a gateway. */
    const val CAPTURE_JPEG_QUALITY = 85

    /** Defensive copy of [buffer]'s remaining bytes; the buffer's position is restored. */
    fun copyI420(buffer: ByteBuffer): ByteArray {
        val originalPosition = buffer.position()
        val copy = ByteArray(buffer.remaining())
        buffer.get(copy)
        buffer.position(originalPosition)
        return copy
    }

    /**
     * Copies the SDK frame. VideoFrame.buffer is only guaranteed valid inside the collector, so
     * this must be the first thing a collector does with a frame.
     */
    fun copyI420(frame: VideoFrame): ByteArray? = try {
        copyI420(frame.buffer)
    } catch (e: Exception) {
        Log.e(TAG, "Error copying video frame: ${e.message}")
        null
    }

    /** I420 (YYYY…UU…VV…) -> NV21 (YYYY…VUVU…). */
    fun i420ToNv21(input: ByteArray, width: Int, height: Int): ByteArray {
        val size = width * height
        val quarter = size / 4
        require(input.size >= size + 2 * quarter) {
            "I420 buffer too small: ${input.size} bytes for ${width}x${height}"
        }
        val output = ByteArray(input.size)
        input.copyInto(output, 0, 0, size) // Y is the same
        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
    }

    fun i420ToJpeg(i420: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val nv21 = i420ToNv21(i420, width, height)
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return ByteArrayOutputStream().use { stream ->
            image.compressToJpeg(Rect(0, 0, width, height), quality, stream)
            stream.toByteArray()
        }
    }

    fun i420ToBitmap(i420: ByteArray, width: Int, height: Int, quality: Int): Bitmap? = try {
        val jpeg = i420ToJpeg(i420, width, height, quality)
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    } catch (e: Exception) {
        Log.e(TAG, "Error converting I420 frame: ${e.message}")
        null
    }

    /** Copy + convert in one call; never call this on the main thread. */
    fun frameToBitmap(frame: VideoFrame, quality: Int): Bitmap? {
        val i420 = copyI420(frame) ?: return null
        return i420ToBitmap(i420, frame.width, frame.height, quality)
    }

    /** Decodes a captured photo (HEIC bytes or an SDK Bitmap). Never call this on the main thread. */
    fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buffer = photo.data.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
