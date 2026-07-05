package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop SpotArOverlay stub — on-screen AR is not available on desktop.
 */
actual class SpotArOverlay actual constructor() {
    actual val trackingState: StateFlow<TrackingState> = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    actual fun setItemLocation(lat: Double, lng: Double) {}
    actual fun updateGeoReference(lat: Double, lng: Double) {}
    actual fun updateCoverage(coveredSectors: Set<Int>, uncoveredBearings: List<Double>) {}
    actual fun start() {}
    actual fun stop() {}

    actual val liveDetections: StateFlow<List<LiveDetection>> = MutableStateFlow(emptyList())
    actual fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?) {}
    actual fun setFrameAnalysisEnabled(enabled: Boolean) {}
    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {}
    actual fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind) {}
}

/** Desktop stub — no on-screen AR view; renders an empty box. */
@Composable
actual fun SpotArOverlayView(overlay: SpotArOverlay, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize())
}
