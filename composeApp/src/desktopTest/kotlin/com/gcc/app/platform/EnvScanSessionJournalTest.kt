package com.gcc.app.platform

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
    fun recorderJournalsSamplesAndGateEventsRoundTrip() {
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        recorder.addSample(41.85, -87.68, 90f, 5.0)
        recorder.addSample(41.851, -87.681, null, 4.0)
        recorder.noteGateEvent("indoor@123 reason=ceiling")

        val lines = File(path).readLines()
        assertEquals(3, lines.size)

        val (samples, gateEvents) = parseSessionJournal(lines)
        val draft = recorder.draft(emptyList())
        assertEquals(draft.path, samples)
        assertEquals(draft.gateEvents, gateEvents)
    }

    @Test
    fun corruptLineIsSkippedByRecoveryParser() {
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        recorder.addSample(41.0, -87.0, null, 5.0)
        // Simulate an app-kill-mid-write / on-disk corruption: an unparsable
        // line landed between two good ones.
        File(path).appendText("{not valid json\n")
        recorder.addSample(41.001, -87.001, null, 5.0)

        val lines = File(path).readLines()
        assertEquals(3, lines.size) // 2 good + 1 corrupt

        val (samples, _) = parseSessionJournal(lines)
        assertEquals(2, samples.size)
        assertEquals(listOf(41.0, 41.001), samples.map { it.lat })
    }

    @Test
    fun purgeSinceRewritesJournalToSurvivingEntriesOnly() {
        val path = tempJournalPath()
        val recorder = EnvScanSessionRecorder(journalPath = path)
        recorder.addSample(41.0, -87.0, null, 5.0)
        val cutoffAt = recorder.draft(emptyList()).path.first().atMs
        recorder.addSample(41.001, -87.001, null, 5.0) // recorded after cutoffAt below

        recorder.purgeSince(cutoffAt)

        val draftAfter = recorder.draft(emptyList())
        assertEquals(0, draftAfter.path.size) // both samples are at/after cutoffAt

        val (samples, gateEvents) = parseSessionJournal(File(path).readLines())
        assertEquals(0, samples.size)
        // The purge itself is recorded as a gate event, and rewriteJournal
        // persists it — the journal on disk must match the in-memory draft.
        assertEquals(draftAfter.gateEvents, gateEvents)
        assertTrue(gateEvents.any { it.startsWith("purge@") })
    }

    @Test
    fun journalFailureDegradesToMemoryOnlyAfterExactlyOneLoggedError() {
        // A path whose "directory" is actually a plain file: mkdirs()/write
        // must fail every time, exercising the recorder's degrade-once path.
        val blockerFile = File.createTempFile("gcc-journal-blocker", "").apply { writeText("not a dir") }
        val badPath = File(blockerFile, "session.jsonl").path

        val errorsBefore = DiagnosticsLog.entries.value.count { it.tag == "env-capture" }
        val recorder = EnvScanSessionRecorder(journalPath = badPath)
        recorder.addSample(41.0, -87.0, null, 5.0)
        recorder.addSample(41.001, -87.001, null, 5.0)
        recorder.noteGateEvent("gate@1")
        val errorsAfter = DiagnosticsLog.entries.value.count { it.tag == "env-capture" }

        // In-memory recording must be unaffected by the disk failure.
        assertEquals(3, recorder.draft(emptyList()).path.size + recorder.draft(emptyList()).gateEvents.size)
        // Exactly one failure logged, not one per call.
        assertEquals(1, errorsAfter - errorsBefore)
    }
}
