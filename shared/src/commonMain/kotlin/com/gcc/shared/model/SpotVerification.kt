package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SpotVerification(
    val id: Int,
    val reportId: Int,
    val verifierId: Int,
    val verifierName: String? = null,
    val result: SpotVerificationResult,
    val photoUrl: String? = null,
    val verifierLat: Double? = null,
    val verifierLng: Double? = null,
    val distanceFromSpotMeters: Double? = null,
    val pointsAwarded: Int = 0,
    val createdAt: Instant,
)

@Serializable
data class VerifySpotRequest(
    val result: SpotVerificationResult,
    val photoUrl: String,
    val verifierLat: Double? = null,
    val verifierLng: Double? = null,
    val expectedVerificationCount: Int? = null,
    val depthAnalysis: DepthAnalysis? = null,
    val aiAnalysis: OnDeviceAnalysisResult? = null,
    val captureAttestation: CaptureAttestation? = null,
)

@Serializable
data class VerifySpotResponse(
    val verification: SpotVerification,
    val pointsAwarded: Int,
    val spotResolved: Boolean,
)
