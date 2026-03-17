package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum

actual class ShareManager {
    actual fun shareText(text: String, subject: String) {
        val items = listOf(text)
        val controller = UIActivityViewController(
            activityItems = items,
            applicationActivities = null,
        )
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(controller, animated = true, completion = null)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun shareImage(imageBytes: ByteArray, text: String) {
        val nsData = imageBytes.toNSData()
        val uiImage = UIImage(data = nsData)
        val items = mutableListOf<Any>(uiImage)
        if (text.isNotEmpty()) items.add(text)
        val controller = UIActivityViewController(
            activityItems = items,
            applicationActivities = null,
        )
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(controller, animated = true, completion = null)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun saveImageToGallery(imageBytes: ByteArray, filename: String): Boolean {
        val nsData = imageBytes.toNSData()
        val uiImage = UIImage(data = nsData) ?: return false
        UIImageWriteToSavedPhotosAlbum(uiImage, null, null, null)
        return true
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}
