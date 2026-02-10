package com.gcc.admin.viewmodel

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminDashboardState(
    val stats: AdminStatsResponse? = null,
    val recentActivity: List<AdminActivityItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AdminDashboardViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AdminDashboardState())
    val state: StateFlow<AdminDashboardState> = _state.asStateFlow()

    init {
        loadDashboard()
    }

    fun loadDashboard() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                // Load stats
                val statsResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/analytics/stats") {
                    contentType(ContentType.Application.Json)
                }
                val statsResult: ApiResponse<AdminStatsResponse> = statsResponse.body()

                if (statsResult.success) {
                    _state.update { it.copy(stats = statsResult.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = statsResult.message ?: "Failed to load stats", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load dashboard", isLoading = false) }
            }
        }

        // Load recent activity in parallel
        scope.launch {
            try {
                val activityResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/analytics/activity") {
                    contentType(ContentType.Application.Json)
                    parameter("limit", 20)
                }
                val activityResult: ApiResponse<List<AdminActivityItem>> = activityResponse.body()
                if (activityResult.success && activityResult.data != null) {
                    _state.update { it.copy(recentActivity = activityResult.data) }
                }
            } catch (_: Exception) {
                // Activity loading is non-critical
            }
        }
    }

    fun refresh() {
        loadDashboard()
    }
}
