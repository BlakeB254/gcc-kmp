package com.gcc.app.viewmodel

import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.embedGpsExif
import com.gcc.app.ui.map.MapStyles
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sqrt

enum class SpotPhase { CAMERA, LOCATION, DESCRIPTION, SUBMITTING, RESULT }

enum class AreaMode { CIRCLE, RECTANGLE }

data class SpotUiState(
    val capturedPhotos: List<CapturedPhoto> = emptyList(),
    val currentLat: Double = MapStyles.NORTH_LAWNDALE_CENTER_LAT,
    val currentLng: Double = MapStyles.NORTH_LAWNDALE_CENTER_LNG,
    /** Spot pin position — null means use GPS position (currentLat/currentLng). */
    val spotLat: Double? = null,
    val spotLng: Double? = null,
    val affectedRadiusMeters: Double = 15.0,
    val hasAdjustedAffectedRadius: Boolean = false,
    val areaMode: AreaMode = AreaMode.CIRCLE,
    val rectWidth: Double = 20.0,
    val rectHeight: Double = 10.0,
    val rectRotation: Double = 0.0,
    val selectedTags: List<SpotTag> = emptyList(),
    val description: String = "",
    val isSubmitting: Boolean = false,
    val submittedReport: CommunityReport? = null,
    val matchingBounties: List<Bounty> = emptyList(),
    val createdBounty: Bounty? = null,
    val isCreatingBounty: Boolean = false,
    val bountyCreateError: String? = null,
    val error: String? = null,
    val phase: SpotPhase = SpotPhase.CAMERA,
    // On-device AI analysis
    val aiAnalysis: OnDeviceAnalysisResult? = null,
    val isAnalyzing: Boolean = false,
    val aiDescriptionApplied: Boolean = false,
    // Duplicate detection
    val duplicateMatch: DuplicateMatch? = null,
    val duplicateOverrideReason: String? = null,
    /** Trash items geo-tagged during the environment scan (minimap + submission). */
    val observedItems: List<ObservedSpotItem> = emptyList(),
)

data class DuplicateMatch(
    val existingSpot: CommunityReport,
    val visualSimilarity: Float,
    val distanceMeters: Double,
)

class SpotViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
    private val analyzer: OnDeviceAnalyzer,
) {
    companion object {
        const val MAX_PHOTOS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SpotUiState())
    val uiState: StateFlow<SpotUiState> = _uiState.asStateFlow()

    fun addPhoto(photo: CapturedPhoto) {
        val current = _uiState.value
        if (current.capturedPhotos.size >= MAX_PHOTOS) return
        _uiState.value = current.copy(
            capturedPhotos = current.capturedPhotos + photo,
        )

        // Trigger on-device AI analysis on the first photo
        if (current.capturedPhotos.isEmpty() && analyzer.isAvailable()) {
            scope.launch {
                _uiState.value = _uiState.value.copy(isAnalyzing = true)
                try {
                    val result = analyzer.analyzePhoto(photo.bytes, photo.depthAnalysis)
                    if (result != null) {
                        if (result.trashTypes.isNotEmpty()) {
                            addObservedItem(
                                ObservedSpotItem(
                                    lat = _uiState.value.currentLat,
                                    lng = _uiState.value.currentLng,
                                    itemType = result.trashTypes.first(),
                                    severity = result.severity,
                                    confidence = result.confidence,
                                )
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            aiAnalysis = result,
                            isAnalyzing = false,
                            // Pre-populate tags from AI if user hasn't selected any
                            selectedTags = if (_uiState.value.selectedTags.isEmpty())
                                result.suggestedTags else _uiState.value.selectedTags,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isAnalyzing = false)
                    }
                } catch (e: Exception) {
                    DiagnosticsLog.error("vision-analyze", "spot photo analysis failed", e)
                    _uiState.value = _uiState.value.copy(isAnalyzing = false)
                }
            }
        }
    }

    fun addScanPhoto(scanPhoto: ScanCapturedPhoto) {
        val photo = CapturedPhoto(
            bytes = scanPhoto.photoBytes ?: byteArrayOf(),
            width = scanPhoto.photoWidth,
            height = scanPhoto.photoHeight,
            depthAnalysis = scanPhoto.depthAnalysis,
            attestation = scanPhoto.attestation,
        )
        addPhoto(photo)
        if (scanPhoto.aiAnalysis != null) {
            _uiState.value = _uiState.value.copy(aiAnalysis = scanPhoto.aiAnalysis)
        }
    }

    /**
     * Register a trash item observed during the environment scan. Deduped
     * against existing items of the same type within ~4m (the scanner path
     * projects along heading, the analyzer fallback tags the user position —
     * both can fire for the same pile).
     */
    fun addObservedItem(item: ObservedSpotItem) {
        val existing = _uiState.value.observedItems
        val duplicate = existing.any { prior ->
            prior.itemType == item.itemType &&
                GeofenceVerification.distanceMeters(prior.lat, prior.lng, item.lat, item.lng) < 4.0
        }
        if (duplicate) return
        _uiState.value = _uiState.value.copy(observedItems = existing + item)
        DiagnosticsLog.info("env-scan", "tagged ${item.itemType} @ ${item.lat}, ${item.lng} (${existing.size + 1} total)")
    }

    fun removePhoto(index: Int) {
        val current = _uiState.value.capturedPhotos.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _uiState.value = _uiState.value.copy(capturedPhotos = current)
        }
    }

    fun setLocation(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(currentLat = lat, currentLng = lng)
    }

    fun setDescription(text: String) {
        _uiState.value = _uiState.value.copy(description = text)
    }

    fun setRadius(meters: Double) {
        _uiState.value = _uiState.value.copy(
            affectedRadiusMeters = meters,
            hasAdjustedAffectedRadius = true,
        )
    }

    fun setSpotLocation(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(spotLat = lat, spotLng = lng)
    }

    fun setAreaMode(mode: AreaMode) {
        _uiState.value = _uiState.value.copy(areaMode = mode)
    }

    fun setRectDimensions(width: Double, height: Double) {
        _uiState.value = _uiState.value.copy(rectWidth = width, rectHeight = height)
    }

    fun setRectRotation(deg: Double) {
        _uiState.value = _uiState.value.copy(rectRotation = deg)
    }

    fun applyAiDescription() {
        val ai = _uiState.value.aiAnalysis ?: return
        val desc = ai.generatedDescription ?: return
        if (_uiState.value.description.isBlank()) {
            _uiState.value = _uiState.value.copy(
                description = desc,
                aiDescriptionApplied = true,
            )
        }
    }

    fun setDuplicateOverrideReason(reason: String) {
        _uiState.value = _uiState.value.copy(duplicateOverrideReason = reason)
    }

    fun dismissDuplicate() {
        _uiState.value = _uiState.value.copy(duplicateMatch = null)
    }

    fun toggleTag(tag: SpotTag) {
        val current = _uiState.value.selectedTags.toMutableList()
        if (tag in current) current.remove(tag) else current.add(tag)
        _uiState.value = _uiState.value.copy(selectedTags = current)
    }

    /**
     * One-tap completion from the camera phase ("Complete Scan"): auto-apply
     * the on-device AI description (tags were pre-filled by [addPhoto]) and
     * submit directly with the GPS position (spotLat/spotLng stay null →
     * [submitReport] falls back to currentLat/currentLng). The manual
     * LOCATION/DESCRIPTION phases remain reachable via "Add details"; a
     * submit failure lands there with the error shown, so nothing is lost.
     */
    fun completeScan() {
        val state = _uiState.value
        if (state.phase != SpotPhase.CAMERA) return
        if (state.capturedPhotos.isEmpty()) return
        DiagnosticsLog.info("spot-submit", "one-tap Complete Scan with ${state.capturedPhotos.size} photo(s)")
        applyAiDescription()
        submitReport()
    }

    fun nextPhase() {
        val current = _uiState.value.phase
        val next = when (current) {
            SpotPhase.CAMERA -> SpotPhase.LOCATION
            SpotPhase.LOCATION -> {
                // Check for nearby duplicates before advancing to DESCRIPTION
                checkForDuplicates()
                SpotPhase.DESCRIPTION
            }
            SpotPhase.DESCRIPTION -> {
                submitReport()
                return
            }
            SpotPhase.SUBMITTING -> return
            SpotPhase.RESULT -> return
        }
        _uiState.value = _uiState.value.copy(phase = next, error = null)
        // Auto-apply AI description when entering DESCRIPTION phase
        if (next == SpotPhase.DESCRIPTION) applyAiDescription()
    }

    private fun checkForDuplicates() {
        scope.launch {
            try {
                val state = _uiState.value
                val lat = state.spotLat ?: state.currentLat
                val lng = state.spotLng ?: state.currentLng

                // Match the server's duplicate radius (~15m). The earlier
                // 50m radius routinely crossed streets and alleys and
                // blocked legitimate separate spots — see server-side
                // /api/spots route for the same constant.
                val nearbySpots = apiClient.getSpots(lat = lat, lng = lng, radiusMeters = 15.0)
                if (nearbySpots.isEmpty()) return@launch

                var bestMatch: DuplicateMatch? = null

                for (spot in nearbySpots) {
                    val distance = GeofenceVerification.distanceMeters(lat, lng, spot.coordinates.lat, spot.coordinates.lng)
                    // Only flag at <8m as visually likely-duplicate (one
                    // dump pile rather than two). Between 8m and the
                    // server's 15m hard limit the server will return a
                    // 409 if it really is a true overlap, but the client
                    // shouldn't volunteer a confidence-flag confirmation.
                    val similarity = when {
                        distance < 4.0 -> 0.9f
                        distance < 8.0 -> 0.75f
                        else -> 0f
                    }

                    if (similarity >= 0.75f) {
                        if (bestMatch == null || similarity > bestMatch.visualSimilarity) {
                            bestMatch = DuplicateMatch(
                                existingSpot = spot,
                                visualSimilarity = similarity,
                                distanceMeters = distance,
                            )
                        }
                    }
                }

                if (bestMatch != null) {
                    _uiState.value = _uiState.value.copy(duplicateMatch = bestMatch)
                }
            } catch (e: Exception) {
                // Don't block the flow if duplicate check fails
                DiagnosticsLog.error("spot-duplicates", "duplicate check failed (non-blocking)", e)
            }
        }
    }

    fun submitReport() {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                phase = SpotPhase.SUBMITTING,
                isSubmitting = true,
                error = null,
            )
            try {
                val state = _uiState.value
                val submitLat = state.spotLat ?: state.currentLat
                val submitLng = state.spotLng ?: state.currentLng

                // Embed GPS EXIF into photos and upload via typed API (includes auth header)
                val uploadedUrls = mutableListOf<String>()
                if (state.capturedPhotos.isNotEmpty()) {
                    val photoBytesWithGps = state.capturedPhotos.map { photo ->
                        embedGpsExif(photo.bytes, submitLat, submitLng)
                    }
                    val urls = apiClient.uploadSpotPhotos(photoBytesWithGps)
                    uploadedUrls.addAll(urls)
                }

                // Collect depth analysis from the first photo that has it
                val depthAnalysis = state.capturedPhotos.firstNotNullOfOrNull { it.depthAnalysis }

                val effectiveRadiusMeters = state.effectiveAffectedRadiusMeters()

                // Build affected area from mode
                val affectedArea: AffectedArea? = when (state.areaMode) {
                    AreaMode.CIRCLE -> AffectedArea.Circle(radiusMeters = effectiveRadiusMeters)
                    AreaMode.RECTANGLE -> AffectedArea.Rectangle(
                        widthMeters = state.rectWidth,
                        heightMeters = state.rectHeight,
                        rotationDeg = state.rectRotation,
                    )
                }

                // Collect capture attestation from the first photo
                val captureAttestation = state.capturedPhotos.firstNotNullOfOrNull { it.attestation }

                // Include on-device AI analysis
                val onDeviceAiResult = state.aiAnalysis

                val request = CreateSpotRequest(
                    type = CommunityReportType.SIGHTING,
                    lat = submitLat,
                    lng = submitLng,
                    description = state.description.ifBlank { null },
                    mediaUrls = uploadedUrls.ifEmpty { null },
                    affectedRadiusMeters = effectiveRadiusMeters,
                    affectedArea = affectedArea,
                    tags = state.selectedTags,
                    reporterName = if (tokenManager.isAdmin()) "GCC" else null,
                    depthAnalysis = depthAnalysis,
                    captureAttestation = captureAttestation,
                    onDeviceAiResult = onDeviceAiResult,
                    duplicateOverrideReason = state.duplicateOverrideReason,
                    observedItems = state.observedItems.ifEmpty { null },
                )
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/spots") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                if (response.status.value == 409) {
                    DiagnosticsLog.error("spot-submit", "server 409: duplicate spot within 15m")
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        phase = SpotPhase.DESCRIPTION,
                        error = "A spot report already exists within 15m of this location",
                    )
                    return@launch
                }
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    throw Exception("Server error ${response.status.value}: $errorBody")
                }
                val spotResponse = response.body<ApiResponse<SpotResponse>>()
                // Server credits the report row separately from the points
                // ledger; copy pointsAwarded onto the report so the success
                // card can render the "+N pts" reward without an extra fetch.
                val rewardedReport = spotResponse.data.report.copy(
                    observerRewardPoints = spotResponse.data.pointsAwarded
                        .takeIf { it > 0 }
                        ?: spotResponse.data.report.observerRewardPoints,
                )
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = SpotPhase.RESULT,
                    submittedReport = rewardedReport,
                    matchingBounties = spotResponse.data.matchingBounties,
                )
            } catch (e: Exception) {
                DiagnosticsLog.error("spot-submit", "report submission failed", e)
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = SpotPhase.DESCRIPTION,
                    error = e.message ?: "Failed to submit report",
                )
            }
        }
    }

    fun acceptBounty(bountyId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            try {
                apiClient.acceptBounty(bountyId)
                // Remove accepted bounty from list
                _uiState.value = _uiState.value.copy(
                    matchingBounties = _uiState.value.matchingBounties.filter { it.id != bountyId },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to accept bounty",
                )
            }
        }
    }

    fun createBountyForSubmittedSpot(amountDollars: Int) {
        scope.launch {
            val report = _uiState.value.submittedReport ?: return@launch
            val amountCents = amountDollars * 100
            if (amountCents < 1000) {
                _uiState.value = _uiState.value.copy(bountyCreateError = "Minimum bounty amount is $10")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isCreatingBounty = true,
                bountyCreateError = null,
            )

            try {
                val bounty = apiClient.createBounty(
                    CreateBountyRequest(
                        title = "Cleanup bounty for report #${report.id}",
                        description = report.description,
                        amount = amountCents,
                        locationLat = report.coordinates.lat,
                        locationLng = report.coordinates.lng,
                        radiusMeters = report.affectedRadiusMeters,
                        mediaUrls = report.mediaUrls,
                        triggerReportId = report.id,
                        visibility = BountyVisibility.PUBLIC,
                        isAnonymous = true,
                    ),
                )

                _uiState.value = _uiState.value.copy(
                    createdBounty = bounty,
                    isCreatingBounty = false,
                    bountyCreateError = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreatingBounty = false,
                    bountyCreateError = e.message ?: "Failed to place bounty",
                )
            }
        }
    }

    fun reset() {
        _uiState.value = SpotUiState()
    }

    private fun SpotUiState.effectiveAffectedRadiusMeters(): Double {
        if (hasAdjustedAffectedRadius) return affectedRadiusMeters

        val estimatedAreaSqFt = capturedPhotos
            .mapNotNull { it.depthAnalysis?.estimatedAreaSqFt }
            .filter { it > 0.0 }
            .maxOrNull()
            ?: return affectedRadiusMeters

        val estimatedAreaSqMeters = estimatedAreaSqFt * SQ_FT_TO_SQ_METERS
        return sqrt(estimatedAreaSqMeters / PI) + SCAN_RADIUS_BUFFER_METERS
    }
}

private const val SQ_FT_TO_SQ_METERS = 0.09290304
private const val SCAN_RADIUS_BUFFER_METERS = 1.0
