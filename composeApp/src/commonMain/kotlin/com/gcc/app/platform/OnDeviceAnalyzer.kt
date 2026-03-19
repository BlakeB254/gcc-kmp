package com.gcc.app.platform

import com.gcc.shared.model.DepthAnalysis
import com.gcc.shared.model.OnDeviceAnalysisResult

/**
 * Platform-specific on-device AI analyzer.
 *
 * iOS: Apple Vision framework + Apple Intelligence foundation model
 * Android: Google ML Kit + Gemini Nano
 * Desktop: Stub (returns null)
 */
expect class OnDeviceAnalyzer() {
    /** Whether on-device AI analysis is available on this device. */
    fun isAvailable(): Boolean

    /** Analyze a photo for trash content. Returns analysis result. */
    suspend fun analyzePhoto(
        imageBytes: ByteArray,
        depthAnalysis: DepthAnalysis? = null,
    ): OnDeviceAnalysisResult?

    /** Generate an image embedding vector for duplicate detection. */
    suspend fun generateEmbedding(imageBytes: ByteArray): List<Float>?

    /** Compare two images for visual similarity (0.0 = different, 1.0 = identical). */
    suspend fun compareImages(imageA: ByteArray, imageB: ByteArray): Float?
}
