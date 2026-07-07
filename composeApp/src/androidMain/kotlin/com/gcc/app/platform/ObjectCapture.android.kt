package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Android stub — guided Object Capture is iOS-only (RealityKit). */
actual class ObjectCaptureController actual constructor() {
    actual val stage: StateFlow<ObjectCaptureStage> = MutableStateFlow(ObjectCaptureStage.UNSUPPORTED)
    actual val progress: StateFlow<Double> = MutableStateFlow(0.0)
    actual val outcome: StateFlow<ObjectCaptureOutcome?> = MutableStateFlow(null)
    actual fun advance() {}
    actual fun finishCapture() {}
    actual fun cancel() {}
}

actual fun objectCaptureSupported(): Boolean = false

@Composable
actual fun ObjectCaptureHostView(controller: ObjectCaptureController, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize())
}

actual fun readLocalFileBytes(path: String): ByteArray? =
    runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()

actual fun deleteLocalFile(path: String) {
    runCatching { File(path).delete() }
}

actual fun presentUsdzPreview(path: String) {}
