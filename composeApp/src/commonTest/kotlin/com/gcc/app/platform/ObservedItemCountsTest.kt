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

    // C2: dedupUpgradeDecision — the pure seam addObservedItem's 4m/same-type
    // dedup check delegates to.

    @Test
    fun noMatchAddsTheIncomingItem() {
        val existing = listOf(item("litter", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", itemType = "can", provenance = PROVENANCE_USER_CONFIRMED)
        assertEquals(DedupDecision.Add, dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun autoDetectedIncomingAgainstAnyMatchJustDedups() {
        // Regression guard: an auto-detected tick landing on an existing
        // confirmed (or auto-detected) item must keep dedupping plainly —
        // only a USER CONFIRMATION is allowed to upgrade.
        val existing = listOf(item("litter", provenance = PROVENANCE_USER_CONFIRMED))
        val incoming = item("litter", provenance = PROVENANCE_AUTO_DETECTED)
        assertEquals(DedupDecision.Drop, dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun confirmedVsConfirmedStillDedups() {
        val existing = listOf(item("litter", provenance = PROVENANCE_USER_CONFIRMED))
        val incoming = item("litter", provenance = PROVENANCE_USER_CONFIRMED)
        assertEquals(DedupDecision.Drop, dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun userConfirmedIncomingAgainstAutoDetectedMatchUpgrades() {
        // C2: the bug — a user confirmation matching a prior auto-detected
        // tick used to be silently dropped, losing the only evidence that
        // gates report filing.
        val existing = listOf(item("litter", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", provenance = PROVENANCE_USER_CONFIRMED)
        assertEquals(DedupDecision.Upgrade(0), dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun userConfirmedIncomingAgainstNullProvenanceMatchUpgrades() {
        // Legacy rows carry a null provenance, same weak-evidence status as
        // auto_detected — still eligible for upgrade.
        val existing = listOf(item("litter", provenance = null))
        val incoming = item("litter", provenance = PROVENANCE_USER_CONFIRMED)
        assertEquals(DedupDecision.Upgrade(0), dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun differentItemTypeNeverMatches() {
        val existing = listOf(item("litter", itemType = "bottle", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", itemType = "can", provenance = PROVENANCE_USER_CONFIRMED)
        assertEquals(DedupDecision.Add, dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun matchIsSelectedByIndexAmongMultipleExisting() {
        val existing = listOf(
            item("litter", itemType = "can", provenance = PROVENANCE_AUTO_DETECTED),
            item("litter", itemType = "bottle", provenance = PROVENANCE_AUTO_DETECTED),
        )
        val incoming = item("litter", itemType = "bottle", provenance = PROVENANCE_USER_CONFIRMED)
        assertEquals(DedupDecision.Upgrade(1), dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun removeLitterItemsNearRemovesOnlyLitterInsideRadius() {
        val near = item("litter", provenance = PROVENANCE_AUTO_DETECTED)
        // ~22m north of the shared (41.86, -87.72) test position.
        val far = near.copy(lat = 41.86 + 22.0 / 111_320.0)
        val feature = item("vegetation")
        val rejected = item("litter", provenance = PROVENANCE_USER_REJECTED)
        val remaining = removeLitterItemsNear(
            listOf(near, far, feature, rejected),
            lat = 41.86, lng = -87.72, radiusMeters = 5.0,
        )
        assertEquals(listOf(far, feature, rejected), remaining)
    }

    @Test
    fun removeLitterItemsNearTreatsNullDomainAsLitter() {
        val legacy = item(domain = null, provenance = PROVENANCE_AUTO_DETECTED)
        assertEquals(
            emptyList(),
            removeLitterItemsNear(listOf(legacy), lat = 41.86, lng = -87.72),
        )
    }
}
