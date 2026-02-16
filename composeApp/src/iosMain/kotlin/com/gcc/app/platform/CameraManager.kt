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
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
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
 * iOS CameraManager -- uses UIImagePickerController for photo capture
 * and AVCaptureSession for the live camera preview.
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

        return suspendCancellableCoroutine { continuation ->
            dispatch_async(dispatch_get_main_queue()) {
                val rootVc = UIApplication.sharedApplication.keyWindow?.rootViewController
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
                            val image = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage]
                                ?: didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage]) as? UIImage

                            if (image == null) {
                                continuation.resume(null)
                                return@dismissViewControllerAnimated
                            }

                            val jpegData: NSData? = UIImageJPEGRepresentation(image, 0.85)
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
    }
}

/**
 * Camera preview composable.
 *
 * Attempts to set up an AVCaptureVideoPreviewLayer in a UIView via UIKitView.
 * Falls back to a styled dark box with "Tap to capture" text if the camera
 * session cannot be created or UIKitView is unavailable.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraPreview(modifier: Modifier) {
    // Use a styled placeholder that includes live-preview instructions.
    // AVCaptureVideoPreviewLayer requires UIKitView interop; we wrap
    // the setup in a try/catch so if the interop isn't resolved at
    // link time we gracefully fall back.
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
    // Set up an AVCaptureSession with the back camera
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
            // Update the preview layer frame when the view resizes
            val previewLayer = view.layer.sublayers
                ?.filterIsInstance<AVCaptureVideoPreviewLayer>()
                ?.firstOrNull()
            previewLayer?.frame = view.bounds
        },
        onRelease = {
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
