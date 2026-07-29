package com.gcc.shared.model

import kotlinx.serialization.Serializable

/**
 * Remote scan tuning served by GET /api/scan-config — the feedback half of
 * the error-telemetry loop (Blake 2026-07-29): scripts/reconstruction/
 * analyze_scan_errors.py analyzes every session's [EnvScanManifest.errorLog]
 * and proposes a tuning object; a human reviews and uploads it to
 * `config/scan-tuning.json`; the app fetches this at scan open and applies
 * ONLY these safe knobs. Every field defaults to the compiled behavior, so
 * a missing/partial/unfetchable config changes nothing.
 */
@Serializable
data class ScanTuning(
    val revision: Int = 0,
    /** Feature toggles — remote killswitches for the newest surfaces. */
    val sweepEnabled: Boolean = true,
    val drawnMarkEnabled: Boolean = true,
    /** LiDAR ground-sensor geometric caps (see GroundAnomalyDetector). */
    val sensorMaxFootprintM: Float = 1.4f,
    val sensorMaxHeightM: Float = 0.9f,
    /** Analyst note — surfaced in diagnostics only, never UI. */
    val notes: String? = null,
)
