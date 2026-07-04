package com.gcc.app.platform

import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
import com.gcc.shared.offline.MemorySettings
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
        val queue = CoverageEventQueue(submitFn = fakeSubmit, settings = MemorySettings())

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
        val queue = CoverageEventQueue(submitFn = fakeSubmit, settings = MemorySettings())

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
        val queue = CoverageEventQueue(submitFn = fakeSubmit, settings = MemorySettings())
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
        val queue = CoverageEventQueue(submitFn = fakeSubmit, settings = MemorySettings())
        queue.enqueue(sampleEvent("a"))

        val firstFlush = async { queue.flush() }
        runCurrent()

        val secondFlush = async { queue.flush() }
        runCurrent()

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
        val batchSizes = mutableListOf<Int>()
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            batchSizes.add(events.size)
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }
        val queue = CoverageEventQueue(submitFn = fakeSubmit, settings = MemorySettings())
        repeat(CoverageEventQueue.MAX_BATCH_SIZE + 250) { queue.enqueue(sampleEvent("evt$it")) }

        val result = queue.flush()

        assertTrue(result)
        assertEquals(listOf(CoverageEventQueue.MAX_BATCH_SIZE, 250), batchSizes)
    }

    @Test
    fun persistedEventsSurviveAFreshInstance() = runTest {
        val sharedSettings = MemorySettings()
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }

        val firstProcessQueue = CoverageEventQueue(submitFn = fakeSubmit, settings = sharedSettings)
        firstProcessQueue.enqueue(sampleEvent("a"))
        firstProcessQueue.enqueue(sampleEvent("b"))
        // No flush() here — simulates the app being force-quit before the next
        // periodic flush attempt, with events still sitting in the queue.

        // A fresh instance sharing the same underlying settings simulates relaunch.
        var submittedBatch: List<ScanCoverageEventRequest>? = null
        val secondProcessQueue = CoverageEventQueue(
            submitFn = { events -> submittedBatch = events; ScanCoverageSubmitResponse(inserted = events.size, success = true) },
            settings = sharedSettings,
        )
        val result = secondProcessQueue.flush()

        assertTrue(result)
        assertEquals(2, submittedBatch?.size, "events enqueued before the simulated crash must still be present after relaunch")
    }

    @Test
    fun successfulFlushClearsThePersistedCopyNotJustTheInMemoryList() = runTest {
        val sharedSettings = MemorySettings()
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = { events ->
            ScanCoverageSubmitResponse(inserted = events.size, success = true)
        }

        val firstProcessQueue = CoverageEventQueue(submitFn = fakeSubmit, settings = sharedSettings)
        firstProcessQueue.enqueue(sampleEvent("a"))
        val flushResult = firstProcessQueue.flush()
        assertTrue(flushResult)

        // A fresh instance sharing the same settings simulates relaunch after
        // the flush already succeeded and the app was later killed anyway.
        var called = false
        val secondProcessQueue = CoverageEventQueue(
            submitFn = { events -> called = true; ScanCoverageSubmitResponse(inserted = events.size, success = true) },
            settings = sharedSettings,
        )
        val result = secondProcessQueue.flush()

        assertTrue(result)
        assertFalse(called, "nothing should be left to submit — the successful flush must have cleared the persisted copy too")
    }

    @Test
    fun corruptPersistedJsonDegradesToAnEmptyQueue() = runTest {
        val settings = MemorySettings()
        settings.putString("pending_coverage_events", "not valid json")
        var called = false
        val fakeSubmit: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse = {
            called = true
            ScanCoverageSubmitResponse(inserted = 0, success = true)
        }

        val queue = CoverageEventQueue(submitFn = fakeSubmit, settings = settings)
        val result = queue.flush()

        assertTrue(result, "construction against corrupt persisted data must not throw, and flush must succeed as a no-op")
        assertFalse(called, "there should be nothing to submit when persisted state was unparseable")
    }
}
