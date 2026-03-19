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
enum class RouteSessionStatus {
    @SerialName("active") ACTIVE,
    @SerialName("paused") PAUSED,
    @SerialName("completed") COMPLETED,
    @SerialName("abandoned") ABANDONED,
}

@Serializable
enum class RouteMarkerType {
    @SerialName("note") NOTE,
    @SerialName("photo") PHOTO,
    @SerialName("video") VIDEO,
    @SerialName("issue") ISSUE,
    @SerialName("bounty_response") BOUNTY_RESPONSE,
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

// ─── Feedback Enums ──────────────────────────────────────────────────

@Serializable
enum class FeedbackCategory {
    @SerialName("safety_concern") SAFETY_CONCERN,
    @SerialName("app_issue") APP_ISSUE,
    @SerialName("verification_problem") VERIFICATION_PROBLEM,
    @SerialName("payment_issue") PAYMENT_ISSUE,
    @SerialName("general_improvement") GENERAL_IMPROVEMENT,
    @SerialName("feature_request") FEATURE_REQUEST,
    @SerialName("other") OTHER
}

@Serializable
enum class FeedbackStatus {
    @SerialName("open") OPEN,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("waiting_response") WAITING_RESPONSE,
    @SerialName("resolved") RESOLVED,
    @SerialName("closed") CLOSED
}

@Serializable
enum class FeedbackPriority {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("urgent") URGENT
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
