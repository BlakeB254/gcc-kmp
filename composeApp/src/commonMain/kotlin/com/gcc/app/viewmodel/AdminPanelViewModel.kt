package com.gcc.app.viewmodel

import com.gcc.app.platform.CapturedPhoto
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AdminTab(val label: String, val icon: String) {
    OVERVIEW("Overview", "\uD83D\uDCCA"),
    WORKERS("Workers", "\uD83D\uDC77"),
    ROUTES("Routes", "\uD83D\uDDFA"),
    BOUNTIES("Bounties", "\uD83C\uDFAF"),
    SPOTS("Spots", "\uD83D\uDCCD"),
}

data class AdminPanelUiState(
    val selectedTab: AdminTab = AdminTab.OVERVIEW,
    val isLoading: Boolean = true,
    val error: String? = null,
    val stats: AdminStatsResponse? = null,
    val workers: List<Worker> = emptyList(),
    val routes: List<Route> = emptyList(),
    val bounties: List<Bounty> = emptyList(),
    val spotReports: List<CommunityReport> = emptyList(),
)

class AdminPanelViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(AdminPanelUiState())
    val uiState: StateFlow<AdminPanelUiState> = _uiState.asStateFlow()

    init {
        refreshCurrentTab()
    }

    fun selectTab(tab: AdminTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
        refreshCurrentTab()
    }

    fun refreshCurrentTab() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                when (_uiState.value.selectedTab) {
                    AdminTab.OVERVIEW -> {
                        val stats = apiClient.getAdminStats()
                        _uiState.value = _uiState.value.copy(stats = stats)
                    }
                    AdminTab.WORKERS -> {
                        val workers = apiClient.getAdminWorkers()
                        _uiState.value = _uiState.value.copy(workers = workers)
                    }
                    AdminTab.ROUTES -> {
                        val routes = apiClient.getAdminRoutes()
                        _uiState.value = _uiState.value.copy(routes = routes)
                    }
                    AdminTab.BOUNTIES -> {
                        val bounties = apiClient.getAdminBounties()
                        _uiState.value = _uiState.value.copy(bounties = bounties)
                    }
                    AdminTab.SPOTS -> {
                        val spots = apiClient.getAdminSpotReports()
                        _uiState.value = _uiState.value.copy(spotReports = spots)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = when {
                        e.message?.contains("401") == true || e.message?.contains("403") == true ->
                            "Not authorized. Try signing out and back in."
                        e.message?.contains("404") == true ->
                            "Endpoint not found. The server may need updating."
                        e.message?.contains("500") == true ->
                            "Server error. Please try again later."
                        else -> e.message ?: "Failed to load data"
                    },
                )
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun updateRouteStatus(routeId: Int, newStatus: String) {
        scope.launch {
            try {
                val updated = apiClient.updateRouteStatus(routeId, newStatus)
                _uiState.value = _uiState.value.copy(
                    routes = _uiState.value.routes.map { if (it.id == routeId) updated else it },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to update status: ${e.message}")
            }
        }
    }

    fun updateRouteImage(routeId: Int, photoBytes: ByteArray) {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(error = null)
                val urls = apiClient.uploadSpotPhotos(listOf(photoBytes))
                if (urls.isNotEmpty()) {
                    val updated = apiClient.updateRouteImage(routeId, urls.first())
                    _uiState.value = _uiState.value.copy(
                        routes = _uiState.value.routes.map { if (it.id == routeId) updated else it },
                    )
                } else {
                    _uiState.value = _uiState.value.copy(error = "Photo upload returned no URLs")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to upload image: ${e.message}",
                )
            }
        }
    }

    fun removeRouteImage(routeId: Int) {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(error = null)
                val updated = apiClient.updateRouteImage(routeId, null)
                _uiState.value = _uiState.value.copy(
                    routes = _uiState.value.routes.map { if (it.id == routeId) updated else it },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to remove image: ${e.message}",
                )
            }
        }
    }

    fun updateRouteNameDesc(routeId: Int, displayName: String, description: String?) {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(error = null)
                val updated = apiClient.updateRouteNameDesc(routeId, displayName, description)
                _uiState.value = _uiState.value.copy(
                    routes = _uiState.value.routes.map { if (it.id == routeId) updated else it },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to update name: ${e.message}",
                )
            }
        }
    }

    fun updateRouteImageLocation(routeId: Int, lat: Double?, lng: Double?) {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(error = null)
                val updated = apiClient.updateRouteImageLocation(routeId, lat, lng)
                _uiState.value = _uiState.value.copy(
                    routes = _uiState.value.routes.map { if (it.id == routeId) updated else it },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to update image location: ${e.message}",
                )
            }
        }
    }

    fun updateSpotStatus(spotId: Int, newStatus: String) {
        scope.launch {
            try {
                apiClient.updateSpotStatus(spotId, newStatus)
                val updated = apiClient.getAdminSpotReports()
                _uiState.value = _uiState.value.copy(spotReports = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to update spot: ${e.message}")
            }
        }
    }

    fun deleteSpot(spotId: Int) {
        scope.launch {
            try {
                apiClient.deleteSpot(spotId)
                _uiState.value = _uiState.value.copy(
                    spotReports = _uiState.value.spotReports.filter { it.id != spotId },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to delete spot: ${e.message}")
            }
        }
    }

    fun createSpot(request: CreateSpotRequest, photos: List<CapturedPhoto>) {
        scope.launch {
            try {
                val photoBytes = photos.map { it.bytes }
                val uploadedUrls = if (photoBytes.isNotEmpty()) apiClient.uploadSpotPhotos(photoBytes) else emptyList()
                val requestWithPhotos = request.copy(
                    mediaUrls = uploadedUrls.ifEmpty { null },
                )
                apiClient.createAdminSpot(requestWithPhotos)
                // Refresh spots list
                val updated = apiClient.getAdminSpotReports()
                _uiState.value = _uiState.value.copy(spotReports = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to create spot report",
                )
            }
        }
    }

    fun addSpotPhotos(spotId: Int, photoBytes: List<ByteArray>) {
        scope.launch {
            try {
                val urls = apiClient.uploadSpotPhotos(photoBytes)
                if (urls.isNotEmpty()) {
                    apiClient.addSpotPhotos(spotId, urls)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to upload photos: ${e.message}")
            }
        }
    }

    fun addCheckpoint(routeId: Int, request: AdminCheckpointRequest, onAdded: (RouteCheckpoint) -> Unit) {
        scope.launch {
            try {
                val cp = apiClient.addCheckpoint(routeId, request)
                onAdded(cp)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to add checkpoint: ${e.message}")
            }
        }
    }

    // ─── Worker Verification ────────────────────────────────────────

    private val _selectedWorker = MutableStateFlow<Worker?>(null)
    val selectedWorker: StateFlow<Worker?> = _selectedWorker.asStateFlow()

    private val _workerDocuments = MutableStateFlow<WorkerDocuments?>(null)
    val workerDocuments: StateFlow<WorkerDocuments?> = _workerDocuments.asStateFlow()

    private val _workerActionLoading = MutableStateFlow(false)
    val workerActionLoading: StateFlow<Boolean> = _workerActionLoading.asStateFlow()

    fun loadWorkerDetail(workerId: Int) {
        scope.launch {
            _workerActionLoading.value = true
            try {
                val worker = apiClient.getAdminWorker(workerId)
                _selectedWorker.value = worker
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to load worker: ${e.message}")
            }
            _workerActionLoading.value = false
            // Load documents in parallel (non-blocking)
            try {
                _workerDocuments.value = apiClient.getWorkerDocuments(workerId)
            } catch (_: Exception) {
                // Documents are optional — don't block the UI
                _workerDocuments.value = null
            }
        }
    }

    fun verifyWorker(
        workerId: Int,
        status: VerificationStatus,
        governmentIdStatus: DocumentStatus? = null,
        proofOfAddressStatus: DocumentStatus? = null,
        reviewNotes: String? = null,
    ) {
        scope.launch {
            _workerActionLoading.value = true
            try {
                val request = VerifyWorkerRequest(
                    status = status,
                    governmentIdStatus = governmentIdStatus,
                    proofOfAddressStatus = proofOfAddressStatus,
                    reviewNotes = reviewNotes,
                )
                val updated = apiClient.verifyWorker(workerId, request)
                _selectedWorker.value = updated
                // Also update the worker in the list
                _uiState.value = _uiState.value.copy(
                    workers = _uiState.value.workers.map { if (it.id == workerId) updated else it },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to verify worker: ${e.message}")
            }
            _workerActionLoading.value = false
        }
    }

    fun deleteWorker(workerId: Int, onDeleted: () -> Unit) {
        scope.launch {
            _workerActionLoading.value = true
            try {
                apiClient.deleteWorker(workerId)
                _uiState.value = _uiState.value.copy(
                    workers = _uiState.value.workers.filter { it.id != workerId },
                )
                onDeleted()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to delete worker: ${e.message}")
            }
            _workerActionLoading.value = false
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
