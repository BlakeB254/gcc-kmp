package com.gcc.app.platform

import kotlin.math.sqrt

/**
 * One identified item box in the viewport, normalized "view" coords (same
 * top-left-origin, screen-oriented convention [LiveDetection.cx]/`cy` use —
 * so the overlay can draw it with the identical `Offset(x * size.width, y *
 * size.height)` math, no re-projection). Additive to [TrashCandidate]: the
 * single candidate still drives the approach/confirm lock-on flow untouched;
 * this is the multi-item DISPLAY + capture-tagging layer (Task 17, "the best
 * shots for our display image in the backend extraction mechanisms").
 */
data class DetectionReticle(
    val centerX: Float, val centerY: Float,      // normalized [0,1]
    val width: Float, val height: Float,          // normalized
    val label: String?,                           // canonical trash label or null (salient but unidentified)
    val confidence: Float?,
    val isLitter: Boolean,
)

/** Reticle overlay never shows more than this many boxes at once — a wall of
 *  reticles is noise, not signal (see task-17 brief). */
const val MAX_MULTI_ITEM_RETICLES = 3

/** Two reticle centers closer than this (normalized image distance) are
 *  treated as the same physical item — the higher-confidence classification
 *  wins. Matches the tap-focus/candidate-selection precedent of comparing
 *  normalized image-space distances directly (no cell/meter conversion). */
private const val RETICLE_DEDUPE_DISTANCE = 0.12f

/**
 * Pure merge/dedup step for the multi-item reticle layer: collapses any
 * reticles whose centers are within [RETICLE_DEDUPE_DISTANCE] of each other
 * (keeping the higher-confidence one — null confidence sorts lowest) and caps
 * the result at [MAX_MULTI_ITEM_RETICLES]. No Vision/ARKit dependency, so
 * this is the piece iosMain's `updateMultiItemReticles` hands already-
 * classified boxes to, and what the pure-logic test exercises directly.
 */
fun dedupeReticles(reticles: List<DetectionReticle>): List<DetectionReticle> {
    val kept = mutableListOf<DetectionReticle>()
    for (candidate in reticles) {
        val clashIndex = kept.indexOfFirst { existing ->
            val dx = existing.centerX - candidate.centerX
            val dy = existing.centerY - candidate.centerY
            sqrt((dx * dx + dy * dy).toDouble()) < RETICLE_DEDUPE_DISTANCE
        }
        when {
            clashIndex == -1 -> kept.add(candidate)
            (candidate.confidence ?: 0f) > (kept[clashIndex].confidence ?: 0f) -> kept[clashIndex] = candidate
            else -> Unit // existing box already has the higher (or equal) confidence — keep it
        }
    }
    return kept.take(MAX_MULTI_ITEM_RETICLES)
}
