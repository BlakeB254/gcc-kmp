package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge for indoor/outdoor scene votes sampled from the plain AVCapture
 * viewfinder (spot-scan camera phase). The iOS camera preview taps its video
 * stream every ~2s, classifies the frame, and emits here; the camera phase
 * collects and feeds the OutdoorGate. AR surfaces don't use this bus — their
 * votes come straight from the AR overlays' frame pipeline.
 *
 * Global singleton because only one camera session can exist at a time
 * (ARSession and AVCapture cannot share the camera).
 */
object CameraSceneVoteBus {
    private val _votes = MutableSharedFlow<SceneVote>(extraBufferCapacity = 8)
    val votes: SharedFlow<SceneVote> = _votes

    fun emit(vote: SceneVote) {
        _votes.tryEmit(vote)
    }
}


/**
 * Live trash detections from the plain AVCapture viewfinder (same shape the
 * AR overlays expose) — drives the circle reticle on the spot camera phase.
 */
object CameraDetectionBus {
    private val _detections = MutableStateFlow<List<LiveDetection>>(emptyList())
    val detections: StateFlow<List<LiveDetection>> = _detections.asStateFlow()

    fun update(detections: List<LiveDetection>) {
        _detections.value = detections
    }
}
