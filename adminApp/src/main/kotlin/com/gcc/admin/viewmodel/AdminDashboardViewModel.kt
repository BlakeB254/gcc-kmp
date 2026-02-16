package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.AdminActivityItem
import com.gcc.shared.model.AdminDailyStats
import com.gcc.shared.model.AdminStatsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminDashboardViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _stats = MutableStateFlow<AdminStatsResponse?>(null)
    val stats: StateFlow<AdminStatsResponse?> = _stats.asStateFlow()

    private val _activity = MutableStateFlow<List<AdminActivityItem>>(emptyList())
    val activity: StateFlow<List<AdminActivityItem>> = _activity.asStateFlow()

    private val _dailyStats = MutableStateFlow<List<AdminDailyStats>>(emptyList())
    val dailyStats: StateFlow<List<AdminDailyStats>> = _dailyStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadDashboard()
    }

    fun loadDashboard() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val statsDeferred = async { api.getAdminStats() }
                val activityDeferred = async { api.getAdminActivity() }
                val dailyDeferred = async { api.getAdminDailyStats() }

                _stats.value = statsDeferred.await()
                _activity.value = activityDeferred.await()
                _dailyStats.value = dailyDeferred.await()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load dashboard"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
