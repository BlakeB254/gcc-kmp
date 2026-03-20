package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.PointsSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PointsDashboardUiState(
    val pointsSummary: PointsSummary? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PointsDashboardViewModel(private val apiClient: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(PointsDashboardUiState())
    val uiState: StateFlow<PointsDashboardUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun loadPoints() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val summary = apiClient.getMyPoints()
                _uiState.value = _uiState.value.copy(pointsSummary = summary, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
