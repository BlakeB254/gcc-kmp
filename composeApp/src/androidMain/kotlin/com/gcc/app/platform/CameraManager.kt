package com.gcc.app.platform

import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.DepthAnalysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "CameraManager"

/**
 * Android CameraManager -- uses CameraX for live preview and photo capture.
 *
 * Architecture:
 * - CameraPreview composable binds CameraX Preview + ImageCapture use cases to the lifecycle.
 * - capturePhoto() triggers ImageCapture.takePicture() via the shared ImageCapture instance.
 * - A companion object bridges the composable (which has Context/LifecycleOwner) with the
 *   no-arg-constructed CameraManager instance.
 */
actual class CameraManager actual constructor() {

    actual fun hasCameraHardware(): Boolean = true

    /**
     * Capture a photo using the CameraX ImageCapture use case.
     *
     * If the CameraPreview composable is active and has bound CameraX, this will
     * trigger a real capture. Otherwise returns null.
     */
    actual suspend fun capturePhoto(): CapturedPhoto? {
        val imageCapture = activeImageCapture ?: run {
            Log.w(TAG, "capturePhoto called but no active ImageCapture use case. Is CameraPreview visible?")
            return null
        }

        val context = activeContext ?: run {
            Log.w(TAG, "capturePhoto called but no active Context registered.")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            val outputFile = File(context.cacheDir, "gcc_capture_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        try {
                            val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                            if (bitmap == null) {
                                Log.e(TAG, "Failed to decode captured image")
                                outputFile.delete()
                                continuation.resume(null)
                                return
                            }

                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                            val bytes = stream.toByteArray()
                            val width = bitmap.width
                            val height = bitmap.height
                            bitmap.recycle()
                            outputFile.delete()

                            continuation.resume(
                                CapturedPhoto(
                                    bytes = bytes,
                                    width = width,
                                    height = height,
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing captured image", e)
                            outputFile.delete()
                            continuation.resume(null)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Image capture failed", exception)
                        outputFile.delete()
                        continuation.resume(null)
                    }
                }
            )

            continuation.invokeOnCancellation {
                outputFile.delete()
            }
        }
    }

    companion object {
        /**
         * The currently active ImageCapture use case, set by CameraPreview when CameraX binds.
         * Cleared when the composable leaves composition.
         */
        @Volatile
        internal var activeImageCapture: ImageCapture? = null

        /**
         * The currently active Context from the CameraPreview composable.
         */
        @Volatile
        internal var activeContext: Context? = null
    }
}

/**
 * Live camera preview composable using CameraX PreviewView.
 *
 * Binds CameraX Preview and ImageCapture use cases to the current lifecycle.
 * The ImageCapture instance is stored in [CameraManager.activeImageCapture] so
 * that [CameraManager.capturePhoto] can trigger captures.
 */
@Composable
actual fun CameraPreview(modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraProviderReady by remember { mutableStateOf(false) }

    // Create the CameraX use cases once
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val preview = remember { Preview.Builder().build() }

    // Register the ImageCapture and Context for CameraManager.capturePhoto()
    DisposableEffect(imageCapture, context) {
        CameraManager.activeImageCapture = imageCapture
        CameraManager.activeContext = context
        onDispose {
            CameraManager.activeImageCapture = null
            CameraManager.activeContext = null
        }
    }

    // Bind CameraX to the lifecycle
    LaunchedEffect(lifecycleOwner) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                {
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                        cameraProviderReady = true
                    } catch (e: Exception) {
                        Log.e(TAG, "CameraX bind failed", e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        } catch (e: Exception) {
            Log.e(TAG, "CameraX initialization failed", e)
        }
    }

    if (cameraProviderReady) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    preview.setSurfaceProvider(surfaceProvider)
                }
            },
            modifier = modifier.fillMaxSize(),
            update = { previewView ->
                preview.setSurfaceProvider(previewView.surfaceProvider)
            },
        )
    } else {
        // Loading state while CameraX initializes
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A2E)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Initializing camera...",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

actual suspend fun pickFromLibrary(): CapturedPhoto? = null // TODO: Android photo picker

actual fun embedGpsExif(jpegBytes: ByteArray, lat: Double, lng: Double): ByteArray = jpegBytes

actual fun hasLiDAR(): Boolean = false

actual fun getDeviceModel(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

actual fun getOsVersion(): String = "Android ${android.os.Build.VERSION.RELEASE}"
