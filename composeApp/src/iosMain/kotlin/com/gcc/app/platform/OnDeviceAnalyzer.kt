package com.gcc.app.platform

import com.gcc.shared.model.DepthAnalysis
import com.gcc.shared.model.OnDeviceAnalysisResult

import com.gcc.shared.model.SpotTag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNClassificationObservation
import platform.Vision.VNGenerateImageFeaturePrintRequest
import platform.Vision.VNFeaturePrintObservation
import platform.Vision.VNImageRequestHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * iOS on-device analyzer using Apple Vision framework.
 *
 * - VNClassifyImageRequest: scene/object classification (built-in taxonomy)
 * - VNGenerateImageFeaturePrintRequest: image embedding for duplicate detection
 * - VNFeaturePrintObservation.computeDistance: image similarity comparison
 */
actual class OnDeviceAnalyzer actual constructor() {

    actual fun isAvailable(): Boolean = true // Vision framework available on all iOS 13+

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun analyzePhoto(
        imageBytes: ByteArray,
        depthAnalysis: DepthAnalysis?,
    ): OnDeviceAnalysisResult? = suspendCancellableCoroutine { continuation ->
        try {
            val nsData = imageBytes.toNSData()
            val handler = VNImageRequestHandler(data = nsData, options = emptyMap<Any?, Any>())

            val classifyRequest = VNClassifyImageRequest { request, error ->
                if (error != null) {
                    continuation.resume(null)
                    return@VNClassifyImageRequest
                }

                val observations = request?.results
                    ?.filterIsInstance<VNClassificationObservation>()
                    ?.filter { it.confidence > 0.1f }
                    ?.sortedByDescending { it.confidence }
                    ?: emptyList()

                val trashTypes = mutableListOf<String>()
                val suggestedTags = mutableSetOf<SpotTag>()
                var topConfidence = 0f

                for (obs in observations.take(10)) {
                    val label = obs.identifier.lowercase()
                    topConfidence = maxOf(topConfidence, obs.confidence)

                    // Map Vision classifications to GCC trash types and tags
                    when {
                        label.contains("garbage") || label.contains("trash") || label.contains("waste") || label.contains("litter") -> {
                            trashTypes.add("General Trash")
                            suggestedTags.add(SpotTag.TRASH)
                        }
                        label.contains("leaf") || label.contains("plant") || label.contains("garden") -> {
                            trashTypes.add("Organic / Leaves")
                            suggestedTags.add(SpotTag.LEAVES)
                        }
                        label.contains("bottle") || label.contains("glass") -> {
                            trashTypes.add("Glass")
                            suggestedTags.add(SpotTag.GLASS)
                        }
                        label.contains("metal") || label.contains("aluminum") || label.contains("can") -> {
                            trashTypes.add("Metal")
                            suggestedTags.add(SpotTag.METAL)
                        }
                        label.contains("tire") || label.contains("rubber") -> {
                            trashTypes.add("Tires")
                            suggestedTags.add(SpotTag.TIRES)
                        }
                        label.contains("furniture") || label.contains("chair") || label.contains("couch") || label.contains("mattress") -> {
                            trashTypes.add("Furniture")
                            suggestedTags.add(SpotTag.FURNITURE)
                        }
                        label.contains("construction") || label.contains("brick") || label.contains("concrete") || label.contains("wood") -> {
                            trashTypes.add("Construction Debris")
                            suggestedTags.add(SpotTag.CONSTRUCTION)
                        }
                        label.contains("electronic") || label.contains("computer") || label.contains("appliance") -> {
                            trashTypes.add("Electronics")
                            suggestedTags.add(SpotTag.ELECTRONICS)
                        }
                        label.contains("cloth") || label.contains("textile") || label.contains("fabric") -> {
                            trashTypes.add("Clothing / Textiles")
                            suggestedTags.add(SpotTag.CLOTHING)
                        }
                        label.contains("hazard") || label.contains("chemical") || label.contains("paint") -> {
                            trashTypes.add("Hazardous")
                            suggestedTags.add(SpotTag.HAZARDOUS)
                        }
                    }
                }

                // Estimate weight using depth volume if available
                val estimatedWeight = depthAnalysis?.estimatedVolumeCubicFt?.let { volumeCuFt ->
                    // Average density heuristic: ~8 lbs/cu ft for mixed trash
                    val density = when {
                        suggestedTags.contains(SpotTag.METAL) -> 15.0
                        suggestedTags.contains(SpotTag.CONSTRUCTION) -> 20.0
                        suggestedTags.contains(SpotTag.FURNITURE) -> 5.0
                        suggestedTags.contains(SpotTag.LEAVES) -> 3.0
                        suggestedTags.contains(SpotTag.TIRES) -> 12.0
                        else -> 8.0
                    }
                    (volumeCuFt * density * 10).toInt() / 10.0
                }

                // Severity estimation (1-5)
                val severity = estimateSeverity(
                    trashTypes = trashTypes,
                    volumeCuFt = depthAnalysis?.estimatedVolumeCubicFt,
                    areaSqFt = depthAnalysis?.estimatedAreaSqFt,
                    tagCount = suggestedTags.size,
                )

                // Generate template description
                val description = buildDescription(trashTypes, estimatedWeight, severity, depthAnalysis)

                continuation.resume(
                    OnDeviceAnalysisResult(
                        trashTypes = trashTypes.distinct(),
                        estimatedWeightLbs = estimatedWeight,
                        severity = severity,
                        confidence = topConfidence,
                        suggestedTags = suggestedTags.toList(),
                        generatedDescription = description,
                    )
                )
            }

            handler.performRequests(listOf(classifyRequest), error = null)
        } catch (_: Throwable) {
            continuation.resume(null)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun generateEmbedding(imageBytes: ByteArray): List<Float>? {
        return try {
            val nsData = imageBytes.toNSData()
            val handler = VNImageRequestHandler(data = nsData, options = emptyMap<Any?, Any>())
            val request = VNGenerateImageFeaturePrintRequest()
            handler.performRequests(listOf(request), error = null)

            val observation = request.results?.firstOrNull() as? VNFeaturePrintObservation
            // VNFeaturePrintObservation doesn't expose raw floats directly in K/N,
            // but we can use its computeDistance for comparison. Return a placeholder
            // embedding marker so we know this photo has been processed.
            if (observation != null) listOf(1.0f) else null
        } catch (_: Throwable) {
            null
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun compareImages(imageA: ByteArray, imageB: ByteArray): Float? {
        return try {
            val handlerA = VNImageRequestHandler(data = imageA.toNSData(), options = emptyMap<Any?, Any>())
            val requestA = VNGenerateImageFeaturePrintRequest()
            handlerA.performRequests(listOf(requestA), error = null)
            val fpA = requestA.results?.firstOrNull() as? VNFeaturePrintObservation ?: return null

            val handlerB = VNImageRequestHandler(data = imageB.toNSData(), options = emptyMap<Any?, Any>())
            val requestB = VNGenerateImageFeaturePrintRequest()
            handlerB.performRequests(listOf(requestB), error = null)
            val fpB = requestB.results?.firstOrNull() as? VNFeaturePrintObservation ?: return null

            // computeDistance returns euclidean distance; convert to 0-1 similarity
            memScoped {
                val distanceVar = alloc<kotlinx.cinterop.FloatVar>()
                val success = fpA.computeDistance(distanceVar.ptr, toFeaturePrintObservation = fpB, error = null)
                if (success) {
                    val distance = distanceVar.value
                    // Distance ~0 = identical, ~50+ = very different. Normalize to 0-1 similarity.
                    (1.0f - min(distance / 50f, 1f))
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun estimateSeverity(
        trashTypes: List<String>,
        volumeCuFt: Double?,
        areaSqFt: Double?,
        tagCount: Int,
    ): Int {
        var score = 1
        // Volume-based
        when {
            volumeCuFt != null && volumeCuFt > 20 -> score = 5
            volumeCuFt != null && volumeCuFt > 10 -> score = 4
            volumeCuFt != null && volumeCuFt > 3 -> score = 3
            volumeCuFt != null && volumeCuFt > 0.5 -> score = 2
        }
        // Area-based boost
        if (areaSqFt != null && areaSqFt > 50) score = maxOf(score, 3)
        if (areaSqFt != null && areaSqFt > 100) score = maxOf(score, 4)
        // Hazardous auto-escalation
        if (trashTypes.any { it.contains("Hazard") }) score = maxOf(score, 4)
        // Diversity boost
        if (tagCount >= 3) score = minOf(score + 1, 5)
        return score
    }

    private fun buildDescription(
        trashTypes: List<String>,
        weight: Double?,
        severity: Int,
        depth: DepthAnalysis?,
    ): String {
        val typesStr = trashTypes.distinct().take(3).joinToString(", ")
        if (typesStr.isEmpty()) {
            return "No likely trash detected"
        }
        val parts = mutableListOf<String>()
        if (typesStr.isNotEmpty()) parts.add(typesStr)
        if (weight != null) parts.add("~${weight} lbs estimated")
        if (depth?.estimatedAreaSqFt != null) parts.add("~${depth.estimatedAreaSqFt} sq ft area")
        val severityLabel = when (severity) {
            1 -> "Minor"
            2 -> "Small"
            3 -> "Moderate"
            4 -> "Large"
            5 -> "Severe"
            else -> "Unknown"
        }
        return "$severityLabel dumping: ${parts.joinToString(", ")}"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(
            bytes = pinned.addressOf(0),
            length = size.toULong(),
        )
    }
}
