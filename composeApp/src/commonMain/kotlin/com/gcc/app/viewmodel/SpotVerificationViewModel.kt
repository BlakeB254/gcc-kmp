package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.SpotVerificationResult
import com.gcc.shared.model.VerifySpotRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VerificationPhase {
    CAMERA,
    DECISION,
    SUBMITTING,
    COMPLETE,
}

data class SpotVerificationUiState(
    val reportId: Int = 0,
    val phase: VerificationPhase = VerificationPhase.CAMERA,
    val photoUrl: String? = null,
    val pointsAwarded: Int = 0,
    val spotResolved: Boolean = false,
    val result: SpotVerificationResult? = null,
    val error: String? = null,
    val isSubmitting: Boolean = false,
    // GPS at verification time
    val verifierLat: Double? = null,
    val verifierLng: Double? = null,
    // Spot info for display
    val expectedVerificationCount: Int = 0,
)

class SpotVerificationViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(SpotVerificationUiState())
    val uiState: StateFlow<SpotVerificationUiState> = _uiState.asStateFlow()

    fun initForReport(reportId: Int, expectedVerificationCount: Int = 0) {
        _uiState.value = SpotVerificationUiState(
            reportId = reportId,
            expectedVerificationCount = expectedVerificationCount,
        )
    }

    fun setPhoto(url: String) {
        _uiState.update { it.copy(photoUrl = url, phase = VerificationPhase.DECISION) }
    }

    fun setVerifierLocation(lat: Double, lng: Double) {
        _uiState.update { it.copy(verifierLat = lat, verifierLng = lng) }
    }

    fun submitVerification(result: SpotVerificationResult) {
        val state = _uiState.value
        val photoUrl = state.photoUrl ?: return

        _uiState.update { it.copy(
            phase = VerificationPhase.SUBMITTING,
            isSubmitting = true,
            result = result,
            error = null,
        ) }

        scope.launch {
            try {
                val response = apiClient.verifySpot(
                    state.reportId,
                    VerifySpotRequest(
                        result = result,
                        photoUrl = photoUrl,
                        verifierLat = state.verifierLat,
                        verifierLng = state.verifierLng,
                        expectedVerificationCount = state.expectedVerificationCount,
                    ),
                )
                _uiState.update { it.copy(
                    phase = VerificationPhase.COMPLETE,
                    pointsAwarded = response.pointsAwarded,
                    spotResolved = response.spotResolved,
                    isSubmitting = false,
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    phase = VerificationPhase.DECISION,
                    error = e.message ?: "Verification failed",
                    isSubmitting = false,
                ) }
            }
        }
    }
}
