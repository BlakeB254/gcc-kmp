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

data class DisputeState(
    val disputes: List<Dispute> = emptyList(),
    val selectedDispute: Dispute? = null,
    val statusFilter: DisputeStatus? = null,
    val counts: Map<DisputeStatus, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class DisputeViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(DisputeState())
    val state: StateFlow<DisputeState> = _state.asStateFlow()

    init {
        loadDisputes()
    }

    fun loadDisputes() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val current = _state.value
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/disputes") {
                    contentType(ContentType.Application.Json)
                    current.statusFilter?.let { parameter("status", it.name.lowercase()) }
                }
                val result: ApiResponse<List<Dispute>> = response.body()
                if (result.success && result.data != null) {
                    val disputes = result.data
                    val counts = disputes.groupBy { it.status }.mapValues { it.value.size }
                    _state.update { it.copy(disputes = disputes, counts = counts, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load disputes", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load disputes", isLoading = false) }
            }
        }
    }

    fun setStatusFilter(status: DisputeStatus?) {
        _state.update { it.copy(statusFilter = status) }
        loadDisputes()
    }

    fun selectDispute(disputeId: Int) {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/disputes/$disputeId") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Dispute> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(selectedDispute = result.data) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load dispute detail") }
            }
        }
    }

    fun clearSelectedDispute() {
        _state.update { it.copy(selectedDispute = null) }
    }

    fun startReview(disputeId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/disputes/$disputeId/review") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Dispute> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedDispute = result.data, isLoading = false) }
                    loadDisputes()
                } else {
                    _state.update { it.copy(error = result.message ?: "Start review failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Start review failed", isLoading = false) }
            }
        }
    }

    fun resolveDispute(disputeId: Int, request: ResolveDisputeRequest) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/disputes/$disputeId/resolve") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val result: ApiResponse<Dispute> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedDispute = result.data, isLoading = false) }
                    loadDisputes()
                } else {
                    _state.update { it.copy(error = result.message ?: "Resolve failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Resolve failed", isLoading = false) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
