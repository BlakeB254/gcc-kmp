package com.gcc.app.platform

import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
import com.gcc.shared.serialization.GccJson
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Local buffer for ScanCoverageEvents, flushed in one batch to the coverage
 * ledger endpoint. On failure (offline, transient error), events stay queued
 * for the next flush attempt rather than being lost — per design spec §1.6.
 *
 * Persisted via [settings] (same `multiplatform-settings` pattern as
 * TokenManager) so a force-quit/crash between flushes doesn't lose queued
 * events — the next app launch reloads them from disk and the next periodic
 * flush picks up where the previous process left off.
 */
class CoverageEventQueue(
    private val submitFn: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse,
    private val settings: Settings = Settings(),
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<ScanCoverageEventRequest>().apply {
        addAll(loadPersisted())
    }
    private var flushInProgress = false

    private fun loadPersisted(): List<ScanCoverageEventRequest> {
        val json = settings.getStringOrNull(KEY_PENDING_EVENTS) ?: return emptyList()
        return try {
            GccJson.decodeFromString(json)
        } catch (e: Exception) {
            // Corrupt/unreadable persisted state must never crash startup —
            // degrade to an empty queue, same as if nothing had been persisted.
            emptyList()
        }
    }

    /** Must be called with `mutex` already held — writes the current `pending` snapshot to disk. */
    private fun persistLocked() {
        settings.putString(KEY_PENDING_EVENTS, GccJson.encodeToString(pending))
    }

    suspend fun enqueue(event: ScanCoverageEventRequest) {
        mutex.withLock {
            pending.add(event)
            persistLocked()
        }
    }

    /**
     * Returns true if the queue is empty or the flush succeeded; false on failure
     * (events remain queued for the next attempt).
     *
     * Single-flight: only one flush() may be in progress at a time. `enqueue()`
     * only ever appends, so the batch snapshotted here is guaranteed to be a
     * prefix of `pending` at removal time — but only as long as a second flush()
     * can't be concurrently snapshotting/removing against the same list. If a
     * flush is already in progress when this is called, this call returns
     * `false` immediately without invoking `submitFn` — it does not wait for the
     * in-flight flush or retry. Any events still unsent will simply be picked up
     * by a later flush() call.
     */
    suspend fun flush(): Boolean {
        val acquiredFlush = mutex.withLock {
            if (flushInProgress) {
                false
            } else {
                flushInProgress = true
                true
            }
        }
        if (!acquiredFlush) return false

        try {
            // Chunked: the coverage endpoint rejects batches over MAX_BATCH_SIZE
            // with a 400, so a backlog built up offline must drain in slices —
            // one oversized POST would fail forever and the queue would only grow.
            while (true) {
                val batch = mutex.withLock { pending.take(MAX_BATCH_SIZE) }
                if (batch.isEmpty()) return true

                try {
                    val response = submitFn(batch)
                    if (!response.success) return false
                    // Position-based removal, not removeAll(batch): ScanCoverageEventRequest
                    // is a plain data class with no id field, so structural equality could
                    // match a distinct event enqueued mid-flight and drop that one instead of
                    // the submitted one. The snapshotted batch is always the first
                    // batch.size elements of `pending` (enqueue only appends, and the
                    // single-flight guard above ensures no other flush() is mutating
                    // `pending` concurrently), so removing by position is safe.
                    mutex.withLock {
                        repeat(batch.size) { pending.removeAt(0) }
                        persistLocked()
                    }
                } catch (e: Exception) {
                    return false
                }
            }
        } finally {
            mutex.withLock { flushInProgress = false }
        }
    }

    companion object {
        /** Server-side cap on POST /api/scans/coverage batch size. */
        const val MAX_BATCH_SIZE = 500

        /** Settings key for the persisted pending-events JSON blob. */
        private const val KEY_PENDING_EVENTS = "pending_coverage_events"
    }
}
