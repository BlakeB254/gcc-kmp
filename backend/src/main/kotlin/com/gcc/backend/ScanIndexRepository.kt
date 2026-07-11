package com.gcc.backend

import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

/** A row to upsert into `scan_sessions`. */
data class SessionRow(
    val sessionId: String,
    val deviceId: String,
    val deviceModel: String?,
    val platform: String?,
    val osVersion: String?,
    val lapKind: String?,
    val routeId: Int?,
    val locationCellId: String?,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val photoCount: Int,
    val chunkCount: Int,
    val observedItemCount: Int,
    val scannedCellCount: Int,
    val hasObjectCapture: Boolean,
    val objectCaptureR2Key: String?,
    val manifestR2Key: String,
    val totalBytes: Long?,
    /** AR<->GPS pairings in the manifest (scan-splat-registration-contract).
     *  The DGX worker's solve fills the registration_* columns later. */
    val geoRefEventCount: Int = 0,
)

/** A row to upsert into `capture_chunks` (one R2 object). */
data class ChunkRow(
    val sessionId: String,
    val locationCellId: String,
    val artifactType: String,
    val r2Key: String,
    val format: String?,
    val byteSize: Long?,
    val capturedAtMs: Long?,
)

/**
 * Plain-JDBC upserts into the scan-index tables. Idempotent: `scan_sessions`
 * conflicts on `session_id`, `capture_chunks` on `r2_key`, so re-running a
 * sweep updates rather than duplicates. Drizzle (`site/src/db/schema.ts`) owns
 * the schema; this only reads/writes it.
 */
class ScanIndexRepository(private val dataSource: DataSource) {

    fun upsertSession(row: SessionRow) {
        dataSource.connection.use { c ->
            c.prepareStatement(UPSERT_SESSION).use { ps ->
                var i = 1
                ps.setString(i++, row.sessionId)
                ps.setString(i++, row.deviceId)
                ps.setNullableString(i++, row.deviceModel)
                ps.setNullableString(i++, row.platform)
                ps.setNullableString(i++, row.osVersion)
                ps.setNullableString(i++, row.lapKind)
                ps.setNullableInt(i++, row.routeId)
                ps.setNullableString(i++, row.locationCellId)
                ps.setNullableTs(i++, row.startedAtMs)
                ps.setNullableTs(i++, row.completedAtMs)
                ps.setInt(i++, row.photoCount)
                ps.setInt(i++, row.chunkCount)
                ps.setInt(i++, row.observedItemCount)
                ps.setInt(i++, row.scannedCellCount)
                ps.setBoolean(i++, row.hasObjectCapture)
                ps.setNullableString(i++, row.objectCaptureR2Key)
                ps.setString(i++, row.manifestR2Key)
                ps.setNullableLong(i++, row.totalBytes)
                ps.setInt(i++, row.geoRefEventCount)
                ps.executeUpdate()
            }
        }
    }

    /** Batch-upsert all chunks for one session on a single connection. */
    fun upsertChunks(rows: List<ChunkRow>) {
        if (rows.isEmpty()) return
        dataSource.connection.use { c ->
            c.prepareStatement(UPSERT_CHUNK).use { ps ->
                for (row in rows) {
                    var i = 1
                    ps.setString(i++, row.sessionId)
                    ps.setString(i++, row.locationCellId)
                    ps.setString(i++, row.artifactType)
                    ps.setString(i++, row.r2Key)
                    ps.setNullableString(i++, row.format)
                    ps.setNullableLong(i++, row.byteSize)
                    ps.setNullableTs(i++, row.capturedAtMs)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /**
     * Insert a reconstructed asset if its R2 key isn't already recorded.
     * `source_scan_coverage_event_ids` is NOT NULL jsonb; provenance linking is
     * Phase 3, so we record an empty array for now. Returns true if inserted.
     */
    fun insertReconstructionAssetIfAbsent(
        locationCellId: String,
        assetType: String,
        format: String,
        r2Key: String,
        version: Int,
    ): Boolean {
        dataSource.connection.use { c ->
            c.prepareStatement(INSERT_RECON_IF_ABSENT).use { ps ->
                ps.setString(1, locationCellId)
                ps.setString(2, assetType)
                ps.setString(3, format)
                ps.setString(4, r2Key)
                ps.setInt(5, version)
                ps.setString(6, r2Key) // NOT EXISTS guard
                return ps.executeUpdate() > 0
            }
        }
    }

    /**
     * Enqueue one Phase-3 reconstruction job per completed, unreconstructed
     * session that has a full capture-chunk set (pose sidecar + depth +
     * video — the fusion pipeline needs all three; GPS-only sessions are
     * permanently skipped). Idempotent on `session_id`; consumed by the DGX
     * worker (scripts/reconstruction/recon_worker.py) with
     * SELECT … FOR UPDATE SKIP LOCKED. Returns how many were newly queued.
     */
    fun enqueueReconstructionJobs(): Int {
        dataSource.connection.use { c ->
            c.prepareStatement(ENQUEUE_RECON_JOBS).use { ps ->
                return ps.executeUpdate()
            }
        }
    }

    private fun java.sql.PreparedStatement.setNullableString(idx: Int, v: String?) =
        if (v == null) setNull(idx, Types.VARCHAR) else setString(idx, v)

    private fun java.sql.PreparedStatement.setNullableInt(idx: Int, v: Int?) =
        if (v == null) setNull(idx, Types.INTEGER) else setInt(idx, v)

    private fun java.sql.PreparedStatement.setNullableLong(idx: Int, v: Long?) =
        if (v == null) setNull(idx, Types.BIGINT) else setLong(idx, v)

    private fun java.sql.PreparedStatement.setNullableTs(idx: Int, ms: Long?) =
        if (ms == null) setNull(idx, Types.TIMESTAMP) else setTimestamp(idx, Timestamp(ms))

    companion object {
        private val UPSERT_SESSION = """
            INSERT INTO scan_sessions
              (session_id, device_id, device_model, platform, os_version, lap_kind, route_id,
               location_cell_id, started_at, completed_at, photo_count, chunk_count,
               observed_item_count, scanned_cell_count, has_object_capture, object_capture_r2_key,
               manifest_r2_key, total_bytes, geo_ref_event_count, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (session_id) DO UPDATE SET
               device_id = EXCLUDED.device_id,
               device_model = EXCLUDED.device_model,
               platform = EXCLUDED.platform,
               os_version = EXCLUDED.os_version,
               lap_kind = EXCLUDED.lap_kind,
               route_id = EXCLUDED.route_id,
               location_cell_id = EXCLUDED.location_cell_id,
               started_at = EXCLUDED.started_at,
               completed_at = EXCLUDED.completed_at,
               photo_count = EXCLUDED.photo_count,
               chunk_count = EXCLUDED.chunk_count,
               observed_item_count = EXCLUDED.observed_item_count,
               scanned_cell_count = EXCLUDED.scanned_cell_count,
               has_object_capture = EXCLUDED.has_object_capture,
               object_capture_r2_key = EXCLUDED.object_capture_r2_key,
               manifest_r2_key = EXCLUDED.manifest_r2_key,
               total_bytes = EXCLUDED.total_bytes,
               geo_ref_event_count = EXCLUDED.geo_ref_event_count,
               indexed_at = now()
        """.trimIndent()

        private val UPSERT_CHUNK = """
            INSERT INTO capture_chunks
              (session_id, location_cell_id, artifact_type, r2_key, format, byte_size, captured_at, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (r2_key) DO UPDATE SET
               session_id = EXCLUDED.session_id,
               location_cell_id = EXCLUDED.location_cell_id,
               artifact_type = EXCLUDED.artifact_type,
               format = EXCLUDED.format,
               byte_size = EXCLUDED.byte_size,
               captured_at = EXCLUDED.captured_at,
               indexed_at = now()
        """.trimIndent()

        private val INSERT_RECON_IF_ABSENT = """
            INSERT INTO reconstruction_assets
              (location_cell_id, asset_type, format, r2_key, version, source_scan_coverage_event_ids, created_at)
            SELECT ?, ?, ?, ?, ?, '[]'::jsonb, now()
            WHERE NOT EXISTS (SELECT 1 FROM reconstruction_assets WHERE r2_key = ?)
        """.trimIndent()

        private val ENQUEUE_RECON_JOBS = """
            INSERT INTO reconstruction_jobs (session_id, location_cell_id)
            SELECT s.session_id, s.location_cell_id
            FROM scan_sessions s
            WHERE s.reconstruction_version IS NULL
              AND s.completed_at IS NOT NULL
              AND s.location_cell_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM capture_chunks c WHERE c.session_id = s.session_id AND c.format = 'jsonl')
              AND EXISTS (SELECT 1 FROM capture_chunks c WHERE c.session_id = s.session_id AND c.format = 'bin')
              AND EXISTS (SELECT 1 FROM capture_chunks c WHERE c.session_id = s.session_id AND c.format = 'mov')
            ON CONFLICT (session_id) DO NOTHING
        """.trimIndent()
    }
}
