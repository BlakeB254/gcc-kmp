package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-screen AR overlay for spot-scan mode: sector markers ringing a single
 * litter item show which angles still need capturing (ObjectCoverageTracker),
 * with the same look-at guidance arrow and tracking-health signal as
 * ArRouteOverlay.
 *
 * Structurally parallel to ArRouteOverlay rather than a shared base class —
 * see the 2026-07-02 plan's "Deviation" note for why. Call order mirrors it:
 * [setItemLocation] once, [updateGeoReference] on every GPS fix, then
 * [updateCoverage] as sectors fill in.
 *
 * iOS: SceneKit. Android/Desktop: no-op stub.
 */
expect class SpotArOverlay() {
    /** AR tracking quality; LIMITED should surface a "hold steady" prompt. */
    val trackingState: StateFlow<TrackingState>

    /** The litter item the worker is circling — anchor for the sector ring. */
    fun setItemLocation(lat: Double, lng: Double)

    /** Pair the worker's latest GPS fix with the AR camera's current position. */
    fun updateGeoReference(lat: Double, lng: Double)

    /** Render markers for uncovered sector bearings; covered sectors are removed. */
    fun updateCoverage(coveredSectors: Set<Int>, uncoveredBearings: List<Double>)

    fun start()
    fun stop()
}

/**
 * Hosts the platform AR view that [SpotArOverlay] renders into.
 * iOS: a Compose `UIKitView` wrapping an `ARSCNView`.
 * Android/Desktop: an empty placeholder — on-screen AR is iOS-only for v1.
 */
@Composable
expect fun SpotArOverlayView(overlay: SpotArOverlay, modifier: Modifier)
