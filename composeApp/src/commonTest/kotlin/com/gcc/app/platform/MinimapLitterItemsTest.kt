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

    // Session items are user-confirmed here so they pass the cluster display
    // gate (clusterVisibleLitter) — this test is about DOMAIN filtering; the
    // gating itself is covered in LitterClusterDisplayTest.
    private fun observed(
        domain: String?,
        itemType: String = "plastic_bottle",
    ) = ObservedSpotItem(
        lat = 41.86, lng = -87.72, itemType = itemType, domain = domain,
        provenance = PROVENANCE_USER_CONFIRMED,
    )

    private fun nearby(
        domain: String?,
        itemType: String? = "plastic_bottle",
        id: Int = 1,
    ) = WorldObservation(
        id = id, lat = 41.86, lng = -87.72, itemType = itemType, domain = domain, state = "dirty",
        sourceType = "route_scan",
    )

    @Test
    fun landmarkDomainsDoNotReachTheMinimapList() {
        val session = listOf(
            observed("litter"),
            observed("vegetation", "bush"),
            observed("construction", "fence"),
            observed(null, itemType = "can"), // legacy: null domain means litter
        )
        val result = minimapLitterItems(session, expectedDirty = emptyList())
        assertEquals(2, result.size)
        assertTrue(result.all { it.domain == null || it.domain == "litter" })
    }

    @Test
    fun nearbyLandmarkObservationsAreExcludedToo() {
        // Three co-located litter observations = a displayable cluster; the
        // pole is a landmark domain and must stay off the minimap even
        // inside a cluster's radius.
        val expectedDirty = listOf(
            nearby("litter", "can", id = 1),
            nearby("litter", "can", id = 2),
            nearby(null, null, id = 3),
            nearby("construction", "pole", id = 4),
        )
        val result = minimapLitterItems(emptyList(), expectedDirty)
        assertEquals(3, result.size)
        // The null-itemType nearby litter observation still defaults to "other".
        assertTrue(result.any { it.itemType == "other" })
        assertTrue(result.none { it.itemType == "pole" })
    }

    @Test
    fun emptyInputsProduceEmptyList() {
        assertEquals(0, minimapLitterItems(emptyList(), emptyList()).size)
    }
}
