package com.gcc.app.viewmodel

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
import kotlinx.coroutines.launch

data class BountyListUiState(
    val bounties: List<Bounty> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedBounty: Bounty? = null,
    val isClaimingBounty: Boolean = false,
    val claimError: String? = null,
    val isCreating: Boolean = false,
    val createError: String? = null,
)

class BountyViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(BountyListUiState())
    val uiState: StateFlow<BountyListUiState> = _uiState.asStateFlow()

    fun loadBounties(lat: Double? = null, lng: Double? = null, communityArea: String? = null) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/bounties") {
                    parameter("limit", 50)
                    parameter("status", "open")
                    lat?.let { parameter("lat", it) }
                    lng?.let { parameter("lng", it) }
                    communityArea?.let { parameter("communityArea", it) }
                }
                val bounties = response.body<ApiResponse<List<Bounty>>>()
                _uiState.value = _uiState.value.copy(
                    bounties = bounties.data,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load bounties",
                )
            }
        }
    }

    fun refresh() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/bounties") {
                    parameter("limit", 50)
                    parameter("status", "open")
                }
                val bounties = response.body<ApiResponse<List<Bounty>>>()
                _uiState.value = _uiState.value.copy(bounties = bounties.data, isRefreshing = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun loadBountyById(bountyId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, selectedBounty = null)
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/bounties/$bountyId")
                val bounty = response.body<ApiResponse<Bounty>>()
                _uiState.value = _uiState.value.copy(
                    selectedBounty = bounty.data,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load bounty",
                )
            }
        }
    }

    fun claimBounty(bountyId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isClaimingBounty = true, claimError = null)
            try {
                val bounty = apiClient.claimBounty(bountyId)
                _uiState.value = _uiState.value.copy(
                    selectedBounty = bounty,
                    isClaimingBounty = false,
                )
                // Refresh the bounties list so claimed bounty disappears from open list
                refresh()
            } catch (e: Exception) {
                val message = e.message ?: "Failed to claim bounty"
                val claimError = if (message.contains("409") || message.contains("Conflict")) {
                    "Someone else got there first!"
                } else if (message.contains("403") || message.contains("Forbidden")) {
                    "You're not authorized to claim this bounty"
                } else {
                    message
                }
                _uiState.value = _uiState.value.copy(
                    isClaimingBounty = false,
                    claimError = claimError,
                )
            }
        }
    }

    fun createBounty(request: CreateBountyRequest) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, createError = null)
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/bounties") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val bounty = response.body<ApiResponse<Bounty>>()
                _uiState.value = _uiState.value.copy(
                    selectedBounty = bounty.data,
                    isCreating = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    createError = e.message ?: "Failed to create bounty",
                )
            }
        }
    }
}
