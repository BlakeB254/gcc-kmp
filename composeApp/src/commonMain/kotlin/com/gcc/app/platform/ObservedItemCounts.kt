package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.WorldObservation

/** Same-type items within this radius are considered the same physical pile
 *  — the scanner path projects along heading, the analyzer fallback tags
 *  the user position, and both can fire for the same pile. */
private const val OBSERVED_ITEM_DEDUP_RADIUS_METERS = 4.0

/** Tighter radius for CONFIRMED-vs-CONFIRMED matches: two explicit user
 *  confirmations of the same type are only the same item when nearly
 *  co-located. The 4m radius exists to fuse noisy AUTO producers; applying
 *  it to deliberate confirmations silently swallowed the second of two real
 *  bottles a sidewalk-width apart (field report: "I tapped it and nothing
 *  happened"). Within this radius a re-confirm is still a double-tap of the
 *  same item and dedups. */
private const val CONFIRMED_DUPLICATE_RADIUS_METERS = 1.5

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
    var matchIndex = -1
    var matchDistance = Double.MAX_VALUE
    for ((index, prior) in existing.withIndex()) {
        if (prior.itemType != incoming.itemType) continue
        val distance = GeofenceVerification.distanceMeters(
            prior.lat, prior.lng, incoming.lat, incoming.lng,
        )
        if (distance < OBSERVED_ITEM_DEDUP_RADIUS_METERS && distance < matchDistance) {
            matchIndex = index
            matchDistance = distance
        }
    }
    if (matchIndex < 0) return DedupDecision.Add
    val matched = existing[matchIndex]
    val bothConfirmed = incoming.provenance == PROVENANCE_USER_CONFIRMED &&
        matched.provenance == PROVENANCE_USER_CONFIRMED
    return when {
        // Two deliberate confirmations only fuse when nearly co-located —
        // beyond that they are two real items, not a double-tap.
        bothConfirmed && matchDistance >= CONFIRMED_DUPLICATE_RADIUS_METERS -> DedupDecision.Add
        incoming.provenance == PROVENANCE_USER_CONFIRMED &&
            matched.provenance != PROVENANCE_USER_CONFIRMED -> DedupDecision.Upgrade(matchIndex)
        else -> DedupDecision.Drop
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

// ── Cluster display gating (Blake, 2026-07-23) ──────────────────────
// "I only want those orange dots displayed if there was an area scanned with
// a lot of issues that can be viewed in a single frame." A lone automatic
// detection is weak evidence and must not tag the map on its own — it still
// rides the session manifest as data, but the map (live minimap, summary,
// public markers) only shows litter that is either explicitly user-confirmed
// or part of a genuine cluster.

/** Minimum litter items (any provenance, self included) within
 *  [LITTER_CLUSTER_RADIUS_METERS] for auto-detections to display as a cluster. */
const val LITTER_CLUSTER_MIN_ITEMS = 3

/** "Viewable in a single frame": items within this radius cluster together. */
const val LITTER_CLUSTER_RADIUS_METERS = 6.0

/** Session litter items within this distance of a world observation count as
 *  "the litter is still there" when deciding whether a scan cleared a dot. */
private const val CLEARED_OBSERVATION_MATCH_METERS = 4.0

/**
 * The session litter that may DISPLAY (minimap glyphs/zones, summary map and
 * stats, public marker uploads): user-confirmed items always; auto-detected
 * items only when at least [LITTER_CLUSTER_MIN_ITEMS] litter items sit within
 * [LITTER_CLUSTER_RADIUS_METERS] of them. Rejected candidates and non-litter
 * domains never display (unchanged).
 */
fun clusterVisibleLitter(items: List<ObservedSpotItem>): List<ObservedSpotItem> {
    val litter = items.filter { !it.isRejected() && it.isLitter() }
    return litter.filter { item ->
        item.provenance == PROVENANCE_USER_CONFIRMED || litter.count { other ->
            GeofenceVerification.distanceMeters(item.lat, item.lng, other.lat, other.lng) <=
                LITTER_CLUSTER_RADIUS_METERS
        } >= LITTER_CLUSTER_MIN_ITEMS
    }
}

/**
 * World-model litter (previous scans' observations) that may still display on
 * this session's minimap:
 *  - cluster-gated exactly like session items ("no lone dots from past scans"),
 *  - and CLEARED the moment this session close-scans the spot without finding
 *    litter there — [closeCellCenters] (the session's CLOSE-tier coverage)
 *    covering the observation with no session litter within
 *    [CLEARED_OBSERVATION_MATCH_METERS] means the ground was examined and the
 *    old dot is stale. The server-side record is corrected separately (the
 *    "already clean?" confirm flow); this keeps the LIVE display honest.
 */
fun visibleExpectedDirty(
    expectedDirty: List<WorldObservation>,
    sessionItems: List<ObservedSpotItem>,
    closeCellCenters: List<Pair<Double, Double>>,
    cellSizeMeters: Double,
): List<WorldObservation> {
    val litterOnly = expectedDirty.filter { it.domain == null || it.domain == "litter" }
    val clustered = litterOnly.filter { obs ->
        litterOnly.count { other ->
            GeofenceVerification.distanceMeters(obs.lat, obs.lng, other.lat, other.lng) <=
                LITTER_CLUSTER_RADIUS_METERS
        } >= LITTER_CLUSTER_MIN_ITEMS
    }
    val sessionLitter = sessionItems.filter { !it.isRejected() && it.isLitter() }
    return clustered.filterNot { obs ->
        val closeScanned = closeCellCenters.any { (lat, lng) ->
            GeofenceVerification.distanceMeters(lat, lng, obs.lat, obs.lng) <= cellSizeMeters
        }
        closeScanned && sessionLitter.none {
            GeofenceVerification.distanceMeters(it.lat, it.lng, obs.lat, obs.lng) <=
                CLEARED_OBSERVATION_MATCH_METERS
        }
    }
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
 *
 * Both halves are display-gated: session items through [clusterVisibleLitter]
 * (lone auto-detections stay off the map), world-model items through
 * [visibleExpectedDirty] (no lone dots from past scans; close-scanned-clean
 * spots clear immediately).
 */
fun minimapLitterItems(
    observedItems: List<ObservedSpotItem>,
    expectedDirty: List<WorldObservation>,
    closeCellCenters: List<Pair<Double, Double>> = emptyList(),
    cellSizeMeters: Double = 3.0,
): List<ObservedSpotItem> {
    val session = clusterVisibleLitter(observedItems)
    val nearby = visibleExpectedDirty(expectedDirty, observedItems, closeCellCenters, cellSizeMeters)
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
