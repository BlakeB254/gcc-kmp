package com.gcc.app.platform

/**
 * Android permission manager.
 *
 * Note: Full runtime permission handling requires an Activity context.
 * This provides the API surface; actual permission dialogs will be wired
 * when the Activity-based permission launcher is integrated in Phase 8.
 * For now, returns NOT_DETERMINED to signal permissions need requesting.
 */
actual class PermissionManager actual constructor() {
    actual fun checkPermission(permission: Permission): PermissionStatus {
        // Full implementation requires Activity context — will be wired in Phase 8
        return PermissionStatus.NOT_DETERMINED
    }

    actual suspend fun requestPermission(permission: Permission): PermissionStatus {
        // Full implementation requires Activity result launcher — will be wired in Phase 8
        return PermissionStatus.NOT_DETERMINED
    }

    actual fun isPermanentlyDenied(permission: Permission): Boolean {
        return false
    }
}
