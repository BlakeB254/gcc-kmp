package com.gcc.app.ui.map

import kotlin.math.PI
import kotlin.math.cos

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
        val latPerMeter = 1.0 / 111_320.0
        val lngPerMeter = 1.0 / (111_320.0 * cos(lat * PI / 180.0))
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
