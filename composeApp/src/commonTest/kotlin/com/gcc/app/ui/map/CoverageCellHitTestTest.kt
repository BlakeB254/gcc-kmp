package com.gcc.app.ui.map

import com.gcc.shared.geo.gridCell10m
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageCellHitTestTest {

    @Test
    fun tapResolvesToTheSameCellIdAsGridCell10m() {
        // The map tap hit-test must produce the exact id the server/client grid
        // uses (MapCoverageCell.locationCellId), so MapFullScreen's
        // coverageCells.find { it.locationCellId == cellId } matches.
        val lat = 41.8781
        val lng = -87.6298
        assertEquals(gridCell10m(lat, lng), coverageCellIdAt(lat, lng))
    }

    @Test
    fun twoTapsInsideTheSame10mCellResolveToTheSameId() {
        val id1 = coverageCellIdAt(41.87810, -87.62980)
        val id2 = coverageCellIdAt(41.878105, -87.629805)
        assertEquals(id1, id2)
    }
}
