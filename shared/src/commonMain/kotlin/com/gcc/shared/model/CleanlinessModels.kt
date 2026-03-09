package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class StreetCleanlinessStats(
    val street: String = "",
    val activeSpots: Int = 0,
    val avgDurationHours: Double = 0.0,
    val score: Int = 100,
)

@Serializable
data class CleanlinessStats(
    val area: String = "",
    val activeSpots: Int = 0,
    val resolvedSpots: Int = 0,
    val avgMessDurationHours: Double = 0.0,
    val avgVerificationVelocityHours: Double = 0.0,
    val verificationRate: Double = 0.0,
    val recurrenceRate: Double = 0.0,
    val cleanlinessScore: Int = 100,
    val totalVerifications: Int = 0,
    val totalVolunteerCleanups: Int = 0,
    val streets: List<StreetCleanlinessStats> = emptyList(),
)

@Serializable
data class CityCleanlinessOverview(
    val cityScore: Int = 100,
    val totalActiveSpots: Int = 0,
    val totalResolvedSpots: Int = 0,
    val avgMessDurationHours: Double = 0.0,
    val areas: List<CleanlinessStats> = emptyList(),
)
