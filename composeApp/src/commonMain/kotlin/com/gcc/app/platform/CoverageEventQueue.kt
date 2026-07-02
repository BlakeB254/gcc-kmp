package com.gcc.app.platform

import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local buffer for ScanCoverageEvents, flushed in one batch to the coverage
 * ledger endpoint. On failure (offline, transient error), events stay queued
 * for the next flush attempt rather than being lost — per design spec §1.6.
 */
class CoverageEventQueue(
    private val submitFn: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse,
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<ScanCoverageEventRequest>()
    private var flushInProgress = false

    suspend fun enqueue(event: ScanCoverageEventRequest) {
        mutex.withLock { pending.add(event) }
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
            val batch = mutex.withLock { pending.toList() }
            if (batch.isEmpty()) return true

            return try {
                val response = submitFn(batch)
                if (response.success) {
                    // Position-based removal, not removeAll(batch): ScanCoverageEventRequest
                    // is a plain data class with no id field, so structural equality could
                    // match a distinct event enqueued mid-flight and drop that one instead of
                    // the submitted one. The snapshotted batch is always the first
                    // batch.size elements of `pending` (enqueue only appends, and the
                    // single-flight guard above ensures no other flush() is mutating
                    // `pending` concurrently), so removing by position is safe.
                    mutex.withLock {
                        repeat(batch.size) { pending.removeAt(0) }
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        } finally {
            mutex.withLock { flushInProgress = false }
        }
    }
}
