package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Every litter class the Stage-2 crop classifier emits (scripts/litter_model
 * README "Class contract") must type as trash in both rule sets — a class no
 * token matches is silently dropped (was the case for "cigarette").
 */
class LitterClassTokensTest {

    private val litterClasses = listOf(
        "plastic_bottle", "glass_bottle", "can", "plastic_bag", "trash_bag", "cup", "paper",
        "wrapper", "chip_bag", "carton", "cigarette", "food_waste", "other_litter",
    )

    @Test
    fun everyClassifierLitterClassTypesAsTrash() {
        for (label in litterClasses) {
            val obs = listOf(ClassificationObservation(label, 0.9f))
            assertNotNull(TrashDetectionRules.trashLabelFrom(obs), "$label: TrashDetectionRules")
            assertNotNull(GroundExclusionRules.classify(obs).trashType, "$label: GroundExclusionRules")
        }
    }

    @Test
    fun cigaretteMapsToItsCatalogType() {
        val label = TrashDetectionRules.trashLabelFrom(listOf(ClassificationObservation("cigarette", 0.9f)))
        assertEquals("Cigarette", label)
        assertEquals("cigarette", TrashItemCatalog.canonicalType(label!!))
    }
}
