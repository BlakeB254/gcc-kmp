package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
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
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/routes/$routeId")
                val route = response.body<ApiResponse<Route>>()
                _uiState.value = _uiState.value.copy(
                    route = route.data,
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
                // First, find an available job for this route
                val jobsResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/jobs") {
                    parameter("route_id", routeId)
                    parameter("status", "available")
                    parameter("limit", 1)
                }
                val jobs = jobsResponse.body<ApiResponse<List<Job>>>()
                val job = jobs.data.firstOrNull()
                    ?: throw IllegalStateException("No available jobs for this route")

                // Claim the job
                val claimResponse = apiClient.httpClient.post("${apiClient.baseUrl}/api/jobs/${job.id}/claim")
                val claimedJob = claimResponse.body<ApiResponse<Job>>()

                _uiState.value = _uiState.value.copy(
                    isClaiming = false,
                    claimedJobId = claimedJob.data.id,
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
