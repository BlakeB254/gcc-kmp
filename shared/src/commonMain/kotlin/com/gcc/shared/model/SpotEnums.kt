package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CommunityReportType {
    @SerialName("sighting") SIGHTING,
    @SerialName("volunteer_cleanup") VOLUNTEER_CLEANUP,
    @SerialName("bounty_triggered") BOUNTY_TRIGGERED
}

@Serializable
enum class CommunityReportStatus {
    @SerialName("pending") PENDING,
    @SerialName("verified") VERIFIED,
    @SerialName("resolved") RESOLVED,
    @SerialName("acknowledged") ACKNOWLEDGED,
    @SerialName("rejected") REJECTED
}

@Serializable
enum class SpotTag {
    @SerialName("trash") TRASH,
    @SerialName("leaves") LEAVES,
    @SerialName("glass") GLASS,
    @SerialName("metal") METAL,
    @SerialName("tires") TIRES,
    @SerialName("furniture") FURNITURE,
    @SerialName("construction") CONSTRUCTION,
    @SerialName("organic") ORGANIC,
    @SerialName("hazardous") HAZARDOUS,
    @SerialName("electronics") ELECTRONICS,
    @SerialName("clothing") CLOTHING,
    @SerialName("graffiti") GRAFFITI,
    @SerialName("other") OTHER,
}

@Serializable
enum class SpotVerificationResult {
    @SerialName("still_dirty") STILL_DIRTY,
    @SerialName("already_clean") ALREADY_CLEAN
}

@Serializable
enum class VolunteerCleanupStatus {
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("verified") VERIFIED,
    @SerialName("already_clean") ALREADY_CLEAN,
}

@Serializable
enum class PointsAction {
    @SerialName("spot_created") SPOT_CREATED,
    @SerialName("verification_dirty") VERIFICATION_DIRTY,
    @SerialName("verification_clean") VERIFICATION_CLEAN,
    @SerialName("volunteer_cleanup") VOLUNTEER_CLEANUP,
    @SerialName("already_clean_report") ALREADY_CLEAN_REPORT,
    @SerialName("admin_grant") ADMIN_GRANT,
    @SerialName("bug_report") BUG_REPORT,
}

@Serializable
enum class PosterType {
    @SerialName("sponsor") SPONSOR,
    @SerialName("worker") WORKER
}
