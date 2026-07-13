package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Field bug (2026-07-12 session env-1783899861528-6bqurc, diagnosed from its
 * flight-recorder event log): the free env scan initialised the coverage
 * lattice from a SINGLE anchor waypoint, so `initFromRoute`'s 20m bounding-box
 * padding made the entire creditable universe a ~40m box. Walking a ~150m
 * fence line credited the first ~40m, then every position fell outside the
 * grid and was silently dropped — the minimap blob froze and the idle-nudge
 * guidance nagged forever. Free scans now init with a large exploration
 * padding: the user goes where they want (env-scan PRD P4), the blob follows.
 */
class CoverageTrackerExplorationTest {

    private val anchorLat = 41.8600
    private val anchorLng = -87.7200
    private val latPerMeter = 1.0 / 111_320.0

    @Test
    fun explorationPaddingCreditsCellsFarFromTheAnchor() {
        val t = CoverageTracker()
        t.initFromRoute(
            listOf(Coordinates(lng = anchorLng, lat = anchorLat)),
            paddingMeters = CoverageTracker.FREE_SCAN_PADDING_METERS,
        )
        // ~150m north of the anchor — the fence-line walk that got dropped.
        t.updatePosition(anchorLat + 150.0 * latPerMeter, anchorLng)
        assertTrue(
            t.scannedCells.value.isNotEmpty(),
            "a position 150m from the anchor must still credit cells in a free scan",
        )
    }

    @Test
    fun defaultPaddingStillBoundsRouteLapGrids() {
        val t = CoverageTracker()
        t.initFromRoute(listOf(Coordinates(lng = anchorLng, lat = anchorLat)))
        // Same 150m walk with the route-lap default: outside the grid, no
        // credit — corridor-scoped laps must not grow unbounded grids.
        t.updatePosition(anchorLat + 150.0 * latPerMeter, anchorLng)
        assertTrue(
            t.scannedCells.value.isEmpty(),
            "default padding keeps the route-lap bounding contract",
        )
    }
}
