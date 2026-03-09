package com.gcc.app.viewmodel

import com.gcc.app.platform.CapturedPhoto
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

data class SpotUiState(
    val capturedPhotos: List<CapturedPhoto> = emptyList(),
    val currentLat: Double = 41.8604,
    val currentLng: Double = -87.7225,
    /** Spot pin position — null means use GPS position (currentLat/currentLng). */
    val spotLat: Double? = null,
    val spotLng: Double? = null,
    val affectedRadiusMeters: Double = 15.0,
    val description: String = "",
    val isSubmitting: Boolean = false,
    val submittedReport: CommunityReport? = null,
    val matchingBounties: List<Bounty> = emptyList(),
    val error: String? = null,
    val phase: SpotPhase = SpotPhase.CAMERA,
)

class SpotViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SpotUiState())
    val uiState: StateFlow<SpotUiState> = _uiState.asStateFlow()

    fun addPhoto(photo: CapturedPhoto) {
        _uiState.value = _uiState.value.copy(
            capturedPhotos = _uiState.value.capturedPhotos + photo,
        )
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

    fun nextPhase() {
        val current = _uiState.value.phase
        val next = when (current) {
            SpotPhase.CAMERA -> SpotPhase.LOCATION
            SpotPhase.LOCATION -> SpotPhase.DESCRIPTION
            SpotPhase.DESCRIPTION -> {
                submitReport()
                return
            }
            SpotPhase.SUBMITTING -> return
            SpotPhase.RESULT -> return
        }
        _uiState.value = _uiState.value.copy(phase = next, error = null)
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

                val request = CreateSpotRequest(
                    type = CommunityReportType.SIGHTING,
                    lat = submitLat,
                    lng = submitLng,
                    description = state.description.ifBlank { null },
                    mediaUrls = uploadedUrls.ifEmpty { null },
                    affectedRadiusMeters = state.affectedRadiusMeters,
                    reporterName = if (tokenManager.isAdmin()) "GCC" else null,
                )
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/spots") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
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
