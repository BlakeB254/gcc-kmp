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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.fileDataRepresentation
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerEditedImage
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * Resize a UIImage so its longest side is at most [maxDimension] pixels.
 * Returns the original image if it's already within bounds.
 */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.resizedToMax(maxDimension: Double = 1920.0): UIImage {
    val w = size.useContents { width }
    val h = size.useContents { height }
    if (w <= maxDimension && h <= maxDimension) return this

    val scale = minOf(maxDimension / w, maxDimension / h)
    val newW = w * scale
    val newH = h * scale

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(newW, newH), false, 1.0)
    drawInRect(platform.CoreGraphics.CGRectMake(0.0, 0.0, newW, newH))
    val resized = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return resized ?: this
}

/**
 * Delegate object for AVCapturePhotoOutput that receives captured photos.
 * Declared as a concrete class to properly satisfy Kotlin/Native ObjC interop
 * requirements for the AVCapturePhotoCaptureDelegate protocol.
 */
private class PhotoCaptureDelegate(
    private val onComplete: (NSData?) -> Unit,
) : NSObject(), platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol {
    @Suppress("CONFLICTING_OVERLOADS")
    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) {
            onComplete(null)
            return
        }
        onComplete(didFinishProcessingPhoto.fileDataRepresentation())
    }
}

/**
 * iOS CameraManager — uses AVCapturePhotoOutput for inline capture from the
 * live preview session, with UIImagePickerController as fallback when the
 * preview is not active.
 *
 * Architecture mirrors Android: a companion object bridges the CameraPreview
 * composable (which owns the AVCaptureSession) with capturePhoto().
 */
actual class CameraManager actual constructor() {

    actual fun hasCameraHardware(): Boolean {
        return UIImagePickerController.isSourceTypeAvailable(
            UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        )
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual suspend fun capturePhoto(): CapturedPhoto? {
        if (!hasCameraHardware()) return null

        // If preview is active, capture inline from the AVCaptureSession
        val photoOutput = activePhotoOutput
        if (photoOutput != null) {
            return captureFromPreviewSession(photoOutput)
        }

        // Fallback: launch UIImagePickerController when preview isn't active
        return fallbackCapture()
    }

    /**
     * Capture a still photo from the live AVCaptureSession via AVCapturePhotoOutput.
     * No modal UI — instant capture from the preview feed.
     */
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun captureFromPreviewSession(
        photoOutput: AVCapturePhotoOutput,
    ): CapturedPhoto? = suspendCancellableCoroutine { continuation ->
        dispatch_async(dispatch_get_main_queue()) {
            val settings = AVCapturePhotoSettings.photoSettings()

            val delegate = PhotoCaptureDelegate { fileData ->
                if (fileData == null || fileData.length.toInt() == 0) {
                    continuation.resume(null)
                    return@PhotoCaptureDelegate
                }

                val image = UIImage(data = fileData).resizedToMax(1920.0)
                val jpegData: NSData? = UIImageJPEGRepresentation(image, 0.80)
                if (jpegData == null || jpegData.length.toInt() == 0) {
                    continuation.resume(null)
                    return@PhotoCaptureDelegate
                }

                val bytes = jpegData.bytes?.readBytes(jpegData.length.toInt())
                if (bytes == null) {
                    continuation.resume(null)
                    return@PhotoCaptureDelegate
                }

                val width = image.size.useContents { width.toInt() }
                val height = image.size.useContents { height.toInt() }

                continuation.resume(
                    CapturedPhoto(
                        bytes = bytes,
                        width = width,
                        height = height,
                    )
                )
            }

            photoOutput.capturePhotoWithSettings(settings, delegate = delegate)
        }
    }

    /**
     * Fallback capture using UIImagePickerController — used when the camera
     * preview composable is not active.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private suspend fun fallbackCapture(): CapturedPhoto? =
        suspendCancellableCoroutine { continuation ->
            dispatch_async(dispatch_get_main_queue()) {
                val rootVc = UIApplication.sharedApplication.connectedScenes
                    .filterIsInstance<UIWindowScene>()
                    .firstOrNull()
                    ?.windows
                    ?.filterIsInstance<UIWindow>()
                    ?.firstOrNull { it.isKeyWindow() }
                    ?.rootViewController
                    ?: UIApplication.sharedApplication.keyWindow?.rootViewController
                if (rootVc == null) {
                    continuation.resume(null)
                    return@dispatch_async
                }

                val picker = UIImagePickerController()
                picker.sourceType =
                    UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera

                val delegate = object : NSObject(),
                    UIImagePickerControllerDelegateProtocol,
                    UINavigationControllerDelegateProtocol {

                    override fun imagePickerController(
                        picker: UIImagePickerController,
                        didFinishPickingMediaWithInfo: Map<Any?, *>,
                    ) {
                        picker.dismissViewControllerAnimated(true) {
                            val rawImage = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage]
                                ?: didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage]) as? UIImage

                            if (rawImage == null) {
                                continuation.resume(null)
                                return@dismissViewControllerAnimated
                            }

                            val image = rawImage.resizedToMax(1920.0)
                            val jpegData: NSData? = UIImageJPEGRepresentation(image, 0.80)
                            if (jpegData == null || jpegData.length.toInt() == 0) {
                                continuation.resume(null)
                                return@dismissViewControllerAnimated
                            }

                            val bytes = jpegData.bytes?.readBytes(jpegData.length.toInt())
                            if (bytes == null) {
                                continuation.resume(null)
                                return@dismissViewControllerAnimated
                            }

                            val width = image.size.useContents { width.toInt() }
                            val height = image.size.useContents { height.toInt() }

                            continuation.resume(
                                CapturedPhoto(
                                    bytes = bytes,
                                    width = width,
                                    height = height,
                                )
                            )
                        }
                    }

                    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                        picker.dismissViewControllerAnimated(true) {
                            continuation.resume(null)
                        }
                    }
                }

                picker.delegate = delegate
                rootVc.presentViewController(picker, animated = true, completion = null)

                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) {
                        picker.dismissViewControllerAnimated(false, completion = null)
                    }
                }
            }
        }

    companion object {
        internal var activePhotoOutput: AVCapturePhotoOutput? = null
        internal var activeSession: AVCaptureSession? = null
    }
}

/**
 * Strong reference to the active library picker delegate.
 * Prevents Kotlin/Native GC from collecting the delegate while the picker is presented.
 * UIImagePickerController.delegate is a WEAK reference, so without this the delegate
 * gets collected and callbacks never fire.
 */
private var activeLibraryDelegate: LibraryPickerDelegate? = null

/**
 * Concrete delegate class for UIImagePickerController.
 *
 * IMPORTANT: Must be a named class (not anonymous object) to prevent Kotlin/Native
 * from garbage-collecting the delegate before the callback fires.
 */
private class LibraryPickerDelegate(
    private val onResult: (UIImage?) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage]
            ?: didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage]) as? UIImage
        picker.dismissViewControllerAnimated(true) {
            activeLibraryDelegate = null // release strong ref
            onResult(image)
        }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true) {
            activeLibraryDelegate = null
            onResult(null)
        }
    }
}

/**
 * Pick a photo from the photo library using UIImagePickerController.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun pickFromLibrary(): CapturedPhoto? {
    return suspendCancellableCoroutine { continuation ->
        dispatch_async(dispatch_get_main_queue()) {
            // Use connectedScenes API (keyWindow is deprecated on iOS 15+)
            val rootVc = UIApplication.sharedApplication.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .firstOrNull()
                ?.windows
                ?.filterIsInstance<UIWindow>()
                ?.firstOrNull { it.isKeyWindow() }
                ?.rootViewController
                ?: UIApplication.sharedApplication.keyWindow?.rootViewController
            if (rootVc == null) {
                continuation.resume(null)
                return@dispatch_async
            }
            // Present from the topmost presented VC to avoid "already presenting" errors
            var topVc: platform.UIKit.UIViewController = rootVc
            while (topVc.presentedViewController != null) {
                topVc = topVc.presentedViewController!!
            }

            val delegate = LibraryPickerDelegate { rawImage ->
                if (rawImage == null) {
                    continuation.resume(null)
                    return@LibraryPickerDelegate
                }

                // Resize to max 1920px to stay under Vercel's body size limit
                val image = rawImage.resizedToMax(1920.0)

                val jpegData: NSData? = UIImageJPEGRepresentation(image, 0.80)
                if (jpegData == null || jpegData.length.toInt() == 0) {
                    continuation.resume(null)
                    return@LibraryPickerDelegate
                }

                val bytes = jpegData.bytes?.readBytes(jpegData.length.toInt())
                if (bytes == null) {
                    continuation.resume(null)
                    return@LibraryPickerDelegate
                }

                val width = image.size.useContents { width.toInt() }
                val height = image.size.useContents { height.toInt() }

                continuation.resume(
                    CapturedPhoto(bytes = bytes, width = width, height = height)
                )
            }
            // Hold a strong reference so Kotlin/Native GC doesn't collect the delegate
            activeLibraryDelegate = delegate

            val picker = UIImagePickerController()
            picker.sourceType =
                UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
            picker.delegate = delegate
            topVc.presentViewController(picker, animated = true, completion = null)

            continuation.invokeOnCancellation {
                activeLibraryDelegate = null
                dispatch_async(dispatch_get_main_queue()) {
                    picker.dismissViewControllerAnimated(false, completion = null)
                }
            }
        }
    }
}

/**
 * Camera preview composable.
 *
 * Uses AVCaptureSession with AVCapturePhotoOutput for both preview and capture.
 * The photo output is stored in CameraManager.activePhotoOutput so that
 * capturePhoto() can capture directly from the preview session without launching
 * a separate UIImagePickerController.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraPreview(modifier: Modifier) {
    val hasCamera = UIImagePickerController.isSourceTypeAvailable(
        UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
    )

    if (hasCamera) {
        CameraPreviewWithAVCapture(modifier)
    } else {
        CameraPreviewFallback(modifier)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun CameraPreviewWithAVCapture(modifier: Modifier) {
    val captureSession = AVCaptureSession().apply {
        sessionPreset = AVCaptureSessionPresetHigh
    }

    val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
    if (device == null) {
        CameraPreviewFallback(modifier)
        return
    }

    val input = try {
        AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
    } catch (_: Throwable) {
        null
    }

    if (input == null || !captureSession.canAddInput(input)) {
        CameraPreviewFallback(modifier)
        return
    }

    captureSession.addInput(input)

    // Add photo output for inline capture
    val photoOutput = AVCapturePhotoOutput()
    if (captureSession.canAddOutput(photoOutput)) {
        captureSession.addOutput(photoOutput)
    }

    // Store refs so capturePhoto() can use the live session
    CameraManager.activePhotoOutput = photoOutput
    CameraManager.activeSession = captureSession

    androidx.compose.ui.interop.UIKitView(
        factory = {
            val previewLayer = AVCaptureVideoPreviewLayer(session = captureSession)
            previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill

            val container = UIView(frame = CGRectMake(0.0, 0.0, 400.0, 600.0))
            container.backgroundColor = platform.UIKit.UIColor.blackColor
            previewLayer.frame = container.bounds
            container.layer.addSublayer(previewLayer)

            dispatch_async(dispatch_get_main_queue()) {
                captureSession.startRunning()
            }

            container
        },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            val previewLayer = view.layer.sublayers
                ?.filterIsInstance<AVCaptureVideoPreviewLayer>()
                ?.firstOrNull()
            previewLayer?.frame = view.bounds
        },
        onRelease = {
            CameraManager.activePhotoOutput = null
            CameraManager.activeSession = null
            dispatch_async(dispatch_get_main_queue()) {
                captureSession.stopRunning()
            }
        },
    )
}

@Composable
private fun CameraPreviewFallback(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Tap to capture",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

actual fun embedGpsExif(jpegBytes: ByteArray, lat: Double, lng: Double): ByteArray = jpegBytes
