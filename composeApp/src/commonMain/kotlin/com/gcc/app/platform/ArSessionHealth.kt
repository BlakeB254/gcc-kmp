package com.gcc.app.platform

import kotlinx.coroutines.flow.StateFlow

enum class TrackingState {
    NORMAL,
    LIMITED, // tracking degraded — fast motion, low light, insufficient features
    NOT_AVAILABLE, // no tracking at all
}

/**
 * Wraps the platform AR session's tracking-quality signal so
 * ArRouteOverlay can show a consistent "hold steady" /
 * "find more light" recovery prompt and suspend guidance updates during
 * degraded tracking, per design spec §1.4.
 *
 * iOS: observes ARSession tracking-state changes via an ARSessionDelegate.
 * Android/Desktop: permanently NOT_AVAILABLE (no on-screen AR on these
 * targets for v1), so consumers naturally render no recovery prompt.
 */
expect class ArSessionHealth() {
    val trackingState: StateFlow<TrackingState>
}
