package com.gcc.app.platform

import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
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
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            attempts++
            if (attempts == 1) throw RuntimeException("network unreachable")
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }
        val queue = CoverageEventQueue(submitFn = fakeSubmit)
        queue.enqueue(sampleEvent("a"))

        val firstAttempt = queue.flush()
        assertFalse(firstAttempt, "flush should report failure on network error")

        val secondAttempt = queue.flush()
        assertTrue(secondAttempt, "retry should succeed and still contain the original event")
        assertEquals(2, attempts)
    }
}
