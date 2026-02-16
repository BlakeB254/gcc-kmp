package com.gcc.app.platform

/**
 * iOS background location is handled by CLLocationManager with
 * allowsBackgroundLocationUpdates = true (set in LocationProvider).
 * No separate service management needed.
 */
actual class LocationTrackingController {
    actual fun startTracking() { /* CLLocationManager handles this */ }
    actual fun stopTracking() { /* CLLocationManager handles this */ }
}
