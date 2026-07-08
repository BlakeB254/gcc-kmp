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
        assertEquals(55.0, CoverageTracker.SEEN_RANGE_METERS)
    }

    @Test
    fun raycastHitCreditsTierByDistance() {
        val t = tracker()
        t.updateWithRaycastHits(
            listOf(
                RaycastCoverageHit(41.8600, -87.7200, distanceMeters = 2.0),   // CLOSE
                RaycastCoverageHit(41.86005, -87.7200, distanceMeters = 7.0),  // GRAZED
                RaycastCoverageHit(41.86010, -87.7200, distanceMeters = 40.0), // SEEN
                RaycastCoverageHit(41.86015, -87.7200, distanceMeters = 60.0), // beyond range: no credit
            ),
            nowMs = 1_000L,
        )
        val credits = t.creditedCells.value
        val closeCell = t.cellForLatLng(41.8600, -87.7200)!!
        val grazedCell = t.cellForLatLng(41.86005, -87.7200)!!
        val seenCell = t.cellForLatLng(41.86010, -87.7200)!!
        val farCell = t.cellForLatLng(41.86015, -87.7200)!!
        assertEquals(CoverageQuality.CLOSE, credits[closeCell]?.quality)
        assertEquals(CoverageQuality.GRAZED, credits[grazedCell]?.quality)
        assertEquals(CoverageQuality.SEEN, credits[seenCell]?.quality)
        assertNull(credits[farCell])
    }

    @Test
    fun seenDoesNotMoveCoveragePercentOrFog() {
        val t = tracker()
        val before = t.coveragePercent.value
        val seenCell = t.cellForLatLng(41.86010, -87.7200)!!
        t.updateWithRaycastHits(
            listOf(RaycastCoverageHit(41.86010, -87.7200, distanceMeters = 40.0)),
            nowMs = 1_000L,
        )
        // coveragePercent must not move — SEEN never counts as "scanned".
        assertEquals(before, t.coveragePercent.value)
        // The cell is credited (visible via creditedCells/seenCells)...
        assertEquals(CoverageQuality.SEEN, t.creditedCells.value[seenCell]?.quality)
        assertTrue(seenCell in t.seenCells.value)
        // ...but is NOT in the legacy scannedCells set, so it stays in fog
        // (getUnscannedCells = corridor - scannedCells - preSeeded).
        assertTrue(seenCell !in t.scannedCells.value)
        assertTrue(seenCell in t.getUnscannedCells())
    }

    @Test
    fun upgradeChainSeenToGrazedToClose() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 40.0)), nowMs = 1_000L)
        assertEquals(CoverageQuality.SEEN, t.creditedCells.value[cell]?.quality)
        assertTrue(cell !in t.scannedCells.value)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 7.0)), nowMs = 2_000L)
        assertEquals(CoverageQuality.GRAZED, t.creditedCells.value[cell]?.quality)
        assertTrue(cell in t.scannedCells.value)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 3_000L)
        assertEquals(CoverageQuality.CLOSE, t.creditedCells.value[cell]?.quality)
        // A later SEEN-range hit must never downgrade CLOSE.
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 40.0)), nowMs = 4_000L)
        assertEquals(CoverageQuality.CLOSE, t.creditedCells.value[cell]?.quality)
    }

    @Test
    fun purgeRevertWithThreeTiers() {
        val t = tracker()
        // Cell A: SEEN BEFORE the window, upgraded straight to CLOSE INSIDE
        // it (skipping GRAZED) — purge reverts one hop back to SEEN.
        val cellA = t.cellForLatLng(41.8600, -87.7200)!!
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 40.0)), nowMs = 1_000L)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 5_000L)
        // Cell B: first credited INSIDE the window at SEEN tier — purge
        // removes it entirely (no pre-window history to fall back to).
        val cellB = t.cellForLatLng(41.86005, -87.7200)!!
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.86005, -87.7200, 40.0)), nowMs = 6_000L)
        t.purgeSince(cutoffMs = 4_000L)
        assertEquals(CoverageQuality.SEEN, t.creditedCells.value[cellA]?.quality)
        assertTrue(cellA !in t.scannedCells.value)
        assertNull(t.creditedCells.value[cellB])
        assertTrue(cellB !in t.seenCells.value)
    }

    @Test
    fun purgeWalksPastTwoInWindowUpgradesToThePreWindowCredit() {
        // Regression test: a single-level prior revert (the pre-SEEN
        // design) would stop at the first prior even when THAT credit is
        // also inside the purge window — three tiers make a two-hop
        // in-window upgrade chain realistic (SEEN pre-window, then both
        // GRAZED and CLOSE land inside one short indoor-flip window).
        val t = tracker()
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 40.0)), nowMs = 1_000L) // SEEN, pre-window
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 7.0)), nowMs = 5_000L)  // GRAZED, in-window
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 6_000L)  // CLOSE, in-window
        t.purgeSince(cutoffMs = 4_000L)
        // Must walk past BOTH in-window credits, all the way back to SEEN —
        // not stop at GRAZED (which is itself >= the cutoff).
        assertEquals(CoverageQuality.SEEN, t.creditedCells.value[cell]?.quality)
        assertTrue(cell !in t.scannedCells.value)
    }

    @Test
    fun purgeRemovesACellWhoseEntireUpgradeChainIsInsideTheWindow() {
        // No pre-window credit at all: SEEN, GRAZED, and CLOSE all land
        // inside the window — the cell must disappear entirely, not settle
        // on some intermediate in-window tier.
        val t = tracker()
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 40.0)), nowMs = 5_000L)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 7.0)), nowMs = 5_500L)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), nowMs = 6_000L)
        t.purgeSince(cutoffMs = 4_000L)
        assertNull(t.creditedCells.value[cell])
        assertTrue(cell !in t.scannedCells.value)
        assertTrue(cell !in t.seenCells.value)
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
    fun bestOfRanksCloseOverGrazedOverSeenOverNull() {
        assertEquals(CoverageQuality.CLOSE, bestOf(CoverageQuality.CLOSE, CoverageQuality.SEEN))
        assertEquals(CoverageQuality.GRAZED, bestOf(CoverageQuality.SEEN, CoverageQuality.GRAZED))
        assertEquals(CoverageQuality.SEEN, bestOf(null, CoverageQuality.SEEN))
        assertEquals(CoverageQuality.SEEN, bestOf(CoverageQuality.SEEN, null))
        assertNull(bestOf(null, null))
    }

    @Test
    fun wireQualityStringMapsEachTierAndDefaultsNullToGrazed() {
        assertEquals("close", wireQualityString(CoverageQuality.CLOSE))
        assertEquals("grazed", wireQualityString(CoverageQuality.GRAZED))
        assertEquals("seen", wireQualityString(CoverageQuality.SEEN))
        assertEquals("grazed", wireQualityString(null))
    }

    @Test
    fun fanHitQualityPicksBestAcrossHitsAndDropsBeyondCeiling() {
        assertEquals(
            CoverageQuality.CLOSE,
            fanHitQuality(
                listOf(
                    RaycastCoverageHit(0.0, 0.0, 40.0), // SEEN
                    RaycastCoverageHit(0.0, 0.0, 2.0),  // CLOSE
                ),
            ),
        )
        assertNull(fanHitQuality(listOf(RaycastCoverageHit(0.0, 0.0, 60.0))))
        assertNull(fanHitQuality(emptyList()))
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

    // ── WS3: quality-gated deep tier ──────────────────────────────────

    @Test
    fun closeHitBelowGoodQualityCreditsGrazedNotClose() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        t.updateWithRaycastHits(
            listOf(RaycastCoverageHit(41.8600, -87.7200, distanceMeters = 2.0)),
            deepAllowed = false,
            nowMs = 1_000L,
        )
        assertEquals(CoverageQuality.GRAZED, t.creditedCells.value[cell]?.quality)
        assertTrue(cell in t.scannedCells.value)
        assertEquals(0.0, t.closePercent.value)
    }

    @Test
    fun closeHitAtGoodQualityCreditsClose() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        t.updateWithRaycastHits(
            listOf(RaycastCoverageHit(41.8600, -87.7200, distanceMeters = 2.0)),
            deepAllowed = true,
            nowMs = 1_000L,
        )
        assertEquals(CoverageQuality.CLOSE, t.creditedCells.value[cell]?.quality)
        assertTrue(t.closePercent.value > 0.0)
    }

    @Test
    fun qualityGateNeverAffectsSeenOrGrazedBands() {
        val t = tracker()
        val grazedCell = t.cellForLatLng(41.86005, -87.7200)!!
        val seenCell = t.cellForLatLng(41.86010, -87.7200)!!
        t.updateWithRaycastHits(
            listOf(
                RaycastCoverageHit(41.86005, -87.7200, distanceMeters = 7.0),  // GRAZED
                RaycastCoverageHit(41.86010, -87.7200, distanceMeters = 40.0), // SEEN
            ),
            deepAllowed = false,
            nowMs = 1_000L,
        )
        assertEquals(CoverageQuality.GRAZED, t.creditedCells.value[grazedCell]?.quality)
        assertEquals(CoverageQuality.SEEN, t.creditedCells.value[seenCell]?.quality)
    }

    @Test
    fun goodCloseCreditIsNotDowngradedByLaterUngatedCloseHit() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8600, -87.7200)!!
        t.updateWithRaycastHits(
            listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), deepAllowed = true, nowMs = 1_000L,
        )
        t.updateWithRaycastHits(
            listOf(RaycastCoverageHit(41.8600, -87.7200, 2.0)), deepAllowed = false, nowMs = 2_000L,
        )
        assertEquals(CoverageQuality.CLOSE, t.creditedCells.value[cell]?.quality)
    }

    @Test
    fun coverageQualityForDistanceGatedCapsCloseWhenNotAllowed() {
        assertEquals(CoverageQuality.GRAZED, coverageQualityForDistanceGated(2.0, deepAllowed = false))
        assertEquals(CoverageQuality.CLOSE, coverageQualityForDistanceGated(2.0, deepAllowed = true))
        assertEquals(CoverageQuality.GRAZED, coverageQualityForDistanceGated(7.0, deepAllowed = false))
        assertEquals(CoverageQuality.SEEN, coverageQualityForDistanceGated(40.0, deepAllowed = false))
        assertNull(coverageQualityForDistanceGated(60.0, deepAllowed = true))
    }
}
