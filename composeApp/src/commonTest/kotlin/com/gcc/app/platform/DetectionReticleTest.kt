package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 17: multi-item detection reticles. [dedupeReticles] is the pure
 * box->reticle merge/dedup step extracted out of iosMain's
 * `updateMultiItemReticles` so it's testable without Vision/ARKit — see the
 * task-17 report for how the iOS producer builds its input list.
 */
class DetectionReticleTest {

    private fun reticle(
        cx: Float,
        cy: Float,
        label: String? = "Bottle",
        confidence: Float? = 0.5f,
    ) = DetectionReticle(
        centerX = cx,
        centerY = cy,
        width = 0.2f,
        height = 0.2f,
        label = label,
        confidence = confidence,
        isLitter = label != null,
    )

    @Test
    fun threeDistinctBoxesProduceThreeReticles() {
        val input = listOf(
            reticle(0.1f, 0.1f, "Bottle", 0.9f),
            reticle(0.5f, 0.5f, "Can", 0.8f),
            reticle(0.9f, 0.9f, "Bag", 0.7f),
        )
        val result = dedupeReticles(input)
        assertEquals(3, result.size)
        assertEquals(setOf("Bottle", "Can", "Bag"), result.mapNotNull { it.label }.toSet())
    }

    @Test
    fun overlappingCentersKeepHigherConfidence() {
        // Two boxes whose centers are well within the 0.12 dedupe radius.
        val input = listOf(
            reticle(0.50f, 0.50f, "Bottle", 0.40f),
            reticle(0.55f, 0.50f, "Can", 0.85f),
        )
        val result = dedupeReticles(input)
        assertEquals(1, result.size)
        assertEquals("Can", result.single().label)
        assertEquals(0.85f, result.single().confidence)
    }

    @Test
    fun farApartCentersBothSurvive() {
        // 0.12 apart on one axis is right at the dedupe boundary — use a
        // clearly-separated pair so this test isn't boundary-flaky.
        val input = listOf(
            reticle(0.20f, 0.20f, "Bottle", 0.6f),
            reticle(0.80f, 0.80f, "Can", 0.6f),
        )
        val result = dedupeReticles(input)
        assertEquals(2, result.size)
    }

    @Test
    fun vehicleSuppressedLabelIsUnidentifiedNotLitter() {
        // autoPromotionLabelFrom returns null for a vehicle-context tire —
        // the reticle for that box must carry label=null, isLitter=false
        // (salient-but-unidentified styling), never a suppressed label leaking
        // through as if it were still identified litter.
        val suppressed = reticle(0.3f, 0.3f, label = null, confidence = null)
        val result = dedupeReticles(listOf(suppressed))
        assertEquals(1, result.size)
        assertNull(result.single().label)
        assertTrue(!result.single().isLitter)
    }

    @Test
    fun capsAtThreeEvenWithFourFarApartBoxes() {
        val input = listOf(
            reticle(0.05f, 0.05f, "Bottle", 0.9f),
            reticle(0.35f, 0.05f, "Can", 0.8f),
            reticle(0.65f, 0.05f, "Bag", 0.7f),
            reticle(0.95f, 0.05f, "Cup", 0.6f),
        )
        val result = dedupeReticles(input)
        assertEquals(MAX_MULTI_ITEM_RETICLES, result.size)
        assertEquals(3, result.size)
    }
}
