package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.AdminJobSummary
import com.gcc.shared.model.Worker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminJobListUiState(
    val isLoading: Boolean = true,
    val jobs: List<AdminJobSummary> = emptyList(),
    val error: String? = null,
    // Reassign-worker flow (fix jobs credited to the wrong worker)
    val reassignJob: AdminJobSummary? = null,
    val reassignSearch: String = "",
    val reassignCandidates: List<Worker> = emptyList(),
    val reassignSearching: Boolean = false,
    val reassignInFlight: Boolean = false,
    val reassignError: String? = null,
    val reassignSuccess: String? = null,
    // Release flow (return a stalled / denied job to the pool)
    val releaseJob: AdminJobSummary? = null,
    val releaseInFlight: Boolean = false,
    val releaseError: String? = null,
    val releaseSuccess: String? = null,
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

    private var lastStatus: String? = null
    private var searchJob: Job? = null

    fun load(status: String?) {
        lastStatus = status
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

    // ── Reassign worker (moves the wallet credit with the job) ─────────

    fun openReassign(job: AdminJobSummary) {
        _uiState.update {
            it.copy(
                reassignJob = job,
                reassignSearch = "",
                reassignCandidates = emptyList(),
                reassignError = null,
                reassignSuccess = null,
            )
        }
        searchWorkers("")
    }

    fun dismissReassign() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                reassignJob = null,
                reassignCandidates = emptyList(),
                reassignError = null,
            )
        }
    }

    fun clearReassignSuccess() {
        _uiState.update { it.copy(reassignSuccess = null) }
    }

    fun updateReassignSearch(query: String) {
        _uiState.update { it.copy(reassignSearch = query) }
        searchWorkers(query)
    }

    private fun searchWorkers(query: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            if (query.isNotEmpty()) delay(250) // debounce typing
            _uiState.update { it.copy(reassignSearching = true) }
            try {
                val workers = apiClient.getAdminWorkers(
                    search = query.ifBlank { null },
                    limit = 20,
                )
                _uiState.update { it.copy(reassignCandidates = workers, reassignSearching = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        reassignSearching = false,
                        reassignError = error.message ?: "Failed to search workers",
                    )
                }
            }
        }
    }

    fun reassign(toWorker: Worker) {
        val job = _uiState.value.reassignJob ?: return
        _uiState.update { it.copy(reassignInFlight = true, reassignError = null) }
        scope.launch {
            try {
                val result = apiClient.adminReassignJob(job.jobId, toWorker.id)
                val moved = if (result.creditMoved) {
                    " ${com.gcc.shared.util.GccFormatters.formatCents(result.movedCents)} moved to their wallet."
                } else ""
                _uiState.update {
                    it.copy(
                        reassignInFlight = false,
                        reassignJob = null,
                        reassignSuccess = "Job #${job.jobId} reassigned to ${toWorker.name}.$moved",
                    )
                }
                load(lastStatus)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        reassignInFlight = false,
                        reassignError = error.message ?: "Failed to reassign job",
                    )
                }
            }
        }
    }

    // ── Release to the pool (stalled / denied / abandoned work) ────────

    fun openRelease(job: AdminJobSummary) {
        _uiState.update { it.copy(releaseJob = job, releaseError = null, releaseSuccess = null) }
    }

    fun dismissRelease() {
        _uiState.update { it.copy(releaseJob = null, releaseError = null) }
    }

    fun clearReleaseSuccess() {
        _uiState.update { it.copy(releaseSuccess = null) }
    }

    fun release() {
        val job = _uiState.value.releaseJob ?: return
        _uiState.update { it.copy(releaseInFlight = true, releaseError = null) }
        scope.launch {
            try {
                apiClient.adminReleaseJob(job.jobId)
                _uiState.update {
                    it.copy(
                        releaseInFlight = false,
                        releaseJob = null,
                        releaseSuccess = "Job #${job.jobId} is back in the pool.",
                    )
                }
                load(lastStatus)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        releaseInFlight = false,
                        releaseError = error.message ?: "Failed to release job",
                    )
                }
            }
        }
    }

    fun onCleared() {
        scope.cancel()
    }
}
