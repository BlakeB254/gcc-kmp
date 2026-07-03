@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

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

