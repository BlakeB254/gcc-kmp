package com.gcc.admin.ui.map

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LayerType {
    COMMUNITY_AREAS, BLOCKS, ROUTES, SPOTS, BOUNTIES, GRID
}

data class AdminMapState(
    val blocks: List<Block> = emptyList(),
    val routes: List<Route> = emptyList(),
    val serviceAreas: List<ServiceArea> = emptyList(),
    val spots: List<CommunityReport> = emptyList(),
    val bounties: List<Bounty> = emptyList(),
    val selectedBlockId: Int? = null,
    val selectedServiceAreaId: Int? = null,
    val selectedSpotId: Int? = null,
    val selectedBountyId: Int? = null,
    val showCommunityAreas: Boolean = true,
    val showBlocks: Boolean = true,
    val showRoutes: Boolean = true,
    val showSpots: Boolean = true,
    val showBounties: Boolean = true,
    val showGrid: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AdminMapViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AdminMapState())
    val state: StateFlow<AdminMapState> = _state.asStateFlow()

    init {
        loadAllData()
    }

    fun loadAllData() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val blocksDeferred = async { loadList<Block>("/api/admin/blocks") }
                val routesDeferred = async { loadList<Route>("/api/admin/routes") }
                val areasDeferred = async { loadList<ServiceArea>("/api/admin/service-areas") }
                val spotsDeferred = async { loadList<CommunityReport>("/api/admin/spots") }
                val bountiesDeferred = async { loadList<Bounty>("/api/admin/bounties") }

                _state.update {
                    it.copy(
                        blocks = blocksDeferred.await(),
                        routes = routesDeferred.await(),
                        serviceAreas = areasDeferred.await(),
                        spots = spotsDeferred.await(),
                        bounties = bountiesDeferred.await(),
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load map data", isLoading = false) }
            }
        }
    }

    private suspend inline fun <reified T> loadList(path: String): List<T> {
        return try {
            val response = apiClient.httpClient.get("${apiClient.baseUrl}$path") {
                contentType(ContentType.Application.Json)
            }
            val result: ApiResponse<List<T>> = response.body()
            if (result.success && result.data != null) result.data else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun selectBlock(id: Int) {
        _state.update { it.copy(selectedBlockId = id, selectedServiceAreaId = null, selectedSpotId = null, selectedBountyId = null) }
    }

    fun selectServiceArea(id: Int) {
        _state.update { it.copy(selectedBlockId = null, selectedServiceAreaId = id, selectedSpotId = null, selectedBountyId = null) }
    }

    fun selectSpot(id: Int) {
        _state.update { it.copy(selectedBlockId = null, selectedServiceAreaId = null, selectedSpotId = id, selectedBountyId = null) }
    }

    fun selectBounty(id: Int) {
        _state.update { it.copy(selectedBlockId = null, selectedServiceAreaId = null, selectedSpotId = null, selectedBountyId = id) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedBlockId = null, selectedServiceAreaId = null, selectedSpotId = null, selectedBountyId = null) }
    }

    fun toggleLayer(layer: LayerType) {
        _state.update {
            when (layer) {
                LayerType.COMMUNITY_AREAS -> it.copy(showCommunityAreas = !it.showCommunityAreas)
                LayerType.BLOCKS -> it.copy(showBlocks = !it.showBlocks)
                LayerType.ROUTES -> it.copy(showRoutes = !it.showRoutes)
                LayerType.SPOTS -> it.copy(showSpots = !it.showSpots)
                LayerType.BOUNTIES -> it.copy(showBounties = !it.showBounties)
                LayerType.GRID -> it.copy(showGrid = !it.showGrid)
            }
        }
    }

    fun refresh() {
        loadAllData()
    }
}
