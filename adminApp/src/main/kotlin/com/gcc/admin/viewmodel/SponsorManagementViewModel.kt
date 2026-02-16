package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Sponsor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SponsorManagementViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sponsors = MutableStateFlow<List<Sponsor>>(emptyList())
    val sponsors: StateFlow<List<Sponsor>> = _sponsors.asStateFlow()

    private val _selectedSponsor = MutableStateFlow<Sponsor?>(null)
    val selectedSponsor: StateFlow<Sponsor?> = _selectedSponsor.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadSponsors()
    }

    fun loadSponsors() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val search = _searchQuery.value.ifBlank { null }
                _sponsors.value = api.getAdminSponsors(search = search)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load sponsors"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onSearchChange(query: String) {
        _searchQuery.value = query
        loadSponsors()
    }

    fun selectSponsor(sponsor: Sponsor) {
        _selectedSponsor.value = sponsor
    }

    fun clearSelection() {
        _selectedSponsor.value = null
    }
}
