package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteFogClassificationTest {

    @Test
    fun cellAbsentFromPriorsIsFog() {
        val result = classifyFogAndStale(
            unscannedCells = setOf(0 to 0),
            cellPriorStates = emptyMap(),
        )
        assertEquals(setOf(0 to 0), result.fogCells)
        assertEquals(emptySet(), result.staleCells)
    }

    @Test
    fun cellMarkedStaleInPriorsIsStale() {
        val result = classifyFogAndStale(
            unscannedCells = setOf(1 to 1),
            cellPriorStates = mapOf((1 to 1) to "stale"),
        )
        assertEquals(emptySet(), result.fogCells)
        assertEquals(setOf(1 to 1), result.staleCells)
    }

    @Test
    fun cellMarkedFreshInPriorsIsNeitherFogNorStale() {
        // "fresh" priors are folded directly into CoverageTracker.seedScannedCells
        // by RouteScanViewModel — they never appear in unscannedCells in the
        // first place, but this guards the function's own contract regardless.
        val result = classifyFogAndStale(
            unscannedCells = setOf(2 to 2),
            cellPriorStates = mapOf((2 to 2) to "fresh"),
        )
        assertEquals(emptySet(), result.fogCells)
        assertEquals(emptySet(), result.staleCells)
    }

    @Test
    fun mixedSetPartitionsCorrectly() {
        val result = classifyFogAndStale(
            unscannedCells = setOf(0 to 0, 1 to 1, 2 to 2),
            cellPriorStates = mapOf((1 to 1) to "stale"),
        )
        assertEquals(setOf(0 to 0, 2 to 2), result.fogCells)
        assertEquals(setOf(1 to 1), result.staleCells)
    }
}
