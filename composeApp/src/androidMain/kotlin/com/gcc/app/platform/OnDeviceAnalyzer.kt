package com.gcc.app.platform

import com.gcc.shared.model.DepthAnalysis
import com.gcc.shared.model.OnDeviceAnalysisResult

import com.gcc.shared.model.SpotTag

/**
 * Android on-device analyzer using Google ML Kit.
 *
 * Uses ImageLabeling for classification and object detection.
 * Gemini Nano integration for description generation (when available).
 *
 * Note: ML Kit dependencies need to be added to build.gradle.kts:
 *   implementation("com.google.mlkit:image-labeling:17.0.9")
 *   implementation("com.google.mlkit:object-detection:17.0.2")
 * Until then, this provides heuristic-based analysis using image metadata.
 */
actual class OnDeviceAnalyzer actual constructor() {

    actual fun isAvailable(): Boolean = true

    actual suspend fun analyzePhoto(
        imageBytes: ByteArray,
        depthAnalysis: DepthAnalysis?,
    ): OnDeviceAnalysisResult? {
        // Heuristic analysis based on available data
        // TODO: Integrate ML Kit ImageLabeling when dependency is added
        val suggestedTags = mutableListOf(SpotTag.TRASH)
        val trashTypes = mutableListOf("Mixed Debris")

        val estimatedWeight = depthAnalysis?.estimatedVolumeCubicFt?.let { it * 8.0 }

        val severity = when {
            depthAnalysis?.estimatedVolumeCubicFt != null && depthAnalysis.estimatedVolumeCubicFt > 20 -> 5
            depthAnalysis?.estimatedVolumeCubicFt != null && depthAnalysis.estimatedVolumeCubicFt > 10 -> 4
            depthAnalysis?.estimatedVolumeCubicFt != null && depthAnalysis.estimatedVolumeCubicFt > 3 -> 3
            depthAnalysis?.estimatedVolumeCubicFt != null && depthAnalysis.estimatedVolumeCubicFt > 0.5 -> 2
            else -> 2 // Default moderate for photo-only reports
        }

        val severityLabel = when (severity) {
            1 -> "Minor"; 2 -> "Small"; 3 -> "Moderate"; 4 -> "Large"; 5 -> "Severe"; else -> "Unknown"
        }

        val description = buildString {
            append("$severityLabel dumping: ${trashTypes.joinToString(", ")}")
            if (estimatedWeight != null) append(", ~${(estimatedWeight * 10).toInt() / 10.0} lbs estimated")
            if (depthAnalysis?.estimatedAreaSqFt != null) append(", ~${depthAnalysis.estimatedAreaSqFt} sq ft area")
        }

        return OnDeviceAnalysisResult(
            trashTypes = trashTypes,
            estimatedWeightLbs = estimatedWeight?.let { (it * 10).toInt() / 10.0 },
            severity = severity,
            confidence = 0.5f, // Lower confidence without ML Kit
            suggestedTags = suggestedTags,
            generatedDescription = description,
        )
    }

    actual suspend fun generateEmbedding(imageBytes: ByteArray): List<Float>? {
        // TODO: Integrate ML Kit or TFLite MobileNet for real embeddings
        return null
    }

    actual suspend fun compareImages(imageA: ByteArray, imageB: ByteArray): Float? {
        // TODO: Implement with ML Kit label confidence vector similarity
        return null
    }
}
