package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CompletionReport(
    val jobId: Int,
    val jobType: String,
    val workerName: String,
    val location: Coordinates,
    val communityArea: String? = null,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMinutes: Int,
    val beforePhotos: List<String> = emptyList(),
    val afterPhotos: List<String> = emptyList(),
    val gpsPath: List<Coordinates>? = null,
    val payoutAmount: Int,
    val fundType: String,
    val remainingBalance: Int,
)
