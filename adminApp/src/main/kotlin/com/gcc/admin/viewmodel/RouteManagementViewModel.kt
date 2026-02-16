package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.AdminCheckpointRequest
import com.gcc.shared.model.AdminRouteRequest
import com.gcc.shared.model.CameraMode
import com.gcc.shared.model.CheckpointType
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteBountyPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RouteManagementViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _editingRoute = MutableStateFlow<AdminRouteRequest?>(null)
    val editingRoute: StateFlow<AdminRouteRequest?> = _editingRoute.asStateFlow()

    private val _editingRouteId = MutableStateFlow<Int?>(null)
    val editingRouteId: StateFlow<Int?> = _editingRouteId.asStateFlow()

    private val _editingCheckpoints = MutableStateFlow<List<AdminCheckpointRequest>>(emptyList())
    val editingCheckpoints: StateFlow<List<AdminCheckpointRequest>> = _editingCheckpoints.asStateFlow()

    private val _editingBountyPrompts = MutableStateFlow<List<RouteBountyPrompt>>(emptyList())
    val editingBountyPrompts: StateFlow<List<RouteBountyPrompt>> = _editingBountyPrompts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadRoutes()
    }

    fun loadRoutes() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _routes.value = api.getAdminRoutes()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load routes"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startNewRoute() {
        _editingRouteId.value = null
        _editingCheckpoints.value = emptyList()
        _editingBountyPrompts.value = emptyList()
        _editingRoute.value = AdminRouteRequest(
            code = "",
            displayName = "",
            serviceAreaId = 0,
        )
    }

    fun startEditRoute(route: Route) {
        _editingRouteId.value = route.id
        _editingCheckpoints.value = route.checkpoints.mapIndexed { index, cp ->
            AdminCheckpointRequest(
                name = cp.name,
                lat = cp.coordinates.lat,
                lng = cp.coordinates.lng,
                sequenceOrder = index,
                photoRequired = cp.photoRequired,
                geofenceRadius = cp.geofenceRadius,
                type = cp.type.name.lowercase(),
                instructions = cp.instructions,
                cameraMode = cp.cameraMode?.name?.lowercase(),
                bountyPrompts = cp.bountyPrompts,
            )
        }
        _editingBountyPrompts.value = emptyList()
        _editingRoute.value = AdminRouteRequest(
            code = route.code,
            displayName = route.displayName,
            serviceAreaId = route.serviceAreaId,
            distanceMeters = route.distanceMeters,
            estimatedDurationMinutes = route.estimatedDurationMinutes,
            payoutAmount = route.payoutAmount,
            checkpoints = _editingCheckpoints.value,
            imageUrl = route.imageUrl,
        )
    }

    fun updateField(field: String, value: Any?) {
        val current = _editingRoute.value ?: return
        _editingRoute.value = when (field) {
            "code" -> current.copy(code = value as String)
            "displayName" -> current.copy(displayName = value as String)
            "serviceAreaId" -> current.copy(serviceAreaId = value as Int)
            "distanceMeters" -> current.copy(distanceMeters = value as? Double)
            "estimatedDurationMinutes" -> current.copy(estimatedDurationMinutes = value as? Int)
            "payoutAmount" -> current.copy(payoutAmount = value as? Int)
            "imageUrl" -> current.copy(imageUrl = value as? String)
            else -> current
        }
    }

    fun addCheckpoint(
        name: String,
        lat: Double,
        lng: Double,
        type: CheckpointType = CheckpointType.PHOTO_POINT,
        instructions: String? = null,
        cameraMode: CameraMode? = null,
        bountyPrompts: List<String> = emptyList(),
    ) {
        val current = _editingCheckpoints.value.toMutableList()
        current.add(
            AdminCheckpointRequest(
                name = name,
                lat = lat,
                lng = lng,
                sequenceOrder = current.size,
                type = type.name.lowercase(),
                instructions = instructions,
                cameraMode = cameraMode?.name?.lowercase(),
                bountyPrompts = bountyPrompts,
            )
        )
        _editingCheckpoints.value = current
    }

    fun updateCheckpoint(
        index: Int,
        name: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        photoRequired: Boolean? = null,
        geofenceRadius: Double? = null,
        type: CheckpointType? = null,
        instructions: String? = null,
        cameraMode: CameraMode? = null,
        bountyPrompts: List<String>? = null,
    ) {
        val current = _editingCheckpoints.value.toMutableList()
        if (index !in current.indices) return
        val cp = current[index]
        current[index] = cp.copy(
            name = name ?: cp.name,
            lat = lat ?: cp.lat,
            lng = lng ?: cp.lng,
            photoRequired = photoRequired ?: cp.photoRequired,
            geofenceRadius = geofenceRadius ?: cp.geofenceRadius,
            type = type?.name?.lowercase() ?: cp.type,
            instructions = if (instructions != null) instructions.ifBlank { null } else cp.instructions,
            cameraMode = if (cameraMode != null) cameraMode.name.lowercase() else if (type != null) cp.cameraMode else cp.cameraMode,
            bountyPrompts = bountyPrompts ?: cp.bountyPrompts,
        )
        _editingCheckpoints.value = current
    }

    /** Clear the camera mode for a checkpoint (when switching to a type that doesn't need it). */
    fun clearCheckpointCameraMode(index: Int) {
        val current = _editingCheckpoints.value.toMutableList()
        if (index !in current.indices) return
        current[index] = current[index].copy(cameraMode = null)
        _editingCheckpoints.value = current
    }

    fun removeCheckpoint(index: Int) {
        val current = _editingCheckpoints.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            // Re-index sequence orders
            _editingCheckpoints.value = current.mapIndexed { i, cp ->
                cp.copy(sequenceOrder = i)
            }
        }
    }

    fun addBountyPrompt(title: String, description: String, pointValue: Int) {
        val current = _editingBountyPrompts.value.toMutableList()
        val routeId = _editingRouteId.value ?: 0
        current.add(
            RouteBountyPrompt(
                id = 0, // server assigns real ID
                routeId = routeId,
                title = title,
                description = description,
                pointValue = pointValue,
                active = true,
            )
        )
        _editingBountyPrompts.value = current
    }

    fun removeBountyPrompt(index: Int) {
        val current = _editingBountyPrompts.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _editingBountyPrompts.value = current
        }
    }

    fun saveRoute() {
        val route = _editingRoute.value ?: return
        val routeWithCheckpoints = route.copy(checkpoints = _editingCheckpoints.value)

        scope.launch {
            _isSaving.value = true
            _error.value = null
            try {
                val routeId = _editingRouteId.value
                if (routeId != null) {
                    api.updateRoute(routeId, routeWithCheckpoints)
                } else {
                    api.createRoute(routeWithCheckpoints)
                }
                _editingRoute.value = null
                _editingRouteId.value = null
                _editingCheckpoints.value = emptyList()
                _editingBountyPrompts.value = emptyList()
                loadRoutes()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to save route"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteRoute(routeId: Int) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.deleteRoute(routeId)
                loadRoutes()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to delete route"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
