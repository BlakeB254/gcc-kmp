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

data class BountyManagementState(
    val bounties: List<Bounty> = emptyList(),
    val selectedBounty: Bounty? = null,
    val statusFilter: BountyStatus? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class BountyManagementViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(BountyManagementState())
    val state: StateFlow<BountyManagementState> = _state.asStateFlow()

    init {
        loadBounties()
    }

    fun loadBounties() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val current = _state.value
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/bounties") {
                    contentType(ContentType.Application.Json)
                    current.statusFilter?.let { parameter("status", it.name.lowercase()) }
                }
                val result: ApiResponse<List<Bounty>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(bounties = result.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load bounties", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load bounties", isLoading = false) }
            }
        }
    }

    fun setStatusFilter(status: BountyStatus?) {
        _state.update { it.copy(statusFilter = status) }
        loadBounties()
    }

    fun selectBounty(bountyId: Int) {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/bounties/$bountyId") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Bounty> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(selectedBounty = result.data) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load bounty detail") }
            }
        }
    }

    fun clearSelectedBounty() {
        _state.update { it.copy(selectedBounty = null) }
    }

    fun forceExpire(bountyId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/bounties/$bountyId/force-expire") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Bounty> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedBounty = result.data, isLoading = false) }
                    loadBounties()
                } else {
                    _state.update { it.copy(error = result.message ?: "Force expire failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Force expire failed", isLoading = false) }
            }
        }
    }

    fun forceClose(bountyId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/bounties/$bountyId/force-close") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Bounty> = response.body()
                if (result.success) {
                    _state.update { it.copy(selectedBounty = result.data, isLoading = false) }
                    loadBounties()
                } else {
                    _state.update { it.copy(error = result.message ?: "Force close failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Force close failed", isLoading = false) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
