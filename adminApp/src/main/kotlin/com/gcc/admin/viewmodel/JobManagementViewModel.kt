package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.ForceJobStatusRequest
import com.gcc.shared.model.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class JobManagementViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val _selectedJob = MutableStateFlow<Job?>(null)
    val selectedJob: StateFlow<Job?> = _selectedJob.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadJobs()
    }

    fun loadJobs() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _jobs.value = api.getAdminJobs(status = _statusFilter.value)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load jobs"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onStatusFilterChange(status: String?) {
        _statusFilter.value = status
        loadJobs()
    }

    fun selectJob(job: Job) {
        _selectedJob.value = job
    }

    fun clearSelection() {
        _selectedJob.value = null
    }

    fun forceStatus(jobId: Int, request: ForceJobStatusRequest) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.forceJobStatus(jobId, request)
                loadJobs()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update job status"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
