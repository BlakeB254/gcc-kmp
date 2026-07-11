package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class OutdoorGateTest {

    private val t0 = 1_000_000L

    @Test
    fun startsUncertain() {
        val gate = OutdoorGate()
        assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
    }

    @Test
    fun nullAccuracySamplesStayUncertain() {
        val gate = OutdoorGate()
        for (i in 0..10) gate.onAccuracySample(null, t0 + i * 1000L)
        assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
    }

    @Test
    fun singleBadAccuracySampleDoesNotFlipIndoor() {
        val gate = OutdoorGate()
        gate.onAccuracySample(40.0, t0)
        assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
    }

    @Test
    fun sustainedBadAccuracyGoesIndoor() {
        val gate = OutdoorGate()
        for (i in 0..6) gate.onAccuracySample(40.0, t0 + i * 1000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun sustainedGoodAccuracyGoesOutdoor() {
        val gate = OutdoorGate()
        for (i in 0..6) gate.onAccuracySample(5.0, t0 + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun recoversOutdoorAfterSustainedGoodAccuracy() {
        val gate = OutdoorGate()
        for (i in 0..6) gate.onAccuracySample(40.0, t0 + i * 1000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        // 6s of clean fixes afterwards clears the gate.
        val t1 = t0 + 20_000L
        for (i in 0..6) gate.onAccuracySample(5.0, t1 + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun mixedEvidenceDuringRecoveryStaysIndoor() {
        val gate = OutdoorGate()
        for (i in 0..6) gate.onAccuracySample(40.0, t0 + i * 1000L)
        // Alternating good/bad — indoor evidence keeps interrupting recovery.
        val t1 = t0 + 7000L
        for (i in 0..6) {
            gate.onAccuracySample(if (i % 2 == 0) 5.0 else 40.0, t1 + i * 1000L)
        }
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun sceneIndoorVotePlusBadAccuracyFlipsImmediately() {
        val gate = OutdoorGate()
        gate.onAccuracySample(40.0, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 500L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun sceneVoteAloneDoesNotFlipImmediately() {
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
    }

    @Test
    fun neutralAccuracyBetweenThresholdsIsIgnored() {
        val gate = OutdoorGate()
        for (i in 0..10) gate.onAccuracySample(15.0, t0 + i * 1000L)
        assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
    }
}

class OutdoorGateSceneStreakTest {
    private val t0 = 1_000_000L

    @kotlin.test.Test
    fun twoConsecutiveCameraIndoorVotesFlipAlone() {
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        kotlin.test.assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 2000)
        kotlin.test.assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @kotlin.test.Test
    fun outdoorVoteResetsTheIndoorStreak() {
        // Kept inside a <5s window so the general evidence rule can't flip —
        // this isolates the consecutive-streak rule specifically.
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 1000)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 2000)
        kotlin.test.assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
    }
}


class OutdoorGateMapEvidenceTest {
    private val t0 = 1_000_000L

    @kotlin.test.Test
    fun singleFixInsideMappedBuildingFlipsImmediately() {
        val gate = OutdoorGate()
        gate.onMapEvidence(insideBuilding = true, timestampMs = t0)
        kotlin.test.assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @kotlin.test.Test
    fun leavingTheBuildingAllowsRecovery() {
        val gate = OutdoorGate()
        gate.onMapEvidence(true, t0)
        gate.onMapEvidence(true, t0 + 1000)
        kotlin.test.assertEquals(OutdoorState.INDOOR, gate.state.value)
        // Walk outside: containment ends, clean outdoor GPS for 6s.
        val t1 = t0 + 20_000L
        gate.onMapEvidence(false, t1)
        for (i in 0..6) gate.onAccuracySample(5.0, t1 + i * 1000L)
        kotlin.test.assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }
}

class OutdoorGateV4Test {

    private val t0 = 1_000_000L

    @Test
    fun captureDisallowedWhileUncertain() {
        val gate = OutdoorGate()
        assertEquals(false, gate.captureAllowed.value)
    }

    @Test
    fun captureAllowedOnceOutdoor() {
        val gate = OutdoorGate()
        for (i in 0..6) gate.onAccuracySample(5.0, t0 + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
        assertEquals(true, gate.captureAllowed.value)
    }

    @Test
    fun ceilingPlaneFlipsIndoorInstantly() {
        val gate = OutdoorGate()
        gate.onCeilingPlane(true, t0)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        assertEquals(false, gate.captureAllowed.value)
    }

    @Test
    fun sceneStreakFlipsDespiteGoodGps() {
        val gate = OutdoorGate()
        // Outdoor-grade GPS in the window must no longer veto camera evidence.
        gate.onAccuracySample(5.0, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 500L)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 1500L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun twoOutdoorVotesRecoverFromIndoorFast() {
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 1000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        // ~3s later the camera sees street twice — recover without 5s clean GPS.
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 3000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 4000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun outdoorVoteRecoveryBlockedByRecentCeiling() {
        val gate = OutdoorGate()
        gate.onCeilingPlane(true, t0)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 1000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 2000L)
        // Ceiling seen 2s ago (< CEILING_RECENT_MS) — stay INDOOR.
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        // Once the ceiling is stale, the same streak recovers.
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 6000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 7000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun outdoorVoteRecoveryBlockedWhileMapContained() {
        val gate = OutdoorGate()
        gate.onMapEvidence(insideBuilding = true, timestampMs = t0)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 1000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 2000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun overrideSurvivesWeakEvidence() {
        val gate = OutdoorGate()
        gate.forceOutdoors()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 1000L)
        for (i in 0..6) gate.onAccuracySample(40.0, t0 + 2000L + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun overrideSurvivesMapContainment() {
        // Field regression (Douglass Park 2026-07-11): a stale 2012 footprint
        // import sat over the running track, so every GPS fix re-cleared the
        // override and re-showed "Go outside" in a ~1s loop. The user's
        // explicit confirmation must outrank map data for the session.
        val gate = OutdoorGate()
        gate.forceOutdoors()
        gate.onMapEvidence(insideBuilding = true, timestampMs = t0)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun overridePinsThroughRepeatedContainmentFixes() {
        val gate = OutdoorGate()
        gate.onMapEvidence(insideBuilding = true, timestampMs = t0)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        gate.forceOutdoors()
        // One containment sample per GPS fix for a minute of walking.
        for (i in 1..60) gate.onMapEvidence(insideBuilding = true, timestampMs = t0 + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
        assertEquals(true, gate.captureAllowed.value)
    }

    @Test
    fun overrideYieldsToCeilingPlane() {
        val gate = OutdoorGate()
        gate.forceOutdoors()
        gate.onCeilingPlane(true, t0)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun lastIndoorFlipTimestampRecorded() {
        val gate = OutdoorGate()
        assertEquals(null, gate.lastIndoorFlipAtMs)
        gate.onCeilingPlane(true, t0)
        assertEquals(t0, gate.lastIndoorFlipAtMs)
    }

    @Test
    fun staleOutdoorStreakDoesNotShortcutRecovery() {
        val gate = OutdoorGate()
        // Rack up outdoor votes while OUTDOOR, then flip INDOOR via ceiling.
        gate.onSceneVote(SceneVote.OUTDOOR, t0)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 1000L)
        gate.onCeilingPlane(true, t0 + 2000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        // ONE fresh outdoor vote must not recover (needs 2 consecutive)…
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 8000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        // …the second one may (ceiling is stale by now: >5s).
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 9000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun overrideSurvivesSustainedDegradedGpsAlone() {
        val gate = OutdoorGate()
        gate.forceOutdoors()
        for (i in 0..9) gate.onAccuracySample(40.0, t0 + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun twoOutdoorVotesUnlockFromUncertainFast() {
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.OUTDOOR, t0)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 750L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
        assertEquals(true, gate.captureAllowed.value)
    }

    @Test
    fun insidePolygonFootprintFlipsIndoorViaMapEvidence() {
        com.gcc.app.ui.map.MappedBuildingRegistry.clear()
        val ring = listOf(
            listOf(-87.720, 41.860), listOf(-87.710, 41.860),
            listOf(-87.710, 41.866), listOf(-87.720, 41.866),
            listOf(-87.720, 41.860),
        )
        com.gcc.app.ui.map.MappedBuildingRegistry.setRingsForTile("b:4186:-8772", listOf(ring))

        val gate = OutdoorGate()
        val inside = com.gcc.app.ui.map.isInsideMappedBuilding(lat = 41.863, lng = -87.715)
        gate.onMapEvidence(insideBuilding = inside, timestampMs = t0)
        assertEquals(OutdoorState.INDOOR, gate.state.value) // MAP_STREAK_TO_FLIP=1

        com.gcc.app.ui.map.MappedBuildingRegistry.clear()
    }
}
