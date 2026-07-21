package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteFogClassificationTest {

    @Test
    fun cellAbsentFromPriorsIsNotStale() {
        val stale = classifyStaleCells(
            unscannedCells = setOf(0 to 0),
            cellPriorStates = emptyMap(),
        )
        assertEquals(emptySet(), stale)
    }

    @Test
    fun cellMarkedStaleInPriorsIsStale() {
        val stale = classifyStaleCells(
            unscannedCells = setOf(1 to 1),
            cellPriorStates = mapOf((1 to 1) to "stale"),
        )
        assertEquals(setOf(1 to 1), stale)
    }

    @Test
    fun cellMarkedFreshInPriorsIsNotStale() {
        // "fresh" priors are folded directly into CoverageTracker.seedScannedCells
        // by RouteScanViewModel — they never appear in unscannedCells in the
        // first place, but this guards the function's own contract regardless.
        val stale = classifyStaleCells(
            unscannedCells = setOf(2 to 2),
            cellPriorStates = mapOf((2 to 2) to "fresh"),
        )
        assertEquals(emptySet(), stale)
    }

    @Test
    fun mixedSetSelectsOnlyStale() {
        val stale = classifyStaleCells(
            unscannedCells = setOf(0 to 0, 1 to 1, 2 to 2),
            cellPriorStates = mapOf((1 to 1) to "stale"),
        )
        assertEquals(setOf(1 to 1), stale)
    }
}
