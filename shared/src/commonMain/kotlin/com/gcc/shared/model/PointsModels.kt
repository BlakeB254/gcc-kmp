package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PointsLedgerEntry(
    val id: Int,
    val workerId: Int = 0,
    val action: PointsAction,
    val points: Int,
    val referenceType: String? = null,
    val referenceId: Int? = null,
    val reason: String? = null,
    val grantedByWorkerId: Int? = null,
    val createdAt: Instant,
)

@Serializable
data class PointsSummary(
    val totalPoints: Int = 0,
    val breakdown: PointsBreakdown = PointsBreakdown(),
    val recentEntries: List<PointsLedgerEntry> = emptyList(),
)

@Serializable
data class PointsBreakdown(
    val spotCreated: Int = 0,
    val verificationDirty: Int = 0,
    val verificationClean: Int = 0,
    val volunteerCleanup: Int = 0,
    val alreadyCleanReport: Int = 0,
    val adminGrant: Int = 0,
    val bugReport: Int = 0,
)
