package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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

/** Desktop stub — no on-screen AR view; renders an empty box. */
@Composable
actual fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize())
}
