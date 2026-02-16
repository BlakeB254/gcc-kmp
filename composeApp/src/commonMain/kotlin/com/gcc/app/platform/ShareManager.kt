package com.gcc.app.platform

/**
 * Platform-specific sharing interface.
 *
 * Android: Intent.ACTION_SEND with image URI
 * iOS: UIActivityViewController
 *
 * For now, this is a simple expect/actual interface.
 * The actual implementations handle platform-specific share sheets.
 */
expect class ShareManager() {
    fun shareText(text: String, subject: String = "")
    fun shareImage(imageBytes: ByteArray, text: String = "")
}
