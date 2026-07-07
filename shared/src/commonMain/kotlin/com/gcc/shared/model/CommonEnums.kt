package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Route / Area Enums ──────────────────────────────────────────────

@Serializable
enum class ServiceAreaStatus {
    @SerialName("planning") PLANNING,
    @SerialName("pilot") PILOT,
    @SerialName("active") ACTIVE,
    @SerialName("paused") PAUSED
}

@Serializable
enum class RouteStatus {
    @SerialName("draft") DRAFT,
    @SerialName("published") PUBLISHED,
    @SerialName("archived") ARCHIVED
}

@Serializable
enum class CheckpointType {
    @SerialName("route_start") ROUTE_START,
    @SerialName("photo_point") PHOTO_POINT,
    @SerialName("alley_entrance") ALLEY_ENTRANCE,
    @SerialName("route_end") ROUTE_END,
    @SerialName("record") RECORD,
    @SerialName("clean") CLEAN,
    @SerialName("verify") VERIFY,
    @SerialName("record_clean_verify") RECORD_CLEAN_VERIFY,
}

@Serializable
enum class CameraMode {
    @SerialName("panoramic") PANORAMIC,
    @SerialName("focused") FOCUSED,
    @SerialName("video") VIDEO,
}

// ─── Admin Enums ─────────────────────────────────────────────────────

@Serializable
enum class AdminActivityType {
    @SerialName("worker_registered") WORKER_REGISTERED,
    @SerialName("worker_verified") WORKER_VERIFIED,
    @SerialName("job_completed") JOB_COMPLETED,
    @SerialName("bounty_posted") BOUNTY_POSTED,
    @SerialName("bounty_claimed") BOUNTY_CLAIMED,
    @SerialName("bounty_completed") BOUNTY_COMPLETED,
    @SerialName("dispute_opened") DISPUTE_OPENED,
    @SerialName("dispute_resolved") DISPUTE_RESOLVED,
    @SerialName("sponsor_registered") SPONSOR_REGISTERED,
    @SerialName("block_funded") BLOCK_FUNDED,
    @SerialName("route_published") ROUTE_PUBLISHED,
}
