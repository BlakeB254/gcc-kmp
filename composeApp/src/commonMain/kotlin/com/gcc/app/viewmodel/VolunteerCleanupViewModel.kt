package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VolunteerPhase { IN_PROGRESS, AFTER_PHOTOS, TRASH_HEAP_PHOTO, SUBMITTING, COMPLETE }

data class VolunteerCleanupUiState(
    val reportId: Int = 0,
    val cleanup: VolunteerCleanup? = null,
    val elapsedSeconds: Long = 0,
    val afterPhotoUrls: List<String> = emptyList(),
    val trashHeapUrl: String? = null,
    val isSubmitting: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null,
    val phase: VolunteerPhase = VolunteerPhase.IN_PROGRESS,
)

class VolunteerCleanupViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(VolunteerCleanupUiState())
    val uiState: StateFlow<VolunteerCleanupUiState> = _uiState.asStateFlow()

    fun startCleanup(reportId: Int, beforeMediaUrls: List<String>) {
        scope.launch {
            _uiState.value = _uiState.value.copy(reportId = reportId, error = null)
            try {
                val request = StartVolunteerRequest(beforeMediaUrls = beforeMediaUrls)
                val response = apiClient.httpClient.post(
                    "${apiClient.baseUrl}/api/spots/$reportId/volunteer"
                ) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val cleanup = response.body<ApiResponse<VolunteerCleanup>>()
                _uiState.value = _uiState.value.copy(
                    cleanup = cleanup.data,
                    phase = VolunteerPhase.IN_PROGRESS,
                )
                startTimer()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to start cleanup",
                )
            }
        }
    }

    fun addAfterPhoto(url: String) {
        _uiState.value = _uiState.value.copy(
            afterPhotoUrls = _uiState.value.afterPhotoUrls + url,
        )
    }

    fun setTrashHeapUrl(url: String) {
        _uiState.value = _uiState.value.copy(trashHeapUrl = url)
    }

    fun advancePhase() {
        val next = when (_uiState.value.phase) {
            VolunteerPhase.IN_PROGRESS -> VolunteerPhase.AFTER_PHOTOS
            VolunteerPhase.AFTER_PHOTOS -> VolunteerPhase.TRASH_HEAP_PHOTO
            VolunteerPhase.TRASH_HEAP_PHOTO -> {
                completeCleanup()
                return
            }
            VolunteerPhase.SUBMITTING -> return
            VolunteerPhase.COMPLETE -> return
        }
        _uiState.value = _uiState.value.copy(phase = next, error = null)
    }

    private fun completeCleanup() {
        val state = _uiState.value
        val cleanup = state.cleanup ?: return
        scope.launch {
            _uiState.value = state.copy(
                phase = VolunteerPhase.SUBMITTING,
                isSubmitting = true,
                error = null,
            )
            try {
                val request = CompleteVolunteerRequest(
                    afterMediaUrls = state.afterPhotoUrls,
                    trashHeapMediaUrl = state.trashHeapUrl,
                )
                val response = apiClient.httpClient.patch(
                    "${apiClient.baseUrl}/api/spots/${state.reportId}/volunteer/${cleanup.id}/complete"
                ) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val updated = response.body<ApiResponse<VolunteerCleanup>>()
                _uiState.value = _uiState.value.copy(
                    cleanup = updated.data,
                    isSubmitting = false,
                    isCompleted = true,
                    phase = VolunteerPhase.COMPLETE,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = VolunteerPhase.TRASH_HEAP_PHOTO,
                    error = e.message ?: "Failed to complete cleanup",
                )
            }
        }
    }

    fun startTimer() {
        scope.launch {
            while (!_uiState.value.isCompleted) {
                delay(1000L)
                _uiState.value = _uiState.value.copy(
                    elapsedSeconds = _uiState.value.elapsedSeconds + 1,
                )
            }
        }
    }
}
