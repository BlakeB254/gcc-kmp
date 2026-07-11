package com.gcc.app.platform

import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.WorldObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Punchlist: "its showing little white squares on the minimap. remove that
 * feature we dont want it." — landmark/world-feature observations (fence,
 * pole, bush, tree, etc.) must never reach the minimap glyph list, even
 * though they're real world-observation data and still count toward
 * "Features mapped" elsewhere.
 */
class MinimapLitterItemsTest {

    private fun observed(
        domain: String?,
        itemType: String = "plastic_bottle",
    ) = ObservedSpotItem(lat = 41.86, lng = -87.72, itemType = itemType, domain = domain)

    private fun nearby(
        domain: String?,
        itemType: String? = "plastic_bottle",
    ) = WorldObservation(
        id = 1, lat = 41.86, lng = -87.72, itemType = itemType, domain = domain, state = "dirty",
        sourceType = "route_scan",
    )

    @Test
    fun landmarkDomainsDoNotReachTheMinimapList() {
        val session = listOf(
            observed("litter"),
            observed("vegetation", "bush"),
            observed("construction", "fence"),
            observed(null), // legacy: null domain means litter
        )
        val result = minimapLitterItems(session, expectedDirty = emptyList())
        assertEquals(2, result.size)
        assertTrue(result.all { it.domain == null || it.domain == "litter" })
    }

    @Test
    fun nearbyLandmarkObservationsAreExcludedToo() {
        val expectedDirty = listOf(
            nearby("litter", "can"),
            nearby("construction", "pole"),
            nearby(null, null),
        )
        val result = minimapLitterItems(emptyList(), expectedDirty)
        assertEquals(2, result.size)
        // The null-itemType nearby litter observation still defaults to "other".
        assertTrue(result.any { it.itemType == "other" })
        assertTrue(result.none { it.itemType == "pole" })
    }

    @Test
    fun emptyInputsProduceEmptyList() {
        assertEquals(0, minimapLitterItems(emptyList(), emptyList()).size)
    }
}
