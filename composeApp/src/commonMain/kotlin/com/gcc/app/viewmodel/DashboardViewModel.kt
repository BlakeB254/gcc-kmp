package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.cache.RouteCacheStore
import com.gcc.shared.model.*
import com.gcc.shared.offline.SeedData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class DashboardUiState(
    val routes: List<Route> = emptyList(),
    val assignedJobs: List<Job> = emptyList(),
    val assignedRoutesById: Map<Int, Route> = emptyMap(),
    val blocks: List<Block> = emptyList(),
    val worker: Worker? = null,
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val spots: List<CommunityReport> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val serviceArea: ServiceArea? = null,
    val serviceAreaName: String? = null,
    val decliningAssignedJobIds: Set<Int> = emptySet(),
    val error: String? = null,
)

class DashboardViewModel(
    private val apiClient: GccApiClient,
    private val routeCacheStore: RouteCacheStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun loadDashboard() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val routeRecords = loadRouteRecordsSafely()
                val assignedJobs = loadAssignedJobsSafely()
                val assignedRoutesById = loadAssignedRoutesById(assignedJobs, routeRecords)
                val routes = loadAvailableWorkerRoutes(routeRecords, assignedRoutesById)
                val blocks = loadBlockRecordsSafely(routeRecords)
                _uiState.value = _uiState.value.copy(
                    routes = routes,
                    assignedJobs = assignedJobs,
                    assignedRoutesById = assignedRoutesById,
                    blocks = blocks,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load dashboard",
                )
            }
        }
    }

    fun loadLeaderboard() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val leaderboard = apiClient.getLeaderboard(limit = 10)
                _uiState.value = _uiState.value.copy(
                    leaderboard = leaderboard,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load leaderboard",
                )
            }
        }
    }

    fun refresh() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val routeRecords = loadRouteRecordsSafely()
                val assignedJobs = loadAssignedJobsSafely()
                val assignedRoutesById = loadAssignedRoutesById(assignedJobs, routeRecords)
                val routes = loadAvailableWorkerRoutes(routeRecords, assignedRoutesById)
                val blocks = loadBlockRecordsSafely(routeRecords)
                _uiState.value = _uiState.value.copy(
                    routes = routes,
                    assignedJobs = assignedJobs,
                    assignedRoutesById = assignedRoutesById,
                    blocks = blocks,
                    isRefreshing = false,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun loadServiceArea() {
        scope.launch {
            try {
                val area = apiClient.getMyServiceArea()
                _uiState.value = _uiState.value.copy(
                    serviceArea = area,
                    serviceAreaName = area?.name,
                )
            } catch (_: Exception) { }
        }
    }

    fun loadWorkerProfile() {
        scope.launch {
            try {
                val worker = apiClient.getWorkerProfile()
                _uiState.value = _uiState.value.copy(worker = worker)
            } catch (_: Exception) {
                // Profile loading is non-critical; silently fail
            }
        }
    }

    fun loadSpots() {
        scope.launch {
            try {
                val spots = loadUnifiedSpots()
                _uiState.value = _uiState.value.copy(spots = spots)
            } catch (_: Exception) {
                // Spot loading is non-critical; silently fail
            }
        }
    }

    /**
     * Community reports plus scan-sourced pins from the unified
     * world-observation layer. Reports stay the source of record for their
     * own pins (tap-through to details keeps working); observations
     * contribute the route-scan hotspots that have no backing report, which
     * previously never appeared on the mobile map at all.
     */
    private suspend fun loadUnifiedSpots(): List<CommunityReport> {
        val reports = apiClient.getSpots(limit = 100)
        val scanPins = try {
            apiClient.getMapObservations()
                // sourceType (not communityReportId) is the dedupe key: spot-report
                // observations are already pinned via /api/spots, and sourceType is
                // present in every deployed version of the observations response.
                // Litter domain only: landmark elements (trees, fences…) are
                // world-model context, not trash pins.
                .filter {
                    it.sourceType == "route_scan" && it.state == "dirty" &&
                        (it.domain == null || it.domain == "litter")
                }
                .map { it.toScanMapPin() }
        } catch (_: Exception) {
            emptyList() // observation layer is additive; never block report pins
        }
        return reports + scanPins
    }

    fun declineAssignedJob(jobId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                decliningAssignedJobIds = _uiState.value.decliningAssignedJobIds + jobId,
                error = null,
            )
            try {
                apiClient.declineJob(jobId)
                val routeRecords = loadRouteRecordsSafely()
                val assignedJobs = loadAssignedJobsSafely()
                val assignedRoutesById = loadAssignedRoutesById(assignedJobs, routeRecords)
                _uiState.value = _uiState.value.copy(
                    routes = loadAvailableWorkerRoutes(routeRecords, assignedRoutesById),
                    assignedJobs = assignedJobs,
                    assignedRoutesById = assignedRoutesById,
                    decliningAssignedJobIds = _uiState.value.decliningAssignedJobIds - jobId,
                    blocks = loadBlockRecordsSafely(routeRecords),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    decliningAssignedJobIds = _uiState.value.decliningAssignedJobIds - jobId,
                    error = e.message ?: "Failed to decline route assignment",
                )
            }
        }
    }

    fun loadBlocks() {
        scope.launch {
            val routeRecords = loadRouteRecordsSafely()
            val blocks = loadBlockRecordsSafely(routeRecords)
            _uiState.value = _uiState.value.copy(blocks = blocks)
        }
    }

    /** Load all dashboard data concurrently. */
    fun loadAll() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                coroutineScope {
                    val routeRecordsDeferred = async { loadRouteRecordsSafely() }
                    val assignedJobsDeferred = async { loadAssignedJobsSafely() }
                    val leaderboardDeferred = async { apiClient.getLeaderboard(limit = 10) }
                    val workerDeferred = async {
                        try { apiClient.getWorkerProfile() } catch (_: Exception) { null }
                    }
                    val serviceAreaDeferred = async {
                        try { apiClient.getMyServiceArea() } catch (_: Exception) { null }
                    }
                    val spotsDeferred = async {
                        try { loadUnifiedSpots() } catch (_: Exception) { null }
                    }

                    val routeRecords = routeRecordsDeferred.await()
                    val assignedJobs = assignedJobsDeferred.await()
                    val assignedRoutesById = loadAssignedRoutesById(assignedJobs, routeRecords)
                    val routes = loadAvailableWorkerRoutes(routeRecords, assignedRoutesById)
                    val blocks = loadBlockRecordsSafely(routeRecords)
                    val leaderboard = leaderboardDeferred.await()
                    val worker = workerDeferred.await()
                    val serviceArea = serviceAreaDeferred.await()
                    val spots = spotsDeferred.await()

                    _uiState.value = _uiState.value.copy(
                        routes = routes,
                        assignedJobs = assignedJobs,
                        assignedRoutesById = assignedRoutesById,
                        blocks = blocks,
                        leaderboard = leaderboard,
                        worker = worker,
                        serviceArea = serviceArea,
                        serviceAreaName = serviceArea?.name,
                        spots = spots ?: emptyList(),
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load dashboard",
                )
            }
            }
        }

    private suspend fun loadAssignedJobsSafely(): List<Job> =
        try {
            apiClient.getMyJobs(limit = 20)
                .filter { it.status == JobStatus.CLAIMED || it.status == JobStatus.IN_PROGRESS }
                .filter { it.isVisibleDashboardAssignment() }
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun loadAvailableWorkerRoutes(
        routeRecords: List<Route>,
        assignedRoutesById: Map<Int, Route>,
    ): List<Route> {
        val assignedRouteIds = assignedRoutesById.keys
        val availableRouteIds = try {
            apiClient.getAvailableRouteJobs(limit = 100)
                .filter { (it.payoutAmount ?: 0) > 0 }
                .mapNotNull { it.routeId }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }

        if (availableRouteIds.isEmpty()) return emptyList()

        return routeRecords.filter { route ->
            route.isWorkerAvailableRoute() &&
                route.id in availableRouteIds &&
                route.id !in assignedRouteIds
        }
    }

    private suspend fun loadAssignedRoutesById(
        jobs: List<Job>,
        routeRecords: List<Route>,
    ): Map<Int, Route> {
        if (jobs.isEmpty()) return emptyMap()
        val routeRecordsById = routeRecords.associateBy { it.id }
        return jobs.mapNotNull { job ->
            val routeId = job.routeId ?: return@mapNotNull null
            val route = routeRecordsById[routeId] ?: try {
                apiClient.getRoute(routeId)
            } catch (_: Exception) {
                null
            }
            route?.let { routeId to it }
        }.toMap()
    }

    private suspend fun loadRouteRecordsSafely(): List<Route> {
        val routes = try {
            apiClient.getRoutes()
        } catch (_: Exception) {
            try {
                apiClient.getPublicRoutes()
            } catch (_: Exception) {
                null
            }
        }
        if (routes != null) {
            if (routes.isNotEmpty()) routeCacheStore.replaceAll(routes)
            return routes
        }
        // Both live fetches failed -- fall back to the last successfully
        // cached set, instead of returning an empty list outright.
        return routeCacheStore.getAll()
    }

    private suspend fun loadBlockRecordsSafely(routeRecords: List<Route> = emptyList()): List<Block> {
        val apiBlocks = try {
            apiClient.getAvailableBlocks()
        } catch (_: Exception) {
            emptyList()
        }
        return apiBlocks
            .ifEmpty { routeRecords.map { it.toBlockRecord() } }
            .ifEmpty { SeedData.blocks }
    }
}

// Checkpoints are legacy from the photo-lap era: a route is workable when it
// has anything scannable — a block boundary (scan corridor), a path geometry,
// or (legacy) checkpoints.
private fun Route.isWorkerAvailableRoute(): Boolean =
    status == RouteStatus.PUBLISHED &&
        (blockBoundary != null || geometry != null || checkpoints.size >= 2)

private fun Job.isVisibleDashboardAssignment(
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
): Boolean {
    val deadline = testAssignmentStartDeadlineMillis()
    return deadline == null || status == JobStatus.IN_PROGRESS || deadline > nowMillis
}

private fun Route.toBlockRecord(): Block {
    val centerLng = imageLocationLng ?: geometry?.coordinates?.map { it[0] }?.average()
    val centerLat = imageLocationLat ?: geometry?.coordinates?.map { it[1] }?.average()
    return Block(
        id = id,
        code = code,
        name = displayName,
        serviceAreaId = serviceAreaId,
        center = if (centerLat != null && centerLng != null) {
            GeoJSONPoint(coordinates = listOf(centerLng, centerLat))
        } else null,
        displayImage = imageUrl,
        imageLocationLat = imageLocationLat,
        imageLocationLng = imageLocationLng,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
