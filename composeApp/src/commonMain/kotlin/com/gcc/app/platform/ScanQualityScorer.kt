package com.gcc.app.platform

/**
 * Inputs to a live, voluntary scan-quality estimate. All three signals
 * already exist independently ([ArSessionHealth]'s [TrackingState],
 * [LocationProvider]'s GPS accuracy, and detection confidence from the live
 * trash-detection pipeline) but were never fused into one number a worker
 * can glance at. This is feedback only -- nothing in the app gates capture
 * or scan submission on it; environment scans stay voluntary.
 */
data class ScanQualitySignal(
    val trackingState: TrackingState = TrackingState.NORMAL,
    val gpsAccuracyMeters: Double? = null,
    val detectionConfidence: Float? = null,
)

enum class ScanQualityTier { GOOD, FAIR, POOR }

object ScanQualityScorer {

    private const val GPS_GOOD_METERS = 8.0
    private const val GPS_POOR_METERS = 25.0

    // Neutral defaults when a signal is unavailable (e.g. no GPS fix yet, no
    // live detection this tick) -- absence shouldn't read as a failure, but
    // shouldn't assume perfect either.
    private const val GPS_UNKNOWN_SCORE = 0.6f
    private const val CONFIDENCE_UNKNOWN_SCORE = 0.7f

    private const val TRACKING_WEIGHT = 0.5f
    private const val GPS_WEIGHT = 0.3f
    private const val CONFIDENCE_WEIGHT = 0.2f

    private const val GOOD_THRESHOLD = 0.7f
    private const val FAIR_THRESHOLD = 0.4f

    /** 0f (worst) .. 1f (best) composite. Tracking state is weighted highest
     *  because a lost AR session makes GPS/detection quality moot regardless
     *  of how good either looks in isolation. */
    fun score(signal: ScanQualitySignal): Float {
        val trackingScore = when (signal.trackingState) {
            TrackingState.NORMAL -> 1f
            TrackingState.LIMITED -> 0.5f
            TrackingState.NOT_AVAILABLE -> 0f
        }
        val gpsScore = signal.gpsAccuracyMeters?.let { accuracy ->
            when {
                accuracy <= GPS_GOOD_METERS -> 1f
                accuracy >= GPS_POOR_METERS -> 0f
                else -> 1f - ((accuracy - GPS_GOOD_METERS) / (GPS_POOR_METERS - GPS_GOOD_METERS)).toFloat()
            }
        } ?: GPS_UNKNOWN_SCORE
        val confidenceScore = signal.detectionConfidence ?: CONFIDENCE_UNKNOWN_SCORE

        return (trackingScore * TRACKING_WEIGHT + gpsScore * GPS_WEIGHT + confidenceScore * CONFIDENCE_WEIGHT)
            .coerceIn(0f, 1f)
    }

    fun tierOf(score: Float): ScanQualityTier = when {
        score >= GOOD_THRESHOLD -> ScanQualityTier.GOOD
        score >= FAIR_THRESHOLD -> ScanQualityTier.FAIR
        else -> ScanQualityTier.POOR
    }

    /** Single most-actionable reason for a non-GOOD tier, for a short label
     *  ("Fair · GPS weak") rather than an opaque number. Null when GOOD. */
    fun limitingFactor(signal: ScanQualitySignal): String? = when {
        signal.trackingState == TrackingState.NOT_AVAILABLE -> "AR tracking lost"
        signal.trackingState == TrackingState.LIMITED -> "AR tracking unsteady"
        (signal.gpsAccuracyMeters ?: 0.0) >= GPS_POOR_METERS -> "GPS weak"
        (signal.detectionConfidence ?: 1f) < 0.5f -> "Low detection confidence"
        else -> null
    }
}
