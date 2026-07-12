package com.gcc.backend

import com.gcc.shared.model.ScanEventJson
import com.gcc.shared.model.ScanSessionEvent
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Headline metrics extracted from one session's flight-recorder event stream
 * (`events.jsonl.gz`, scan-session-replay PRD Phase 1 §3) at index time —
 * the numbers the admin review surface filters/red-flags on. Mirrored into
 * `scan_sessions` (migration 0037); all columns nullable there, so a session
 * simply has no metrics when it predates the recorder or the artifact
 * couldn't be fetched.
 */
data class EventLogMetrics(
    val eventCount: Int,
    val gateFlipCount: Int,
    val tapCount: Int,
    val confirmCount: Int,
    val rejectCount: Int,
    val raycastMissCount: Int,
    val detectionCount: Int,
    val uploadAttemptCount: Int,
    val uploadFailureCount: Int,
    val errorCount: Int,
)

object EventLogMetricsExtractor {

    /** Gunzip an `events.jsonl.gz` artifact into its raw JSONL lines. */
    fun gunzipLines(gz: ByteArray): List<String> =
        GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
            .decodeToString()
            .split("\n")
            .filter { it.isNotBlank() }

    /**
     * Tolerant single pass: torn/corrupt lines (crash-salvaged logs) are
     * skipped, unknown event types (newer clients) still count toward
     * [EventLogMetrics.eventCount]. Gate flips are transitions between
     * DISTINCT consecutive gate states — the first gate event establishes
     * the baseline and is not itself a flip.
     */
    fun extract(lines: Sequence<String>): EventLogMetrics {
        var events = 0
        var gateFlips = 0
        var lastGateState: String? = null
        var taps = 0
        var confirms = 0
        var rejects = 0
        var raycastMisses = 0
        var detections = 0
        var uploadAttempts = 0
        var uploadFailures = 0
        var errors = 0
        for (line in lines) {
            val event = try {
                ScanEventJson.decodeFromString(ScanSessionEvent.serializer(), line)
            } catch (e: Exception) {
                continue
            }
            events++
            when (event.type) {
                "gate" -> {
                    val state = event.state
                    if (state != null && lastGateState != null && state != lastGateState) gateFlips++
                    if (state != null) lastGateState = state
                }
                "tap" -> taps++
                "confirm" -> confirms++
                "reject" -> rejects++
                "pill" -> if (event.state == "NO_RAYCAST") raycastMisses++
                "detection" -> detections++
                "upload" -> {
                    uploadAttempts++
                    if (event.ok == false) uploadFailures++
                }
                "diag" -> if (event.state == "ERROR") errors++
            }
        }
        return EventLogMetrics(
            eventCount = events,
            gateFlipCount = gateFlips,
            tapCount = taps,
            confirmCount = confirms,
            rejectCount = rejects,
            raycastMissCount = raycastMisses,
            detectionCount = detections,
            uploadAttemptCount = uploadAttempts,
            uploadFailureCount = uploadFailures,
            errorCount = errors,
        )
    }
}
