package com.gcc.app.platform

import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.DepthAnalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Desktop CameraManager — no real camera, shows placeholder.
 */
actual class CameraManager actual constructor() {
    actual fun hasCameraHardware(): Boolean = false

    actual suspend fun capturePhoto(): CapturedPhoto? = null

    actual suspend fun captureFrame(): CapturedPhoto? = null

    actual companion object {
        /** No real camera on Desktop — no-op. */
        actual var sceneVoteFastMode: Boolean = true
    }
}

@Composable
actual fun CameraPreview(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "\uD83D\uDCF7",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Camera not available on Desktop",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

actual suspend fun pickFromLibrary(): CapturedPhoto? = null

actual fun embedGpsExif(jpegBytes: ByteArray, lat: Double, lng: Double): ByteArray = jpegBytes

actual fun hasLiDAR(): Boolean = false

actual fun getDeviceModel(): String = "Desktop (${System.getProperty("os.name")})"

actual fun getOsVersion(): String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
