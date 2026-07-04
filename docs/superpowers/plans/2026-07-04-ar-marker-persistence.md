# AR Marker Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give live AR-detected "confirmed trash" markers a durable write path to the server's existing `WorldObservation` model, so they survive app relaunch instead of vanishing when the AR session ends.

**Architecture:** A new, minimal Next.js write endpoint reusing the existing `recordWorldObservation()` function (no new table), a durable client-side queue mirroring `CoverageEventQueue` exactly, and iOS geo-math to convert a marker's AR-local coordinates back to real lat/lng at the moment it's promoted — wired through a new listener that mirrors the existing `sceneVoteListener` pattern.

**Tech Stack:** Kotlin Multiplatform (`composeApp`, `shared`), Next.js App Router + Drizzle (`site/`), Ktor client (`GccApiClient`).

## Global Constraints

- No new server table — reuse the existing `worldObservations` table and `recordWorldObservation()` function entirely.
- Only `WorldMarkerKind.CONFIRMED_TRASH` markers get persisted. `EXPECTED_TRASH` markers already came *from* the server (via `getMapObservations`) — persisting them again would create spurious duplicate observations.
- No photo/media/confidence for AR-auto-detected observations — nothing is available at the point of passive detection (no deliberate capture action occurs).
- The client-server response envelope for mobile-facing Next.js POST routes is `{ data, success, message }` (confirmed identical to the Ktor `ApiResponse<T>` shape — verified against `site/src/app/api/scans/[id]/hotspots/route.ts`), so `GccApiClient.postJson<R>` (which unwraps `ApiResponse<R>.data`) works correctly against this new Next.js route.
- This plan is written against the current state of `main` — the AR checkmark-confirmation work (a separate, not-yet-merged PR) is NOT assumed to be present. `ArFrameFeedback.kt`'s `onTick()`/`LiveDetection` in this codebase still use their original (pre-checkmark) shapes.

---

### Task 1: Server write endpoint + shared DTOs + `GccApiClient` method

**Files:**
- Modify: `site/src/lib/world-observations.ts`
- Create: `site/src/app/api/observations/ar-detected/route.ts`
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/ArDetectedMarker.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`

**Interfaces:**
- Produces: `ArDetectedMarkerRequest(lat: Double, lng: Double, label: String, capturedAt: Instant)`, `ArDetectedMarkerBatchRequest(markers: List<ArDetectedMarkerRequest>)`, `ArDetectedMarkerBatchResponse(inserted: Int, success: Boolean)` (shared DTOs) — consumed by Task 2.
- Produces: `GccApiClient.reportArDetectedMarkers(markers: List<ArDetectedMarkerRequest>): ArDetectedMarkerBatchResponse` — consumed by Task 2.

- [ ] **Step 1: Widen the `sourceType` union**

In `site/src/lib/world-observations.ts`, change:

```typescript
export interface RecordObservationInput {
  sourceType: 'route_scan' | 'spot_report'
```

to:

```typescript
export interface RecordObservationInput {
  sourceType: 'route_scan' | 'spot_report' | 'ar_auto_detect'
```

(Read the actual current file first — confirm this interface's exact current shape before editing, since the plan's excerpt above only shows the one field that changes.)

- [ ] **Step 2: Add the shared DTOs**

Create `shared/src/commonMain/kotlin/com/gcc/shared/model/ArDetectedMarker.kt`:

```kotlin
package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ArDetectedMarkerRequest(
    val lat: Double,
    val lng: Double,
    val label: String,
    val capturedAt: Instant,
)

@Serializable
data class ArDetectedMarkerBatchRequest(
    val markers: List<ArDetectedMarkerRequest>,
)

@Serializable
data class ArDetectedMarkerBatchResponse(
    val inserted: Int,
    val success: Boolean,
)
```

- [ ] **Step 3: Add the Next.js route**

Create `site/src/app/api/observations/ar-detected/route.ts`, mirroring the hotspot route's imports/auth/envelope conventions (`site/src/app/api/scans/[id]/hotspots/route.ts`):

```typescript
import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { recordWorldObservation } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

interface ArDetectedMarker {
  lat: number
  lng: number
  label: string
  capturedAt: string
}

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const body = await request.json()
    const markers: ArDetectedMarker[] = body?.markers ?? []

    for (const marker of markers) {
      await recordWorldObservation({
        sourceType: 'ar_auto_detect',
        lat: marker.lat,
        lng: marker.lng,
        itemType: marker.label,
        state: 'dirty',
        capturedAt: marker.capturedAt,
      })
    }

    return NextResponse.json(
      { data: { inserted: markers.length, success: true }, success: true },
      { status: 201 },
    )
  } catch (error) {
    console.error('POST /api/observations/ar-detected error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to record markers' }, { status: 500 })
  }
}
```

- [ ] **Step 4: Add `GccApiClient.reportArDetectedMarkers()`**

In `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`, add near the other observation-related methods (e.g. near `getMapObservations`):

```kotlin
    suspend fun reportArDetectedMarkers(markers: List<ArDetectedMarkerRequest>): ArDetectedMarkerBatchResponse =
        postJson("/api/observations/ar-detected", ArDetectedMarkerBatchRequest(markers))
```

(Confirm `ArDetectedMarkerRequest`/`ArDetectedMarkerBatchRequest`/`ArDetectedMarkerBatchResponse` resolve via the file's existing `import com.gcc.shared.model.*` wildcard import — add an explicit import only if the file doesn't already wildcard-import `com.gcc.shared.model`.)

- [ ] **Step 5: Verify**

Run: `cd site && npx tsc --noEmit`
Expected: no new type errors.

Run: `./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL (confirms the new DTOs and `GccApiClient` method compile).

- [ ] **Step 6: Commit**

```bash
git add site/src/lib/world-observations.ts site/src/app/api/observations/ar-detected/route.ts shared/src/commonMain/kotlin/com/gcc/shared/model/ArDetectedMarker.kt shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt
git commit -m "Add AR-detected marker write endpoint (reuses WorldObservation, no new table)"
```

---

### Task 2: `ArMarkerQueue` — durable client-side queue

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArMarkerQueue.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ArMarkerQueueTest.kt`

**Interfaces:**
- Consumes: `ArDetectedMarkerRequest`, `ArDetectedMarkerBatchResponse` (Task 1).
- Produces: `class ArMarkerQueue(submitFn: suspend (List<ArDetectedMarkerRequest>) -> ArDetectedMarkerBatchResponse, settings: Settings = Settings()) { suspend fun enqueue(marker: ArDetectedMarkerRequest); suspend fun flush(): Boolean }` — consumed by Task 3.

This task is a close structural mirror of the existing `CoverageEventQueue`/`CoverageEventQueueTest` (`composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt` and its test) — same persistence mechanism, same single-flight/chunking/retry semantics, different types.

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ArMarkerQueueTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.ArMarkerQueueTest"`
Expected: FAIL — compile error, `ArMarkerQueue` unresolved.

- [ ] **Step 3: Implement `ArMarkerQueue`**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArMarkerQueue.kt` — this is `CoverageEventQueue.kt` with the types swapped and the settings key/batch-size constant renamed:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.ArMarkerQueueTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Run the full module test suite to confirm no regressions**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArMarkerQueue.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/ArMarkerQueueTest.kt
git commit -m "Add ArMarkerQueue: durable client-side queue for AR-detected markers"
```

---

### Task 3: iOS geo-math + listener wiring + ViewModel integration

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (expect class)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (actual)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ArRouteOverlayGeoMathTest.kt` (new — see note in Step 1)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt`

**Interfaces:**
- Consumes: `ArMarkerQueue`, `ArDetectedMarkerRequest` (Task 2), `apiClient.reportArDetectedMarkers` (Task 1).

- [ ] **Step 1: Extract and test the geo-math as a pure function**

`localToLatLng`'s math needs to be verifiable without ARKit/iOS — but it currently would live inside the iOS-only `ArRouteOverlay.kt` actual. Since `latLngToLocal` is already `private` inside that iOS-only file (not shared), and this is genuinely pure arithmetic with no ARKit dependency, extract BOTH functions into a small, separately-testable, commonMain pure-function file rather than leaving the new inverse function iOS-only and untestable:

Create `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArGeoMath.kt`:

```kotlin
package com.gcc.app.platform

import kotlin.math.cos

/** Meters per degree of latitude -- a fixed constant, accurate enough at the scale of a single AR session's local coordinate frame. */
const val METERS_PER_DEG_LAT = 111_320.0

/** cos(latDegrees), for scaling east-west (longitude) distances at a given latitude. */
fun lngScaleFor(latDegrees: Double): Double = cos(latDegrees * kotlin.math.PI / 180.0)

/** (lat, lng) -> AR-local (x, z) meters, relative to the given geo reference. */
fun latLngToLocal(
    lat: Double, lng: Double,
    refLat: Double, refLng: Double, refX: Float, refZ: Float, lngScale: Double,
): Pair<Double, Double> {
    val northMeters = (lat - refLat) * METERS_PER_DEG_LAT
    val eastMeters = (lng - refLng) * METERS_PER_DEG_LAT * lngScale
    return (refX + eastMeters) to (refZ - northMeters)
}

/** AR-local (x, z) meters -> (lat, lng), the exact inverse of [latLngToLocal], using the SAME geo reference the local coordinates were measured against. */
fun localToLatLng(
    x: Float, z: Float,
    refLat: Double, refLng: Double, refX: Float, refZ: Float, lngScale: Double,
): Pair<Double, Double> {
    val lat = refLat + (refZ - z) / METERS_PER_DEG_LAT
    val lng = refLng + (x - refX) / (METERS_PER_DEG_LAT * lngScale)
    return lat to lng
}
```

- [ ] **Step 2: Write the failing round-trip test**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ArGeoMathTest.kt`:

```kotlin
package com.gcc.app.platform

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ArGeoMathTest {

    @Test
    fun localToLatLngIsTheExactInverseOfLatLngToLocal() {
        val refLat = 41.87
        val refLng = -87.68
        val refX = 0f
        val refZ = 0f
        val lngScale = lngScaleFor(refLat)

        val originalLat = 41.8712
        val originalLng = -87.6791

        val (x, z) = latLngToLocal(originalLat, originalLng, refLat, refLng, refX, refZ, lngScale)
        val (roundTrippedLat, roundTrippedLng) = localToLatLng(x.toFloat(), z.toFloat(), refLat, refLng, refX, refZ, lngScale)

        assertTrue(abs(roundTrippedLat - originalLat) < 0.000001, "lat round-trip: expected $originalLat, got $roundTrippedLat")
        assertTrue(abs(roundTrippedLng - originalLng) < 0.000001, "lng round-trip: expected $originalLng, got $roundTrippedLng")
    }

    @Test
    fun localToLatLngAtTheReferencePointReturnsTheReferenceCoordinates() {
        val refLat = 41.87
        val refLng = -87.68
        val refX = 5f
        val refZ = -3f
        val lngScale = lngScaleFor(refLat)

        val (lat, lng) = localToLatLng(refX, refZ, refLat, refLng, refX, refZ, lngScale)

        assertTrue(abs(lat - refLat) < 0.000001)
        assertTrue(abs(lng - refLng) < 0.000001)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.ArGeoMathTest"`
Expected: FAIL — compile error, `ArGeoMath.kt` (containing `latLngToLocal`/`localToLatLng`/`lngScaleFor`) doesn't exist yet.

- [ ] **Step 4: Create `ArGeoMath.kt` and run tests to verify they pass**

(Content is the file from Step 1.)

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.ArGeoMathTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Read the real current iOS `ArRouteOverlay.kt` before editing**

Read `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` in full to confirm the exact current `refLat/refLng/refX/refY/refZ/lngScale` field declarations, `updateGeoReference`, and the existing private `latLngToLocal` method before applying the following changes.

- [ ] **Step 6: Replace the iOS file's private geo-math with calls to the new shared functions**

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`:
- Replace the body of `updateGeoReference`'s `lngScale = cos(lat * PI / 180.0)` line with `lngScale = lngScaleFor(lat)` (using the new shared function — remove the now-unused `cos`/`PI` imports if nothing else in the file uses them; check first).
- Replace the private `latLngToLocal(lat, lng)` method's body with a call to the new shared function: `return latLngToLocal(lat, lng, refLat, refLng, refX, refZ, lngScale)`. This is not a recursive self-call — the shared top-level function takes 7 parameters while the private member method takes 2, so Kotlin resolves the 7-argument call to the top-level function unambiguously.
- Add a new private method `localToLatLng(x: Float, z: Float): Pair<Double, Double> = localToLatLng(x, z, refLat, refLng, refX, refZ, lngScale)` (calling the new shared free function with this instance's current reference fields).

- [ ] **Step 7: Add `markerPlacedListener` to `ArFrameFeedback`**

Read `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt` in full first to confirm the exact current `addMarkerAt` signature and `analyzeTick`'s promotion block before editing.

Add a new field alongside `sceneVoteListener`:

```kotlin
    var markerPlacedListener: ((x: Float, y: Float, z: Float, label: String) -> Unit)? = null
```

Change `addMarkerAt`'s signature to accept a label:

```kotlin
    fun addMarkerAt(x: Float, y: Float, z: Float, kind: WorldMarkerKind, label: String = "") {
        val exists = markerPositions.any { (mx, _, mz) ->
            val dx = (mx - x).toDouble()
            val dz = (mz - z).toDouble()
            sqrt(dx * dx + dz * dz) < MARKER_DEDUPE_METERS
        }
        if (exists) return
        markerPositions.add(Triple(x, y, z))
        DiagnosticsLog.info("ar-marker", "placed $kind marker #${markerPositions.size}")

        if (kind == WorldMarkerKind.CONFIRMED_TRASH) {
            val listener = markerPlacedListener
            if (listener != null) {
                dispatch_async(dispatch_get_main_queue()) { listener(x, y, z, label) }
            }
        }

        dispatch_async(dispatch_get_main_queue()) {
            // ... existing SceneKit node-building code, unchanged ...
        }
    }
```

At the call site inside `analyzeTick()` where `addMarkerAt` is invoked for a promoted `CONFIRMED_TRASH` marker, pass `trashLabel` (already in scope at that point) as the new `label` argument:

```kotlin
            addMarkerAt(floats[0], floats[1], floats[2], WorldMarkerKind.CONFIRMED_TRASH, trashLabel)
```

Any OTHER call site of `addMarkerAt` (the `EXPECTED_TRASH` path, reached via `ArRouteOverlay.addWorldMarker`) does not need to pass a label — it will use the new parameter's `""` default, which is fine since that path never triggers `markerPlacedListener` (gated by `kind == WorldMarkerKind.CONFIRMED_TRASH` above).

- [ ] **Step 8: Add `setMarkerPlacedListener` to the `expect`/`actual` `ArRouteOverlay`**

In `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`, add to the `expect class ArRouteOverlay()` block, alongside `setSceneVoteListener`:

```kotlin
    /** Fires when a live-detected (CONFIRMED_TRASH) marker is placed, with its
     *  real lat/lng (converted from AR-local coordinates) and item label.
     *  iOS only; never invoked elsewhere. Listener is called on the main thread. */
    fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String) -> Unit)?)
```

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`, add the `actual` implementation near `setSceneVoteListener`'s actual:

```kotlin
    actual fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String) -> Unit)?) {
        frameFeedback.markerPlacedListener = if (listener == null) null else { x, _, z, label ->
            val (lat, lng) = localToLatLng(x, z)
            listener(lat, lng, label)
        }
    }
```

You will also need a Desktop/Android no-op `actual` for this new expect member — check `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` and `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (both currently empty-stub `actual class`es per prior investigation this session) and add:

```kotlin
    actual fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String) -> Unit)?) {}
```

to each.

- [ ] **Step 9: Wire the listener in `ArRouteScreen.kt`**

Read `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt` in full to confirm the exact current `setSceneVoteListener` call site and its teardown before editing.

Add, next to the existing `overlay.setSceneVoteListener { vote -> viewModel.onSceneVote(vote) }` call:

```kotlin
        overlay.setMarkerPlacedListener { lat, lng, label -> viewModel.onMarkerPlaced(lat, lng, label) }
```

And next to the existing teardown `overlay.setSceneVoteListener(null)`:

```kotlin
        overlay.setMarkerPlacedListener(null)
```

- [ ] **Step 10: Wire `RouteScanViewModel`**

Read `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt` in full around its `coverageEventQueue` construction, the `startLocationTracking()` flush-timer job, `completeScan()`, and `onCleared()` before editing — confirm the exact current line numbers/context match what's described below (this is a large, frequently-changed file).

Add, alongside the existing `coverageEventQueue` construction:

```kotlin
    private val arMarkerQueue = ArMarkerQueue(
        submitFn = { markers ->
            try {
                apiClient.reportArDetectedMarkers(markers)
            } catch (e: Exception) {
                DiagnosticsLog.error("ar-marker-flush", "flush of ${markers.size} markers failed (kept queued)", e)
                throw e
            }
        },
    )

    fun onMarkerPlaced(lat: Double, lng: Double, label: String) {
        scope.launch {
            arMarkerQueue.enqueue(
                ArDetectedMarkerRequest(
                    lat = lat, lng = lng, label = label,
                    capturedAt = Clock.System.now(),
                ),
            )
        }
    }
```

Add `arMarkerQueue.flush()` alongside every existing `coverageEventQueue.flush()` call (the 30-second periodic timer job in `startLocationTracking()`, `completeScan()`, and `onCleared()`) — e.g. the periodic job becomes:

```kotlin
        coverageFlushJob = scope.launch {
            while (true) {
                delay(30_000L)
                coverageEventQueue.flush()
                arMarkerQueue.flush()
            }
        }
```

Apply the equivalent addition at the other two call sites (`completeScan()`, `onCleared()`), matching each site's exact existing style (e.g. if it's `scope.launch { coverageEventQueue.flush() }`, change it to `scope.launch { coverageEventQueue.flush(); arMarkerQueue.flush() }`).

- [ ] **Step 11: Verify**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, including the new `ArGeoMathTest` (2 tests) and no regressions.

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :composeApp:compileDebugKotlinAndroid -x processDebugResources -x mergeDebugResources -x packageDebugResources -x processDebugManifest -x processDebugMainManifest`
Expected: BUILD SUCCESSFUL (confirms the new `expect`/Android `actual` member and `ArGeoMath.kt` compile on Android too).

iOS-specific edits (`ArFrameFeedback.kt`, iOS `ArRouteOverlay.kt`) cannot be compiled on this host — verify by careful reading only.

- [ ] **Step 12: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArGeoMath.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/ArGeoMathTest.kt composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt
git commit -m "Wire AR-detected marker persistence: geo-math, listener, queue flush"
```

---

## Self-Review Notes

- **Spec coverage**: §2 Server → Task 1. §2 Shared DTO/client → Task 1. §2 Client durable queue → Task 2. §2 iOS geo-math + event wiring → Task 3 Steps 1-9. §2 Flush triggering (reuse existing 30s timer) → Task 3 Step 10. §3 test coverage (queue fully testable, geo-math round-trip testable, server route verified via tsc, iOS wiring unverifiable) → each task's own verification steps. §4 (out of scope) — no `EXPECTED_TRASH` persistence anywhere, no dedup-logic changes, no photo/confidence fields populated, Android's actual detection pipeline untouched (only stub `actual` members added for the new expect interface member).
- **Placeholder scan**: no TBD/TODO. Task 3 Steps 5, 7, 9, 10 each have an explicit "read the real file first, confirm it matches" instruction rather than presenting this plan's reconstruction as verified-exact — all are bounded, single-file verifications given this plan touches several large, frequently-changed files, not open-ended design work.
- **Type consistency**: `ArDetectedMarkerRequest(lat, lng, label, capturedAt)` (Task 1) is constructed identically in Task 2's test and Task 3's `RouteScanViewModel.onMarkerPlaced`. `ArMarkerQueue(submitFn, settings)` / `enqueue()` / `flush(): Boolean` (Task 2) match exactly how Task 3 constructs and calls it. `latLngToLocal`/`localToLatLng`/`lngScaleFor` (Task 3 Step 1) have identical signatures between their definition and every call site in Steps 2, 6, and 8.
- **Caught during self-review (second pass)**: Task 3 Step 6's original wording around the `latLngToLocal` naming ("name the private method something distinct if the compiler complains") hedged on a non-issue — Kotlin resolves the 2-arg member call vs. the 7-arg top-level call unambiguously by arity, no renaming is ever needed. Simplified to state this directly.
- **Caught during self-review**: an earlier version of Task 3 planned to keep `localToLatLng` as a private method inside the iOS-only `ArRouteOverlay.kt` actual, which would have made it completely untestable (matching this plan's own stated principle that iOS-only code can't be verified on this host) despite being pure, dependency-free arithmetic with no real reason to be iOS-only. Restructured into a small shared `ArGeoMath.kt` file with free functions, genuinely tested via a round-trip test, with the iOS actual reduced to thin wrapper calls supplying its own instance state.
