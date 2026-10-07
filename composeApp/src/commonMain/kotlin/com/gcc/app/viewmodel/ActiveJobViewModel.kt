package com.gcc.app.viewmodel

import com.gcc.app.platform.LocationTrackingController
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.geo.routePathCoordinates
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

data class ActiveJobUiState(
    val job: Job? = null,
    val route: Route? = null,
    val currentLocation: Coordinates? = null,
    val distanceToRouteMeters: Double? = null,
    val isWithinStartArea: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isStarting: Boolean = false,
    val isCompleting: Boolean = false,
    val isDeclining: Boolean = false,
    val jobStarted: Boolean = false,
    val jobCompleted: Boolean = false,
    val isAdminMode: Boolean = false,
    /** One-shot signal that a decline succeeded and the screen should pop.
     * Consumed via [ActiveJobViewModel.acknowledgeDeclined] from a main-thread
     * LaunchedEffect — navigation must never be driven from the background
     * decline coroutine (Decompose navigation is main-thread-only). */
    val declined: Boolean = false,
)

/** Pure state transition for a successful decline — the screen keys its
 *  main-thread back-navigation off [ActiveJobUiState.declined]. */
internal fun ActiveJobUiState.declineSucceeded(): ActiveJobUiState =
    copy(
        job = null,
        isDeclining = false,
        jobStarted = false,
        jobCompleted = false,
        declined = true,
    )

/**
 * Assignment lifecycle for an accepted job: load, GPS-gated start, decline,
 * and completion. The work itself (both laps) happens in the route-scan flow
 * (RouteScanViewModel) — this ViewModel only handles assignment gating
 * and route proximity.
 */
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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, declined = false)
            try {
                val route = apiClient.getRoute(routeId)

                // Also fetch the job to get its current status
                val job = apiClient.getJob(jobId)

                // The scan flow completes the job server-side; when we come
                // back to a finished job (COMPLETED, or PENDING_REVIEW for a
                // paid job awaiting admin approval), make sure background
                // tracking from startJob is stopped.
                val finished = job.status == JobStatus.COMPLETED || job.status == JobStatus.PENDING_REVIEW
                if (finished) {
                    locationTrackingController.stopTracking()
                }

                _uiState.value = _uiState.value.copy(
                    job = job,
                    route = route,
                    isLoading = false,
                    jobStarted = job.status == JobStatus.IN_PROGRESS,
                    jobCompleted = finished,
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

    fun declineJob() {
        val job = _uiState.value.job ?: return
        scope.launch {
            _uiState.value = _uiState.value.copy(isDeclining = true, error = null)
            try {
                apiClient.declineJob(job.id)
                // Signal the screen to pop via its main-thread LaunchedEffect
                // instead of invoking the navigation callback here: this
                // coroutine runs on Dispatchers.Default, and popping the
                // Decompose stack off the main thread crashes the app.
                _uiState.value = _uiState.value.declineSucceeded()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeclining = false,
                    error = e.message ?: "Failed to decline assignment",
                )
            }
        }
    }

    /** Clears the one-shot [ActiveJobUiState.declined] signal after the screen
     *  has handled the back-navigation, so re-entering the (singleton) screen
     *  does not immediately pop again. */
    fun acknowledgeDeclined() {
        if (_uiState.value.declined) {
            _uiState.value = _uiState.value.copy(declined = false)
        }
    }

    fun updateLocation(location: Coordinates) {
        val state = _uiState.value
        val routeDistance = state.route?.distanceToRouteMeters(location)
        val isWithinStartArea = routeDistance?.let { it <= START_ROUTE_RADIUS_METERS } ?: false

        _uiState.value = state.copy(
            currentLocation = location,
            distanceToRouteMeters = routeDistance,
            isWithinStartArea = isWithinStartArea,
        )
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
