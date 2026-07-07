package com.gcc.shared.cache

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One recorded capture artifact chunk (video, pose/intrinsics sidecar, or LiDAR depth). */
data class CaptureChunkRecord(
    val sessionId: String,
    val chunkIndex: Long,
    val kind: String,
    val filePath: String,
    val sizeBytes: Long,
    val locationCellId: String,
    val r2Key: String?,
    val uploadedAtMs: Long?,
    val createdAtMs: Long,
)

/**
 * Bookkeeping for full-session AR capture chunks (Pillar 1), backed by
 * SQLDelight in the same `GccDatabase` as [ScanAreaCacheStore] -- ONE shared
 * driver, wired once in AppModule. The recorder writes chunk files to disk
 * (FileManager/Documents, never here) and calls [insertChunk] as each chunk
 * finalizes; [CaptureChunkUploader] drains [pendingChunks] and calls
 * [markUploaded] once a chunk lands in R2. Queries run off the caller's
 * thread (`Dispatchers.Default`) since capture-chunk bookkeeping must never
 * block the live AR/recording path.
 */
class CaptureChunkStore(private val driver: SqlDriver) {
    private val queries = GccDatabase(driver).captureQueueQueries

    init {
        ensureTable()
    }

    /**
     * keep in sync — upgraded installs create this table via
     * CaptureChunkStore.ensureTable, fresh installs via Schema.create.
     *
     * SQLDelight's `GccDatabase.Schema.create()` only runs against a brand
     * new "gcc.db" file (NativeSqliteDriver/AndroidSqliteDriver skip it once
     * `PRAGMA user_version` is already at the schema's version, and this
     * table's addition never bumped that version) — so a TestFlight upgrade
     * lands on a gcc.db that has scanAreaCache/routeCache but NOT
     * captureChunk, and the first [insertChunk] would throw. Running this
     * idempotent DDL here (every store construction, i.e. every process
     * launch) is the cheapest way to backfill the table on upgraded installs
     * without a real SQLDelight migration file. The DDL must stay
     * byte-consistent with CaptureQueue.sq's `CREATE TABLE captureChunk`.
     */
    private fun ensureTable() {
        driver.execute(
            null,
            """
            |CREATE TABLE IF NOT EXISTS captureChunk (
            |  sessionId TEXT NOT NULL,
            |  chunkIndex INTEGER NOT NULL,
            |  kind TEXT NOT NULL,
            |  filePath TEXT NOT NULL,
            |  sizeBytes INTEGER NOT NULL,
            |  locationCellId TEXT NOT NULL,
            |  r2Key TEXT,
            |  uploadedAtMs INTEGER,
            |  createdAtMs INTEGER NOT NULL,
            |  PRIMARY KEY(sessionId, chunkIndex, kind)
            |)
            """.trimMargin(),
            0,
        )
    }

    suspend fun insertChunk(chunk: CaptureChunkRecord) = withContext(Dispatchers.Default) {
        queries.insertChunk(
            sessionId = chunk.sessionId,
            chunkIndex = chunk.chunkIndex,
            kind = chunk.kind,
            filePath = chunk.filePath,
            sizeBytes = chunk.sizeBytes,
            locationCellId = chunk.locationCellId,
            r2Key = chunk.r2Key,
            uploadedAtMs = chunk.uploadedAtMs,
            createdAtMs = chunk.createdAtMs,
        )
    }

    /** Marks a chunk uploaded (real r2Key) or poisoned (r2Key = null, sentinel uploadedAtMs like -1). */
    suspend fun markUploaded(
        sessionId: String,
        chunkIndex: Long,
        kind: String,
        r2Key: String?,
        uploadedAtMs: Long,
    ) = withContext(Dispatchers.Default) {
        queries.markUploaded(
            r2Key = r2Key,
            uploadedAtMs = uploadedAtMs,
            sessionId = sessionId,
            chunkIndex = chunkIndex,
            kind = kind,
        )
    }

    suspend fun pendingChunks(): List<CaptureChunkRecord> = withContext(Dispatchers.Default) {
        queries.pendingChunks().executeAsList().map(::toRecord)
    }

    suspend fun chunksForSession(sessionId: String): List<CaptureChunkRecord> = withContext(Dispatchers.Default) {
        queries.chunksForSession(sessionId).executeAsList().map(::toRecord)
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.Default) {
        queries.deleteSession(sessionId)
    }

    /** Distinct sessionIds that still have pending (unuploaded) chunks older than [olderThanMs] absolute time. */
    suspend fun orphanSessions(olderThanMs: Long): List<String> = withContext(Dispatchers.Default) {
        queries.orphanSessions(olderThanMs).executeAsList()
    }

    private fun toRecord(row: CaptureChunk): CaptureChunkRecord =
        CaptureChunkRecord(
            sessionId = row.sessionId,
            chunkIndex = row.chunkIndex,
            kind = row.kind,
            filePath = row.filePath,
            sizeBytes = row.sizeBytes,
            locationCellId = row.locationCellId,
            r2Key = row.r2Key,
            uploadedAtMs = row.uploadedAtMs,
            createdAtMs = row.createdAtMs,
        )
}
