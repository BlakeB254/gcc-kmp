# Phase 1 — Spot-Mode Guidance, Tracking Health & Event Emission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the genuinely new part of Phase 1's iOS AR scan-guidance overlay — spot-mode
circle-coverage guidance, AR tracking-health handling, and the client-side event-emission
path that feeds Phase 0's now-live coverage ledger — without duplicating the route-mode
arrow-completion work, which is already fully scoped as Task 17 of
`docs/superpowers/plans/2026-07-01-unified-scan-ecosystem.md` (unexecuted; see §Relationship
below).

**Architecture:** New `ObjectCoverageTracker` (commonMain, pure Kotlin, angular-coverage
analog to the existing `CoverageTracker`'s grid-coverage) drives a new `SpotArOverlay`
(expect/actual, following `ArRouteOverlay`'s exact existing SceneKit-cinterop shape rather
than a shared base class — see §Deviation below). A new `ArSessionHealth` expect/actual
wraps ARKit tracking-state for both overlays. Client-side, a new event-batching queue turns
`CoverageTracker`/`ObjectCoverageTracker` updates into `POST /api/scans/coverage` calls
against Phase 0's live endpoint, via two new `GccApiClient` methods.

**Tech Stack:** Kotlin Multiplatform (`composeApp`, `shared`), SceneKit/ARKit via
Kotlin/Native cinterop (iOS only), Ktor client (existing `GccApiClient`).

## Relationship to the existing, partially-executed 2026-07-01 plan

Read `docs/superpowers/plans/2026-07-01-unified-scan-ecosystem.md` Tasks 14-18 before
starting this plan — Tasks 14-16 are **already shipped** (confirmed: `ArRouteOverlay.kt`,
`CoverageTracker.cellCenterLatLng`, `ArRouteScreen.kt` all exist in the current tree,
matching that plan's specified code almost verbatim). **Task 17 (MacBook build + finishing
the route-mode directional-arrow coordinate math) was never executed** — the shipped
`ArRouteOverlay.kt`'s `updateHeading()` and the cell-add half of `updateFog()` are still
literally empty/`DEFERRED`, per their own code comments referencing "Task 17." **This plan
does not re-scope that work — when a MacBook session becomes available, resume the existing
plan's Task 17 directly, don't re-derive it.** This plan's own MacBook-dependent tasks (§4,
§5) should be done in the same MacBook session as that resumed Task 17, since they touch
overlapping files.

## Deviation from the design spec's "shared component" decision

`docs/superpowers/specs/2026-07-02-scan-guidance-world-model-and-rewards-design.md` §1.2
called for "One `ScanOverlay` composable takes a `ScanMode`... reuses the same arrow/text
rendering, animation, and AR session plumbing." Having now read the actual shipped
`ArRouteOverlay.kt`, it's a route-specific class with route-specific method names
(`updateFog`, `updateBoundaryStatus`, `updateHeading`), not a mode-parameterized component —
and it cannot be compiled or tested on this machine. Refactoring already-shipped,
unverifiable-here AR rendering code into a shared abstraction, without the ability to
confirm the refactor didn't break anything, is a real risk this plan chooses not to take.
**This plan builds `SpotArOverlay` as a structurally parallel class** (same method shape:
`updateCoverage`/`updateHeading`/`start`/`stop`) rather than forcing unification now. Once
both are verified working on a MacBook, unifying them behind one interface is a reasonable
fast-follow — flagged here, not solved here.

## Global Constraints

- **This Linux DGX machine cannot compile Kotlin/Native iOS targets at all** — confirmed
  directly this session (`./gradlew :composeApp:compileKotlinIosSimulatorArm64` reports
  `SKIPPED`, not executed). Tasks that create/modify `composeApp/src/iosMain/**` files are
  **authored here, verified only on a MacBook** — follow the exact honesty convention the
  existing `ArRouteOverlay.kt` file comments already use ("NOTE: This file cannot be
  compiled on the Linux DGX... compiled + visually iterated on the MacBook").
- **`compileCommonMainKotlinMetadata` and `compileKotlinDesktop` DO work on this machine**
  (confirmed: real `BUILD SUCCESSFUL` this session, not a skip) — this is the verification
  command for any `commonMain`-only change, per the existing plan's own established
  convention (`../gradlew :shared:jvmMainClasses :composeApp:compileKotlinDesktop`).
- **Android target does not build here** (no `ANDROID_HOME` configured) — Android `actual`
  stubs in this plan are simple, mechanical, and follow the existing `ArScanner.kt`/
  `ArRouteOverlay.kt` Android-stub pattern exactly; they cannot be compile-verified on this
  machine either, but their triviality (empty no-op bodies) makes that an acceptable,
  low-risk gap — same risk profile the existing shipped Android stubs already carry.
  Consuming code in `shared` and `composeApp` commonMain/desktop targets IS verified,
  which catches any signature mismatch even though the Android body itself isn't compiled.
- **`shared`'s `commonTest` source set is real and runs on this machine** (confirmed:
  `./gradlew :shared:jvmTest` — `BUILD SUCCESSFUL`, real tests exist at
  `shared/src/commonTest/kotlin/com/gcc/shared/geo/{PolygonContainmentTest,
  GeofenceVerificationTest}.kt`). New `shared` logic in this plan gets real unit tests
  there, following those files' existing conventions.
- **`composeApp` has no `commonTest` source set yet** — this plan creates one
  (`composeApp/src/commonTest/kotlin/...`), verified via `./gradlew :composeApp:desktopTest`
  (the `desktopTest` Gradle task already exists, generated by the KMP plugin, currently a
  no-op only because no test files exist yet).
- Follow `CoverageTracker.kt`'s existing conventions exactly for `ObjectCoverageTracker`
  (`StateFlow` exposure, `companion object` constants, KDoc block above the class).
  Follow `GccApiClient.kt`'s existing conventions exactly for new methods (`suspend fun
  name(...): ResponseType = postJson("/path", req)` / `get("/path")`).

---

### Task 1: `ObjectCoverageTracker` — angular coverage tracker for spot-mode guidance

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ObjectCoverageTracker.kt`
- Create: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ObjectCoverageTrackerTest.kt`

**Interfaces:**
- Produces: `class ObjectCoverageTracker` with `fun initAroundPoint(lat: Double, lng: Double)`,
  `fun updateCapturedAngle(userLat: Double, userLng: Double, distanceMeters: Double)`,
  `val coveredSectors: StateFlow<Set<Int>>`, `val coveragePercent: StateFlow<Double>`,
  `fun getUncoveredSectorCenters(): List<Double>` (bearings in degrees) — consumed by
  Task 5's `SpotArOverlay` for arrow guidance, mirroring how `CoverageTracker.
  getUnscannedCenters()` feeds `ArRouteOverlay` today.

- [ ] **Step 1: Read `CoverageTracker.kt` in full for the exact style to mirror**

```bash
cat composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt
```

Note its `StateFlow`/`MutableStateFlow` exposure pattern, `companion object` for tunable
constants, and its `bearingTo`/`normalizeDegrees` reuse from `AiDirectionEngine` (don't
reimplement bearing math — import and reuse `AiDirectionEngine.bearingTo` and
`AiDirectionEngine.normalizeDegrees`, both already `companion object` functions on that
class per the earlier grounding in this session).

- [ ] **Step 2: Write the failing test — sector coverage accumulates correctly**

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectCoverageTrackerTest {

    @Test
    fun capturingFromOneAngleCoversOnlyThatSector() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)

        // User stands due north of the item, close enough to count as "captured"
        tracker.updateCapturedAngle(userLat = 41.8627, userLng = -87.6851, distanceMeters = 2.0)

        val covered = tracker.coveredSectors.value
        assertTrue(covered.isNotEmpty(), "expected at least one sector covered")
        assertTrue(covered.size < ObjectCoverageTracker.SECTOR_COUNT, "expected NOT full coverage from a single angle")
    }

    @Test
    fun capturingAllSectorsReaches100Percent() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)

        // Walk a full circle around the item at a fixed radius, sampling every 10 degrees —
        // finer than SECTOR_COUNT's angular width, so every sector gets hit.
        val radiusDeg = 2.0 / 111_000.0 // ~2m in degrees latitude
        for (bearing in 0 until 360 step 10) {
            val rad = bearing * kotlin.math.PI / 180.0
            val lat = 41.8617 + radiusDeg * kotlin.math.cos(rad)
            val lng = -87.6851 + radiusDeg * kotlin.math.sin(rad)
            tracker.updateCapturedAngle(userLat = lat, userLng = lng, distanceMeters = 2.0)
        }

        assertEquals(100.0, tracker.coveragePercent.value, absoluteTolerance = 0.01)
    }

    @Test
    fun tooFarAwayDoesNotCountAsCaptured() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)

        // 50m away — well outside any reasonable spot-capture radius
        tracker.updateCapturedAngle(userLat = 41.8662, userLng = -87.6851, distanceMeters = 50.0)

        assertTrue(tracker.coveredSectors.value.isEmpty(), "expected no coverage from too far away")
    }

    @Test
    fun uncoveredSectorCentersReturnsBearingsNotYetCaptured() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)
        tracker.updateCapturedAngle(userLat = 41.8627, userLng = -87.6851, distanceMeters = 2.0)

        val uncovered = tracker.getUncoveredSectorCenters()
        assertTrue(uncovered.isNotEmpty())
        assertTrue(uncovered.all { it in 0.0..360.0 })
    }
}
```

(`assertEquals(expected, actual, absoluteTolerance)` is `kotlin.test`'s double-comparison
overload — if this project's `kotlin.test` version doesn't expose it, use
`assertTrue(kotlin.math.abs(100.0 - tracker.coveragePercent.value) < 0.01)` instead; check
which compiles during Step 4.)

- [ ] **Step 2b: Run it to confirm it fails to compile (the class doesn't exist yet)**

```bash
./gradlew :composeApp:desktopTest
```
Expected: FAILS — `ObjectCoverageTracker` unresolved reference.

- [ ] **Step 3: Implement `ObjectCoverageTracker`**

```kotlin
package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Angular-coverage tracker for spot-mode guidance: which directions around a
 * single fixed point (a litter item) have been captured from, at what
 * distance. The spot-mode analog of CoverageTracker's grid-cell coverage —
 * same "uncovered centers" shape, so SpotArOverlay's arrow-guidance logic can
 * mirror ArRouteOverlay's exactly (see AiDirectionEngine.bearingTo usage).
 */
class ObjectCoverageTracker {

    companion object {
        /** Number of angular sectors the full circle around the item is divided into. */
        const val SECTOR_COUNT = 12 // 30-degree sectors
        /** Maximum distance from the item for a capture to count toward that sector. */
        const val CAPTURE_RADIUS_METERS = 5.0
    }

    private var itemLat = 0.0
    private var itemLng = 0.0

    private val _coveredSectors = MutableStateFlow<Set<Int>>(emptySet())
    val coveredSectors: StateFlow<Set<Int>> = _coveredSectors.asStateFlow()

    private val _coveragePercent = MutableStateFlow(0.0)
    val coveragePercent: StateFlow<Double> = _coveragePercent.asStateFlow()

    fun initAroundPoint(lat: Double, lng: Double) {
        itemLat = lat
        itemLng = lng
        _coveredSectors.value = emptySet()
        _coveragePercent.value = 0.0
    }

    /**
     * Called as the user's position updates during spot capture. If within
     * CAPTURE_RADIUS_METERS of the item, marks the angular sector the user is
     * currently standing in (relative to the item) as covered.
     */
    fun updateCapturedAngle(userLat: Double, userLng: Double, distanceMeters: Double) {
        if (distanceMeters > CAPTURE_RADIUS_METERS) return

        val bearing = AiDirectionEngine.bearingTo(itemLat, itemLng, userLat, userLng)
        val sector = (bearing / (360.0 / SECTOR_COUNT)).toInt().coerceIn(0, SECTOR_COUNT - 1)

        val updated = _coveredSectors.value + sector
        _coveredSectors.value = updated
        _coveragePercent.value = (updated.size.toDouble() / SECTOR_COUNT * 100.0).coerceIn(0.0, 100.0)
    }

    /** Bearings (degrees, 0=north) of sector centers not yet captured. */
    fun getUncoveredSectorCenters(): List<Double> {
        val sectorWidth = 360.0 / SECTOR_COUNT
        return (0 until SECTOR_COUNT)
            .filter { it !in _coveredSectors.value }
            .map { it * sectorWidth + sectorWidth / 2.0 }
    }

    fun reset() {
        _coveredSectors.value = emptySet()
        _coveragePercent.value = 0.0
    }
}
```

If `GeofenceVerification` (imported above, matching `CoverageTracker.kt`'s import) isn't
actually needed once written (this implementation computes distance via the caller-supplied
`distanceMeters` rather than recomputing it), remove the unused import — don't leave dead
imports (this session already hit that exact defect once in the Phase 0 work; don't repeat
it).

- [ ] **Step 4: Run tests, iterate until green**

```bash
./gradlew :composeApp:desktopTest
```
Expected: all 4 tests pass. If `assertEquals` with `absoluteTolerance` doesn't compile,
switch to the `assertTrue(abs(...) < 0.01)` form noted in Step 2, and re-run.

- [ ] **Step 5: Verify the module builds clean**

```bash
./gradlew :composeApp:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL, no warnings about unused imports.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ObjectCoverageTracker.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/ObjectCoverageTrackerTest.kt
git commit -m "feat(ar): add ObjectCoverageTracker for spot-mode angular coverage guidance"
```

---

### Task 2: `GccApiClient` coverage-event methods + DTOs

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCoverage.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt` (append after
  the existing "Scout & Clean Route Scan API" section, currently ending at the
  `completeScan` method)
- Create: `shared/src/commonTest/kotlin/com/gcc/shared/model/ScanCoverageTest.kt`

**Interfaces:**
- Consumes: Phase 0's live `POST /api/scans/coverage` and `GET /api/map/coverage`
  endpoints (already deployed — request/response shapes are exact, copy verbatim, not
  from memory: request `{ events: [{ deviceId, platform, workerId?, lat, lng,
  distanceMeters, scanMode, deviceTier, capturedAt }] }` capped at 500 events, response
  `{ inserted, success, error? }`; GET response `{ cells: [{ locationCellId, lat, lng,
  state, lastScannedAt, totalDistanceMeters }], success }`).
- Produces: `submitCoverageEvents(events: List<ScanCoverageEventRequest>):
  ScanCoverageSubmitResponse` and `getMapCoverage(bbox: String): MapCoverageResponse` on
  `GccApiClient` — consumed by Task 3's event-batching queue.

- [ ] **Step 1: Read the existing DTO style to match exactly**

```bash
cat shared/src/commonMain/kotlin/com/gcc/shared/model/RouteScan.kt | head -40
```
Confirm the `@Serializable data class` + `kotlinx.serialization` import convention, and
whether request/response DTOs live in the same file as their domain model or separately
(match whichever convention `RouteScan.kt` establishes).

- [ ] **Step 2: Write the DTOs**

```kotlin
package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class ScanCoverageEventRequest(
    val deviceId: String,
    val platform: String, // "ios" | "android"
    val workerId: Int? = null,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double,
    val scanMode: String, // "route" | "spot"
    val deviceTier: String, // "basic" | "scanner" | "pro_scanner"
    val capturedAt: String,
)

@Serializable
data class ScanCoverageSubmitRequest(
    val events: List<ScanCoverageEventRequest>,
)

@Serializable
data class ScanCoverageSubmitResponse(
    val inserted: Int,
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class MapCoverageCell(
    val locationCellId: String,
    val lat: Double,
    val lng: Double,
    val state: String, // "fresh" | "stale"
    val lastScannedAt: String,
    val totalDistanceMeters: Double,
)

@Serializable
data class MapCoverageResponse(
    val cells: List<MapCoverageCell>,
    val success: Boolean,
)
```

- [ ] **Step 3: Write the failing test — request serialization matches the live endpoint's expected shape**

```kotlin
package com.gcc.shared.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanCoverageTest {

    @Test
    fun submitRequestSerializesWithAllRequiredFields() {
        val request = ScanCoverageSubmitRequest(
            events = listOf(
                ScanCoverageEventRequest(
                    deviceId = "test-device",
                    platform = "ios",
                    lat = 41.87,
                    lng = -87.68,
                    distanceMeters = 5.0,
                    scanMode = "route",
                    deviceTier = "pro_scanner",
                    capturedAt = "2026-07-02T12:00:00Z",
                )
            )
        )
        val json = Json.encodeToString(ScanCoverageSubmitRequest.serializer(), request)
        assertTrue(json.contains("\"deviceId\":\"test-device\""))
        assertTrue(json.contains("\"platform\":\"ios\""))
        assertTrue(json.contains("\"scanMode\":\"route\""))
        // workerId omitted (null) should not appear as a required field failure —
        // just confirm the whole thing round-trips.
        val decoded = Json.decodeFromString(ScanCoverageSubmitRequest.serializer(), json)
        assertEquals(request, decoded)
    }

    @Test
    fun submitResponseDecodesFromLiveEndpointShape() {
        // Exact shape confirmed against the live POST /api/scans/coverage endpoint
        // this session (site/src/app/api/scans/coverage/route.ts) — not guessed.
        val raw = """{"inserted":1,"success":true}"""
        val decoded = Json.decodeFromString(ScanCoverageSubmitResponse.serializer(), raw)
        assertEquals(1, decoded.inserted)
        assertEquals(true, decoded.success)
    }

    @Test
    fun mapCoverageResponseDecodesFromLiveEndpointShape() {
        // Exact shape confirmed against the live GET /api/map/coverage endpoint
        // this session (site/src/app/api/map/coverage/route.ts) — not guessed.
        val raw = """{"cells":[{"locationCellId":"464756:-721607","lat":41.87,"lng":-87.68,"state":"fresh","lastScannedAt":"2026-07-02T12:00:00Z","totalDistanceMeters":5.0}],"success":true}"""
        val decoded = Json.decodeFromString(MapCoverageResponse.serializer(), raw)
        assertEquals(1, decoded.cells.size)
        assertEquals("fresh", decoded.cells[0].state)
    }
}
```

- [ ] **Step 4: Run to confirm it fails, then implement, then run to confirm it passes**

```bash
./gradlew :shared:jvmTest
```
First run: FAILS (classes don't exist). After writing Step 2's DTOs, and only that, re-run
until these 3 tests pass — you'll need `import kotlinx.serialization.json.Json` and
possibly a `kotlinx-serialization-json` dependency check (look for how `RouteScan.kt` or
its test neighbors already do this if the import fails to resolve; the dependency is very
likely already present given `@Serializable` is already used throughout this module).

- [ ] **Step 5: Add the two `GccApiClient` methods**

Append immediately after the existing `completeScan` method (end of the "Scout & Clean
Route Scan API" section):

```kotlin
    // ── Scan Coverage Ledger API ───────────────────────────────────

    suspend fun submitCoverageEvents(events: List<ScanCoverageEventRequest>): ScanCoverageSubmitResponse =
        postJson("/api/scans/coverage", ScanCoverageSubmitRequest(events))

    suspend fun getMapCoverage(bbox: String): MapCoverageResponse =
        get("/api/map/coverage?bbox=$bbox")
```

Add the corresponding import (`import com.gcc.shared.model.ScanCoverageEventRequest`,
etc. — check the top of the file for how other `shared.model` types are already imported
and match that exactly, whether individual imports or a wildcard).

- [ ] **Step 6: Verify the whole module builds**

```bash
./gradlew :shared:jvmMainClasses :composeApp:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL — this confirms `GccApiClient`'s new methods compile and that
nothing downstream in `composeApp` (which depends on `shared`) broke.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCoverage.kt shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt shared/src/commonTest/kotlin/com/gcc/shared/model/ScanCoverageTest.kt
git commit -m "feat(api-client): add submitCoverageEvents/getMapCoverage to GccApiClient"
```

---

### Task 3: Client-side coverage-event batching queue

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt`
- Create: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageEventQueueTest.kt`

**Interfaces:**
- Consumes: `GccApiClient.submitCoverageEvents` (Task 2).
- Produces: `class CoverageEventQueue(private val apiClient: GccApiClient)` with
  `fun enqueue(event: ScanCoverageEventRequest)` and `suspend fun flush(): Boolean` —
  consumed by the (MacBook-dependent) screen-level wiring in Task 4/5, not built in this
  task.

This is the concrete implementation of design spec §1.6 ("Both overlays batch and flush
`scanCoverageEvent` records... If the coverage-write endpoint is unreachable, events queue
locally and retry").

- [ ] **Step 1: Write the failing test — enqueue then flush submits all queued events**

```kotlin
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
```

- [ ] **Step 2: Run to confirm it fails to compile**

```bash
./gradlew :composeApp:desktopTest
```

- [ ] **Step 3: Implement `CoverageEventQueue`**

Note the constructor takes a `submitFn` lambda rather than `GccApiClient` directly — this
keeps the class trivially testable without a real network client, following the same
dependency-injection-via-lambda shape the test above assumes. A thin wrapper at the call
site (Task 4/5, MacBook-dependent) supplies `apiClient::submitCoverageEvents` as the real
`submitFn` in production.

```kotlin
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
```

- [ ] **Step 4: Run tests, iterate until green**

```bash
./gradlew :composeApp:desktopTest
```
Expected: all 3 new tests pass, plus the 4 from Task 1 still pass (7 total in this module).

- [ ] **Step 5: Verify the module builds clean**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageEventQueueTest.kt
git commit -m "feat(ar): add CoverageEventQueue for offline-tolerant coverage-event batching"
```

---

### Task 4: `ArSessionHealth` — tracking-loss detection (expect/actual)

> **iOS `actual` cannot be built or tested on this machine** (Global Constraints). Authored
> to the same cinterop conventions `ArRouteOverlay.kt`/`ArScanner.kt` already establish;
> compiled and visually verified on the MacBook alongside Task 17's resumed work.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt` (expect)
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt` (actual stub)
- Create: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt` (actual stub)
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt` (actual, iOS — unverified here)

**Interfaces:**
- Produces: `expect class ArSessionHealth()` with `val trackingState: StateFlow<TrackingState>`
  and `fun bind(session: Any)` — consumed by both `ArRouteOverlay`'s MacBook-resumed Task 17
  work and this plan's Task 5 `SpotArOverlay`.

- [ ] **Step 1: Write the `expect` declaration + shared `TrackingState` enum**

```kotlin
package com.gcc.app.platform

import kotlinx.coroutines.flow.StateFlow

enum class TrackingState {
    NORMAL,
    LIMITED, // tracking degraded — fast motion, low light, insufficient features
    NOT_AVAILABLE, // no tracking at all
}

/**
 * Wraps the platform AR session's tracking-quality signal so both
 * ArRouteOverlay and SpotArOverlay can show a consistent "hold steady" /
 * "find more light" recovery prompt and suspend guidance updates during
 * degraded tracking, per design spec §1.4.
 */
expect class ArSessionHealth() {
    val trackingState: StateFlow<TrackingState>
}
```

- [ ] **Step 2: Write the Android stub**

```bash
cat composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArScanner.kt
```
Match its exact no-op/stub style.

```kotlin
package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class ArSessionHealth actual constructor() {
    actual val trackingState: StateFlow<TrackingState> = MutableStateFlow(TrackingState.NOT_AVAILABLE)
}
```

- [ ] **Step 3: Write the desktop stub, same shape**

```kotlin
package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class ArSessionHealth actual constructor() {
    actual val trackingState: StateFlow<TrackingState> = MutableStateFlow(TrackingState.NOT_AVAILABLE)
}
```

- [ ] **Step 4: Verify non-iOS targets build**

```bash
./gradlew :composeApp:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL. (Android stub can't be compile-verified here per Global
Constraints — its triviality makes this an accepted, low-risk gap, same as existing stubs.)

- [ ] **Step 5: Write the iOS `actual` — wraps `ARCamera.TrackingState`**

```bash
cat composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt
```
Match its exact `@OptIn(ExperimentalForeignApi::class)` + `platform.ARKit` import style.

```kotlin
package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.ARKit.ARCamera
import platform.ARKit.ARCameraTrackingStateLimited
import platform.ARKit.ARCameraTrackingStateNormal
import platform.ARKit.ARCameraTrackingStateNotAvailable
import platform.ARKit.ARSession
import platform.ARKit.ARSessionDelegateProtocol
import platform.darwin.NSObject

/**
 * NOTE: This file cannot be compiled on the Linux DGX (Kotlin/Native iOS
 * targets are unavailable here). Authored to the codebase's proven cinterop
 * conventions (ArRouteOverlay.kt, ArScanner.kt) and compiled + verified on
 * the MacBook, in the same session as ArRouteOverlay's resumed Task 17 work
 * (docs/superpowers/plans/2026-07-01-unified-scan-ecosystem.md), since
 * SpotArOverlay (Task 5 of this plan) binds an ArSessionHealth instance to
 * the same ARSession it drives.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ArSessionHealth actual constructor() {

    private val _trackingState = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    actual val trackingState: StateFlow<TrackingState> = _trackingState

    private val delegate = object : NSObject(), ARSessionDelegateProtocol {
        override fun session(session: ARSession, cameraDidChangeTrackingState: ARCamera) {
            _trackingState.value = when (cameraDidChangeTrackingState.trackingState) {
                ARCameraTrackingStateNormal -> TrackingState.NORMAL
                ARCameraTrackingStateLimited -> TrackingState.LIMITED
                ARCameraTrackingStateNotAvailable -> TrackingState.NOT_AVAILABLE
                else -> TrackingState.NOT_AVAILABLE
            }
        }
    }

    /** Call once the ARSession exists (e.g. from ArRouteOverlay.bind or SpotArOverlay.bind). */
    fun bind(session: ARSession) {
        session.delegate = delegate
    }
}
```

**DEFERRED to the MacBook session:** confirm the exact ARKit delegate method signature —
`ARSessionDelegateProtocol`'s Kotlin/Native-bridged method name and parameter order for
`session(_:cameraDidChangeTrackingState:)` may not match this file's guess exactly (Swift
delegate methods often bridge into Kotlin with different parameter-label handling than
written here); Xcode's autocomplete/compiler errors will show the exact required override
signature immediately, and this is a mechanical fix once a compiler is available, not a
design question.

- [ ] **Step 6: Commit non-iOS pieces now; commit the iOS actual alongside them with a clear message**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArSessionHealth.kt
git commit -m "feat(ar): scaffold ArSessionHealth (tracking-state expect/actual), iOS delegate signature pending MacBook compiler verification"
```

---

### Task 5: `SpotArOverlay` — spot-mode circle-guidance overlay (expect/actual)

> **iOS `actual` cannot be built or tested on this machine.** Same status as Task 4.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt` (expect)
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt` (actual stub)
- Create: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt` (actual stub)
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt` (actual, iOS — unverified here)

**Interfaces:**
- Consumes: `ObjectCoverageTracker` (Task 1), `ArSessionHealth` (Task 4).
- Produces: `expect class SpotArOverlay()` with `fun updateCoverage(coveredSectors: Set<Int>,
  uncoveredBearings: List<Double>)`, `fun updateHeading(degrees: Float)`, `fun start()`,
  `fun stop()`, plus `expect fun SpotArOverlayView(overlay: SpotArOverlay, modifier: Modifier)`
  — the screen-level wiring (a new `SpotArScreen.kt`, analogous to the existing
  `ArRouteScreen.kt`) is explicitly **out of scope for this task** (MacBook-dependent
  Compose/UIKitView wiring, do alongside Task 4/17 MacBook work, not here).

- [ ] **Step 1: Write the `expect` declaration**

```kotlin
package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Full-screen AR overlay for spot-scan mode: directional guidance circling a
 * single litter item until all angular sectors are captured (ObjectCoverageTracker),
 * plus the same tracking-health recovery prompt ArRouteOverlay uses (ArSessionHealth).
 *
 * Structurally parallel to ArRouteOverlay rather than a shared base class — see
 * this plan's "Deviation" note for why. iOS: SceneKit. Android/Desktop: no-op stub.
 */
expect class SpotArOverlay() {
    fun updateCoverage(coveredSectors: Set<Int>, uncoveredBearings: List<Double>)
    fun updateHeading(degrees: Float)
    fun start()
    fun stop()
}

@Composable
expect fun SpotArOverlayView(overlay: SpotArOverlay, modifier: Modifier)
```

- [ ] **Step 2: Write the Android stub**

```kotlin
package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual class SpotArOverlay actual constructor() {
    actual fun updateCoverage(coveredSectors: Set<Int>, uncoveredBearings: List<Double>) {}
    actual fun updateHeading(degrees: Float) {}
    actual fun start() {}
    actual fun stop() {}
}

@Composable
actual fun SpotArOverlayView(overlay: SpotArOverlay, modifier: Modifier) {
    Box(modifier = modifier)
}
```

(Confirm `Box(modifier)` matches how `ArRouteOverlayView`'s Android actual stubs an empty
composable — check `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`
for the exact existing pattern before writing this, per this plan's Global Constraints on
matching established conventions.)

- [ ] **Step 3: Write the desktop stub, same shape as Step 2**

- [ ] **Step 4: Verify non-iOS targets build**

```bash
./gradlew :composeApp:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Write the iOS `actual`**

```bash
cat composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt
```
Mirror its structure exactly (SceneKit `ARSCNView`, `bind()` pattern, `UIKitView` factory),
substituting sector/bearing-based node placement for grid-cell-based:

```kotlin
package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldTrackingConfiguration
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNMaterial
import platform.UIKit.UIColor

/**
 * NOTE: This file cannot be compiled on the Linux DGX (Kotlin/Native iOS
 * targets are unavailable here). Authored to the same SceneKit/ARKit cinterop
 * conventions as ArRouteOverlay.kt. Compiled + visually iterated on the
 * MacBook, in the same session as ArRouteOverlay's resumed Task 17 arrow work
 * — the directional-arrow rendering approach worked out there for route mode
 * should be reused here for the sector-bearing arrow, not redesigned twice.
 */
@OptIn(ExperimentalForeignApi::class)
actual class SpotArOverlay actual constructor() {

    private var sceneView: ARSCNView? = null
    private val sectorNodes = mutableMapOf<Int, SCNNode>()
    private val sessionHealth = ArSessionHealth()

    fun bind(view: ARSCNView) {
        sceneView = view
        val config = ARWorldTrackingConfiguration()
        view.session.runWithConfiguration(config)
        sessionHealth.bind(view.session)
    }

    actual fun start() {
        // Session start is driven by bind(), called from the Compose UIKitView
        // factory below — nothing to do here beyond what bind() already does.
    }

    actual fun stop() {
        sceneView?.session?.pause()
    }

    /**
     * One SCNNode per uncovered angular sector, positioned around the item at
     * CAPTURE_RADIUS_METERS. Covered sectors have their node removed.
     * DEFERRED to MacBook iteration: the lat/lng-independent, purely
     * angular/local-space placement here is actually simpler than
     * ArRouteOverlay's fog-of-war (no lat/lng -> AR-space conversion needed,
     * since sectors are relative to wherever the AR session's local origin
     * is when spot-capture starts) — but still needs a live ARSession to
     * verify node placement looks correct from the user's actual walking
     * position, so it's deferred alongside ArRouteOverlay's Task 17 for the
     * same reason: guessing world-space geometry without a live session to
     * look at is how bugs get shipped, not a shortcut worth taking.
     */
    actual fun updateCoverage(coveredSectors: Set<Int>, uncoveredBearings: List<Double>) {
        sceneView?.scene ?: return
        for ((sector, node) in sectorNodes.toMap()) {
            if (sector !in (0 until 12)) continue // stale index guard
        }
        // Node creation/placement for uncoveredBearings: DEFERRED to MacBook (see above).
    }

    actual fun updateHeading(degrees: Float) {
        // DEFERRED to MacBook: directional-arrow rendering, reusing whatever
        // approach ArRouteOverlay's Task 17 lands on for its own arrow.
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SpotArOverlayView(overlay: SpotArOverlay, modifier: Modifier) {
    UIKitView(
        factory = {
            val view = ARSCNView()
            overlay.bind(view)
            view
        },
        modifier = modifier,
        onRelease = {
            overlay.stop()
        },
    )
}
```

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt composeApp/src/desktopMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt
git commit -m "feat(ar): scaffold SpotArOverlay (spot-mode circle guidance), iOS node placement + arrow rendering pending MacBook iteration"
```

---

## Task 6: MacBook session — not executable here, tracked for the next on-device session

Not a task with steps to run now — a pointer for whoever next has MacBook access, so this
work doesn't get re-derived from scratch:

1. Resume `docs/superpowers/plans/2026-07-01-unified-scan-ecosystem.md` **Task 17** first
   (route-mode arrow + fog-of-war coordinate math) — it was scoped before this plan and
   nothing here changes it.
2. In the same session, verify this plan's Tasks 4-5 iOS `actual` files actually compile —
   fix the `ARSessionDelegateProtocol` method signature (Task 4 Step 5's noted uncertainty)
   and `SpotArOverlay`'s deferred node-placement/arrow code (Task 5 Step 5), reusing
   whatever coordinate-math and arrow-rendering approach Task 17 lands on for route mode,
   rather than solving the same problem twice.
3. Build a `SpotArScreen.kt` (commonMain, analogous to the existing `ArRouteScreen.kt`) and
   wire it into the spot-scan capture entry point (`SpotScanCapture.kt` /
   `ScoutCaptureDialog.kt`) — this screen-level wiring was explicitly out of scope for
   Task 5 above.
4. Wire `CoverageEventQueue` (Task 3) into both `ArRouteScreen`/`SpotArScreen`'s view
   models, flushing on a timer and on session end, using `apiClient::submitCoverageEvents`
   as the real `submitFn`.
5. `cdx-mobile build ios --sync` to a new TestFlight build once the above is stable.

## Self-Review Notes

- **Spec coverage:** implements design spec §1.2 (spot mode / `ObjectCoverageTracker`),
  §1.4 (`ArSessionHealth`), §1.6 (event emission via `CoverageEventQueue` +
  `GccApiClient`). Does not implement §1.1 (route-mode arrow — already scoped elsewhere,
  see Relationship section), §1.3 (device tiering wiring into these new overlays — the
  overlays are tier-agnostic classes; tiering which overlay/mode gets instantiated is a
  screen-level concern, deferred to Task 6 item 3), §1.5 (expected-litter markers /
  rectification — not started in this plan; needs its own task once Task 6's screen wiring
  exists to hang markers on).
- **Placeholder scan:** the iOS `actual` files (Tasks 4-5) contain intentional, clearly-
  labeled `DEFERRED` sections — these are not silent placeholders, they match the existing
  codebase's own established, successful convention for MacBook-dependent AR rendering
  work (`ArRouteOverlay.kt`'s real, shipped file has the identical pattern). Every other
  step in this plan has complete, runnable code.
- **Type consistency:** `ObjectCoverageTracker.getUncoveredSectorCenters()` returns
  `List<Double>` (bearings) consumed by `SpotArOverlay.updateCoverage`'s
  `uncoveredBearings: List<Double>` parameter — matches. `ScanCoverageEventRequest`
  (Task 2) is the exact type `CoverageEventQueue` (Task 3) and its test double both use —
  matches.
