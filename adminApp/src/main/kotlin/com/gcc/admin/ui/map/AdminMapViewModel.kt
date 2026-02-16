package com.gcc.admin.ui.map

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel for the admin map screen. Manages map state including loaded data
 * (blocks, routes, service areas), selection state, and checkpoint placement mode.
 */
class AdminMapViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Map center (North Lawndale default)
    private val _centerLat = MutableStateFlow(41.8603)
    val centerLat: StateFlow<Double> = _centerLat.asStateFlow()

    private val _centerLng = MutableStateFlow(-87.7228)
    val centerLng: StateFlow<Double> = _centerLng.asStateFlow()

    private val _zoom = MutableStateFlow(14.0)
    val zoom: StateFlow<Double> = _zoom.asStateFlow()

    // Loaded data
    private val _blocks = MutableStateFlow<List<Block>>(emptyList())
    val blocks: StateFlow<List<Block>> = _blocks.asStateFlow()

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _serviceAreas = MutableStateFlow<List<ServiceArea>>(emptyList())
    val serviceAreas: StateFlow<List<ServiceArea>> = _serviceAreas.asStateFlow()

    // Selection state
    private val _selectedBlockId = MutableStateFlow<Int?>(null)
    val selectedBlockId: StateFlow<Int?> = _selectedBlockId.asStateFlow()

    private val _selectedRouteId = MutableStateFlow<Int?>(null)
    val selectedRouteId: StateFlow<Int?> = _selectedRouteId.asStateFlow()

    // Checkpoint placement mode
    private val _placingCheckpoints = MutableStateFlow(false)
    val placingCheckpoints: StateFlow<Boolean> = _placingCheckpoints.asStateFlow()

    private val _placedCheckpoints = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val placedCheckpoints: StateFlow<List<Pair<Double, Double>>> = _placedCheckpoints.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadMapData()
    }

    fun loadMapData() {
        scope.launch {
            _isLoading.value = true
            try {
                coroutineScope {
                    val blocksDeferred = async { api.getAdminBlocks() }
                    val routesDeferred = async { api.getAdminRoutes() }
                    val areasDeferred = async { api.getAdminServiceAreas() }
                    _blocks.value = blocksDeferred.await()
                    _routes.value = routesDeferred.await()
                    _serviceAreas.value = areasDeferred.await()
                }
            } catch (_: Exception) {
                // Silently handle API errors — map still renders with empty data
            }
            _isLoading.value = false
        }
    }

    fun selectBlock(blockId: Int?) {
        _selectedBlockId.value = blockId
    }

    fun selectRoute(routeId: Int?) {
        _selectedRouteId.value = routeId
    }

    fun startCheckpointPlacement() {
        _placingCheckpoints.value = true
        _placedCheckpoints.value = emptyList()
    }

    fun stopCheckpointPlacement() {
        _placingCheckpoints.value = false
    }

    fun addCheckpoint(lat: Double, lng: Double) {
        _placedCheckpoints.value = _placedCheckpoints.value + (lat to lng)
    }

    fun removeLastCheckpoint() {
        val current = _placedCheckpoints.value
        if (current.isNotEmpty()) {
            _placedCheckpoints.value = current.dropLast(1)
        }
    }

    fun clearCheckpoints() {
        _placedCheckpoints.value = emptyList()
    }

    fun setCenter(lat: Double, lng: Double) {
        _centerLat.value = lat
        _centerLng.value = lng
    }

    fun setZoom(z: Double) {
        _zoom.value = z
    }
}
