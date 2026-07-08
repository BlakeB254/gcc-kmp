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
data class ObservedSpotItem(
    val lat: Double,
    val lng: Double,
    val itemType: String,
    val severity: Int? = null,
    val confidence: Float? = null,
    /** Estimated mass in grams (type-typical mass scaled by LiDAR-measured size). */
    val estimatedWeightGrams: Int? = null,
    /** Physical width measured from LiDAR depth + bounding-box angular size. */
    val estimatedWidthMeters: Double? = null,
    /**
     * world_observations.domain: null/'litter' for trash; 'vegetation' /
     * 'construction' for auto-registered landmark elements (trees, fences…)
     * the scan maps to the grid so future scans expect them.
     */
    val domain: String? = null,
    /**
     * Epoch millis when the item was observed. Lets post-processing correlate
     * the item to the manifest's GPS/heading path samples (which carry atMs);
     * without it an item can't be matched to where the camera actually was.
     */
    val atMs: Long? = null,
    /**
     * AR-local compass bearing (degrees, [0, 360)) from the camera to the
     * observed face at capture time — the orientation a true-scale footprint
     * should render at. Null when unmeasurable (no geo-ref, no camera
     * position, or a degenerate camera/hit position).
     */
    val yawDegrees: Double? = null,
)

/**
 * Result of a guided Object Capture (photogrammetry) pass over large litter:
 * a reconstructed USDZ model in R2 plus its measured bounding box. Dimensions
 * are real-world meters from the reconstruction bounds; volume assumes a
 * ~50% bbox fill; weight uses the loose-mixed-litter bulk density heuristic.
 */
@Serializable
data class ObjectCaptureResult(
    val r2Key: String? = null,
    val widthMeters: Double,
    val heightMeters: Double,
    val depthMeters: Double,
    val volumeLiters: Double,
    val estimatedWeightGrams: Int,
)

@kotlinx.serialization.Serializable
data class CreateSpotRequest(
    val type: CommunityReportType = CommunityReportType.SIGHTING,
    val lat: Double,
    val lng: Double,
    /**
     * Per-item positions observed during the environment scan (heading-projected
     * from the capture point). The server records one world observation per
     * item at its own coordinates, feeding the map/heatmap layers accurately.
     */
    val observedItems: List<ObservedSpotItem>? = null,
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
    /**
     * Admin-only: when set, the admin is recording a spot on behalf of
     * another worker. The created spot is attributed to that worker and
     * they receive the standard observer reward (10 pts).
     */
    val discoveredByWorkerId: Int? = null,
    /** Guided 3D capture of large litter (measured model), when performed. */
    val objectCapture: ObjectCaptureResult? = null,
    /**
     * Cells (server `gridCell()` ids, see [com.gcc.shared.geo.gridCell10m]) a
     * scan's coverage tracker actually swept at GRAZED+ quality, beyond just
     * the anchor + [observedItems] positions. The server treats any of these
     * NOT re-confirmed by an anchor/item as "checked and found clean now"
     * and supersedes stale reports/observations there (Pillar 8 task 1).
     * Omitted/null for non-scan submissions and old clients — server
     * behavior is unchanged when absent.
     */
    val coveredCellIds: List<String>? = null,
)

@Serializable
data class SpotResponse(
    val report: CommunityReport,
    val matchingBounties: List<Bounty> = emptyList(),
    val pointsAwarded: Int = 0,
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
