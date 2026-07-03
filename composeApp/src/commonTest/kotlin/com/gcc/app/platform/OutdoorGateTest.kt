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
    fun threeConsecutiveCameraIndoorVotesFlipAlone() {
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 2000)
        kotlin.test.assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 4000)
        kotlin.test.assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @kotlin.test.Test
    fun outdoorVoteResetsTheIndoorStreak() {
        // Kept inside a <5s window so the general evidence rule can't flip —
        // this isolates the consecutive-streak rule specifically.
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 1000)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 2000)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 3000)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 4000)
        kotlin.test.assertEquals(OutdoorState.UNCERTAIN, gate.state.value)
    }
}
