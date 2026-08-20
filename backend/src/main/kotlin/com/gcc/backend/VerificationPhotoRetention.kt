package com.gcc.backend

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Retention sweep for participant verification photos (`verification/` prefix
 * in R2 — government ID + proof-of-address uploads from
 * /api/workers/me/verification/upload). These are PII we should not hold
 * longer than the review flow needs them.
 *
 * Default posture is SAFE: the job only exists when VERIFICATION_RETENTION_DAYS
 * is set above 0, and even then it runs in dry-run mode (log + count only)
 * until VERIFICATION_RETENTION_DRY_RUN=false is set explicitly.
 *
 * Enforce-mode order per object is tombstone-then-delete:
 *   1. media rows referencing the key get `url = 'purged:' || key` — the
 *      admin documents route passes non-`verification/` strings through
 *      untouched, so a purged reference renders as a broken image instead of
 *      presigning a 404, and the purge stays visible in the DB.
 *   2. the R2 object is deleted.
 * If the delete fails after the tombstone, the blob becomes unreferenced and
 * the next sweep finds and removes it — no stuck half-state.
 *
 * Review-flow guard: a key is NEVER purged while its worker is still in the
 * verification loop — overall `verification_status` in (pending, needs_info)
 * or either per-document status in (pending, resubmit). A key whose worker id
 * no longer resolves to a workers row cannot be in any review flow and is
 * purgeable.
 *
 * Idempotent and safe to run on a schedule.
 */
class VerificationPhotoRetention(
    private val r2: R2Storage,
    private val dataSource: DataSource,
    private val retentionDays: Long,
    private val dryRun: Boolean,
) {
    private val log = LoggerFactory.getLogger("verification-retention")

    @Serializable
    data class Result(
        val dryRun: Boolean,
        val retentionDays: Long,
        val scanned: Int,
        val expired: Int,
        val skippedPendingReview: Int,
        val expiredReferenced: Int,
        val deleted: Int,
        val tombstoned: Int,
        val durationMs: Long,
    )

    fun runOnce(): Result {
        val start = System.currentTimeMillis()
        val cutoffMs = start - retentionDays * 24 * 60 * 60 * 1000
        val objects = r2.listAllWithInfo(PREFIX)

        val expired = objects.filter { it.value.lastModifiedEpochMs in 1 until cutoffMs }
        if (expired.isEmpty()) {
            return Result(dryRun, retentionDays, objects.size, 0, 0, 0, 0, 0, System.currentTimeMillis() - start)
        }

        val protectedKeys = findReviewProtectedKeys(expired.keys)
        val purgeable = expired.keys - protectedKeys
        if (protectedKeys.isNotEmpty()) {
            log.info("skipping {} expired object(s) still in the verification review flow", protectedKeys.size)
        }

        val referencedKeys = findReferencedKeys(purgeable)
        var deleted = 0
        var tombstoned = 0

        for (key in purgeable) {
            if (dryRun) {
                log.info("dry-run: would purge {} (referenced={})", key, key in referencedKeys)
                continue
            }
            try {
                if (key in referencedKeys && tombstoneMediaRows(key)) {
                    tombstoned++
                }
                r2.deleteObject(key)
                deleted++
                log.info("purged verification object {}", key)
            } catch (e: Exception) {
                // Tombstone-first means a failure here leaves at most an
                // unreferenced blob, which a later sweep picks up.
                log.warn("failed to purge {}: {}", key, e.message)
            }
        }

        return Result(
            dryRun, retentionDays, objects.size, expired.size, protectedKeys.size,
            referencedKeys.size, deleted, tombstoned, System.currentTimeMillis() - start,
        )
    }

    /**
     * Keys whose worker is still inside the verification review loop. The
     * worker id is the second path segment (`verification/{workerId}/…`);
     * keys that don't parse or whose worker row is gone cannot be in a
     * review flow, so they are NOT protected.
     */
    private fun findReviewProtectedKeys(keys: Set<String>): Set<String> {
        val byWorker = keys.mapNotNull { key ->
            WORKER_KEY_PATTERN.find(key)?.let { it.groupValues[1].toInt() to key }
        }.groupBy({ it.first }, { it.second })
        if (byWorker.isEmpty()) return emptySet()

        val protectedWorkers = HashSet<Int>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id FROM workers
                WHERE id = ANY(?)
                  AND (verification_status IN ('pending', 'needs_info')
                       OR verification_government_id_status IN ('pending', 'resubmit')
                       OR verification_proof_of_address_status IN ('pending', 'resubmit'))
                """.trimIndent()
            ).use { ps ->
                ps.setArray(1, conn.createArrayOf("integer", byWorker.keys.toTypedArray()))
                ps.executeQuery().use { rs ->
                    while (rs.next()) protectedWorkers.add(rs.getInt(1))
                }
            }
        }
        return byWorker.filterKeys { it in protectedWorkers }.values.flatten().toSet()
    }

    /** Which of [keys] are still referenced by a `media.url` row. */
    private fun findReferencedKeys(keys: Set<String>): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val out = HashSet<String>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT url FROM media WHERE url = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("varchar", keys.toTypedArray()))
                ps.executeQuery().use { rs ->
                    while (rs.next()) rs.getString(1)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    /** Rewrite media rows pointing at [key] to the purged tombstone. */
    private fun tombstoneMediaRows(key: String): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE media SET url = ?, updated_at = now() WHERE url = ?").use { ps ->
                ps.setString(1, TOMBSTONE_PREFIX + key)
                ps.setString(2, key)
                return ps.executeUpdate() > 0
            }
        }
    }

    companion object {
        const val PREFIX = "verification/"
        const val TOMBSTONE_PREFIX = "purged:"
        private val WORKER_KEY_PATTERN = Regex("""^verification/(\d+)/""")
    }
}
