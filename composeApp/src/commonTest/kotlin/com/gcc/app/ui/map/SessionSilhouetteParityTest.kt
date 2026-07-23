package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.RaycastCoverageHit
import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live-minimap ↔ summary-map parity for a realistic env-scan session, end to
 * end through the REAL CoverageTracker: a GPS walk (5m disc, contiguous) plus
 * a sparse raycast fan (discrete rays landing >1 cell apart at range — the
 * comb pattern from TestFlight 161's "streaks that never connect" report).
 *
 * The invariant under test: the live frontier silhouette is traced from the
 * SAME cell set the Scan Session Complete card draws (GRAZED+), through the
 * SAME gap-closing tracer — so during the scan and at completion the covered
 * area reads as the same connected blob, regardless of how sparse the fan's
 * SEEN-tier sampling was.
 */
class SessionSilhouetteParityTest {

    private val anchorLat = 41.8600
    private val anchorLng = -87.7200
    private val latPerMeter = 1.0 / 111_320.0

    @Test
    fun walkWithSparseFanYieldsOneConnectedSilhouetteLiveAndAtCompletion() {
        val tracker = CoverageTracker()
        tracker.initFromRoute(listOf(Coordinates(lng = anchorLng, lat = anchorLat)))

        // Walk ~60m north at ~1.4m/tick (1Hz walking pace): GPS disc credits
        // a contiguous GRAZED swath, exactly like SpotScreen's location loop.
        var t = 0L
        var walkMeters = 0.0
        while (walkMeters <= 60.0) {
            tracker.updatePosition(anchorLat + walkMeters * latPerMeter, anchorLng, nowMs = t)
            // Every tick the fan also lands sparse SEEN hits 20–40m east —
            // single cells 2+ columns apart (discrete-ray sampling at range).
            val lngPerMeter = 1.0 / (111_320.0 * kotlin.math.cos(anchorLat * kotlin.math.PI / 180.0))
            tracker.updateWithRaycastHits(
                listOf(20.0, 28.0, 36.0).map { east ->
                    RaycastCoverageHit(
                        lat = anchorLat + walkMeters * latPerMeter,
                        lng = anchorLng + east * lngPerMeter,
                        distanceMeters = east,
                    )
                },
                nowMs = t,
            )
            walkMeters += 1.4
            t += 1_000L
        }

        val scanCells = tracker.scannedCells.value.map { (r, c) -> tracker.cellCenterLatLng(r, c) }
        val seenCells = tracker.seenCells.value.map { (r, c) -> tracker.cellCenterLatLng(r, c) }
        assertTrue(seenCells.isNotEmpty(), "fan should have credited sparse SEEN cells")

        // Live minimap frontier: silhouette of GRAZED+ traced off the live
        // lattice anchor (first-composition GPS fix — NOT a cell center).
        val liveAnchorLat = anchorLat + 0.7 * latPerMeter
        val liveAnchorLng = anchorLng
        val liveRings = traceSessionSilhouette(scanCells, CoverageTracker.cellSizeMeters, liveAnchorLat, liveAnchorLng)

        // Summary map: same set, anchored at the first credited cell center.
        val summaryAnchor = scanCells.first()
        val summaryRings =
            traceSessionSilhouette(scanCells, CoverageTracker.cellSizeMeters, summaryAnchor.first, summaryAnchor.second)

        // One connected blob on both maps — the walked swath, with any fan
        // sampling seams healed; anchor choice must not change connectivity.
        assertEquals(1, liveRings.size, "live frontier should be one connected blob")
        assertEquals(1, summaryRings.size, "summary blob should be one connected blob")

        // The pre-fix frontier (GRAZED+ ∪ SEEN, raw trace) is what produced
        // the streaks: sparse SEEN hits at range trace as their own islands.
        val unionRings = quantizeAndTraceOutlines(
            (scanCells + seenCells).distinct(),
            CoverageTracker.cellSizeMeters,
            liveAnchorLat,
            liveAnchorLng,
        )
        assertTrue(
            unionRings.size > 1,
            "expected the raw GRAZED∪SEEN union to fragment (guards the fix's premise; " +
                "if this starts tracing as one ring, SEEN crediting became dense and the " +
                "silhouette/wash split may deserve a revisit)",
        )
    }
}
