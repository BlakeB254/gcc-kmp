package com.gcc.app.viewmodel

import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.triggerHapticFeedback
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val isAdminMode: Boolean = false,
)

class ActiveJobViewModel(
    private val apiClient: GccApiClient,
    private val locationTrackingController: LocationTrackingController,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ActiveJobUiState(isAdminMode = tokenManager.isAdmin()))
    val uiState: StateFlow<ActiveJobUiState> = _uiState.asStateFlow()

    fun loadJob(jobId: Int, routeId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val route = apiClient.getRoute(routeId)

                // Also fetch the job to get its current status
                val job = apiClient.getJob(jobId)

                val checkpointItems = route.checkpoints
                    .sortedBy { it.sequenceOrder }
                    .mapIndexed { index, cp ->
                        CheckpointUiItem(
                            checkpoint = cp,
                            status = if (index == 0) CheckpointStatus.CURRENT else CheckpointStatus.UPCOMING,
                        )
                    }

                _uiState.value = _uiState.value.copy(
                    job = job,
                    route = route,
                    checkpoints = checkpointItems,
                    isLoading = false,
                    jobStarted = job.status == JobStatus.IN_PROGRESS,
                    jobCompleted = job.status == JobStatus.COMPLETED,
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
                val updatedJob = apiClient.startJob(job.id)
                locationTrackingController.startTracking()
                _uiState.value = _uiState.value.copy(
                    job = updatedJob,
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
                val updatedJob = apiClient.completeJob(job.id)
                locationTrackingController.stopTracking()
                _uiState.value = _uiState.value.copy(
                    job = updatedJob,
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
        val distance = GeofenceVerification.distanceMeters(
            location.lat, location.lng,
            currentCheckpoint.coordinates.lat, currentCheckpoint.coordinates.lng,
        )
        val withinGeofence = distance <= currentCheckpoint.geofenceRadius

        val updatedCheckpoints = checkpoints.mapIndexed { index, item ->
            val cpDistance = GeofenceVerification.distanceMeters(
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

    /** Admin shortcut: mark all checkpoints done and complete the job. */
    fun adminCompleteAll() {
        val state = _uiState.value
        if (!state.isAdminMode) return
        val allCompleted = state.checkpoints.map { it.copy(status = CheckpointStatus.COMPLETED) }
        _uiState.value = state.copy(
            checkpoints = allCompleted,
            currentCheckpointIndex = allCompleted.size,
        )
        completeJob()
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
        triggerHapticFeedback()
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

}
