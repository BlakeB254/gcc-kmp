package com.gcc.app.platform

import com.gcc.shared.model.SpotTag
import kotlinx.serialization.Serializable

/**
 * Result of on-device AI photo analysis.
 * Populated by platform-specific implementations (Vision/CoreML on iOS, ML Kit on Android).
 */
@Serializable
data class OnDeviceAnalysisResult(
    val trashTypes: List<String> = emptyList(),
    val estimatedWeightLbs: Double? = null,
    val severity: Int? = null,
    val confidence: Float? = null,
    val suggestedTags: List<SpotTag> = emptyList(),
    val generatedDescription: String? = null,
    val imageEmbedding: List<Float>? = null,
)

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
