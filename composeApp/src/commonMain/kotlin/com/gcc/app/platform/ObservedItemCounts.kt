package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.WorldObservation

/** Same-type items within this radius are considered the same physical pile
 *  — the scanner path projects along heading, the analyzer fallback tags
 *  the user position, and both can fire for the same pile. */
private const val OBSERVED_ITEM_DEDUP_RADIUS_METERS = 4.0

/** What [addObservedItem] (SpotViewModel) should do with an incoming item
 *  against the session's existing observed items. */
sealed interface DedupDecision {
    /** No same-type match within the dedup radius — add the incoming item as new. */
    object Add : DedupDecision

    /** A same-type match within radius, and the incoming item adds no new
     *  evidence over it (auto-vs-anything, or confirmed-vs-confirmed) — drop. */
    object Drop : DedupDecision

    /** A same-type match within radius exists at [index] in the existing
     *  list, but the incoming item is a fresh user confirmation and the
     *  match isn't already one — upgrade the match in place instead of
     *  dropping the confirmation on the floor (C2). */
    data class Upgrade(val index: Int) : DedupDecision
}

/**
 * Pure decision seam for [addObservedItem]'s dedup step (C2). A same-type
 * match within [OBSERVED_ITEM_DEDUP_RADIUS_METERS] normally just dedups —
 * except when [incoming] is a user confirmation (provenance ==
 * [PROVENANCE_USER_CONFIRMED]) and the matched item is NOT already one: a
 * user confirmation is the only evidence [hasUserConfirmedLitter] accepts
 * to file a report, so silently dropping it because an earlier
 * auto-detected tick already claimed the same spot would break report
 * filing for that pile. A confirmed-vs-confirmed match still dedups
 * plainly — it carries no new evidence.
 */
fun dedupUpgradeDecision(existing: List<ObservedSpotItem>, incoming: ObservedSpotItem): DedupDecision {
    val matchIndex = existing.indexOfFirst { prior ->
        prior.itemType == incoming.itemType &&
            GeofenceVerification.distanceMeters(
                prior.lat, prior.lng, incoming.lat, incoming.lng,
            ) < OBSERVED_ITEM_DEDUP_RADIUS_METERS
    }
    if (matchIndex < 0) return DedupDecision.Add
    val matched = existing[matchIndex]
    return if (incoming.provenance == PROVENANCE_USER_CONFIRMED && matched.provenance != PROVENANCE_USER_CONFIRMED) {
        DedupDecision.Upgrade(matchIndex)
    } else {
        DedupDecision.Drop
    }
}

/** An automatic detection. Never sufficient to file a report (constraint 10). */
const val PROVENANCE_AUTO_DETECTED = "auto_detected"

/** The user tapped it and pressed "Confirm litter". A strong training label. */
const val PROVENANCE_USER_CONFIRMED = "user_confirmed"

/** The user tapped it and pressed "Not litter". A negative training example. */
const val PROVENANCE_USER_REJECTED = "user_rejected"

/** Legacy rows carry a null domain, which has always meant litter. */
private fun ObservedSpotItem.isLitter(): Boolean = domain == null || domain == "litter"

private fun ObservedSpotItem.isRejected(): Boolean = provenance == PROVENANCE_USER_REJECTED

/** Litter the scan found. Rejected candidates are excluded — they are not litter. */
fun countLitter(items: List<ObservedSpotItem>): Int =
    items.count { !it.isRejected() && it.isLitter() }

/** Non-litter world features mapped (vegetation, construction, land use). */
fun countFeatures(items: List<ObservedSpotItem>): Int =
    items.count { !it.isRejected() && !it.isLitter() }

/**
 * Whether this session has evidence strong enough to file an incident report.
 *
 * Only a USER-CONFIRMED litter observation qualifies. A whole-frame classifier
 * label is a weak routing signal and may never file a report on its own — that
 * is how a photo of a car became a tire report, and how foliage became a
 * "leaves" report.
 */
fun hasUserConfirmedLitter(items: List<ObservedSpotItem>): Boolean =
    items.any { it.provenance == PROVENANCE_USER_CONFIRMED && it.isLitter() }

/**
 * Everything except the session litter items within [radiusMeters] of
 * ([lat], [lng]) — the local half of answering "Already clean" on a
 * session-detected item (TestFlight 157: a phantom auto-detection had no
 * correction path). Non-litter items and rejected candidates are kept
 * regardless of distance: rejections are negative training labels the
 * manifest must still carry, and features aren't litter to begin with.
 */
fun removeLitterItemsNear(
    items: List<ObservedSpotItem>,
    lat: Double,
    lng: Double,
    radiusMeters: Double = 5.0,
): List<ObservedSpotItem> = items.filterNot { item ->
    !item.isRejected() && item.isLitter() &&
        GeofenceVerification.distanceMeters(lat, lng, item.lat, item.lng) <= radiusMeters
}

/**
 * Litter-only glyphs for the scan minimap. Landmark/world-feature
 * observations (fence, pole, sign, bench, gate, tree, bush — domain
 * "vegetation"/"construction") are real world-observation data and still
 * count toward "Features mapped", but they must never render on the
 * minimap: punchlist "its showing little white squares on the minimap.
 * remove that feature we dont want it." `knownElements` (nearby landmark
 * observations) is intentionally not a parameter here — it never
 * contributes to the minimap.
 */
fun minimapLitterItems(
    observedItems: List<ObservedSpotItem>,
    expectedDirty: List<WorldObservation>,
): List<ObservedSpotItem> {
    val session = observedItems.filter { it.isLitter() }
    val nearby = expectedDirty
        .filter { it.domain == null || it.domain == "litter" }
        .map { obs ->
            ObservedSpotItem(
                lat = obs.lat,
                lng = obs.lng,
                itemType = obs.itemType ?: "other",
                severity = obs.severity,
                domain = obs.domain,
            )
        }
    return session + nearby
}
