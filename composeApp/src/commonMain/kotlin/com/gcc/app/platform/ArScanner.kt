package com.gcc.app.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight AR anchor representing a real-world pinned location.
 * @param worldTransform JSON-encoded 4x4 column-major transform matrix (AR tier).
 */
data class ArAnchor(
    val id: String,
    val lat: Double,
    val lng: Double,
    val worldTransform: String? = null,
)

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

    /** Place a spatial anchor at the given GPS position. Returns ArAnchor or null if unsupported. */
    fun placeAnchor(lat: Double, lng: Double): ArAnchor?

    /** Horizontal camera field-of-view in degrees. */
    fun getCameraFovDegrees(): Float

    /** Camera heading from AR session (more accurate than compass). Null if unavailable. */
    fun getArHeading(): Float?
}
