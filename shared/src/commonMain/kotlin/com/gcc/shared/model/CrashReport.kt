package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CrashReportRequest(
    val deviceId: String,
    val platform: String,
    val appVersion: String? = null,
    val exceptionMessage: String,
    val stackTrace: String,
    val occurredAt: Instant,
)

/**
 * One MetricKit crash/hang diagnostic JSON (scan-session-replay PRD Phase 1):
 * previously stranded on-device in Files → gcc-crash-diagnostics, now
 * uploaded on next launch via POST /api/crashes/diagnostics — the server
 * stores the full [payload] in R2 (`diagnostics/metrickit/…`) and indexes a
 * `crash_reports` row referencing it. [payload] is the raw
 * `MXDiagnosticPayload.jsonRepresentation()` string, uninterpreted.
 */
@Serializable
data class MetricKitDiagnosticRequest(
    val deviceId: String,
    val platform: String,
    val appVersion: String? = null,
    /** The on-device filename (`diagnostic-<ISO8601>-<n>.json`) — carries the
     *  delivery timestamp and keys the R2 object, so retries overwrite. */
    val fileName: String,
    val payload: String,
)
