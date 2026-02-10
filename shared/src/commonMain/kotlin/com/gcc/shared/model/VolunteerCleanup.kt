package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class VolunteerCleanup(
    val id: Int,
    val reportId: Int,
    val workerId: Int,
    val beforeMediaUrls: List<String> = emptyList(),
    val afterMediaUrls: List<String> = emptyList(),
    val trashHeapMediaUrl: String? = null,
    val aiTrashAnalysis: AiAnalysis? = null,
    val locationLat: Double,
    val locationLng: Double,
    val locationVerified: Boolean = false,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val durationMinutes: Int? = null,
    val pdfUrl: String? = null,
    val status: VolunteerCleanupStatus = VolunteerCleanupStatus.IN_PROGRESS,
    val verificationNotes: String? = null,
    val pointsAwarded: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)
