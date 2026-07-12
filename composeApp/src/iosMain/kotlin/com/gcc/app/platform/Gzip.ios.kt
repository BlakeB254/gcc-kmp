@file:OptIn(ExperimentalForeignApi::class)

package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

/**
 * platform.zlib with `windowBits = 15 + 16` — the +16 selects gzip (RFC 1952)
 * framing so the output is readable by any standard gunzip, matching the JVM
 * actuals' `GZIPOutputStream`. Single-shot: the event log tops out around a
 * few hundred KB, so streaming buys nothing.
 */
actual fun gzipBytes(data: ByteArray): ByteArray = memScoped {
    val strm = alloc<z_stream>()
    val initResult = deflateInit2_(
        strm.ptr,
        /* level = */ 6,
        Z_DEFLATED,
        /* windowBits = */ 15 + 16,
        /* memLevel = */ 8,
        /* strategy = */ 0, // Z_DEFAULT_STRATEGY
        zlibVersion()?.toKString(),
        sizeOf<z_stream>().convert(),
    )
    check(initResult == platform.zlib.Z_OK) { "deflateInit2 failed: $initResult" }
    try {
        // Pin a 1-byte stand-in for empty input: zlib still needs a valid
        // next_in pointer even when avail_in == 0 (header + trailer only).
        val input = if (data.isEmpty()) ByteArray(1) else data
        val bound = deflateBound(strm.ptr, data.size.convert()).toLong()
        val output = ByteArray(maxOf(64L, bound).toInt())
        input.usePinned { pinnedIn ->
            output.usePinned { pinnedOut ->
                strm.next_in = pinnedIn.addressOf(0).reinterpret()
                strm.avail_in = data.size.convert()
                strm.next_out = pinnedOut.addressOf(0).reinterpret()
                strm.avail_out = output.size.convert()
                val result = deflate(strm.ptr, Z_FINISH)
                check(result == Z_STREAM_END) { "deflate(Z_FINISH) failed: $result" }
            }
        }
        output.copyOf(strm.total_out.toInt())
    } finally {
        deflateEnd(strm.ptr)
    }
}
