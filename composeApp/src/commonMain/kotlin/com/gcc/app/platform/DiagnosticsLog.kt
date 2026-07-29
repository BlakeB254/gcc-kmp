package com.gcc.app.platform

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Global scan diagnostics log: every scan-flow error (and key lifecycle
 * event) lands here and ships with the session — the mixed tail in
 * `EnvScanManifest.diagnosticsLog`, the complete error ring in
 * `EnvScanManifest.errorLog` (build 175: the on-screen 🐞 badge was
 * retired; telemetry now travels with the scan data to the reconstruction
 * backend, where analyze_scan_errors.py aggregates it). Entries also go to
 * stdout (NSLog/logcat) for tethered debugging.
 *
 * Ring buffer, newest first, capped at [MAX_ENTRIES]; safe to call from any
 * thread/coroutine (single atomic StateFlow update per append).
 */
object DiagnosticsLog {

    private const val MAX_ENTRIES = 300

    enum class Level { INFO, ERROR }

    data class Entry(
        val atMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        /** "HH:mm:ss LEVEL [tag] message" — what the panel renders. */
        fun format(): String {
            val t = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(TimeZone.currentSystemDefault())
            val hh = t.hour.toString().padStart(2, '0')
            val mm = t.minute.toString().padStart(2, '0')
            val ss = t.second.toString().padStart(2, '0')
            val lvl = if (level == Level.ERROR) "ERR " else "info"
            return "$hh:$mm:$ss $lvl [$tag] $message"
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()


    /**
     * Flight-recorder mirror (scan-session-replay PRD Phase 1): while a scan
     * session is active, its [SessionEventLog] registers here and every entry
     * appended to this log is also mirrored into the session's event stream
     * (`diag` events) — gate causes, upload errors, and lifecycle notes reach
     * the replay with zero per-call-site churn. Set at session start, cleared
     * at completion by the owning ViewModel; @Volatile because appends can
     * come from any thread. Must never throw (see [append]).
     */
    @Volatile
    var sessionSink: ((Entry) -> Unit)? = null

    /** Cap on the diagnostics-log tail shipped in an
     *  [com.gcc.shared.model.EnvScanManifest] — bounds the manifest size; the
     *  ring buffer itself ([MAX_ENTRIES]) is sized for the on-screen panel
     *  and may hold more entries than this. */
    const val DIAGNOSTICS_TAIL_MAX = 300

    /**
     * The log tail as it ships in `EnvScanManifest.diagnosticsLog`: the most
     * recent [max] entries, all severities (info lines carry the context
     * around an error), formatted, in chronological order — most recent LAST.
     * Null when the log is empty so the manifest field stays wire-null.
     * Every manifest construction site (SpotViewModel, OrphanSessionRecovery,
     * RouteScanViewModel) must attach this — the DGX pipeline reads it to
     * debug scan sessions without a screen recording.
     */
    fun tailForManifest(max: Int = DIAGNOSTICS_TAIL_MAX): List<String>? =
        tailForManifest(_entries.value, max)

    /** [tailForManifest] over an explicit newest-first entries list (the
     *  testable seam — the object's own buffer is process-global state). */
    internal fun tailForManifest(entries: List<Entry>, max: Int): List<String>? =
        entries.take(max).asReversed().map { it.format() }.ifEmpty { null }

    fun info(tag: String, message: String) = append(Level.INFO, tag, message)

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { t ->
            ": ${t::class.simpleName}: ${t.message ?: "no message"}"
        } ?: ""
        append(Level.ERROR, tag, message + detail)
    }

    /** Dedicated ERROR ring (newest first): errors must survive info-line
     *  churn — the mixed [MAX_ENTRIES] ring can evict every error on a long
     *  session, which is exactly when they matter. Ships COMPLETE (up to
     *  the cap) in `EnvScanManifest.errorLog` to the reconstruction
     *  backend's bucket, where analyze_scan_errors.py aggregates it
     *  (Blake 2026-07-29: error telemetry to the world-model backend). */
    private const val MAX_ERROR_ENTRIES = 200
    private val _errorEntries = kotlinx.coroutines.flow.MutableStateFlow<List<Entry>>(emptyList())

    /** Every ERROR line this session (chronological), for the manifest.
     *  Null when clean so the wire field stays absent. */
    fun errorLinesForManifest(): List<String>? =
        _errorEntries.value.asReversed().map { it.format() }.ifEmpty { null }

    private fun append(level: Level, tag: String, message: String) {
        val entry = Entry(Clock.System.now().toEpochMilliseconds(), level, tag, message)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        if (level == Level.ERROR) {
            _errorEntries.value = (listOf(entry) + _errorEntries.value).take(MAX_ERROR_ENTRIES)
        }
        // A sink failure must never take down the code path that logged —
        // the event log is telemetry, the log line is the product.
        try {
            sessionSink?.invoke(entry)
        } catch (_: Throwable) {
        }
        // Console mirror for tethered runs (NSLog on iOS, stdout elsewhere).
        println("GCC-DIAG ${entry.format()}")
    }
}
