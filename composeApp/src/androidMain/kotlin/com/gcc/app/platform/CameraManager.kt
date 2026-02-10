package com.gcc.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Android CameraManager — CameraX integration.
 * Full PreviewView + ImageCapture wiring happens in Phase 8
 * when the Activity context is properly available.
 */
actual class CameraManager actual constructor() {
    actual fun hasCameraHardware(): Boolean = true

    actual suspend fun capturePhoto(): CapturedPhoto? {
        // CameraX ImageCapture will be wired in Phase 8
        return null
    }
}

@Composable
actual fun CameraPreview(modifier: Modifier) {
    // CameraX PreviewView will be wired in Phase 8
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Camera Preview (Android)",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
