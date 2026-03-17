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
                val workerResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/workers/me")

                if (workerResponse.status.value !in 200..299) {
                    val errorMsg = when (workerResponse.status.value) {
                        401 -> "Session expired. Please log in again."
                        404 -> "Profile not found."
                        else -> "Failed to load profile (${workerResponse.status.value})"
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, error = errorMsg)
                    return@launch
                }

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

    fun updateDisplayHandle(handle: String) {
        scope.launch {
            _uiState.value = _uiState.value.copy(handleUpdateLoading = true, handleUpdateError = null)
            try {
                val response = apiClient.httpClient.patch("${apiClient.baseUrl}/api/workers/me/handle") {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("handle" to handle))
                }
                if (response.status.value in 200..299) {
                    loadProfile()
                } else {
                    val errorBody = try { response.body<Map<String, String>>() } catch (_: Exception) { emptyMap() }
                    _uiState.value = _uiState.value.copy(
                        handleUpdateLoading = false,
                        handleUpdateError = errorBody["error"] ?: "Failed to update handle"
                    )
                }
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

