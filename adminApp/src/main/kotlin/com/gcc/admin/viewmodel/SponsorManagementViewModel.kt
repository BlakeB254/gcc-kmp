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

data class SponsorManagementState(
    val sponsors: List<Sponsor> = emptyList(),
    val selectedSponsor: Sponsor? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SponsorManagementViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(SponsorManagementState())
    val state: StateFlow<SponsorManagementState> = _state.asStateFlow()

    init {
        loadSponsors()
    }

    fun loadSponsors() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/sponsors") {
                    contentType(ContentType.Application.Json)
                    val query = _state.value.searchQuery
                    if (query.isNotBlank()) {
                        parameter("search", query)
                    }
                }
                val result: ApiResponse<List<Sponsor>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(sponsors = result.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load sponsors", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load sponsors", isLoading = false) }
            }
        }
    }

    fun searchSponsors(query: String) {
        _state.update { it.copy(searchQuery = query) }
        loadSponsors()
    }

    fun selectSponsor(sponsorId: Int) {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/sponsors/$sponsorId") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Sponsor> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(selectedSponsor = result.data) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load sponsor detail") }
            }
        }
    }

    fun clearSelectedSponsor() {
        _state.update { it.copy(selectedSponsor = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
