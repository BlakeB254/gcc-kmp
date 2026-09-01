package com.gcc.app.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Capture coaching v2 (plan Task 1.7; capture-protocol evidence in
 * docs/plans/2026-08-19-scan-to-world-pipeline-and-ios-release-plan.md §2):
 * pure path-shape advisories for reconstruction-quality walking.
 *
 * - TURN: the trailing [STRAIGHT_WINDOW_METERS] of path is nearly straight —
 *   forward-only motion is the recon pipeline's weakest case (row-house
 *   aliasing, feature tracks dying); prompt a side-to-side pan or a turn.
 * - CLOSE_LOOP: the walk is long enough to want loop closure and the walker
 *   is far from the start — prompt heading back ("end where you started").
 *
 * Pure logic, no AR/GPS dependency: [sample] converts fixes to local
 * equirectangular meters from the first fix and decimates to
 * [MIN_POINT_SPACING_METERS]. TRANSIENCE IS THE CALLER'S JOB — [advise]
 * re-reports a live condition on every call; the screen shows it briefly
 * with a cooldown (idle-nudge doctrine: a prompt must never read as a
 * standing order).
 */
class CaptureCoach {

    enum class Kind { TURN, CLOSE_LOOP }
    data class Advice(val kind: Kind, val message: String)

    companion object {
        const val MIN_POINT_SPACING_METERS = 2.0
        const val STRAIGHT_WINDOW_METERS = 25.0
        const val STRAIGHT_MAX_DEVIATION_METERS = 1.5
        const val LOOP_MIN_PATH_METERS = 60.0
        const val LOOP_CLOSED_RADIUS_METERS = 12.0
        /** Path memory cap — a 2m-decimated multi-hour walk stays small. */
        const val MAX_POINTS = 4000
        /** UI transience policy for callers: show briefly, then a per-kind
         *  cooldown (idle-nudge doctrine — never a standing order). */
        const val ADVICE_VISIBLE_MS = 6_000L
        const val ADVICE_COOLDOWN_MS = 90_000L
        const val TURN_MESSAGE = "Long straight stretch — pan side to side or add a turn"
        const val LOOP_MESSAGE = "When you finish this stretch, loop back toward your start"
    }

    private var originLat = 0.0
    private var originLng = 0.0
    private var lngScale = 1.0
    private val eastMeters = ArrayList<Double>()
    private val northMeters = ArrayList<Double>()
    private var totalPathMeters = 0.0

    val pointCount: Int get() = eastMeters.size

    fun reset() {
        eastMeters.clear()
        northMeters.clear()
        totalPathMeters = 0.0
    }

    fun sample(lat: Double, lng: Double) {
        if (eastMeters.isEmpty()) {
            originLat = lat
            originLng = lng
            lngScale = 111_320.0 * cos(lat * PI / 180)
            eastMeters.add(0.0)
            northMeters.add(0.0)
            return
        }
        if (eastMeters.size >= MAX_POINTS) return
        // Local equirectangular meters from the FIRST fix — same small-area
        // approximation the geo-ref bridge uses; path SHAPE is what matters
        // here, not absolute accuracy.
        val e = (lng - originLng) * lngScale
        val n = (lat - originLat) * 111_320.0
        val de = e - eastMeters.last()
        val dn = n - northMeters.last()
        val step = hypot(de, dn)
        if (step < MIN_POINT_SPACING_METERS) return
        eastMeters.add(e)
        northMeters.add(n)
        totalPathMeters += step
    }

    fun advise(): Advice? {
        if (trailingWindowIsStraight()) return Advice(Kind.TURN, TURN_MESSAGE)
        if (totalPathMeters >= LOOP_MIN_PATH_METERS && distanceToStart() > LOOP_CLOSED_RADIUS_METERS) {
            return Advice(Kind.CLOSE_LOOP, LOOP_MESSAGE)
        }
        return null
    }

    private fun distanceToStart(): Double {
        if (eastMeters.isEmpty()) return 0.0
        return hypot(eastMeters.last(), northMeters.last())
    }

    /**
     * True when the trailing points spanning at least [STRAIGHT_WINDOW_METERS]
     * of walked path all lie within [STRAIGHT_MAX_DEVIATION_METERS] of the
     * window's chord. Needs ≥4 points so jittery short windows can't read
     * as "straight".
     */
    private fun trailingWindowIsStraight(): Boolean {
        val n = eastMeters.size
        if (n < 4) return false
        var covered = 0.0
        var start = n - 1
        while (start > 0 && covered < STRAIGHT_WINDOW_METERS) {
            covered += hypot(
                eastMeters[start] - eastMeters[start - 1],
                northMeters[start] - northMeters[start - 1],
            )
            start--
        }
        if (covered < STRAIGHT_WINDOW_METERS || n - start < 4) return false
        val ax = eastMeters[start]; val ay = northMeters[start]
        val bx = eastMeters[n - 1]; val by = northMeters[n - 1]
        val chord = hypot(bx - ax, by - ay)
        if (chord < 1e-6) return false
        for (i in start + 1 until n - 1) {
            // Perpendicular distance of point i to the chord A→B via the
            // cross-product magnitude.
            val cross = abs(
                (bx - ax) * (northMeters[i] - ay) - (eastMeters[i] - ax) * (by - ay)
            )
            if (cross / chord > STRAIGHT_MAX_DEVIATION_METERS) return false
        }
        return true
    }
}
