package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.BountyAnalyticsStats
import com.gcc.shared.model.BountyLocationStats
import com.gcc.shared.model.BountyPosterStats
import com.gcc.shared.model.BountyTimeseriesPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BountyAnalyticsViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _stats = MutableStateFlow(BountyAnalyticsStats())
    val stats: StateFlow<BountyAnalyticsStats> = _stats.asStateFlow()

    private val _timeseries = MutableStateFlow<List<BountyTimeseriesPoint>>(emptyList())
    val timeseries: StateFlow<List<BountyTimeseriesPoint>> = _timeseries.asStateFlow()

    private val _locationStats = MutableStateFlow<List<BountyLocationStats>>(emptyList())
    val locationStats: StateFlow<List<BountyLocationStats>> = _locationStats.asStateFlow()

    private val _leaderboard = MutableStateFlow<List<BountyPosterStats>>(emptyList())
    val leaderboard: StateFlow<List<BountyPosterStats>> = _leaderboard.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _timeRange = MutableStateFlow(30)
    val timeRange: StateFlow<Int> = _timeRange.asStateFlow()

    init { loadAll() }

    fun setTimeRange(days: Int) {
        _timeRange.value = days
        loadTimeseries()
    }

    fun loadAll() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _stats.value = api.getBountyAnalyticsStats()
                _timeseries.value = api.getBountyTimeseries(_timeRange.value)
                _locationStats.value = api.getBountyByLocation()
                _leaderboard.value = api.getBountyLeaderboard()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load bounty analytics"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadTimeseries() {
        scope.launch {
            try {
                _timeseries.value = api.getBountyTimeseries(_timeRange.value)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load timeseries"
            }
        }
    }
}
