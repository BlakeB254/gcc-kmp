package com.gcc.app.platform

/**
 * Platform permission types requested by the app.
 */
enum class Permission {
    CAMERA,
    LOCATION,
    LOCATION_BACKGROUND,
    NOTIFICATIONS,
}

/**
 * Permission request result.
 */
enum class PermissionStatus {
    GRANTED,
    DENIED,
    DENIED_PERMANENTLY,
    NOT_DETERMINED,
}

/**
 * Platform-specific permission manager.
 *
 * Android: Uses ActivityCompat / ContextCompat
 * iOS: Uses CLLocationManager / AVCaptureDevice
 * Desktop: Always returns GRANTED
 */
expect class PermissionManager() {
    /** Check current status of a permission without requesting it. */
    fun checkPermission(permission: Permission): PermissionStatus

    /** Request a permission. On mobile this shows the system dialog. */
    suspend fun requestPermission(permission: Permission): PermissionStatus

    /** Whether the user has permanently denied this permission. */
    fun isPermanentlyDenied(permission: Permission): Boolean
}
