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

data class ProfileUiState(
    val worker: Worker? = null,
    val badges: List<Badge> = emptyList(),
    val streakDays: Int = 0,
    val isLoading: Boolean = true,
    val newlyUnlockedBadge: Badge? = null,
    val error: String? = null,
    val handleUpdateLoading: Boolean = false,
    val handleUpdateError: String? = null,
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
                val worker = apiClient.getWorkerProfile()

                val badges = try {
                    apiClient.getMyBadges()
                } catch (_: Exception) {
                    emptyList()
                }

                _uiState.value = _uiState.value.copy(
                    worker = worker,
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

    fun updateDisplayHandle(handle: String) {
        scope.launch {
            _uiState.value = _uiState.value.copy(handleUpdateLoading = true, handleUpdateError = null)
            try {
                apiClient.updateDisplayHandle(handle)
                loadProfile()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    handleUpdateLoading = false,
                    handleUpdateError = e.message ?: "Failed to update handle"
                )
            }
        }
    }

    fun dismissUnlockedBadge() {
        _uiState.value = _uiState.value.copy(newlyUnlockedBadge = null)
    }

}

