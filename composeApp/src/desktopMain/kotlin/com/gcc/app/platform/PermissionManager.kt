package com.gcc.app.platform

/**
 * Desktop permission manager — always grants permissions.
 * Desktop JVM has no runtime permission system.
 */
actual class PermissionManager actual constructor() {
    actual fun checkPermission(permission: Permission): PermissionStatus {
        return PermissionStatus.GRANTED
    }

    actual suspend fun requestPermission(permission: Permission): PermissionStatus {
        return PermissionStatus.GRANTED
    }

    actual fun isPermanentlyDenied(permission: Permission): Boolean {
        return false
    }
}
