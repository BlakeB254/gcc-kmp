package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Dispute
import com.gcc.shared.model.ResolveDisputeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DisputeViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _disputes = MutableStateFlow<List<Dispute>>(emptyList())
    val disputes: StateFlow<List<Dispute>> = _disputes.asStateFlow()

    private val _selectedDispute = MutableStateFlow<Dispute?>(null)
    val selectedDispute: StateFlow<Dispute?> = _selectedDispute.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadDisputes()
    }

    fun loadDisputes() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _disputes.value = api.getAdminDisputes(status = _statusFilter.value)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load disputes"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onStatusFilterChange(status: String?) {
        _statusFilter.value = status
        loadDisputes()
    }

    fun selectDispute(dispute: Dispute) {
        _selectedDispute.value = dispute
    }

    fun clearSelection() {
        _selectedDispute.value = null
    }

    fun resolveDispute(disputeId: Int, request: ResolveDisputeRequest) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.resolveDispute(disputeId, request)
                loadDisputes()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to resolve dispute"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
