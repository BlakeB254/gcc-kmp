package com.gcc.shared.geo

import kotlin.math.floor

/**
 * Meters-per-degree constants at Chicago latitude — MUST match
 * site/src/lib/geo-utils.ts's `METERS_PER_DEG_LAT`/`METERS_PER_DEG_LNG`
 * exactly (read from that file, not approximated). Any drift here silently
 * breaks agreement between client-computed grid cell ids and the server's
 * `gridCell()` in site/src/lib/world-observations.ts.
 */
const val METERS_PER_DEG_LAT = 111_000.0
const val METERS_PER_DEG_LNG = 82_300.0

private const val DEFAULT_GRID_CELL_SIZE_METERS = 10.0

/**
 * Kotlin mirror of the server's `gridCell(lat, lng)` in
 * site/src/lib/world-observations.ts: a deterministic ~10m grid cell key,
 * format `"${latIdx}:${lngIdx}"`. Used by
 * `RouteScanViewModel.coveredCellIdsForSubmission()` to translate the
 * client's [com.gcc.app.platform.CoverageTracker] scanned-cell set into ids
 * the server's cell-supersede logic (Pillar 8 task 1) can match directly —
 * the formula shape and constants must stay bit-for-bit identical to the TS
 * version, since both sides derive from the same physical constants and any
 * divergence (e.g. rounding instead of floor, or a different constant)
 * would silently produce ids that never match server-side cells.
 */
fun gridCell10m(lat: Double, lng: Double, cellSizeMeters: Double = DEFAULT_GRID_CELL_SIZE_METERS): String {
    val latStep = cellSizeMeters / METERS_PER_DEG_LAT
    val lngStep = cellSizeMeters / METERS_PER_DEG_LNG
    val latIdx = floor(lat / latStep).toLong()
    val lngIdx = floor(lng / lngStep).toLong()
    return "$latIdx:$lngIdx"
}
