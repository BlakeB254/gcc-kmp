package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.ARKit.ARCamera
import platform.ARKit.ARSession
import platform.ARKit.ARSessionDelegateProtocol
import platform.ARKit.ARTrackingState
import platform.darwin.NSObject

/**
 * iOS ARKit tracking-health observer: an ARSessionDelegate that folds
 * `session(_:cameraDidChangeTrackingState:)` into the shared [TrackingState]
 * enum, so both AR overlays can render a consistent recovery prompt.
 *
 * Verified against the MacBook Kotlin/Native compiler (this file was first
 * drafted on the Linux DGX, where iOS targets can't build).
 */
@OptIn(ExperimentalForeignApi::class)
actual class ArSessionHealth actual constructor() {

    private val _trackingState = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    actual val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val delegate = object : NSObject(), ARSessionDelegateProtocol {
        override fun session(session: ARSession, cameraDidChangeTrackingState: ARCamera) {
            _trackingState.value = when (cameraDidChangeTrackingState.trackingState) {
                ARTrackingState.ARTrackingStateNormal -> TrackingState.NORMAL
                ARTrackingState.ARTrackingStateLimited -> TrackingState.LIMITED
                ARTrackingState.ARTrackingStateNotAvailable -> TrackingState.NOT_AVAILABLE
                else -> TrackingState.NOT_AVAILABLE
            }
        }
    }

    /** Native-surface mode has no ARSessionDelegate — overlays poll the
     *  surface's raw tracking state and push it here instead. */
    internal fun overrideState(state: TrackingState) {
        _trackingState.value = state
    }

    /** Call once the ARSession exists (from ArRouteOverlay.bind / SpotArOverlay.bind). */
    fun bind(session: ARSession) {
        session.delegate = delegate
    }
}
