package com.gcc.app.platform

/**
 * Desktop ArRouteOverlay stub — on-screen AR is not available on desktop.
 */
actual class ArRouteOverlay actual constructor() {
    actual fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {}
    actual fun updateBoundaryStatus(isOutside: Boolean) {}
    actual fun updateHeading(degrees: Float) {}
    actual fun start() {}
    actual fun stop() {}
}
