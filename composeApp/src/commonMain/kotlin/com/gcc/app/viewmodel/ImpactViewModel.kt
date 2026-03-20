package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.CityCleanlinessOverview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImpactUiState(
    val overview: CityCleanlinessOverview? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class ImpactViewModel(private val apiClient: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ImpactUiState())
    val uiState: StateFlow<ImpactUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun loadImpact() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val overview = apiClient.getCityCleanlinessOverview()
                _uiState.value = _uiState.value.copy(overview = overview, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
