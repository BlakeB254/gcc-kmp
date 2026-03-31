package com.gcc.app.viewmodel

import com.gcc.app.platform.LocationTrackingController
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RouteTestUiState(
    val route: Route? = null,
    val checkpoints: List<CheckpointUiItem> = emptyList(),
    val currentLocation: Coordinates? = null,
    val currentCheckpointIndex: Int = 0,
    val isWithinGeofence: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val lap: Int = 1,
    val elapsedSeconds: Long = 0,
    val isRunning: Boolean = false,
    val isLapTransition: Boolean = false,
    val isComplete: Boolean = false,
    val lap1Seconds: Long = 0,
)

class RouteTestViewModel(
    private val apiClient: GccApiClient,
    private val locationTrackingController: LocationTrackingController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(RouteTestUiState())
    val uiState: StateFlow<RouteTestUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    fun onCleared() {
        timerJob?.cancel()
        scope.cancel()
    }

    fun loadRoute(routeId: Int) {
        scope.launch {
            _uiState.value = RouteTestUiState(isLoading = true)
            try {
                val route = apiClient.getRoute(routeId)

                val checkpointItems = route.checkpoints
                    .sortedBy { it.sequenceOrder }
                    .mapIndexed { index, cp ->
                        CheckpointUiItem(
                            checkpoint = cp,
                            status = if (index == 0) CheckpointStatus.CURRENT else CheckpointStatus.UPCOMING,
                        )
                    }

                _uiState.value = RouteTestUiState(
                    route = route,
                    checkpoints = checkpointItems,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = RouteTestUiState(
                    isLoading = false,
                    error = e.message ?: "Failed to load route",
                )
            }
        }
    }

    fun startTest() {
        _uiState.value = _uiState.value.copy(isRunning = true)
        locationTrackingController.startTracking()
        startTimer()
    }

    fun updateLocation(location: Coordinates) {
        val state = _uiState.value
        val currentIndex = state.currentCheckpointIndex
        val checkpoints = state.checkpoints
        if (checkpoints.isEmpty()) return

        val currentCheckpoint = checkpoints.getOrNull(currentIndex)?.checkpoint ?: return
        val distance = GeofenceVerification.distanceMeters(
            location.lat, location.lng,
            currentCheckpoint.coordinates.lat, currentCheckpoint.coordinates.lng,
        )
        val withinGeofence = distance <= currentCheckpoint.geofenceRadius

        val updatedCheckpoints = checkpoints.map { item ->
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

    fun completeCurrentCheckpoint() {
        val state = _uiState.value
        if (!state.isRunning) return
        val currentIndex = state.currentCheckpointIndex
        val checkpoints = state.checkpoints
        if (currentIndex >= checkpoints.size) return

        val updated = checkpoints.mapIndexed { index, item ->
            when {
                index <= currentIndex -> item.copy(status = CheckpointStatus.COMPLETED)
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

        // All checkpoints done for this lap
        if (newIndex >= checkpoints.size) {
            onLapComplete()
        }
    }

    /** Admin shortcut: bypass geofence check */
    fun adminSkipCheckpoint() {
        completeCurrentCheckpoint()
    }

    private fun onLapComplete() {
        val state = _uiState.value
        if (state.lap == 1) {
            // Pause for lap transition
            _uiState.value = state.copy(
                isLapTransition = true,
                lap1Seconds = state.elapsedSeconds,
            )
        } else {
            // Lap 2 complete — test finished
            stopTimer()
            locationTrackingController.stopTracking()
            _uiState.value = state.copy(
                isRunning = false,
                isComplete = true,
            )
        }
    }

    fun startLap2() {
        val state = _uiState.value
        val resetCheckpoints = state.checkpoints.mapIndexed { index, item ->
            item.copy(
                status = if (index == 0) CheckpointStatus.CURRENT else CheckpointStatus.UPCOMING,
                distanceMeters = null,
            )
        }
        _uiState.value = state.copy(
            lap = 2,
            currentCheckpointIndex = 0,
            checkpoints = resetCheckpoints,
            isLapTransition = false,
            isWithinGeofence = false,
        )
    }

    fun resetTest() {
        stopTimer()
        locationTrackingController.stopTracking()
        val route = _uiState.value.route ?: return
        val checkpointItems = route.checkpoints
            .sortedBy { it.sequenceOrder }
            .mapIndexed { index, cp ->
                CheckpointUiItem(
                    checkpoint = cp,
                    status = if (index == 0) CheckpointStatus.CURRENT else CheckpointStatus.UPCOMING,
                )
            }
        _uiState.value = RouteTestUiState(
            route = route,
            checkpoints = checkpointItems,
            isLoading = false,
        )
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                val state = _uiState.value
                if (state.isRunning && !state.isLapTransition) {
                    _uiState.value = state.copy(elapsedSeconds = state.elapsedSeconds + 1)
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun cleanup() {
        stopTimer()
        locationTrackingController.stopTracking()
    }
}
