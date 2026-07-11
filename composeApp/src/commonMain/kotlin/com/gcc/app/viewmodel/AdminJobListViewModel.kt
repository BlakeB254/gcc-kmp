package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.AdminJobSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminJobListUiState(
    val isLoading: Boolean = true,
    val jobs: List<AdminJobSummary> = emptyList(),
    val error: String? = null,
)

/**
 * Backs the Operations drill-in list. [status] is an `enum_jobs_status` value;
 * null loads every job.
 */
class AdminJobListViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(AdminJobListUiState())
    val uiState: StateFlow<AdminJobListUiState> = _uiState.asStateFlow()

    fun load(status: String?) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                val jobs = apiClient.getAllAdminJobs(status)
                _uiState.update { it.copy(isLoading = false, jobs = jobs, error = null) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to load jobs")
                }
            }
        }
    }

    fun onCleared() {
        scope.cancel()
    }
}
