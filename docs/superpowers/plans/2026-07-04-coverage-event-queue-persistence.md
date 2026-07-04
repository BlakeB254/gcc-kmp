# Coverage Event Queue Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `CoverageEventQueue` survive a force-quit/crash by persisting its pending events to disk, so a worker's unflushed field-scan data isn't silently lost.

**Architecture:** Add a `Settings`-backed persistence layer to the existing in-memory queue, following the exact pattern `TokenManager` already uses (`multiplatform-settings`, no new dependency). The pending list is JSON-serialized (via the existing shared `GccJson` instance) and written to `Settings` after every mutation; on construction, any previously-persisted list is loaded back in.

**Tech Stack:** Kotlin Multiplatform (commonMain/commonTest), `multiplatform-settings` (`com.russhwolf.settings.Settings`), `kotlinx.serialization`.

## Global Constraints

- No new dependencies — `multiplatform-settings` is already used this way by `TokenManager` (`shared/.../auth/TokenManager.kt:20`).
- `RouteScanViewModel`'s existing `CoverageEventQueue(submitFn = ...)` call site must not need to change — the new `settings` parameter must default exactly like `TokenManager`'s does.
- No change to the existing flush/retry/single-flight logic — only the durability of `pending` changes.
- Tests must not touch real platform storage (JVM `java.util.prefs.Preferences`, Android `SharedPreferences`, iOS `NSUserDefaults`) — use `MemorySettings` (`shared/src/commonMain/kotlin/com/gcc/shared/offline/MemorySettings.kt`) for full isolation, a fresh instance per test.

---

### Task 1: Persist `CoverageEventQueue` via `Settings`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageEventQueueTest.kt`

**Interfaces:**
- Produces: `class CoverageEventQueue(submitFn: suspend (List<ScanCoverageEventRequest>) -> ScanCoverageSubmitResponse, settings: Settings = Settings())` — the `settings` parameter is new; every other public member (`enqueue`, `flush`, `MAX_BATCH_SIZE`) is unchanged.

- [ ] **Step 1: Rewrite the test file — adjust the 5 existing tests for isolation, and add 3 new persistence tests**

Replace the full contents of `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageEventQueueTest.kt` with:

```kotlin
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
```

- [ ] **Step 2: Run the test file to confirm it fails to compile against the current implementation**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.CoverageEventQueueTest"`
Expected: FAIL — compile error, `Unresolved reference: settings` (no `settings` parameter exists on `CoverageEventQueue` yet) and/or `Unresolved reference: MemorySettings` if the import path doesn't yet resolve (it should — `shared` is already a dependency of `composeApp` — but confirm this is a real compile failure caused by the missing constructor parameter, not an import path typo).

- [ ] **Step 3: Implement persistence in `CoverageEventQueue.kt`**

Replace the full contents of `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt` with:

```kotlin
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
```

- [ ] **Step 4: Run the tests to confirm they all pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.CoverageEventQueueTest"`
Expected: PASS — 8 tests (5 existing + 3 new), no failures, pristine output (no stray warnings).

- [ ] **Step 5: Confirm the rest of the module still compiles clean (nothing else references the old constructor shape incompatibly)**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL — confirms `RouteScanViewModel.kt`'s existing `CoverageEventQueue(submitFn = ...)` call site (no `settings` argument) still compiles against the new default parameter.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageEventQueueTest.kt
git commit -m "Persist CoverageEventQueue to survive force-quit/crash"
```

---

## Self-Review Notes

- **Spec coverage**: §2 (persistence design) → Step 3. §3 (test isolation) → Step 1's rewrite of the 5 existing tests to use `MemorySettings()`. §4 (new test coverage, all 3 named scenarios) → Step 1's three new tests. §5 (out of scope) — nothing added beyond the spec.
- **Placeholder scan**: no TBD/TODO; every step has complete, real code.
- **Type consistency**: `CoverageEventQueue(submitFn, settings: Settings = Settings())` constructor shape is identical everywhere it's referenced (test file and implementation). `KEY_PENDING_EVENTS` string literal (`"pending_coverage_events"`) matches between the implementation's companion object and the corrupt-JSON test's direct `settings.putString(...)` call.
- **Caught during self-review**: Step 3's original draft imported `kotlinx.serialization.encodeToString` but called both `GccJson.encodeToString(...)` and `GccJson.decodeFromString(...)` — the reified `decodeFromString` extension needs its own import (`kotlinx.serialization.decodeFromString`). Fixed inline; the code block above already reflects the correction.
