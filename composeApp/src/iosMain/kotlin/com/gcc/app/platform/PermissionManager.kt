package com.gcc.app.platform

/**
 * iOS permission manager.
 *
 * Uses CLLocationManager for location and AVCaptureDevice for camera.
 * Full native integration will be completed in Phase 8.
 */
actual class PermissionManager actual constructor() {
    actual fun checkPermission(permission: Permission): PermissionStatus {
        // Full CLLocationManager/AVCaptureDevice integration in Phase 8
        return PermissionStatus.NOT_DETERMINED
    }

    actual suspend fun requestPermission(permission: Permission): PermissionStatus {
        // Full native permission request flow in Phase 8
        return PermissionStatus.NOT_DETERMINED
    }

    actual fun isPermanentlyDenied(permission: Permission): Boolean {
        return false
    }
}
