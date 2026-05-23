package com.gcc.app.viewmodel

import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.triggerHapticFeedback
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.geo.CheckpointGenerator
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
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
    val distanceToRouteMeters: Double? = null,
    val isWithinStartArea: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isStarting: Boolean = false,
    val isCompleting: Boolean = false,
    val isUploadingEvidence: Boolean = false,
    val isDeclining: Boolean = false,
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

    private companion object {
        const val START_ROUTE_RADIUS_METERS = 35.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }

    fun loadJob(jobId: Int, routeId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val route = apiClient.getRoute(routeId)

                // Also fetch the job to get its current status
                val job = apiClient.getJob(jobId)

                val checkpointItems = CheckpointGenerator.generateIfMissing(route)
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
                val state = _uiState.value
                val rotatedCheckpoints = reorderCheckpointsForStart(
                    checkpoints = state.checkpoints,
                    location = state.currentLocation,
                )
                _uiState.value = _uiState.value.copy(
                    job = updatedJob,
                    checkpoints = rotatedCheckpoints,
                    currentCheckpointIndex = 0,
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

    fun declineJob(onDeclined: () -> Unit = {}) {
        val job = _uiState.value.job ?: return
        scope.launch {
            _uiState.value = _uiState.value.copy(isDeclining = true, error = null)
            try {
                apiClient.declineJob(job.id)
                _uiState.value = _uiState.value.copy(
                    job = null,
                    isDeclining = false,
                    jobStarted = false,
                    jobCompleted = false,
                )
                onDeclined()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeclining = false,
                    error = e.message ?: "Failed to decline assignment",
                )
            }
        }
    }

    fun submitCheckpointEvidence(
        checkpointId: Int?,
        beforePhotoBytes: ByteArray,
        afterPhotoBytes: ByteArray,
    ) {
        val job = _uiState.value.job ?: return
        scope.launch {
            _uiState.value = _uiState.value.copy(isUploadingEvidence = true, error = null)
            try {
                val urls = apiClient.uploadSpotPhotos(listOf(beforePhotoBytes, afterPhotoBytes))
                if (urls.size < 2) {
                    throw IllegalStateException("Both before and after photos are required")
                }
                val updatedJob = apiClient.submitJobEvidence(
                    job.id,
                    JobEvidenceRequest(
                        checkpointId = checkpointId,
                        beforePhotoUrl = urls[0],
                        afterPhotoUrl = urls[1],
                    ),
                )
                _uiState.value = _uiState.value.copy(
                    job = updatedJob,
                    isUploadingEvidence = false,
                )
                completeCurrentCheckpoint()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingEvidence = false,
                    error = e.message ?: "Failed to upload checkpoint photos",
                )
            }
        }
    }

    fun updateLocation(location: Coordinates) {
        val state = _uiState.value
        val currentIndex = state.currentCheckpointIndex
        val checkpoints = state.checkpoints
        val routeDistance = state.route?.distanceToRouteMeters(location)
        val isWithinStartArea = routeDistance?.let { it <= START_ROUTE_RADIUS_METERS } ?: false

        if (checkpoints.isEmpty() || currentIndex >= checkpoints.size) {
            _uiState.value = state.copy(
                currentLocation = location,
                distanceToRouteMeters = routeDistance,
                isWithinStartArea = isWithinStartArea,
            )
            return
        }

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
            distanceToRouteMeters = routeDistance,
            isWithinStartArea = isWithinStartArea,
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

    private fun reorderCheckpointsForStart(
        checkpoints: List<CheckpointUiItem>,
        location: Coordinates?,
    ): List<CheckpointUiItem> {
        if (checkpoints.size <= 1 || location == null) {
            return checkpoints.mapIndexed { index, item ->
                item.copy(status = if (index == 0) CheckpointStatus.CURRENT else CheckpointStatus.UPCOMING)
            }
        }

        val nearestIndex = checkpoints.indices.minByOrNull { index ->
            val coordinates = checkpoints[index].checkpoint.coordinates
            GeofenceVerification.distanceMeters(
                location.lat,
                location.lng,
                coordinates.lat,
                coordinates.lng,
            )
        } ?: 0

        val reordered = checkpoints.drop(nearestIndex) + checkpoints.take(nearestIndex)
        return reordered.mapIndexed { index, item ->
            val coordinates = item.checkpoint.coordinates
            item.copy(
                status = if (index == 0) CheckpointStatus.CURRENT else CheckpointStatus.UPCOMING,
                distanceMeters = GeofenceVerification.distanceMeters(
                    location.lat,
                    location.lng,
                    coordinates.lat,
                    coordinates.lng,
                ),
            )
        }
    }

    private fun Route.distanceToRouteMeters(location: Coordinates): Double? {
        val path = routePathCoordinates()
        if (path.isEmpty()) return null
        if (path.size == 1) {
            return GeofenceVerification.distanceMeters(
                location.lat,
                location.lng,
                path.first().lat,
                path.first().lng,
            )
        }

        return path.zipWithNext().minOfOrNull { (from, to) ->
            distanceToSegmentMeters(location, from, to)
        }
    }

    private fun Route.routePathCoordinates(): List<Coordinates> =
        geometry
            ?.toCoordinates()
            .orEmpty()
            .withoutClosingDuplicate()
            .ifEmpty {
                checkpoints
                    .sortedBy { it.sequenceOrder }
                    .map { it.coordinates }
                    .withoutClosingDuplicate()
            }

    private fun List<Coordinates>.withoutClosingDuplicate(): List<Coordinates> {
        if (size < 2) return this
        val first = first()
        val last = last()
        val closesLoop = GeofenceVerification.distanceMeters(
            first.lat,
            first.lng,
            last.lat,
            last.lng,
        ) < 2.0
        return if (closesLoop) dropLast(1) else this
    }

    private fun distanceToSegmentMeters(
        point: Coordinates,
        from: Coordinates,
        to: Coordinates,
    ): Double {
        val latScale = cos(point.lat.toRadians())
        val ax = (from.lng - point.lng).toRadians() * EARTH_RADIUS_METERS * latScale
        val ay = (from.lat - point.lat).toRadians() * EARTH_RADIUS_METERS
        val bx = (to.lng - point.lng).toRadians() * EARTH_RADIUS_METERS * latScale
        val by = (to.lat - point.lat).toRadians() * EARTH_RADIUS_METERS

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0001) {
            return sqrt(ax * ax + ay * ay)
        }

        val projection = (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val closestX = ax + projection * dx
        val closestY = ay + projection * dy
        return sqrt(closestX * closestX + closestY * closestY)
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

}
