package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.ARKit.ARAnchor
import platform.ARKit.ARFrameSemanticSceneDepth
import platform.ARKit.ARFrameSemanticSmoothedSceneDepth
import platform.ARKit.ARSession
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.darwin.simd_float4x4
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.atan2
import kotlin.math.PI

/**
 * iOS ARKit session wrapper for route-scan coverage and lightweight anchors.
 */
actual class ArScanner actual constructor() {

    private val _isSessionActive = MutableStateFlow(false)
    actual val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()
    private val session: ARSession? = if (ARWorldTrackingConfiguration.isSupported()) ARSession() else null

    @OptIn(ExperimentalForeignApi::class)
    actual fun startSession() {
        val activeSession = session ?: return
        dispatch_async(dispatch_get_main_queue()) {
            val configuration = ARWorldTrackingConfiguration()
            configuration.worldAlignment = ARWorldAlignment.ARWorldAlignmentGravityAndHeading
            val frameSemantics = when {
                ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSmoothedSceneDepth) ->
                    ARFrameSemanticSmoothedSceneDepth
                ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSceneDepth) ->
                    ARFrameSemanticSceneDepth
                else -> 0uL
            }
            if (frameSemantics != 0uL) {
                configuration.frameSemantics = frameSemantics
            }
            activeSession.runWithConfiguration(configuration)
            _isSessionActive.value = true
        }
    }

    actual fun stopSession() {
        session?.pause()
        _isSessionActive.value = false
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun placeAnchor(lat: Double, lng: Double): ArAnchor? {
        val activeSession = session ?: return null
        val cameraTransform = activeSession.currentFrame?.camera?.transform ?: return null
        val anchor = ARAnchor(cameraTransform)
        activeSession.addAnchor(anchor)
        return ArAnchor(
            id = anchor.identifier.UUIDString,
            lat = lat,
            lng = lng,
            worldTransform = buildAnchorMetadata(anchor.identifier.UUIDString),
        )
    }

    /**
     * iPhone wide camera horizontal FOV ≈ 63°.
     * This matches the standard iPhone 12/13/14/15 wide lens.
     */
    actual fun getCameraFovDegrees(): Float = 63.0f

    @OptIn(ExperimentalForeignApi::class)
    actual fun getArHeading(): Float? {
        val transform = session?.currentFrame?.camera?.transform ?: return null
        val matrix = matrixFloats(transform)
        // ARKit matrices are column-major. The camera looks down -Z, so column 2
        // gives the backward vector; negating it gives forward heading.
        val forwardX = -matrix[8].toDouble()
        val forwardZ = -matrix[10].toDouble()
        val heading = ((atan2(forwardX, forwardZ) * 180.0 / PI) + 360.0) % 360.0
        return heading.toFloat()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun matrixFloats(transform: CValue<simd_float4x4>): List<Float> = memScoped {
        val matrix = alloc<simd_float4x4>()
        transform.place(matrix.ptr)
        val values = matrix.ptr.reinterpret<FloatVar>()
        List(16) { index -> values[index] }
    }

    private fun buildAnchorMetadata(anchorId: String): String {
        val heading = getArHeading()
        return buildString {
            append("{\"type\":\"arkit_anchor\",\"anchorId\":\"")
            append(anchorId)
            append("\",\"headingDegrees\":")
            if (heading != null) append(heading) else append("null")
            append(",\"hasSceneDepth\":")
            append(ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSceneDepth))
            append("}")
        }
    }
}
