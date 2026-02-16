package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.AdminBlockRequest
import com.gcc.shared.model.Block
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BlockManagementViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _blocks = MutableStateFlow<List<Block>>(emptyList())
    val blocks: StateFlow<List<Block>> = _blocks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadBlocks()
    }

    fun loadBlocks() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _blocks.value = api.getAdminBlocks()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load blocks"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createBlock(request: AdminBlockRequest) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.createBlock(request)
                loadBlocks()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to create block"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateBlock(blockId: Int, request: AdminBlockRequest) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.updateBlock(blockId, request)
                loadBlocks()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update block"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
