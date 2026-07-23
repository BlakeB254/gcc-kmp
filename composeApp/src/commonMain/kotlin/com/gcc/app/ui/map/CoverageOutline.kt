package com.gcc.app.ui.map

import com.gcc.app.platform.METERS_PER_DEG_LAT
import com.gcc.app.platform.lngScaleFor
import kotlin.math.roundToInt

/**
 * Traces the OUTLINE(S) of a set of coverage-grid cells as closed, smoothed
 * rings — the "organic swept territory" look (Pillar 7) instead of the old
 * per-cell rect grid ("no graph paper").
 *
 * Algorithm (standard raster-mask boundary tracing, sometimes called
 * "marching squares" in this codebase's shorthand): every side of a filled
 * cell whose neighbor on that side is NOT filled contributes one directed
 * unit edge in grid-CORNER space, oriented consistently (top: L→R, right:
 * T→B, bottom: R→L, left: B→T). Chaining these edges tail-to-head traces
 * every closed boundary loop in the cell set — an ISOLATED island traces one
 * way, and a HOLE inside a filled region (a cell/cluster excluded from the
 * set but fully surrounded) traces the opposite rotational sense, so holes
 * come back as their OWN separate ring rather than being silently merged
 * into the enclosing outline. Callers that need true GeoJSON Polygon holes
 * (exterior + interior rings in one Feature) can group the returned rings by
 * point-in-polygon containment; callers that just need to FILL the shape
 * (e.g. a Compose Canvas Path) can draw every returned ring into one Path
 * with an even-odd fill rule, which renders holes correctly with zero extra
 * bookkeeping.
 *
 * Each ring is closed (first point == last point) and gets one pass of
 * Chaikin corner-cutting so the silhouette reads as swept ground rather than
 * a blocky cell lattice.
 *
 * @param cells cell (row, col) keys credited at whatever tier the caller is
 *   tracing (e.g. GRAZED+ for one collection, CLOSE for another) — callers
 *   trace one tier's cell set at a time, same as the rest of the coverage
 *   rendering pipeline.
 * @param cellCenter maps ANY (row, col) — not just members of [cells] — to
 *   its real-world (lat, lng) center. Must be a stable, pure grid projection
 *   (the tracer calls it for cells bordering the set too, to derive shared
 *   corner positions).
 * @param cellSizeMeters the grid's cell size, used to offset a cell's center
 *   into its corners (same fixed meters-per-degree approximation used
 *   throughout this codebase's map math).
 */
fun traceCoverageOutlines(
    cells: Set<Pair<Int, Int>>,
    cellCenter: (Int, Int) -> Pair<Double, Double>,
    cellSizeMeters: Double,
): List<List<Pair<Double, Double>>> {
    if (cells.isEmpty()) return emptyList()

    // Grid-corner (vertex) space: vertex (r, c) is the TOP-LEFT corner of
    // cell (r, c) (and so also the bottom-left of (r-1,c), etc.) — a pure
    // function of cellCenter, valid for any integer (r, c) whether or not
    // that cell is credited.
    fun vertexLatLng(vr: Int, vc: Int): Pair<Double, Double> {
        val (lat, lng) = cellCenter(vr, vc)
        val latPerMeter = 1.0 / METERS_PER_DEG_LAT
        val lngPerMeter = 1.0 / (METERS_PER_DEG_LAT * lngScaleFor(lat))
        val halfLat = cellSizeMeters / 2.0 * latPerMeter
        val halfLng = cellSizeMeters / 2.0 * lngPerMeter
        return (lat - halfLat) to (lng - halfLng)
    }

    val edgesFrom = mutableMapOf<Pair<Int, Int>, MutableList<Pair<Int, Int>>>()
    fun addEdge(from: Pair<Int, Int>, to: Pair<Int, Int>) {
        edgesFrom.getOrPut(from) { mutableListOf() }.add(to)
    }
    for ((r, c) in cells) {
        if ((r - 1 to c) !in cells) addEdge(r to c, r to c + 1)             // TOP: L→R
        if ((r to c + 1) !in cells) addEdge(r to c + 1, r + 1 to c + 1)     // RIGHT: T→B
        if ((r + 1 to c) !in cells) addEdge(r + 1 to c + 1, r + 1 to c)     // BOTTOM: R→L
        if ((r to c - 1) !in cells) addEdge(r + 1 to c, r to c)             // LEFT: B→T
    }

    val visited = mutableSetOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
    val vertexRings = mutableListOf<List<Pair<Int, Int>>>()
    for (start in edgesFrom.keys.toList()) {
        for (firstNext in edgesFrom[start].orEmpty()) {
            val firstEdge = start to firstNext
            if (firstEdge in visited) continue
            val ring = mutableListOf(start)
            var current = start
            var next = firstNext
            var guard = 0
            // Bounded, not infinite: a malformed/checkerboard-adjacency input
            // (a vertex with no unvisited outgoing edge) breaks out early
            // rather than looping forever; the partial ring is then dropped
            // by the closure check below.
            while (guard++ < 200_000) {
                visited.add(current to next)
                ring.add(next)
                if (next == start) break
                current = next
                val candidate = edgesFrom[current].orEmpty().firstOrNull { (current to it) !in visited }
                if (candidate == null) break
                next = candidate
            }
            if (ring.size >= 5 && ring.first() == ring.last()) vertexRings.add(ring)
        }
    }

    return vertexRings.map { ring -> chaikinSmooth(ring.map { (vr, vc) -> vertexLatLng(vr, vc) }) }
}

/**
 * One pass of Chaikin corner-cutting on a CLOSED ring (first == last):
 * every edge P→Q is replaced by two points at 1/4 and 3/4 along it, rounding
 * the blocky cell-grid silhouette into a swept-looking outline. Rings under
 * 3 unique points are returned unchanged (nothing to smooth).
 */
internal fun chaikinSmooth(ring: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    val open = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
    if (open.size < 3) return ring
    val smoothed = ArrayList<Pair<Double, Double>>(open.size * 2 + 1)
    for (i in open.indices) {
        val p0 = open[i]
        val p1 = open[(i + 1) % open.size]
        smoothed.add((p0.first + 0.25 * (p1.first - p0.first)) to (p0.second + 0.25 * (p1.second - p0.second)))
        smoothed.add((p0.first + 0.75 * (p1.first - p0.first)) to (p0.second + 0.75 * (p1.second - p0.second)))
    }
    smoothed.add(smoothed.first())
    return smoothed
}

/**
 * A fixed quantization lattice anchored at ([anchorLat], [anchorLng]) with
 * [cellSizeMeters] spacing — shared by every caller that re-quantizes lat/lng
 * cell centers into integer (row, col) keys for [traceCoverageOutlines]
 * (ScanMinimapOverlay's session-tier tracer, GeoJsonLayers' outline mode).
 *
 * The anchor MUST be stable across repeated calls for a given cell set: it
 * does not have to be a real cell center or aligned to any particular grid,
 * but if it moves between calls (e.g. a live GPS fix or a panning map camera
 * center), every cell's rounded (row, col) bucket shifts in lockstep, which
 * snaps the whole traced shape by up to a cell width relative to anything
 * drawn from raw, non-quantized lat/lng. Callers own picking a stable anchor
 * (e.g. the first credited cell, or a value locked at first composition).
 */
class CellLattice(private val anchorLat: Double, private val anchorLng: Double, private val cellSizeMeters: Double) {
    private val latPerMeter = 1.0 / METERS_PER_DEG_LAT
    private val lngPerMeter = 1.0 / (METERS_PER_DEG_LAT * lngScaleFor(anchorLat))

    fun toRowCol(lat: Double, lng: Double): Pair<Int, Int> =
        ((lat - anchorLat) / (cellSizeMeters * latPerMeter)).roundToInt() to
            ((lng - anchorLng) / (cellSizeMeters * lngPerMeter)).roundToInt()

    fun cellCenter(row: Int, col: Int): Pair<Double, Double> =
        (anchorLat + row * cellSizeMeters * latPerMeter) to (anchorLng + col * cellSizeMeters * lngPerMeter)
}

/**
 * Convenience combining [CellLattice] quantization with [traceCoverageOutlines]:
 * re-quantizes [cellCenters] into integer (row, col) keys around the given
 * stable anchor and traces the union into smoothed rings. See [CellLattice]
 * for the anchor-stability requirement — every caller of this function
 * (ScanMinimapOverlay's session tiers, GeoJsonLayers' outline mode) shares
 * this one quantize-then-trace path instead of reimplementing it.
 */
fun quantizeAndTraceOutlines(
    cellCenters: List<Pair<Double, Double>>,
    cellSizeMeters: Double,
    anchorLat: Double,
    anchorLng: Double,
): List<List<Pair<Double, Double>>> {
    if (cellCenters.isEmpty()) return emptyList()
    val lattice = CellLattice(anchorLat, anchorLng, cellSizeMeters)
    val cells = cellCenters.map { (lat, lng) -> lattice.toRowCol(lat, lng) }.toSet()
    return traceCoverageOutlines(cells, lattice::cellCenter, cellSizeMeters)
}

/**
 * Morphological closing (radius-1 dilate, then erode, 8-neighborhood): seals
 * gaps of up to two cells between credited regions while keeping every input
 * cell (closing is a superset of its input) and leaving genuinely separate
 * areas (3+ cells apart) as separate islands.
 *
 * Why it exists: the AR raycast fan credits only the 15 cells its discrete
 * rays LAND in per tick. Past ~12m, adjacent rays are further apart than one
 * 3m grid cell, so a swept stretch of ground comes back as sparse single-cell
 * columns. Those are sampling seams, not unscanned ground — the fan swept
 * straight across them. Tracing the raw set outlines every column as its own
 * capsule (field report, TestFlight 161: the live minimap read as "streaks
 * that never connect" while the walk-credited summary blob was contiguous).
 */
fun closeCellGaps(cells: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
    if (cells.isEmpty()) return cells
    val dilated = mutableSetOf<Pair<Int, Int>>()
    for ((r, c) in cells) {
        for (dr in -1..1) for (dc in -1..1) dilated.add(r + dr to c + dc)
    }
    return dilated.filterTo(mutableSetOf()) { (r, c) ->
        (-1..1).all { dr -> (-1..1).all { dc -> (r + dr to c + dc) in dilated } }
    }
}

/**
 * The session's swept-territory silhouette: [quantizeAndTraceOutlines] with a
 * [closeCellGaps] pass between quantization and tracing. This is THE shape
 * both the live scan minimap's frontier (fill + bright edge + veil punch-out)
 * and the Scan Session Complete summary map trace from the session's GRAZED+
 * cells — one shared path so the live map and the completion map can never
 * disagree about what "the area you covered" looks like.
 */
fun traceSessionSilhouette(
    cellCenters: List<Pair<Double, Double>>,
    cellSizeMeters: Double,
    anchorLat: Double,
    anchorLng: Double,
): List<List<Pair<Double, Double>>> {
    if (cellCenters.isEmpty()) return emptyList()
    val lattice = CellLattice(anchorLat, anchorLng, cellSizeMeters)
    val cells = closeCellGaps(cellCenters.map { (lat, lng) -> lattice.toRowCol(lat, lng) }.toSet())
    return traceCoverageOutlines(cells, lattice::cellCenter, cellSizeMeters)
}
