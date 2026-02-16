package com.gcc.app.platform

/**
 * Platform-specific controller for background location tracking during active jobs.
 *
 * - Android: Starts/stops the [LocationTrackingService] foreground service.
 * - iOS: Background location is handled by CLLocationManager settings; this is a no-op.
 * - Desktop: No-op stub.
 */
expect class LocationTrackingController() {
    fun startTracking()
    fun stopTracking()
}
