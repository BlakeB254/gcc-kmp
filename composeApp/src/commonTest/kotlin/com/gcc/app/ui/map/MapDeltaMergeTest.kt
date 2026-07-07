package com.gcc.app.ui.map

import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.WorldObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MapDeltaMergeTest {

    private fun cell(id: String, lastScannedAt: String, quality: String = "grazed") = MapCoverageCell(
        locationCellId = id,
        lat = 0.0,
        lng = 0.0,
        state = "fresh",
        lastScannedAt = lastScannedAt,
        totalDistanceMeters = 0.0,
        quality = quality,
    )

    private fun obs(id: Int, state: String = "dirty", capturedAt: String? = "2026-07-07T00:00:00Z") = WorldObservation(
        id = id,
        lat = 0.0,
        lng = 0.0,
        state = state,
        sourceType = "route_scan",
        capturedAt = capturedAt,
    )

    // ── mergeCoverageCells ──────────────────────────────────────────

    @Test
    fun mergeCoverageCells_upsertReplacesMatchingKey() {
        val existing = listOf(cell("a", "2026-07-07T00:00:00Z", quality = "grazed"))
        val delta = listOf(cell("a", "2026-07-07T00:05:00Z", quality = "close"))

        val merged = mergeCoverageCells(existing, delta)

        assertEquals(1, merged.size)
        assertEquals("close", merged.single().quality)
        assertEquals("2026-07-07T00:05:00Z", merged.single().lastScannedAt)
    }

    @Test
    fun mergeCoverageCells_keepsUntouchedExistingCells() {
        val existing = listOf(cell("a", "2026-07-07T00:00:00Z"), cell("b", "2026-07-07T00:00:00Z"))
        val delta = listOf(cell("a", "2026-07-07T00:05:00Z"))

        val merged = mergeCoverageCells(existing, delta)

        assertEquals(setOf("a", "b"), merged.map { it.locationCellId }.toSet())
    }

    @Test
    fun mergeCoverageCells_appendsNewKeysFromDelta() {
        val existing = listOf(cell("a", "2026-07-07T00:00:00Z"))
        val delta = listOf(cell("b", "2026-07-07T00:05:00Z"))

        val merged = mergeCoverageCells(existing, delta)

        assertEquals(setOf("a", "b"), merged.map { it.locationCellId }.toSet())
    }

    @Test
    fun mergeCoverageCells_emptyDeltaReturnsExistingUnchanged() {
        val existing = listOf(cell("a", "2026-07-07T00:00:00Z"))

        val merged = mergeCoverageCells(existing, emptyList())

        assertSame(existing, merged)
    }

    // ── mergeObservations ───────────────────────────────────────────

    @Test
    fun mergeObservations_upsertReplacesMatchingKey() {
        val existing = listOf(obs(1, state = "dirty"))
        val delta = listOf(obs(1, state = "clean"))

        val merged = mergeObservations(existing, delta)

        assertEquals(1, merged.size)
        assertEquals("clean", merged.single().state)
    }

    @Test
    fun mergeObservations_keepsUntouchedExistingObservations() {
        val existing = listOf(obs(1), obs(2))
        val delta = listOf(obs(1, state = "clean"))

        val merged = mergeObservations(existing, delta)

        assertEquals(setOf(1, 2), merged.map { it.id }.toSet())
    }

    @Test
    fun mergeObservations_emptyDeltaReturnsExistingUnchanged() {
        val existing = listOf(obs(1))

        val merged = mergeObservations(existing, emptyList())

        assertSame(existing, merged)
    }

    // ── latestIsoTimestamp (server-clock cursor advance) ────────────

    @Test
    fun latestIsoTimestamp_picksLaterOfCurrentAndCandidates() {
        val result = latestIsoTimestamp(
            current = "2026-07-07T00:00:00Z",
            candidates = listOf("2026-07-07T00:05:00Z", "2026-07-06T23:00:00Z"),
        )
        assertEquals("2026-07-07T00:05:00Z", result)
    }

    @Test
    fun latestIsoTimestamp_ignoresNullCandidates() {
        val result = latestIsoTimestamp(
            current = "2026-07-07T00:00:00Z",
            candidates = listOf(null, null),
        )
        assertEquals("2026-07-07T00:00:00Z", result)
    }

    @Test
    fun latestIsoTimestamp_nullCurrentPicksFirstNonNullCandidate() {
        val result = latestIsoTimestamp(
            current = null,
            candidates = listOf(null, "2026-07-07T00:05:00Z"),
        )
        assertEquals("2026-07-07T00:05:00Z", result)
    }
}
