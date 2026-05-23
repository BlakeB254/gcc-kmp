package com.gcc.shared.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-function tests for the geofence math used to verify worker proximity
 * to checkpoints. Lives in commonTest so the same assertions run for every
 * KMP target.
 */
class GeofenceVerificationTest {

    @Test
    fun `distance to self is zero`() {
        val p = doubleArrayOf(-87.7178, 41.8597).toList()  // North Lawndale, Chicago
        assertEquals(0.0, GeofenceVerification.distanceMeters(p, p), 0.001)
    }

    @Test
    fun `distance between Chicago and NYC matches known great-circle value`() {
        // North Lawndale (Chicago) → Times Square (NYC)
        // Haversine great-circle for these exact lat/lngs: ~1152 km.
        val chicago = doubleArrayOf(-87.7178, 41.8597).toList()
        val nyc = doubleArrayOf(-73.9855, 40.7580).toList()
        val meters = GeofenceVerification.distanceMeters(chicago, nyc)

        // Allow ±5 km tolerance so the test stays green if someone tweaks the
        // reference points slightly. Anchor: actual sphere-distance is ~1152 km.
        assertTrue(
            abs(meters - 1_152_000.0) < 5_000.0,
            "expected ~1152km, got ${meters / 1000} km",
        )
    }

    @Test
    fun `distanceMeters overload with raw lat-lng matches Point overload`() {
        val p1 = doubleArrayOf(-87.7178, 41.8597).toList()
        val p2 = doubleArrayOf(-87.6500, 41.8800).toList()

        val viaPoints = GeofenceVerification.distanceMeters(p1, p2)
        val viaCoords = GeofenceVerification.distanceMeters(
            lat1 = 41.8597, lng1 = -87.7178,
            lat2 = 41.8800, lng2 = -87.6500,
        )
        assertEquals(viaPoints, viaCoords, 0.01)
    }

    @Test
    fun `isWithinGeofence is true when user is exactly at checkpoint`() {
        val p = doubleArrayOf(-87.7178, 41.8597).toList()
        assertTrue(GeofenceVerification.isWithinGeofence(p, p, radiusMeters = 50.0))
    }

    @Test
    fun `isWithinGeofence is true for nearby point inside radius`() {
        // Two points ~10m apart (small longitude offset at Chicago's latitude)
        val checkpoint = doubleArrayOf(-87.7178, 41.8597).toList()
        val nearby = doubleArrayOf(-87.71768, 41.8597).toList()  // ~10m east
        assertTrue(
            GeofenceVerification.isWithinGeofence(nearby, checkpoint, radiusMeters = 50.0),
            "should be inside 50m radius",
        )
    }

    @Test
    fun `isWithinGeofence is false for point well outside radius`() {
        val checkpoint = doubleArrayOf(-87.7178, 41.8597).toList()
        val faraway = doubleArrayOf(-87.6500, 41.8800).toList()  // several km away
        assertFalse(
            GeofenceVerification.isWithinGeofence(faraway, checkpoint, radiusMeters = 50.0),
            "should be outside 50m radius",
        )
    }

    @Test
    fun `tiny lat-lng deltas produce sub-meter distances`() {
        // 1e-5 degree at the equator is ~1.1m. At Chicago latitude (cos(~42deg) ≈ 0.74)
        // the same delta in longitude is ~0.83m.
        val p1 = doubleArrayOf(0.0, 41.8597).toList()
        val p2 = doubleArrayOf(0.00001, 41.8597).toList()
        val meters = GeofenceVerification.distanceMeters(p1, p2)
        assertTrue(meters < 1.5, "expected sub-meter distance, got $meters m")
    }
}
