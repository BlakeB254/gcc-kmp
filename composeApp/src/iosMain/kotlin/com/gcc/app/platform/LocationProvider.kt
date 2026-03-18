package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS LocationProvider -- uses CLLocationManager for real GPS location updates.
 *
 * Requests when-in-use authorization and emits location updates through both
 * a StateFlow (latest snapshot) and a Flow (stream of updates).
 */
actual class LocationProvider actual constructor() {

    private val _currentLocation = MutableStateFlow<Coordinates?>(null)
    actual val currentLocation: StateFlow<Coordinates?> = _currentLocation.asStateFlow()

    private var _isTracking = false
    actual val isTracking: Boolean get() = _isTracking

    /** Reference count — multiple screens can start/stop without interfering. */
    private var refCount = 0

    private val locationManager = CLLocationManager()
    private var locationDelegate: NSObject? = null

    /**
     * A cold Flow that emits location updates while collected.
     * Each collector gets its own delegate subscription but shares the
     * underlying CLLocationManager. The StateFlow is also updated from here.
     */
    actual val locationFlow: Flow<Coordinates> = callbackFlow {
        val flowDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(
                manager: CLLocationManager,
                didUpdateLocations: List<*>,
            ) {
                val clLocation = didUpdateLocations.lastOrNull() as? CLLocation ?: return
                @OptIn(ExperimentalForeignApi::class)
                val coords = clLocation.coordinate.useContents {
                    Coordinates(lat = latitude, lng = longitude)
                }
                _currentLocation.value = coords
                trySend(coords)
            }

            override fun locationManager(
                manager: CLLocationManager,
                didFailWithError: platform.Foundation.NSError,
            ) {
                // Location errors are non-fatal; CLLocationManager will retry
                // automatically. We log nothing here to avoid noise.
            }

            override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                // If authorization was just granted, start updates
                handleAuthorizationChange(manager)
            }
        }

        dispatch_async(dispatch_get_main_queue()) {
            locationManager.delegate = flowDelegate
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
            locationManager.distanceFilter = 10.0 // Only update when moved 10+ meters
            locationManager.requestWhenInUseAuthorization()
            locationManager.startUpdatingLocation()
        }

        awaitClose {
            dispatch_async(dispatch_get_main_queue()) {
                locationManager.stopUpdatingLocation()
                locationManager.delegate = null
            }
        }
    }

    /**
     * Start receiving location updates. Updates are pushed into [currentLocation].
     * Call [stopLocationUpdates] to stop.
     */
    actual fun startLocationUpdates() {
        refCount++
        if (_isTracking) return
        _isTracking = true

        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(
                manager: CLLocationManager,
                didUpdateLocations: List<*>,
            ) {
                val clLocation = didUpdateLocations.lastOrNull() as? CLLocation ?: return
                @OptIn(ExperimentalForeignApi::class)
                val coords = clLocation.coordinate.useContents {
                    Coordinates(lat = latitude, lng = longitude)
                }
                _currentLocation.value = coords
            }

            override fun locationManager(
                manager: CLLocationManager,
                didFailWithError: platform.Foundation.NSError,
            ) {
                // Non-fatal; CLLocationManager retries automatically
            }

            override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                handleAuthorizationChange(manager)
            }
        }
        locationDelegate = delegate

        dispatch_async(dispatch_get_main_queue()) {
            locationManager.delegate = delegate
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
            locationManager.distanceFilter = 10.0 // Only update when moved 10+ meters
            locationManager.requestWhenInUseAuthorization()
            locationManager.startUpdatingLocation()
        }
    }

    /**
     * Stop receiving location updates to conserve battery.
     */
    actual fun stopLocationUpdates() {
        if (refCount > 0) refCount--
        if (refCount > 0) return  // Other screens still need location
        if (!_isTracking) return
        _isTracking = false
        locationDelegate = null

        dispatch_async(dispatch_get_main_queue()) {
            locationManager.stopUpdatingLocation()
            locationManager.allowsBackgroundLocationUpdates = false
            locationManager.delegate = null
        }
    }

    /**
     * Enable background location tracking for active job sessions.
     *
     * Upgrades from when-in-use to always authorization and enables
     * allowsBackgroundLocationUpdates so the OS continues delivering
     * location updates when the app is backgrounded.
     *
     * Also starts significant location change monitoring as a fallback —
     * if the app is suspended, significant changes will relaunch it.
     */
    actual fun startBackgroundTracking() {
        dispatch_async(dispatch_get_main_queue()) {
            locationManager.requestAlwaysAuthorization()
            locationManager.allowsBackgroundLocationUpdates = true
            locationManager.pausesLocationUpdatesAutomatically = false
            locationManager.startMonitoringSignificantLocationChanges()

            if (!_isTracking) {
                startLocationUpdates()
            }
        }
    }

    actual fun stopBackgroundTracking() {
        dispatch_async(dispatch_get_main_queue()) {
            locationManager.allowsBackgroundLocationUpdates = false
            locationManager.pausesLocationUpdatesAutomatically = true
            locationManager.stopMonitoringSignificantLocationChanges()
        }
    }

    private fun handleAuthorizationChange(manager: CLLocationManager) {
        if (_isTracking) {
            dispatch_async(dispatch_get_main_queue()) {
                manager.startUpdatingLocation()
            }
        }
    }
}
