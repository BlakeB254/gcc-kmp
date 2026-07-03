package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-screen AR overlay for the "Begin Route" experience: directional
 * guidance toward the nearest unscanned frontier, a red boundary veil when
 * the worker steps outside the route's assigned polygon, and a fog-of-war
 * ground overlay driven by CoverageTracker's existing per-cell coverage grid.
 *
 * Call order: [updateGeoReference] on every GPS fix (pairs the fix with the
 * AR camera position — placement is a no-op until the first pairing), then
 * [updateFog] whenever coverage changes.
 *
 * iOS: SceneKit (`ARSCNView`) overlay via platform.ARKit/platform.SceneKit.
 * Android/Desktop: no-op stub — on-screen AR rendering is iOS-only for v1.
 */
expect class ArRouteOverlay() {
    /** AR tracking quality; LIMITED should surface a "hold steady" prompt. */
    val trackingState: StateFlow<TrackingState>

    /** Pair the worker's latest GPS fix with the AR camera's current position. */
    fun updateGeoReference(lat: Double, lng: Double)

    /** Render fog over [unscannedCells]; cells absent from the set are revealed. */
    fun updateFog(unscannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>)

    fun updateBoundaryStatus(isOutside: Boolean)
    fun start()
    fun stop()

    /** Live trash detections from sampled AR frames (iOS only; empty elsewhere). */
    val liveDetections: StateFlow<List<LiveDetection>>

    /** Indoor/outdoor scene votes from sampled AR frames, for the OutdoorGate
     *  (iOS only; never invoked elsewhere). Listener is called on the main thread. */
    fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?)

    /** Pause/resume frame sampling (scene votes + detections) without tearing
     *  down the ARSession — used while the OutdoorGate reads INDOOR. */
    fun setFrameAnalysisEnabled(enabled: Boolean)

    /** Drop a persistent world-anchored marker at a lat/lng (no-op off-iOS,
     *  and before the first geo-reference pairing exists). */
    fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind)
}

/**
 * Hosts the platform AR view that [ArRouteOverlay] renders into.
 *
 * iOS: a Compose `UIKitView` wrapping an `ARSCNView`, bound to [overlay] so
 * fog/boundary updates land on live SceneKit nodes.
 * Android/Desktop: an empty placeholder — on-screen AR is iOS-only for v1.
 */
@Composable
expect fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier)
