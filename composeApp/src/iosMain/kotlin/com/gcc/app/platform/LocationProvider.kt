package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLDistanceFilterNone
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

    private val _headingDegrees = MutableStateFlow<Float?>(null)
    actual val headingDegrees: StateFlow<Float?> = _headingDegrees.asStateFlow()

    private val _horizontalAccuracyMeters = MutableStateFlow<Double?>(null)
    actual val horizontalAccuracyMeters: StateFlow<Double?> = _horizontalAccuracyMeters.asStateFlow()

    /** CLLocation reports negative horizontalAccuracy for invalid fixes. */
    private fun recordAccuracy(location: CLLocation) {
        _horizontalAccuracyMeters.value = location.horizontalAccuracy.takeIf { it >= 0.0 }
    }

    private var _isTracking = false
    actual val isTracking: Boolean get() = _isTracking

    /** Reference count — multiple screens can start/stop without interfering. */
    private var refCount = 0

    private val locationManager = CLLocationManager()

    /** Hot stream of fixes fanned out to every [locationFlow] collector. */
    private val locationUpdates = MutableSharedFlow<Coordinates>(replay = 1, extraBufferCapacity = 16)

    /**
     * The ONE CLLocationManager delegate. CLLocationManager has a single
     * delegate slot and only calls back a delegate that responds to the
     * selector — a second delegate without didUpdateHeading silently kills
     * the compass stream (this bug hid the heading cone and frustum-sweep
     * crediting in the field). Never install another delegate.
     */
    private val sharedDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
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
            recordAccuracy(clLocation)
            locationUpdates.tryEmit(coords)
        }

        override fun locationManager(
            manager: CLLocationManager,
            didUpdateHeading: CLHeading,
        ) {
            val trueHeading = didUpdateHeading.trueHeading
            val heading = if (trueHeading >= 0) trueHeading else didUpdateHeading.magneticHeading
            _headingDegrees.value = heading.toFloat()
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

    /**
     * A cold Flow that emits location updates while collected. Collectors
     * share the refcounted CLLocationManager session with [startLocationUpdates]
     * instead of installing their own delegate.
     */
    actual val locationFlow: Flow<Coordinates> = callbackFlow {
        startLocationUpdates()
        val relay = launch {
            locationUpdates.collect { trySend(it) }
        }
        awaitClose {
            relay.cancel()
            stopLocationUpdates()
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

        dispatch_async(dispatch_get_main_queue()) {
            locationManager.delegate = sharedDelegate
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
            // Continuous updates (no distance filter): the OutdoorGate needs a steady
            // stream of accuracy samples even when the worker is standing still —
            // with the old 10m filter an indoor (stationary) user produced one fix
            // and then silence, so the gate could never accumulate evidence.
            locationManager.distanceFilter = kCLDistanceFilterNone
            locationManager.headingFilter = 5.0   // Update every 5 degrees of change
            locationManager.requestWhenInUseAuthorization()
            locationManager.startUpdatingLocation()
            locationManager.startUpdatingHeading()
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

        dispatch_async(dispatch_get_main_queue()) {
            locationManager.stopUpdatingLocation()
            locationManager.stopUpdatingHeading()
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
                manager.startUpdatingHeading()
            }
        }
    }
}
