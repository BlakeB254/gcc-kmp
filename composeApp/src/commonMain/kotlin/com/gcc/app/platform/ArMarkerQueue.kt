package com.gcc.app.platform

import com.gcc.shared.model.ArDetectedMarkerBatchResponse
import com.gcc.shared.model.ArDetectedMarkerRequest
import com.gcc.shared.serialization.GccJson
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Local buffer for AR-detected markers, flushed in one batch to the
 * observations endpoint. On failure (offline, transient error), markers stay
 * queued for the next flush attempt rather than being lost -- mirrors
 * CoverageEventQueue exactly (see that class's doc comment for the full
 * rationale); a marker discovered with no signal must not silently vanish.
 */
class ArMarkerQueue(
    private val submitFn: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse,
    private val settings: Settings = Settings(),
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<ArDetectedMarkerRequest>().apply {
        addAll(loadPersisted())
    }
    private var flushInProgress = false

    private fun loadPersisted(): List<ArDetectedMarkerRequest> {
        val json = settings.getStringOrNull(KEY_PENDING_MARKERS) ?: return emptyList()
        return try {
            GccJson.decodeFromString(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistLocked() {
        settings.putString(KEY_PENDING_MARKERS, GccJson.encodeToString(pending))
    }

    suspend fun enqueue(marker: ArDetectedMarkerRequest) {
        mutex.withLock {
            pending.add(marker)
            persistLocked()
        }
    }

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
                val batch = mutex.withLock { pending.take(MAX_BATCH_SIZE) }
                if (batch.isEmpty()) return true

                try {
                    val response = submitFn(batch)
                    if (!response.success) return false
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
        const val MAX_BATCH_SIZE = 500
        private const val KEY_PENDING_MARKERS = "pending_ar_markers"
    }
}
