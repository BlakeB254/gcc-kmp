package com.gcc.app.platform

/**
 * iOS background location tracking during active jobs. Delegates to the
 * app's shared [LocationProvider] Koin singleton (wired in once via
 * [initLocationProvider], called from MainViewController.kt at startup) —
 * calling `LocationProvider()` directly here would construct a disconnected
 * CLLocationManager with none of the location-update state actually in use
 * elsewhere in the app.
 */
actual class LocationTrackingController {
    actual fun startTracking() {
        sharedLocationProvider?.startBackgroundTracking()
    }

    actual fun stopTracking() {
        sharedLocationProvider?.stopBackgroundTracking()
    }

    companion object {
        private var sharedLocationProvider: LocationProvider? = null

        /** Call once at app startup with the Koin-managed LocationProvider instance. */
        fun initLocationProvider(provider: LocationProvider) {
            sharedLocationProvider = provider
        }
    }
}
