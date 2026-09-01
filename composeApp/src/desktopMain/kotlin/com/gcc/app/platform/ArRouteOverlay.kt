package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop ArRouteOverlay stub — on-screen AR is not available on desktop.
 */
actual class ArRouteOverlay actual constructor() {
    actual val trackingState: StateFlow<TrackingState> = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    actual fun updateGeoReference(
        lat: Double,
        lng: Double,
        accuracyMeters: Double?,
        headingDegrees: Double?,
        headingAccuracyDegrees: Double?,
    ): com.gcc.shared.model.GeoRefEvent? = null
    actual fun setGridLatticeOrigin(lat: Double, lng: Double) {}
    actual fun updateBoundaries(outer: List<Pair<Double, Double>>, islands: List<List<Pair<Double, Double>>>) {}
    actual fun updateBoundaryStatus(isOutside: Boolean) {}
    actual fun start() {}
    actual fun stop() {}

    actual val liveDetections: StateFlow<List<LiveDetection>> = MutableStateFlow(emptyList())
    actual fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?) {}
    actual fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String, photoBytes: ByteArray?) -> Unit)?) {}
    actual fun setFrameAnalysisEnabled(enabled: Boolean) {}
    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {}
    actual fun setSceneVoteCadence(fast: Boolean) {}
    actual fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?) {
        // No AR raycasting off-iOS — GPS-tier crediting (updatePosition) covers these targets.
    }
    actual fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind) {}
    actual fun setNavigationTarget(lat: Double, lng: Double) {}
    actual fun clearNavigationTarget() {}
    actual fun startCapture(sessionId: String): Boolean = false
    actual fun stopCapture(): Pair<List<CaptureChunkInfo>, String?> = emptyList<CaptureChunkInfo>() to null
    actual fun discardCurrentCaptureChunk() {}
    actual fun setCaptureEnabled(enabled: Boolean) {}

    actual val tapCandidate: StateFlow<TapCandidateState> = MutableStateFlow(TapCandidateState())
    actual fun focusAt(x: Float, y: Float) {}
    actual fun confirmTapCandidate(): TapCandidateState? = null
    actual fun rejectTapCandidate(): TapCandidateState? = null
    actual fun dismissTapCandidate() {}
    actual suspend fun captureTapEvidencePhoto(): ByteArray? = null
}

/** Desktop stub — no on-screen AR view; renders an empty box. */
@Composable
actual fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize())
}
