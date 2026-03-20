package com.gcc.app.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode a byte array (JPEG/PNG) into a Compose ImageBitmap.
 * Each platform implements this with its native image decoder.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
