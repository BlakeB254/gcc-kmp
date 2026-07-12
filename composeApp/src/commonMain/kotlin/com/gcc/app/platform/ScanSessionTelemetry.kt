package com.gcc.app.platform

import kotlin.concurrent.Volatile

/**
 * Process-global handle to the ACTIVE scan session's flight recorder
 * ([SessionEventLog]) — the seam that lets long-lived services
 * ([com.gcc.app.platform.capture.CaptureChunkUploader], the ViewModels'
 * upload paths) and [DiagnosticsLog]'s sink reach the current session's
 * event stream without threading a reference through every constructor.
 *
 * One scan session is active at a time by construction (SpotScreen and the
 * route-lap screens are mutually exclusive navigation destinations), so a
 * single slot suffices; [activate] also installs the DiagnosticsLog sink so
 * every diagnostics line lands in the stream as a `diag` event, and
 * [deactivate]/[deactivateSession] tear both down together.
 */
object ScanSessionTelemetry {

    @Volatile
    var active: SessionEventLog? = null
        private set

    fun activate(log: SessionEventLog) {
        active = log
        DiagnosticsLog.sessionSink = { entry ->
            log.diag(entry.level.name, entry.tag, entry.message)
        }
    }

    /** Clears [log] if it is still the active one (a newer session may
     *  already have taken the slot — never tear that one down). */
    fun deactivate(log: SessionEventLog) {
        if (active === log) {
            active = null
            DiagnosticsLog.sessionSink = null
        }
    }

    fun deactivateSession(sessionId: String) {
        active?.takeIf { it.sessionId == sessionId }?.let(::deactivate)
    }

    /** The active log only when it belongs to [sessionId] — upload workers
     *  processing a BACKLOGGED session's chunks must not write into the
     *  currently-running session's stream. */
    fun forSession(sessionId: String): SessionEventLog? =
        active?.takeIf { it.sessionId == sessionId }
}
