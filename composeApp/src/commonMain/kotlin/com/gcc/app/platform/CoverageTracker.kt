package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

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
    }

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

    // Mutable state
    private val _scannedCells = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    val scannedCells: StateFlow<Set<Pair<Int, Int>>> = _scannedCells.asStateFlow()

    private val _coveragePercent = MutableStateFlow(0.0)
    val coveragePercent: StateFlow<Double> = _coveragePercent.asStateFlow()

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
        _scannedCells.value = emptySet()
        _coveragePercent.value = 0.0
    }

    // ── Grid coordinate projection ───────────────────────────────────

    /**
     * Inverse of the grid projection used throughout this class (see
     * [updatePosition], [updateWithFrustum], [getUnscannedCenters]) —
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

    // ── Position update ──────────────────────────────────────────────

    /**
     * Called each time the worker's GPS position changes.
     * Marks all cells within [scanRadiusMeters] as scanned and recomputes coverage %.
     */
    fun updatePosition(lat: Double, lng: Double) {
        if (totalCells == 0) return

        // Convert scan radius to grid cell units
        val radiusRows = (scanRadiusMeters * latPerMeter / (cellSizeMeters * latPerMeter)).toInt() + 1
        val radiusCols = (scanRadiusMeters * lngPerMeter / (cellSizeMeters * lngPerMeter)).toInt() + 1

        val currentRow = ((lat - originLat) / (cellSizeMeters * latPerMeter)).toInt()
        val currentCol = ((lng - originLng) / (cellSizeMeters * lngPerMeter)).toInt()

        val newScanned = _scannedCells.value.toMutableSet()
        for (dr in -radiusRows..radiusRows) {
            for (dc in -radiusCols..radiusCols) {
                val r = currentRow + dr
                val c = currentCol + dc
                if (r < 0 || r >= gridRows || c < 0 || c >= gridCols) continue
                if (newScanned.contains(Pair(r, c))) continue  // already scanned — skip distance check

                val cellLat = originLat + (r + 0.5) * cellSizeMeters * latPerMeter
                val cellLng = originLng + (c + 0.5) * cellSizeMeters * lngPerMeter

                val dist = GeofenceVerification.distanceMeters(lat, lng, cellLat, cellLng)
                if (dist <= scanRadiusMeters) {
                    newScanned.add(Pair(r, c))
                }
            }
        }

        _scannedCells.value = newScanned

        val scannedCorridor = newScanned.count { it in corridorCells }
        _coveragePercent.value = (scannedCorridor.toDouble() / totalCells * 100.0).coerceIn(0.0, 100.0)
    }

    // ── Frustum (AR-tier) coverage ───────────────────────────────────

    /**
     * AR tier: clears fog based on the camera frustum rather than a GPS radius.
     *
     * Marks corridor cells that are:
     *  1. Within [rangeMeters] of the user, AND
     *  2. Within [fovDegrees]/2 of the [headingDegrees] direction.
     *
     * @param lat user latitude
     * @param lng user longitude
     * @param headingDegrees camera heading (0=north, 90=east)
     * @param fovDegrees horizontal field of view (default 63° for iPhone wide lens)
     * @param rangeMeters maximum frustum reach in metres (default 15m)
     */
    fun updateWithFrustum(
        lat: Double,
        lng: Double,
        headingDegrees: Float,
        fovDegrees: Float = 63f,
        rangeMeters: Double = 15.0,
    ) {
        if (totalCells == 0) return

        val halfFov = fovDegrees / 2.0
        val radiusRows = (rangeMeters * latPerMeter / (cellSizeMeters * latPerMeter)).toInt() + 1
        val radiusCols = (rangeMeters * lngPerMeter / (cellSizeMeters * lngPerMeter)).toInt() + 1

        val currentRow = ((lat - originLat) / (cellSizeMeters * latPerMeter)).toInt()
        val currentCol = ((lng - originLng) / (cellSizeMeters * lngPerMeter)).toInt()

        val newScanned = _scannedCells.value.toMutableSet()
        for (dr in -radiusRows..radiusRows) {
            for (dc in -radiusCols..radiusCols) {
                val r = currentRow + dr
                val c = currentCol + dc
                if (r < 0 || r >= gridRows || c < 0 || c >= gridCols) continue
                if (newScanned.contains(Pair(r, c))) continue  // already scanned — skip

                val cellLat = originLat + (r + 0.5) * cellSizeMeters * latPerMeter
                val cellLng = originLng + (c + 0.5) * cellSizeMeters * lngPerMeter

                val dist = GeofenceVerification.distanceMeters(lat, lng, cellLat, cellLng)
                if (dist > rangeMeters) continue

                // Compute bearing from user to cell and check it's inside the frustum cone
                val bearing = AiDirectionEngine.bearingTo(lat, lng, cellLat, cellLng)
                val angularDiff = abs(
                    AiDirectionEngine.normalizeDegrees(bearing - headingDegrees.toDouble())
                )
                if (angularDiff <= halfFov) {
                    newScanned.add(Pair(r, c))
                }
            }
        }

        _scannedCells.value = newScanned

        val scannedCorridor = newScanned.count { it in corridorCells }
        _coveragePercent.value = (scannedCorridor.toDouble() / totalCells * 100.0).coerceIn(0.0, 100.0)
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
        _scannedCells.value = emptySet()
        _coveragePercent.value = 0.0
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
