package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Bounty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BountyManagementViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _bounties = MutableStateFlow<List<Bounty>>(emptyList())
    val bounties: StateFlow<List<Bounty>> = _bounties.asStateFlow()

    private val _selectedBounty = MutableStateFlow<Bounty?>(null)
    val selectedBounty: StateFlow<Bounty?> = _selectedBounty.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadBounties()
    }

    fun loadBounties() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _bounties.value = api.getAdminBounties()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load bounties"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectBounty(bounty: Bounty) {
        _selectedBounty.value = bounty
    }

    fun clearSelection() {
        _selectedBounty.value = null
    }

    fun forceClose(bountyId: Int) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.forceCloseBounty(bountyId)
                loadBounties()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to close bounty"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun forceReassign(bountyId: Int) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val updated = api.forceReassignBounty(bountyId)
                _selectedBounty.value = updated
                loadBounties()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to reassign bounty"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
