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

data class AnalyticsState(
    val stats: AdminStatsResponse? = null,
    val revenueByArea: List<RevenueByArea> = emptyList(),
    val workerMetrics: List<WorkerMetrics> = emptyList(),
    val sponsorMetrics: List<SponsorMetrics> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AnalyticsViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    init {
        loadAnalytics()
    }

    fun loadAnalytics() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val statsResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/analytics/stats") {
                    contentType(ContentType.Application.Json)
                }
                val statsResult: ApiResponse<AdminStatsResponse> = statsResponse.body()
                if (statsResult.success) {
                    _state.update { it.copy(stats = statsResult.data) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load stats") }
            }
        }

        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/analytics/revenue-by-area") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<List<RevenueByArea>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(revenueByArea = result.data) }
                }
            } catch (_: Exception) { }
        }

        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/analytics/worker-metrics") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<List<WorkerMetrics>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(workerMetrics = result.data) }
                }
            } catch (_: Exception) { }
        }

        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/analytics/sponsor-metrics") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<List<SponsorMetrics>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(sponsorMetrics = result.data, isLoading = false) }
                }
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun refresh() {
        loadAnalytics()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
