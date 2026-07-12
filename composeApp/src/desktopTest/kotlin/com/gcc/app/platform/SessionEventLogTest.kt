package com.gcc.app.platform

import com.gcc.shared.model.ScanEventJson
import com.gcc.shared.model.ScanSessionEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Flight recorder (scan-session-replay PRD Phase 1): the SessionEventLog is
 * the structured JSONL event stream for one scan session — captions,
 * tap→raycast→pill transitions, confirm/reject, auto-detections, gate
 * changes, coverage milestones, lap transitions, upload results, and
 * diagnostics — timestamped session-relative, capped, and mirrored to disk
 * (iOS) for crash salvage. Same single-writer determinism strategy as
 * [EnvScanSessionJournalTest]: await the FIFO writer before asserting.
 */
class SessionEventLogTest {

    private fun tempEventsPath(): String {
        val dir = File.createTempFile("gcc-events-test", "").also { it.delete(); it.mkdirs() }
        return File(dir, "session.events.jsonl").path
    }

    private fun log(
        startedAtMs: Long = 1_000L,
        path: String? = null,
        maxEvents: Int = 4000,
        nowMs: () -> Long,
    ) = SessionEventLog(
        sessionId = "env-test-1",
        startedAtMs = startedAtMs,
        logPath = path,
        maxEvents = maxEvents,
        nowMs = nowMs,
    )

    private fun decode(line: String): ScanSessionEvent = ScanEventJson.decodeFromString(ScanSessionEvent.serializer(), line)

    @Test
    fun eventsCarrySessionRelativeTimestamps() = runBlocking {
        var now = 1_000L
        val log = log(startedAtMs = 1_000L, nowMs = { now })
        log.caption("Sweep the camera slowly")
        now = 6_000L
        log.gate("OUTDOOR")
        val events = log.snapshotLines().map(::decode)
        assertEquals(listOf(0L, 5_000L), events.map { it.t })
    }

    @Test
    fun consecutiveDuplicateCaptionsCollapse() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.caption("A")
        log.caption("A")
        log.caption("B")
        log.caption("A")
        val captions = log.snapshotLines().map(::decode).filter { it.type == "caption" }
        assertEquals(listOf("A", "B", "A"), captions.map { it.msg })
    }

    @Test
    fun coverageMilestonesEmitOnceEachOnCrossing() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.coverage(10.0)
        log.coverage(30.0)
        log.coverage(30.0)
        log.coverage(80.0)
        log.coverage(96.0)
        log.coverage(97.0)
        val milestones = log.snapshotLines().map(::decode).filter { it.type == "coverage" }
        assertEquals(listOf(25, 50, 75, 95), milestones.map { it.pct })
    }

    @Test
    fun pillPhaseTransitionsDedupeConsecutive() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.pillPhase("LOCATING")
        log.pillPhase("LOCATING")
        log.pillPhase("AWAITING_CONFIRMATION", label = "plastic bottle", confidence = 0.82f, distanceM = 2.1)
        val pills = log.snapshotLines().map(::decode).filter { it.type == "pill" }
        assertEquals(listOf("LOCATING", "AWAITING_CONFIRMATION"), pills.map { it.state })
        assertEquals("plastic bottle", pills[1].label)
    }

    @Test
    fun eventCapDropsAndSnapshotRecordsTruncation() = runBlocking {
        val log = log(maxEvents = 5, nowMs = { 1_000L })
        repeat(9) { log.tap() }
        val events = log.snapshotLines().map(::decode)
        assertEquals(5, events.count { it.type == "tap" })
        val truncated = events.last()
        assertEquals("truncated", truncated.type)
        assertEquals(4, truncated.pct) // dropped count rides pct
    }

    @Test
    fun snapshotLinesAreCompactJson() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.caption("Sweep")
        val line = log.snapshotLines().single()
        assertFalse(line.contains("label"), "null fields must not be encoded: $line")
        assertFalse(line.contains("null"), "null fields must not be encoded: $line")
        assertEquals("caption", decode(line).type)
    }

    @Test
    fun typedEventsCarryTheirFields() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.tap()
        log.detection(label = "can", confidence = 0.71f, distanceM = 3.4)
        log.confirm(label = "can")
        log.reject(label = "leaf")
        log.gate("INDOOR", cause = "ceiling-plane")
        log.lap("clean")
        log.upload(kind = "chunk", ok = false, detail = "PUT 401")
        log.diag("ERROR", "capture-upload", "chunk PUT 4xx — skipped permanently")
        val events = log.snapshotLines().map(::decode)
        assertEquals(
            listOf("tap", "detection", "confirm", "reject", "gate", "lap", "upload", "diag"),
            events.map { it.type },
        )
        val detection = events[1]
        assertEquals("can", detection.label)
        assertEquals(0.71f, detection.confidence)
        assertEquals(3.4, detection.distanceM)
        val gate = events[4]
        assertEquals("INDOOR", gate.state)
        assertEquals("ceiling-plane", gate.msg)
        val upload = events[6]
        assertEquals(false, upload.ok)
        assertEquals("chunk", upload.tag)
        val diag = events[7]
        assertEquals("ERROR", diag.state)
        assertEquals("capture-upload", diag.tag)
    }

    @Test
    fun journalMirrorsEventsToDiskForCrashSalvage() = runBlocking {
        val path = tempEventsPath()
        val log = log(path = path, nowMs = { 1_000L })
        log.caption("Sweep")
        log.gate("OUTDOOR")
        log.awaitIdle()
        val onDisk = File(path).readLines().map(::decode)
        assertEquals(listOf("caption", "gate"), onDisk.map { it.type })
    }

    @Test
    fun snapshotWithoutTruncationHasNoTruncationMarker() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.tap()
        val events = log.snapshotLines().map(::decode)
        assertNull(events.find { it.type == "truncated" })
        assertTrue(events.isNotEmpty())
    }
}
