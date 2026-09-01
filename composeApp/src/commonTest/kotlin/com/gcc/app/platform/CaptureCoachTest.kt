package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Capture coaching v2 (plan Task 1.7): pure-logic prompts derived from the
 * session GPS path — turn prompts on long straight stretches (forward-only
 * motion is the reconstruction pipeline's weakest case) and a loop-closure
 * prompt once the walk is long enough to want one. All geometry is local
 * equirectangular meters from the first fix; tests build paths in meters
 * via a fixed origin and the same conversion the coach uses.
 */
class CaptureCoachTest {

    private val originLat = 41.8595
    private val originLng = -87.7136

    /** Feed a path of (eastMeters, northMeters) offsets from the origin. */
    private fun coachWith(points: List<Pair<Double, Double>>): CaptureCoach {
        val coach = CaptureCoach()
        val latPerMeter = 1.0 / 111_320.0
        val lngPerMeter = 1.0 / (111_320.0 * kotlin.math.cos(originLat * kotlin.math.PI / 180))
        points.forEach { (east, north) ->
            coach.sample(originLat + north * latPerMeter, originLng + east * lngPerMeter)
        }
        return coach
    }

    /** N points straight north, `spacing` meters apart. */
    private fun straightNorth(count: Int, spacing: Double = 3.0): List<Pair<Double, Double>> =
        (0 until count).map { 0.0 to it * spacing }

    @Test
    fun shortPathAdvisesNothing() {
        val coach = coachWith(straightNorth(4)) // 9m — under every threshold
        assertNull(coach.advise())
    }

    @Test
    fun longStraightStretchAdvisesTurn() {
        val coach = coachWith(straightNorth(12)) // 33m dead straight
        assertEquals(CaptureCoach.Kind.TURN, coach.advise()?.kind)
    }

    @Test
    fun weavingPathDoesNotAdviseTurn() {
        // Same northward progress but weaving ±3m east — deviation from the
        // chord exceeds the straightness tolerance, so never TURN.
        val weave = (0 until 12).map { i ->
            (if (i % 2 == 0) 3.0 else -3.0) to i * 3.0
        }
        val coach = coachWith(weave)
        val kind = coach.advise()?.kind
        assertEquals(null, kind?.takeIf { it == CaptureCoach.Kind.TURN })
    }

    @Test
    fun longWanderingPathFarFromStartAdvisesLoopClosure() {
        // ~80m of path that keeps weaving PERPENDICULAR to travel (±2.0m,
        // beyond the 1.5m straightness tolerance, so no trailing window
        // reads straight) and ends far from the origin.
        val leg1 = (0 until 14).map { i -> (if (i % 2 == 0) 2.0 else -2.0) to i * 3.0 }
        val leg2 = (1 until 14).map { i -> i * 3.0 to 39.0 + (if (i % 2 == 0) 2.0 else -2.0) }
        val coach = coachWith(leg1 + leg2)
        assertEquals(CaptureCoach.Kind.CLOSE_LOOP, coach.advise()?.kind)
    }

    @Test
    fun closedLoopAdvisesNothing() {
        // A jittered rectangle returning to the start: long path, but the
        // walker is back within the closed radius — no loop prompt.
        val north = (0 until 8).map { i -> (if (i % 2 == 0) 1.0 else -1.0) to i * 3.0 }
        val east = (1 until 8).map { i -> i * 3.0 + (if (i % 2 == 0) 1.0 else -1.0) to 21.0 }
        val south = (0 until 8).map { i -> 21.0 + (if (i % 2 == 0) 1.0 else -1.0) to 21.0 - i * 3.0 }
        val west = (1 until 8).map { i -> 21.0 - i * 3.0 to (if (i % 2 == 0) 1.0 else -1.0) }
        val coach = coachWith(north + east + south + west)
        assertNull(coach.advise())
    }

    @Test
    fun duplicateFixesAreDecimated() {
        val coach = CaptureCoach()
        repeat(50) { coach.sample(originLat, originLng) } // standing still
        assertEquals(1, coach.pointCount)
        assertNull(coach.advise())
    }

    @Test
    fun resetClearsThePath() {
        val coach = coachWith(straightNorth(12))
        coach.reset()
        assertEquals(0, coach.pointCount)
        assertNull(coach.advise())
    }
}
