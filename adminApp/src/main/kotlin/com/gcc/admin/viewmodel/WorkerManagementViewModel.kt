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

data class WorkerManagementState(
    val workers: List<Worker> = emptyList(),
    val selectedWorker: Worker? = null,
    val searchQuery: String = "",
    val statusFilter: VerificationStatus? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalCount: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
)

class WorkerManagementViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(WorkerManagementState())
    val state: StateFlow<WorkerManagementState> = _state.asStateFlow()

    init {
        loadWorkers()
    }

    fun loadWorkers() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val current = _state.value
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/workers") {
                    contentType(ContentType.Application.Json)
                    if (current.searchQuery.isNotBlank()) {
                        parameter("search", current.searchQuery)
                    }
                    current.statusFilter?.let { parameter("status", it.name.lowercase()) }
                    parameter("limit", current.limit)
                    parameter("offset", current.offset)
                }
                val result: ApiResponse<List<Worker>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(workers = result.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load workers", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load workers", isLoading = false) }
            }
        }
    }

    fun searchWorkers(query: String) {
        _state.update { it.copy(searchQuery = query, offset = 0) }
        loadWorkers()
    }

    fun setStatusFilter(status: VerificationStatus?) {
        _state.update { it.copy(statusFilter = status, offset = 0) }
        loadWorkers()
    }

    fun selectWorker(workerId: Int) {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/workers/$workerId") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Worker> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(selectedWorker = result.data) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load worker detail") }
            }
        }
    }

    fun clearSelectedWorker() {
        _state.update { it.copy(selectedWorker = null) }
    }

    fun verifyWorker(workerId: Int, request: VerifyWorkerRequest) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/workers/$workerId/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val result: ApiResponse<Worker> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedWorker = result.data, isLoading = false) }
                    loadWorkers() // Refresh the list
                } else {
                    _state.update { it.copy(error = result.message ?: "Verification failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Verification failed", isLoading = false) }
            }
        }
    }

    fun updateRole(workerId: Int, role: UserRole) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/workers/$workerId/role") {
                    contentType(ContentType.Application.Json)
                    setBody(UpdateWorkerRoleRequest(role = role))
                }
                val result: ApiResponse<Worker> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedWorker = result.data, isLoading = false) }
                    loadWorkers()
                } else {
                    _state.update { it.copy(error = result.message ?: "Role update failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Role update failed", isLoading = false) }
            }
        }
    }

    fun bulkVerify(workerIds: List<Int>) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/admin/workers/bulk-verify") {
                    contentType(ContentType.Application.Json)
                    setBody(BulkActionRequest(workerIds = workerIds, action = BulkAction.VERIFY))
                }
                val result: ApiResponse<Map<String, Int>> = response.body()
                if (result.success) {
                    _state.update { it.copy(isLoading = false) }
                    loadWorkers()
                } else {
                    _state.update { it.copy(error = result.message ?: "Bulk verify failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Bulk verify failed", isLoading = false) }
            }
        }
    }

    fun deleteWorker(workerId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.delete("${apiClient.baseUrl}/api/admin/workers/$workerId") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Map<String, Boolean>> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedWorker = null, isLoading = false) }
                    loadWorkers()
                } else {
                    _state.update { it.copy(error = result.message ?: "Delete failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Delete failed", isLoading = false) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
