package com.gcc.app.platform

import platform.UIKit.UIView

/**
 * Contract for the native Swift Object Capture module (ObjectCaptureSession +
 * ObjectCaptureView + PhotogrammetrySession are all Swift-only). Same
 * registration pattern as [ArNativeSurface]: Swift implements this and sets
 * [ObjectCaptureRegistry.factory] at launch — but ONLY on hardware that
 * passes `ObjectCaptureSession.isSupported && PhotogrammetrySession.isSupported`,
 * so a registered factory IS the capability check.
 *
 * State raw values: 0 initializing, 1 ready, 2 detecting, 3 capturing,
 * 4 reconstructing, 5 completed, 6 failed.
 */
interface ObjectCaptureBridgeSurface {
    /** The guided capture UI (SwiftUI ObjectCaptureView, UIKit-hosted). */
    fun view(): UIView

    /** Advance the guided flow (ready → detecting → capturing). */
    fun advance()

    /** Done orbiting — run photogrammetry reconstruction. */
    fun finishCapture()

    /** Abort; the surface cleans its temp files. */
    fun cancel()

    /**
     * [onState] fires on every stage change (raw code + reconstruction
     * progress 0..1); [onResult] fires once — usdzPath null on failure,
     * then width/height/depth meters and volumeLiters (bbox × fill factor).
     * Both called on the main thread.
     */
    fun setListener(
        onState: ((Int, Double) -> Unit)?,
        onResult: ((String?, Double, Double, Double, Double) -> Unit)?,
    )
}

object ObjectCaptureRegistry {
    var factory: (() -> ObjectCaptureBridgeSurface)? = null
}
