package com.gcc.shared.constants

import com.gcc.shared.model.Coordinates

object GccConstants {
    // Geofence config
    const val CHECKPOINT_RADIUS_METERS = 15.0
    const val GPS_ACCURACY_THRESHOLD_METERS = 20.0
    const val MIN_CHECKPOINT_DWELL_MS = 5000L
    const val PATH_RECORDING_INTERVAL_MS = 5000L

    // North Lawndale
    val NORTH_LAWNDALE_CENTER = Coordinates(lng = -87.7158, lat = 41.8666)

    // Defaults
    const val DEFAULT_CITY = "Chicago"
    const val DEFAULT_STATE = "IL"

    // API
    const val DEFAULT_API_PORT = 7310
}
