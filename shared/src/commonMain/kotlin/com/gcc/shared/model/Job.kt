package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class Job(
    val id: Int,
    val blockId: Int? = null,
    val routeId: Int? = null,
    val workerId: Int? = null,
    val status: JobStatus = JobStatus.AVAILABLE,
    val timing: JobTiming = JobTiming(),
    val photos: JsonArray? = null,
    val gpsPath: JsonArray? = null,
    val payoutAmount: Int? = null,
    val payoutStatus: TransactionStatus? = null,
    val rating: Double? = null,
    val reviewNotes: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class JobTiming(
    val claimedAt: Instant? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val durationMinutes: Int? = null
)
