package com.gcc.app.platform

import platform.ARKit.ARFrameSemanticSceneDepth
import platform.ARKit.ARWorldTrackingConfiguration

actual fun detectDeviceCapabilities(): DeviceCapabilities {
    val hasArKit = runCatching {
        ARWorldTrackingConfiguration.isSupported()
    }.getOrDefault(false)

    // LiDAR detection: ARKit scene-depth support is the canonical signal.
    // It returns true on iPhone Pro / iPad Pro with LiDAR sensors and false
    // everywhere else — we don't need a second AVCaptureDevice probe.
    val hasLiDAR = runCatching {
        ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSceneDepth)
    }.getOrDefault(false)

    return DeviceCapabilities(
        hasLiDAR = hasLiDAR,
        hasArKit = hasArKit,
        hasArCore = false,
        supportsHighAccuracyGps = true,
        deviceClass = when {
            hasLiDAR -> DeviceClass.PRO_SCANNER
            hasArKit -> DeviceClass.SCANNER
            else -> DeviceClass.BASIC
        },
    )
}
