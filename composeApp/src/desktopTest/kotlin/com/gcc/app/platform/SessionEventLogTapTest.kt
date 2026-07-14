package com.gcc.app.platform

import com.gcc.shared.model.ScanEventJson
import com.gcc.shared.model.ScanSessionEvent
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tap telemetry at the source (t2-task-1, tap_count=0 field bug): the `tap`
 * event now carries an optional `state` so a tap suppressed by the auto-lock
 * confirm card ([com.gcc.app.platform.ArEnvScanOverlay.focusAt]'s early
 * return) is still visible to the flight recorder instead of vanishing.
 */
class SessionEventLogTapTest {

    private fun log(nowMs: () -> Long) = SessionEventLog(
        sessionId = "env-test-1",
        startedAtMs = 1_000L,
        nowMs = nowMs,
    )

    private fun decode(line: String): ScanSessionEvent = ScanEventJson.decodeFromString(ScanSessionEvent.serializer(), line)

    @Test
    fun tapEventCarriesOptionalState() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.tap(state = "suppressed")
        val events = log.snapshotLines().map(::decode)
        assertTrue(events.any { it.type == "tap" && it.state == "suppressed" })
    }

    @Test
    fun tapEventWithoutStateOmitsIt() = runBlocking {
        val log = log(nowMs = { 1_000L })
        log.tap()
        val events = log.snapshotLines().map(::decode)
        assertTrue(events.any { it.type == "tap" && it.state == null })
    }
}
