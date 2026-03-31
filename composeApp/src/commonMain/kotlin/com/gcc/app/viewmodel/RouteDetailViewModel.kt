package com.gcc.app.viewmodel

import com.gcc.app.platform.triggerHapticFeedback
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RouteDetailUiState(
    val route: Route? = null,
    val isClaiming: Boolean = false,
    val claimError: String? = null,
    val claimedJobId: Int? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val areaCleanlinessScore: Int = 0,
)

class RouteDetailViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(RouteDetailUiState())
    val uiState: StateFlow<RouteDetailUiState> = _uiState.asStateFlow()

    fun loadRoute(routeId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val route = apiClient.getRoute(routeId)
                _uiState.value = _uiState.value.copy(
                    route = route,
                    isLoading = false,
                )
                // Fetch area cleanliness score in background
                try {
                    val overview = apiClient.getCityCleanlinessOverview()
                    _uiState.value = _uiState.value.copy(
                        areaCleanlinessScore = overview.cityScore
                    )
                } catch (_: Exception) { /* non-critical */ }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load route",
                )
            }
        }
    }

    fun claimRoute(routeId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isClaiming = true, claimError = null)
            try {
                val jobs = apiClient.getAvailableJobs(routeId)
                val job = jobs.firstOrNull()
                    ?: throw IllegalStateException("No available jobs for this route")

                val claimedJob = apiClient.claimJob(job.id)

                triggerHapticFeedback()
                _uiState.value = _uiState.value.copy(
                    isClaiming = false,
                    claimedJobId = claimedJob.id,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isClaiming = false,
                    claimError = e.message ?: "Failed to claim route",
                )
            }
        }
    }

    fun clearClaimError() {
        _uiState.value = _uiState.value.copy(claimError = null)
    }
}
