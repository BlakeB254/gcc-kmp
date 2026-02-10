package com.gcc.admin.viewmodel

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlockManagementState(
    val blocks: List<Block> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class BlockManagementViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(BlockManagementState())
    val state: StateFlow<BlockManagementState> = _state.asStateFlow()

    init {
        loadBlocks()
    }

    fun loadBlocks() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/blocks") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<List<Block>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(blocks = result.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load blocks", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load blocks", isLoading = false) }
            }
        }
    }

    fun createBlock(request: AdminBlockRequest) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/admin/blocks") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val result: ApiResponse<Block> = response.body()
                if (result.success) {
                    _state.update { it.copy(isLoading = false) }
                    loadBlocks()
                } else {
                    _state.update { it.copy(error = result.message ?: "Create failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Create failed", isLoading = false) }
            }
        }
    }

    fun updateBlock(blockId: Int, request: AdminBlockRequest) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/blocks/$blockId") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val result: ApiResponse<Block> = response.body()
                if (result.success) {
                    _state.update { it.copy(isLoading = false) }
                    loadBlocks()
                } else {
                    _state.update { it.copy(error = result.message ?: "Update failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Update failed", isLoading = false) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
