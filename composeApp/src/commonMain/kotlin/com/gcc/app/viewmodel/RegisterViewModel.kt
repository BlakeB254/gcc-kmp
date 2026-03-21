package com.gcc.app.viewmodel

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

data class RegisterUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isRegistered: Boolean = false,
    val worker: Worker? = null,
)

class RegisterViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun register(
        name: String,
        email: String,
        phone: String,
        password: String,
        addressStreet: String = "",
        addressCity: String = "Chicago",
        addressState: String = "IL",
        addressZip: String = "",
    ) {
        // Client-side validation
        val validationError = validate(name, email, password)
        if (validationError != null) {
            _uiState.value = _uiState.value.copy(error = validationError)
            return
        }

        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest(
                        name = name.trim(),
                        email = email.trim(),
                        phone = phone.trim(),
                        password = password,
                        addressStreet = addressStreet.trim().ifBlank { null },
                        addressCity = addressCity.trim().ifBlank { null },
                        addressState = addressState.trim().ifBlank { null },
                        addressZip = addressZip.trim().ifBlank { null },
                    ))
                }
                if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                    val apiResponse = response.body<ApiResponse<AuthResponse>>()
                    apiResponse.data.token?.let { tokenManager.saveToken(it) }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRegistered = true,
                        worker = apiResponse.data.worker,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Registration failed. Please try again.",
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Registration failed",
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun validate(name: String, email: String, password: String): String? {
        if (name.isBlank()) return "Name is required"
        if (!email.contains("@") || !email.contains(".")) return "Please enter a valid email"
        if (password.length < 8) return "Password must be at least 8 characters"
        return null
    }
}
