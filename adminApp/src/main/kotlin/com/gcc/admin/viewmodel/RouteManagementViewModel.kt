package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RouteManagementState(
    val routes: List<Route> = emptyList(),
    val editingRoute: AdminRouteRequest? = null,
    val editingRouteId: Int? = null,
    val checkpoints: List<AdminCheckpointRequest> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

class RouteManagementViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(RouteManagementState())
    val state: StateFlow<RouteManagementState> = _state.asStateFlow()

    init {
        loadRoutes()
    }

    fun loadRoutes() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/admin/routes") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<List<Route>> = response.body()
                if (result.success && result.data != null) {
                    _state.update { it.copy(routes = result.data, isLoading = false) }
                } else {
                    _state.update { it.copy(error = result.message ?: "Failed to load routes", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to load routes", isLoading = false) }
            }
        }
    }

    fun startNewRoute() {
        _state.update {
            it.copy(
                editingRoute = AdminRouteRequest(
                    code = "",
                    displayName = "",
                    serviceAreaId = 0,
                ),
                editingRouteId = null,
                checkpoints = emptyList(),
            )
        }
    }

    fun startEditRoute(route: Route) {
        _state.update {
            it.copy(
                editingRoute = AdminRouteRequest(
                    code = route.code,
                    displayName = route.displayName,
                    serviceAreaId = route.serviceAreaId,
                    distanceMeters = route.distanceMeters,
                    estimatedDurationMinutes = route.estimatedDurationMinutes,
                    payoutAmount = route.payoutAmount,
                    checkpoints = route.checkpoints.map { cp ->
                        AdminCheckpointRequest(
                            name = cp.name,
                            lat = cp.coordinates.lat,
                            lng = cp.coordinates.lng,
                            sequenceOrder = cp.sequenceOrder,
                            photoRequired = cp.photoRequired,
                            geofenceRadius = cp.geofenceRadius,
                            type = cp.type.name.lowercase(),
                        )
                    },
                ),
                editingRouteId = route.id,
                checkpoints = route.checkpoints.map { cp ->
                    AdminCheckpointRequest(
                        name = cp.name,
                        lat = cp.coordinates.lat,
                        lng = cp.coordinates.lng,
                        sequenceOrder = cp.sequenceOrder,
                        photoRequired = cp.photoRequired,
                        geofenceRadius = cp.geofenceRadius,
                        type = cp.type.name.lowercase(),
                    )
                },
            )
        }
    }

    fun updateEditingRoute(route: AdminRouteRequest) {
        _state.update { it.copy(editingRoute = route) }
    }

    fun cancelEdit() {
        _state.update { it.copy(editingRoute = null, editingRouteId = null, checkpoints = emptyList()) }
    }

    fun addCheckpoint(checkpoint: AdminCheckpointRequest) {
        _state.update { it.copy(checkpoints = it.checkpoints + checkpoint) }
    }

    fun removeCheckpoint(index: Int) {
        _state.update {
            val updated = it.checkpoints.toMutableList()
            if (index in updated.indices) {
                updated.removeAt(index)
            }
            it.copy(checkpoints = updated)
        }
    }

    fun createRoute(request: AdminRouteRequest) {
        scope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val requestWithCheckpoints = request.copy(checkpoints = _state.value.checkpoints)
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/admin/routes") {
                    contentType(ContentType.Application.Json)
                    setBody(requestWithCheckpoints)
                }
                val result: ApiResponse<Route> = response.body()
                if (result.success) {
                    _state.update {
                        it.copy(
                            editingRoute = null,
                            editingRouteId = null,
                            checkpoints = emptyList(),
                            isSaving = false,
                        )
                    }
                    loadRoutes()
                } else {
                    _state.update { it.copy(error = result.message ?: "Create failed", isSaving = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Create failed", isSaving = false) }
            }
        }
    }

    fun updateRoute(routeId: Int, request: AdminRouteRequest) {
        scope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val requestWithCheckpoints = request.copy(checkpoints = _state.value.checkpoints)
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/routes/$routeId") {
                    contentType(ContentType.Application.Json)
                    setBody(requestWithCheckpoints)
                }
                val result: ApiResponse<Route> = response.body()
                if (result.success) {
                    _state.update {
                        it.copy(
                            editingRoute = null,
                            editingRouteId = null,
                            checkpoints = emptyList(),
                            isSaving = false,
                        )
                    }
                    loadRoutes()
                } else {
                    _state.update { it.copy(error = result.message ?: "Update failed", isSaving = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Update failed", isSaving = false) }
            }
        }
    }

    fun publishRoute(routeId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/routes/$routeId/publish") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Route> = response.body()
                if (result.success) {
                    _state.update { it.copy(isLoading = false) }
                    loadRoutes()
                } else {
                    _state.update { it.copy(error = result.message ?: "Publish failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Publish failed", isLoading = false) }
            }
        }
    }

    fun archiveRoute(routeId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.httpClient.put("${apiClient.baseUrl}/api/admin/routes/$routeId/archive") {
                    contentType(ContentType.Application.Json)
                }
                val result: ApiResponse<Route> = response.body()
                if (result.success) {
                    _state.update { it.copy(isLoading = false) }
                    loadRoutes()
                } else {
                    _state.update { it.copy(error = result.message ?: "Archive failed", isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Archive failed", isLoading = false) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
