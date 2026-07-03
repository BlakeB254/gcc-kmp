package com.gcc.app.platform

import com.gcc.shared.model.EnvScanCell
import com.gcc.shared.model.EnvScanPathSample
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Accumulates one environment-scan session (screen-open → Complete Scan):
 * the GPS/facing track, and the coverage cells credited — everything the
 * session manifest needs beyond the photos the ViewModel already holds.
 */
class EnvScanSessionRecorder {
    val sessionId: String = buildString {
        append("env-")
        append(Clock.System.now().toEpochMilliseconds())
        append("-")
        repeat(6) { append("abcdefghijklmnopqrstuvwxyz0123456789".random(Random)) }
    }
    val startedAtMs: Long = Clock.System.now().toEpochMilliseconds()

    private val samples = mutableListOf<EnvScanPathSample>()

    fun addSample(lat: Double, lng: Double, headingDegrees: Float?, accuracyMeters: Double?) {
        if (samples.size >= MAX_SAMPLES) return
        samples.add(
            EnvScanPathSample(
                lat = lat,
                lng = lng,
                headingDegrees = headingDegrees,
                accuracyMeters = accuracyMeters,
                atMs = Clock.System.now().toEpochMilliseconds(),
            )
        )
    }

    fun draft(scannedCellCenters: List<Pair<Double, Double>>): EnvScanSessionDraft =
        EnvScanSessionDraft(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            path = samples.toList(),
            scannedCells = scannedCellCenters.map { (lat, lng) -> EnvScanCell(lat, lng) },
        )

    companion object {
        /** ~1Hz for an hour — nobody scans longer in one session. */
        private const val MAX_SAMPLES = 3600
    }
}

/** Immutable snapshot handed to the ViewModel at Complete Scan. */
data class EnvScanSessionDraft(
    val sessionId: String,
    val startedAtMs: Long,
    val path: List<EnvScanPathSample>,
    val scannedCells: List<EnvScanCell>,
)
