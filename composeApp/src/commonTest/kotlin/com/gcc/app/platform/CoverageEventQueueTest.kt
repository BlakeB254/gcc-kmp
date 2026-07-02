package com.gcc.app.platform

import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun sampleEvent(deviceId: String = "test") = ScanCoverageEventRequest(
    deviceId = deviceId, platform = "ios", lat = 41.87, lng = -87.68,
    distanceMeters = 5.0, scanMode = "route", deviceTier = "pro_scanner",
    capturedAt = "2026-07-02T12:00:00Z",
)

class CoverageEventQueueTest {

    @Test
    fun flushSubmitsAllQueuedEventsInOneBatch() = runTest {
        var submittedBatch: List<ScanCoverageEventRequest>? = null
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            submittedBatch = events
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }
        val queue = CoverageEventQueue(submitFn = fakeSubmit)

        queue.enqueue(sampleEvent("a"))
        queue.enqueue(sampleEvent("b"))
        val result = queue.flush()

        assertTrue(result)
        assertEquals(2, submittedBatch?.size)
    }

    @Test
    fun flushOnEmptyQueueIsANoOpSuccess() = runTest {
        var called = false
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = {
            called = true
            ScanCoverageSubmitResponse(inserted = 0, success = true)
        }
        val queue = CoverageEventQueue(submitFn = fakeSubmit)

        val result = queue.flush()

        assertTrue(result)
        assertFalse(called, "submit should not be called for an empty queue")
    }

    @Test
    fun failedFlushKeepsEventsQueuedForRetry() = runTest {
        var attempts = 0
        var submittedBatch: List<ScanCoverageEventRequest>? = null
        val original = sampleEvent("a")
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            attempts++
            if (attempts == 1) throw RuntimeException("network unreachable")
            submittedBatch = events
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }
        val queue = CoverageEventQueue(submitFn = fakeSubmit)
        queue.enqueue(original)

        val firstAttempt = queue.flush()
        assertFalse(firstAttempt, "flush should report failure on network error")

        val secondAttempt = queue.flush()
        assertTrue(secondAttempt, "retry should succeed and still contain the original event")
        assertEquals(2, attempts)
        assertEquals(listOf(original), submittedBatch, "retry must resubmit the exact original event, not a coincidentally-equal one")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentFlushCallsResultInOnlyOneSubmitCall() = runTest {
        var callCount = 0
        val gate = CompletableDeferred<Unit>()
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            callCount++
            gate.await()
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }
        val queue = CoverageEventQueue(submitFn = fakeSubmit)
        queue.enqueue(sampleEvent("a"))

        // Launch two flush() calls without awaiting the first, so they overlap.
        val firstFlush = async { queue.flush() }
        runCurrent() // drive the first flush up through submitFn, where it suspends on the gate

        val secondFlush = async { queue.flush() }
        runCurrent() // drive the second flush as far as it can go while the first is still in-flight

        // Nothing can complete submitFn until the gate opens.
        gate.complete(Unit)
        advanceUntilIdle()

        val firstResult = firstFlush.await()
        val secondResult = secondFlush.await()

        assertEquals(1, callCount, "submitFn must be invoked exactly once despite two concurrent flush() calls")
        assertTrue(firstResult, "the flush that acquired the single-flight guard should still succeed")
        assertFalse(secondResult, "a flush already in progress should be rejected, not silently duplicate-submitted")
    }

    @Test
    fun oversizedBacklogDrainsInServerSizedChunks() = runTest {
        // The live endpoint 400s any batch over MAX_BATCH_SIZE, so a backlog
        // accumulated offline must drain in capped slices within one flush().
        val batchSizes = mutableListOf<Int>()
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            batchSizes.add(events.size)
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }
        val queue = CoverageEventQueue(submitFn = fakeSubmit)
        repeat(CoverageEventQueue.MAX_BATCH_SIZE + 250) { queue.enqueue(sampleEvent("evt$it")) }

        val result = queue.flush()

        assertTrue(result)
        assertEquals(listOf(CoverageEventQueue.MAX_BATCH_SIZE, 250), batchSizes)
    }
}
