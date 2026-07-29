package com.gcc.app.ui.map

import com.gcc.shared.model.MapCoverageCell
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemBlobTest {

    private fun cell(
        id: String,
        latMeters: Double,
        lngMeters: Double,
        count: Int = 1,
        grams: Double = 100.0,
    ) = MapCoverageCell(
        locationCellId = id,
        lat = 41.86 + latMeters / 111_320.0,
        lng = -87.72 + lngMeters / (111_320.0 * kotlin.math.cos(41.86 * kotlin.math.PI / 180.0)),
        state = "fresh",
        lastScannedAt = "2026-07-28T12:00:00Z",
        totalDistanceMeters = 0.0,
        litterCount = count,
        litterWeightGrams = grams,
    )

    @Test
    fun contiguousSameTierCellsFormOneBlob() {
        val a = cell("a", 0.0, 0.0)
        val b = cell("b", 0.0, 10.0)
        val c = cell("c", 0.0, 20.0)
        val blob = sameColorBlobCells(a, listOf(a, b, c))
        assertEquals(listOf("a", "b", "c"), blob.map { it.locationCellId })
    }

    @Test
    fun gapBreaksTheBlob() {
        val a = cell("a", 0.0, 0.0)
        val far = cell("far", 0.0, 40.0) // 40m — two clean cells between
        assertEquals(listOf("a"), sameColorBlobCells(a, listOf(a, far)).map { it.locationCellId })
    }

    @Test
    fun differentSeverityTierDoesNotJoin() {
        val light = cell("light", 0.0, 0.0, count = 1, grams = 100.0)
        val severe = cell("severe", 0.0, 10.0, count = 8, grams = 20_000.0)
        assertEquals(
            listOf("light"),
            sameColorBlobCells(light, listOf(light, severe)).map { it.locationCellId },
        )
    }

    @Test
    fun nonProblemTapReturnsJustItself() {
        val clean = cell("clean", 0.0, 0.0, count = 0, grams = 0.0)
        val problem = cell("p", 0.0, 10.0)
        assertEquals(
            listOf("clean"),
            sameColorBlobCells(clean, listOf(clean, problem)).map { it.locationCellId },
        )
    }

    @Test
    fun diagonalNeighborsJoin() {
        val a = cell("a", 0.0, 0.0)
        val d = cell("d", 10.0, 10.0)
        assertEquals(2, sameColorBlobCells(a, listOf(a, d)).size)
    }
}
