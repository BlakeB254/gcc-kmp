package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.model.LeaderboardEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LeaderboardPeriod(val apiValue: String, val label: String) {
    WEEKLY("weekly", "Weekly"),
    MONTHLY("monthly", "Monthly"),
    ALL_TIME("all_time", "All Time"),
}

data class LeaderboardUiState(
    val entries: List<LeaderboardEntry> = emptyList(),
    val currentUserRank: Int? = null,
    val currentUserId: Int? = null,
    val selectedPeriod: LeaderboardPeriod = LeaderboardPeriod.ALL_TIME,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class LeaderboardViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()

    fun loadLeaderboard() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val entries = apiClient.getLeaderboard(
                    period = _uiState.value.selectedPeriod.apiValue,
                    limit = 50,
                )
                val userId = tokenManager.getWorkerId()
                val userRank = entries.find { it.workerId == userId }?.rank
                _uiState.value = _uiState.value.copy(
                    entries = entries,
                    currentUserId = userId,
                    currentUserRank = userRank,
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

    fun selectPeriod(period: LeaderboardPeriod) {
        _uiState.value = _uiState.value.copy(selectedPeriod = period)
        loadLeaderboard()
    }
}
