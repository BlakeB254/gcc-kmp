package com.gcc.app.platform

data class DeviceCapabilities(
    val hasLiDAR: Boolean,
    val hasArKit: Boolean,
    val hasArCore: Boolean,
    val supportsHighAccuracyGps: Boolean,
    val deviceClass: DeviceClass,
)

enum class DeviceClass {
    PRO_SCANNER,
    SCANNER,
    BASIC,
}

expect fun detectDeviceCapabilities(): DeviceCapabilities
