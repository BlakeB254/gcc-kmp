package com.gcc.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** iOS Object Capture controller: drives the registered Swift bridge. */
actual class ObjectCaptureController actual constructor() {

    private var surface: ObjectCaptureBridgeSurface? = null

    private val _stage = MutableStateFlow(ObjectCaptureStage.INITIALIZING)
    actual val stage: StateFlow<ObjectCaptureStage> = _stage.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    actual val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _outcome = MutableStateFlow<ObjectCaptureOutcome?>(null)
    actual val outcome: StateFlow<ObjectCaptureOutcome?> = _outcome.asStateFlow()

    /** Called by the host view factory; failure surfaces as FAILED stage. */
    fun bind(surface: ObjectCaptureBridgeSurface) {
        this.surface = surface
        surface.setListener(
            onState = { raw, progress ->
                _progress.value = progress
                _stage.value = when (raw) {
                    0 -> ObjectCaptureStage.INITIALIZING
                    1 -> ObjectCaptureStage.READY
                    2 -> ObjectCaptureStage.DETECTING
                    3 -> ObjectCaptureStage.CAPTURING
                    4 -> ObjectCaptureStage.RECONSTRUCTING
                    5 -> ObjectCaptureStage.COMPLETED
                    else -> ObjectCaptureStage.FAILED
                }
            },
            onResult = { usdzPath, width, height, depth, volumeLiters ->
                _outcome.value = ObjectCaptureOutcome(
                    usdzPath = usdzPath,
                    widthMeters = width,
                    heightMeters = height,
                    depthMeters = depth,
                    volumeLiters = volumeLiters,
                )
            },
        )
        DiagnosticsLog.info("object-capture", "guided capture surface bound")
    }

    actual fun advance() {
        surface?.advance()
    }

    actual fun finishCapture() {
        surface?.finishCapture()
    }

    actual fun cancel() {
        surface?.cancel()
        surface?.setListener(null, null)
        surface = null
    }
}

actual fun objectCaptureSupported(): Boolean = ObjectCaptureRegistry.factory != null

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ObjectCaptureHostView(controller: ObjectCaptureController, modifier: Modifier) {
    val factory = ObjectCaptureRegistry.factory
    if (factory == null) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
        return
    }
    UIKitView(
        factory = {
            try {
                val surface = factory()
                controller.bind(surface)
                surface.view()
            } catch (e: Throwable) {
                DiagnosticsLog.error("object-capture", "surface creation failed", e)
                platform.UIKit.UIView()
            }
        },
        modifier = modifier,
        onRelease = {
            controller.cancel()
        },
    )
}

@OptIn(ExperimentalForeignApi::class)
actual fun readLocalFileBytes(path: String): ByteArray? {
    val data: NSData = NSData.dataWithContentsOfFile(path) ?: return null
    val length = data.length.toInt()
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
    return bytes
}
