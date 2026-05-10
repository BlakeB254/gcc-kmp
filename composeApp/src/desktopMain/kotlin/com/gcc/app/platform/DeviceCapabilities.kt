package com.gcc.app.platform

actual fun detectDeviceCapabilities(): DeviceCapabilities =
    DeviceCapabilities(
        hasLiDAR = false,
        hasArKit = false,
        hasArCore = false,
        supportsHighAccuracyGps = false,
        deviceClass = DeviceClass.BASIC,
    )
