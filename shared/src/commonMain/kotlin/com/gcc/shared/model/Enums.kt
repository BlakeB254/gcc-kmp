package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EarlyAdopterTier {
    @SerialName("founder") FOUNDER,
    @SerialName("pioneer") PIONEER,
    @SerialName("early") EARLY,
    @SerialName("standard") STANDARD
}

@Serializable
enum class VerificationStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
    @SerialName("needs_info") NEEDS_INFO
}

@Serializable
enum class DocumentStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
    @SerialName("resubmit") RESUBMIT
}

@Serializable
enum class StripeOnboardingStatus {
    @SerialName("not_started") NOT_STARTED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("restricted") RESTRICTED
}

@Serializable
enum class PendingAddressStatus {
    @SerialName("none") NONE,
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED
}

@Serializable
enum class WaitlistStatus {
    @SerialName("waitlisted") WAITLISTED,
    @SerialName("invited") INVITED,
    @SerialName("converted") CONVERTED
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
enum class BlockStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("funded") FUNDED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED
}

@Serializable
enum class JobStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("claimed") CLAIMED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("pending_review") PENDING_REVIEW,
    @SerialName("completed") COMPLETED,
    @SerialName("disputed") DISPUTED
}

@Serializable
enum class TransactionStatus {
    @SerialName("pending") PENDING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("refunded") REFUNDED
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

@Serializable
enum class UserType {
    @SerialName("worker") WORKER,
    @SerialName("sponsor") SPONSOR
}

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
    @SerialName("route_end") ROUTE_END
}

@Serializable
enum class CommunityReportType {
    @SerialName("sighting") SIGHTING,
    @SerialName("volunteer_cleanup") VOLUNTEER_CLEANUP,
    @SerialName("bounty_triggered") BOUNTY_TRIGGERED
}

@Serializable
enum class WalletTransactionType {
    @SerialName("deposit") DEPOSIT,
    @SerialName("allocation") ALLOCATION,
    @SerialName("job_deduction") JOB_DEDUCTION,
    @SerialName("bounty_deduction") BOUNTY_DEDUCTION,
    @SerialName("refund") REFUND
}

@Serializable
enum class FundType {
    @SerialName("general") GENERAL,
    @SerialName("route") ROUTE,
    @SerialName("bounty") BOUNTY
}

@Serializable
enum class BountyStatus {
    @SerialName("open") OPEN,
    @SerialName("first_refusal") FIRST_REFUSAL,
    @SerialName("claimed") CLAIMED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("expired") EXPIRED,
    @SerialName("cancelled") CANCELLED
}

@Serializable
enum class VolunteerCleanupStatus {
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("verified") VERIFIED
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
enum class PosterType {
    @SerialName("sponsor") SPONSOR,
    @SerialName("worker") WORKER
}

@Serializable
enum class WalletReferenceType {
    @SerialName("job") JOB,
    @SerialName("bounty") BOUNTY,
    @SerialName("bounty_pledge") BOUNTY_PLEDGE,
    @SerialName("stripe_deposit") STRIPE_DEPOSIT
}

@Serializable
enum class PledgeStatus {
    @SerialName("active") ACTIVE,
    @SerialName("expired") EXPIRED,
    @SerialName("refunded") REFUNDED,
    @SerialName("paid_out") PAID_OUT
}

@Serializable
enum class CommunityReportStatus {
    @SerialName("pending") PENDING,
    @SerialName("verified") VERIFIED,
    @SerialName("resolved") RESOLVED,
    @SerialName("acknowledged") ACKNOWLEDGED,
    @SerialName("rejected") REJECTED
}
