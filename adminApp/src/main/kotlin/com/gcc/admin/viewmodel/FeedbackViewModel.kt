package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.FeedbackTicket
import com.gcc.shared.viewmodel.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FeedbackViewModel(private val api: GccApiClient) : BaseViewModel() {

    private val _tickets = MutableStateFlow<List<FeedbackTicket>>(emptyList())
    val tickets: StateFlow<List<FeedbackTicket>> = _tickets.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    init {
        loadFeedback()
    }

    fun loadFeedback() {
        launchSafe(showLoading = true) {
            _tickets.value = api.getAdminFeedback(status = _statusFilter.value)
        }
    }

    fun onStatusFilterChange(status: String?) {
        _statusFilter.value = status
        loadFeedback()
    }
}
