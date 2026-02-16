package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.FeedbackTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FeedbackViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _tickets = MutableStateFlow<List<FeedbackTicket>>(emptyList())
    val tickets: StateFlow<List<FeedbackTicket>> = _tickets.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadFeedback()
    }

    fun loadFeedback() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _tickets.value = api.getAdminFeedback(status = _statusFilter.value)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load feedback"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onStatusFilterChange(status: String?) {
        _statusFilter.value = status
        loadFeedback()
    }
}
