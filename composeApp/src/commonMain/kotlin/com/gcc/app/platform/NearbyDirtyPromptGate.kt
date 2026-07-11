package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.WorldObservation

/**
 * Gates the env-scan "Trash was reported here — is it still there?" confirm
 * card. Field report (TestFlight 145): a block with many old dirty
 * observations popped one card per observation as the user walked — spam.
 *
 * Policy: one card at a time; the visible card never silently swaps to a
 * nearer candidate; ignoring the card and walking away (> [walkAwayRadiusMeters])
 * dismisses it QUIETLY (unresolved — it may come back after the cooldown);
 * answering or walking away arms a [cooldownMs] window during which no new
 * card appears. Callers resolve the whole ≤[promptRadiusMeters] cluster on an
 * answer via [clusterIdsAround] so ten observations of the same mess cost one
 * question, not ten.
 */
class NearbyDirtyPromptGate(
    private val promptRadiusMeters: Double = 5.0,
    private val walkAwayRadiusMeters: Double = 12.0,
    private val cooldownMs: Long = 45_000L,
) {
    private var showingId: Int? = null
    private var cooldownUntilMs: Long = 0L

    /**
     * One location tick: returns the observation whose card should be visible
     * right now, or null. [unresolved] is the caller's already-filtered list
     * (dirty, litter-domain, not locally resolved).
     */
    fun onLocation(
        nowMs: Long,
        lat: Double,
        lng: Double,
        unresolved: List<WorldObservation>,
    ): WorldObservation? {
        val showing = showingId?.let { id -> unresolved.firstOrNull { it.id == id } }
        if (showing == null) {
            // Resolved upstream (answer/cluster) — the answer path already
            // armed the cooldown; just forget it.
            showingId = null
        } else {
            val dist = GeofenceVerification.distanceMeters(lat, lng, showing.lat, showing.lng)
            if (dist > walkAwayRadiusMeters) {
                // Ignored and walked away: drop the card without resolving,
                // and hold the quiet period so the walk doesn't chain-prompt.
                showingId = null
                cooldownUntilMs = nowMs + cooldownMs
            } else {
                return showing
            }
        }
        if (nowMs < cooldownUntilMs) return null
        val next = unresolved
            .asSequence()
            .map { it to GeofenceVerification.distanceMeters(lat, lng, it.lat, it.lng) }
            .filter { (_, dist) -> dist <= promptRadiusMeters }
            .minByOrNull { (_, dist) -> dist }
            ?.first
        showingId = next?.id
        return next
    }

    /** The user answered the visible card (either button). */
    fun onAnswered(nowMs: Long) {
        showingId = null
        cooldownUntilMs = nowMs + cooldownMs
    }
}

/**
 * The answered observation plus every other unresolved observation within
 * [radiusMeters] of it — one answer settles the whole cluster locally.
 */
fun clusterIdsAround(
    answered: WorldObservation,
    unresolved: List<WorldObservation>,
    radiusMeters: Double = 5.0,
): Set<Int> = unresolved
    .asSequence()
    .filter {
        it.id == answered.id ||
            GeofenceVerification.distanceMeters(answered.lat, answered.lng, it.lat, it.lng) <= radiusMeters
    }
    .map { it.id }
    .toSet()
