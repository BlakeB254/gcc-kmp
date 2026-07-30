package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Flight recorder salvage (scan-session-replay PRD Phase 1): the launch-time
 * orphan sweep now pairs each session's journal (`{id}.jsonl`) with its
 * event-log mirror (`{id}.events.jsonl`) from ONE directory listing — the
 * pure pairing logic, so the sweep can't mistake an event log for a journal
 * (its filename also ends in `.jsonl`) or orphan an event log whose journal
 * never got a line (crash before the first GPS fix — exactly the session
 * whose event log matters most).
 */
class OrphanSessionRecoveryPlanTest {

    private val dir = "/docs/scan-sessions"

    @Test
    fun pairsJournalWithItsEventLog() {
        val plan = planOrphanSessionRecovery(
            listOf("$dir/env-1-abc.jsonl", "$dir/env-1-abc.events.jsonl"),
        )
        assertEquals(1, plan.size)
        assertEquals("env-1-abc", plan[0].sessionId)
        assertEquals("$dir/env-1-abc.jsonl", plan[0].journalPath)
        assertEquals("$dir/env-1-abc.events.jsonl", plan[0].eventLogPath)
    }

    @Test
    fun journalWithoutEventLogStillPlans() {
        val plan = planOrphanSessionRecovery(listOf("$dir/env-2-def.jsonl"))
        assertEquals(1, plan.size)
        assertEquals("env-2-def", plan[0].sessionId)
        assertNull(plan[0].eventLogPath)
    }

    @Test
    fun eventLogWithoutJournalStillPlans() {
        val plan = planOrphanSessionRecovery(listOf("$dir/env-3-ghi.events.jsonl"))
        assertEquals(1, plan.size)
        assertEquals("env-3-ghi", plan[0].sessionId)
        assertNull(plan[0].journalPath)
        assertEquals("$dir/env-3-ghi.events.jsonl", plan[0].eventLogPath)
    }

    @Test
    fun ignoresForeignFilesAndBlankIds() {
        val plan = planOrphanSessionRecovery(
            listOf("$dir/notes.txt", "$dir/.jsonl", "$dir/.events.jsonl", "$dir/env-4-jkl.jsonl"),
        )
        assertEquals(listOf("env-4-jkl"), plan.map { it.sessionId })
    }

    @Test
    fun sessionIdEpochRecoversStartTime() {
        assertEquals(1783347520720L, sessionIdEpochMs("env-1783347520720-fuvaue"))
        assertNull(sessionIdEpochMs("not-a-session-id"))
    }

    // ── planOrphanChunkBooking: per-ARTIFACT dedup (TestFlight 159 field
    // round — the late-finalizing .mov was stranded forever because the
    // sweep skipped any session that already had booked rows) ──

    private fun bookedRow(index: Long, kind: String) =
        com.gcc.shared.cache.CaptureChunkRecord(
            sessionId = "env-1-abc", chunkIndex = index, kind = kind,
            filePath = "/cap/chunk-$index.x", sizeBytes = 1L,
            locationCellId = "0:0", r2Key = null, uploadedAtMs = null, createdAtMs = 0L,
        )

    @Test
    fun lateVideoIsBookedWhenSidecarAndDepthAlreadyWere() {
        val plan = planOrphanChunkBooking(
            existing = listOf(bookedRow(0, "sidecar"), bookedRow(0, "depth")),
            files = listOf("/cap/chunk-0.mov", "/cap/chunk-0.jsonl", "/cap/chunk-0.depth"),
        )
        assertEquals(listOf(OrphanChunkFile(0, "video", "/cap/chunk-0.mov")), plan)
    }

    @Test
    fun fullyBookedSessionPlansNothing() {
        val plan = planOrphanChunkBooking(
            existing = listOf(bookedRow(0, "video"), bookedRow(0, "sidecar"), bookedRow(0, "depth")),
            files = listOf("/cap/chunk-0.mov", "/cap/chunk-0.jsonl", "/cap/chunk-0.depth"),
        )
        assertEquals(emptyList(), plan)
    }

    @Test
    fun unbookedSessionPlansEveryChunkFileAndIgnoresForeignNames() {
        val plan = planOrphanChunkBooking(
            existing = emptyList(),
            files = listOf("/cap/chunk-0.mov", "/cap/chunk-1.jsonl", "/cap/notes.txt", "/cap/chunk-x.mov"),
        )
        assertEquals(
            listOf(
                OrphanChunkFile(0, "video", "/cap/chunk-0.mov"),
                OrphanChunkFile(1, "sidecar", "/cap/chunk-1.jsonl"),
            ),
            plan,
        )
    }

    @Test
    fun unbookedCompletionPhotoIsRecoveredAsDurableArtifact() {
        val plan = planOrphanChunkBooking(
            existing = emptyList(),
            files = listOf("/cap/photos/photo-0.jpg"),
        )

        assertEquals(
            listOf(OrphanChunkFile(0, "photo", "/cap/photos/photo-0.jpg")),
            plan,
        )
    }
}
