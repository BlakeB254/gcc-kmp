package com.gcc.app.platform

import com.gcc.shared.model.RetractCoverageRequest
import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
import com.gcc.shared.serialization.GccJson
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
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

    /**
     * Guarded by [mutex]. Size of the batch flush() has snapshotted and handed to
     * `submitFn` but not yet removed from `pending` — i.e. the in-flight prefix.
     * purgeSince() must never touch indices < inFlightCount: those events are
     * already "in the mail" to the server, and the server-side retraction window
     * ([CoverageRetractionQueue]) is what undoes already-accepted events, not this
     * queue. Treating the in-flight prefix as untouchable here is correct, not
     * lossy — it mirrors "already flushed" semantics for events that may complete
     * their flush at any moment while purgeSince runs concurrently.
     */
    private var inFlightCount = 0

    /**
     * Guarded by [mutex]. Set by [purgeSince] when an in-window event was shielded
     * by [inFlightCount] (i.e. skipped because it was already handed to `submitFn`).
     * If that in-flight submit later fails, the shielded event is still sitting in
     * `pending` — but by then the server-side retraction window for it has already
     * run, so a later flush() must not resubmit it. [flush] applies this deferred
     * cutoff (full walk, nothing in-flight to protect) on the failure/exception
     * paths, and DISCHARGES it (clears without purging) the moment a batch is
     * successfully delivered: the shielded events reached the server, so the
     * deferred obligation is fulfilled. Leaving the cutoff armed past that point
     * would let a later transient batch failure wrongly purge legitimate NEW
     * events (enqueued after the flip, never shielded) whose capturedAt happens
     * to be >= the stale cutoff. A purge during a later in-flight batch simply
     * re-arms it. `maxOf` across repeated purgeSince calls during the same
     * in-flight window so the widest cutoff wins.
     */
    private var pendingPurgeCutoffMs: Long? = null

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
     * Must be called with `mutex` already held. Removes events at index >=
     * [fromIndex] whose parseable `capturedAt` is >= [cutoffEpochMs], walking
     * back-to-front so removals don't shift indices still to be examined.
     * Unparseable timestamps are kept (the server validates them). Returns the
     * number of events removed. Shared by [purgeSince] (`fromIndex =
     * inFlightCount`, protecting the in-flight prefix) and the deferred purge
     * applied in [flush] (`fromIndex = 0`, a full walk once nothing is in flight).
     */
    private fun removeInWindowFrom(fromIndex: Int, cutoffEpochMs: Long): Int {
        val before = pending.size
        var i = pending.size - 1
        while (i >= fromIndex) {
            val at = try {
                Instant.parse(pending[i].capturedAt).toEpochMilliseconds()
            } catch (e: Exception) {
                i--
                continue
            }
            if (at >= cutoffEpochMs) {
                pending.removeAt(i)
            }
            i--
        }
        return before - pending.size
    }

    /**
     * Must be called with `mutex` already held. If a deferred purge cutoff is
     * pending, applies it now (full walk — nothing is in flight at this point)
     * and clears it. Called from [flush]'s failure/exception paths, where the
     * shielded in-flight batch is still sitting in `pending` unsent.
     */
    private fun applyDeferredPurgeLocked() {
        val cutoff = pendingPurgeCutoffMs ?: return
        val dropped = removeInWindowFrom(0, cutoff)
        if (dropped > 0) persistLocked()
        pendingPurgeCutoffMs = null
    }

    /**
     * INDOOR-flip purge: drop still-pending events captured at/after the
     * cutoff so they never reach the server, and re-persist. Returns how many
     * were dropped. Events that already flushed are handled by the retraction
     * queue instead (spec §2).
     *
     * Never touches indices < [inFlightCount]: a concurrent flush() may have
     * already handed that prefix to `submitFn` and released the mutex while
     * awaiting the response, so flush()'s later positional `removeAt(0)` must
     * still see exactly that prefix at the front of `pending`. Removing (or
     * reordering via removeAll) any of those elements here would desync the
     * positional removal — wrong elements dropped, or an IndexOutOfBounds
     * mid-loop, and in-memory/persisted state would diverge from what the
     * server actually received. In-flight events are treated as "already
     * flushed" for purge purposes; see [inFlightCount] for why that's safe.
     * Iterates by explicit index (not removeAll) so the in-flight prefix can
     * be skipped precisely; walks back-to-front so removals don't shift the
     * indices still to be examined.
     *
     * Deferred-purge semantics for the shielded in-flight prefix: an in-window
     * event sitting in that prefix is either (a) delivered — the in-flight
     * submit succeeds, and whatever server-side retraction ran after its
     * arrival is the only thing that can still undo it, this queue has nothing
     * left to do — or (b) dropped — the in-flight submit fails, and [flush]
     * applies the cutoff recorded here ([pendingPurgeCutoffMs]) via
     * [applyDeferredPurgeLocked] before returning, once the prefix is no longer
     * in flight. Either way, a shielded event is never resubmitted after a
     * failed flush.
     */
    suspend fun purgeSince(cutoffEpochMs: Long): Int {
        mutex.withLock {
            val shielded = (0 until inFlightCount).any { idx ->
                try {
                    Instant.parse(pending[idx].capturedAt).toEpochMilliseconds() >= cutoffEpochMs
                } catch (e: Exception) {
                    false
                }
            }
            if (shielded) {
                pendingPurgeCutoffMs = maxOf(pendingPurgeCutoffMs ?: Long.MIN_VALUE, cutoffEpochMs)
            }
            val dropped = removeInWindowFrom(inFlightCount, cutoffEpochMs)
            if (dropped > 0) persistLocked()
            return dropped
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
                // inFlightCount is set here, under the same lock as the snapshot, so
                // purgeSince() can never observe a batch size without also observing
                // that these elements are in flight. It must be reset to 0 on every
                // exit from this iteration — success, failed response, and exception
                // alike — before the next snapshot (or return) so purgeSince() never
                // treats a stale prefix as untouchable.
                val batch = mutex.withLock {
                    val b = pending.take(MAX_BATCH_SIZE)
                    inFlightCount = b.size
                    b
                }
                if (batch.isEmpty()) {
                    // Fully drained: any shielded in-window events from purgeSince() were
                    // either submitted in an earlier successful iteration (server has them;
                    // this queue can do nothing more) or there never was a shielded event.
                    // Either way there is nothing left to defer-purge.
                    mutex.withLock { pendingPurgeCutoffMs = null }
                    return true
                }

                try {
                    val response = submitFn(batch)
                    if (!response.success) {
                        // Batch rejected: it stays in `pending` unsent. If purgeSince()
                        // shielded an in-window event inside this exact batch, that event
                        // is still here — but the retraction window it depended on has
                        // already run, so it must be dropped now rather than resubmitted
                        // by a later flush().
                        mutex.withLock {
                            inFlightCount = 0
                            applyDeferredPurgeLocked()
                        }
                        return false
                    }
                    // Position-based removal, not removeAll(batch): ScanCoverageEventRequest
                    // is a plain data class with no id field, so structural equality could
                    // match a distinct event enqueued mid-flight and drop that one instead of
                    // the submitted one. The snapshotted batch is always the first
                    // batch.size elements of `pending` (enqueue only appends, the
                    // single-flight guard above ensures no other flush() is mutating
                    // `pending` concurrently, and purgeSince() leaves the first
                    // inFlightCount elements untouched), so removing by position is safe.
                    mutex.withLock {
                        repeat(batch.size) { pending.removeAt(0) }
                        inFlightCount = 0
                        // Successful delivery discharges any deferred purge: the shielded
                        // events (if any) are on the server now — the queue has nothing
                        // left to drop for that flip. Clearing here (not just on full
                        // drain) is essential: if a LATER batch in this same loop fails,
                        // a stale cutoff would wrongly purge legitimate new events
                        // enqueued after the flip that were never shielded.
                        pendingPurgeCutoffMs = null
                        persistLocked()
                    }
                } catch (e: Exception) {
                    // Same reasoning as the failure-response branch above: the batch never
                    // left `pending`, so any shielded in-window event in it must be
                    // defer-purged now, not left to resubmit later.
                    mutex.withLock {
                        inFlightCount = 0
                        applyDeferredPurgeLocked()
                    }
                    return false
                }
            }
        } finally {
            // Defensive: every explicit exit above already resets inFlightCount, but
            // reset it here too so an unexpected exit (e.g. coroutine cancellation
            // propagating past the inner catch) can never leave a stale nonzero
            // inFlightCount behind to wrongly shield elements from a later purge.
            // pendingPurgeCutoffMs is likewise cleared: every explicit exit above
            // already discharged or applied it, so anything still set here is stale
            // state from an abnormal exit — carrying it into the NEXT flush() call
            // could wrongly purge never-shielded events on that call's failure path.
            mutex.withLock {
                inFlightCount = 0
                pendingPurgeCutoffMs = null
                flushInProgress = false
            }
        }
    }

    companion object {
        /** Server-side cap on POST /api/scans/coverage batch size. */
        const val MAX_BATCH_SIZE = 500

        /** Settings key for the persisted pending-events JSON blob. */
        private const val KEY_PENDING_EVENTS = "pending_coverage_events"
    }
}

/**
 * Persistent queue of coverage RETRACTIONS — windows of already-flushed
 * events the server must undo after an INDOOR flip. Same offline-safe
 * pattern as [CoverageEventQueue]: enqueue persists immediately; flush
 * retries until the server accepts.
 */
class CoverageRetractionQueue(
    private val submitFn: suspend (RetractCoverageRequest) -> Boolean,
    private val settings: Settings = Settings(),
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<RetractCoverageRequest>().apply {
        val json = settings.getStringOrNull(KEY_PENDING_RETRACTIONS)
        if (json != null) {
            try {
                addAll(GccJson.decodeFromString<List<RetractCoverageRequest>>(json))
            } catch (e: Exception) {
                // Corrupt persisted state degrades to empty, same as the event queue.
            }
        }
    }
    private var flushInProgress = false

    private fun persistLocked() {
        settings.putString(KEY_PENDING_RETRACTIONS, GccJson.encodeToString(pending))
    }

    suspend fun enqueue(request: RetractCoverageRequest) {
        mutex.withLock {
            pending.add(request)
            persistLocked()
        }
    }

    /**
     * True when empty or all retractions were accepted; false leaves the rest queued.
     *
     * Single-flight, same pattern as [CoverageEventQueue.flush]: if a flush is
     * already in progress when this is called, this call returns `false`
     * immediately without invoking `submitFn` — it does not wait for the
     * in-flight flush or retry. Without this guard, two concurrent flush()
     * calls could both read the same `firstOrNull()` head, both submit it, and
     * then both race to removeAt(0) — double-submitting a retraction and/or
     * dropping the wrong element.
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
            while (true) {
                val next = mutex.withLock { pending.firstOrNull() } ?: return true
                val ok = try {
                    submitFn(next)
                } catch (e: Exception) {
                    false
                }
                if (!ok) return false
                mutex.withLock {
                    pending.removeAt(0)
                    persistLocked()
                }
            }
        } finally {
            mutex.withLock { flushInProgress = false }
        }
    }

    companion object {
        private const val KEY_PENDING_RETRACTIONS = "pending_coverage_retractions"
    }
}
