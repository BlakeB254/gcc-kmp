package com.gcc.app.viewmodel

import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.OnDeviceAnalysisResult
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.embedGpsExif
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
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

enum class SpotPhase { CAMERA, LOCATION, DESCRIPTION, SUBMITTING, RESULT }

enum class AreaMode { CIRCLE, RECTANGLE }

data class SpotUiState(
    val capturedPhotos: List<CapturedPhoto> = emptyList(),
    val currentLat: Double = 41.8604,
    val currentLng: Double = -87.7225,
    /** Spot pin position — null means use GPS position (currentLat/currentLng). */
    val spotLat: Double? = null,
    val spotLng: Double? = null,
    val affectedRadiusMeters: Double = 15.0,
    val areaMode: AreaMode = AreaMode.CIRCLE,
    val rectWidth: Double = 20.0,
    val rectHeight: Double = 10.0,
    val rectRotation: Double = 0.0,
    val selectedTags: List<SpotTag> = emptyList(),
    val description: String = "",
    val isSubmitting: Boolean = false,
    val submittedReport: CommunityReport? = null,
    val matchingBounties: List<Bounty> = emptyList(),
    val error: String? = null,
    val phase: SpotPhase = SpotPhase.CAMERA,
    // On-device AI analysis
    val aiAnalysis: OnDeviceAnalysisResult? = null,
    val isAnalyzing: Boolean = false,
    val aiDescriptionApplied: Boolean = false,
    // Duplicate detection
    val duplicateMatch: DuplicateMatch? = null,
    val duplicateOverrideReason: String? = null,
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
                } catch (_: Exception) {
                    _uiState.value = _uiState.value.copy(isAnalyzing = false)
                }
            }
        }
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
        _uiState.value = _uiState.value.copy(affectedRadiusMeters = meters)
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
        if (ai.generatedDescription != null && _uiState.value.description.isBlank()) {
            _uiState.value = _uiState.value.copy(
                description = ai.generatedDescription,
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

                // Query API for spots within 50m
                val nearbySpots = apiClient.getSpots(lat = lat, lng = lng, radiusMeters = 50.0)
                if (nearbySpots.isEmpty()) return@launch

                // If we have photos and the analyzer supports comparison, check visual similarity
                val firstPhoto = state.capturedPhotos.firstOrNull()
                var bestMatch: DuplicateMatch? = null

                for (spot in nearbySpots) {
                    val distance = haversineDistance(lat, lng, spot.coordinates.lat, spot.coordinates.lng)
                    // GPS proximity alone is a strong signal at <50m
                    var similarity = if (distance < 20.0) 0.8f else 0.6f

                    // TODO: download spot photo and compare visually when compareImages is available
                    if (similarity > 0.7f) {
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
            } catch (_: Exception) {
                // Don't block the flow if duplicate check fails
            }
        }
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = kotlin.math.PI / 180.0 * (lat2 - lat1)
        val dLng = kotlin.math.PI / 180.0 * (lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(kotlin.math.PI / 180.0 * lat1) * kotlin.math.cos(kotlin.math.PI / 180.0 * lat2) *
                kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
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

                // Embed GPS EXIF into photos and upload
                val uploadedUrls = mutableListOf<String>()
                if (state.capturedPhotos.isNotEmpty()) {
                    val uploadResponse = apiClient.httpClient.submitFormWithBinaryData(
                        url = "${apiClient.baseUrl}/api/spots/upload",
                        formData = formData {
                            state.capturedPhotos.forEachIndexed { index, photo ->
                                val gpsBytes = embedGpsExif(photo.bytes, submitLat, submitLng)
                                append("photo_$index", gpsBytes, Headers.build {
                                    append(HttpHeaders.ContentType, "image/jpeg")
                                    append(HttpHeaders.ContentDisposition, "filename=\"photo_$index.jpg\"")
                                })
                            }
                        },
                    )
                    if (uploadResponse.status.isSuccess()) {
                        val result = uploadResponse.body<ApiResponse<PhotoUploadResponse>>()
                        uploadedUrls.addAll(result.data.urls)
                    }
                }

                // Collect depth analysis from the first photo that has it
                val depthAnalysis = state.capturedPhotos
                    .firstNotNullOfOrNull { it.depthAnalysis }
                    ?.let { depth ->
                        com.gcc.shared.model.DepthAnalysis(
                            hasDepth = depth.hasDepth,
                            estimatedVolumeCubicFt = depth.estimatedVolumeCubicFt,
                            estimatedAreaSqFt = depth.estimatedAreaSqFt,
                            maxDepthMeters = depth.maxDepthMeters,
                            depthConfidence = depth.depthConfidence,
                            depthMapThumbnailBase64 = depth.depthMapThumbnailBase64,
                        )
                    }

                // Build affected area from mode
                val affectedArea: AffectedArea? = when (state.areaMode) {
                    AreaMode.CIRCLE -> AffectedArea.Circle(radiusMeters = state.affectedRadiusMeters)
                    AreaMode.RECTANGLE -> AffectedArea.Rectangle(
                        widthMeters = state.rectWidth,
                        heightMeters = state.rectHeight,
                        rotationDeg = state.rectRotation,
                    )
                }

                // Collect capture attestation from the first photo
                val captureAttestation = state.capturedPhotos
                    .firstNotNullOfOrNull { it.attestation }
                    ?.let { att ->
                        com.gcc.shared.model.CaptureAttestation(
                            capturedAt = att.capturedAt,
                            capturedLat = att.capturedLat,
                            capturedLng = att.capturedLng,
                            deviceModel = att.deviceModel,
                            osVersion = att.osVersion,
                            sessionId = att.sessionId,
                            captureIndex = att.captureIndex,
                            isLiveCapture = att.isLiveCapture,
                        )
                    }

                // Include on-device AI analysis
                val onDeviceAiResult = state.aiAnalysis?.let { ai ->
                    OnDeviceAiResult(
                        trashTypes = ai.trashTypes,
                        estimatedWeightLbs = ai.estimatedWeightLbs,
                        severity = ai.severity,
                        confidence = ai.confidence,
                        suggestedTags = ai.suggestedTags,
                        generatedDescription = ai.generatedDescription,
                    )
                }

                val request = CreateSpotRequest(
                    type = CommunityReportType.SIGHTING,
                    lat = submitLat,
                    lng = submitLng,
                    description = state.description.ifBlank { null },
                    mediaUrls = uploadedUrls.ifEmpty { null },
                    affectedRadiusMeters = state.affectedRadiusMeters,
                    affectedArea = affectedArea,
                    tags = state.selectedTags,
                    reporterName = if (tokenManager.isAdmin()) "GCC" else null,
                    depthAnalysis = depthAnalysis,
                    captureAttestation = captureAttestation,
                    onDeviceAiResult = onDeviceAiResult,
                    duplicateOverrideReason = state.duplicateOverrideReason,
                )
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/spots") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                if (response.status.value == 409) {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        phase = SpotPhase.DESCRIPTION,
                        error = "A spot report already exists within 50m of this location",
                    )
                    return@launch
                }
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    throw Exception("Server error ${response.status.value}: $errorBody")
                }
                val spotResponse = response.body<ApiResponse<SpotResponse>>()
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = SpotPhase.RESULT,
                    submittedReport = spotResponse.data.report,
                    matchingBounties = spotResponse.data.matchingBounties,
                )
            } catch (e: Exception) {
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
                apiClient.httpClient.post("${apiClient.baseUrl}/api/bounties/$bountyId/accept") {
                    contentType(ContentType.Application.Json)
                }
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

    fun reset() {
        _uiState.value = SpotUiState()
    }
}
