package com.gcc.app.platform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/**
 * Client-side estimate of the points a scan session is earning, for the live
 * AR HUD. Mirrors the server's per-meter rates (`site/src/lib/points-config.ts`:
 * new-area 10/m, stale-rescan 5/m, fresh 0) but is display-only — the server
 * ledger remains the source of truth and is what the completion screen shows.
 *
 * Each grid cell credits at most once per session (re-walking the same spot
 * would be classified fresh_rescan by the server after the first event lands).
 * Single segments are capped at [MAX_SEGMENT_METERS] to mirror the server's
 * GPS-jump plausibility rejection.
 */
class SessionPointsEstimator {

    companion object {
        const val NEW_AREA_POINTS_PER_METER = 10
        const val STALE_POINTS_PER_METER = 5
        const val MAX_SEGMENT_METERS = 50.0

        /**
         * Server-equivalent ~10m grid cell for spot mode, where no session
         * CoverageTracker grid exists. Matches `gridCell` in
         * `site/src/lib/world-observations.ts`.
         */
        fun gridCell10m(lat: Double, lng: Double): Pair<Int, Int> {
            val latStep = 10.0 / 111_320.0
            val lngStep = 10.0 / (111_320.0 * cos(lat * PI / 180.0))
            return floor(lat / latStep).toInt() to floor(lng / lngStep).toInt()
        }
    }

    private val creditedCells = mutableSetOf<Pair<Int, Int>>()

    var total: Int = 0
        private set

    /**
     * Credit a walked segment ending in [cell] whose production-ledger prior
     * is [priorState] (null = never scanned, "stale", or "fresh").
     * Returns the points delta (0 when nothing new was earned).
     */
    fun onSegment(cell: Pair<Int, Int>?, priorState: String?, meters: Double): Int {
        if (cell == null || meters <= 0.0) return 0
        if (!creditedCells.add(cell)) return 0
        val rate = when (priorState) {
            null -> NEW_AREA_POINTS_PER_METER
            "stale" -> STALE_POINTS_PER_METER
            else -> 0
        }
        val delta = (min(meters, MAX_SEGMENT_METERS) * rate).toInt()
        total += delta
        return delta
    }
}
