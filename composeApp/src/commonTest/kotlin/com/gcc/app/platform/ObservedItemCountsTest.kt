package com.gcc.app.platform

import com.gcc.shared.model.ObservedSpotItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservedItemCountsTest {

    private fun item(
        domain: String?,
        itemType: String = "plastic_bottle",
        provenance: String? = null,
    ) = ObservedSpotItem(
        lat = 41.86, lng = -87.72, itemType = itemType, domain = domain, provenance = provenance,
    )

    @Test
    fun litterAndFeaturesAreCountedSeparately() {
        val items = listOf(
            item("litter"),
            item("vegetation", "tree"),
            item("construction", "fence"),
            item(null), // legacy: null domain means litter
        )
        assertEquals(2, countLitter(items))
        assertEquals(2, countFeatures(items))
    }

    @Test
    fun rejectedItemsCountAsNeitherLitterNorFeature() {
        val items = listOf(item("litter", provenance = PROVENANCE_USER_REJECTED))
        assertEquals(0, countLitter(items))
        assertEquals(0, countFeatures(items))
    }

    @Test
    fun emptyListCountsZero() {
        assertEquals(0, countLitter(emptyList()))
        assertEquals(0, countFeatures(emptyList()))
    }

    @Test
    fun onlyUserConfirmedLitterIsReportEvidence() {
        // Constraint 10: an automatic whole-frame detection may never file a report.
        assertTrue(!hasUserConfirmedLitter(listOf(item("litter", provenance = PROVENANCE_AUTO_DETECTED))))
        assertTrue(hasUserConfirmedLitter(listOf(item("litter", provenance = PROVENANCE_USER_CONFIRMED))))
    }

    @Test
    fun aConfirmedFeatureIsNotReportEvidence() {
        assertTrue(!hasUserConfirmedLitter(listOf(item("vegetation", "tree", PROVENANCE_USER_CONFIRMED))))
    }

    @Test
    fun aRejectedConfirmationIsNotReportEvidence() {
        assertTrue(!hasUserConfirmedLitter(listOf(item("litter", provenance = PROVENANCE_USER_REJECTED))))
    }
}
