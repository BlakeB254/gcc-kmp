package com.gcc.app.platform

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flight recorder: the session event log uploads as `events.jsonl.gz`, so the
 * client needs a gzip primitive ([gzipBytes], expect/actual — java.util.zip on
 * JVM targets, platform.zlib on iOS). The output must be a REAL gzip stream
 * (RFC 1952 framing, not bare deflate): the backend indexer and any standard
 * tool must be able to gunzip it.
 */
class GzipBytesTest {

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    @Test
    fun gzipRoundTripsThroughStandardGunzip() {
        val payload = buildString {
            repeat(500) { appendLine("""{"t":${it * 250},"type":"caption","msg":"Sweep the camera slowly"}""") }
        }.encodeToByteArray()
        val zipped = gzipBytes(payload)
        assertContentEquals(payload, gunzip(zipped))
    }

    @Test
    fun gzipOutputHasGzipMagicAndCompresses() {
        val payload = "abc123 ".repeat(4000).encodeToByteArray()
        val zipped = gzipBytes(payload)
        assertEquals(0x1f, zipped[0].toInt() and 0xff, "gzip magic byte 1")
        assertEquals(0x8b, zipped[1].toInt() and 0xff, "gzip magic byte 2")
        assertTrue(zipped.size < payload.size / 2, "repetitive payload should compress well")
    }

    @Test
    fun gzipHandlesEmptyInput() {
        assertContentEquals(ByteArray(0), gunzip(gzipBytes(ByteArray(0))))
    }
}
