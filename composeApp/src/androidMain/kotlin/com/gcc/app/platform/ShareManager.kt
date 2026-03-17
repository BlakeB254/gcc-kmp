package com.gcc.app.platform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

actual class ShareManager actual constructor() {
    actual fun shareText(text: String, subject: String) {
        val ctx = appContext ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        ctx.startActivity(
            Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    actual fun shareImage(imageBytes: ByteArray, text: String) {
        val ctx = appContext ?: return
        val file = File(ctx.cacheDir, "shared_image.png").apply {
            writeBytes(imageBytes)
        }
        val uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    actual fun saveImageToGallery(imageBytes: ByteArray, filename: String): Boolean {
        val ctx = appContext ?: return false
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GCC")
        }
        val uri = ctx.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } ?: return false
        return true
    }

    companion object {
        @Volatile
        private var appContext: Context? = null

        fun initContext(context: Context) {
            appContext = context.applicationContext
        }
    }
}
