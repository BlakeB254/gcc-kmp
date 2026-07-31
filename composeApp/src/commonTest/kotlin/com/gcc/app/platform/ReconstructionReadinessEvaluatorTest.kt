package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconstructionReadinessEvaluatorTest {

    @Test
    fun nonLidarCaptureIsReadyAtInclusiveMotionAndTrackingThresholds() {
        val result = ReconstructionReadinessEvaluator.evaluate(
            ReconstructionReadinessInput(
                cameraPositions = listOf(
                    CameraPosition3d(x = 0.0, y = 0.0, z = 0.0),
                    CameraPosition3d(x = 2.0, y = 0.0, z = 0.0),
                ),
                normalTrackingSamples = 3,
                totalTrackingSamples = 5,
                isLidarDevice = false,
                usableDepthSamples = 0,
            )
        )

        assertEquals(2.0, result.horizontalBaselineMeters)
        assertTrue(result.hasHorizontalBaseline)
        assertTrue(result.hasEnoughNormalTracking)
        assertTrue(result.hasUsableDepth)
        assertTrue(result.isReady)
        assertNull(result.guidance)
    }

    @Test
    fun rotationOnlyMotionDoesNotCreateCameraBaseline() {
        val result = ReconstructionReadinessEvaluator.evaluate(
            readyInput(
                cameraPositions = listOf(
                    CameraPosition3d(x = 4.0, y = 1.5, z = -3.0),
                    CameraPosition3d(x = 4.0, y = 1.5, z = -3.0),
                    CameraPosition3d(x = 4.0, y = 1.5, z = -3.0),
                )
            )
        )

        assertEquals(0.0, result.horizontalBaselineMeters)
        assertFalse(result.hasHorizontalBaseline)
        assertFalse(result.isReady)
        assertEquals("Move slowly 2-5 meters and slightly sideways", result.guidance)
    }

    @Test
    fun verticalMovementDoesNotCountTowardHorizontalBaseline() {
        val result = ReconstructionReadinessEvaluator.evaluate(
            readyInput(
                cameraPositions = listOf(
                    CameraPosition3d(x = 0.0, y = 0.0, z = 0.0),
                    CameraPosition3d(x = 0.0, y = 3.0, z = 0.0),
                )
            )
        )

        assertEquals(0.0, result.horizontalBaselineMeters)
        assertFalse(result.hasHorizontalBaseline)
    }

    @Test
    fun poorTrackingTakesPriorityOverMotionGuidance() {
        val result = ReconstructionReadinessEvaluator.evaluate(
            readyInput(
                cameraPositions = listOf(CameraPosition3d(0.0, 0.0, 0.0)),
                normalTrackingSamples = 2,
                totalTrackingSamples = 5,
            )
        )

        assertFalse(result.hasEnoughNormalTracking)
        assertEquals("Hold steady for tracking", result.guidance)
    }

    @Test
    fun lidarCaptureRequiresAtLeastOneUsableDepthSample() {
        val missingDepth = ReconstructionReadinessEvaluator.evaluate(
            readyInput(isLidarDevice = true, usableDepthSamples = 0)
        )
        val withDepth = ReconstructionReadinessEvaluator.evaluate(
            readyInput(isLidarDevice = true, usableDepthSamples = 1)
        )

        assertFalse(missingDepth.hasUsableDepth)
        assertFalse(missingDepth.isReady)
        assertEquals("Keep scanning for depth", missingDepth.guidance)
        assertTrue(withDepth.hasUsableDepth)
        assertTrue(withDepth.isReady)
    }

    private fun readyInput(
        cameraPositions: List<CameraPosition3d> = listOf(
            CameraPosition3d(x = 0.0, y = 0.0, z = 0.0),
            CameraPosition3d(x = 0.0, y = 0.0, z = 2.5),
        ),
        normalTrackingSamples: Int = 5,
        totalTrackingSamples: Int = 5,
        isLidarDevice: Boolean = false,
        usableDepthSamples: Int = 0,
    ) = ReconstructionReadinessInput(
        cameraPositions = cameraPositions,
        normalTrackingSamples = normalTrackingSamples,
        totalTrackingSamples = totalTrackingSamples,
        isLidarDevice = isLidarDevice,
        usableDepthSamples = usableDepthSamples,
    )
}
