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

@Serializable
enum class RouteMarkerType {
    @SerialName("note") NOTE,
    @SerialName("photo") PHOTO,
    @SerialName("video") VIDEO,
    @SerialName("issue") ISSUE,
    @SerialName("bounty_response") BOUNTY_RESPONSE,
}

@Serializable
enum class RouteSessionStatus {
    @SerialName("active") ACTIVE,
    @SerialName("paused") PAUSED,
    @SerialName("completed") COMPLETED,
    @SerialName("abandoned") ABANDONED,
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
    @SerialName("verified") VERIFIED,
    @SerialName("already_clean") ALREADY_CLEAN,
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
enum class SpotVerificationResult {
    @SerialName("still_dirty") STILL_DIRTY,
    @SerialName("already_clean") ALREADY_CLEAN
}

@Serializable
enum class PointsAction {
    @SerialName("spot_created") SPOT_CREATED,
    @SerialName("verification_dirty") VERIFICATION_DIRTY,
    @SerialName("verification_clean") VERIFICATION_CLEAN,
    @SerialName("volunteer_cleanup") VOLUNTEER_CLEANUP,
    @SerialName("already_clean_report") ALREADY_CLEAN_REPORT
}

@Serializable
enum class CommunityReportStatus {
    @SerialName("pending") PENDING,
    @SerialName("verified") VERIFIED,
    @SerialName("resolved") RESOLVED,
    @SerialName("acknowledged") ACKNOWLEDGED,
    @SerialName("rejected") REJECTED
}

// ─── Admin Enums ────────────────────────────────────────────────────

@Serializable
enum class UserRole {
    @SerialName("worker") WORKER,
    @SerialName("admin") ADMIN,
    @SerialName("super_admin") SUPER_ADMIN,
}

@Serializable
enum class BulkAction {
    @SerialName("verify") VERIFY,
    @SerialName("suspend") SUSPEND,
    @SerialName("activate") ACTIVATE,
    @SerialName("delete") DELETE,
}

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

// ─── Dispute Enums ──────────────────────────────────────────────────

@Serializable
enum class DisputeType {
    @SerialName("quality") QUALITY,
    @SerialName("incomplete") INCOMPLETE,
    @SerialName("no_show") NO_SHOW,
    @SerialName("payment") PAYMENT,
    @SerialName("misconduct") MISCONDUCT,
    @SerialName("other") OTHER,
}

@Serializable
enum class DisputeStatus {
    @SerialName("open") OPEN,
    @SerialName("under_review") UNDER_REVIEW,
    @SerialName("resolved") RESOLVED,
    @SerialName("escalated") ESCALATED,
}

@Serializable
enum class DisputePartyType {
    @SerialName("worker") WORKER,
    @SerialName("sponsor") SPONSOR,
    @SerialName("admin") ADMIN,
}

@Serializable
enum class DisputeResolution {
    @SerialName("refund_full") REFUND_FULL,
    @SerialName("refund_partial") REFUND_PARTIAL,
    @SerialName("pay_worker") PAY_WORKER,
    @SerialName("split") SPLIT,
    @SerialName("dismissed") DISMISSED,
    @SerialName("escalated") ESCALATED,
}
