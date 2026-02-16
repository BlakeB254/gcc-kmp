package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Block
import com.gcc.shared.model.Route
import com.gcc.shared.model.ServiceArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServiceAreaViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _serviceAreas = MutableStateFlow<List<ServiceArea>>(emptyList())
    val serviceAreas: StateFlow<List<ServiceArea>> = _serviceAreas.asStateFlow()

    private val _blocks = MutableStateFlow<List<Block>>(emptyList())
    val blocks: StateFlow<List<Block>> = _blocks.asStateFlow()

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val areasDeferred = async { api.getAdminServiceAreas() }
                val blocksDeferred = async { api.getAdminBlocks() }
                val routesDeferred = async { api.getAdminRoutes() }

                _serviceAreas.value = areasDeferred.await()
                _blocks.value = blocksDeferred.await()
                _routes.value = routesDeferred.await()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load service area data"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
