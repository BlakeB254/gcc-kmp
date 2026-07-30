package com.gcc.app.ui.screens

import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapViewportCoverageTest {
    private val northLawndale = Coordinates(lng = -87.7228, lat = 41.8603)

    @Test
    fun smallNativeCameraJitterDoesNotRefetch() {
        val jitter = Coordinates(lng = -87.7210, lat = 41.8610)
        assertFalse(shouldRefetchMapData(northLawndale, jitter))
    }

    @Test
    fun panningIntoDouglasParkRefetchesCoverage() {
        // The July 29 Douglas scans reached -87.7016. The old fixed
        // North-Lawndale bbox ended at -87.7028 and never loaded them.
        val douglasParkScan = Coordinates(lng = -87.7016, lat = 41.8581)
        assertTrue(shouldRefetchMapData(northLawndale, douglasParkScan))
    }

    @Test
    fun thresholdWorksOnEitherAxis() {
        assertTrue(
            shouldRefetchMapData(
                northLawndale,
                northLawndale.copy(
                    lat = northLawndale.lat + COVERAGE_REFETCH_THRESHOLD_DEGREES * 1.01,
                ),
            ),
        )
    }
}
