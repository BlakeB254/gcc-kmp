package com.gcc.app.viewmodel

import com.gcc.app.platform.BiometricAuth
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BiometricAuthState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isAdmin: Boolean = false,
    val error: String? = null,
)

/**
 * Handles biometric re-authentication (silent token restore + credential re-login).
 * Used by BiometricGateScreen and the Face ID button on LoginScreen.
 */
class BiometricAuthViewModel(
    private val tokenManager: TokenManager,
    private val biometricAuth: BiometricAuth,
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(BiometricAuthState())
    val state: StateFlow<BiometricAuthState> = _state.asStateFlow()

    /**
     * Suspending biometric re-login for BiometricGateScreen.
     * Returns true if login succeeded, false otherwise.
     */
    suspend fun loginWithBiometricSuspend(): Boolean {
        // Fast path: active token still valid
        if (tokenManager.isLoggedIn() && !tokenManager.isTokenExpired()) {
            _state.value = _state.value.copy(
                isLoggedIn = true,
                isAdmin = tokenManager.isAdmin(),
            )
            return true
        }
        // Fast path: biometric token still valid
        if (tokenManager.restoreBiometricToken()) {
            _state.value = _state.value.copy(
                isLoggedIn = true,
                isAdmin = tokenManager.isAdmin(),
            )
            return true
        }

        // Slow path: re-login with saved credentials via typed API
        val credentials = tokenManager.loadCredentials() ?: return false

        _state.value = _state.value.copy(isLoading = true, error = null)
        return try {
            val (email, password) = credentials
            val authData = apiClient.login(email, password)
            val token = authData.token
            if (authData.requiresTwoFactor) {
                _state.value = _state.value.copy(isLoading = false)
                false
            } else if (token == null) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Unexpected server response — please try again",
                )
                false
            } else {
                tokenManager.saveToken(token)
                _state.value = _state.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    isAdmin = tokenManager.isAdmin(),
                )
                true
            }
        } catch (_: Exception) {
            tokenManager.clearCredentials()
            _state.value = _state.value.copy(isLoading = false)
            false
        }
    }

    /**
     * Fire-and-forget biometric re-login.
     * Updates [state] so observers can react to isLoggedIn/error.
     */
    fun loginWithBiometric(onSuccess: () -> Unit) {
        scope.launch {
            val success = loginWithBiometricSuspend()
            if (success) onSuccess()
        }
    }

    fun resetState() {
        _state.value = BiometricAuthState()
    }
}
