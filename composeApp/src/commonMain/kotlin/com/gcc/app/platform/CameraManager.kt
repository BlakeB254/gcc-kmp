package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Captured photo result.
 */
data class CapturedPhoto(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedPhoto) return false
        return bytes.contentEquals(other.bytes) && width == other.width && height == other.height
    }
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
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
