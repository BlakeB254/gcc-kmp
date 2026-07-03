package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Auto-generated block candidate awaiting admin review — see
 * site/src/db/schema.ts `blockProposals` for the source of truth. Nothing
 * a proposal describes is live until an admin approves it, which inserts a
 * real [Block] row on the server.
 */
@Serializable
data class BlockProposal(
    val id: Int,
    val status: BlockProposalStatus,
    val codeHint: String? = null,
    val name: String,
    val locationNeighborhood: String? = null,
    val boundary: GeoJSONPolygon,
    val center: GeoJSONPoint,
    val streetName: String? = null,
    val fromCorner: String? = null,
    val toCorner: String? = null,
    val side: String? = null,
    val exclusionZoneCount: Int = 0,
    val source: String? = null,
    val generationBatch: String? = null,
    val reviewedByAdminId: Int? = null,
    val reviewedAt: String? = null,
    val rejectionReason: String? = null,
    val publishedBlockId: Int? = null,
    val createdAt: String? = null,
)

@Serializable
enum class BlockProposalStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
}
