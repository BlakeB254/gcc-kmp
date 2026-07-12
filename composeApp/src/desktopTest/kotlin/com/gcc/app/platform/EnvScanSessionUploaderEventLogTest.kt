package com.gcc.app.platform

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.model.EnvScanPathSample
import com.gcc.shared.offline.MemorySettings
import com.gcc.shared.serialization.GccJson
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Flight recorder upload contract (scan-session-replay PRD Phase 1): the
 * event log uploads ALWAYS on completion as its own R2 artifact
 * (`raw/{cell}/{sessionId}/events.jsonl.gz`), BEFORE the manifest so the
 * manifest can reference it via `eventLogKey` — and when the event-log
 * upload fails, BOTH queue and travel together through `flushPending()`
 * (an offline session's event log is exactly the one worth keeping).
 *
 * End-to-end over a real localhost HTTP server (same wire-level philosophy
 * as [com.gcc.app.api.PresignedUploadWireTest]): a real GccApiClient issues
 * the presign POST and the presigned PUT, and assertions run against the
 * actual bytes that hit the wire — including gunzipping the PUT body.
 */
class EnvScanSessionUploaderEventLogTest {

    private fun manifest(sessionId: String = "env-1-test") = EnvScanManifest(
        sessionId = sessionId,
        deviceId = "device-1",
        deviceModel = "iPhone17,1",
        osVersion = "19.0",
        startedAtMs = 1_000L,
        completedAtMs = 61_000L,
        path = listOf(EnvScanPathSample(lat = 41.88, lng = -87.63, atMs = 1_500L)),
        scannedCells = emptyList(),
        scannedCellSizeMeters = 3.0,
        observedItems = emptyList(),
        photoKeys = emptyList(),
    )

    private val eventLines = listOf(
        """{"t":0,"type":"caption","msg":"Sweep the camera slowly"}""",
        """{"t":5000,"type":"gate","state":"OUTDOOR"}""",
    )

    private fun gunzip(data: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }.decodeToString()

    @Test
    fun uploadsEventLogFirstAndManifestReferencesIt() = runBlocking {
        TestScanServer().use { server ->
            val uploader = EnvScanSessionUploader(
                apiClient = GccApiClient(baseUrl = server.base, tokenProvider = { "tok" }),
                settings = MemorySettings(),
            )
            val ok = uploader.uploadOrQueue(manifest(), eventLogLines = eventLines)
            assertTrue(ok)

            val puts = server.requests.filter { it.method == "PUT" }
            assertEquals(2, puts.size)
            val eventsPut = puts.first()
            assertTrue(eventsPut.path.endsWith("/events.jsonl.gz"), "event log must upload before the manifest: ${eventsPut.path}")
            assertEquals(eventLines.joinToString("\n") + "\n", gunzip(eventsPut.body))

            val manifestPut = puts.last()
            val uploaded: EnvScanManifest = GccJson.decodeFromString(EnvScanManifest.serializer(), manifestPut.body.decodeToString())
            assertTrue(uploaded.eventLogKey!!.endsWith("/${manifest().sessionId}/events.jsonl.gz"))
            assertEquals(1, uploaded.eventLogSchema)
        }
    }

    @Test
    fun eventLogFailureQueuesBothAndFlushPendingDelivers() = runBlocking {
        TestScanServer().use { server ->
            val settings = MemorySettings()
            val uploader = EnvScanSessionUploader(
                apiClient = GccApiClient(baseUrl = server.base, tokenProvider = { "tok" }),
                settings = settings,
            )
            server.putStatus = { path -> if (path.endsWith("events.jsonl.gz")) 500 else 200 }
            val ok = uploader.uploadOrQueue(manifest(), eventLogLines = eventLines)
            assertFalse(ok)
            // Nothing must have landed: the manifest waits for its event log.
            assertEquals(0, server.requests.count { it.method == "PUT" && it.path.contains("-manifest") })

            server.putStatus = { 200 }
            uploader.flushPending()

            val manifestPut = server.requests.last { it.method == "PUT" && it.path.contains("-manifest") }
            val uploaded: EnvScanManifest = GccJson.decodeFromString(EnvScanManifest.serializer(), manifestPut.body.decodeToString())
            assertTrue(uploaded.eventLogKey!!.endsWith("/events.jsonl.gz"))
            assertEquals(eventLines.joinToString("\n") + "\n", gunzip(server.requests.last { it.path.endsWith("events.jsonl.gz") }.body))

            // Queue fully drained: a second flush issues no new requests.
            val requestCount = server.requests.size
            uploader.flushPending()
            assertEquals(requestCount, server.requests.size)
        }
    }

    @Test
    fun sessionsWithoutEventLinesUploadExactlyAsBefore() = runBlocking {
        TestScanServer().use { server ->
            val uploader = EnvScanSessionUploader(
                apiClient = GccApiClient(baseUrl = server.base, tokenProvider = { "tok" }),
                settings = MemorySettings(),
            )
            val ok = uploader.uploadOrQueue(manifest(), eventLogLines = null)
            assertTrue(ok)
            val puts = server.requests.filter { it.method == "PUT" }
            assertEquals(1, puts.size)
            val uploaded: EnvScanManifest = GccJson.decodeFromString(EnvScanManifest.serializer(), puts.single().body.decodeToString())
            assertNull(uploaded.eventLogKey)
        }
    }
}
