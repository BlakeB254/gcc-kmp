package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectCoverageTrackerTest {

    @Test
    fun capturingFromOneAngleCoversOnlyThatSector() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)

        // User stands due north of the item, close enough to count as "captured"
        tracker.updateCapturedAngle(userLat = 41.8627, userLng = -87.6851, distanceMeters = 2.0)

        val covered = tracker.coveredSectors.value
        assertTrue(covered.isNotEmpty(), "expected at least one sector covered")
        assertTrue(covered.size < ObjectCoverageTracker.SECTOR_COUNT, "expected NOT full coverage from a single angle")
    }

    @Test
    fun capturingAllSectorsReaches100Percent() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)

        // Walk a full circle around the item at a fixed radius, sampling every 10 degrees —
        // finer than SECTOR_COUNT's angular width, so every sector gets hit.
        val radiusDeg = 2.0 / 111_000.0 // ~2m in degrees latitude
        for (bearing in 0 until 360 step 10) {
            val rad = bearing * kotlin.math.PI / 180.0
            val lat = 41.8617 + radiusDeg * kotlin.math.cos(rad)
            val lng = -87.6851 + radiusDeg * kotlin.math.sin(rad)
            tracker.updateCapturedAngle(userLat = lat, userLng = lng, distanceMeters = 2.0)
        }

        assertTrue(kotlin.math.abs(100.0 - tracker.coveragePercent.value) < 0.01)
    }

    @Test
    fun tooFarAwayDoesNotCountAsCaptured() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)

        // 50m away — well outside any reasonable spot-capture radius
        tracker.updateCapturedAngle(userLat = 41.8662, userLng = -87.6851, distanceMeters = 50.0)

        assertTrue(tracker.coveredSectors.value.isEmpty(), "expected no coverage from too far away")
    }

    @Test
    fun uncoveredSectorCentersReturnsBearingsNotYetCaptured() {
        val tracker = ObjectCoverageTracker()
        tracker.initAroundPoint(lat = 41.8617, lng = -87.6851)
        tracker.updateCapturedAngle(userLat = 41.8627, userLng = -87.6851, distanceMeters = 2.0)

        val uncovered = tracker.getUncoveredSectorCenters()
        assertTrue(uncovered.isNotEmpty())
        assertTrue(uncovered.all { it in 0.0..360.0 })
    }
}
