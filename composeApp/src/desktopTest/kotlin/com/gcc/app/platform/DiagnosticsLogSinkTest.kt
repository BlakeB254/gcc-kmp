package com.gcc.app.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flight recorder: while a scan session is active its SessionEventLog
 * registers as [DiagnosticsLog]'s session sink, so every diagnostics entry
 * (gate causes, upload errors, recovery notes) is mirrored into the session
 * event stream with zero per-call-site churn. Desktop-hosted because the
 * sink is process-global state and JVM tests run suites in one process —
 * cleanup in @AfterTest keeps other suites unaffected.
 */
class DiagnosticsLogSinkTest {

    @AfterTest
    fun clearSink() {
        DiagnosticsLog.sessionSink = null
    }

    @Test
    fun sinkReceivesEveryAppendedEntry() {
        val seen = mutableListOf<DiagnosticsLog.Entry>()
        DiagnosticsLog.sessionSink = { seen.add(it) }
        DiagnosticsLog.info("outdoor-gate", "scene votes: outdoor streak 5")
        DiagnosticsLog.error("capture-upload", "chunk PUT failed", RuntimeException("401"))
        assertEquals(2, seen.size)
        assertEquals(DiagnosticsLog.Level.INFO, seen[0].level)
        assertEquals("outdoor-gate", seen[0].tag)
        assertEquals(DiagnosticsLog.Level.ERROR, seen[1].level)
        assertTrue(seen[1].message.contains("401"))
    }

    @Test
    fun clearedSinkStopsReceiving() {
        val seen = mutableListOf<DiagnosticsLog.Entry>()
        DiagnosticsLog.sessionSink = { seen.add(it) }
        DiagnosticsLog.info("env-scan", "one")
        DiagnosticsLog.sessionSink = null
        DiagnosticsLog.info("env-scan", "two")
        assertEquals(1, seen.size)
    }
}
