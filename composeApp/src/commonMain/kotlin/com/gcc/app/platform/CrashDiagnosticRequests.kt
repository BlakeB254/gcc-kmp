package com.gcc.app.platform

import com.gcc.shared.model.CrashReportRequest
import com.gcc.shared.model.MetricKitDiagnosticRequest
import kotlinx.datetime.Instant

/**
 * Keep direct crash uploads tagged with the current app build so first-party
 * crash rows can be attributed to the exact TestFlight/App Store binary.
 */
internal fun crashReportRequest(
    deviceId: String,
    platform: String,
    exceptionMessage: String,
    stackTrace: String,
    occurredAt: Instant,
): CrashReportRequest = CrashReportRequest(
    deviceId = deviceId,
    platform = platform,
    appVersion = appVersion(),
    exceptionMessage = exceptionMessage,
    stackTrace = stackTrace,
    occurredAt = occurredAt,
)

/**
 * Keep MetricKit diagnostic uploads tagged with the same app build string the
 * rest of crash telemetry uses, so server-side incident clustering can tell
 * which TestFlight/App Store binary produced a payload.
 */
internal fun metricKitDiagnosticRequest(
    deviceId: String,
    platform: String,
    fileName: String,
    payload: String,
): MetricKitDiagnosticRequest = MetricKitDiagnosticRequest(
    deviceId = deviceId,
    platform = platform,
    appVersion = appVersion(),
    fileName = fileName,
    payload = payload,
)
