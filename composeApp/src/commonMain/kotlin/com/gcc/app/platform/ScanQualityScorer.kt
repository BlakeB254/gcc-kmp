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

    /**
     * Deep-scan quality bar (spec WS3): the CLOSE/deep coverage shade only
     * locks in on a genuinely good scan. Gates SHADE TIER ONLY — capture and
     * submission stay ungated (this object's feedback-only contract). Wraps
     * the existing score/tier so there is one definition of "GOOD".
     */
    fun meetsDeepScanQuality(signal: ScanQualitySignal): Boolean =
        tierOf(score(signal)) == ScanQualityTier.GOOD

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

/** A–F grade + one advice line for a *completed* scan session. */
data class ScanScoreResult(
    val grade: Char,
    val headline: String,
    val advice: String?,
)

/**
 * The end-of-session sibling of [ScanQualityScorer]: same file, same
 * philosophy (weighted-sum composite → banded verdict → single
 * lowest-component advice line, mirroring [ScanQualityScorer.limitingFactor]),
 * deliberately kept as ONE scoring system rather than a second, parallel
 * implementation of scan quality.
 *
 * The two differ in what they fuse and when: [ScanQualityScorer] fuses
 * instantaneous per-tick signals (tracking state, GPS accuracy, detection
 * confidence) into a live in-scan badge. [ScanScore] fuses session-aggregate
 * outcomes — [CoverageTracker]'s final `coveragePercent`/`closePercent`, and
 * `steadinessFraction` (the fraction of the session's tracking ticks that
 * were [TrackingState.NORMAL], i.e. the session-long counterpart of the same
 * trackingState signal [ScanQualityScorer] weights highest per-tick) — into
 * the one-time completion grade. A session with a mostly-POOR live badge
 * (frequent LIMITED/NOT_AVAILABLE ticks) necessarily posts a low
 * steadinessFraction here too, so the live badge and the completion grade
 * agree in spirit even though neither literally calls the other.
 */
object ScanScore {

    private const val COVERAGE_WEIGHT = 0.45
    private const val CLOSE_WEIGHT = 0.35
    private const val STEADINESS_WEIGHT = 0.20

    /** Absolute covered area that earns full effort credit for a free
     *  environment scan — roughly a diligent few-minute sidewalk sweep.
     *  See [compute]'s recalibration note. */
    const val FULL_EFFORT_AREA_SQM = 200.0

    /**
     * @param coveragePercent [CoverageTracker.coveragePercent], 0..100.
     * @param closePercent [CoverageTracker.closePercent], 0..100.
     * @param steadinessFraction fraction (0..1) of session tracking ticks
     *   that were [TrackingState.NORMAL]. Sessions with no AR tracking
     *   history (desktop/Android, or an AR-less session) should pass 1.0 —
     *   absence isn't a steadiness failure.
     * @param areaCoveredSquareMeters when provided (free environment scans),
     *   effort is graded on the ABSOLUTE ground covered instead of
     *   [coveragePercent]'s fraction-of-corridor.
     *
     * Recalibration (Blake's TestFlight 145 video: a good-faith 1:21 sweep
     * covering 189m² graded a red F): the original formula double-penalized —
     * a free env scan's "corridor" is a synthetic disc around the anchor, so
     * a worker who swept a clean strip of it lost coverage points for the
     * rest of the disc AND close points because closePercent shared that same
     * disc denominator. Now (1) effort = absolute area against
     * [FULL_EFFORT_AREA_SQM] when the caller can supply it, and (2) close
     * quality = close fraction OF THE COVERED GROUND (closePercent /
     * coveragePercent — the disc denominators cancel), i.e. "how well did
     * you scan what you scanned", not "how much of the disc did you
     * detail-scan".
     */
    fun compute(
        coveragePercent: Double,
        closePercent: Double,
        steadinessFraction: Double,
        areaCoveredSquareMeters: Double? = null,
    ): ScanScoreResult {
        val coverageNorm = (coveragePercent / 100.0).coerceIn(0.0, 1.0)
        val effortNorm = areaCoveredSquareMeters
            ?.let { (it / FULL_EFFORT_AREA_SQM).coerceIn(0.0, 1.0) }
            ?: coverageNorm
        val closeNorm = if (coverageNorm > 0.0) {
            ((closePercent / 100.0) / coverageNorm).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val steadinessNorm = steadinessFraction.coerceIn(0.0, 1.0)

        val composite = effortNorm * COVERAGE_WEIGHT +
            closeNorm * CLOSE_WEIGHT +
            steadinessNorm * STEADINESS_WEIGHT
        val compositePercent = composite * 100.0

        val grade = when {
            compositePercent >= 90.0 -> 'A'
            compositePercent >= 80.0 -> 'B'
            compositePercent >= 65.0 -> 'C'
            compositePercent >= 50.0 -> 'D'
            else -> 'F'
        }
        val headline = when (grade) {
            'A' -> "Excellent scan"
            'B' -> "Good scan"
            'C' -> "Decent scan"
            'D' -> "Rough scan"
            else -> "Incomplete scan"
        }
        // Lowest-scoring component drives the advice, same shape as
        // ScanQualityScorer.limitingFactor — one reason, not a checklist.
        // Order (close, coverage, steadiness) breaks ties deterministically.
        val advice = if (grade == 'A') {
            null
        } else {
            listOf(
                closeNorm to "Walk closer — most of your area was sweep-only",
                effortNorm to "Cover more ground next time",
                steadinessNorm to "Hold the phone steadier while scanning",
            ).minByOrNull { it.first }?.second
        }

        return ScanScoreResult(grade = grade, headline = headline, advice = advice)
    }
}
