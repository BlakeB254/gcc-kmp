package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android ArRouteOverlay stub.
 * On-screen AR route rendering (ARCore/Sceneform) is deferred; the "Begin
 * Route" screen falls back to the 2D map + GPS coverage on Android.
 */
actual class ArRouteOverlay actual constructor() {
    actual val trackingState: StateFlow<TrackingState> = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    actual fun updateGeoReference(lat: Double, lng: Double) {}
    actual fun updateFog(unscannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {}
    actual fun updateBoundaryStatus(isOutside: Boolean) {}
    actual fun start() {}
    actual fun stop() {}

    actual val liveDetections: StateFlow<List<LiveDetection>> = MutableStateFlow(emptyList())
    actual fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?) {}
    actual fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String) -> Unit)?) {}
    actual fun setFrameAnalysisEnabled(enabled: Boolean) {}
    actual fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind) {}
}

/** Android stub — no on-screen AR view; renders an empty box. */
@Composable
actual fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize())
}
