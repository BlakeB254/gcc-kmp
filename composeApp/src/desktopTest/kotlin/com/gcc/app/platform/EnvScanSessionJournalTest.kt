package com.gcc.app.platform

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pillar 6 (disk-backed session recorder): journal append/rewrite + the
 * launch-recovery parser's round-trip, including corrupt-line tolerance.
 * Desktop actuals implement the journal file ops with plain java.io (see
 * ObjectCapture.desktop.kt) purely so this suite can exercise the real
 * on-disk format even though desktop itself never sets a journalPath in the
 * app (sessionJournalPath returns null there).
 *
 * Determinism with the async writer: recorder journal writes run on a
 * private single-writer (FIFO) coroutine, so tests call the internal
 * [EnvScanSessionRecorder.awaitJournalIdle] (join a fresh no-op job — FIFO
 * means everything enqueued before it has executed) before asserting on
 * file contents. No test dispatcher needed; the real dispatcher stays in
 * play, matching production.
 */
class EnvScanSessionJournalTest {

    private fun tempJournalPath(): String {
        val dir = File.createTempFile("gcc-journal-test", "").also { it.delete(); it.mkdirs() }
        return File(dir, "session.jsonl").path
    }

    @Test
    fun appendLineToFileCreatesFileAndParentDirs() {
        val root = File.createTempFile("gcc-journal-nested", "").also { it.delete(); it.mkdirs() }
        val path = File(root, "nested/dir/session.jsonl").path
        appendLineToFile(path, "line-one")
        appendLineToFile(path, "line-two")
        val lines = File(path).readLines()
        assertEquals(listOf("line-one", "line-two"), lines)
    }

    @Test
    fun appendLineToFileGrowsByExactlyOneLinePerCall() {
        // Semantic O(1)-append check: each call adds exactly one line and
        // leaves every previously-written line untouched — the contract a
        // true seek-to-end append satisfies and a lossy rewrite would break.
        val path = tempJournalPath()
        for (i in 1..5) {
            appendLineToFile(path, "line-$i")
            assertEquals(i, File(path).readLines().size)
        }
        assertEquals((1..5).map { "line-$it" }, File(path).readLines())
    }

    @Test
    fun writeFileLinesTruncatesAndRewrites() {
        val path = tempJournalPath()
        appendLineToFile(path, "a")
        appendLineToFile(path, "b")
        appendLineToFile(path, "c")
        writeFileLines(path, listOf("only-this"))
        assertEquals(listOf("only-this"), File(path).readLines())
    }

    @Test
    fun listFilesInDirReturnsChildrenAndEmptyForMissingDir() {
        val dir = File.createTempFile("gcc-list-test", "").also { it.delete(); it.mkdirs() }
        File(dir, "a.jsonl").writeText("x")
        File(dir, "b.jsonl").writeText("y")
        val listed = listFilesInDir(dir.path).map { File(it).name }.sorted()
        assertEquals(listOf("a.jsonl", "b.jsonl"), listed)
        assertTrue(listFilesInDir(dir.path + "/does-not-exist").isEmpty())
    }

    @Test
    fun recorderCreatesNoFileUntilFirstRecordedEvent() = runBlocking {
        // Launch recovery's active-session exclusion rests on this: a merely
        // constructed recorder must be invisible to the sweep's dir listing.
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        recorder.awaitJournalIdle()
        assertTrue(!File(path).exists())
        recorder.addSample(41.0, -87.0, null, 5.0)
        recorder.awaitJournalIdle()
        assertTrue(File(path).exists())
    }

    @Test
    fun recorderJournalsSamplesAndGateEventsRoundTrip() = runBlocking {
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        recorder.addSample(41.85, -87.68, 90f, 5.0)
        recorder.addSample(41.851, -87.681, null, 4.0)
        recorder.noteGateEvent("indoor@123 reason=ceiling")
        recorder.awaitJournalIdle()

        val lines = File(path).readLines()
        assertEquals(3, lines.size)

        val (samples, gateEvents) = parseSessionJournal(lines)
        val draft = recorder.draft(emptyList())
        assertEquals(draft.path, samples)
        assertEquals(draft.gateEvents, gateEvents)
    }

    @Test
    fun geoRefEventsJournalRoundTripAndRateLimit() = runBlocking {
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        fun event(atMs: Long) = com.gcc.shared.model.GeoRefEvent(
            atMs = atMs, lat = 41.85, lng = -87.68, accuracyMeters = 4.0,
            arX = 1.5f, arY = 0.1f, arZ = -3.25f, arTimestampSeconds = 1234.5,
        )
        recorder.addGeoRefEvent(event(atMs = 10_000))
        recorder.addGeoRefEvent(event(atMs = 12_000)) // <5s after previous — thinned
        recorder.addGeoRefEvent(event(atMs = 16_000)) // ≥5s — kept
        recorder.awaitJournalIdle()

        val draft = recorder.draft(emptyList())
        assertEquals(listOf(10_000L, 16_000L), draft.geoRefEvents.map { it.atMs })

        // Disk round-trip: recovery salvages the pairings so an app kill
        // never produces a pose stream the DGX can't register.
        val parsed = parseSessionJournal(File(path).readLines())
        assertEquals(draft.geoRefEvents, parsed.geoRefEvents)
        assertEquals(1.5f, parsed.geoRefEvents.first().arX)
        assertEquals(1234.5, parsed.geoRefEvents.first().arTimestampSeconds)
    }

    @Test
    fun corruptLineIsSkippedByRecoveryParser() = runBlocking {
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        recorder.addSample(41.0, -87.0, null, 5.0)
        recorder.awaitJournalIdle()
        // Simulate an app-kill-mid-write / on-disk corruption: an unparsable
        // line landed between two good ones.
        File(path).appendText("{not valid json\n")
        recorder.addSample(41.001, -87.001, null, 5.0)
        recorder.awaitJournalIdle()

        val lines = File(path).readLines()
        assertEquals(3, lines.size) // 2 good + 1 corrupt

        val (samples, _) = parseSessionJournal(lines)
        assertEquals(2, samples.size)
        assertEquals(listOf(41.0, 41.001), samples.map { it.lat })
    }

    @Test
    fun purgeSinceRewritesJournalToSurvivingEntriesOnly() = runBlocking {
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        recorder.addSample(41.0, -87.0, null, 5.0)
        val cutoffAt = recorder.draft(emptyList()).path.first().atMs
        recorder.addSample(41.001, -87.001, null, 5.0) // recorded after cutoffAt below

        recorder.purgeSince(cutoffAt)
        recorder.awaitJournalIdle()

        val draftAfter = recorder.draft(emptyList())
        assertEquals(0, draftAfter.path.size) // both samples are at/after cutoffAt

        val diskLines = File(path).readLines()
        val (samples, gateEvents) = parseSessionJournal(diskLines)
        assertEquals(0, samples.size)
        // The purge itself is recorded as ONE gate event carried by the
        // rewrite (not double-written by an extra append) — disk must match
        // the in-memory draft exactly, line-for-entry.
        assertEquals(draftAfter.gateEvents, gateEvents)
        assertEquals(draftAfter.gateEvents.size, diskLines.size)
        assertTrue(gateEvents.any { it.startsWith("purge@") })
    }

    @Test
    fun journalFailureDegradesToMemoryOnlyAfterExactlyOneLoggedError() = runBlocking {
        // A path whose "directory" is actually a plain file: mkdirs()/write
        // must fail every time, exercising the recorder's degrade-once path.
        val blockerFile = File.createTempFile("gcc-journal-blocker", "").apply { writeText("not a dir") }
        val badPath = File(blockerFile, "session.jsonl").path

        val errorsBefore = DiagnosticsLog.entries.value.count { it.tag == "env-capture" }
        val recorder = EnvScanSessionRecorder(journalPath = badPath)
        recorder.addSample(41.0, -87.0, null, 5.0)
        recorder.addSample(41.001, -87.001, null, 5.0)
        recorder.noteGateEvent("gate@1")
        recorder.awaitJournalIdle()
        val errorsAfter = DiagnosticsLog.entries.value.count { it.tag == "env-capture" }

        // In-memory recording must be unaffected by the disk failure.
        assertEquals(3, recorder.draft(emptyList()).path.size + recorder.draft(emptyList()).gateEvents.size)
        // Exactly one failure logged (the single-writer coroutine flips the
        // degrade flag before the next queued write runs), not one per call.
        assertEquals(1, errorsAfter - errorsBefore)
    }
}
