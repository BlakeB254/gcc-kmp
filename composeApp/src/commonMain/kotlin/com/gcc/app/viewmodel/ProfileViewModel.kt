package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val worker: Worker? = null,
    val badges: List<Badge> = emptyList(),
    val streakDays: Int = 0,
    val isLoading: Boolean = true,
    val newlyUnlockedBadge: Badge? = null,
    val error: String? = null,
)

class ProfileViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun loadProfile() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val workerResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/workers/me")
                val worker = workerResponse.body<ApiResponse<Worker>>()

                val badges = try {
                    apiClient.getMyBadges()
                } catch (_: Exception) {
                    emptyList()
                }

                _uiState.value = _uiState.value.copy(
                    worker = worker.data,
                    badges = badges,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load profile",
                )
            }
        }
    }

    fun dismissUnlockedBadge() {
        _uiState.value = _uiState.value.copy(newlyUnlockedBadge = null)
    }

}

