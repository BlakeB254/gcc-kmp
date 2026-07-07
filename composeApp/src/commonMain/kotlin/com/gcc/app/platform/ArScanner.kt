package com.gcc.app.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific AR scanner.
 *
 * iOS: ARKit ARWorldTrackingConfiguration (basic session wrapper for v1).
 * Android/Desktop: Stub — always inactive, FOV 60°, heading null.
 */
expect class ArScanner() {
    val isSessionActive: StateFlow<Boolean>

    fun startSession()
    fun stopSession()

    /** Camera heading from AR session (more accurate than compass). Null if unavailable. */
    fun getArHeading(): Float?
}
