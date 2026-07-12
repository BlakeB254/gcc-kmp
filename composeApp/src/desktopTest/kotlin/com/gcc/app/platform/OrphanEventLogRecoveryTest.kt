package com.gcc.app.platform

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.shared.cache.GccDatabase
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.offline.MemorySettings
import com.gcc.shared.serialization.GccJson
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Flight recorder salvage end-to-end (scan-session-replay PRD Phase 1): a
 * crashed session's on-disk event log must reach R2 with its recovered
 * manifest — including the crash-before-first-GPS-fix case where NO journal
 * samples exist (the gate never opened; the event log is the only record of
 * why). Runs the real sweep against a temp directory and a real localhost
 * upload server.
 */
class OrphanEventLogRecoveryTest {

    private fun freshStore(): CaptureChunkStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GccDatabase.Schema.create(driver)
        return CaptureChunkStore(driver)
    }

    private fun tempSessionsDir(): File =
        File.createTempFile("gcc-orphan-test", "").also { it.delete(); it.mkdirs() }

    private fun gunzip(data: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }.decodeToString()

    private val journalLine =
        """{"type":"sample","sample":{"lat":41.88,"lng":-87.63,"atMs":1500}}"""
    private val eventLine =
        """{"t":0,"type":"gate","state":"INDOOR","msg":"ceiling-plane"}"""

    @Test
    fun crashedSessionUploadsItsEventLogWithTheRecoveredManifest() = runBlocking {
        TestScanServer().use { server ->
            val dir = tempSessionsDir()
            File(dir, "env-1700000000000-abcdef.jsonl").writeText(journalLine + "\n")
            File(dir, "env-1700000000000-abcdef.events.jsonl").writeText(eventLine + "\n")
            val uploader = EnvScanSessionUploader(
                apiClient = GccApiClient(baseUrl = server.base, tokenProvider = { "tok" }),
                settings = MemorySettings(),
            )
            recoverOrphanScanSessions(uploader, freshStore(), dirOverride = dir.path)

            val eventsPut = server.requests.single { it.method == "PUT" && it.path.endsWith("events.jsonl.gz") }
            assertEquals(eventLine + "\n", gunzip(eventsPut.body))
            val manifestPut = server.requests.single { it.method == "PUT" && it.path.contains("-manifest") }
            val manifest: EnvScanManifest = GccJson.decodeFromString(EnvScanManifest.serializer(), manifestPut.body.decodeToString())
            assertEquals("recovered", manifest.lapKind)
            assertTrue(manifest.eventLogKey!!.endsWith("/events.jsonl.gz"))
            assertEquals(1, manifest.path.size)

            assertTrue(dir.listFiles().isNullOrEmpty(), "both files must be deleted after salvage")
        }
    }

    @Test
    fun eventLogWithoutJournalSamplesStillRecovers() = runBlocking {
        TestScanServer().use { server ->
            val dir = tempSessionsDir()
            // No journal at all — the crash happened before the first GPS fix.
            File(dir, "env-1700000000000-noflix.events.jsonl").writeText(eventLine + "\n")
            val uploader = EnvScanSessionUploader(
                apiClient = GccApiClient(baseUrl = server.base, tokenProvider = { "tok" }),
                settings = MemorySettings(),
            )
            recoverOrphanScanSessions(uploader, freshStore(), dirOverride = dir.path)

            val manifestPut = server.requests.single { it.method == "PUT" && it.path.contains("-manifest") }
            val manifest: EnvScanManifest = GccJson.decodeFromString(EnvScanManifest.serializer(), manifestPut.body.decodeToString())
            assertEquals("recovered", manifest.lapKind)
            assertEquals(1700000000000L, manifest.startedAtMs, "start time recovered from the sessionId epoch")
            assertTrue(manifest.path.isEmpty())
            assertTrue(manifest.eventLogKey!!.endsWith("/events.jsonl.gz"))
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun emptySessionWithNeitherSamplesNorEventsJustCleansUp() = runBlocking {
        TestScanServer().use { server ->
            val dir = tempSessionsDir()
            File(dir, "env-1700000000000-empty1.jsonl").writeText("")
            val uploader = EnvScanSessionUploader(
                apiClient = GccApiClient(baseUrl = server.base, tokenProvider = { "tok" }),
                settings = MemorySettings(),
            )
            recoverOrphanScanSessions(uploader, freshStore(), dirOverride = dir.path)
            assertFalse(server.requests.any { it.method == "PUT" }, "nothing to restore, nothing must upload")
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
    }
}
