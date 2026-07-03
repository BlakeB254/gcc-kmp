package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Global in-app diagnostics log for field testing: every scan-flow error (and
 * key lifecycle events) lands here so an on-screen panel ([com.gcc.app.ui.components.DiagnosticsOverlay])
 * can show the tail — an error screenshot then carries the "why", not just
 * the symptom. Entries also go to stdout (NSLog/logcat) for tethered debugging.
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

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    fun info(tag: String, message: String) = append(Level.INFO, tag, message)

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { t ->
            ": ${t::class.simpleName}: ${t.message ?: "no message"}"
        } ?: ""
        append(Level.ERROR, tag, message + detail)
    }

    private fun append(level: Level, tag: String, message: String) {
        val entry = Entry(Clock.System.now().toEpochMilliseconds(), level, tag, message)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        if (level == Level.ERROR) _errorCount.value += 1
        // Console mirror for tethered runs (NSLog on iOS, stdout elsewhere).
        println("GCC-DIAG ${entry.format()}")
    }
}
