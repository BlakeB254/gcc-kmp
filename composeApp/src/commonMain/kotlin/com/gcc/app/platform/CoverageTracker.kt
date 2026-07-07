package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.geo.pointInPolygon
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlin.math.PI
import kotlin.math.cos

/**
 * Scan quality tier for a credited cell. Order matters: SEEN < GRAZED < CLOSE
 * (ordinal upgrade check via `>=` in [CoverageTracker.creditCell]) — SEEN
 * MUST stay ordinal 0. CellCredit/this enum are in-memory only (never
 * persisted or serialized — verified: no @Serializable on either type, no
 * DB/wire encoding references CoverageQuality), so this reorder carries no
 * migration risk; the wire protocol's quality strings ('seen'|'grazed'|
 * 'close') are a separate, independently-versioned representation.
 */
enum class CoverageQuality { SEEN, GRAZED, CLOSE }

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

/**
 * Grid-based coverage tracker for the Scout lap fog-of-war system.
 *
 * The route bounding box is divided into [cellSizeMeters] x [cellSizeMeters] cells.
 * As the worker moves, cells within [scanRadiusMeters] are marked scanned.
 * Only cells within [corridorWidthMeters] of the route line are counted toward
 * the total, so coverage% reflects route corridor coverage, not random wandering.
 */
class CoverageTracker {

    companion object {
        const val cellSizeMeters = 3.0
        const val scanRadiusMeters = 5.0
        const val corridorWidthMeters = 15.0

        /** Raycast hits at or under this surface distance credit CLOSE (detail-scan tier). */
        const val CLOSE_RANGE_METERS = 4.0
        /** Raycast hits at or under this surface distance (and beyond CLOSE) credit GRAZED. */
        const val GRAZE_RANGE_METERS = 10.0
        /**
         * Raycast hits at or under this surface distance (and beyond GRAZE)
         * credit SEEN — the camera looked at it (a cross-street storefront
         * face) even though the worker never walked there. Beyond this, the
         * fan drops the hit entirely (fan ceiling).
         */
        const val SEEN_RANGE_METERS = 55.0
    }

    private fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

    // Grid dimensions
    private var gridRows = 0
    private var gridCols = 0

    // Bounding box origin (SW corner)
    private var originLat = 0.0
    private var originLng = 0.0

    // Degrees per meter at the area latitude
    private var latPerMeter = 0.0
    private var lngPerMeter = 0.0

    // Cells that belong to the route corridor
    private var corridorCells: Set<Pair<Int, Int>> = emptySet()

    // Total corridor cells — cached to avoid repeated size checks
    private var totalCells = 0

    // Mutable state: quality-aware credit map is the source of truth; the
    // legacy scanned SET stays as a derived mirror for existing consumers
    // (fog, minimap punch-out, corridor fill).
    private val _creditedCells = MutableStateFlow<Map<Pair<Int, Int>, CellCredit>>(emptyMap())
    val creditedCells: StateFlow<Map<Pair<Int, Int>, CellCredit>> = _creditedCells.asStateFlow()

    // GRAZED+ keys only (SEEN excluded — sight isn't "scanned" for fog/lap
    // purposes, spec Pillar 7 §AR fog). Existing consumers (fog reveal via
    // getUnscannedCells, minimap punch-out, corridor fill) all key off this.
    private val _scannedCells = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    val scannedCells: StateFlow<Set<Pair<Int, Int>>> = _scannedCells.asStateFlow()

    // SEEN-only cells (credited but not GRAZED+) — their own accessor so
    // renderers can draw the faintest "sighted at a distance" wash without
    // it counting as scanned anywhere fog/coverage% is computed.
    private val _seenCells = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    val seenCells: StateFlow<Set<Pair<Int, Int>>> = _seenCells.asStateFlow()

    private val _coveragePercent = MutableStateFlow(0.0)
    val coveragePercent: StateFlow<Double> = _coveragePercent.asStateFlow()

    private val _closePercent = MutableStateFlow(0.0)
    val closePercent: StateFlow<Double> = _closePercent.asStateFlow()

    // ── Initialisation ───────────────────────────────────────────────

    /**
     * Build the grid from route waypoints.
     * Typically called with [RouteCheckpoint] coordinates in sequence order.
     */
    fun initFromRoute(waypoints: List<Coordinates>) {
        if (waypoints.isEmpty()) return

        // Compute bounding box with a 20m padding
        val minLat = waypoints.minOf { it.lat }
        val maxLat = waypoints.maxOf { it.lat }
        val minLng = waypoints.minOf { it.lng }
        val maxLng = waypoints.maxOf { it.lng }

        // Approx metres per degree at this latitude
        latPerMeter = 1.0 / 111_320.0
        lngPerMeter = 1.0 / (111_320.0 * cos(minLat * PI / 180.0))

        val paddingLat = 20.0 * latPerMeter
        val paddingLng = 20.0 * lngPerMeter

        originLat = minLat - paddingLat
        originLng = minLng - paddingLng

        val spanLat = (maxLat + paddingLat) - originLat
        val spanLng = (maxLng + paddingLng) - originLng

        gridRows = (spanLat / (cellSizeMeters * latPerMeter)).toInt() + 1
        gridCols = (spanLng / (cellSizeMeters * lngPerMeter)).toInt() + 1

        // Identify corridor cells: cells whose centre is within corridorWidthMeters
        // of at least one segment of the route polyline.
        val corridor = mutableSetOf<Pair<Int, Int>>()
        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val cellLat = originLat + (row + 0.5) * cellSizeMeters * latPerMeter
                val cellLng = originLng + (col + 0.5) * cellSizeMeters * lngPerMeter
                if (isNearRoute(cellLat, cellLng, waypoints)) {
                    corridor.add(Pair(row, col))
                }
            }
        }
        corridorCells = corridor
        totalCells = corridor.size
        publish(emptyMap())
    }

    /**
     * Build the grid from a block's authored boundaries instead of route-line
     * proximity: the required scan area is every cell whose center lies INSIDE
     * the outer boundary and OUTSIDE every inner-boundary island (private
     * property / built stretches are excluded islands; vacant stretches have
     * no island and stay required). Route-v2 design: corridor = between-lines
     * band, coverage% = fill of that band.
     *
     * Falls back to [initFromRoute] semantics when [outerRing] is degenerate
     * (fewer than 3 vertices), so callers can pass whatever the route carries.
     */
    fun initFromBlock(
        outerRing: List<Coordinates>,
        innerRings: List<List<Coordinates>>,
        fallbackWaypoints: List<Coordinates> = emptyList(),
    ) {
        if (outerRing.size < 3) {
            initFromRoute(fallbackWaypoints.ifEmpty { outerRing })
            return
        }

        val minLat = outerRing.minOf { it.lat }
        val maxLat = outerRing.maxOf { it.lat }
        val minLng = outerRing.minOf { it.lng }
        val maxLng = outerRing.maxOf { it.lng }

        latPerMeter = 1.0 / 111_320.0
        lngPerMeter = 1.0 / (111_320.0 * cos(minLat * PI / 180.0))

        val paddingLat = 20.0 * latPerMeter
        val paddingLng = 20.0 * lngPerMeter

        originLat = minLat - paddingLat
        originLng = minLng - paddingLng

        val spanLat = (maxLat + paddingLat) - originLat
        val spanLng = (maxLng + paddingLng) - originLng

        gridRows = (spanLat / (cellSizeMeters * latPerMeter)).toInt() + 1
        gridCols = (spanLng / (cellSizeMeters * lngPerMeter)).toInt() + 1

        val outerPairs = outerRing.map { it.lat to it.lng }
        val innerPairs = innerRings.filter { it.size >= 3 }.map { ring -> ring.map { it.lat to it.lng } }

        val corridor = mutableSetOf<Pair<Int, Int>>()
        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val cellLat = originLat + (row + 0.5) * cellSizeMeters * latPerMeter
                val cellLng = originLng + (col + 0.5) * cellSizeMeters * lngPerMeter
                if (!pointInPolygon(cellLat, cellLng, outerPairs)) continue
                if (innerPairs.any { pointInPolygon(cellLat, cellLng, it) }) continue
                corridor.add(Pair(row, col))
            }
        }
        corridorCells = corridor
        totalCells = corridor.size
        publish(emptyMap())
    }

    /** True when the required area was built from block boundaries (not line proximity). */
    val hasBlockCorridor: Boolean
        get() = totalCells > 0

    /**
     * Geographic origin (lat, lng) the cell lattice is phased to — the padded
     * SW corner every [cellCenterLatLng] is derived from. Null before init.
     * The AR overlays phase the ground grid to this point so the on-camera
     * grid registers with the minimap's cell grid.
     */
    fun latticeOrigin(): Pair<Double, Double>? =
        if (latPerMeter == 0.0 || lngPerMeter == 0.0) null else originLat to originLng

    /** Scanned cells clipped to the required corridor — the minimap fill set. */
    fun getScannedCorridorCells(): Set<Pair<Int, Int>> =
        _scannedCells.value intersect corridorCells

    /** All required-corridor cell centers, for map fill rendering. */
    fun getCorridorCenters(): List<Pair<Double, Double>> =
        corridorCells.map { (row, col) -> cellCenterLatLng(row, col) }

    // ── Grid coordinate projection ───────────────────────────────────

    /**
     * Inverse of the grid projection used throughout this class (see
     * [updatePosition], [updateWithRaycastHits], [getUnscannedCenters]) —
     * returns the real-world lat/lng center of a given grid cell, for
     * AR-space anchoring of the fog-of-war overlay (Task 15).
     */
    fun cellCenterLatLng(row: Int, col: Int): Pair<Double, Double> {
        val lat = originLat + (row + 0.5) * cellSizeMeters * latPerMeter
        val lng = originLng + (col + 0.5) * cellSizeMeters * lngPerMeter
        return lat to lng
    }

    /**
     * Forward projection: the grid cell containing (lat, lng), or null when
     * the point falls outside the grid (or the grid isn't initialised yet).
     * Used to map production coverage cells ([seedScannedCells]) and world
     * observations onto this session's grid.
     */
    fun cellForLatLng(lat: Double, lng: Double): Pair<Int, Int>? {
        if (gridRows == 0 || gridCols == 0 || latPerMeter == 0.0 || lngPerMeter == 0.0) return null
        val row = ((lat - originLat) / (cellSizeMeters * latPerMeter)).toInt()
        val col = ((lng - originLng) / (cellSizeMeters * lngPerMeter)).toInt()
        if (row < 0 || row >= gridRows || col < 0 || col >= gridCols) return null
        return row to col
    }

    /**
     * Seed cells that the production coverage ledger already marks fresh:
     * they are excluded from the fog overlay (nothing left to reveal there)
     * but do NOT count toward this session's [coveragePercent] — scout
     * completion is still earned by the worker's own walk.
     */
    fun seedScannedCells(cells: Set<Pair<Int, Int>>) {
        preSeededCells = preSeededCells + cells
    }

    /** Cells already fresh in the production ledger — fog-exempt only. */
    private var preSeededCells: Set<Pair<Int, Int>> = emptySet()

    // ── Quality-aware credit map ──────────────────────────────────────

    /**
     * Recompute all derived flows from a new credit map. GRAZED+ defines
     * "scanned" everywhere (fog, coveragePercent, lap-advance) — SEEN cells
     * never inflate lap completion (spec Pillar 7: sight-at-distance still
     * needs walking). closePercent is unchanged (CLOSE-only, as before).
     */
    private fun publish(map: Map<Pair<Int, Int>, CellCredit>) {
        _creditedCells.value = map
        val grazedPlus = map.filterValues { it.quality >= CoverageQuality.GRAZED }.keys
        _scannedCells.value = grazedPlus
        _seenCells.value = map.filterValues { it.quality == CoverageQuality.SEEN }.keys
        if (totalCells > 0) {
            val scannedCorridor = grazedPlus.count { it in corridorCells }
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
            if (hit.distanceMeters > SEEN_RANGE_METERS) continue
            val cell = cellForLatLng(hit.lat, hit.lng) ?: continue
            val quality = when {
                hit.distanceMeters <= CLOSE_RANGE_METERS -> CoverageQuality.CLOSE
                hit.distanceMeters <= GRAZE_RANGE_METERS -> CoverageQuality.GRAZED
                else -> CoverageQuality.SEEN
            }
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

    // ── Position update ──────────────────────────────────────────────

    /**
     * Called each time the worker's GPS position changes.
     * Marks all cells within [scanRadiusMeters] as scanned and recomputes coverage %.
     */
    fun updatePosition(lat: Double, lng: Double, nowMs: Long = nowEpochMs()) {
        if (totalCells == 0) return

        // Convert scan radius to grid cell units
        val radiusRows = (scanRadiusMeters * latPerMeter / (cellSizeMeters * latPerMeter)).toInt() + 1
        val radiusCols = (scanRadiusMeters * lngPerMeter / (cellSizeMeters * lngPerMeter)).toInt() + 1

        val currentRow = ((lat - originLat) / (cellSizeMeters * latPerMeter)).toInt()
        val currentCol = ((lng - originLng) / (cellSizeMeters * lngPerMeter)).toInt()

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
    }

    // ── Unscanned cell centers ────────────────────────────────────────

    /**
     * Returns lat/lng centers of corridor cells that have not yet been scanned.
     * Samples every 5th cell to avoid returning thousands of points.
     */
    fun getUnscannedCenters(): List<Pair<Double, Double>> {
        val scanned = _scannedCells.value
        val unscanned = mutableListOf<Pair<Double, Double>>()
        var i = 0
        for (cell in corridorCells) {
            if (cell !in scanned) {
                if (i % 5 == 0) {
                    val (row, col) = cell
                    val lat = originLat + (row + 0.5) * cellSizeMeters * latPerMeter
                    val lng = originLng + (col + 0.5) * cellSizeMeters * lngPerMeter
                    unscanned.add(Pair(lat, lng))
                }
                i++
            }
        }
        return unscanned
    }

    /** Corridor cells (row, col) not yet scanned — drives ArRouteOverlay's fog node set. */
    fun getUnscannedCells(): Set<Pair<Int, Int>> =
        corridorCells - _scannedCells.value - preSeededCells

    // ── Reset ────────────────────────────────────────────────────────

    fun reset() {
        _creditedCells.value = emptyMap()
        _scannedCells.value = emptySet()
        _seenCells.value = emptySet()
        _coveragePercent.value = 0.0
        _closePercent.value = 0.0
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Returns true if (lat, lng) is within corridorWidthMeters of any route segment. */
    private fun isNearRoute(lat: Double, lng: Double, waypoints: List<Coordinates>): Boolean {
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            if (distanceToSegment(lat, lng, a.lat, a.lng, b.lat, b.lng) <= corridorWidthMeters) {
                return true
            }
        }
        // Also check proximity to each individual waypoint
        return waypoints.any { wp ->
            GeofenceVerification.distanceMeters(lat, lng, wp.lat, wp.lng) <= corridorWidthMeters
        }
    }

    /**
     * Approximate distance from point P to line segment AB in metres.
     * Uses projected parameter t clamped to [0, 1].
     */
    private fun distanceToSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Double {
        val abLat = bLat - aLat
        val abLng = bLng - aLng
        val apLat = pLat - aLat
        val apLng = pLng - aLng

        val abLenSq = abLat * abLat + abLng * abLng
        if (abLenSq == 0.0) {
            return GeofenceVerification.distanceMeters(pLat, pLng, aLat, aLng)
        }

        val t = ((apLat * abLat + apLng * abLng) / abLenSq).coerceIn(0.0, 1.0)
        val closestLat = aLat + t * abLat
        val closestLng = aLng + t * abLng
        return GeofenceVerification.distanceMeters(pLat, pLng, closestLat, closestLng)
    }
}
