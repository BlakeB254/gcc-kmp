package com.gcc.app.platform

import com.gcc.shared.model.ArDetectedMarkerBatchResponse
import com.gcc.shared.model.ArDetectedMarkerRequest
import com.gcc.shared.offline.MemorySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun sampleMarker(label: String = "bottle") = ArDetectedMarkerRequest(
    lat = 41.87, lng = -87.68, label = label,
    capturedAt = Instant.parse("2026-07-04T12:00:00Z"),
)

class ArMarkerQueueTest {

    @Test
    fun flushSubmitsAllQueuedMarkersInOneBatch() = runTest {
        var submittedBatch: List<ArDetectedMarkerRequest>? = null
        val fakeSubmit: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse = { markers ->
            submittedBatch = markers
            ArDetectedMarkerBatchResponse(inserted = markers.size, success = true)
        }
        val queue = ArMarkerQueue(submitFn = fakeSubmit, settings = MemorySettings())

        queue.enqueue(sampleMarker("bottle"))
        queue.enqueue(sampleMarker("wrapper"))
        val result = queue.flush()

        assertTrue(result)
        assertEquals(2, submittedBatch?.size)
    }

    @Test
    fun flushOnEmptyQueueIsANoOpSuccess() = runTest {
        var called = false
        val fakeSubmit: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse = {
            called = true
            ArDetectedMarkerBatchResponse(inserted = 0, success = true)
        }
        val queue = ArMarkerQueue(submitFn = fakeSubmit, settings = MemorySettings())

        val result = queue.flush()

        assertTrue(result)
        assertFalse(called, "submit should not be called for an empty queue")
    }

    @Test
    fun failedFlushKeepsMarkersQueuedForRetry() = runTest {
        var attempts = 0
        var submittedBatch: List<ArDetectedMarkerRequest>? = null
        val original = sampleMarker("bottle")
        val fakeSubmit: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse = { markers ->
            attempts++
            if (attempts == 1) throw RuntimeException("network unreachable")
            submittedBatch = markers
            ArDetectedMarkerBatchResponse(inserted = markers.size, success = true)
        }
        val queue = ArMarkerQueue(submitFn = fakeSubmit, settings = MemorySettings())
        queue.enqueue(original)

        val firstAttempt = queue.flush()
        assertFalse(firstAttempt, "flush should report failure on network error")

        val secondAttempt = queue.flush()
        assertTrue(secondAttempt, "retry should succeed and still contain the original marker")
        assertEquals(2, attempts)
        assertEquals(listOf(original), submittedBatch, "retry must resubmit the exact original marker")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentFlushCallsResultInOnlyOneSubmitCall() = runTest {
        var callCount = 0
        val gate = CompletableDeferred<Unit>()
        val fakeSubmit: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse = { markers ->
            callCount++
            gate.await()
            ArDetectedMarkerBatchResponse(inserted = markers.size, success = true)
        }
        val queue = ArMarkerQueue(submitFn = fakeSubmit, settings = MemorySettings())
        queue.enqueue(sampleMarker())

        val firstFlush = async { queue.flush() }
        runCurrent()

        val secondFlush = async { queue.flush() }
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        val firstResult = firstFlush.await()
        val secondResult = secondFlush.await()

        assertEquals(1, callCount, "submitFn must be invoked exactly once despite two concurrent flush() calls")
        assertTrue(firstResult)
        assertFalse(secondResult)
    }

    @Test
    fun oversizedBacklogDrainsInServerSizedChunks() = runTest {
        val batchSizes = mutableListOf<Int>()
        val fakeSubmit: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse = { markers ->
            batchSizes.add(markers.size)
            ArDetectedMarkerBatchResponse(inserted = markers.size, success = true)
        }
        val queue = ArMarkerQueue(submitFn = fakeSubmit, settings = MemorySettings())
        repeat(ArMarkerQueue.MAX_BATCH_SIZE + 50) { queue.enqueue(sampleMarker("bottle$it")) }

        val result = queue.flush()

        assertTrue(result)
        assertEquals(listOf(ArMarkerQueue.MAX_BATCH_SIZE, 50), batchSizes)
    }

    @Test
    fun persistedMarkersSurviveAFreshInstance() = runTest {
        val sharedSettings = MemorySettings()
        val fakeSubmit: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse = { markers ->
            ArDetectedMarkerBatchResponse(inserted = markers.size, success = true)
        }

        val firstProcessQueue = ArMarkerQueue(submitFn = fakeSubmit, settings = sharedSettings)
        firstProcessQueue.enqueue(sampleMarker("bottle"))
        firstProcessQueue.enqueue(sampleMarker("wrapper"))
        // No flush() here — simulates a force-quit before the next periodic flush.

        var submittedBatch: List<ArDetectedMarkerRequest>? = null
        val secondProcessQueue = ArMarkerQueue(
            submitFn = { markers -> submittedBatch = markers; ArDetectedMarkerBatchResponse(markers.size, true) },
            settings = sharedSettings,
        )
        val result = secondProcessQueue.flush()

        assertTrue(result)
        assertEquals(2, submittedBatch?.size, "markers enqueued before the simulated crash must still be present after relaunch")
    }

    @Test
    fun corruptPersistedJsonDegradesToAnEmptyQueue() = runTest {
        val settings = MemorySettings()
        settings.putString("pending_ar_markers", "not valid json")
        var called = false
        val fakeSubmit: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse = {
            called = true
            ArDetectedMarkerBatchResponse(0, true)
        }

        val queue = ArMarkerQueue(submitFn = fakeSubmit, settings = settings)
        val result = queue.flush()

        assertTrue(result, "construction against corrupt persisted data must not throw")
        assertFalse(called, "there should be nothing to submit when persisted state was unparseable")
    }
}
