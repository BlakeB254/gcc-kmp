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

    suspend fun enqueue(event: ScanCoverageEventRequest) {
        mutex.withLock { pending.add(event) }
    }

    /** Returns true if the queue is empty or the flush succeeded; false on failure (events remain queued). */
    suspend fun flush(): Boolean {
        val batch = mutex.withLock { pending.toList() }
        if (batch.isEmpty()) return true

        return try {
            val response = submitFn(batch)
            if (response.success) {
                mutex.withLock { pending.removeAll(batch) }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
