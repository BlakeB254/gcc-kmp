package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android LocationProvider — will use Compass/FusedLocation in Phase 8.
 * For now provides the API surface with placeholder behavior.
 */
actual class LocationProvider actual constructor() {
    private val _currentLocation = MutableStateFlow<Coordinates?>(null)
    actual val currentLocation: StateFlow<Coordinates?> = _currentLocation.asStateFlow()

    actual val locationFlow: Flow<Coordinates> = emptyFlow()

    private var _isTracking = false
    actual val isTracking: Boolean get() = _isTracking

    actual fun startLocationUpdates() {
        _isTracking = true
        // Compass GPS integration will be wired in Phase 8
        // For development, set a default Chicago location
        _currentLocation.value = Coordinates(lat = 41.8686, lng = -87.7258)
    }

    actual fun stopLocationUpdates() {
        _isTracking = false
    }
}
