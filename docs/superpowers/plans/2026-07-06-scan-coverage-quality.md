# Scan Coverage Quality (Grazed vs Close) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the blind 15m AR crediting frustum with raycast-measured two-tier coverage (grazed/close), complete the INDOOR-flip purge (cells + queued events + server retraction), persist quality end-to-end, and render two-shade minimap/map with two-phase "3D-scanner" guidance.

**Architecture:** `CoverageTracker` becomes a quality-aware map (`Map<cell, CellCredit>`); iOS AR overlays cast a per-tick raycast fan whose hits (with real surface distance) drive crediting; coverage events carry a `quality` field to a new summary column; a retract endpoint undoes indoor-window credits. Spec: `docs/superpowers/specs/2026-07-06-scan-coverage-quality-design.md`.

**Tech Stack:** Kotlin Multiplatform (composeApp/shared), ARKit via cinterop (iosMain), Next.js + Drizzle/Neon (site/), kotlinx-serialization wire models.

## Global Constraints

- `Coordinates(lng, lat)` is GeoJSON-ordered — positional args have caused repeat bugs; always use named args.
- `gridCell()` keys (site/src/lib/world-observations.ts, client `gridCell10m`) must remain byte-equal — R2 `raw/{cellId}/` paths depend on them. Do NOT touch that function or its constants.
- New Kotlin tests go in **composeApp** commonTest (run via desktopTest). shared/'s own commonTest is broken on all targets (JdbcSqliteDriver) — never add tests there.
- `SpotScanCapture`/`CameraCapture` wrapper composables MUST forward every new minimap param — silently-defaulted params have shipped invisible features before (PR #86 root cause).
- iosMain only compiles on this MacBook: after any iosMain/shared change run `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`.
- Fresh worktrees need `npm install` in `site/` before `npx tsc --noEmit`. Local tsc baseline has pre-existing @turf/@aws-sdk resolution errors from stale node_modules — those two module-resolution errors are NOT yours; anything else is.
- Schema changes deploy via `npm run db:push` at merge time (record as a MERGE ACTION, do not run against prod from the worktree).
- Constants (all in `CoverageTracker.companion`): `cellSizeMeters=3.0`, `scanRadiusMeters=5.0`, `corridorWidthMeters=15.0` (existing, unchanged) + new `CLOSE_RANGE_METERS=4.0`, `GRAZE_RANGE_METERS=10.0`.
- Point rates (site/src/lib/points-config.ts): NEW_AREA 10/m, NEW_AREA_GRAZED 4/m, CLOSE_UPGRADE 6/m, STALE_RESCAN 5/m, FRESH_RESCAN 0/m.
- Commit after every task; message prefix `feat(coverage-quality):` / `fix(coverage-quality):`.

---

### Task 1: Quality-aware CoverageTracker

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageTrackerQualityTest.kt` (create)

**Interfaces:**
- Consumes: existing `initFromRoute`, `cellForLatLng`, `cellCenterLatLng`, `GeofenceVerification.distanceMeters`.
- Produces (later tasks rely on these exact names):
  - `enum class CoverageQuality { GRAZED, CLOSE }` (GRAZED first — ordinal comparison means CLOSE > GRAZED)
  - `data class CellCredit(val quality: CoverageQuality, val creditedAtMs: Long, val prior: CellCredit? = null)`
  - `data class RaycastCoverageHit(val lat: Double, val lng: Double, val distanceMeters: Double)`
  - `val creditedCells: StateFlow<Map<Pair<Int, Int>, CellCredit>>`
  - `val closePercent: StateFlow<Double>`
  - `fun updateWithRaycastHits(hits: List<RaycastCoverageHit>, nowMs: Long = nowEpochMs())`
  - `fun updatePosition(lat: Double, lng: Double, nowMs: Long = nowEpochMs())` (existing signature + nowMs)
  - `fun purgeSince(cutoffMs: Long)`
  - `CoverageTracker.CLOSE_RANGE_METERS`, `CoverageTracker.GRAZE_RANGE_METERS`
  - `scannedCells`, `coveragePercent`, `getUnscannedCells()`, `getScannedCorridorCells()` keep their existing types/semantics ("credited at either tier").
  - `updateWithFrustum` is DELETED (call sites rewired in Tasks 5-6; this task keeps it compiling by leaving it in place until Task 6 removes it — do NOT delete it here).

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageTrackerQualityTest.kt`:

```kotlin
package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CoverageTrackerQualityTest {

    /** One-point "route" like the env scan uses — grid spans anchor ±(20m pad + corridor). */
    private fun tracker(anchorLat: Double = 41.8600, anchorLng: Double = -87.7200): CoverageTracker {
        val t = CoverageTracker()
        t.initFromRoute(listOf(Coordinates(lng = anchorLng, lat = anchorLat)))
        return t
    }

    @Test
    fun frozenRangeConstants() {
        // Tuning knobs, but changes must be DELIBERATE (they redefine what
        // "scanned" means on the public map) — snap the values.
        assertEquals(4.0, CoverageTracker.CLOSE_RANGE_METERS)
        assertEquals(10.0, CoverageTracker.GRAZE_RANGE_METERS)
    }

    @Test
    fun raycastHitCreditsTierByDistance() {
        val t = tracker()
        t.updateWithRaycastHits(
            listOf(
                RaycastCoverageHit(41.8600, -87.7200, distanceMeters = 2.0),   // CLOSE
                RaycastCoverageHit(41.86005, -87.7200, distanceMeters = 7.0),  // GRAZED
                RaycastCoverageHit(41.86010, -87.7200, distanceMeters = 12.0), // beyond range: no credit
            ),
            nowMs = 1_000L,
        )
        val credits = t.creditedCells.value
        val closeCell = t.cellForLatLng(41.8600, -87.7200)!!
        val grazedCell = t.cellForLatLng(41.86005, -87.7200)!!
        val farCell = t.cellForLatLng(41.86010, -87.7200)!!
        assertEquals(CoverageQuality.CLOSE, credits[closeCell]?.quality)
        assertEquals(CoverageQuality.GRAZED, credits[grazedCell]?.quality)
        assertNull(credits[farCell])
    }

    @Test
    fun upgradeIsMonotonic() {
        val t = tracker()
        val hitClose = RaycastCoverageHit(41.8600, -87.7200, 2.0)
        val hitGrazed = RaycastCoverageHit(41.8600, -87.7200, 8.0)
        t.updateWithRaycastHits(listOf(hitGrazed), nowMs = 1_000L)
        t.updateWithRaycastHits(listOf(hitClose), nowMs = 2_000L)
        // A later grazed hit must NOT downgrade (and must not touch the timestamp).
        t.updateWithRaycastHits(listOf(hitGrazed), nowMs = 3_000L)
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        val credit = t.creditedCells.value[cell]!!
        assertEquals(CoverageQuality.CLOSE, credit.quality)
        assertEquals(2_000L, credit.creditedAtMs)
    }

    @Test
    fun updatePositionCreditsGrazedOnly() {
        val t = tracker()
        t.updatePosition(41.8600, -87.7200, nowMs = 1_000L)
        val credits = t.creditedCells.value
        assertTrue(credits.isNotEmpty())
        assertTrue(credits.values.all { it.quality == CoverageQuality.GRAZED })
        // scannedCells (legacy set) mirrors the credited keys.
        assertEquals(credits.keys, t.scannedCells.value)
    }

    @Test
    fun purgeRemovesWindowCreditsAndRevertsUpgrades() {
        val t = tracker()
        // Cell A: grazed BEFORE the window, upgraded to close INSIDE it — purge reverts to grazed.
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 8.0)), nowMs = 1_000L)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 5_000L)
        // Cell B: first credited INSIDE the window — purge removes it entirely.
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.86005, -87.7200, 2.0)), nowMs = 6_000L)
        t.purgeSince(cutoffMs = 4_000L)
        val credits = t.creditedCells.value
        val cellA = t.cellForLatLng(41.8600, -87.7200)!!
        val cellB = t.cellForLatLng(41.86005, -87.7200)!!
        assertEquals(CoverageQuality.GRAZED, credits[cellA]?.quality)
        assertNull(credits[cellB])
        assertTrue(cellB !in t.scannedCells.value)
    }

    @Test
    fun closePercentTracksCloseTierOnly() {
        val t = tracker()
        assertEquals(0.0, t.closePercent.value)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 8.0)), nowMs = 1_000L)
        assertEquals(0.0, t.closePercent.value) // grazed counts toward coveragePercent only
        assertTrue(t.coveragePercent.value > 0.0)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 2_000L)
        assertTrue(t.closePercent.value > 0.0)
        assertNotNull(t.creditedCells.value)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.CoverageTrackerQualityTest"`
Expected: FAIL to compile — `CoverageQuality`, `RaycastCoverageHit`, `creditedCells`, etc. unresolved.

- [ ] **Step 3: Implement the quality model**

In `CoverageTracker.kt`, add above the class:

```kotlin
/** Scan quality tier for a credited cell. Order matters: CLOSE > GRAZED (ordinal upgrade check). */
enum class CoverageQuality { GRAZED, CLOSE }

/**
 * Per-cell credit: what tier, when, and (one level deep) what the cell held
 * before the latest change — so an INDOOR-flip purge can revert an in-window
 * upgrade instead of erasing pre-window history.
 */
data class CellCredit(
    val quality: CoverageQuality,
    val creditedAtMs: Long,
    val prior: CellCredit? = null,
)

/** One raycast fan hit, already geo-resolved: where the ray LANDED + how far the surface was. */
data class RaycastCoverageHit(val lat: Double, val lng: Double, val distanceMeters: Double)
```

In the companion object add:

```kotlin
        /** Raycast hits at or under this surface distance credit CLOSE (detail-scan tier). */
        const val CLOSE_RANGE_METERS = 4.0
        /** Raycast hits beyond this surface distance credit nothing (fan ceiling). */
        const val GRAZE_RANGE_METERS = 10.0
```

Add import `kotlinx.datetime.Clock` and a private helper:

```kotlin
    private fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
```

Replace the `_scannedCells` declaration block (lines 47-52) with:

```kotlin
    // Mutable state: quality-aware credit map is the source of truth; the
    // legacy scanned SET stays as a derived mirror for existing consumers
    // (fog, minimap punch-out, corridor fill).
    private val _creditedCells = MutableStateFlow<Map<Pair<Int, Int>, CellCredit>>(emptyMap())
    val creditedCells: StateFlow<Map<Pair<Int, Int>, CellCredit>> = _creditedCells.asStateFlow()

    private val _scannedCells = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    val scannedCells: StateFlow<Set<Pair<Int, Int>>> = _scannedCells.asStateFlow()

    private val _coveragePercent = MutableStateFlow(0.0)
    val coveragePercent: StateFlow<Double> = _coveragePercent.asStateFlow()

    private val _closePercent = MutableStateFlow(0.0)
    val closePercent: StateFlow<Double> = _closePercent.asStateFlow()
```

Add the shared publish/credit helpers (place after `reset()`):

```kotlin
    /** Recompute all derived flows from a new credit map. */
    private fun publish(map: Map<Pair<Int, Int>, CellCredit>) {
        _creditedCells.value = map
        _scannedCells.value = map.keys
        if (totalCells > 0) {
            val scannedCorridor = map.keys.count { it in corridorCells }
            _coveragePercent.value = (scannedCorridor.toDouble() / totalCells * 100.0).coerceIn(0.0, 100.0)
            val closeCorridor = map.count { (cell, credit) ->
                cell in corridorCells && credit.quality == CoverageQuality.CLOSE
            }
            _closePercent.value = (closeCorridor.toDouble() / totalCells * 100.0).coerceIn(0.0, 100.0)
        } else {
            _coveragePercent.value = 0.0
            _closePercent.value = 0.0
        }
    }

    /** Monotonic credit: upgrades keep a one-deep prior for purge revert; equal/lower tiers are no-ops. */
    private fun creditCell(
        map: MutableMap<Pair<Int, Int>, CellCredit>,
        cell: Pair<Int, Int>,
        quality: CoverageQuality,
        nowMs: Long,
    ): Boolean {
        val existing = map[cell]
        if (existing != null && existing.quality >= quality) return false
        map[cell] = CellCredit(quality, nowMs, prior = existing?.copy(prior = null))
        return true
    }

    /**
     * AR tier: credit the cells raycast fan rays actually LANDED in. Rays stop
     * at real surfaces, so a wall 2m away means nothing beyond it can credit —
     * the indoor 15m-cone overreach is impossible by construction (spec §1).
     */
    fun updateWithRaycastHits(hits: List<RaycastCoverageHit>, nowMs: Long = nowEpochMs()) {
        if (totalCells == 0 || hits.isEmpty()) return
        val map = _creditedCells.value.toMutableMap()
        var changed = false
        for (hit in hits) {
            if (hit.distanceMeters > GRAZE_RANGE_METERS) continue
            val cell = cellForLatLng(hit.lat, hit.lng) ?: continue
            val quality =
                if (hit.distanceMeters <= CLOSE_RANGE_METERS) CoverageQuality.CLOSE
                else CoverageQuality.GRAZED
            if (creditCell(map, cell, quality, nowMs)) changed = true
        }
        if (changed) publish(map)
    }

    /**
     * INDOOR-flip purge: undo every credit made at/after [cutoffMs] — in-window
     * upgrades revert to their pre-window state; cells first credited in the
     * window disappear entirely. Companion to the recorder/photo purges.
     */
    fun purgeSince(cutoffMs: Long) {
        val map = _creditedCells.value.toMutableMap()
        var changed = false
        for ((cell, credit) in _creditedCells.value) {
            if (credit.creditedAtMs >= cutoffMs) {
                val prior = credit.prior
                if (prior != null) map[cell] = prior else map.remove(cell)
                changed = true
            }
        }
        if (changed) publish(map)
    }
```

Rewrite `updatePosition` (lines 222-254): signature becomes
`fun updatePosition(lat: Double, lng: Double, nowMs: Long = nowEpochMs())`; the body keeps the
identical cell-radius loop but replaces the set mutation with the credit map:

```kotlin
        val map = _creditedCells.value.toMutableMap()
        var changed = false
        for (dr in -radiusRows..radiusRows) {
            for (dc in -radiusCols..radiusCols) {
                val r = currentRow + dr
                val c = currentCol + dc
                if (r < 0 || r >= gridRows || c < 0 || c >= gridCols) continue
                val cell = Pair(r, c)
                if (map[cell] != null) continue  // GPS tier can never upgrade — skip credited cells

                val cellLat = originLat + (r + 0.5) * cellSizeMeters * latPerMeter
                val cellLng = originLng + (c + 0.5) * cellSizeMeters * lngPerMeter

                val dist = GeofenceVerification.distanceMeters(lat, lng, cellLat, cellLng)
                if (dist <= scanRadiusMeters) {
                    if (creditCell(map, cell, CoverageQuality.GRAZED, nowMs)) changed = true
                }
            }
        }
        if (changed) publish(map)
```

(Delete the old `_scannedCells.value = newScanned` + percent recompute lines — `publish` owns them.)

Update `reset()` to also clear the map/close percent:

```kotlin
    fun reset() {
        _creditedCells.value = emptyMap()
        _scannedCells.value = emptySet()
        _coveragePercent.value = 0.0
        _closePercent.value = 0.0
    }
```

`initFromRoute`/`initFromBlock` end with `_scannedCells.value = emptySet(); _coveragePercent.value = 0.0` — replace both occurrences with `publish(emptyMap())`.

In `updateWithFrustum` (still present until Task 6), replace its set-mutation tail the same way as
`updatePosition` (use `creditCell(map, Pair(r, c), CoverageQuality.GRAZED, nowEpochMs())` and
`publish(map)`) so the class has a single state representation. Do not change its signature.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.CoverageTrackerQualityTest"`
Expected: PASS (6 tests). Also run the existing suite to catch regressions:
`./gradlew :composeApp:desktopTest --tests "*CoverageTracker*"`
Expected: PASS (includes CoverageTrackerBlockCorridorTest).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageTrackerQualityTest.kt
git commit -m "feat(coverage-quality): quality-aware CoverageTracker with raycast crediting and purge"
```

---

### Task 2: CoverageEventQueue.purgeSince + CoverageRetractionQueue

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageQueuePurgeTest.kt` (create)

**Interfaces:**
- Consumes: `ScanCoverageEventRequest` (existing), `RetractCoverageRequest` (Task 3 — write this task against the model, it compiles once Task 3 lands; if executing strictly in order, do Task 3 FIRST — Tasks 2 and 3 are order-swappable, and subagent executors should run Task 3 before Task 2).
- Produces:
  - `suspend fun CoverageEventQueue.purgeSince(cutoffEpochMs: Long): Int` (returns count dropped)
  - `class CoverageRetractionQueue(submitFn: suspend (RetractCoverageRequest) -> Boolean, settings: Settings = Settings())` with `suspend fun enqueue(request: RetractCoverageRequest)` and `suspend fun flush(): Boolean`, persisted under key `"pending_coverage_retractions"`.

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageQueuePurgeTest.kt`:

```kotlin
package com.gcc.app.platform

import com.gcc.shared.model.RetractCoverageRequest
import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
import com.russhwolf.settings.Settings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory Settings — the interface is tiny; a map-backed fake keeps the test dependency-free. */
private class FakeSettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun remove(key: String) { map.remove(key) }
    override fun hasKey(key: String): Boolean = key in map
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = map[key] as? Int
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = map[key] as? Long
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defaultValue: String): String = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = map[key] as? String
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = map[key] as? Float
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = map[key] as? Double
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = map[key] as? Boolean
}

private fun event(capturedAt: String) = ScanCoverageEventRequest(
    deviceId = "dev-1", platform = "ios", lat = 41.86, lng = -87.72,
    distanceMeters = 1.0, scanMode = "spot", deviceTier = "pro_scanner",
    capturedAt = capturedAt,
)

class CoverageQueuePurgeTest {

    @Test
    fun purgeDropsInWindowEventsAndPersists() = runTest {
        val settings = FakeSettings()
        val queue = CoverageEventQueue(submitFn = { ScanCoverageSubmitResponse(inserted = it.size, success = true) }, settings = settings)
        queue.enqueue(event("2026-07-06T15:00:00Z"))
        queue.enqueue(event("2026-07-06T15:00:20Z"))
        val cutoff = kotlinx.datetime.Instant.parse("2026-07-06T15:00:10Z").toEpochMilliseconds()
        val dropped = queue.purgeSince(cutoffEpochMs = cutoff)
        assertEquals(1, dropped)
        // Persistence survives a "restart": a NEW queue on the same settings sees one event.
        var flushedCount = -1
        val reloaded = CoverageEventQueue(submitFn = { flushedCount = it.size; ScanCoverageSubmitResponse(it.size, true) }, settings = settings)
        assertTrue(reloaded.flush())
        assertEquals(1, flushedCount)
    }

    @Test
    fun retractionQueueFlushesAndRetriesOnFailure() = runTest {
        val settings = FakeSettings()
        var attempts = 0
        var succeed = false
        val queue = CoverageRetractionQueue(
            submitFn = { attempts++; succeed },
            settings = settings,
        )
        queue.enqueue(RetractCoverageRequest(deviceId = "dev-1", fromCapturedAt = "2026-07-06T15:00:00Z", toCapturedAt = "2026-07-06T15:00:10Z"))
        assertEquals(false, queue.flush()) // fails — stays queued
        succeed = true
        assertTrue(queue.flush())
        assertEquals(2, attempts)
        assertTrue(queue.flush()) // empty now — no third submit
        assertEquals(2, attempts)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.CoverageQueuePurgeTest"`
Expected: FAIL to compile — `purgeSince` / `CoverageRetractionQueue` / `RetractCoverageRequest` unresolved. (`RetractCoverageRequest` comes from Task 3.)

- [ ] **Step 3: Implement**

In `CoverageEventQueue.kt` add to the class (after `enqueue`):

```kotlin
    /**
     * INDOOR-flip purge: drop still-pending events captured at/after the
     * cutoff so they never reach the server, and re-persist. Returns how many
     * were dropped. Events that already flushed are handled by the retraction
     * queue instead (spec §2).
     */
    suspend fun purgeSince(cutoffEpochMs: Long): Int {
        mutex.withLock {
            val before = pending.size
            pending.removeAll { event ->
                val at = try {
                    kotlinx.datetime.Instant.parse(event.capturedAt).toEpochMilliseconds()
                } catch (e: Exception) {
                    return@removeAll false // unparseable timestamp: keep (server validates)
                }
                at >= cutoffEpochMs
            }
            val dropped = before - pending.size
            if (dropped > 0) persistLocked()
            return dropped
        }
    }
```

Append to the same file (below `CoverageEventQueue`):

```kotlin
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

    private fun persistLocked() {
        settings.putString(KEY_PENDING_RETRACTIONS, GccJson.encodeToString(pending))
    }

    suspend fun enqueue(request: RetractCoverageRequest) {
        mutex.withLock {
            pending.add(request)
            persistLocked()
        }
    }

    /** True when empty or all retractions were accepted; false leaves the rest queued. */
    suspend fun flush(): Boolean {
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
    }

    companion object {
        private const val KEY_PENDING_RETRACTIONS = "pending_coverage_retractions"
    }
}
```

Add `import com.gcc.shared.model.RetractCoverageRequest` and `import kotlinx.datetime.Instant` at the top.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.CoverageQueuePurgeTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageQueuePurgeTest.kt
git commit -m "feat(coverage-quality): pending-event purge + persistent retraction queue"
```

---

### Task 3: Wire models + API client (quality, retract)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCoverage.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt` (around line 1112, `submitCoverageEvents`)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageWireCompatTest.kt` (create)

**Interfaces:**
- Produces:
  - `ScanCoverageEventRequest.quality: String = "grazed"` (values `"grazed"` | `"close"`)
  - `MapCoverageCell.quality: String = "grazed"`
  - `@Serializable data class RetractCoverageRequest(val deviceId: String, val fromCapturedAt: String, val toCapturedAt: String)`
  - `@Serializable data class RetractCoverageResponse(val deletedEvents: Int = 0, val cellsRecomputed: Int = 0, val success: Boolean, val error: String? = null)`
  - `suspend fun GccApiClient.retractCoverageEvents(request: RetractCoverageRequest): RetractCoverageResponse` → POST `/api/scans/coverage/retract`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageWireCompatTest.kt`:

```kotlin
package com.gcc.app.platform

import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.RetractCoverageRequest
import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageWireCompatTest {

    @Test
    fun legacyEventJsonDefaultsToGrazed() {
        // Deployed builds send NO quality field — they credit via the blind
        // 15m cone, which is exactly what "grazed" means (spec §3).
        val json = """{"deviceId":"d","platform":"ios","lat":41.0,"lng":-87.0,
            "distanceMeters":1.0,"scanMode":"spot","deviceTier":"basic",
            "capturedAt":"2026-07-06T15:00:00Z"}"""
        val event = GccJson.decodeFromString<ScanCoverageEventRequest>(json)
        assertEquals("grazed", event.quality)
    }

    @Test
    fun legacyMapCellJsonDefaultsToGrazed() {
        val json = """{"locationCellId":"c","lat":41.0,"lng":-87.0,"state":"fresh",
            "lastScannedAt":"2026-07-06T15:00:00Z","totalDistanceMeters":5.0}"""
        val cell = GccJson.decodeFromString<MapCoverageCell>(json)
        assertEquals("grazed", cell.quality)
    }

    @Test
    fun retractRequestRoundTrips() {
        val request = RetractCoverageRequest("d", "2026-07-06T15:00:00Z", "2026-07-06T15:00:10Z")
        val decoded = GccJson.decodeFromString<RetractCoverageRequest>(
            com.gcc.shared.serialization.GccJson.encodeToString(RetractCoverageRequest.serializer(), request)
        )
        assertEquals(request, decoded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.CoverageWireCompatTest"`
Expected: FAIL to compile — `quality` / `RetractCoverageRequest` unresolved.

- [ ] **Step 3: Implement the models + client method**

In `ScanCoverage.kt`: add `val quality: String = "grazed", // "grazed" | "close"` as the last
property of `ScanCoverageEventRequest`, and `val quality: String = "grazed", // best tier on record`
as the last property of `MapCoverageCell`. Append:

```kotlin
/** Undo a device's coverage events in a time window (INDOOR-flip retraction). */
@Serializable
data class RetractCoverageRequest(
    val deviceId: String,
    val fromCapturedAt: String,
    val toCapturedAt: String,
)

@Serializable
data class RetractCoverageResponse(
    val deletedEvents: Int = 0,
    val cellsRecomputed: Int = 0,
    val success: Boolean,
    val error: String? = null,
)
```

In `GccApiClient.kt`, directly below `submitCoverageEvents` (line ~1112), add — mirroring its
error handling exactly (read the existing method body and copy its status-check/`checkUnauthorized`/
`httpErrorMessage` tail verbatim, adjusting types):

```kotlin
    suspend fun retractCoverageEvents(request: RetractCoverageRequest): RetractCoverageResponse {
        val response = httpClient.post("$baseUrl/api/scans/coverage/retract") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body()
    }
```

Add `RetractCoverageRequest`/`RetractCoverageResponse` to the imports of `GccApiClient.kt`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.Coverage*"`
Expected: PASS — wire tests AND Tasks 1-2 suites still green.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCoverage.kt \
        shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/platform/CoverageWireCompatTest.kt
git commit -m "feat(coverage-quality): quality on wire models + retract endpoint client"
```

---

### Task 4: Raycast fan (iosMain) + coverage-hit listener on both AR overlays

**Files:**
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CoverageRayFan.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (expect, ~line 102)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (expect — find the expect class declaration and add the same member)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`
- Modify: Android + Desktop `actual` files for BOTH overlays (locate with `grep -rln "actual class ArEnvScanOverlay\|actual class ArRouteOverlay" composeApp/src/androidMain composeApp/src/desktopMain`) — add no-op actuals.

**Interfaces:**
- Consumes: `RaycastCoverageHit` (Task 1), `CoverageTracker.GRAZE_RANGE_METERS`, existing `raycastImage` logic (`ArEnvScan.kt` iosMain :484-504), `localToLatLng` (`ArGeoMath.kt`), `ArNativeSurface.raycast/cameraPosition`.
- Produces (both overlays):
  - `fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?)` — batch of geo-resolved fan hits, delivered on the main thread, at most ~1/sec, only while a geo-reference is paired.

**No new unit test** (ARKit-bound; commonTest can't exercise it) — the deliverable gate is the iOS
framework link + the crediting logic already pinned by Task 1's tests. Manual walk-test rides the
existing TestFlight checklist.

- [ ] **Step 1: Shared fan helper**

Create `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CoverageRayFan.kt`:

```kotlin
package com.gcc.app.platform

import platform.ARKit.ARFrame
import platform.ARKit.ARHitTestResult
import platform.ARKit.ARHitTestResultTypeEstimatedHorizontalPlane
import platform.ARKit.ARHitTestResultTypeExistingPlaneUsingGeometry
import platform.ARKit.ARHitTestResultTypeFeaturePoint
import platform.CoreGraphics.CGPointMake
import kotlin.math.sqrt

/**
 * Per-tick raycast fan for coverage crediting (spec §1): a fixed grid of
 * image-space sample points swept across the camera view. Each ray credits
 * the cell it LANDS in — rays terminate on real surfaces, so indoor walls
 * physically bound what can credit.
 *
 * Image-space orientation (portrait UI): screen-vertical = image X
 * (viewY = imageX in the reticle mapping, ArEnvScan analyzeTrash), so
 * "toward the ground" = imageX toward 1.0. imageY spans screen-horizontal.
 */
internal object CoverageRayFan {
    /** (imageX, imageY) sample grid: 3 vertical bands (center → ground) × 5 horizontal. */
    val SAMPLE_POINTS: List<Pair<Double, Double>> = buildList {
        for (ix in listOf(0.5, 0.7, 0.85)) {
            for (iy in listOf(0.15, 0.35, 0.5, 0.65, 0.85)) add(ix to iy)
        }
    }

    class FanHit(val x: Float, val y: Float, val z: Float, val distance: Float)

    /** One world hit for a normalized image point via native surface or SceneKit frame. */
    fun raycastPoint(surface: ArNativeSurface?, frame: ARFrame?, imageX: Double, imageY: Double): FanHit? {
        surface?.let { native ->
            val hit = native.raycast(imageX, imageY) ?: return null
            val cam = native.cameraPosition() ?: return null
            val dx = hit[0] - cam[0]
            val dy = hit[1] - cam[1]
            val dz = hit[2] - cam[2]
            return FanHit(
                hit[0].toFloat(), hit[1].toFloat(), hit[2].toFloat(),
                sqrt(dx * dx + dy * dy + dz * dz).toFloat(),
            )
        }
        val hit = frame?.hitTest(
            CGPointMake(imageX, imageY),
            types = ARHitTestResultTypeExistingPlaneUsingGeometry
                or ARHitTestResultTypeEstimatedHorizontalPlane
                or ARHitTestResultTypeFeaturePoint,
        )?.firstOrNull() as? ARHitTestResult ?: return null
        val world = matrixTranslation(hit.worldTransform)
        return FanHit(world[0], world[1], world[2], hit.distance.toFloat())
    }

    /**
     * Cast the full fan and geo-resolve the hits. [toLatLng] is the caller's
     * geo-ref conversion (localToLatLng bound to its reference frame).
     * Hits beyond [CoverageTracker.GRAZE_RANGE_METERS] are dropped here so
     * listeners only ever see creditable hits.
     */
    fun cast(
        surface: ArNativeSurface?,
        frame: ARFrame?,
        toLatLng: (x: Float, z: Float) -> Pair<Double, Double>,
    ): List<RaycastCoverageHit> {
        val hits = mutableListOf<RaycastCoverageHit>()
        for ((ix, iy) in SAMPLE_POINTS) {
            val hit = raycastPoint(surface, frame, ix, iy) ?: continue
            if (hit.distance > CoverageTracker.GRAZE_RANGE_METERS) continue
            val (lat, lng) = toLatLng(hit.x, hit.z)
            hits.add(RaycastCoverageHit(lat, lng, hit.distance.toDouble()))
        }
        return hits
    }
}
```

Note: `matrixTranslation` is an existing iosMain helper used by `raycastImage` (ArEnvScan.kt:502) —
if it is `private` in the ArEnvScan actual class, move it to file-level `internal` in
`CoverageRayFan.kt` and delete the class-local copy (single definition).

- [ ] **Step 2: expect declarations**

In commonMain `ArEnvScan.kt`, inside `expect class ArEnvScanOverlay` after `setCeilingListener`
(line ~102), add:

```kotlin
    /**
     * Fires ~1/sec with the coverage raycast fan's geo-resolved hits (where
     * rays LANDED, with surface distance) while a geo-reference is paired.
     * Listener runs on the main thread; iOS only. Feed straight into
     * [CoverageTracker.updateWithRaycastHits].
     */
    fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?)
```

Add the identical member (same KDoc) to `expect class ArRouteOverlay` in commonMain
`ArRouteOverlay.kt` (place it beside `setCeilingListener`).

- [ ] **Step 3: iosMain ArEnvScanOverlay actual**

In iosMain `ArEnvScan.kt`:
- Field (beside `ceilingListener`, ~line 179): `private var coverageHitListener: ((List<RaycastCoverageHit>) -> Unit)? = null`
- Actual (beside `setCeilingListener`):

```kotlin
    actual fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?) {
        coverageHitListener = listener
    }
```

- In `analyzeTick()`, insert AFTER the ground-detected block (after line ~390's `else if (frame != null) { ... updatePlaneNodes ... }`) and BEFORE the vote-cadence computation:

```kotlin
        // Coverage raycast fan: cheap (no Vision), runs whenever analysis is
        // live and a geo-ref exists. Rays stop on real surfaces — this is the
        // measured "what did the camera actually see" signal (spec §1).
        if (analysisEnabled && hasGeoRef) {
            coverageHitListener?.let { listener ->
                val fanHits = CoverageRayFan.cast(nativeSurface, frame) { x, z ->
                    localToLatLng(x, z, refLat, refLng, refX, refZ, lngScale)
                }
                if (fanHits.isNotEmpty()) {
                    dispatch_async(dispatch_get_main_queue()) { listener(fanHits) }
                }
            }
        }
```

- Refactor: `raycastImage` (line 484) keeps working for trash lock-on; if you moved
  `matrixTranslation` per Step 1's note, update its reference.

- [ ] **Step 4: iosMain ArRouteOverlay actual (own 1s fan loop)**

ArRouteOverlay has no tick loop of its own (frame work lives in `frameFeedback`), so give the fan a
lightweight one. In iosMain `ArRouteOverlay.kt`:

- Imports: `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.Job`, `kotlinx.coroutines.SupervisorJob`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.launch`, `platform.darwin.dispatch_async`, `platform.darwin.dispatch_get_main_queue` (keep whichever already exist).
- Fields (near `hasGeoRef`, line ~112):

```kotlin
    private var coverageHitListener: ((List<RaycastCoverageHit>) -> Unit)? = null
    private var coverageFanJob: Job? = null
    private val coverageFanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

- Actual + loop:

```kotlin
    actual fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?) {
        coverageHitListener = listener
        if (listener == null) {
            coverageFanJob?.cancel()
            coverageFanJob = null
            return
        }
        if (coverageFanJob != null) return
        coverageFanJob = coverageFanScope.launch {
            while (true) {
                delay(1_000L)
                if (!hasGeoRef) continue
                val listenerNow = coverageHitListener ?: continue
                val frame = if (nativeSurface == null) sceneView?.session?.currentFrame else null
                val fanHits = try {
                    CoverageRayFan.cast(nativeSurface, frame) { x, z -> localToLatLng(x, z) }
                } catch (e: Throwable) {
                    DiagnosticsLog.error("ar-route", "coverage fan tick failed", e)
                    emptyList()
                }
                if (fanHits.isNotEmpty()) {
                    dispatch_async(dispatch_get_main_queue()) { listenerNow(fanHits) }
                }
            }
        }
    }
```

(`localToLatLng(x, z)` here is ArRouteOverlay's existing 2-arg private wrapper, line ~664.)
- In `stop()` (line ~150) add `coverageFanJob?.cancel(); coverageFanJob = null` before the pauses.

- [ ] **Step 5: Android/Desktop no-op actuals**

Locate each non-iOS `actual class ArEnvScanOverlay` / `actual class ArRouteOverlay` and add:

```kotlin
    actual fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?) {
        // No AR raycasting off-iOS — GPS-tier crediting (updatePosition) covers these targets.
    }
```

(with the matching import if the file needs it).

- [ ] **Step 6: Verify all targets compile**

Run: `./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL — desktop tests green, iOS framework links. If the link fails on the
expect/actual pair, confirm every target's actual class gained the member (Android compiles via
`:composeApp:compileDebugKotlinAndroid` if you need to isolate it).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt \
        composeApp/src/iosMain/kotlin/com/gcc/app/platform/CoverageRayFan.kt \
        composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt \
        composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt \
        composeApp/src/androidMain composeApp/src/desktopMain
git commit -m "feat(coverage-quality): raycast coverage fan on env + route AR overlays"
```

---

### Task 5: SpotScreen rewire — fan crediting, event quality, indoor purge + retraction

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`

**Interfaces:**
- Consumes: `tracker.updateWithRaycastHits/purgeSince/creditedCells` (Task 1), `coverageQueue.purgeSince` + `CoverageRetractionQueue` (Task 2), `RetractCoverageRequest` + `apiClient.retractCoverageEvents` (Task 3), `arOverlay.setCoverageHitListener` (Task 4).
- Produces: `closeCellCenters` list consumed by Task 9's minimap params; session events now carry `quality`.

- [ ] **Step 1: Fan listener + close-tier flag**

Near the top of the composable where `sessionRecorder` is declared (line ~326), add:

```kotlin
    // Set by the raycast-fan listener when any CLOSE-tier hit credits; read +
    // reset per coverage event so the event carries the best tier since the
    // previous fix. Plain holder (not Compose state): mutated off-composition.
    val closeCreditSinceLastEvent = remember { object { var value = false } }
```

In the `DisposableEffect(arOverlay)` block (line ~328), alongside `setElementObservedListener`, add:

```kotlin
        arOverlay?.setCoverageHitListener { hits ->
            if (!gate.captureAllowed.value) return@setCoverageHitListener
            tracker.updateWithRaycastHits(hits)
            if (hits.any { it.distanceMeters <= CoverageTracker.CLOSE_RANGE_METERS }) {
                closeCreditSinceLastEvent.value = true
            }
        }
```

and in its `onDispose` add `arOverlay?.setCoverageHitListener(null)`.

- [ ] **Step 2: Replace the frustum credit path with the grazed floor**

In the crediting collector (line ~534-548), replace:

```kotlin
            val heading = locationProvider.headingDegrees.value
            if (heading != null) {
                tracker.updateWithFrustum(lat = loc.lat, lng = loc.lng, headingDegrees = heading)
            } else {
                tracker.updatePosition(loc.lat, loc.lng)
            }
```

with:

```kotlin
            // Grazed floor: the ground you walk over is observed at sweep
            // quality; everything richer comes from the raycast fan (spec §1).
            val heading = locationProvider.headingDegrees.value
            tracker.updatePosition(loc.lat, loc.lng)
```

(`heading` stays — `sessionRecorder.addSample` below still consumes it.)

- [ ] **Step 3: Event quality**

In the same collector, the `coverageQueue.enqueue(ScanCoverageEventRequest(...))` call (line ~562)
gains one argument and the flag resets after it:

```kotlin
            if (getPlatformName() != "desktop") {
                coverageQueue.enqueue(
                    ScanCoverageEventRequest(
                        deviceId = CoverageDeviceId.value,
                        platform = getPlatformName(),
                        lat = loc.lat,
                        lng = loc.lng,
                        distanceMeters = segmentMeters,
                        scanMode = "spot",
                        deviceTier = deviceCapabilities.deviceClass.coverageTier(),
                        capturedAt = Clock.System.now().toString(),
                        quality = if (closeCreditSinceLastEvent.value) "close" else "grazed",
                    )
                )
                closeCreditSinceLastEvent.value = false
            }
```

- [ ] **Step 4: INDOOR purge + retraction**

Below the `coverageQueue` declaration (line ~411-420) add:

```kotlin
    // Retractions: already-flushed indoor-window events the server must undo.
    val retractQueue = remember(apiClient) {
        CoverageRetractionQueue(submitFn = { request ->
            apiClient.retractCoverageEvents(request).success
        })
    }
```

In the `LaunchedEffect(outdoorState)` INDOOR branch (line ~392-396), extend to:

```kotlin
        if (outdoorState == OutdoorState.INDOOR) {
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val cutoff = (gate.lastIndoorFlipAtMs ?: nowMs) - OutdoorGate.PURGE_WINDOW_MS
            sessionRecorder.purgeSince(cutoff)      // SpotScreen only (owns the recorder)
            onPurgeRecentCaptures(cutoff)           // new param → AppNavigation → viewModel::purgeRecentCaptures
            // Coverage companion purges (spec §2): local cells, pending
            // events, and a server retraction for anything already flushed.
            tracker.purgeSince(cutoff)
            flushScope.launch {
                coverageQueue.purgeSince(cutoff)
                retractQueue.enqueue(
                    RetractCoverageRequest(
                        deviceId = CoverageDeviceId.value,
                        fromCapturedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(cutoff).toString(),
                        toCapturedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs).toString(),
                    )
                )
                retractQueue.flush()
            }
        }
```

Add imports: `com.gcc.app.platform.CoverageRetractionQueue`, `com.gcc.shared.model.RetractCoverageRequest`,
`com.gcc.app.platform.RaycastCoverageHit` if not auto-imported, `com.gcc.app.platform.CoverageQuality`.

- [ ] **Step 5: Expose the close-tier cell list (used by Task 9)**

After `scanCellCenters` (line ~586-588) add:

```kotlin
    val creditedCells by tracker.creditedCells.collectAsState()
    val closeCellCenters = remember(creditedCells) {
        creditedCells.filterValues { it.quality == CoverageQuality.CLOSE }
            .keys.map { (row, col) -> tracker.cellCenterLatLng(row, col) }
    }
```

- [ ] **Step 6: Verify**

Run: `./gradlew :composeApp:desktopTest && ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL, all tests green. (`closeCellCenters` is unused until Task 9 — a
compiler warning is fine, an error is not.)

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt
git commit -m "feat(coverage-quality): env-scan fan crediting, event quality, indoor coverage purge"
```

---

### Task 6: RouteScanViewModel rewire + delete updateWithFrustum

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt` (wire listener)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt` (delete `updateWithFrustum`)

**Interfaces:**
- Consumes: everything Tasks 1-4 produced.
- Produces: `fun RouteScanViewModel.onCoverageRayHits(hits: List<RaycastCoverageHit>)` — ArRouteScreen calls it from the overlay listener.

- [ ] **Step 1: Add the hits entry point + close flag to the VM**

In `RouteScanViewModel.kt`, near the other private state fields (find `lastCoverageFix`), add:

```kotlin
    /** True when any CLOSE-tier fan hit credited since the last coverage event. */
    private var closeCreditSinceLastEvent = false
```

Add the public entry point (near `setError` or the other public funs):

```kotlin
    /** Raycast-fan hits from the route AR overlay (ArRouteScreen wires the listener). */
    fun onCoverageRayHits(hits: List<RaycastCoverageHit>) {
        if (!outdoorGate.captureAllowed.value) return
        if (_uiState.value.phase != ScanPhase.SCOUTING) return
        coverageTracker.updateWithRaycastHits(hits)
        if (hits.any { it.distanceMeters <= CoverageTracker.CLOSE_RANGE_METERS }) {
            closeCreditSinceLastEvent = true
        }
    }
```

Import `com.gcc.app.platform.RaycastCoverageHit`.

- [ ] **Step 2: Replace the frustum branch, stamp event quality, purge on INDOOR**

At lines ~1135-1146, replace:

```kotlin
                        // AR tier: use camera frustum for coverage; GPS tier: use radius
                        val heading = arScanner.getArHeading() ?: locationProvider.headingDegrees.value
                        if (state.deviceType == DeviceType.AR && heading != null) {
                            coverageTracker.updateWithFrustum(
                                lat = lat,
                                lng = lng,
                                headingDegrees = heading,
                                fovDegrees = arScanner.getCameraFovDegrees(),
                            )
                        } else {
                            coverageTracker.updatePosition(lat, lng)
                        }
```

with:

```kotlin
                        // Grazed floor at the walked position; the raycast fan
                        // (onCoverageRayHits) supplies measured grazed/close
                        // credit — the blind 15m frustum is gone (spec §1).
                        val heading = arScanner.getArHeading() ?: locationProvider.headingDegrees.value
                        coverageTracker.updatePosition(lat, lng)
```

At the `coverageEventQueue.enqueue(ScanCoverageEventRequest(...))` call (line ~1102), add the
quality argument + reset, mirroring Task 5 Step 3:

```kotlin
                        if (getPlatformName() != "desktop") {
                            coverageEventQueue.enqueue(
                                ScanCoverageEventRequest(
                                    deviceId = CoverageDeviceId.value,
                                    platform = getPlatformName(),
                                    lat = lat,
                                    lng = lng,
                                    distanceMeters = segmentMeters,
                                    scanMode = "route",
                                    deviceTier = coverageDeviceTier,
                                    capturedAt = Clock.System.now().toString(),
                                    quality = if (closeCreditSinceLastEvent) "close" else "grazed",
                                )
                            )
                            closeCreditSinceLastEvent = false
                        }
```

In the gate collector's INDOOR branch (line ~1032-1038), extend to:

```kotlin
                    if (gateState == OutdoorState.INDOOR) {
                        // Privacy purge: drop the trailing walk-inside seconds
                        // recorded just before this flip so they never upload.
                        val cutoff = (outdoorGate.lastIndoorFlipAtMs ?: nowMs) - OutdoorGate.PURGE_WINDOW_MS
                        lapSessionRecorder.purgeSince(cutoff)
                        // Coverage companions (spec §2): local cells, pending
                        // events, server retraction of flushed ones.
                        coverageTracker.purgeSince(cutoff)
                        scope.launch {
                            coverageEventQueue.purgeSince(cutoff)
                            retractionQueue.enqueue(
                                RetractCoverageRequest(
                                    deviceId = CoverageDeviceId.value,
                                    fromCapturedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(cutoff).toString(),
                                    toCapturedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs).toString(),
                                )
                            )
                            retractionQueue.flush()
                        }
                    }
```

Declare the retraction queue beside `coverageEventQueue` (line ~166), using the SAME api-client
source that `coverageEventQueue`'s `submitFn` uses (read that declaration and mirror it):

```kotlin
    private val retractionQueue = CoverageRetractionQueue(
        submitFn = { request -> apiClient.retractCoverageEvents(request).success },
    )
```

(If `coverageEventQueue`'s submitFn reaches the client through a different name — e.g. an injected
`gccApiClient` — use that exact name.) Import `CoverageRetractionQueue`, `RetractCoverageRequest`.

- [ ] **Step 3: Wire the listener in ArRouteScreen**

In `ArRouteScreen.kt`, find where the route overlay is created/bound and where `updateGeoReference`
is wired (grep `updateGeoReference` in the file); in the same effect that owns the overlay lifecycle
add, with the matching disposal:

```kotlin
        overlay.setCoverageHitListener { hits -> viewModel.onCoverageRayHits(hits) }
```

```kotlin
        overlay.setCoverageHitListener(null)   // in the existing onDispose/cleanup path
```

(Use the local variable names the file actually has for the overlay and view model.)

- [ ] **Step 4: Delete updateWithFrustum**

In `CoverageTracker.kt`, delete the entire `updateWithFrustum` function and its KDoc (the
"Frustum (AR-tier) coverage" section). Verify no remaining callers:

Run: `grep -rn "updateWithFrustum" composeApp/src shared/src`
Expected: no output.

- [ ] **Step 5: Verify**

Run: `./gradlew :composeApp:desktopTest && ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL, tests green.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt
git commit -m "feat(coverage-quality): route scan on raycast crediting; delete blind 15m frustum"
```

---

### Task 7: Server — schema, quality recording, split points

**Files:**
- Modify: `site/src/db/schema.ts` (scan_coverage_events ~line 1688, cell_coverage_summary ~line 1708)
- Modify: `site/src/lib/points-config.ts`
- Modify: `site/src/lib/scan-coverage.ts`
- Modify: `site/src/app/api/scans/coverage/route.ts`

**Interfaces:**
- Produces: `scanCoverageEvents.quality`, `cellCoverageSummary.bestQuality/lastCloseScannedAt` columns; `RecordCoverageEventInput.quality`; categories `new_area | new_area_grazed | close_upgrade | stale_rescan | fresh_rescan`; ledger actions `scan_coverage_new_area | scan_coverage_stale | scan_coverage_close_upgrade`.

- [ ] **Step 1: Schema columns**

In `schema.ts`, add to `scanCoverageEvents` (after `deviceTier`):

```typescript
  quality: varchar('quality', { length: 8 }).default('grazed').notNull(), // 'grazed' | 'close'
```

and to `cellCoverageSummary` (after `scanCount`):

```typescript
  bestQuality: varchar('best_quality', { length: 8 }).default('grazed').notNull(), // 'grazed' | 'close'
  lastCloseScannedAt: timestamp('last_close_scanned_at', { mode: 'string', withTimezone: true }),
```

- [ ] **Step 2: Points config**

In `points-config.ts` replace the `POINTS_PER_METER` object with:

```typescript
export const POINTS_PER_METER = {
  NEW_AREA: 10,          // first-ever scan of a cell, close quality
  NEW_AREA_GRAZED: 4,    // first-ever scan, sweep quality — partial award (spec §3)
  CLOSE_UPGRADE: 6,      // grazed→close upgrade pays the remainder (4 + 6 = 10)
  STALE_RESCAN: 5,
  FRESH_RESCAN: 0, // no incentive to farm the same block repeatedly in one sitting
} as const
```

- [ ] **Step 3: Quality-aware recording**

In `scan-coverage.ts`:

- `RecordCoverageEventInput` gains `quality: 'grazed' | 'close'`.
- Replace the category type + classifier:

```typescript
type CoverageCategory = 'new_area' | 'new_area_grazed' | 'close_upgrade' | 'stale_rescan' | 'fresh_rescan'

/**
 * Classifies against the cell's PRIOR state (read before any write this call
 * makes). Quality split (spec §3): grazed first-scan pays partial, a
 * grazed→close upgrade on a fresh cell pays the remainder, straight-to-close
 * pays full. Stale rescans pay the flat stale rate at either quality.
 */
function categorizeCoverageEvent(
  priorLastScannedAt: string | null,
  priorBestQuality: string | null,
  capturedAt: string,
  quality: 'grazed' | 'close'
): CoverageCategory {
  if (priorLastScannedAt === null) return quality === 'close' ? 'new_area' : 'new_area_grazed'
  const ageMs = new Date(capturedAt).getTime() - new Date(priorLastScannedAt).getTime()
  if (ageMs > STALE_AFTER_MS) return 'stale_rescan'
  if (quality === 'close' && priorBestQuality !== 'close') return 'close_upgrade'
  return 'fresh_rescan'
}
```

- In `recordCoverageEvent`: read `bestQuality` in the prior-summary select:

```typescript
  const [priorSummary] = await db
    .select({
      lastScannedAt: cellCoverageSummary.lastScannedAt,
      bestQuality: cellCoverageSummary.bestQuality,
    })
    .from(cellCoverageSummary)
    .where(eq(cellCoverageSummary.locationCellId, cellId))
    .limit(1)

  const category = categorizeCoverageEvent(
    priorSummary?.lastScannedAt ?? null,
    priorSummary?.bestQuality ?? null,
    input.capturedAt,
    input.quality
  )
```

- Add `quality: input.quality,` to the event insert values.
- Extend the summary upsert values/set:

```typescript
    .values({
      locationCellId: cellId,
      firstScannedAt: input.capturedAt,
      lastScannedAt: input.capturedAt,
      totalDistanceMeters: input.distanceMeters,
      scanCount: 1,
      bestQuality: input.quality,
      lastCloseScannedAt: input.quality === 'close' ? input.capturedAt : null,
    })
    .onConflictDoUpdate({
      target: cellCoverageSummary.locationCellId,
      set: {
        lastScannedAt: sql`GREATEST(${cellCoverageSummary.lastScannedAt}, EXCLUDED.last_scanned_at)`,
        firstScannedAt: sql`LEAST(${cellCoverageSummary.firstScannedAt}, EXCLUDED.first_scanned_at)`,
        totalDistanceMeters: sql`${cellCoverageSummary.totalDistanceMeters} + EXCLUDED.total_distance_meters`,
        scanCount: sql`${cellCoverageSummary.scanCount} + 1`,
        bestQuality: sql`CASE WHEN ${cellCoverageSummary.bestQuality} = 'close' OR EXCLUDED.best_quality = 'close' THEN 'close' ELSE 'grazed' END`,
        lastCloseScannedAt: sql`CASE WHEN EXCLUDED.best_quality = 'close'
          THEN GREATEST(COALESCE(${cellCoverageSummary.lastCloseScannedAt}, EXCLUDED.last_scanned_at), EXCLUDED.last_scanned_at)
          ELSE ${cellCoverageSummary.lastCloseScannedAt} END`,
      },
    })
```

- Points block:

```typescript
  if (input.workerId != null) {
    const perMeter =
      category === 'new_area' ? POINTS_PER_METER.NEW_AREA :
      category === 'new_area_grazed' ? POINTS_PER_METER.NEW_AREA_GRAZED :
      category === 'close_upgrade' ? POINTS_PER_METER.CLOSE_UPGRADE :
      category === 'stale_rescan' ? POINTS_PER_METER.STALE_RESCAN :
      POINTS_PER_METER.FRESH_RESCAN
    const points = Math.round(perMeter * input.distanceMeters)

    if (points > 0) {
      const action =
        category === 'close_upgrade' ? 'scan_coverage_close_upgrade' :
        category === 'stale_rescan' ? 'scan_coverage_stale' :
        'scan_coverage_new_area'
      await db.insert(pointsLedger).values({
        workerId: input.workerId,
        action,
        points,
        referenceType: 'scan_coverage_event',
        referenceId: row.id,
        createdAt: input.capturedAt,
      })
    }
  }
```

- [ ] **Step 4: Validate quality at the API boundary**

In `api/scans/coverage/route.ts`, inside the per-event loop before `recordCoverageEvent`, normalize
(old clients send nothing → grazed; anything unrecognized → grazed, never inflate):

```typescript
      const quality: 'grazed' | 'close' = event.quality === 'close' ? 'close' : 'grazed'
      await recordCoverageEvent({ ...event, quality, workerId: auth.workerId })
```

- [ ] **Step 5: Verify**

Run: `cd site && npm install && npx tsc --noEmit`
Expected: only the two pre-existing @turf/@aws-sdk module-resolution errors (see Global
Constraints); no new errors.

- [ ] **Step 6: Commit**

```bash
git add site/src/db/schema.ts site/src/lib/points-config.ts site/src/lib/scan-coverage.ts \
        site/src/app/api/scans/coverage/route.ts
git commit -m "feat(coverage-quality): quality columns, quality-aware recording, split point awards"
```

---

### Task 8: Server — retract endpoint + points clawback

**Files:**
- Create: `site/src/app/api/scans/coverage/retract/route.ts`
- Modify: `site/src/lib/scan-coverage.ts` (add `retractCoverageWindow`)

**Interfaces:**
- Consumes: Task 7's columns; `requireMobileAuth` (same import as coverage route).
- Produces: `POST /api/scans/coverage/retract` accepting `{deviceId, fromCapturedAt, toCapturedAt}`, response `{deletedEvents, cellsRecomputed, success}` — window bound 10 minutes; deletes only events matching deviceId AND the caller's workerId.

- [ ] **Step 1: Implement retractCoverageWindow**

Append to `scan-coverage.ts` (imports: add `and`, `gte`, `lte`, `inArray` from `drizzle-orm`):

```typescript
const MAX_RETRACT_WINDOW_MS = 10 * 60 * 1000

export interface RetractCoverageInput {
  deviceId: string
  workerId: number
  fromCapturedAt: string
  toCapturedAt: string
}

/**
 * Undo a device's coverage events in a bounded time window (INDOOR-flip
 * retraction, spec §2/§3): claw back the points those events awarded, delete
 * the events, and recompute each touched cell's summary from the surviving
 * event log (deleting the summary row when nothing survives). The event log
 * is the source of truth; the summary is always derivable — which is what
 * makes retraction safe.
 */
export async function retractCoverageWindow(input: RetractCoverageInput) {
  const fromMs = new Date(input.fromCapturedAt).getTime()
  const toMs = new Date(input.toCapturedAt).getTime()
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || toMs <= fromMs) {
    return { deletedEvents: 0, cellsRecomputed: 0, error: 'invalid window' as const }
  }
  if (toMs - fromMs > MAX_RETRACT_WINDOW_MS) {
    return { deletedEvents: 0, cellsRecomputed: 0, error: 'window too large' as const }
  }

  const deleted = await db
    .delete(scanCoverageEvents)
    .where(and(
      eq(scanCoverageEvents.deviceId, input.deviceId),
      eq(scanCoverageEvents.workerId, input.workerId),
      gte(scanCoverageEvents.capturedAt, input.fromCapturedAt),
      lte(scanCoverageEvents.capturedAt, input.toCapturedAt),
    ))
    .returning({ id: scanCoverageEvents.id, locationCellId: scanCoverageEvents.locationCellId })

  if (deleted.length === 0) return { deletedEvents: 0, cellsRecomputed: 0 }

  await db
    .delete(pointsLedger)
    .where(and(
      eq(pointsLedger.referenceType, 'scan_coverage_event'),
      inArray(pointsLedger.referenceId, deleted.map((d) => d.id)),
    ))

  const cellIds = [...new Set(deleted.map((d) => d.locationCellId))]
  for (const cellId of cellIds) {
    const [agg] = await db
      .select({
        firstScannedAt: sql<string | null>`MIN(${scanCoverageEvents.capturedAt})`,
        lastScannedAt: sql<string | null>`MAX(${scanCoverageEvents.capturedAt})`,
        totalDistanceMeters: sql<number>`COALESCE(SUM(${scanCoverageEvents.distanceMeters}), 0)`,
        scanCount: sql<number>`COUNT(*)::int`,
        hasClose: sql<boolean>`COALESCE(BOOL_OR(${scanCoverageEvents.quality} = 'close'), false)`,
        lastCloseScannedAt: sql<string | null>`MAX(${scanCoverageEvents.capturedAt}) FILTER (WHERE ${scanCoverageEvents.quality} = 'close')`,
      })
      .from(scanCoverageEvents)
      .where(eq(scanCoverageEvents.locationCellId, cellId))

    if (!agg || agg.scanCount === 0 || agg.firstScannedAt === null || agg.lastScannedAt === null) {
      await db.delete(cellCoverageSummary).where(eq(cellCoverageSummary.locationCellId, cellId))
    } else {
      await db
        .update(cellCoverageSummary)
        .set({
          firstScannedAt: agg.firstScannedAt,
          lastScannedAt: agg.lastScannedAt,
          totalDistanceMeters: agg.totalDistanceMeters,
          scanCount: agg.scanCount,
          bestQuality: agg.hasClose ? 'close' : 'grazed',
          lastCloseScannedAt: agg.lastCloseScannedAt,
        })
        .where(eq(cellCoverageSummary.locationCellId, cellId))
    }
  }

  return { deletedEvents: deleted.length, cellsRecomputed: cellIds.length }
}
```

- [ ] **Step 2: The route**

Create `site/src/app/api/scans/coverage/retract/route.ts`:

```typescript
import { NextRequest, NextResponse } from 'next/server'
import { retractCoverageWindow } from '@/lib/scan-coverage'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json(
      { deletedEvents: 0, cellsRecomputed: 0, success: false, error: 'Not authenticated' },
      { status: 401 }
    )
  }

  try {
    const body = await request.json()
    if (
      typeof body.deviceId !== 'string' ||
      typeof body.fromCapturedAt !== 'string' ||
      typeof body.toCapturedAt !== 'string'
    ) {
      return NextResponse.json(
        { deletedEvents: 0, cellsRecomputed: 0, success: false, error: 'deviceId, fromCapturedAt, toCapturedAt required' },
        { status: 400 }
      )
    }

    const result = await retractCoverageWindow({
      deviceId: body.deviceId,
      workerId: auth.workerId,
      fromCapturedAt: body.fromCapturedAt,
      toCapturedAt: body.toCapturedAt,
    })
    if ('error' in result && result.error) {
      return NextResponse.json({ ...result, success: false }, { status: 400 })
    }
    return NextResponse.json({ ...result, success: true })
  } catch (error) {
    console.error('POST /api/scans/coverage/retract error:', error)
    return NextResponse.json({ deletedEvents: 0, cellsRecomputed: 0, success: false }, { status: 500 })
  }
}
```

NOTE: an untracked stray `site/src/app/api/scans/[id]/coverage/` directory may exist in the main
checkout — this new route is `scans/coverage/retract` (no `[id]`), so there is no sibling-slug
conflict (`[id]` vs `[routeId]` broke a build once; `coverage` is a literal segment).

- [ ] **Step 3: Verify**

Run: `cd site && npx tsc --noEmit`
Expected: baseline errors only.

- [ ] **Step 4: Commit**

```bash
git add site/src/lib/scan-coverage.ts site/src/app/api/scans/coverage/retract/route.ts
git commit -m "feat(coverage-quality): coverage retraction endpoint with points clawback + summary recompute"
```

---

### Task 9: Quality on the map read path + two-shade main map + minimap

**Files:**
- Modify: `site/src/app/api/map/coverage/route.ts`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt` (buildCoverageStateGeoJsons ~line 849)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt` (coverage layers ~line 766)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ScanMinimapOverlay.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` (pass the new lists)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt` (forward new params — Global Constraints gotcha)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt` (minimap call site, line ~439 — pass session close cells)

**Interfaces:**
- Consumes: `MapCoverageCell.quality` (Task 3), `closeCellCenters` (Task 5), `cellCoverageSummary.bestQuality` (Task 7).
- Produces: `CoverageStateJsons.cleanGrazed`; minimap params `closeCellCenters` + `freshCloseCellCenters`.

- [ ] **Step 1: Server returns quality**

In `api/map/coverage/route.ts`, the response cell mapper gains `quality: r.bestQuality,` (after `state`).

- [ ] **Step 2: Split the clean layer by quality**

In `GeoJsonLayers.kt` `buildCoverageStateGeoJsons`: find `CoverageStateJsons` (its data class is in
the same file — grep `data class CoverageStateJsons`) and add `val cleanGrazed: String`. Replace the
`clean` computation:

```kotlin
    val fresh = coverageCells.filter { it.state == "fresh" }
    val freshNoSpot = fresh.filter { !hasSpotNear(it.lat, it.lng) }
    // Two-tier clean (spec §3): close-scanned reads full green; grazed reads a
    // lighter green — "touched, still needs a detail pass".
    val clean = freshNoSpot
        .filter { it.quality == "close" }
        .joinToString(",") { feature(it) }
    val cleanGrazed = freshNoSpot
        .filter { it.quality != "close" }
        .joinToString(",") { feature(it) }
```

and return `cleanGrazed = wrap(cleanGrazed),` in the result constructor.

- [ ] **Step 3: New fill layer on the iOS map**

In `GccMap.ios.kt` (coverage block, line ~766): add
`val cleanGrazedSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.cleanGrazed))`
beside the other sources, and BEFORE the `coverage-clean` FillLayer add a clone with lighter
opacity (same onClick closure as coverage-clean, copied verbatim):

```kotlin
                // Grazed-fresh: scanned at sweep quality only — lighter green,
                // the map-level "come back and detail-scan me" signal.
                FillLayer(
                    id = "coverage-clean-grazed",
                    source = cleanGrazedSrc,
                    color = const(Color(0xFF22C55E)),
                    opacity = const(0.10f),
                    onClick = if (onCoverageCellTap != null) { features ->
                        val cellId = features.firstOrNull()?.properties?.get("cellId")
                            ?.toString()?.trim('"')
                        if (!cellId.isNullOrBlank()) {
                            onCoverageCellTap(cellId)
                            ClickResult.Consume
                        } else {
                            ClickResult.Pass
                        }
                    } else null,
                )
```

Check other `buildCoverageStateGeoJsons`/`CoverageStateJsons` consumers compile
(`grep -rn "CoverageStateJsons\|buildCoverageStateGeoJsons" composeApp/src`) — desktop/Android map
actuals may need the new field defaulted or ignored. If `ScanCoverageLegend` exists
(`grep -rn "ScanCoverageLegend" composeApp/src`), add a "light green = needs detail scan" row
mirroring its existing row structure.

- [ ] **Step 4: Minimap two shades**

In `ScanMinimapOverlay.kt` add params (after `freshnessCellSizeMeters`):

```kotlin
    /** Session cells credited at CLOSE tier — drawn vivid over the grazed shade. */
    closeCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Ledger-fresh cells whose best quality is close — brighter fresh wash. */
    freshCloseCellCenters: List<Pair<Double, Double>> = emptyList(),
```

Punch-out: add `for ((lat, lng) in freshCloseCellCenters) punch(lat, lng, freshnessCellSizeMeters, 1.1f)`
beside the existing `freshCellCenters` punch (closeCellCenters need no extra punch — they are a
subset of `scanCellCenters`).

Replace the fresh + session draw passes (lines ~144-160) with:

```kotlin
        // Ledger-fresh cells: grazed = whisper (needs detail), close = the
        // established faint green (already well-scanned by the community).
        for ((lat, lng) in freshCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, GccGreen.copy(alpha = 0.07f), overscan = 1.1f)
        }
        for ((lat, lng) in freshCloseCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, GccGreen.copy(alpha = 0.16f), overscan = 1.1f)
        }

        // Stale cells: scanned before, worth re-scanning — amber glow over
        // the veil.
        for ((lat, lng) in staleCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, StaleAmber.copy(alpha = 0.45f), overscan = 1.1f)
        }

        // This session, two shades (spec §4): every credited cell gets the
        // light grazed wash; close-tier cells re-cover vivid — the live
        // "you've detail-scanned this" feedback.
        for ((lat, lng) in scanCellCenters) {
            drawCell(lat, lng, cellSizeMeters, GccGreen.copy(alpha = 0.25f), overscan = 1.2f)
        }
        for ((lat, lng) in closeCellCenters) {
            drawCell(lat, lng, cellSizeMeters, GccGreen.copy(alpha = 0.60f), overscan = 1.2f)
        }
```

(The stale pass stays where it was; only fresh/session passes change.)

- [ ] **Step 5: Thread the data through**

- `SpotScreen.kt`: split the server-fresh lists (line ~476-477 and the cache fallback at ~491-492):

```kotlin
            staleCells = serverCells.filter { it.state == "stale" }.map { it.lat to it.lng }
            freshCells = serverCells.filter { it.state == "fresh" && it.quality != "close" }.map { it.lat to it.lng }
            freshCloseCells = serverCells.filter { it.state == "fresh" && it.quality == "close" }.map { it.lat to it.lng }
```

with a new state var beside `freshCells` (line ~407): `var freshCloseCells by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }`.
Apply the same split in the cache-fallback branch.
- Find where SpotScreen passes minimap layers into its capture UI (`grep -n "freshCellCenters\|SpotScanCapture" composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`) and pass `closeCellCenters = closeCellCenters, freshCloseCellCenters = freshCloseCells` through EVERY wrapper hop down to `ScanMinimapOverlay` in `CameraCapture.kt` — add the params to each wrapper's signature explicitly (Global Constraints: wrappers must forward everything; defaults hide features).
- `ArRouteScreen.kt` minimap call (line ~439): compute close centers from the VM's tracker the same
  way SpotScreen does (the screen already receives scanned cell centers — mirror that source to
  build `closeCellCenters` from `creditedCells`) and pass them. Route mode has no server-fresh
  split on the minimap today (`freshCellCenters` source at that call site) — pass
  `freshCloseCellCenters = emptyList()` if the route screen doesn't fetch quality-tagged cells.

- [ ] **Step 6: AR ground quads = close tier only**

In `SpotScreen.kt`, the AR vector-quad feed (line ~594-596) switches from all credited cells to
close-tier cells — fog still lifts on any credit, so the AR read becomes: fog = unscanned, clear
ground = grazed, vivid quad = close (spec §4, zero Swift-protocol changes):

```kotlin
    LaunchedEffect(closeCellCenters) {
        arOverlay?.updateScannedCells(closeCellCenters)
    }
```

In route mode, find where `updateScannedCells` is fed (`grep -rn "updateScannedCells" composeApp/src/commonMain`)
and apply the same close-only filter at that call site.

- [ ] **Step 7: Verify**

Run: `cd site && npx tsc --noEmit` (baseline errors only), then
`./gradlew :composeApp:desktopTest && ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add site/src/app/api/map/coverage/route.ts \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt \
        composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ScanMinimapOverlay.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt
git commit -m "feat(coverage-quality): two-shade coverage on main map, minimap, and AR quads"
```

---

### Task 10: Two-phase guidance (cover → detail)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/EnvScanGuidance.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/EnvScanGuidancePhaseTest.kt` (create)

**Interfaces:**
- Produces:
  - `enum class GuidancePhase { COVER, DETAIL }`
  - `data class GuidancePick(val target: Pair<Double, Double>, val phase: GuidancePhase)`
  - `EnvScanGuidance.DETAIL_PHASE_COVERAGE_PERCENT = 80.0`
  - `fun EnvScanGuidance.pickTarget(current, uncredited, grazedNotClose, userLat, userLng, coveragePercent): GuidancePick?`

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/EnvScanGuidancePhaseTest.kt`:

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvScanGuidancePhaseTest {

    private val user = 41.8600 to -87.7200
    private val nearCell = 41.86005 to -87.7200   // ~5.5m north
    private val farCell = 41.86020 to -87.7200    // ~22m north

    @Test
    fun coverPhaseTargetsUncreditedWhileCoverageLow() {
        val pick = EnvScanGuidance.pickTarget(
            current = null,
            uncredited = listOf(farCell),
            grazedNotClose = listOf(nearCell),
            userLat = user.first, userLng = user.second,
            coveragePercent = 30.0,
        )
        assertEquals(GuidancePhase.COVER, pick?.phase)
        assertEquals(farCell, pick?.target)
    }

    @Test
    fun detailPhaseKicksInAtThreshold() {
        val pick = EnvScanGuidance.pickTarget(
            current = null,
            uncredited = listOf(farCell),
            grazedNotClose = listOf(nearCell),
            userLat = user.first, userLng = user.second,
            coveragePercent = EnvScanGuidance.DETAIL_PHASE_COVERAGE_PERCENT,
        )
        assertEquals(GuidancePhase.DETAIL, pick?.phase)
        assertEquals(nearCell, pick?.target)
    }

    @Test
    fun detailPhaseAlsoWhenNothingLeftToCover() {
        val pick = EnvScanGuidance.pickTarget(
            current = null,
            uncredited = emptyList(),
            grazedNotClose = listOf(nearCell),
            userLat = user.first, userLng = user.second,
            coveragePercent = 10.0,
        )
        assertEquals(GuidancePhase.DETAIL, pick?.phase)
    }

    @Test
    fun nothingLeftAnywhereReturnsNull() {
        assertNull(
            EnvScanGuidance.pickTarget(
                current = null,
                uncredited = emptyList(),
                grazedNotClose = emptyList(),
                userLat = user.first, userLng = user.second,
                coveragePercent = 100.0,
            )
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.EnvScanGuidancePhaseTest"`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

Add to `EnvScanGuidance.kt` (keep `pickFrontier` untouched — it becomes the shared primitive):

```kotlin
enum class GuidancePhase { COVER, DETAIL }

data class GuidancePick(val target: Pair<Double, Double>, val phase: GuidancePhase)
```

and inside the object:

```kotlin
    /** Corridor coverage% at which steering flips from cover-everything to
     *  detail-scan-the-grazed-areas (spec §4). */
    const val DETAIL_PHASE_COVERAGE_PERCENT = 80.0

    /**
     * Two-phase pick: while coverage is low and uncredited frontier remains,
     * steer like a coverage scan (phase COVER). Once the area is mostly
     * grazed — or nothing uncredited remains — steer at the nearest
     * grazed-but-not-close cluster like a 3D-scan app's "get closer" pass
     * (phase DETAIL). Same stickiness as [pickFrontier] in both phases.
     */
    fun pickTarget(
        current: Pair<Double, Double>?,
        uncredited: List<Pair<Double, Double>>,
        grazedNotClose: List<Pair<Double, Double>>,
        userLat: Double,
        userLng: Double,
        coveragePercent: Double,
    ): GuidancePick? {
        val coverDone = coveragePercent >= DETAIL_PHASE_COVERAGE_PERCENT || uncredited.isEmpty()
        if (!coverDone) {
            pickFrontier(current, uncredited, userLat, userLng)?.let {
                return GuidancePick(it, GuidancePhase.COVER)
            }
        }
        return pickFrontier(current, grazedNotClose, userLat, userLng)
            ?.let { GuidancePick(it, GuidancePhase.DETAIL) }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.EnvScanGuidance*"`
Expected: PASS (new + existing EnvScanGuidanceTest).

- [ ] **Step 5: Wire into SpotScreen**

Replace the guidance LaunchedEffect (line ~600-613) with:

```kotlin
    var guidanceTarget by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var guidancePhase by remember { mutableStateOf(GuidancePhase.COVER) }
    val coveragePercentValue by tracker.coveragePercent.collectAsState()
    LaunchedEffect(scanCellCenters, closeCellCenters, trackerReady, currentLocation) {
        if (!trackerReady) return@LaunchedEffect
        val uncredited = tracker.getUnscannedCells()
            .map { (row, col) -> tracker.cellCenterLatLng(row, col) }
        arOverlay?.updateFog(uncredited)
        val grazedNotClose = creditedCells
            .filterValues { it.quality == CoverageQuality.GRAZED }
            .keys.map { (row, col) -> tracker.cellCenterLatLng(row, col) }
        val loc = currentLocation
        val pick = loc?.let {
            EnvScanGuidance.pickTarget(
                current = guidanceTarget,
                uncredited = uncredited,
                grazedNotClose = grazedNotClose,
                userLat = it.lat, userLng = it.lng,
                coveragePercent = coveragePercentValue,
            )
        }
        guidanceTarget = pick?.target
        guidancePhase = pick?.phase ?: GuidancePhase.COVER
        if (arOverlay != null) {
            val target = pick?.target
            if (target != null) arOverlay.setGuidanceTarget(target.first, target.second)
            else arOverlay.clearGuidanceTarget()
        }
    }
```

(Imports: `GuidancePhase`. `creditedCells` exists from Task 5 Step 5.)

Then surface the DETAIL caption: find how the idle nudge / status chips reach the capture UI
(`grep -n "idleNudge" composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt`)
and thread a new `detailPhase: Boolean = false` param along the SAME path (declare in the capture
composable, forward through every wrapper — Global Constraints). Where the idle-nudge chip renders,
add a sibling chip with the same visual treatment shown when `detailPhase` is true and the idle
chip is not:

```kotlin
    if (detailPhase) {
        // Same chip composable/style the idle nudge uses at this call site:
        Text("Move closer — detail-scan the light areas")
    }
```

(Adopt the exact chip wrapper used beside it; the string is the deliverable.)

- [ ] **Step 6: Verify + commit**

Run: `./gradlew :composeApp:desktopTest && ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/EnvScanGuidance.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/platform/EnvScanGuidancePhaseTest.kt
git commit -m "feat(coverage-quality): two-phase guidance — cover the area, then detail-scan grazed cells"
```

---

### Task 11: Full verification + cleanup docs + ship

**Files:**
- Create: `docs/superpowers/specs/2026-07-06-scan-coverage-quality-cleanup.md` (one-off cleanup runbook)

- [ ] **Step 1: Full test sweep**

Run, expecting all green / baseline-only:

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
cd site && npx tsc --noEmit && cd ..
```

Then a full sim build (memory: purge stale frameworks first):

```bash
rm -rf composeApp/build/xcode-frameworks
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build
```

Expected: BUILD SUCCEEDED.

- [ ] **Step 2: Cleanup runbook for the 3 bad geotiles**

Create `docs/superpowers/specs/2026-07-06-scan-coverage-quality-cleanup.md`:

```markdown
# One-off cleanup: indoor-credited geotiles (2026-07-06)

Blake's indoor scan credited ~3 geotiles before this PR's fixes. After deploy:

1. In the app, tap each wrong tile → CoverageCellInfoCard shows its `cellId`.
2. From site/, with .env.local's DATABASE_URL:

    psql "$DATABASE_URL" <<'SQL'
    BEGIN;
    -- $CELLS: replace with the tapped cellIds, e.g. ('cell_a','cell_b','cell_c')
    DELETE FROM points_ledger
      WHERE reference_type = 'scan_coverage_event'
        AND reference_id IN (SELECT id FROM scan_coverage_events WHERE location_cell_id IN $CELLS);
    DELETE FROM scan_coverage_events WHERE location_cell_id IN $CELLS;
    DELETE FROM cell_coverage_summary WHERE location_cell_id IN $CELLS;
    COMMIT;
    SQL

3. Verify: the tiles return to fog on the map (pull-to-refresh / reopen).

MERGE ACTIONS for the PR itself:
- `cd site && npm run db:push` (new columns: scan_coverage_events.quality,
  cell_coverage_summary.best_quality / last_close_scanned_at)
- TestFlight build + device walk test: indoor open (gate flips, minimap
  un-credits, tiles never appear on main map), outdoor sweep (light shade),
  close pass (dark shade + AR quads appear), route lap completion still
  reachable, phase-2 "move closer" chip.
```

- [ ] **Step 3: Commit, push, draft PR**

```bash
git add docs/superpowers/specs/2026-07-06-scan-coverage-quality-cleanup.md
git commit -m "docs(coverage-quality): one-off geotile cleanup runbook + merge actions"
git push -u origin worktree-scan-coverage-quality
gh pr create --draft --title "Scan coverage quality: grazed/close tiers, raycast crediting, indoor retraction" \
  --body "Implements docs/superpowers/specs/2026-07-06-scan-coverage-quality-design.md ..."
```

PR body must list the MERGE ACTIONS from the runbook (db:push, TestFlight walk test, one-off SQL cleanup).

---

## Execution notes

- Task order: 1 → 3 → 2 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 (Task 2's test imports a Task 3 model).
- Tasks 7-8 (server) are independent of 4-6 (client) and may run in parallel worktrees if using
  subagent-driven development; Task 9 needs both.
- The auto-classifier blocks merging/readying self-authored PRs — leave the PR as draft for Blake.
- Do NOT run `npm run db:push` or any prod SQL from this worktree; those are merge actions for Blake.
```
