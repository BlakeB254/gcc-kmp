package com.gcc.backend

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Flight recorder headline metrics (scan-session-replay PRD Phase 1 §3): the
 * indexer fetches each session's `events.jsonl.gz` and extracts the numbers
 * the review surface filters on — gate flips, taps, confirms, raycast
 * misses, upload failures, errors. The extractor must tolerate torn/corrupt
 * lines (crash-salvaged logs) and unknown event types (newer clients).
 */
class EventLogMetricsTest {

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun extractsHeadlineCountsFromTypedEvents() {
        val lines = listOf(
            """{"t":0,"type":"caption","msg":"Sweep the camera slowly"}""",
            """{"t":1000,"type":"gate","state":"UNCERTAIN"}""",
            """{"t":2000,"type":"gate","state":"OUTDOOR"}""",
            """{"t":2500,"type":"gate","state":"OUTDOOR"}""",
            """{"t":3000,"type":"gate","state":"INDOOR","msg":"ceiling-plane"}""",
            """{"t":4000,"type":"tap"}""",
            """{"t":4100,"type":"pill","state":"LOCATING"}""",
            """{"t":4200,"type":"pill","state":"NO_RAYCAST"}""",
            """{"t":5000,"type":"tap"}""",
            """{"t":5200,"type":"pill","state":"AWAITING_CONFIRMATION","label":"can","confidence":0.8}""",
            """{"t":5500,"type":"confirm","label":"can"}""",
            """{"t":6000,"type":"reject","label":"leaf"}""",
            """{"t":7000,"type":"detection","label":"bottle","confidence":0.7}""",
            """{"t":8000,"type":"upload","tag":"chunk-video","ok":true,"key":"raw/x/y/z.mov"}""",
            """{"t":9000,"type":"upload","tag":"chunk-depth","ok":false,"msg":"PUT 401"}""",
            """{"t":9500,"type":"diag","state":"ERROR","tag":"capture-upload","msg":"chunk PUT 4xx"}""",
            """{"t":9600,"type":"diag","state":"INFO","tag":"outdoor-gate","msg":"scene votes indoor"}""",
        )
        val metrics = EventLogMetricsExtractor.extract(lines.asSequence())
        assertEquals(17, metrics.eventCount)
        // UNCERTAIN → OUTDOOR → (OUTDOOR dedupes) → INDOOR = 2 flips
        assertEquals(2, metrics.gateFlipCount)
        assertEquals(2, metrics.tapCount)
        assertEquals(1, metrics.confirmCount)
        assertEquals(1, metrics.rejectCount)
        assertEquals(1, metrics.raycastMissCount)
        assertEquals(1, metrics.detectionCount)
        assertEquals(2, metrics.uploadAttemptCount)
        assertEquals(1, metrics.uploadFailureCount)
        assertEquals(1, metrics.errorCount)
    }

    @Test
    fun toleratesCorruptLinesAndUnknownTypes() {
        val lines = listOf(
            """{"t":0,"type":"tap"}""",
            """{"t":100,"type":"hologram","weird":true}""", // future event type
            """not json at all""",
            """{"t":200,"type":"tap"""", // torn line
        )
        val metrics = EventLogMetricsExtractor.extract(lines.asSequence())
        assertEquals(2, metrics.eventCount) // tap + unknown-but-parseable
        assertEquals(1, metrics.tapCount)
    }

    @Test
    fun emptyLogYieldsZeroes() {
        val metrics = EventLogMetricsExtractor.extract(emptySequence())
        assertEquals(0, metrics.eventCount)
        assertEquals(0, metrics.gateFlipCount)
        assertEquals(0, metrics.uploadFailureCount)
    }

    @Test
    fun gunzipsARealEventLogArtifact() {
        val jsonl = """{"t":0,"type":"tap"}""" + "\n" + """{"t":1,"type":"confirm","label":"can"}""" + "\n"
        val lines = EventLogMetricsExtractor.gunzipLines(gzip(jsonl))
        assertEquals(2, lines.size)
        val metrics = EventLogMetricsExtractor.extract(lines.asSequence())
        assertEquals(1, metrics.tapCount)
        assertEquals(1, metrics.confirmCount)
    }
}
