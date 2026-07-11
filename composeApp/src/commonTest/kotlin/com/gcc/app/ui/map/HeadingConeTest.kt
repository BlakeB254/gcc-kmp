package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.METERS_PER_DEG_LAT
import com.gcc.app.platform.lngScaleFor
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadingConeTest {

    private val selfLat = 41.8600
    private val selfLng = -87.7200

    private fun meters(lat: Double, lng: Double): Double {
        val north = (lat - selfLat) * METERS_PER_DEG_LAT
        val east = (lng - selfLng) * METERS_PER_DEG_LAT * lngScaleFor(selfLat)
        return sqrt(north * north + east * east)
    }

    @Test
    fun ringStartsAndEndsAtSelf() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f)
        assertTrue(ring.size >= 3)
        assertEquals(selfLat to selfLng, ring.first())
        assertEquals(selfLat to selfLng, ring.last())
    }

    @Test
    fun coneAlwaysRendersEvenWithNoRaycasts() {
        // Constraint 5: the cone is not a function of ray hits. It only needs a heading.
        assertTrue(headingConePolygon(selfLat, selfLng, headingDegrees = 137f).size >= 3)
    }

    @Test
    fun outerRadiusIsTheSeenRange() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f)
        val reach = ring.drop(1).dropLast(1).maxOf { (la, ln) -> meters(la, ln) }
        assertTrue(
            abs(reach - CoverageTracker.SEEN_RANGE_METERS) < 0.5,
            "cone reach $reach should be SEEN_RANGE_METERS",
        )
    }

    @Test
    fun northHeadingPointsNorth() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f, arcSteps = 2)
        val tip = ring[2] // arc midpoint at arcSteps=2 lies on the heading
        assertTrue(tip.first > selfLat, "north heading must increase latitude")
        assertTrue(abs(tip.second - selfLng) < 1e-6, "north heading must not shift longitude")
    }

    @Test
    fun eastHeadingPointsEast() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 90f, arcSteps = 2)
        val tip = ring[2]
        assertTrue(tip.second > selfLng, "east heading must increase longitude")
        assertTrue(abs(tip.first - selfLat) < 1e-6, "east heading must not shift latitude")
    }

    @Test
    fun spreadIsFiftyFiveDegrees() {
        // Constraint 5. Sample the two arc extremes and compare their bearings.
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f, arcSteps = 2)
        val left = ring[1]
        val right = ring[3]
        fun bearing(p: Pair<Double, Double>): Double {
            val north = (p.first - selfLat) * METERS_PER_DEG_LAT
            val east = (p.second - selfLng) * METERS_PER_DEG_LAT * lngScaleFor(selfLat)
            return kotlin.math.atan2(east, north) * 180.0 / kotlin.math.PI
        }
        assertTrue(abs((bearing(right) - bearing(left)) - 55.0) < 0.5)
    }

    @Test
    fun geoJsonDelegatesToTheSamePolygon() {
        // The main map's FillLayer and the minimap Canvas must not drift apart.
        val json = headingConeGeoJson(selfLat, selfLng, 0f)
        assertTrue(json.startsWith("""{"type":"FeatureCollection""""))
        assertTrue(json.contains("Polygon"))
    }

    // ── ADDENDUM: shortest-path heading unwrap ──────────────────────────
    // Blake (binding addendum): the cone must rotate the short way around
    // the compass. A raw heading delta across the 0/360 seam (350 -> 10)
    // is a naive -340 degrees; unwrapHeading must report the short +20.

    @Test
    fun unwrapCrossingNorthForward() {
        assertEquals(20f, unwrapHeading(350f, 10f))
    }

    @Test
    fun unwrapCrossingNorthBackward() {
        assertEquals(-20f, unwrapHeading(10f, 350f))
    }

    @Test
    fun unwrapNoWrapForward() {
        assertEquals(10f, unwrapHeading(10f, 20f))
    }

    @Test
    fun unwrapNoWrapBackward() {
        assertEquals(-45f, unwrapHeading(90f, 45f))
    }

    @Test
    fun unwrapSameHeadingIsZero() {
        assertEquals(0f, unwrapHeading(180f, 180f))
    }

    @Test
    fun unwrapHandlesUnnormalizedCumulativePrevious() {
        // previous may be an accumulated (non-mod-360) angle, e.g. 370 after
        // a prior wrap; only (next - previous) mod 360 matters. Compared with
        // a tolerance (not assertEquals) because -360f % 360f is IEEE -0.0f,
        // which boxed Float.equals treats as distinct from 0.0f.
        assertTrue(abs(unwrapHeading(370f, 10f)) < 1e-6f)
    }
}
