package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific location provider.
 *
 * Android/iOS: Uses Compass library (GPS/FusedLocation/CLLocation)
 * Desktop: Returns hardcoded Chicago coordinates for development
 *
 * Usage:
 * - Call startLocationUpdates() to begin receiving GPS data
 * - Collect from locationFlow to get updates
 * - Call stopLocationUpdates() to conserve battery
 */
expect class LocationProvider() {
    /** Current location as a StateFlow (null until first fix). */
    val currentLocation: StateFlow<Coordinates?>

    /** Flow of location updates. */
    val locationFlow: Flow<Coordinates>

    /** Start receiving location updates. */
    fun startLocationUpdates()

    /** Stop receiving location updates. */
    fun stopLocationUpdates()

    /** Whether location services are currently active. */
    val isTracking: Boolean
}
