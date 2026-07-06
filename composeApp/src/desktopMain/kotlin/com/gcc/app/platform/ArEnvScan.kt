package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop ArEnvScanOverlay stub: the environment scan keeps the plain
 * camera viewfinder on desktop (no camera AR).
 */
actual class ArEnvScanOverlay actual constructor() {
    actual val trackingState: StateFlow<TrackingState> = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    actual val groundDetected: StateFlow<Boolean> = MutableStateFlow(false)
    actual val trashCandidate: StateFlow<TrashCandidate?> = MutableStateFlow(null)
    actual fun updateGeoReference(lat: Double, lng: Double) {}
    actual fun updateScannedCells(cellCenters: List<Pair<Double, Double>>) {}
    actual fun updateFog(cellCenters: List<Pair<Double, Double>>) {}
    actual fun setGuidanceTarget(lat: Double, lng: Double) {}
    actual fun clearGuidanceTarget() {}
    actual fun confirmCandidate(overrideItemType: String?): TrashCandidate? = null
    actual fun dismissCandidate() {}
    actual fun setElementObservedListener(
        listener: ((lat: Double, lng: Double, itemType: String, domain: String, confidence: Float) -> Unit)?,
    ) {}
    actual fun setFrameAnalysisEnabled(enabled: Boolean) {}
    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {}
    actual fun setSceneVoteCadence(fast: Boolean) {}
    actual fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?) {
        // No AR raycasting off-iOS — GPS-tier crediting (updatePosition) covers these targets.
    }
    actual fun stop() {}
}

actual fun arEnvScanSupported(): Boolean = false

/** Desktop stub — callers gate on [arEnvScanSupported]; renders an empty box. */
@Composable
actual fun ArEnvScanView(overlay: ArEnvScanOverlay, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize())
}
