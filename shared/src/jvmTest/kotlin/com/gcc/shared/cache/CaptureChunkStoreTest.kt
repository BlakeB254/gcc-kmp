package com.gcc.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sampleChunk(
    sessionId: String = "session-1",
    chunkIndex: Long = 0L,
    kind: String = "video",
    createdAtMs: Long = 1_000L,
) = CaptureChunkRecord(
    sessionId = sessionId,
    chunkIndex = chunkIndex,
    kind = kind,
    filePath = "/tmp/$sessionId/chunk-$chunkIndex.$kind",
    sizeBytes = 4096L,
    locationCellId = "41850:-87720",
    r2Key = null,
    uploadedAtMs = null,
    createdAtMs = createdAtMs,
)

private fun freshInMemoryStore(): CaptureChunkStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    GccDatabase.Schema.create(driver)
    return CaptureChunkStore(driver)
}

class CaptureChunkStoreTest {

    @Test
    fun insertThenChunksForSessionRoundTrips() = runTest {
        val store = freshInMemoryStore()
        store.insertChunk(sampleChunk(chunkIndex = 0L, kind = "video"))
        store.insertChunk(sampleChunk(chunkIndex = 1L, kind = "sidecar"))
        store.insertChunk(sampleChunk(sessionId = "session-2", chunkIndex = 0L, kind = "video"))

        val rows = store.chunksForSession("session-1")
        assertEquals(2, rows.size)
        assertEquals(listOf(0L, 1L), rows.map { it.chunkIndex })
    }

    @Test
    fun insertIsUpsertOnPrimaryKey() = runTest {
        val store = freshInMemoryStore()
        store.insertChunk(sampleChunk(chunkIndex = 0L))
        store.insertChunk(sampleChunk(chunkIndex = 0L).copy(sizeBytes = 999L))

        val rows = store.chunksForSession("session-1")
        assertEquals(1, rows.size)
        assertEquals(999L, rows.single().sizeBytes)
    }

    @Test
    fun pendingChunksReturnsOnlyUnuploadedOrderedByCreation() = runTest {
        val store = freshInMemoryStore()
        store.insertChunk(sampleChunk(chunkIndex = 0L, createdAtMs = 200L))
        store.insertChunk(sampleChunk(chunkIndex = 1L, createdAtMs = 100L))
        store.markUploaded("session-1", 0L, "video", r2Key = "raw/cell/session-1/chunk-0.video", uploadedAtMs = 500L)

        val pending = store.pendingChunks()
        assertEquals(1, pending.size)
        assertEquals(1L, pending.single().chunkIndex)
        assertNull(pending.single().uploadedAtMs)
    }

    @Test
    fun markUploadedSetsR2KeyAndTimestamp() = runTest {
        val store = freshInMemoryStore()
        store.insertChunk(sampleChunk(chunkIndex = 0L))
        store.markUploaded("session-1", 0L, "video", r2Key = "raw/cell/session-1/chunk-0.video", uploadedAtMs = 555L)

        val row = store.chunksForSession("session-1").single()
        assertEquals("raw/cell/session-1/chunk-0.video", row.r2Key)
        assertEquals(555L, row.uploadedAtMs)
        assertTrue(store.pendingChunks().isEmpty())
    }

    @Test
    fun markUploadedCanRecordAPoisonedSentinelWithNullKey() = runTest {
        val store = freshInMemoryStore()
        store.insertChunk(sampleChunk(chunkIndex = 0L))
        store.markUploaded("session-1", 0L, "video", r2Key = null, uploadedAtMs = -1L)

        val row = store.chunksForSession("session-1").single()
        assertNull(row.r2Key)
        assertEquals(-1L, row.uploadedAtMs)
    }

    @Test
    fun deleteSessionRemovesAllItsChunksOnly() = runTest {
        val store = freshInMemoryStore()
        store.insertChunk(sampleChunk(sessionId = "session-1", chunkIndex = 0L))
        store.insertChunk(sampleChunk(sessionId = "session-1", chunkIndex = 1L, kind = "sidecar"))
        store.insertChunk(sampleChunk(sessionId = "session-2", chunkIndex = 0L))

        store.deleteSession("session-1")

        assertTrue(store.chunksForSession("session-1").isEmpty())
        assertEquals(1, store.chunksForSession("session-2").size)
    }

    @Test
    fun orphanSessionsFindsDistinctSessionsWithStalePendingChunks() = runTest {
        val store = freshInMemoryStore()
        store.insertChunk(sampleChunk(sessionId = "stale-session", chunkIndex = 0L, createdAtMs = 100L))
        store.insertChunk(sampleChunk(sessionId = "stale-session", chunkIndex = 1L, createdAtMs = 101L))
        store.insertChunk(sampleChunk(sessionId = "fresh-session", chunkIndex = 0L, createdAtMs = 900L))
        store.insertChunk(sampleChunk(sessionId = "uploaded-session", chunkIndex = 0L, createdAtMs = 50L))
        store.markUploaded("uploaded-session", 0L, "video", r2Key = "raw/x", uploadedAtMs = 60L)

        val orphans = store.orphanSessions(olderThanMs = 500L)
        assertEquals(listOf("stale-session"), orphans)
    }
}
