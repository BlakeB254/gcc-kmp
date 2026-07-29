package com.gcc.app.ui.map

import com.gcc.shared.model.MapCoverageCell
import kotlin.math.PI
import kotlin.math.cos

/** Server coverage cells are a ~10m grid; treat centers within this many
 *  meters (per axis) as adjacent — 1.4× the pitch absorbs jitter without
 *  bridging across a clean cell. */
private const val BLOB_ADJACENCY_METERS = 14.0

/**
 * The contiguous run of problem cells sharing the tapped cell's COLOR
 * (severity tier) — "the combined area on the map that shares that color"
 * (Blake 2026-07-28): tapping any cell of an amber blob answers for the
 * whole blob, not the single 10m tile under the finger. BFS over per-axis
 * adjacency; a non-problem tapped cell returns just itself.
 */
fun sameColorBlobCells(tapped: MapCoverageCell, all: List<MapCoverageCell>): List<MapCoverageCell> {
    fun tierOf(cell: MapCoverageCell): ProblemSeverity? =
        if (cell.litterCount > 0 || cell.litterWeightGrams > 0.0) {
            problemSeverityTier(cell.litterCount, cell.litterWeightGrams)
        } else null

    val tapTier = tierOf(tapped) ?: return listOf(tapped)
    val candidates = all.filter { tierOf(it) == tapTier }
    if (candidates.isEmpty()) return listOf(tapped)

    val lngScale = cos(tapped.lat * PI / 180.0)
    fun adjacent(a: MapCoverageCell, b: MapCoverageCell): Boolean {
        val dLatM = kotlin.math.abs(a.lat - b.lat) * 111_320.0
        val dLngM = kotlin.math.abs(a.lng - b.lng) * 111_320.0 * lngScale
        return dLatM <= BLOB_ADJACENCY_METERS && dLngM <= BLOB_ADJACENCY_METERS
    }

    val blob = LinkedHashMap<String, MapCoverageCell>()
    blob[tapped.locationCellId] = tapped
    val queue = ArrayDeque<MapCoverageCell>()
    queue.add(tapped)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (candidate in candidates) {
            if (candidate.locationCellId in blob) continue
            if (adjacent(current, candidate)) {
                blob[candidate.locationCellId] = candidate
                queue.add(candidate)
            }
        }
    }
    return blob.values.toList()
}
