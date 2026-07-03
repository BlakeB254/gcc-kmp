package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.DepthAnalysis

/**
 * Captured photo result.
 */
data class CapturedPhoto(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val depthAnalysis: DepthAnalysis? = null,
    val attestation: CaptureAttestation? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedPhoto) return false
        return bytes.contentEquals(other.bytes) && width == other.width && height == other.height && depthAnalysis == other.depthAnalysis
    }
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (depthAnalysis?.hashCode() ?: 0)
        return result
    }
}

/**
 * Platform-specific camera manager.
 *
 * Android: CameraX (PreviewView + ImageCapture)
 * iOS: AVCaptureSession + AVCapturePhotoOutput
 * Desktop: Placeholder (shows a simulated viewfinder)
 */
expect class CameraManager() {
    /** Whether the device has a camera. */
    fun hasCameraHardware(): Boolean

    /** Take a photo. Returns bytes of the captured image. */
    suspend fun capturePhoto(): CapturedPhoto?

    /**
     * Silent capture from the live preview stream — no shutter sound, no
     * depth metadata. iOS grabs a video frame; other platforms fall back
     * to [capturePhoto].
     */
    suspend fun captureFrame(): CapturedPhoto?
}

/**
 * Camera preview composable — shows the live viewfinder.
 *
 * Android: Wraps CameraX PreviewView
 * iOS: Wraps AVCaptureVideoPreviewLayer
 * Desktop: Shows a placeholder
 */
@Composable
expect fun CameraPreview(modifier: Modifier)

/**
 * Pick a photo from the device photo library.
 * Returns the selected photo as CapturedPhoto, or null if cancelled.
 *
 * iOS: Uses UIImagePickerController with photoLibrary source
 * Android: Uses system photo picker intent
 * Desktop: No-op (returns null)
 */
expect suspend fun pickFromLibrary(): CapturedPhoto?

/**
 * Embed GPS coordinates into JPEG EXIF metadata.
 * Returns new JPEG bytes with GPS tags, or original bytes if embedding fails.
 *
 * iOS: Uses CGImageDestination + kCGImagePropertyGPSDictionary
 * Desktop/Android: Returns bytes unchanged (no-op)
 */
expect fun embedGpsExif(jpegBytes: ByteArray, lat: Double, lng: Double): ByteArray

/**
 * Check if device has LiDAR sensor.
 *
 * iOS: True for devices with LiDAR scanner (iPhone Pro, iPad Pro)
 * Android/Desktop: False
 */
expect fun hasLiDAR(): Boolean

/** Returns device model string (e.g. "iPhone 15 Pro", "Pixel 8"). */
expect fun getDeviceModel(): String

/** Returns OS version string (e.g. "iOS 18.4", "Android 15"). */
expect fun getOsVersion(): String
