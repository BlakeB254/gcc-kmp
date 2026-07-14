package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrashItemCatalogTest {

    @Test
    fun `vision identifiers map to canonical types`() {
        assertEquals("plastic_bottle", TrashItemCatalog.canonicalType("water_bottle"))
        assertEquals("glass_bottle", TrashItemCatalog.canonicalType("glass bottle"))
        assertEquals("can", TrashItemCatalog.canonicalType("aluminum_can"))
        assertEquals("plastic_bag", TrashItemCatalog.canonicalType("plastic bag"))
        assertEquals("cigarette", TrashItemCatalog.canonicalType("cigarette_butt"))
        assertEquals("paper", TrashItemCatalog.canonicalType("cardboard"))
        assertEquals("tire", TrashItemCatalog.canonicalType("tire"))
    }

    @Test
    fun `display labels from TrashDetectionRules map to canonical types`() {
        // trashLabelFrom emits display labels like "Bottle"/"Can"/"Bag".
        assertEquals("plastic_bottle", TrashItemCatalog.canonicalType("Bottle"))
        assertEquals("can", TrashItemCatalog.canonicalType("Can"))
        assertEquals("plastic_bag", TrashItemCatalog.canonicalType("Bag"))
        assertEquals("wrapper", TrashItemCatalog.canonicalType("Wrapper"))
    }

    @Test
    fun `ground candidate trash tokens canonicalize to the same types the tap path produces`() {
        // The completed-ground-candidate path (SpotScreen) files
        // GroundExclusionRules.trashTypeFrom's raw tokens through
        // canonicalType() — they must land on the EXACT canonical strings
        // the tap-confirm path produces, or the minimap glyph `when` misses
        // (generic dot) and dedupUpgradeDecision's itemType compare never
        // matches (double-add instead of upgrade).
        assertEquals("plastic_bottle", TrashItemCatalog.canonicalType("bottle"))
        assertEquals("glass_bottle", TrashItemCatalog.canonicalType("glass"))
        assertEquals("plastic_bag", TrashItemCatalog.canonicalType("bag"))
        assertEquals("carton", TrashItemCatalog.canonicalType("box"))
        assertEquals("cup", TrashItemCatalog.canonicalType("cup"))
        assertEquals("can", TrashItemCatalog.canonicalType("can"))
        assertEquals("paper", TrashItemCatalog.canonicalType("paper"))
        assertEquals("wrapper", TrashItemCatalog.canonicalType("wrapper"))
        assertEquals("carton", TrashItemCatalog.canonicalType("carton"))
        // The no-classification fallback label canonicalizes to the generic type.
        assertEquals(TrashItemCatalog.FALLBACK_TYPE, TrashItemCatalog.canonicalType(UNKNOWN_LITTER_LABEL))
    }

    @Test
    fun `unknown labels fall back to other`() {
        assertEquals(TrashItemCatalog.FALLBACK_TYPE, TrashItemCatalog.canonicalType("zebra"))
        assertEquals(TrashItemCatalog.FALLBACK_TYPE, TrashItemCatalog.canonicalType(""))
    }

    @Test
    fun `chip bag tokens route to chip_bag ahead of the generic bag branch`() {
        assertEquals("chip_bag", TrashItemCatalog.canonicalType("chip_bag"))
        assertEquals("chip_bag", TrashItemCatalog.canonicalType("chips"))
        assertEquals("chip_bag", TrashItemCatalog.canonicalType("Chip Bag"))
    }

    @Test
    fun `trash or garbage bag tokens route to trash_bag ahead of the generic bag branch`() {
        assertEquals("trash_bag", TrashItemCatalog.canonicalType("trash_bag"))
        assertEquals("trash_bag", TrashItemCatalog.canonicalType("garbage bag"))
        // "bag" alone (no trash/garbage/lawn qualifier) still routes to the
        // generic shopping-bag-sized type.
        assertEquals("plastic_bag", TrashItemCatalog.canonicalType("plastic bag"))
    }

    @Test
    fun `chip_bag and trash_bag catalog entries have expected physical estimates`() {
        val chip = TrashItemCatalog.infoFor("chip_bag")
        assertEquals("Chip bag", chip.displayName)
        assertEquals(0.22, chip.typicalWidthMeters)
        assertEquals(4, chip.typicalWeightGrams)

        val trashBag = TrashItemCatalog.infoFor("trash_bag")
        assertEquals("Trash bag", trashBag.displayName)
        assertEquals(0.60, trashBag.typicalWidthMeters)
        assertEquals(60, trashBag.typicalWeightGrams)
    }

    @Test
    fun `typical width returns typical weight`() {
        val info = TrashItemCatalog.infoFor("plastic_bottle")
        assertEquals(
            info.typicalWeightGrams,
            TrashItemCatalog.estimateWeightGrams("plastic_bottle", info.typicalWidthMeters),
        )
    }

    @Test
    fun `null or absurd width falls back to typical weight`() {
        val typical = TrashItemCatalog.infoFor("can").typicalWeightGrams
        assertEquals(typical, TrashItemCatalog.estimateWeightGrams("can", null))
        assertEquals(typical, TrashItemCatalog.estimateWeightGrams("can", 0.0))
        assertEquals(typical, TrashItemCatalog.estimateWeightGrams("can", 9.9))
    }

    @Test
    fun `hollow container scales with area not volume`() {
        val info = TrashItemCatalog.infoFor("plastic_bottle")
        val doubled = TrashItemCatalog.estimateWeightGrams("plastic_bottle", info.typicalWidthMeters * 2)
        // Area scaling: 2x width -> ~4x mass (not 8x).
        assertEquals(info.typicalWeightGrams * 4, doubled)
    }

    @Test
    fun `sheet-like item scales linearly`() {
        val info = TrashItemCatalog.infoFor("plastic_bag")
        val doubled = TrashItemCatalog.estimateWeightGrams("plastic_bag", info.typicalWidthMeters * 2)
        assertEquals(info.typicalWeightGrams * 2, doubled)
    }

    @Test
    fun `estimates stay within sane clamp`() {
        assertTrue(TrashItemCatalog.estimateWeightGrams("mattress", 2.9) <= 20_000)
        assertTrue(TrashItemCatalog.estimateWeightGrams("cigarette", 0.01) >= 1)
    }

    @Test
    fun `volume-based bulk weight uses litter density`() {
        // 100 L of loose mixed litter ≈ 13 kg at 130 g/L.
        assertEquals(13_000, TrashItemCatalog.estimateWeightFromVolumeLiters(100.0))
        assertEquals(1, TrashItemCatalog.estimateWeightFromVolumeLiters(0.0))
        assertEquals(1, TrashItemCatalog.estimateWeightFromVolumeLiters(Double.NaN))
        // Pile clamp is 2 tonnes, not the single-item 20 kg cap.
        assertEquals(2_000_000, TrashItemCatalog.estimateWeightFromVolumeLiters(1e9))
    }

    @Test
    fun `every catalog type resolves to itself via infoFor`() {
        for (type in listOf(
            "plastic_bottle", "glass_bottle", "can", "plastic_bag", "cup", "paper",
            "wrapper", "carton", "cigarette", "food_waste", "tire", "mattress",
            "chip_bag", "trash_bag", "other",
        )) {
            assertEquals(type, TrashItemCatalog.infoFor(type).itemType)
        }
    }
}
