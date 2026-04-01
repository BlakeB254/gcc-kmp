package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val serviceArea: ServiceArea? = null,
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
                val routes = apiClient.getRoutes()
                _uiState.value = _uiState.value.copy(
                    routes = routes,
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
                val leaderboard = apiClient.getLeaderboard(limit = 10)
                _uiState.value = _uiState.value.copy(
                    leaderboard = leaderboard,
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
                val routes = apiClient.getRoutes()
                _uiState.value = _uiState.value.copy(routes = routes, isRefreshing = false)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun loadServiceArea() {
        scope.launch {
            try {
                val area = apiClient.getMyServiceArea()
                _uiState.value = _uiState.value.copy(
                    serviceArea = area,
                    serviceAreaName = area?.name,
                )
            } catch (_: Exception) { }
        }
    }

    fun loadWorkerProfile() {
        scope.launch {
            try {
                val worker = apiClient.getWorkerProfile()
                _uiState.value = _uiState.value.copy(worker = worker)
            } catch (_: Exception) {
                // Profile loading is non-critical; silently fail
            }
        }
    }

    fun loadSpots() {
        scope.launch {
            try {
                val spots = apiClient.getSpots(limit = 100)
                _uiState.value = _uiState.value.copy(spots = spots)
            } catch (_: Exception) {
                // Spot loading is non-critical; silently fail
            }
        }
    }

    /** Load all dashboard data concurrently. */
    fun loadAll() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                coroutineScope {
                    val routesDeferred = async { apiClient.getRoutes() }
                    val leaderboardDeferred = async { apiClient.getLeaderboard(limit = 10) }
                    val workerDeferred = async {
                        try { apiClient.getWorkerProfile() } catch (_: Exception) { null }
                    }
                    val serviceAreaDeferred = async {
                        try { apiClient.getMyServiceArea() } catch (_: Exception) { null }
                    }
                    val spotsDeferred = async {
                        try { apiClient.getSpots(limit = 100) } catch (_: Exception) { null }
                    }

                    val routes = routesDeferred.await()
                    val leaderboard = leaderboardDeferred.await()
                    val worker = workerDeferred.await()
                    val serviceArea = serviceAreaDeferred.await()
                    val spots = spotsDeferred.await()

                    _uiState.value = _uiState.value.copy(
                        routes = routes,
                        leaderboard = leaderboard,
                        worker = worker,
                        serviceArea = serviceArea,
                        serviceAreaName = serviceArea?.name,
                        spots = spots ?: emptyList(),
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load dashboard",
                )
            }
        }
    }
}
