package com.gcc.app.viewmodel

import com.gcc.app.platform.EnvScanSessionUploader
import com.gcc.app.platform.PushNotificationManager
import com.gcc.app.platform.capture.CaptureChunkUploader
import com.gcc.app.platform.openUrl
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.cache.CaptureChunkStore
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
    val connectStatus: ConnectStatus? = null,
    val connectStatusLoading: Boolean = false,
    val connectOnboardLoading: Boolean = false,
    val connectError: String? = null,
    // Scan-upload controls (capture chunks are policy-gated: Wi-Fi OR
    // charging OR this cellular opt-in — see CaptureChunkUploader).
    val cellularUploadEnabled: Boolean = false,
    val pendingScanUploads: Int = 0,
    val scanUploadFlushing: Boolean = false,
    val scanUploadHint: String? = null,
)

class SettingsViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
    private val pushNotificationManager: PushNotificationManager,
    private val captureChunkUploader: CaptureChunkUploader,
    private val envScanSessionUploader: EnvScanSessionUploader,
    private val captureChunkStore: CaptureChunkStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = Settings()
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            pushNotificationsEnabled = settings.getBoolean("push_notifications", true),
            biometricEnabled = settings.getBoolean("biometric_enabled", true),
            cellularUploadEnabled = settings.getBoolean(
                CaptureChunkUploader.KEY_CELLULAR_UPLOAD_OPT_IN, false
            ),
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun loadPendingScanUploads() {
        scope.launch {
            val pending = try {
                captureChunkStore.pendingChunks().size
            } catch (_: Exception) {
                0
            }
            _uiState.value = _uiState.value.copy(pendingScanUploads = pending)
        }
    }

    fun toggleCellularUpload(enabled: Boolean) {
        settings.putBoolean(CaptureChunkUploader.KEY_CELLULAR_UPLOAD_OPT_IN, enabled)
        _uiState.value = _uiState.value.copy(cellularUploadEnabled = enabled, scanUploadHint = null)
        if (enabled) flushScanUploadsNow() // opting in should start draining immediately
    }

    fun flushScanUploadsNow() {
        scope.launch {
            _uiState.value = _uiState.value.copy(scanUploadFlushing = true, scanUploadHint = null)
            val before = _uiState.value.pendingScanUploads
            try {
                envScanSessionUploader.flushPending()
                captureChunkUploader.flushPending()
            } catch (_: Exception) {
                // flushPending logs internally; the count below tells the truth
            }
            val after = try {
                captureChunkStore.pendingChunks().size
            } catch (_: Exception) {
                before
            }
            // Prefer the REAL failure over guessing: the uploaders record why
            // the most recent attempt failed (signature errors, timeouts, 4xx
            // bodies) — surfacing it here is how field upload failures get
            // diagnosed without a debugger on the phone.
            val realError = captureChunkUploader.lastError ?: envScanSessionUploader.lastError
            _uiState.value = _uiState.value.copy(
                pendingScanUploads = after,
                scanUploadFlushing = false,
                scanUploadHint = when {
                    realError != null && after > 0 -> "Upload failing: $realError"
                    after > 0 && after == before && !_uiState.value.cellularUploadEnabled ->
                        "Still pending — connect to Wi-Fi, plug in, or enable cellular uploads"
                    else -> null
                },
            )
        }
    }

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

    fun loadConnectStatus() {
        scope.launch {
            _uiState.value = _uiState.value.copy(connectStatusLoading = true, connectError = null)
            try {
                val status = apiClient.connectStatus()
                _uiState.value = _uiState.value.copy(
                    connectStatus = status,
                    connectStatusLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectStatusLoading = false,
                    connectError = e.message ?: "Failed to load payout status",
                )
            }
        }
    }

    fun startConnectOnboard() {
        scope.launch {
            _uiState.value = _uiState.value.copy(connectOnboardLoading = true, connectError = null)
            try {
                val url = apiClient.connectOnboard()
                _uiState.value = _uiState.value.copy(connectOnboardLoading = false)
                openUrl(url)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectOnboardLoading = false,
                    connectError = e.message ?: "Failed to open payout setup",
                )
            }
        }
    }
}
