package com.gcc.app.platform

import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.DepthAnalysis

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
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.darwin.dispatch_queue_create
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
import platform.Foundation.NSNumber
import platform.Foundation.valueForKey
import platform.Foundation.setValue
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.get
import kotlin.math.abs

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

@OptIn(ExperimentalForeignApi::class)
private fun processDepthData(photo: AVCapturePhoto): DepthAnalysis? {
    val depthData = photo.depthData ?: return null
    val pixelBuffer = depthData.depthDataMap

    CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
    try {
        val width = CVPixelBufferGetWidth(pixelBuffer).toInt()
        val height = CVPixelBufferGetHeight(pixelBuffer).toInt()
        val bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer).toInt()
        val baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) ?: return null

        val floatPtr = baseAddress.reinterpret<kotlinx.cinterop.FloatVar>()
        val floatsPerRow = bytesPerRow / 4

        val depths = mutableListOf<Float>()
        val bottomDepths = mutableListOf<Float>()

        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                val d = floatPtr[y * floatsPerRow + x]
                if (d.isFinite() && d > 0f && d < 10f) {
                    depths.add(d)
                    if (y > height * 0.8) {
                        bottomDepths.add(d)
                    }
                }
            }
        }

        if (depths.isEmpty()) return null

        bottomDepths.sort()
        val groundPlane = if (bottomDepths.isNotEmpty()) {
            bottomDepths[bottomDepths.size / 2]
        } else {
            depths.sorted()[depths.size / 2]
        }

        val hFovRad = 1.047
        var totalVolumeM3 = 0.0
        var aboveGroundCount = 0
        var maxDepth = 0f

        for (y in (height * 0.1).toInt() until (height * 0.9).toInt() step 4) {
            for (x in (width * 0.1).toInt() until (width * 0.9).toInt() step 4) {
                val d = floatPtr[y * floatsPerRow + x]
                if (d.isFinite() && d > 0f && d < groundPlane) {
                    val heightAboveGround = (groundPlane - d).toDouble()
                    val pixelWidthM = (d * hFovRad / width) * 4
                    val pixelAreaM2 = pixelWidthM * pixelWidthM
                    totalVolumeM3 += heightAboveGround * pixelAreaM2
                    aboveGroundCount++
                    if (d > maxDepth) maxDepth = d
                }
            }
        }

        val volumeCuFt = totalVolumeM3 * 35.3147
        val groundPixelWidthM = (groundPlane * hFovRad / width) * 4
        val areaSqFt = aboveGroundCount * groundPixelWidthM * groundPixelWidthM * 10.7639

        val confidence = if (depths.size > 0) {
            val validRatio = depths.count { abs(it - groundPlane) < 0.05f }.toFloat() / depths.size
            (1f - validRatio).coerceIn(0.3f, 0.95f)
        } else 0.5f

        return DepthAnalysis(
            hasDepth = true,
            estimatedVolumeCubicFt = if (volumeCuFt > 0.01) (volumeCuFt * 10).toInt() / 10.0 else null,
            estimatedAreaSqFt = if (areaSqFt > 0.1) (areaSqFt * 10).toInt() / 10.0 else null,
            maxDepthMeters = if (maxDepth > 0f) (maxDepth * 100).toInt() / 100.0 else null,
            depthConfidence = (confidence * 100).toInt() / 100f,
            depthMapThumbnailBase64 = null,
        )
    } finally {
        CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
    }
}

/**
 * Delegate object for AVCapturePhotoOutput that receives captured photos.
 * Declared as a concrete class to properly satisfy Kotlin/Native ObjC interop
 * requirements for the AVCapturePhotoCaptureDelegate protocol.
 */
private class PhotoCaptureDelegate(
    private val onComplete: (NSData?, DepthAnalysis?) -> Unit,
) : NSObject(), platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol {
    @Suppress("CONFLICTING_OVERLOADS")
    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) {
            onComplete(null, null)
            return
        }
        val depthAnalysis = processDepthData(didFinishProcessingPhoto)
        onComplete(didFinishProcessingPhoto.fileDataRepresentation(), depthAnalysis)
    }
}

private val activePhotoCaptureDelegates = mutableSetOf<PhotoCaptureDelegate>()

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
            // Enable depth data delivery if supported (KVO since K/N may not expose these)
            try {
                val supported = (photoOutput as platform.darwin.NSObject)
                    .valueForKey("depthDataDeliverySupported") as? Boolean ?: false
                if (supported) {
                    (settings as platform.darwin.NSObject)
                        .setValue(true, forKey = "depthDataDeliveryEnabled")
                }
            } catch (_: Throwable) { /* depth not available on this device */ }

            var delegate: PhotoCaptureDelegate? = null
            delegate = PhotoCaptureDelegate { fileData, depthAnalysis ->
                delegate?.let { activePhotoCaptureDelegates.remove(it) }
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

                captureCount++
                val attestation = CaptureAttestation(
                    capturedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                    deviceModel = getDeviceModel(),
                    osVersion = getOsVersion(),
                    sessionId = sessionId,
                    captureIndex = captureCount,
                    isLiveCapture = true,
                )

                continuation.resume(
                    CapturedPhoto(
                        bytes = bytes,
                        width = width,
                        height = height,
                        depthAnalysis = depthAnalysis,
                        attestation = attestation,
                    )
                )
            }
            val activeDelegate = delegate ?: return@dispatch_async
            activePhotoCaptureDelegates.add(activeDelegate)
            continuation.invokeOnCancellation {
                delegate?.let { activePhotoCaptureDelegates.remove(it) }
            }

            photoOutput.capturePhotoWithSettings(settings, delegate = activeDelegate)
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

                val delegate = CameraPickerDelegate { rawImage ->
                    if (rawImage == null) {
                        continuation.resume(null)
                        return@CameraPickerDelegate
                    }

                    val image = rawImage.resizedToMax(1920.0)
                    val jpegData: NSData? = UIImageJPEGRepresentation(image, 0.80)
                    if (jpegData == null || jpegData.length.toInt() == 0) {
                        continuation.resume(null)
                        return@CameraPickerDelegate
                    }

                    val bytes = jpegData.bytes?.readBytes(jpegData.length.toInt())
                    if (bytes == null) {
                        continuation.resume(null)
                        return@CameraPickerDelegate
                    }

                    val width = image.size.useContents { width.toInt() }
                    val height = image.size.useContents { height.toInt() }

                    captureCount++
                    val attestation = CaptureAttestation(
                        capturedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                        deviceModel = getDeviceModel(),
                        osVersion = getOsVersion(),
                        sessionId = sessionId,
                        captureIndex = captureCount,
                        isLiveCapture = true,
                    )

                    continuation.resume(
                        CapturedPhoto(
                            bytes = bytes,
                            width = width,
                            height = height,
                            attestation = attestation,
                        )
                    )
                }

                activeCameraPickerDelegate = delegate
                picker.delegate = delegate
                rootVc.presentViewController(picker, animated = true, completion = null)

                continuation.invokeOnCancellation {
                    activeCameraPickerDelegate = null
                    dispatch_async(dispatch_get_main_queue()) {
                        picker.dismissViewControllerAnimated(false, completion = null)
                    }
                }
            }
        }

    companion object {
        internal var activePhotoOutput: AVCapturePhotoOutput? = null
        internal var activeSession: AVCaptureSession? = null
        /** Strong ref — AVCaptureVideoDataOutput holds its delegate weakly. */
        internal var activeSceneDelegate: Any? = null
        internal var hasDepthCapability: Boolean = false
        internal var sessionId: String = platform.Foundation.NSUUID().UUIDString()
        internal var captureCount: Int = 0
    }
}

/**
 * Strong reference to the active library picker delegate.
 * Prevents Kotlin/Native GC from collecting the delegate while the picker is presented.
 * UIImagePickerController.delegate is a WEAK reference, so without this the delegate
 * gets collected and callbacks never fire.
 */
private var activeLibraryDelegate: LibraryPickerDelegate? = null

private var activeCameraPickerDelegate: CameraPickerDelegate? = null

private class CameraPickerDelegate(
    private val onResult: (UIImage?) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage]
            ?: didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage]) as? UIImage
        picker.dismissViewControllerAnimated(true) {
            activeCameraPickerDelegate = null
            onResult(image)
        }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true) {
            activeCameraPickerDelegate = null
            onResult(null)
        }
    }
}

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

    // Use the standard back camera (LiDAR depth is detected via photo output capabilities)
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

    // Try to enable depth data delivery (works on LiDAR-capable devices)
    val depthCapable = try {
        val supported = (photoOutput as platform.darwin.NSObject)
            .valueForKey("depthDataDeliverySupported") as? Boolean ?: false
        if (supported) {
            (photoOutput as platform.darwin.NSObject)
                .setValue(true, forKey = "depthDataDeliveryEnabled")
        }
        supported
    } catch (_: Throwable) { false }

    // Scene-vote tap: classify one frame every ~2s and feed the OutdoorGate
    // via CameraSceneVoteBus (the plain viewfinder has no AR pipeline).
    val sceneDelegate = SceneSampleDelegate()
    val videoOutput = AVCaptureVideoDataOutput()
    videoOutput.alwaysDiscardsLateVideoFrames = true
    videoOutput.setSampleBufferDelegate(
        sceneDelegate,
        queue = dispatch_queue_create("gcc.scene.sampling", null),
    )
    if (captureSession.canAddOutput(videoOutput)) {
        captureSession.addOutput(videoOutput)
    }

    // Store refs so capturePhoto() can use the live session
    CameraManager.activeSceneDelegate = sceneDelegate
    CameraManager.activePhotoOutput = photoOutput
    CameraManager.activeSession = captureSession
    CameraManager.hasDepthCapability = depthCapable
    CameraManager.sessionId = platform.Foundation.NSUUID().UUIDString()
    CameraManager.captureCount = 0

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
            CameraManager.activeSceneDelegate = null
            CameraManager.hasDepthCapability = false
            dispatch_async(dispatch_get_main_queue()) {
                captureSession.stopRunning()
            }
        },
    )
}

/**
 * Samples the live viewfinder stream for indoor/outdoor evidence: at most one
 * Vision pass every [SAMPLE_INTERVAL_MS]; non-NONE votes go to
 * [CameraSceneVoteBus] on the main queue. Frames outside the sampling window
 * return immediately (the delegate rides the 30fps video queue).
 */
private const val SCENE_SAMPLE_INTERVAL_MS = 2_000L

@OptIn(ExperimentalForeignApi::class)
private class SceneSampleDelegate :
    platform.darwin.NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {

    private var lastSampleAtMs = 0L

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        if (nowMs - lastSampleAtMs < SCENE_SAMPLE_INTERVAL_MS) return
        lastSampleAtMs = nowMs

        val pixelBuffer = CMSampleBufferGetImageBuffer(didOutputSampleBuffer)
            ?: return
        val result = try {
            SceneVoteVision.analyzeViewfinderFrame(pixelBuffer)
        } catch (e: Throwable) {
            DiagnosticsLog.error("scene-vote", "viewfinder frame analysis failed", e)
            return
        }
        dispatch_async(dispatch_get_main_queue()) {
            if (result.vote != SceneVote.NONE) CameraSceneVoteBus.emit(result.vote)
            CameraDetectionBus.update(listOfNotNull(result.detection))
        }
    }
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

actual fun hasLiDAR(): Boolean = CameraManager.hasDepthCapability

actual fun getDeviceModel(): String {
    val device = platform.UIKit.UIDevice.currentDevice
    return "${device.model} (${device.systemName})"
}

actual fun getOsVersion(): String {
    val device = platform.UIKit.UIDevice.currentDevice
    return "${device.systemName} ${device.systemVersion}"
}
