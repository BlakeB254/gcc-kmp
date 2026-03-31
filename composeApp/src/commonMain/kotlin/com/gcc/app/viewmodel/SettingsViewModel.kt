package com.gcc.app.viewmodel

import com.gcc.app.platform.PushNotificationManager
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.model.*
import com.russhwolf.settings.Settings
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
    val biometricEnabled: Boolean = true,
    val isLoading: Boolean = true,
    val signedOut: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val accountDeleted: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
    private val pushNotificationManager: PushNotificationManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = Settings()
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            pushNotificationsEnabled = settings.getBoolean("push_notifications", true),
            biometricEnabled = settings.getBoolean("biometric_enabled", true),
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun loadSettings() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val worker = apiClient.getWorkerProfile()
                _uiState.value = _uiState.value.copy(
                    worker = worker,
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

    fun toggleBiometric(enabled: Boolean) {
        settings.putBoolean("biometric_enabled", enabled)
        _uiState.value = _uiState.value.copy(biometricEnabled = enabled)
    }

    fun signOut() {
        scope.launch {
            try {
                apiClient.removeDeviceToken()
            } catch (_: Exception) {
                // Best-effort: server token cleanup is non-critical
            }
            pushNotificationManager.unregister()
            tokenManager.logout()
            _uiState.value = _uiState.value.copy(signedOut = true)
        }
    }

    fun deleteAccount() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isDeletingAccount = true, error = null)
            try {
                apiClient.deleteAccount()
                pushNotificationManager.unregister()
                tokenManager.logout()
                _uiState.value = _uiState.value.copy(
                    isDeletingAccount = false,
                    accountDeleted = true,
                    signedOut = true,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeletingAccount = false,
                    error = e.message ?: "Failed to delete account",
                )
            }
        }
    }
}
