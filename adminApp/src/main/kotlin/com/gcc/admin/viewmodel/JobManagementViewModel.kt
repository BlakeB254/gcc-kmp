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

data class JobManagementState(
    val jobs: List<Job> = emptyList(),
    val selectedJob: Job? = null,
    val statusFilter: JobStatus? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val offset: Int = 0,
    val limit: Int = 50,
)

class JobManagementViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(JobManagementState())
    val state: StateFlow<JobManagementState> = _state.asStateFlow()

    init {
        loadJobs()
    }

    fun loadJobs() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val current = _state.value
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/jobs") {
                    contentType(ContentType.Application.Json)
                    current.statusFilter?.let { parameter("status", it.name.lowercase()) }
                    parameter("limit", current.limit)
                    parameter("offset", current.offset)
                }
                val result: ApiResponse<List<Job>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(jobs = result.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load jobs", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load jobs", isLoading = false) }
            }
        }
    }

    fun setStatusFilter(status: JobStatus?) {
        _state.update { it.copy(statusFilter = status, offset = 0) }
        loadJobs()
    }

    fun selectJob(jobId: Int) {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/jobs/$jobId") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Job> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(selectedJob = result.data) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load job detail") }
            }
        }
    }

    fun clearSelectedJob() {
        _state.update { it.copy(selectedJob = null) }
    }

    fun forceJobStatus(jobId: Int, status: JobStatus, reason: String) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/jobs/$jobId/force-status") {
                    contentType(ContentType.Application.Json)
                    setBody(ForceJobStatusRequest(status = status, reason = reason))
                }
                val result: ApiResponse<Job> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedJob = result.data, isLoading = false) }
                    loadJobs()
                } else {
                    _state.update { it.copy(error = result.message ?: "Force status failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Force status failed", isLoading = false) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
