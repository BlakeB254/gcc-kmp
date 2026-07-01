package com.gcc.app.platform

/**
 * Android ArRouteOverlay stub.
 * On-screen AR route rendering (ARCore/Sceneform) is deferred; the "Begin
 * Route" screen falls back to the 2D map + GPS coverage on Android.
 */
actual class ArRouteOverlay actual constructor() {
    actual fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {}
    actual fun updateBoundaryStatus(isOutside: Boolean) {}
    actual fun updateHeading(degrees: Float) {}
    actual fun start() {}
    actual fun stop() {}
}
