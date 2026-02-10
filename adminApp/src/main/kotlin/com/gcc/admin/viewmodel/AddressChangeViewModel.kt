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
import kotlinx.serialization.Serializable

@Serializable
data class PendingAddressChange(
    val workerId: Int,
    val workerName: String,
    val currentAddress: WorkerAddress,
    val newAddress: WorkerAddress,
    val status: PendingAddressStatus = PendingAddressStatus.PENDING,
    val reason: String? = null,
)

data class AddressChangeState(
    val pendingChanges: List<PendingAddressChange> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AddressChangeViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AddressChangeState())
    val state: StateFlow<AddressChangeState> = _state.asStateFlow()

    init {
        loadPendingChanges()
    }

    fun loadPendingChanges() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/address-changes") {
                    contentType(ContentType.Application.Json)
                    parameter("status", "pending")
                }
                val result: ApiResponse<List<PendingAddressChange>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(pendingChanges = result.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load address changes", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load address changes", isLoading = false) }
            }
        }
    }

    fun approveChange(workerId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/address-changes/$workerId/approve") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Map<String, Boolean>> = response.body()
                if (result.success) {
                    loadPendingChanges()
                } else {
                    _state.update { it.copy(error = result.message ?: "Approve failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Approve failed", isLoading = false) }
            }
        }
    }

    fun rejectChange(workerId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/address-changes/$workerId/reject") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Map<String, Boolean>> = response.body()
                if (result.success) {
                    loadPendingChanges()
                } else {
                    _state.update { it.copy(error = result.message ?: "Reject failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Reject failed", isLoading = false) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
