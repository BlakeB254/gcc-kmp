package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val routes: List<Route> = emptyList(),
    val worker: Worker? = null,
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val spots: List<CommunityReport> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val serviceAreaName: String? = null,
    val error: String? = null,
)

class DashboardViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun loadDashboard() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val routesResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/routes") {
                    parameter("status", "published")
                }
                val routes = routesResponse.body<ApiResponse<List<Route>>>()

                _uiState.value = _uiState.value.copy(
                    routes = routes.data,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load dashboard",
                )
            }
        }
    }

    fun loadLeaderboard() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/leaderboard") {
                    parameter("limit", 10)
                }
                val leaderboard = response.body<ApiResponse<List<LeaderboardEntry>>>()
                _uiState.value = _uiState.value.copy(
                    leaderboard = leaderboard.data,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load leaderboard",
                )
            }
        }
    }

    fun refresh() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val routesResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/routes") {
                    parameter("status", "published")
                }
                val routes = routesResponse.body<ApiResponse<List<Route>>>()
                _uiState.value = _uiState.value.copy(routes = routes.data, isRefreshing = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun loadServiceArea() {
        scope.launch {
            try {
                val area = apiClient.getMyServiceArea()
                _uiState.value = _uiState.value.copy(serviceAreaName = area?.name)
            } catch (_: Exception) { }
        }
    }

    fun loadWorkerProfile() {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/workers/me")
                val worker = response.body<ApiResponse<Worker>>()
                _uiState.value = _uiState.value.copy(worker = worker.data)
            } catch (_: Exception) {
                // Profile loading is non-critical; silently fail
            }
        }
    }

    fun loadSpots() {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/spots") {
                    parameter("limit", 100)
                }
                val spots = response.body<ApiResponse<List<CommunityReport>>>()
                _uiState.value = _uiState.value.copy(spots = spots.data)
            } catch (_: Exception) {
                // Spot loading is non-critical; silently fail
            }
        }
    }
}
