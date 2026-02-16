package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.AdminDailyStats
import com.gcc.shared.model.AdminStatsResponse
import com.gcc.shared.model.RevenueByArea
import com.gcc.shared.model.SponsorMetrics
import com.gcc.shared.model.WorkerMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AnalyticsViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _stats = MutableStateFlow<AdminStatsResponse?>(null)
    val stats: StateFlow<AdminStatsResponse?> = _stats.asStateFlow()

    private val _dailyStats = MutableStateFlow<List<AdminDailyStats>>(emptyList())
    val dailyStats: StateFlow<List<AdminDailyStats>> = _dailyStats.asStateFlow()

    private val _revenueByArea = MutableStateFlow<List<RevenueByArea>>(emptyList())
    val revenueByArea: StateFlow<List<RevenueByArea>> = _revenueByArea.asStateFlow()

    private val _workerMetrics = MutableStateFlow<List<WorkerMetrics>>(emptyList())
    val workerMetrics: StateFlow<List<WorkerMetrics>> = _workerMetrics.asStateFlow()

    private val _sponsorMetrics = MutableStateFlow<List<SponsorMetrics>>(emptyList())
    val sponsorMetrics: StateFlow<List<SponsorMetrics>> = _sponsorMetrics.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadAnalytics()
    }

    fun loadAnalytics() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val statsDeferred = async { api.getAdminStats() }
                val dailyDeferred = async { api.getAdminDailyStats() }
                val revenueDeferred = async { api.getRevenueByArea() }
                val workerDeferred = async { api.getWorkerMetrics() }
                val sponsorDeferred = async { api.getSponsorMetrics() }

                _stats.value = statsDeferred.await()
                _dailyStats.value = dailyDeferred.await()
                _revenueByArea.value = revenueDeferred.await()
                _workerMetrics.value = workerDeferred.await()
                _sponsorMetrics.value = sponsorDeferred.await()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load analytics"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
