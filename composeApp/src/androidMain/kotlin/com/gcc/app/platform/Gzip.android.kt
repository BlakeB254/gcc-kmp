package com.gcc.app.platform

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

actual fun gzipBytes(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(maxOf(64, data.size / 3))
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}
