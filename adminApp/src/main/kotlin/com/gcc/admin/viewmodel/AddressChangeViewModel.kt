package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.AddressChangeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddressChangeViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _changes = MutableStateFlow<List<AddressChangeRequest>>(emptyList())
    val changes: StateFlow<List<AddressChangeRequest>> = _changes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadChanges()
    }

    fun loadChanges() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _changes.value = api.getAddressChanges()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load address changes"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
