package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BlockStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("funded") FUNDED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED
}

@Serializable
enum class SponsorType {
    @SerialName("individual") INDIVIDUAL,
    @SerialName("business") BUSINESS,
    @SerialName("organization") ORGANIZATION,
    @SerialName("foundation") FOUNDATION
}

@Serializable
enum class SponsorFrequency {
    @SerialName("one_time") ONE_TIME,
    @SerialName("monthly") MONTHLY,
    @SerialName("quarterly") QUARTERLY,
    @SerialName("annual") ANNUAL
}

@Serializable
enum class SponsorPackageStatus {
    @SerialName("draft") DRAFT,
    @SerialName("quoted") QUOTED,
    @SerialName("paid") PAID,
    @SerialName("active") ACTIVE,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED
}

@Serializable
enum class EnterpriseTier {
    @SerialName("community_champion") COMMUNITY_CHAMPION,
    @SerialName("impact_partner") IMPACT_PARTNER,
    @SerialName("documentary_sponsor") DOCUMENTARY_SPONSOR,
    @SerialName("movement_partner") MOVEMENT_PARTNER,
    @SerialName("legacy_sponsor") LEGACY_SPONSOR,
    @SerialName("founding_partner") FOUNDING_PARTNER
}

@Serializable
enum class SponsorMilestoneType {
    @SerialName("total_funded") TOTAL_FUNDED,
    @SerialName("jobs_funded") JOBS_FUNDED,
    @SerialName("routes_funded") ROUTES_FUNDED,
    @SerialName("bounties_funded") BOUNTIES_FUNDED,
    @SerialName("areas_covered") AREAS_COVERED
}

@Serializable
enum class MediaCategory {
    @SerialName("job_photo") JOB_PHOTO,
    @SerialName("worker_id") WORKER_ID,
    @SerialName("proof_of_address") PROOF_OF_ADDRESS,
    @SerialName("sponsor_logo") SPONSOR_LOGO,
    @SerialName("landing") LANDING,
    @SerialName("tax_document") TAX_DOCUMENT,
    @SerialName("other") OTHER
}
