package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Desktop LocationProvider — returns hardcoded Chicago coordinates.
 * North Lawndale center point for development/preview.
 */
actual class LocationProvider actual constructor() {
    private val chicagoCoords = Coordinates(lat = 41.8686, lng = -87.7258)
    private val _currentLocation = MutableStateFlow<Coordinates?>(chicagoCoords)
    actual val currentLocation: StateFlow<Coordinates?> = _currentLocation.asStateFlow()

    actual val locationFlow: Flow<Coordinates> = flowOf(chicagoCoords)

    /** Heading not available on desktop — always null. */
    actual val headingDegrees: StateFlow<Float?> = MutableStateFlow<Float?>(null).asStateFlow()

    private var _isTracking = false
    actual val isTracking: Boolean get() = _isTracking

    actual fun startLocationUpdates() {
        _isTracking = true
        _currentLocation.value = chicagoCoords
    }

    actual fun stopLocationUpdates() {
        _isTracking = false
    }

    actual fun startBackgroundTracking() {
        // No-op on desktop
    }

    actual fun stopBackgroundTracking() {
        // No-op on desktop
    }
}
