package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
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
                val bounties = apiClient.getBounties(status = "open", limit = 50)
                _uiState.value = _uiState.value.copy(
                    bounties = bounties,
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
                val bounties = apiClient.getBounties(status = "open", limit = 50)
                _uiState.value = _uiState.value.copy(bounties = bounties, isRefreshing = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun loadBountyById(bountyId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, selectedBounty = null)
            try {
                val bounty = apiClient.getBounty(bountyId)
                _uiState.value = _uiState.value.copy(
                    selectedBounty = bounty,
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

    fun createBounty(request: CreateBountyRequest) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, createError = null)
            try {
                val bounty = apiClient.createBounty(request)
                _uiState.value = _uiState.value.copy(
                    selectedBounty = bounty,
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
