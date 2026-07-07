package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android ArScanner stub.
 * ARCore integration is deferred; GPS-tier coverage uses updatePosition() instead.
 */
actual class ArScanner actual constructor() {

    private val _isSessionActive = MutableStateFlow(false)
    actual val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    actual fun startSession() { /* ARCore not yet integrated */ }
    actual fun stopSession() { /* no-op */ }

    actual fun getArHeading(): Float? = null
}
