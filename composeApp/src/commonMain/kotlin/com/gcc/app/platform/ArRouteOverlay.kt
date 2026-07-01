package com.gcc.app.platform

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
