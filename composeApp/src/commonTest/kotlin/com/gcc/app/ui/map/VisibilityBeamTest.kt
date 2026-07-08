package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.METERS_PER_DEG_LAT
import com.gcc.app.platform.RaycastCoverageHit
import com.gcc.app.platform.lngScaleFor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisibilityBeamTest {

    private val selfLat = 41.8600
    private val selfLng = -87.7200

    /** Planar distance in meters, same projection the beam uses. */
    private fun meters(lat: Double, lng: Double): Double {
        val north = (lat - selfLat) * METERS_PER_DEG_LAT
        val east = (lng - selfLng) * METERS_PER_DEG_LAT * lngScaleFor(selfLat)
        return sqrt(north * north + east * east)
    }

    /** A hit at [distMeters] on compass [bearingDeg] (0=N, 90=E). */
    private fun hitAt(bearingDeg: Double, distMeters: Double): RaycastCoverageHit {
        val rad = bearingDeg * kotlin.math.PI / 180.0
        val dLat = distMeters * cos(rad) / METERS_PER_DEG_LAT
        val dLng = distMeters * sin(rad) / (METERS_PER_DEG_LAT * lngScaleFor(selfLat))
        return RaycastCoverageHit(selfLat + dLat, selfLng + dLng, distMeters)
    }

    @Test
    fun noHitsProducesEmptyBeam() {
        assertEquals(emptyList(), visibilityBeamPolygon(selfLat, selfLng, emptyList()))
    }

    @Test
    fun ringStartsAndEndsAtSelf() {
        val ring = visibilityBeamPolygon(selfLat, selfLng, listOf(hitAt(0.0, 20.0)))
        assertTrue(ring.size >= 3)
        assertEquals(selfLat to selfLng, ring.first())
        assertEquals(selfLat to selfLng, ring.last())
    }

    @Test
    fun nearOnlyHitsProduceShortBeamFarHitsProduceLongBeam() {
        val near = visibilityBeamPolygon(selfLat, selfLng, listOf(hitAt(0.0, 3.0)))
        val far = visibilityBeamPolygon(selfLat, selfLng, listOf(hitAt(0.0, 45.0)))
        val nearReach = near.drop(1).dropLast(1).maxOf { (la, ln) -> meters(la, ln) }
        val farReach = far.drop(1).dropLast(1).maxOf { (la, ln) -> meters(la, ln) }
        assertTrue(nearReach < 5.0, "near beam reach $nearReach")
        assertTrue(farReach > 40.0, "far beam reach $farReach")
    }

    @Test
    fun reachClampsToSeenRange() {
        val ring = visibilityBeamPolygon(selfLat, selfLng, listOf(hitAt(0.0, 200.0)))
        val reach = ring.drop(1).dropLast(1).maxOf { (la, ln) -> meters(la, ln) }
        assertTrue(reach <= CoverageTracker.SEEN_RANGE_METERS + 0.5, "reach $reach exceeds clamp")
    }

    @Test
    fun perBinMaxReachKeepsTheFartherHitInABin() {
        val ring = visibilityBeamPolygon(selfLat, selfLng, listOf(hitAt(90.0, 6.0), hitAt(90.0, 30.0)))
        val reach = ring.drop(1).dropLast(1).maxOf { (la, ln) -> meters(la, ln) }
        assertTrue(abs(reach - 30.0) < 1.0, "expected ~30m reach, got $reach")
    }
}
