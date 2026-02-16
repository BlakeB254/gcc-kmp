package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.AuthResponse
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminLoginViewModel(
    private val api: GccApiClient,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun onEmailChange(value: String) {
        _email.value = value
    }

    fun onPasswordChange(value: String) {
        _password.value = value
    }

    fun login(onSuccess: () -> Unit) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = api.httpClient.post("${api.baseUrl}/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("email" to _email.value, "password" to _password.value))
                }
                val authResponse = response.body<ApiResponse<AuthResponse>>().data
                tokenManager.saveToken(authResponse.token)
                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message ?: "Login failed"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
