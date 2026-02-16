package com.gcc.app.platform

import android.content.Context
import android.content.Intent
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

    companion object {
        @Volatile
        private var appContext: Context? = null

        fun initContext(context: Context) {
            appContext = context.applicationContext
        }
    }
}
