package com.gcc.app.viewmodel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteLapOneGateTest {
    @Test
    fun arLapRequiresCoverageCloseDetailAndLoopClosure() {
        assertFalse(routeLapOneReady(94.9, 100.0, true, true, true, true))
        assertFalse(routeLapOneReady(100.0, 79.9, true, true, true, true))
        assertFalse(routeLapOneReady(100.0, 100.0, false, true, true, true))
        assertFalse(routeLapOneReady(100.0, 100.0, true, true, false, true))
        assertTrue(routeLapOneReady(95.0, 80.0, true, true, true, true))
    }

    @Test
    fun gpsLapOmitsOnlyTheUnavailableCloseRaycastGate() {
        assertTrue(routeLapOneReady(95.0, 0.0, true, false, false, false))
        assertFalse(routeLapOneReady(95.0, 0.0, false, false, false, false))
    }
}
