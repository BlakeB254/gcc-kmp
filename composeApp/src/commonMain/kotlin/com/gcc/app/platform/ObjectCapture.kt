package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Guided Object Capture stages (mirrors ObjectCaptureSession's lifecycle). */
enum class ObjectCaptureStage {
    UNSUPPORTED,
    INITIALIZING,
    READY,
    DETECTING,
    CAPTURING,
    RECONSTRUCTING,
    COMPLETED,
    FAILED,
}

/** Final output of a capture pass; [usdzPath] is a local file until uploaded. */
data class ObjectCaptureOutcome(
    val usdzPath: String?,
    val widthMeters: Double,
    val heightMeters: Double,
    val depthMeters: Double,
    val volumeLiters: Double,
)

/**
 * Cross-platform face of the guided 3D capture flow (iOS 17 Object Capture +
 * on-device photogrammetry). Swift owns the session/UI/reconstruction behind
 * the registration-pattern bridge (see iosMain `ObjectCaptureBridge.kt`);
 * this controller exposes stage/progress/outcome as flows and forwards the
 * three user actions. Unsupported platforms (Android/Desktop, non-LiDAR
 * iPhones) never show the entry point — gate on [objectCaptureSupported].
 */
expect class ObjectCaptureController() {
    val stage: StateFlow<ObjectCaptureStage>
    /** Reconstruction progress 0..1 while [ObjectCaptureStage.RECONSTRUCTING]. */
    val progress: StateFlow<Double>
    val outcome: StateFlow<ObjectCaptureOutcome?>

    /** Advance the guided flow (ready → detecting → capturing). */
    fun advance()

    /** Done orbiting — start reconstruction. */
    fun finishCapture()

    fun cancel()
}

/** True when the device can run guided Object Capture (LiDAR + A14+, iOS 17). */
expect fun objectCaptureSupported(): Boolean

/** Hosts the native guided-capture view (point cloud, reticle, feedback). */
@Composable
expect fun ObjectCaptureHostView(controller: ObjectCaptureController, modifier: Modifier)

/** Read a local file produced by the capture flow (USDZ upload path). */
expect fun readLocalFileBytes(path: String): ByteArray?

/**
 * Present the system AR Quick Look preview for a local USDZ file — pinch,
 * orbit, and place-in-room come free from the OS. No-op off-iOS.
 */
expect fun presentUsdzPreview(path: String)
