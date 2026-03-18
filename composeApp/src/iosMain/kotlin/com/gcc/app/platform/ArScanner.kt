package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS ArScanner — basic ARKit session wrapper (v1).
 *
 * Full ARWorldTrackingConfiguration spatial anchor integration is deferred.
 * This provides session lifecycle management and known camera FOV for frustum coverage.
 */
actual class ArScanner actual constructor() {

    private val _isSessionActive = MutableStateFlow(false)
    actual val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    actual fun startSession() {
        _isSessionActive.value = true
    }

    actual fun stopSession() {
        _isSessionActive.value = false
    }

    /**
     * Place a lightweight anchor using GPS coordinates.
     * Full ARWorldAnchor integration is a TODO for v2.
     */
    actual fun placeAnchor(lat: Double, lng: Double): ArAnchor {
        return ArAnchor(
            id = "ar_${lat}_${lng}",
            lat = lat,
            lng = lng,
        )
    }

    /**
     * iPhone wide camera horizontal FOV ≈ 63°.
     * This matches the standard iPhone 12/13/14/15 wide lens.
     */
    actual fun getCameraFovDegrees(): Float = 63.0f

    /**
     * Camera heading from AR session.
     * TODO: wire to ARFrame.camera.eulerAngles.y in v2.
     */
    actual fun getArHeading(): Float? = null
}
