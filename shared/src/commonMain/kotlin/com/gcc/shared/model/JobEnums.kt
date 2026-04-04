package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
enum class WalletTransactionType {
    @SerialName("deposit") DEPOSIT,
    @SerialName("allocation") ALLOCATION,
    @SerialName("job_deduction") JOB_DEDUCTION,
    @SerialName("bounty_deduction") BOUNTY_DEDUCTION,
    @SerialName("refund") REFUND
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
enum class BountyVisibility {
    @SerialName("public") PUBLIC,
    @SerialName("secret") SECRET
}

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
