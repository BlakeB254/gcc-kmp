package com.gcc.app.ui.map

import com.gcc.shared.geo.gridCell10m

/**
 * Tap→coverage-cell hit-test (spec WS4 sub-task): merged outline features
 * carry no cellId, so a tap on the main map is resolved back to a ~10m cell
 * id by quantizing the tapped lat/lng with [gridCell10m] — the SAME function
 * that produces [com.gcc.shared.model.MapCoverageCell.locationCellId], so
 * MapFullScreen's `coverageCells.find { it.locationCellId == cellId }` matches.
 */
fun coverageCellIdAt(lat: Double, lng: Double): String = gridCell10m(lat, lng)
