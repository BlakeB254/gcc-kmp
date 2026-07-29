package com.gcc.app.platform

/** At/below this raycast distance a tapped candidate is close enough to identify. */
const val TAP_IDENTIFY_MAX_METERS = 4.0

/** At/above this classifier confidence a tapped candidate is "identified". */
const val TAP_IDENTIFY_MIN_CONFIDENCE = 0.5f

/** The label a tapped candidate carries when the classifier names nothing. */
const val UNKNOWN_LITTER_LABEL = "Unknown litter"

enum class TapPhase { IDLE, LOCATING, CHECKING, AWAITING_CONFIRMATION, NO_RAYCAST, GPS_SYNCING }

/**
 * The one pending tap-to-identify candidate. A tap is authoritative (Option A):
 * it always reaches [TapPhase.AWAITING_CONFIRMATION] if it raycasts, even when
 * the model returns no label. Nothing is persisted until the user confirms.
 */
data class TapCandidateState(
    val phase: TapPhase = TapPhase.IDLE,
    val normalizedX: Float? = null,
    val normalizedY: Float? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val distanceMeters: Float? = null,
    val label: String? = null,
    val confidence: Float? = null,
    /** Image-space crop the evaluation classified (normalized center + half
     *  side, top-left origin) — the Stage-2 training box for a confirmed tap. */
    val imageCx: Double? = null,
    val imageCy: Double? = null,
    val imageHalf: Double? = null,
)

sealed interface TapEvent {
    data class Tapped(val normalizedX: Float, val normalizedY: Float) : TapEvent
    data class RaycastHit(val lat: Double, val lng: Double, val distanceMeters: Float) : TapEvent
    object RaycastMissed : TapEvent

    /** The raycast HIT a surface but no AR↔GPS geo-reference is paired yet,
     *  so the hit can't resolve to a world coordinate. Distinct from
     *  [RaycastMissed]: "hold steady and tap again" is a lie here — no
     *  amount of steadiness helps until the GPS pairing lands. */
    object NoGeoRef : TapEvent
    data class Classified(
        val label: String?,
        val confidence: Float?,
        /** The crop that was evaluated — see [TapCandidateState.imageCx]. */
        val imageCx: Double? = null,
        val imageCy: Double? = null,
        val imageHalf: Double? = null,
    ) : TapEvent
    object Confirmed : TapEvent
    object Rejected : TapEvent
    object Dismissed : TapEvent
}

/**
 * Fold one [TapEvent] into the candidate.
 *
 * [TapEvent.Confirmed], [TapEvent.Rejected] and [TapEvent.Dismissed] all
 * return a fresh IDLE state — the caller MUST read the outgoing state (to
 * persist a confirmation or a negative example) BEFORE reducing, because the
 * reducer discards it.
 *
 * Events that do not belong to the current phase are ignored, so a late
 * raycast or classification from a superseded tap cannot resurrect it.
 */
fun TapCandidateState.reduce(event: TapEvent): TapCandidateState = when (event) {
    // A new tap always supersedes whatever was pending — including its raycast.
    is TapEvent.Tapped -> TapCandidateState(
        phase = TapPhase.LOCATING,
        normalizedX = event.normalizedX,
        normalizedY = event.normalizedY,
    )
    is TapEvent.RaycastHit ->
        if (phase != TapPhase.LOCATING) this
        else copy(
            phase = TapPhase.CHECKING,
            lat = event.lat,
            lng = event.lng,
            distanceMeters = event.distanceMeters,
        )
    TapEvent.RaycastMissed ->
        if (phase != TapPhase.LOCATING) this else copy(phase = TapPhase.NO_RAYCAST)
    TapEvent.NoGeoRef ->
        if (phase != TapPhase.LOCATING) this else copy(phase = TapPhase.GPS_SYNCING)
    is TapEvent.Classified -> when (phase) {
        TapPhase.CHECKING -> copy(
            phase = TapPhase.AWAITING_CONFIRMATION,
            label = event.label ?: UNKNOWN_LITTER_LABEL,
            confidence = event.confidence,
            imageCx = event.imageCx,
            imageCy = event.imageCy,
            imageHalf = event.imageHalf,
        )
        // Forced evaluation (Blake 2026-07-28): a tap that couldn't PLACE
        // (no surface / no geo-ref) still EVALUATES — the classification
        // lands here so the pill can report what the camera saw. Label stays
        // nullable (no UNKNOWN fallback): there is no confirmation flow in
        // these states, only feedback.
        TapPhase.NO_RAYCAST, TapPhase.GPS_SYNCING -> copy(
            label = event.label,
            confidence = event.confidence,
            imageCx = event.imageCx,
            imageCy = event.imageCy,
            imageHalf = event.imageHalf,
        )
        else -> this
    }
    TapEvent.Confirmed, TapEvent.Rejected, TapEvent.Dismissed -> TapCandidateState()
}

/** The model named it, was confident, and it is close enough to trust. */
val TapCandidateState.isIdentified: Boolean
    get() = label != null &&
        label != UNKNOWN_LITTER_LABEL &&
        (confidence ?: 0f) >= TAP_IDENTIFY_MIN_CONFIDENCE &&
        (distanceMeters?.toDouble() ?: Double.MAX_VALUE) <= TAP_IDENTIFY_MAX_METERS

/**
 * One item found by a tap SWEEP (Blake 2026-07-28: "if I tap a view, identify
 * as many trash items in view as possible on each tap"). A sweep runs
 * objectness saliency over the whole frame at tap time, keeps regions that
 * geo-resolve to the ground and don't classify as fixture/backdrop, and
 * offers them as ONE batch confirmation — individual taps remain the
 * fallback for anything the sweep missed.
 */
data class TapSweepItem(
    val lat: Double,
    val lng: Double,
    val distanceMeters: Float,
    /** Normalized image-space box (top-left origin) — the training crop. */
    val imageCx: Double,
    val imageCy: Double,
    val imageHalfW: Double,
    val imageHalfH: Double,
    /** Normalized VIEW point for the on-screen marker dot. */
    val viewX: Double,
    val viewY: Double,
    /** AR-world hit point — anchors the persistent AR marker on confirm. */
    val worldX: Float = 0f,
    val worldY: Float = 0f,
    val worldZ: Float = 0f,
    /** Classifier guess, if any — display/typing hint only; naming is the
     *  backend's job (labels ride the manifest for the recon pipeline). */
    val label: String? = null,
)

data class TapSweepState(val items: List<TapSweepItem>)

/** Copy for the bottom pill. `null` means: draw no pill. */
data class TapPillCopy(
    val title: String,
    val confirmLabel: String?,
    val rejectLabel: String?,
)

fun tapPillCopy(state: TapCandidateState): TapPillCopy? = when (state.phase) {
    TapPhase.IDLE -> null
    TapPhase.LOCATING -> TapPillCopy("Locating…", null, null)
    TapPhase.CHECKING -> TapPillCopy("Checking…", null, null)
    TapPhase.NO_RAYCAST ->
        if (state.label != null && state.label != UNKNOWN_LITTER_LABEL) {
            TapPillCopy("Looks like: ${state.label} — aim at the ground and tap to place it", null, null)
        } else {
            TapPillCopy("Hold steady and tap the item again", null, null)
        }
    TapPhase.GPS_SYNCING ->
        if (state.label != null && state.label != UNKNOWN_LITTER_LABEL) {
            TapPillCopy("Looks like: ${state.label} — GPS is syncing, hold on a moment", null, null)
        } else {
            TapPillCopy("GPS is syncing — try again in a few seconds", null, null)
        }
    TapPhase.AWAITING_CONFIRMATION -> TapPillCopy(
        title = if (state.isIdentified) {
            "${state.label} — is this litter?"
        } else {
            "Move closer to identify — is this litter?"
        },
        confirmLabel = "Confirm litter",
        rejectLabel = "Not litter",
    )
}
