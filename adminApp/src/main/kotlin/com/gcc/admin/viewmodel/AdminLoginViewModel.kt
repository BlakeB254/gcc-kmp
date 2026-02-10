package com.gcc.admin.viewmodel

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminLoginState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
)

class AdminLoginViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AdminLoginState())
    val state: StateFlow<AdminLoginState> = _state.asStateFlow()

    init {
        // If we already have a valid token, mark as logged in
        if (tokenManager.isLoggedIn() && !tokenManager.isTokenExpired()) {
            _state.update { it.copy(isLoggedIn = true) }
        }
    }

    fun updateEmail(email: String) {
        _state.update { it.copy(email = email, error = null) }
    }

    fun updatePassword(password: String) {
        _state.update { it.copy(password = password, error = null) }
    }

    fun login() {
        val currentState = _state.value
        if (currentState.email.isBlank() || currentState.password.isBlank()) {
            _state.update { it.copy(error = "Email and password are required") }
            return
        }

        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(email = currentState.email, password = currentState.password))
                }
                if (response.status == HttpStatusCode.OK) {
                    val result: ApiResponse<AuthResponse> = response.body()
                    tokenManager.saveToken(result.data.token)
                    _state.update { it.copy(isLoading = false, isLoggedIn = true) }
                } else {
                    _state.update { it.copy(isLoading = false, error = "Invalid email or password") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Login failed") }
            }
        }
    }

    fun logout() {
        tokenManager.clearToken()
        _state.update {
            AdminLoginState() // Reset to defaults
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
