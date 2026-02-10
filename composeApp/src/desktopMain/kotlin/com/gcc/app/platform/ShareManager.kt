package com.gcc.app.platform

actual class ShareManager {
    actual fun shareText(text: String, subject: String) {
        // Desktop sharing not implemented — no-op for preview
    }

    actual fun shareImage(imageBytes: ByteArray, text: String) {
        // Desktop sharing not implemented — no-op for preview
    }
}
