package com.gcc.app.geo

import com.gcc.shared.geo.GeofenceVerification
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for the haversine `a` clamp in
 * [GeofenceVerification.distanceMeters]: float rounding on identical or
 * antipodal-ish points can push the pre-clamp argument slightly outside
 * [0, 1], sending `sqrt(1 - a)` to NaN. Both distanceMeters overloads are
 * consumed directly by RouteScanViewModel/SpotScreen's coverage-ledger
 * events, where a NaN distance would throw serializing into the persisted
 * queue (GccJson has no allowSpecialFloatingPointValues) — so this must stay
 * finite for every input, not just the well-behaved ones.
 */
class GeofenceVerificationClampTest {

    @Test
    fun identicalPointsReturnFiniteZeroDistance_pointOverload() {
        val p = listOf(-87.7178, 41.8597) // [lng, lat], North Lawndale
        val meters = GeofenceVerification.distanceMeters(p, p)
        assertTrue(meters.isFinite(), "expected finite distance for identical points, got $meters")
        assertTrue(meters == 0.0, "expected exactly zero for identical points, got $meters")
    }

    @Test
    fun identicalPointsReturnFiniteZeroDistance_coordOverload() {
        val meters = GeofenceVerification.distanceMeters(
            lat1 = 41.8597, lng1 = -87.7178,
            lat2 = 41.8597, lng2 = -87.7178,
        )
        assertTrue(meters.isFinite(), "expected finite distance for identical points, got $meters")
        assertTrue(meters == 0.0, "expected exactly zero for identical points, got $meters")
    }

    @Test
    fun antipodalPointsReturnFiniteDistance_pointOverload() {
        // Antipodal points push `a` toward 1 — the other edge where float
        // rounding can tip sqrt(1 - a) negative-under-the-root without the clamp.
        val p1 = listOf(-87.7178, 41.8597)
        val p2 = listOf(180.0 - 87.7178, -41.8597)
        val meters = GeofenceVerification.distanceMeters(p1, p2)
        assertTrue(meters.isFinite(), "expected finite distance for antipodal-ish points, got $meters")
    }

    @Test
    fun antipodalPointsReturnFiniteDistance_coordOverload() {
        val meters = GeofenceVerification.distanceMeters(
            lat1 = 41.8597, lng1 = -87.7178,
            lat2 = -41.8597, lng2 = 180.0 - 87.7178,
        )
        assertTrue(meters.isFinite(), "expected finite distance for antipodal-ish points, got $meters")
    }
}
