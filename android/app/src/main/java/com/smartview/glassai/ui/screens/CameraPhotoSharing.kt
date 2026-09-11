package com.smartview.glassai.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Called only after Share is tapped; FileProvider already exposes cache/images/. */
internal suspend fun createCameraShareIntent(context: Context, photo: Bitmap): Intent = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "images")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create share cache")
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1_000L
    directory.listFiles()?.filter {
        it.name.startsWith("camera-share-") && it.extension == "jpg" && it.lastModified() < cutoff
    }?.forEach { it.delete() }
    val file = File.createTempFile("camera-share-", ".jpg", directory)
    try {
        file.outputStream().use {
            if (!photo.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("Cannot encode photo")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("photo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (failure: Exception) {
        file.delete()
        throw failure
    }
}
