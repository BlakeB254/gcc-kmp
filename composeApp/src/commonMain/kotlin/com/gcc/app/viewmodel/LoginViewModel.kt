package com.gcc.app.viewmodel

import com.gcc.app.platform.PushNotificationManager
import com.gcc.app.platform.getPlatformName
import com.gcc.app.platform.saveToSystemCredentials
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
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

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val worker: Worker? = null,
    val isAdmin: Boolean = false,
    val requiresTwoFactor: Boolean = false,
    val twoFactorToken: String? = null,
)

class LoginViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
    private val pushNotificationManager: PushNotificationManager,
    private val biometricAuthViewModel: BiometricAuthViewModel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        val normalizedEmail = email.trim().lowercase()
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(email = normalizedEmail, password = password))
                }
                if (response.status == HttpStatusCode.OK) {
                    val apiResponse = response.body<ApiResponse<AuthResponse>>()
                    val authData = apiResponse.data
                    if (authData.requiresTwoFactor) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            requiresTwoFactor = true,
                            twoFactorToken = authData.twoFactorToken,
                        )
                    } else {
                        tokenManager.saveToken(authData.token!!)
                        // Save credentials in Keychain for Face ID re-login
                        tokenManager.saveCredentials(normalizedEmail, password)
                        // Offer to save in iOS Passwords app for AutoFill
                        saveToSystemCredentials(normalizedEmail, password)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isLoggedIn = true,
                            worker = authData.worker,
                            isAdmin = tokenManager.isAdmin(),
                        )
                        // Register for push notifications after successful login
                        registerPushToken()
                    }
                } else {
                    // Extract server error message for better diagnostics
                    val errorMsg = try {
                        val errorBody = response.body<ApiResponse<AuthResponse?>>()
                        errorBody.message ?: "Invalid email or password"
                    } catch (_: Exception) {
                        "Login failed (HTTP ${response.status.value})"
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errorMsg,
                    )
                }
            } catch (e: Exception) {
                val detail = e.message ?: "Unknown error"
                println("GCC Login error: $detail")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Login failed: $detail",
                )
            }
        }
    }

    fun verify2FA(code: String) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val twoFactorToken = _uiState.value.twoFactorToken ?: return@launch
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/auth/verify-2fa") {
                    contentType(ContentType.Application.Json)
                    setBody(Verify2FARequest(twoFactorToken = twoFactorToken, code = code))
                }
                if (response.status == HttpStatusCode.OK) {
                    val apiResponse = response.body<ApiResponse<AuthResponse>>()
                    val authData = apiResponse.data
                    tokenManager.saveToken(authData.token!!)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        worker = authData.worker,
                        isAdmin = tokenManager.isAdmin(),
                        requiresTwoFactor = false,
                        twoFactorToken = null,
                    )
                    registerPushToken()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Invalid verification code",
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Verification failed",
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun setError(message: String) {
        _uiState.value = _uiState.value.copy(error = message, isLoading = false)
    }

    fun cancel2FA() {
        _uiState.value = LoginUiState()
    }

    /** Reset to initial state. Call when LoginScreen enters composition
     *  to prevent stale isLoggedIn from a previous session. */
    fun resetState() {
        _uiState.value = LoginUiState()
    }

    /**
     * Suspending biometric re-login for BiometricGateScreen.
     * Delegates to [BiometricAuthViewModel] and mirrors result into [uiState].
     * Push-token registration fires on success.
     */
    suspend fun loginWithBiometricSuspend(): Boolean {
        val success = biometricAuthViewModel.loginWithBiometricSuspend()
        if (success) {
            _uiState.value = _uiState.value.copy(
                isLoggedIn = true,
                isAdmin = tokenManager.isAdmin(),
            )
            registerPushTokenSuspend()
        }
        return success
    }

    /**
     * Fire-and-forget biometric re-login for the LoginScreen Face ID button.
     * Updates uiState so the screen can react to isLoggedIn/error.
     */
    fun loginWithBiometric(onSuccess: () -> Unit) {
        scope.launch {
            val success = loginWithBiometricSuspend()
            if (success) onSuccess()
        }
    }

    private fun registerPushToken() {
        scope.launch { registerPushTokenSuspend() }
    }

    private suspend fun registerPushTokenSuspend() {
        try {
            val token = pushNotificationManager.registerForPushNotifications()
            if (token != null) {
                apiClient.registerDeviceToken(
                    platform = getPlatformName(),
                    token = token,
                )
            }
        } catch (_: Exception) {
            // Push registration is best-effort; don't block login flow
        }
    }
}
