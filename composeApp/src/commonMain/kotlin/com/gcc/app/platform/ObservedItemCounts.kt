package com.gcc.app.platform

import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.WorldObservation

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
