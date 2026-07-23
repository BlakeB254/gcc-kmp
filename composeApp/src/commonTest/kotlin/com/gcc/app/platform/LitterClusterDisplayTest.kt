package com.gcc.app.platform

import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.WorldObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cluster display gating (Blake, 2026-07-23): "I only want those orange dots
 * displayed if there was an area scanned with a lot of issues that can be
 * viewed in a single frame … it should not persist those dots from past
 * scans; if the area was scanned recently and there were no issues then
 * clear the dot."
 */
class LitterClusterDisplayTest {

    private val baseLat = 41.8600
    private val baseLng = -87.7200
    private val latPerMeter = 1.0 / 111_320.0

    private fun item(
        northMeters: Double = 0.0,
        provenance: String? = null,
        domain: String? = "litter",
        itemType: String = "plastic_bottle",
    ) = ObservedSpotItem(
        lat = baseLat + northMeters * latPerMeter,
        lng = baseLng,
        itemType = itemType,
        domain = domain,
        provenance = provenance,
    )

    private fun worldObs(
        id: Int,
        northMeters: Double = 0.0,
        domain: String? = "litter",
    ) = WorldObservation(
        id = id,
        lat = baseLat + northMeters * latPerMeter,
        lng = baseLng,
        itemType = "can",
        domain = domain,
        state = "dirty",
        sourceType = "route_scan",
    )

    // ── clusterVisibleLitter ─────────────────────────────────────────

    @Test
    fun loneAutoDetectionIsHiddenFromDisplay() {
        assertTrue(clusterVisibleLitter(listOf(item())).isEmpty())
    }

    @Test
    fun userConfirmedItemAlwaysDisplays() {
        val items = listOf(item(provenance = PROVENANCE_USER_CONFIRMED))
        assertEquals(1, clusterVisibleLitter(items).size)
    }

    @Test
    fun threeAutoItemsWithinTheClusterRadiusDisplayTogether() {
        val items = listOf(item(0.0), item(2.0), item(4.0))
        assertEquals(3, clusterVisibleLitter(items).size)
    }

    @Test
    fun twoAutoItemsAreStillBelowTheClusterThreshold() {
        val items = listOf(item(0.0), item(2.0))
        assertTrue(clusterVisibleLitter(items).isEmpty())
    }

    @Test
    fun farApartAutoItemsDoNotClusterAcrossTheRadius() {
        // Three items but spread 30m apart — no 3 share a 6m neighborhood.
        val items = listOf(item(0.0), item(30.0), item(60.0))
        assertTrue(clusterVisibleLitter(items).isEmpty())
    }

    @Test
    fun rejectedAndNonLitterItemsNeverDisplayNorSupportClusters() {
        val items = listOf(
            item(0.0, provenance = PROVENANCE_USER_REJECTED),
            item(1.0, domain = "vegetation"),
            item(2.0),
        )
        assertTrue(clusterVisibleLitter(items).isEmpty())
    }

    // ── visibleExpectedDirty ─────────────────────────────────────────

    @Test
    fun loneWorldObservationFromAPastScanIsHidden() {
        val visible = visibleExpectedDirty(
            expectedDirty = listOf(worldObs(1)),
            sessionItems = emptyList(),
            closeCellCenters = emptyList(),
            cellSizeMeters = 3.0,
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun clusteredWorldObservationsDisplay() {
        val visible = visibleExpectedDirty(
            expectedDirty = listOf(worldObs(1, 0.0), worldObs(2, 2.0), worldObs(3, 4.0)),
            sessionItems = emptyList(),
            closeCellCenters = emptyList(),
            cellSizeMeters = 3.0,
        )
        assertEquals(3, visible.size)
    }

    @Test
    fun closeScanningAClusterWithNoLitterFoundClearsIt() {
        val cluster = listOf(worldObs(1, 0.0), worldObs(2, 2.0), worldObs(3, 4.0))
        // Session CLOSE coverage lands on each observation; the session found
        // no litter anywhere near → the stale dots clear.
        val closeCells = cluster.map { it.lat to it.lng }
        val visible = visibleExpectedDirty(
            expectedDirty = cluster,
            sessionItems = emptyList(),
            closeCellCenters = closeCells,
            cellSizeMeters = 3.0,
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun closeScanningThatConfirmsLitterKeepsTheDots() {
        val cluster = listOf(worldObs(1, 0.0), worldObs(2, 2.0), worldObs(3, 4.0))
        val closeCells = cluster.map { it.lat to it.lng }
        val visible = visibleExpectedDirty(
            expectedDirty = cluster,
            sessionItems = listOf(item(1.0, provenance = PROVENANCE_USER_CONFIRMED)),
            closeCellCenters = closeCells,
            cellSizeMeters = 3.0,
        )
        assertEquals(3, visible.size)
    }

    @Test
    fun unscannedWorldClusterPersistsUntouched() {
        val cluster = listOf(worldObs(1, 0.0), worldObs(2, 2.0), worldObs(3, 4.0))
        val visible = visibleExpectedDirty(
            expectedDirty = cluster,
            sessionItems = emptyList(),
            // Session close-scanned somewhere else entirely.
            closeCellCenters = listOf((baseLat + 200 * latPerMeter) to baseLng),
            cellSizeMeters = 3.0,
        )
        assertEquals(3, visible.size)
    }
}
