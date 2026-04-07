package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class CaptureAttestation(
    val capturedAt: Long,
    val capturedLat: Double? = null,
    val capturedLng: Double? = null,
    val deviceModel: String,
    val osVersion: String,
    val sessionId: String,
    val captureIndex: Int,
    val isLiveCapture: Boolean = true,
)

@Serializable
data class OnDeviceAnalysisResult(
    val trashTypes: List<String> = emptyList(),
    val estimatedWeightLbs: Double? = null,
    val severity: Int? = null,
    val confidence: Float? = null,
    val suggestedTags: List<SpotTag> = emptyList(),
    val generatedDescription: String? = null,
    val imageEmbedding: List<Float>? = null,
)

@Serializable
data class CreateSpotRequest(
    val type: CommunityReportType = CommunityReportType.SIGHTING,
    val lat: Double,
    val lng: Double,
    val description: String? = null,
    val mediaUrls: List<String>? = null,
    val affectedRadiusMeters: Double = 15.0,
    val affectedArea: AffectedArea? = null,
    val tags: List<SpotTag> = emptyList(),
    val reporterName: String? = null,
    val depthAnalysis: DepthAnalysis? = null,
    val captureAttestation: CaptureAttestation? = null,
    val onDeviceAiResult: OnDeviceAnalysisResult? = null,
    val duplicateOverrideReason: String? = null,
)

@Serializable
data class SpotResponse(
    val report: CommunityReport,
    val matchingBounties: List<Bounty> = emptyList(),
)

@Serializable
data class PhotoUploadResponse(
    val urls: List<String> = emptyList(),
)

@Serializable
data class StartVolunteerRequest(
    val beforeMediaUrls: List<String> = emptyList(),
    val beforeDepthAnalysis: DepthAnalysis? = null,
    val beforeAiAnalysis: OnDeviceAnalysisResult? = null,
)

@Serializable
data class CompleteVolunteerRequest(
    val afterMediaUrls: List<String> = emptyList(),
    val trashHeapMediaUrl: String? = null,
    val afterDepthAnalysis: DepthAnalysis? = null,
    val afterAiAnalysis: OnDeviceAnalysisResult? = null,
)

@Serializable
data class DepositRequest(
    val amount: Int,
    val stripePaymentIntentId: String? = null,
)

@Serializable
data class AllocateRequest(
    val generalAmount: Int = 0,
    val routeAmount: Int = 0,
    val bountyAmount: Int = 0,
)

@Serializable
data class CreateBountyRequest(
    val title: String,
    val description: String? = null,
    val amount: Int,
    val locationLat: Double,
    val locationLng: Double,
    val radiusMeters: Double = 200.0,
    val communityArea: String? = null,
    val neighborhood: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val triggerReportId: Int? = null,
    val visibility: BountyVisibility = BountyVisibility.PUBLIC,
    val isAnonymous: Boolean = false,
    val posterName: String? = null,
)

@Serializable
data class SponsorBlockRequest(
    val blockId: Int,
    val preferredDate: String,
    val preferredTime: String,
    val amountCents: Int = 10000,
    val isAnonymous: Boolean = false,
    val posterName: String? = null,
)

@Serializable
data class CheckoutRequest(
    val amount: Int? = null,
    val blockIds: List<Int>? = null,
    val frequency: String = "one_time",
    val allocationType: String = "general",
    val name: String,
    val email: String,
    val displayName: String? = null,
    val isAnonymous: Boolean = false,
    val message: String? = null,
    val preferredCleaningDate: String? = null,
    val preferredCleaningTimeStart: String? = null,
    val preferredCleaningTimeEnd: String? = null,
)

@Serializable
data class CheckoutResponse(
    val checkoutUrl: String,
    val sessionId: String,
    val demo: Boolean = false,
)

@Serializable
data class CompleteBountyRequest(
    val afterMediaUrls: List<String> = emptyList(),
)
