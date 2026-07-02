package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Angular-coverage tracker for spot-mode guidance: which directions around a
 * single fixed point (a litter item) have been captured from, at what
 * distance. The spot-mode analog of CoverageTracker's grid-cell coverage —
 * same "uncovered centers" shape, so SpotArOverlay's arrow-guidance logic can
 * mirror ArRouteOverlay's exactly (see AiDirectionEngine.bearingTo usage).
 */
class ObjectCoverageTracker {

    companion object {
        /** Number of angular sectors the full circle around the item is divided into. */
        const val SECTOR_COUNT = 12 // 30-degree sectors
        /** Maximum distance from the item for a capture to count toward that sector. */
        const val CAPTURE_RADIUS_METERS = 5.0
    }

    private var itemLat = 0.0
    private var itemLng = 0.0

    private val _coveredSectors = MutableStateFlow<Set<Int>>(emptySet())
    val coveredSectors: StateFlow<Set<Int>> = _coveredSectors.asStateFlow()

    private val _coveragePercent = MutableStateFlow(0.0)
    val coveragePercent: StateFlow<Double> = _coveragePercent.asStateFlow()

    fun initAroundPoint(lat: Double, lng: Double) {
        itemLat = lat
        itemLng = lng
        _coveredSectors.value = emptySet()
        _coveragePercent.value = 0.0
    }

    /**
     * Called as the user's position updates during spot capture. If within
     * CAPTURE_RADIUS_METERS of the item, marks the angular sector the user is
     * currently standing in (relative to the item) as covered.
     */
    fun updateCapturedAngle(userLat: Double, userLng: Double, distanceMeters: Double) {
        if (distanceMeters > CAPTURE_RADIUS_METERS) return

        val bearing = AiDirectionEngine.bearingTo(itemLat, itemLng, userLat, userLng)
        val sector = (bearing / (360.0 / SECTOR_COUNT)).toInt().coerceIn(0, SECTOR_COUNT - 1)

        val updated = _coveredSectors.value + sector
        _coveredSectors.value = updated
        _coveragePercent.value = (updated.size.toDouble() / SECTOR_COUNT * 100.0).coerceIn(0.0, 100.0)
    }

    /** Bearings (degrees, 0=north) of sector centers not yet captured. */
    fun getUncoveredSectorCenters(): List<Double> {
        val sectorWidth = 360.0 / SECTOR_COUNT
        return (0 until SECTOR_COUNT)
            .filter { it !in _coveredSectors.value }
            .map { it * sectorWidth + sectorWidth / 2.0 }
    }

    fun reset() {
        _coveredSectors.value = emptySet()
        _coveragePercent.value = 0.0
    }
}
