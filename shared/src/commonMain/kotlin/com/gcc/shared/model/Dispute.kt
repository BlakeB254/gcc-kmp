package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Dispute(
    val id: Int,
    val jobId: Int,
    val reporterId: Int,
    val reporterType: DisputePartyType,
    val respondentId: Int,
    val respondentType: DisputePartyType,
    val type: DisputeType,
    val status: DisputeStatus,
    val description: String,
    val evidence: List<String> = emptyList(),
    val resolution: DisputeResolution? = null,
    val resolutionNotes: String? = null,
    val resolvedBy: Int? = null,
    val resolvedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class CreateDisputeRequest(
    val jobId: Int,
    val respondentId: Int,
    val respondentType: DisputePartyType,
    val type: DisputeType,
    val description: String,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class ResolveDisputeRequest(
    val resolution: DisputeResolution,
    val notes: String,
    val refundAmount: Int? = null,
    val payoutAmount: Int? = null,
)

@Serializable
enum class DisputeType {
    QUALITY,
    INCOMPLETE,
    NO_SHOW,
    PAYMENT,
    MISCONDUCT,
    OTHER,
}

@Serializable
enum class DisputeStatus {
    OPEN,
    UNDER_REVIEW,
    RESOLVED,
    ESCALATED,
}

@Serializable
enum class DisputePartyType {
    WORKER,
    SPONSOR,
    ADMIN,
}

@Serializable
enum class DisputeResolution {
    REFUND_FULL,
    REFUND_PARTIAL,
    PAY_WORKER,
    SPLIT,
    DISMISSED,
    ESCALATED,
}
