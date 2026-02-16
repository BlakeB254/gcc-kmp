package com.gcc.app.viewmodel

import com.gcc.app.platform.LocationTrackingController
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
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class CheckpointStatus { COMPLETED, CURRENT, UPCOMING }

data class CheckpointUiItem(
    val checkpoint: RouteCheckpoint,
    val status: CheckpointStatus,
    val distanceMeters: Double? = null,
)

data class ActiveJobUiState(
    val job: Job? = null,
    val route: Route? = null,
    val checkpoints: List<CheckpointUiItem> = emptyList(),
    val currentLocation: Coordinates? = null,
    val currentCheckpointIndex: Int = 0,
    val isWithinGeofence: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isStarting: Boolean = false,
    val isCompleting: Boolean = false,
    val jobStarted: Boolean = false,
    val jobCompleted: Boolean = false,
)

class ActiveJobViewModel(
    private val apiClient: GccApiClient,
    private val locationTrackingController: LocationTrackingController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ActiveJobUiState())
    val uiState: StateFlow<ActiveJobUiState> = _uiState.asStateFlow()

    fun loadJob(jobId: Int, routeId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val routeResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/routes/$routeId")
                val route = routeResponse.body<ApiResponse<Route>>()

                // Also fetch the job to get its current status
                val jobResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/jobs/$jobId")
                val job = jobResponse.body<ApiResponse<Job>>()

                val checkpointItems = route.data.checkpoints
                    .sortedBy { it.sequenceOrder }
                    .mapIndexed { index, cp ->
                        CheckpointUiItem(
                            checkpoint = cp,
                            status = if (index == 0) CheckpointStatus.CURRENT else CheckpointStatus.UPCOMING,
                        )
                    }

                _uiState.value = _uiState.value.copy(
                    job = job.data,
                    route = route.data,
                    checkpoints = checkpointItems,
                    isLoading = false,
                    jobStarted = job.data.status.name == "IN_PROGRESS",
                    jobCompleted = job.data.status.name == "COMPLETED",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load job",
                )
            }
        }
    }

    fun startJob() {
        val job = _uiState.value.job ?: return
        scope.launch {
            _uiState.value = _uiState.value.copy(isStarting = true, error = null)
            try {
                val response = apiClient.httpClient.patch("${apiClient.baseUrl}/api/jobs/${job.id}/start")
                val updatedJob = response.body<ApiResponse<Job>>()
                locationTrackingController.startTracking()
                _uiState.value = _uiState.value.copy(
                    job = updatedJob.data,
                    isStarting = false,
                    jobStarted = true,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isStarting = false,
                    error = e.message ?: "Failed to start job",
                )
            }
        }
    }

    fun completeJob() {
        val job = _uiState.value.job ?: return
        scope.launch {
            _uiState.value = _uiState.value.copy(isCompleting = true, error = null)
            try {
                val response = apiClient.httpClient.patch("${apiClient.baseUrl}/api/jobs/${job.id}/complete")
                val updatedJob = response.body<ApiResponse<Job>>()
                locationTrackingController.stopTracking()
                _uiState.value = _uiState.value.copy(
                    job = updatedJob.data,
                    isCompleting = false,
                    jobCompleted = true,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCompleting = false,
                    error = e.message ?: "Failed to complete job",
                )
            }
        }
    }

    fun updateLocation(location: Coordinates) {
        val state = _uiState.value
        val currentIndex = state.currentCheckpointIndex
        val checkpoints = state.checkpoints
        if (checkpoints.isEmpty()) return

        val currentCheckpoint = checkpoints[currentIndex].checkpoint
        val distance = haversineDistance(
            location.lat, location.lng,
            currentCheckpoint.coordinates.lat, currentCheckpoint.coordinates.lng,
        )
        val withinGeofence = distance <= currentCheckpoint.geofenceRadius

        val updatedCheckpoints = checkpoints.mapIndexed { index, item ->
            val cpDistance = haversineDistance(
                location.lat, location.lng,
                item.checkpoint.coordinates.lat, item.checkpoint.coordinates.lng,
            )
            item.copy(distanceMeters = cpDistance)
        }

        _uiState.value = state.copy(
            currentLocation = location,
            isWithinGeofence = withinGeofence,
            checkpoints = updatedCheckpoints,
        )
    }

    fun completeCurrentCheckpoint() {
        val state = _uiState.value
        val currentIndex = state.currentCheckpointIndex
        val checkpoints = state.checkpoints
        if (currentIndex >= checkpoints.size) return

        val updated = checkpoints.mapIndexed { index, item ->
            when {
                index < currentIndex -> item.copy(status = CheckpointStatus.COMPLETED)
                index == currentIndex -> item.copy(status = CheckpointStatus.COMPLETED)
                index == currentIndex + 1 -> item.copy(status = CheckpointStatus.CURRENT)
                else -> item
            }
        }

        val newIndex = currentIndex + 1
        _uiState.value = state.copy(
            currentCheckpointIndex = newIndex,
            checkpoints = updated,
            isWithinGeofence = false,
        )

        // Auto-complete job when all checkpoints are done
        if (newIndex >= checkpoints.size) {
            completeJob()
        }
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0
        private fun Double.toRad(): Double = this * kotlin.math.PI / 180.0

        /** Haversine distance in meters between two lat/lng points */
        fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = (lat2 - lat1).toRad()
            val dLng = (lng2 - lng1).toRad()
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1.toRad()) * cos(lat2.toRad()) *
                sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_METERS * c
        }
    }
}
