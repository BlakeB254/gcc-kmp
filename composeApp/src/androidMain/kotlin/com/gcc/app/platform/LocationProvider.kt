package com.gcc.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LocationProvider"

/** Minimum time between GPS updates in milliseconds. */
private const val MIN_TIME_MS = 3_000L

/** Minimum distance between GPS updates in meters. */
private const val MIN_DISTANCE_M = 5f

/**
 * Android LocationProvider -- uses android.location.LocationManager for real GPS tracking.
 *
 * Context acquisition:
 * Since the expect declaration requires a no-arg constructor, we use a companion object
 * that holds the application Context. [initContext] must be called once from
 * [com.gcc.app.MainActivity.onCreate] before location updates are requested.
 *
 * If context is not available when [startLocationUpdates] is called, a default
 * Chicago coordinate is emitted as a fallback and a warning is logged.
 */
actual class LocationProvider actual constructor() {

    private val _currentLocation = MutableStateFlow<Coordinates?>(null)
    actual val currentLocation: StateFlow<Coordinates?> = _currentLocation.asStateFlow()

    private val _locationFlow = MutableSharedFlow<Coordinates>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    actual val locationFlow: Flow<Coordinates> = _locationFlow.asSharedFlow()

    private var _isTracking = false
    actual val isTracking: Boolean get() = _isTracking

    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val coords = Coordinates(lat = location.latitude, lng = location.longitude)
            _currentLocation.value = coords
            _locationFlow.tryEmit(coords)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // Deprecated in API 29+ but required for older devices
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "Provider disabled: $provider")
        }
    }

    /**
     * Start receiving GPS location updates.
     *
     * Requests updates from both GPS_PROVIDER and NETWORK_PROVIDER for best
     * coverage. GPS provides high accuracy outdoors; NETWORK provides a faster
     * initial fix and works indoors.
     *
     * Requires ACCESS_FINE_LOCATION permission to be granted. If permission has
     * not been granted, a SecurityException is caught and a fallback location
     * is emitted.
     */
    @SuppressLint("MissingPermission")
    actual fun startLocationUpdates() {
        if (_isTracking) return

        val ctx = appContext
        if (ctx == null) {
            Log.w(TAG, "App context not initialized. Call LocationProvider.initContext() from MainActivity. Using fallback coordinates.")
            _currentLocation.value = FALLBACK_CHICAGO
            _locationFlow.tryEmit(FALLBACK_CHICAGO)
            _isTracking = true
            return
        }

        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            Log.e(TAG, "LocationManager service not available")
            _currentLocation.value = FALLBACK_CHICAGO
            _locationFlow.tryEmit(FALLBACK_CHICAGO)
            _isTracking = true
            return
        }

        locationManager = manager
        _isTracking = true

        try {
            // Try GPS provider first (high accuracy, outdoor)
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper(),
                )
                Log.d(TAG, "Registered GPS_PROVIDER listener")
            }

            // Also register network provider for faster initial fix
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper(),
                )
                Log.d(TAG, "Registered NETWORK_PROVIDER listener")
            }

            // Use last known location for immediate availability
            val lastKnown = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (lastKnown != null) {
                val coords = Coordinates(lat = lastKnown.latitude, lng = lastKnown.longitude)
                _currentLocation.value = coords
                _locationFlow.tryEmit(coords)
                Log.d(TAG, "Using last known location: ${coords.lat}, ${coords.lng}")
            } else {
                Log.d(TAG, "No last known location available, waiting for GPS fix...")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted. Using fallback coordinates.", e)
            _currentLocation.value = FALLBACK_CHICAGO
            _locationFlow.tryEmit(FALLBACK_CHICAGO)
        }
    }

    /**
     * Stop receiving location updates.
     *
     * Unregisters the location listener from the LocationManager to conserve
     * battery and release GPS hardware.
     */
    actual fun stopLocationUpdates() {
        if (!_isTracking) return
        _isTracking = false

        try {
            locationManager?.removeUpdates(locationListener)
            Log.d(TAG, "Location updates stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }

        locationManager = null
    }

    companion object {
        /** Default fallback location: GCC service area in Chicago. */
        private val FALLBACK_CHICAGO = Coordinates(lat = 41.8686, lng = -87.7258)

        /**
         * Application context used to access LocationManager.
         * Must be set from MainActivity.onCreate() or Application.onCreate().
         */
        @Volatile
        private var appContext: Context? = null

        /**
         * Initialize the LocationProvider with the application context.
         * Call this once from [com.gcc.app.MainActivity.onCreate].
         *
         * @param context The application context (NOT an Activity context, to avoid leaks).
         */
        fun initContext(context: Context) {
            appContext = context.applicationContext
        }
    }
}
