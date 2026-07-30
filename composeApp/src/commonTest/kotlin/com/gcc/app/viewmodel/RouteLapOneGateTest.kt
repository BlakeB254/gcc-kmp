package com.gcc.app.viewmodel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteLapOneGateTest {
    @Test
    fun arLapRequiresCoverageCloseDetailAndLoopClosure() {
        assertFalse(routeLapOneReady(94.9, 100.0, loopClosed = true, requiresCloseCoverage = true))
        assertFalse(routeLapOneReady(100.0, 79.9, loopClosed = true, requiresCloseCoverage = true))
        assertFalse(routeLapOneReady(100.0, 100.0, loopClosed = false, requiresCloseCoverage = true))
        assertTrue(routeLapOneReady(95.0, 80.0, loopClosed = true, requiresCloseCoverage = true))
    }

    @Test
    fun gpsLapOmitsOnlyTheUnavailableCloseRaycastGate() {
        assertTrue(routeLapOneReady(95.0, 0.0, loopClosed = true, requiresCloseCoverage = false))
        assertFalse(routeLapOneReady(95.0, 0.0, loopClosed = false, requiresCloseCoverage = false))
    }
}
