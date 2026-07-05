package com.gcc.app.platform

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ArGeoMathTest {

    @Test
    fun localToLatLngIsTheExactInverseOfLatLngToLocal() {
        val refLat = 41.87
        val refLng = -87.68
        val refX = 0f
        val refZ = 0f
        val lngScale = lngScaleFor(refLat)

        val originalLat = 41.8712
        val originalLng = -87.6791

        val (x, z) = latLngToLocal(originalLat, originalLng, refLat, refLng, refX, refZ, lngScale)
        val (roundTrippedLat, roundTrippedLng) = localToLatLng(x.toFloat(), z.toFloat(), refLat, refLng, refX, refZ, lngScale)

        assertTrue(abs(roundTrippedLat - originalLat) < 0.000001, "lat round-trip: expected $originalLat, got $roundTrippedLat")
        assertTrue(abs(roundTrippedLng - originalLng) < 0.000001, "lng round-trip: expected $originalLng, got $roundTrippedLng")
    }

    @Test
    fun localToLatLngAtTheReferencePointReturnsTheReferenceCoordinates() {
        val refLat = 41.87
        val refLng = -87.68
        val refX = 5f
        val refZ = -3f
        val lngScale = lngScaleFor(refLat)

        val (lat, lng) = localToLatLng(refX, refZ, refLat, refLng, refX, refZ, lngScale)

        assertTrue(abs(lat - refLat) < 0.000001)
        assertTrue(abs(lng - refLng) < 0.000001)
    }
}
