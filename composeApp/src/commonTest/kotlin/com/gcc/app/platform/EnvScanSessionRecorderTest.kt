package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvScanSessionRecorderTest {

    @Test
    fun purgeSinceDropsTrailingSamples() {
        val recorder = EnvScanSessionRecorder()
        recorder.addSample(41.0, -87.0, null, 5.0)   // stamped now
        val draftBefore = recorder.draft(emptyList())
        assertEquals(1, draftBefore.path.size)
        // Cutoff in the past ⇒ the just-added sample is inside the purge window.
        recorder.purgeSince(draftBefore.path.first().atMs)
        assertEquals(0, recorder.draft(emptyList()).path.size)
    }

    @Test
    fun gateEventsRideTheDraft() {
        val recorder = EnvScanSessionRecorder()
        recorder.noteGateEvent("indoor@123 reason=ceiling")
        recorder.noteGateEvent("purge@124 dropped=3")
        assertEquals(2, recorder.draft(emptyList()).gateEvents.size)
    }
}
