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

data class WalletUiState(
    val wallet: SponsorWallet? = null,
    val transactions: List<WalletTransaction> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val depositAmount: String = "",
    val isDepositing: Boolean = false,
    val depositError: String? = null,
    val isAllocating: Boolean = false,
    val allocateError: String? = null,
)

class WalletViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    fun loadWallet() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/wallet")
                val wallet = response.body<ApiResponse<SponsorWallet>>()
                _uiState.value = _uiState.value.copy(
                    wallet = wallet.data,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load wallet",
                )
            }
        }
    }

    fun deposit(amount: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isDepositing = true, depositError = null)
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/wallet/deposit") {
                    contentType(ContentType.Application.Json)
                    setBody(DepositRequest(amount = amount))
                }
                val wallet = response.body<ApiResponse<SponsorWallet>>()
                _uiState.value = _uiState.value.copy(
                    wallet = wallet.data,
                    isDepositing = false,
                    depositAmount = "",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDepositing = false,
                    depositError = e.message ?: "Failed to deposit",
                )
            }
        }
    }

    fun allocateFunds(general: Int, route: Int, bounty: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isAllocating = true, allocateError = null)
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/wallet/allocate") {
                    contentType(ContentType.Application.Json)
                    setBody(AllocateRequest(generalAmount = general, routeAmount = route, bountyAmount = bounty))
                }
                val wallet = response.body<ApiResponse<SponsorWallet>>()
                _uiState.value = _uiState.value.copy(
                    wallet = wallet.data,
                    isAllocating = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAllocating = false,
                    allocateError = e.message ?: "Failed to allocate funds",
                )
            }
        }
    }

    fun loadTransactions(limit: Int = 50, offset: Long = 0) {
        scope.launch {
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/wallet/transactions") {
                    parameter("limit", limit)
                    parameter("offset", offset)
                }
                val transactions = response.body<ApiResponse<List<WalletTransaction>>>()
                _uiState.value = _uiState.value.copy(transactions = transactions.data)
            } catch (_: Exception) {
                // Transaction loading is non-critical; silently fail
            }
        }
    }

    fun updateDepositAmount(amount: String) {
        _uiState.value = _uiState.value.copy(depositAmount = amount)
    }
}
