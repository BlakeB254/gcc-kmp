package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.model.*
import com.russhwolf.settings.Settings
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val worker: Worker? = null,
    val pushNotificationsEnabled: Boolean = true,
    val isLoading: Boolean = true,
    val signedOut: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = Settings()
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            pushNotificationsEnabled = settings.getBoolean("push_notifications", true),
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun loadSettings() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/workers/me")
                val worker = response.body<ApiResponse<Worker>>()
                _uiState.value = _uiState.value.copy(
                    worker = worker.data,
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

    fun togglePushNotifications(enabled: Boolean) {
        settings.putBoolean("push_notifications", enabled)
        _uiState.value = _uiState.value.copy(pushNotificationsEnabled = enabled)
    }

    fun signOut() {
        tokenManager.clearToken()
        _uiState.value = _uiState.value.copy(signedOut = true)
    }
}
