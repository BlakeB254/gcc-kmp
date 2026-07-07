package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CoverageTrackerQualityTest {

    /** One-point "route" like the env scan uses — grid spans anchor ±(20m pad + corridor). */
    private fun tracker(anchorLat: Double = 41.8600, anchorLng: Double = -87.7200): CoverageTracker {
        val t = CoverageTracker()
        t.initFromRoute(listOf(Coordinates(lng = anchorLng, lat = anchorLat)))
        return t
    }

    @Test
    fun frozenRangeConstants() {
        // Tuning knobs, but changes must be DELIBERATE (they redefine what
        // "scanned" means on the public map) — snap the values.
        assertEquals(4.0, CoverageTracker.CLOSE_RANGE_METERS)
        assertEquals(10.0, CoverageTracker.GRAZE_RANGE_METERS)
    }

    @Test
    fun raycastHitCreditsTierByDistance() {
        val t = tracker()
        t.updateWithRaycastHits(
            listOf(
                RaycastCoverageHit(41.8600, -87.7200, distanceMeters = 2.0),   // CLOSE
                RaycastCoverageHit(41.86005, -87.7200, distanceMeters = 7.0),  // GRAZED
                RaycastCoverageHit(41.86010, -87.7200, distanceMeters = 12.0), // beyond range: no credit
            ),
            nowMs = 1_000L,
        )
        val credits = t.creditedCells.value
        val closeCell = t.cellForLatLng(41.8600, -87.7200)!!
        val grazedCell = t.cellForLatLng(41.86005, -87.7200)!!
        val farCell = t.cellForLatLng(41.86010, -87.7200)!!
        assertEquals(CoverageQuality.CLOSE, credits[closeCell]?.quality)
        assertEquals(CoverageQuality.GRAZED, credits[grazedCell]?.quality)
        assertNull(credits[farCell])
    }

    @Test
    fun upgradeIsMonotonic() {
        val t = tracker()
        val hitClose = RaycastCoverageHit(41.8600, -87.7200, 2.0)
        val hitGrazed = RaycastCoverageHit(41.8600, -87.7200, 8.0)
        t.updateWithRaycastHits(listOf(hitGrazed), nowMs = 1_000L)
        t.updateWithRaycastHits(listOf(hitClose), nowMs = 2_000L)
        // A later grazed hit must NOT downgrade (and must not touch the timestamp).
        t.updateWithRaycastHits(listOf(hitGrazed), nowMs = 3_000L)
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        val credit = t.creditedCells.value[cell]!!
        assertEquals(CoverageQuality.CLOSE, credit.quality)
        assertEquals(2_000L, credit.creditedAtMs)
    }

    @Test
    fun updatePositionCreditsGrazedOnly() {
        val t = tracker()
        t.updatePosition(41.8600, -87.7200, nowMs = 1_000L)
        val credits = t.creditedCells.value
        assertTrue(credits.isNotEmpty())
        assertTrue(credits.values.all { it.quality == CoverageQuality.GRAZED })
        // scannedCells (legacy set) mirrors the credited keys.
        assertEquals(credits.keys, t.scannedCells.value)
    }

    @Test
    fun purgeRemovesWindowCreditsAndRevertsUpgrades() {
        val t = tracker()
        // Cell A: grazed BEFORE the window, upgraded to close INSIDE it — purge reverts to grazed.
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 8.0)), nowMs = 1_000L)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 5_000L)
        // Cell B: first credited INSIDE the window — purge removes it entirely.
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.86005, -87.7200, 2.0)), nowMs = 6_000L)
        t.purgeSince(cutoffMs = 4_000L)
        val credits = t.creditedCells.value
        val cellA = t.cellForLatLng(41.8600, -87.7200)!!
        val cellB = t.cellForLatLng(41.86005, -87.7200)!!
        assertEquals(CoverageQuality.GRAZED, credits[cellA]?.quality)
        assertNull(credits[cellB])
        assertTrue(cellB !in t.scannedCells.value)
    }

    @Test
    fun closePercentTracksCloseTierOnly() {
        val t = tracker()
        assertEquals(0.0, t.closePercent.value)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 8.0)), nowMs = 1_000L)
        assertEquals(0.0, t.closePercent.value) // grazed counts toward coveragePercent only
        assertTrue(t.coveragePercent.value > 0.0)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 2_000L)
        assertTrue(t.closePercent.value > 0.0)
        assertNotNull(t.creditedCells.value)
    }
}
