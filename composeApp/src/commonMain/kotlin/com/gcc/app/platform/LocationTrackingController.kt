package com.gcc.app.platform

/**
 * Platform-specific controller for background location tracking during active jobs.
 *
 * - Android: Starts/stops the [LocationTrackingService] foreground service.
 * - iOS: Delegates to the shared LocationProvider's startBackgroundTracking()/
 *   stopBackgroundTracking() (CLLocationManager `allowsBackgroundLocationUpdates`).
 * - Desktop: No-op stub.
 */
expect class LocationTrackingController() {
    fun startTracking()
    fun stopTracking()
}
