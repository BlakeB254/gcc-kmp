package com.gcc.app.platform

import kotlin.math.hypot

data class CameraPosition3d(
    val x: Double,
    val y: Double,
    val z: Double,
)

data class ReconstructionReadinessInput(
    val cameraPositions: List<CameraPosition3d>,
    val normalTrackingSamples: Int,
    val totalTrackingSamples: Int,
    val isLidarDevice: Boolean,
    val usableDepthSamples: Int,
)

data class ReconstructionReadiness(
    val horizontalBaselineMeters: Double,
    val hasHorizontalBaseline: Boolean,
    val hasEnoughNormalTracking: Boolean,
    val hasUsableDepth: Boolean,
    val isReady: Boolean,
    val guidance: String?,
)

/**
 * Pure, platform-independent quality check for reconstruction capture.
 *
 * This is advisory only: coverage, litter photos, and scan completion must
 * not depend on [ReconstructionReadiness.isReady].
 */
object ReconstructionReadinessEvaluator {
    const val MIN_HORIZONTAL_BASELINE_METERS = 2.0
    const val MIN_NORMAL_TRACKING_FRACTION = 0.60

    fun evaluate(input: ReconstructionReadinessInput): ReconstructionReadiness {
        val baseline = maximumHorizontalBaseline(input.cameraPositions)
        val hasBaseline = baseline >= MIN_HORIZONTAL_BASELINE_METERS
        val hasTracking = input.totalTrackingSamples > 0 &&
            input.normalTrackingSamples.toDouble() / input.totalTrackingSamples >=
            MIN_NORMAL_TRACKING_FRACTION
        val hasDepth = !input.isLidarDevice || input.usableDepthSamples >= 1
        val ready = hasBaseline && hasTracking && hasDepth
        val guidance = when {
            !hasTracking -> "Hold steady for tracking"
            !hasBaseline -> "Move slowly 2-5 meters and slightly sideways"
            !hasDepth -> "Keep scanning for depth"
            else -> null
        }
        return ReconstructionReadiness(
            horizontalBaselineMeters = baseline,
            hasHorizontalBaseline = hasBaseline,
            hasEnoughNormalTracking = hasTracking,
            hasUsableDepth = hasDepth,
            isReady = ready,
            guidance = guidance,
        )
    }

    private fun maximumHorizontalBaseline(positions: List<CameraPosition3d>): Double {
        var maximum = 0.0
        for (firstIndex in positions.indices) {
            val first = positions[firstIndex]
            for (secondIndex in firstIndex + 1 until positions.size) {
                val second = positions[secondIndex]
                maximum = maxOf(maximum, hypot(second.x - first.x, second.z - first.z))
            }
        }
        return maximum
    }
}
