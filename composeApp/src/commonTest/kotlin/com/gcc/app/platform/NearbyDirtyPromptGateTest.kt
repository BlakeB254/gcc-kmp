package com.gcc.app.platform

import com.gcc.shared.model.WorldObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Field report (TestFlight 145): walking a previously-scanned block popped a
 * "Trash was reported here — is it still there?" card for EVERY old dirty
 * observation along the way — spam. The gate must hold one card at a time,
 * answer or walk-away must arm a cooldown, and one answer must settle the
 * whole ≤5m cluster (SpotScreen resolves the cluster via [clusterIdsAround]).
 */
class NearbyDirtyPromptGateTest {

    // ~1m of latitude near the equator-ish scale used in tests.
    private val latMeter = 1.0 / 111_320.0

    private fun obs(id: Int, lat: Double, lng: Double) = WorldObservation(
        id = id, lat = lat, lng = lng, state = "dirty", sourceType = "route_scan",
    )

    @Test
    fun promptsNearestUnresolvedWithinRadius() {
        val gate = NearbyDirtyPromptGate()
        val near = obs(1, latMeter * 3, 0.0) // 3m north
        val far = obs(2, latMeter * 30, 0.0) // 30m north
        assertEquals(near, gate.onLocation(0L, 0.0, 0.0, listOf(far, near)))
    }

    @Test
    fun holdsCurrentCardEvenWhenANearerCandidateAppears() {
        val gate = NearbyDirtyPromptGate()
        val first = obs(1, latMeter * 4, 0.0)
        assertEquals(first, gate.onLocation(0L, 0.0, 0.0, listOf(first)))
        // A nearer unresolved observation shows up on the next tick — the
        // visible card must NOT silently swap to it.
        val nearer = obs(2, latMeter * 1, 0.0)
        assertEquals(first, gate.onLocation(1_000L, 0.0, 0.0, listOf(nearer, first)))
    }

    @Test
    fun walkAwayDismissesQuietlyAndArmsCooldown() {
        val gate = NearbyDirtyPromptGate()
        val a = obs(1, 0.0, 0.0)
        assertEquals(a, gate.onLocation(0L, 0.0, 0.0, listOf(a)))
        // 20m away: card drops without an answer…
        val b = obs(2, latMeter * 20 + latMeter * 2, 0.0)
        assertNull(gate.onLocation(1_000L, latMeter * 20, 0.0, listOf(a, b)))
        // …and the cooldown suppresses the next candidate (b is 2m away here).
        assertNull(gate.onLocation(10_000L, latMeter * 20, 0.0, listOf(a, b)))
        // After the cooldown expires the next candidate may prompt.
        assertEquals(b, gate.onLocation(46_001L, latMeter * 20, 0.0, listOf(a, b)))
    }

    @Test
    fun answerArmsCooldownBeforeNextPrompt() {
        val gate = NearbyDirtyPromptGate()
        val a = obs(1, 0.0, 0.0)
        val b = obs(2, latMeter * 2, 0.0)
        assertEquals(a, gate.onLocation(0L, 0.0, 0.0, listOf(a, b)))
        gate.onAnswered(5_000L)
        // b is 2m away but must wait out the cooldown.
        assertNull(gate.onLocation(6_000L, 0.0, 0.0, listOf(b)))
        assertEquals(b, gate.onLocation(50_001L, 0.0, 0.0, listOf(b)))
    }

    @Test
    fun currentCardDropsWhenItsObservationIsResolvedUpstream() {
        val gate = NearbyDirtyPromptGate()
        val a = obs(1, 0.0, 0.0)
        assertEquals(a, gate.onLocation(0L, 0.0, 0.0, listOf(a)))
        // a resolved elsewhere (cluster answer): it leaves the unresolved list.
        assertNull(gate.onLocation(1_000L, 0.0, 0.0, emptyList()))
    }

    @Test
    fun clusterIdsAroundReturnsAnsweredObservationAndItsNeighbors() {
        val answered = obs(1, 0.0, 0.0)
        val neighbor = obs(2, latMeter * 3, 0.0) // 3m
        val distant = obs(3, latMeter * 40, 0.0) // 40m
        assertEquals(
            setOf(1, 2),
            clusterIdsAround(answered, listOf(answered, neighbor, distant)),
        )
    }
}
