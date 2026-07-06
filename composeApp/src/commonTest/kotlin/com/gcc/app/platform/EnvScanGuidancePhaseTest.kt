package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvScanGuidancePhaseTest {

    private val user = 41.8600 to -87.7200
    private val nearCell = 41.86005 to -87.7200   // ~5.5m north
    private val farCell = 41.86020 to -87.7200    // ~22m north

    @Test
    fun coverPhaseTargetsUncreditedWhileCoverageLow() {
        val pick = EnvScanGuidance.pickTarget(
            current = null,
            uncredited = listOf(farCell),
            grazedNotClose = listOf(nearCell),
            userLat = user.first, userLng = user.second,
            coveragePercent = 30.0,
        )
        assertEquals(GuidancePhase.COVER, pick?.phase)
        assertEquals(farCell, pick?.target)
    }

    @Test
    fun detailPhaseKicksInAtThreshold() {
        val pick = EnvScanGuidance.pickTarget(
            current = null,
            uncredited = listOf(farCell),
            grazedNotClose = listOf(nearCell),
            userLat = user.first, userLng = user.second,
            coveragePercent = EnvScanGuidance.DETAIL_PHASE_COVERAGE_PERCENT,
        )
        assertEquals(GuidancePhase.DETAIL, pick?.phase)
        assertEquals(nearCell, pick?.target)
    }

    @Test
    fun detailPhaseAlsoWhenNothingLeftToCover() {
        val pick = EnvScanGuidance.pickTarget(
            current = null,
            uncredited = emptyList(),
            grazedNotClose = listOf(nearCell),
            userLat = user.first, userLng = user.second,
            coveragePercent = 10.0,
        )
        assertEquals(GuidancePhase.DETAIL, pick?.phase)
    }

    @Test
    fun nothingLeftAnywhereReturnsNull() {
        assertNull(
            EnvScanGuidance.pickTarget(
                current = null,
                uncredited = emptyList(),
                grazedNotClose = emptyList(),
                userLat = user.first, userLng = user.second,
                coveragePercent = 100.0,
            )
        )
    }
}
