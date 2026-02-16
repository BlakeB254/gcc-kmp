package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.UpdateWorkerRoleRequest
import com.gcc.shared.model.VerifyWorkerRequest
import com.gcc.shared.model.Worker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkerManagementViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _workers = MutableStateFlow<List<Worker>>(emptyList())
    val workers: StateFlow<List<Worker>> = _workers.asStateFlow()

    private val _selectedWorker = MutableStateFlow<Worker?>(null)
    val selectedWorker: StateFlow<Worker?> = _selectedWorker.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadWorkers()
    }

    fun loadWorkers() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val search = _searchQuery.value.ifBlank { null }
                _workers.value = api.getAdminWorkers(search = search)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load workers"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onSearchChange(query: String) {
        _searchQuery.value = query
        loadWorkers()
    }

    fun selectWorker(worker: Worker) {
        _selectedWorker.value = worker
    }

    fun clearSelection() {
        _selectedWorker.value = null
    }

    fun verifyWorker(workerId: Int, request: VerifyWorkerRequest) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.verifyWorker(workerId, request)
                loadWorkers()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to verify worker"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateRole(workerId: Int, request: UpdateWorkerRoleRequest) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.updateWorkerRole(workerId, request)
                loadWorkers()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update worker role"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
