package com.gcc.app.model

import com.gcc.shared.model.WorldObservation
import com.gcc.shared.model.toScanMapPin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lives in composeApp commonTest (not shared's, whose cache tests reference a
 * JVM-only sqlite driver and don't currently compile on any configured target).
 */
class WorldObservationPinTest {

    private fun observation(
        sourceType: String = "route_scan",
        severity: Int? = null,
    ) = WorldObservation(
        id = 7,
        lat = 41.8603,
        lng = -87.7228,
        itemType = "plastic_bottle",
        state = "dirty",
        severity = severity,
        sourceType = sourceType,
    )

    /**
     * Regression: Coordinates is GeoJSON-ordered (lng, lat); a positional
     * (lat, lng) call in toScanMapPin once rendered every scan-observation
     * pin transposed (lat 41.86 became lng), i.e. off the map entirely.
     */
    @Test
    fun scanPinKeepsLatAndLngStraight() {
        val pin = observation().toScanMapPin()
        assertEquals(41.8603, pin.coordinates.lat)
        assertEquals(-87.7228, pin.coordinates.lng)
    }

    @Test
    fun severityRidesTheAiAnalysisChannel() {
        assertEquals(null, observation(severity = null).toScanMapPin().aiAnalysis)
        assertEquals("low", observation(severity = 1).toScanMapPin().aiAnalysis?.severity)
        assertEquals("medium", observation(severity = 3).toScanMapPin().aiAnalysis?.severity)
        assertEquals("high", observation(severity = 5).toScanMapPin().aiAnalysis?.severity)
    }

    @Test
    fun descriptionNamesTheSource() {
        assertEquals("Route scan: plastic_bottle", observation().toScanMapPin().description)
        assertEquals(
            "AR scan: plastic_bottle",
            observation(sourceType = "ar_auto_detect").toScanMapPin().description,
        )
    }
}
