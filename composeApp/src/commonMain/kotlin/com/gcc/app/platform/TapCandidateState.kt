package com.gcc.app.platform

/** At/below this raycast distance a tapped candidate is close enough to identify. */
const val TAP_IDENTIFY_MAX_METERS = 4.0

/** At/above this classifier confidence a tapped candidate is "identified". */
const val TAP_IDENTIFY_MIN_CONFIDENCE = 0.5f

/** The label a tapped candidate carries when the classifier names nothing. */
const val UNKNOWN_LITTER_LABEL = "Unknown litter"

enum class TapPhase { IDLE, LOCATING, CHECKING, AWAITING_CONFIRMATION, NO_RAYCAST }

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
)

sealed interface TapEvent {
    data class Tapped(val normalizedX: Float, val normalizedY: Float) : TapEvent
    data class RaycastHit(val lat: Double, val lng: Double, val distanceMeters: Float) : TapEvent
    object RaycastMissed : TapEvent
    data class Classified(val label: String?, val confidence: Float?) : TapEvent
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
    is TapEvent.Classified ->
        if (phase != TapPhase.CHECKING) this
        else copy(
            phase = TapPhase.AWAITING_CONFIRMATION,
            label = event.label ?: UNKNOWN_LITTER_LABEL,
            confidence = event.confidence,
        )
    TapEvent.Confirmed, TapEvent.Rejected, TapEvent.Dismissed -> TapCandidateState()
}

/** The model named it, was confident, and it is close enough to trust. */
val TapCandidateState.isIdentified: Boolean
    get() = label != null &&
        label != UNKNOWN_LITTER_LABEL &&
        (confidence ?: 0f) >= TAP_IDENTIFY_MIN_CONFIDENCE &&
        (distanceMeters?.toDouble() ?: Double.MAX_VALUE) <= TAP_IDENTIFY_MAX_METERS

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
    TapPhase.NO_RAYCAST -> TapPillCopy("Hold steady and tap the item again", null, null)
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
