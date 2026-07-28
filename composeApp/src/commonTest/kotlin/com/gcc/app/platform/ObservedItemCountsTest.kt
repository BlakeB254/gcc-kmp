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
        // Different type AND beyond the co-location radius: a genuinely new
        // item (build 170 — at the same position this now upgrades instead;
        // see retap tests below).
        val existing = listOf(item("litter", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", itemType = "can", provenance = PROVENANCE_USER_CONFIRMED)
            .let { it.copy(lat = it.lat + 2.0 / 111_320.0) } // ~2m away
        assertEquals(DedupDecision.Add, dedupUpgradeDecision(existing, incoming))
    }

    // ── Build 170: retap dedup — a confirmed tap co-located (<0.6m) with an
    // existing litter item matches regardless of type (classifier noise
    // across retaps must not multiply dots), while real neighbors at ≥0.6m
    // stay distinct.

    @Test
    fun coLocatedConfirmedRetapUpgradesAcrossTypes() {
        val existing = listOf(item("litter", itemType = "bottle", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", itemType = "can", provenance = PROVENANCE_USER_CONFIRMED)
        assertEquals(DedupDecision.Upgrade(0), dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun neighborBeyondCoLocationRadiusStaysDistinctAcrossTypes() {
        val existing = listOf(item("litter", itemType = "bottle", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", itemType = "can", provenance = PROVENANCE_USER_CONFIRMED)
            .let { it.copy(lat = it.lat + 0.8 / 111_320.0) } // ~0.8m — a real neighbor
        assertEquals(DedupDecision.Add, dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun autoDetectedIncomingNeverUsesTheCoLocationMatch() {
        // Only USER taps carry the retap-noise problem — auto ticks keep the
        // strict type gate so multi-item piles map faithfully.
        val existing = listOf(item("litter", itemType = "bottle", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", itemType = "can", provenance = PROVENANCE_AUTO_DETECTED)
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
    fun secondConfirmationBeyondDuplicateRadiusIsARealSecondItem() {
        // Field report: two real bottles a sidewalk-width (~3m) apart — the
        // second explicit confirmation was silently swallowed by the 4m
        // auto-fusion radius. Deliberate confirmations only fuse when nearly
        // co-located (< 1.5m).
        val existing = listOf(item("litter", provenance = PROVENANCE_USER_CONFIRMED))
        val incoming = item("litter", provenance = PROVENANCE_USER_CONFIRMED)
            .copy(lat = 41.86 + 3.0 / 111_320.0) // ~3m north
        assertEquals(DedupDecision.Add, dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun doubleTapOfTheSameItemStillDedups() {
        val existing = listOf(item("litter", provenance = PROVENANCE_USER_CONFIRMED))
        val incoming = item("litter", provenance = PROVENANCE_USER_CONFIRMED)
            .copy(lat = 41.86 + 1.0 / 111_320.0) // ~1m — same physical item
        assertEquals(DedupDecision.Drop, dedupUpgradeDecision(existing, incoming))
    }

    @Test
    fun confirmedUpgradeOverAutoStillAppliesAcrossTheFullFusionRadius() {
        // The tighter confirmed-duplicate radius must NOT shrink the upgrade
        // window: a confirmation 3m from a prior auto tick is still the same
        // pile the auto producer smeared, and must upgrade, not duplicate.
        val existing = listOf(item("litter", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", provenance = PROVENANCE_USER_CONFIRMED)
            .copy(lat = 41.86 + 3.0 / 111_320.0)
        assertEquals(DedupDecision.Upgrade(0), dedupUpgradeDecision(existing, incoming))
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
    fun differentItemTypeNeverMatchesBeyondCoLocation() {
        val existing = listOf(item("litter", itemType = "bottle", provenance = PROVENANCE_AUTO_DETECTED))
        val incoming = item("litter", itemType = "can", provenance = PROVENANCE_USER_CONFIRMED)
            .let { it.copy(lat = it.lat + 1.0 / 111_320.0) } // ~1m — outside the retap radius
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
