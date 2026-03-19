package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Scout & Clean Enums ─────────────────────────────────────────────

@Serializable
enum class DeviceType {
    @SerialName("ar") AR,
    @SerialName("gps") GPS,
}

@Serializable
enum class ScanPhase {
    @SerialName("scouting") SCOUTING,
    @SerialName("scout_review") SCOUT_REVIEW,
    @SerialName("cleaning") CLEANING,
    @SerialName("submitting") SUBMITTING,
    @SerialName("complete") COMPLETE,
}

@Serializable
enum class ScanStatus {
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("abandoned") ABANDONED,
}

@Serializable
enum class HotspotStatus {
    @SerialName("detected") DETECTED,
    @SerialName("cleaned") CLEANED,
    @SerialName("skipped") SKIPPED,
}
