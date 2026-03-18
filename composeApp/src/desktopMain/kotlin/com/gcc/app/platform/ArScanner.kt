package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop ArScanner stub — AR is not available on desktop.
 */
actual class ArScanner actual constructor() {

    private val _isSessionActive = MutableStateFlow(false)
    actual val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    actual fun startSession() { /* no-op */ }
    actual fun stopSession() { /* no-op */ }

    actual fun placeAnchor(lat: Double, lng: Double): ArAnchor? = null

    actual fun getCameraFovDegrees(): Float = 60.0f

    actual fun getArHeading(): Float? = null
}
