package com.gcc.app.platform

import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

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

    actual fun shareImage(imageBytes: ByteArray, text: String) {
        // Image sharing requires NSData → UIImage conversion.
        // For now, share text description; full image sharing
        // will be implemented when CameraManager is wired.
        val items = mutableListOf<Any>()
        if (text.isNotEmpty()) items.add(text)
        val controller = UIActivityViewController(
            activityItems = items,
            applicationActivities = null,
        )
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(controller, animated = true, completion = null)
    }
}
