package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Full-screen AR overlay for the "Begin Route" experience: directional
 * guidance toward the nearest unscanned frontier, a translucent boundary
 * wall rendered when the camera looks outside the route's assigned polygon,
 * and a fog-of-war ground overlay driven by CoverageTracker's existing
 * per-cell coverage grid.
 *
 * iOS: SceneKit (`ARSCNView`) overlay via platform.ARKit/platform.SceneKit.
 * Android/Desktop: no-op stub — on-screen AR rendering is iOS-only for v1.
 */
expect class ArRouteOverlay() {
    fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>)
    fun updateBoundaryStatus(isOutside: Boolean)
    fun updateHeading(degrees: Float)
    fun start()
    fun stop()
}

/**
 * Hosts the platform AR view that [ArRouteOverlay] renders into.
 *
 * iOS: a Compose `UIKitView` wrapping an `ARSCNView`, bound to [overlay] so
 * fog/boundary/heading updates land on live SceneKit nodes.
 * Android/Desktop: an empty placeholder — on-screen AR is iOS-only for v1.
 */
@Composable
expect fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier)
