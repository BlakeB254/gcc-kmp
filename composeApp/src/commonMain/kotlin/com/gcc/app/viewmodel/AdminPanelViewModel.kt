package com.gcc.app.viewmodel

import com.gcc.app.platform.CapturedPhoto
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.api.PendingReview
import com.gcc.shared.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdminTab(val label: String, val icon: String) {
    OVERVIEW("Overview", "\uD83D\uDCCA"),
    WORKERS("Workers", "\uD83D\uDC77"),
    ROUTES("Routes", "\uD83D\uDDFA"),
    BOUNTIES("Bounties", "\uD83C\uDFAF"),
    SPOTS("Spots", "\uD83D\uDCCD"),
    PAYOUTS("Payouts", "\uD83D\uDCB0"),
    BLOCK_PROPOSALS("Block Proposals", "\uD83E\uDDF1"),
}

data class AdminPanelUiState(
    val selectedTab: AdminTab = AdminTab.OVERVIEW,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val stats: AdminStatsResponse? = null,
    val recentActivity: List<AdminActivityItem> = emptyList(),
    val dailyStats: List<AdminDailyStats> = emptyList(),
    val currentWorker: Worker? = null,
    val workers: List<Worker> = emptyList(),
    val routes: List<Route> = emptyList(),
    val updatingRouteStatusIds: Set<Int> = emptySet(),
    val routeStatusNotice: String? = null,
    val routeStatusActionError: String? = null,
    val routeAssignments: Map<Int, List<AdminRouteAssignment>> = emptyMap(),
    val loadingRouteAssignmentIds: Set<Int> = emptySet(),
    val cancellingRouteAssignmentJobIds: Set<Int> = emptySet(),
    val bounties: List<Bounty> = emptyList(),
    val spotReports: List<CommunityReport> = emptyList(),
    val loadedTabs: Set<AdminTab> = emptySet(),
    val isCreatingSpot: Boolean = false,
    val spotCreateError: String? = null,
    val spotCreateSuccessNonce: Int = 0,
    val verifiedWorkers: List<Worker> = emptyList(),
    val selectedProxyWorkerId: Int? = null,
    val pendingReviews: List<PendingReview> = emptyList(),
    val payoutActionError: String? = null,
    // Ad-hoc payouts: paper checks waiting to be mailed + recent history
    val pendingChecks: List<AdminPayout> = emptyList(),
    val recentPayouts: List<AdminPayout> = emptyList(),
    val blockProposals: List<BlockProposal> = emptyList(),
    val blockProposalActionError: String? = null,
)

class AdminPanelViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(AdminPanelUiState())
    val uiState: StateFlow<AdminPanelUiState> = _uiState.asStateFlow()
    private var currentLoadingJob: Job? = null

    init {
        refreshCurrentTab()
    }

    fun selectTab(tab: AdminTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab !in _uiState.value.loadedTabs) {
            refreshCurrentTab()
        }
    }

    fun refreshCurrentTab() {
        loadTabData(isRefresh = false)
    }

    fun forceRefreshCurrentTab() {
        loadTabData(isRefresh = true)
    }

    fun clearSpotCreateError() {
        _uiState.update { it.copy(spotCreateError = null) }
    }

    /**
     * Ensure the workers list is loaded without changing the selected tab.
     * Used by the spot-create attribution picker so the admin doesn't have
     * to visit the Workers tab first before they can pick a discoverer.
     */
    fun ensureWorkersLoaded() {
        if (_uiState.value.workers.isNotEmpty()) return
        if (AdminTab.WORKERS in _uiState.value.loadedTabs) return
        scope.launch {
            try {
                val workers = apiClient.getAllAdminWorkers()
                _uiState.update { it.copy(workers = workers, loadedTabs = it.loadedTabs + AdminTab.WORKERS) }
            } catch (_: Exception) {
                // Don't surface a global error — picker UI handles empty/error
                // state inline; failure here just means the admin sees the
                // "no workers loaded" message until they retry.
            }
        }
    }

    /**
     * Ensure verified (approved) workers are loaded for the proxy-execution
     * dropdown. Mirrors [ensureWorkersLoaded] but filters by verificationStatus.
     */
    fun ensureVerifiedWorkersLoaded() {
        if (_uiState.value.verifiedWorkers.isNotEmpty()) return
        scope.launch {
            try {
                val workers = apiClient.getAllAdminWorkers(verificationStatus = "approved")
                _uiState.update { it.copy(verifiedWorkers = workers) }
            } catch (_: Exception) {
                // Don't surface a global error — dropdown handles empty state inline
            }
        }
    }

    fun setSelectedProxyWorkerId(id: Int?) {
        _uiState.update { it.copy(selectedProxyWorkerId = id) }
    }

    fun ensureCurrentWorkerLoaded() {
        if (_uiState.value.currentWorker != null) return
        scope.launch {
            try {
                val worker = apiClient.getWorkerProfile()
                _uiState.update { it.copy(currentWorker = worker) }
            } catch (_: Exception) {
                // Admin-only sessions may not carry a worker profile. The
                // route detail screen falls back to the regular worker picker.
            }
        }
    }

    private fun invalidateTab(tab: AdminTab) {
        _uiState.update { it.copy(loadedTabs = it.loadedTabs - tab) }
    }

    private fun loadTabData(isRefresh: Boolean) {
        currentLoadingJob?.cancel()
        currentLoadingJob = scope.launch {
            val tab = _uiState.value.selectedTab
            _uiState.update {
                if (isRefresh) it.copy(isRefreshing = true, error = null) else it.copy(isLoading = true, error = null)
            }
            try {
                when (tab) {
                    AdminTab.OVERVIEW -> {
                        val stats = apiClient.getAdminStats()
                        val activity = try { apiClient.getAdminActivity(limit = 10) } catch (_: Exception) { emptyList() }
                        val daily = try { apiClient.getAdminDailyStats(days = 7) } catch (_: Exception) { emptyList() }
                        _uiState.update { it.copy(stats = stats, recentActivity = activity, dailyStats = daily) }
                    }
                    AdminTab.WORKERS -> {
                        val workers = apiClient.getAllAdminWorkers()
                        _uiState.update { it.copy(workers = workers) }
                    }
                    AdminTab.ROUTES -> {
                        val routes = apiClient.getAdminRoutes()
                        _uiState.update { it.applyRoutesRefresh(routes) }
                    }
                    AdminTab.BOUNTIES -> {
                        // Bounties table may not exist in production — gracefully degrade
                        val bounties = try { apiClient.getAdminBounties() } catch (_: Exception) { emptyList() }
                        _uiState.update { it.copy(bounties = bounties) }
                    }
                    AdminTab.SPOTS -> {
                        val spots = apiClient.getAdminSpotReports()
                        _uiState.update { it.copy(spotReports = spots) }
                    }
                    AdminTab.PAYOUTS -> {
                        val reviews = apiClient.getPendingPayoutReviews()
                        // Ad-hoc payout endpoints may not be deployed yet — degrade
                        // to the job-approval queue alone (bounties-tab pattern).
                        val checks = try {
                            apiClient.getAdminPayouts(status = "check_pending").payouts
                        } catch (_: Exception) { emptyList() }
                        val recent = try {
                            apiClient.getAdminPayouts(limit = 20).payouts
                        } catch (_: Exception) { emptyList() }
                        _uiState.update {
                            it.copy(
                                pendingReviews = reviews,
                                pendingChecks = checks,
                                recentPayouts = recent,
                            )
                        }
                    }
                    AdminTab.BLOCK_PROPOSALS -> {
                        val proposals = apiClient.getAdminBlockProposals(status = BlockProposalStatus.PENDING)
                        _uiState.update { it.copy(blockProposals = proposals) }
                    }
                }
                _uiState.update { it.copy(loadedTabs = it.loadedTabs + tab) }
            } catch (e: Exception) {
                val message = when {
                    e.message?.contains("401") == true || e.message?.contains("403") == true ->
                        "Not authorized. Try signing out and back in."
                    e.message?.contains("404") == true ->
                        "Endpoint not found. The server may need updating."
                    e.message?.contains("500") == true ->
                        "Server error. Please try again later."
                    else -> e.message ?: "Failed to load data"
                }
                _uiState.update { it.copy(error = message) }
            }
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
        }
    }

    fun updateRouteStatus(
        routeId: Int,
        newStatus: RouteStatus,
        successNotice: String,
        onResult: (success: Boolean, error: String?) -> Unit = { _, _ -> },
    ) {
        while (true) {
            val current = _uiState.value
            val started = current.startRouteStatusUpdate(routeId) ?: return
            if (_uiState.compareAndSet(current, started)) break
        }

        scope.launch {
            val updateResult = try {
                Result.success(apiClient.updateRouteStatus(routeId, newStatus))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            val callbackResult = updateResult.fold(
                onSuccess = { updated ->
                    _uiState.update { it.completeRouteStatusUpdate(routeId, updated, successNotice) }
                    true to null
                },
                onFailure = { error ->
                    val missing = error.message?.contains("404") == true
                    val message = if (missing) {
                        "Route no longer exists"
                    } else {
                        error.message ?: "Failed to update route status"
                    }
                    _uiState.update { it.failRouteStatusUpdate(routeId, message) }
                    if (missing) forceRefreshCurrentTab()
                    false to message
                },
            )
            onResult(callbackResult.first, callbackResult.second)
        }
    }

    fun clearRouteStatusFeedback() {
        _uiState.update { it.copy(routeStatusNotice = null, routeStatusActionError = null) }
    }

    fun updateRouteImage(routeId: Int, photoBytes: ByteArray) {
        scope.launch {
            try {
                _uiState.update { it.copy(error = null) }
                val urls = apiClient.uploadSpotPhotos(listOf(photoBytes))
                if (urls.isNotEmpty()) {
                    val updated = apiClient.updateRouteImage(routeId, urls.first())
                    _uiState.update { state ->
                        state.copy(routes = state.routes.map { route -> if (route.id == routeId) updated else route })
                    }
                } else {
                    _uiState.update { it.copy(error = "Photo upload returned no URLs") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to upload image: ${e.message}") }
            }
        }
    }

    fun removeRouteImage(routeId: Int) {
        scope.launch {
            try {
                _uiState.update { it.copy(error = null) }
                val updated = apiClient.updateRouteImage(routeId, null)
                _uiState.update { state ->
                    state.copy(routes = state.routes.map { route -> if (route.id == routeId) updated else route })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to remove image: ${e.message}") }
            }
        }
    }

    fun updateRouteNameDesc(routeId: Int, displayName: String, description: String?) {
        scope.launch {
            try {
                _uiState.update { it.copy(error = null) }
                val updated = apiClient.updateRouteNameDesc(routeId, displayName, description)
                _uiState.update { state ->
                    state.copy(routes = state.routes.map { route -> if (route.id == routeId) updated else route })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to update name: ${e.message}") }
            }
        }
    }

    fun updateRouteImageLocation(routeId: Int, lat: Double?, lng: Double?) {
        scope.launch {
            try {
                _uiState.update { it.copy(error = null) }
                val updated = apiClient.updateRouteImageLocation(routeId, lat, lng)
                _uiState.update { state ->
                    state.copy(routes = state.routes.map { route -> if (route.id == routeId) updated else route })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to update image location: ${e.message}") }
            }
        }
    }

    fun updateSpotStatus(spotId: Int, newStatus: String) {
        scope.launch {
            try {
                apiClient.updateSpotStatus(spotId, newStatus)
                val updated = apiClient.getAdminSpotReports()
                _uiState.update { it.copy(spotReports = updated) }
                invalidateTab(AdminTab.OVERVIEW)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to update spot: ${e.message}") }
            }
        }
    }

    fun deleteSpot(spotId: Int) {
        scope.launch {
            try {
                apiClient.deleteSpot(spotId)
                // Re-fetch to get updated statuses (server archives, doesn't hard-delete)
                val updated = apiClient.getAdminSpotReports()
                _uiState.update { it.copy(spotReports = updated) }
                invalidateTab(AdminTab.OVERVIEW)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to archive spot: ${e.message}") }
            }
        }
    }

    fun createSpot(request: CreateSpotRequest, photos: List<CapturedPhoto>) {
        scope.launch {
            try {
                _uiState.update { it.copy(isCreatingSpot = true, spotCreateError = null, error = null) }
                val photoBytes = photos.map { it.bytes }
                val uploadedUrls = if (photoBytes.isNotEmpty()) apiClient.uploadSpotPhotos(photoBytes) else emptyList()
                val requestWithPhotos = request.copy(
                    mediaUrls = uploadedUrls.ifEmpty { null },
                )
                apiClient.createAdminSpot(requestWithPhotos)
                val updated = apiClient.getAdminSpotReports()
                _uiState.update {
                    it.copy(
                        spotReports = updated,
                        isCreatingSpot = false,
                        spotCreateError = null,
                        spotCreateSuccessNonce = it.spotCreateSuccessNonce + 1,
                    )
                }
                invalidateTab(AdminTab.OVERVIEW)
            } catch (e: Exception) {
                val message = friendlyAdminCreateError(e.message)
                _uiState.update {
                    it.copy(
                        isCreatingSpot = false,
                        spotCreateError = message,
                        error = message,
                    )
                }
            }
        }
    }

    private fun friendlyAdminCreateError(rawMessage: String?): String {
        val message = rawMessage ?: return "Failed to create spot report"
        return when {
            message.contains("401") -> "Your admin session expired. Sign in again."
            message.contains("403") -> "Admin access is required to create spots."
            message.contains("429") -> "Spot creation rate limit reached. Try again later."
            message.contains("lat and lng", ignoreCase = true) -> "Choose a valid location before creating the spot."
            message.contains("affectedRadiusMeters", ignoreCase = true) -> "Affected area must be between 1m and 500m."
            message.contains("mediaUrls", ignoreCase = true) || message.contains("photos", ignoreCase = true) ->
                "Too many photos attached. Remove photos and try again."
            else -> message
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
                _uiState.update { it.copy(error = "Failed to upload photos: ${e.message}") }
            }
        }
    }

    fun createRoute(request: AdminRouteRequest) {
        scope.launch {
            try {
                _uiState.update { it.copy(error = null) }
                val created = apiClient.createRoute(request)
                _uiState.update { it.copy(routes = it.routes + created) }
                invalidateTab(AdminTab.OVERVIEW)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to create route: ${e.message}") }
            }
        }
    }

    /**
     * Admin manually grants (or subtracts) community points for a worker.
     * Negative amounts subtract. The reason is captured in the points
     * ledger for audit. Action distinguishes generic admin grants from
     * specific reward categories like bug reports.
     */
    fun grantPointsToWorker(
        workerId: Int,
        amount: Int,
        reason: String,
        action: AdminGrantAction = AdminGrantAction.ADMIN_GRANT,
        onResult: (success: Boolean, newTotal: Int?, error: String?) -> Unit = { _, _, _ -> },
    ) {
        // Block re-entry while a grant is in flight — guards against a fast
        // double-tap on the dialog's submit button creating two ledger rows.
        if (_workerActionLoading.value) {
            onResult(false, null, "Another action is in progress")
            return
        }
        scope.launch {
            _workerActionLoading.value = true
            try {
                _uiState.update { it.copy(error = null) }
                val response = apiClient.adminGrantPoints(
                    workerId = workerId,
                    amount = amount,
                    reason = reason,
                    action = action.serverValue,
                )

                // Reflect new total locally if we have the worker loaded
                _selectedWorker.value?.let { current ->
                    if (current.id == workerId) {
                        _selectedWorker.value = current.copy(
                            stats = current.stats.copy(
                                communityPoints = response.newTotal,
                            ),
                        )
                    }
                }

                // Invalidate workers tab so a refresh fetches the new total
                invalidateTab(AdminTab.WORKERS)

                onResult(true, response.newTotal, null)
            } catch (e: Exception) {
                val msg = friendlyGrantError(e.message)
                _uiState.update { it.copy(error = msg) }
                onResult(false, null, msg)
            } finally {
                _workerActionLoading.value = false
            }
        }
    }

    private fun friendlyGrantError(rawMessage: String?): String {
        val message = rawMessage ?: return "Failed to grant points"
        return when {
            message.contains("403") -> "Admin access is required to grant points."
            message.contains("401") -> "Your admin session expired. Sign in again."
            message.contains("404") -> "Worker not found."
            message.contains("|amount|") -> "Amount must be between -10,000 and 10,000."
            message.contains("reason must") -> "Reason is required (1–280 characters)."
            message.contains("action must") -> "Invalid action category."
            else -> message
        }
    }

    fun updateWorkerInfo(workerId: Int, updates: Map<String, String>) {
        scope.launch {
            try {
                val updated = apiClient.updateAdminWorker(workerId, updates)
                _selectedWorker.value = updated
                _uiState.update { state ->
                    state.copy(workers = state.workers.map { worker -> if (worker.id == workerId) updated else worker })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to update worker: ${e.message}") }
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

    private val _workerPointsHistory = MutableStateFlow<List<PointsLedgerEntry>>(emptyList())
    val workerPointsHistory: StateFlow<List<PointsLedgerEntry>> = _workerPointsHistory.asStateFlow()

    private val _workerPointsHistoryLoading = MutableStateFlow(false)
    val workerPointsHistoryLoading: StateFlow<Boolean> = _workerPointsHistoryLoading.asStateFlow()

    fun loadWorkerPointsHistory(workerId: Int) {
        scope.launch {
            _workerPointsHistoryLoading.value = true
            try {
                val response = apiClient.getWorkerPointsHistory(workerId)
                _workerPointsHistory.value = response.entries
            } catch (_: Exception) {
                // Best effort — history is auxiliary, don't push a global error
                _workerPointsHistory.value = emptyList()
            } finally {
                _workerPointsHistoryLoading.value = false
            }
        }
    }

    fun loadWorkerDetail(workerId: Int) {
        scope.launch {
            _workerActionLoading.value = true
            try {
                val worker = apiClient.getAdminWorker(workerId)
                _selectedWorker.value = worker
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to load worker: ${e.message}") }
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
                _uiState.update { state ->
                    state.copy(workers = state.workers.map { worker -> if (worker.id == workerId) updated else worker })
                }
                invalidateTab(AdminTab.OVERVIEW)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to verify worker: ${e.message}") }
            }
            _workerActionLoading.value = false
        }
    }

    fun deleteWorker(workerId: Int, onDeleted: () -> Unit) {
        scope.launch {
            _workerActionLoading.value = true
            try {
                apiClient.deleteWorker(workerId)
                _uiState.update { state ->
                    state.copy(workers = state.workers.filter { worker -> worker.id != workerId })
                }
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete worker: ${e.message}") }
            }
            _workerActionLoading.value = false
        }
    }

    fun bulkWorkerAction(workerIds: List<Int>, action: BulkAction) {
        scope.launch {
            try {
                _uiState.update { it.copy(error = null) }
                val updated = apiClient.bulkWorkerAction(BulkActionRequest(workerIds, action))
                _uiState.update { state ->
                    state.copy(
                        workers = state.workers.map { existing ->
                            updated.find { it.id == existing.id } ?: existing
                        },
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Bulk action failed: ${e.message}") }
            }
        }
    }

    fun deleteRoute(routeId: Int, onDeleted: () -> Unit) {
        scope.launch {
            try {
                apiClient.deleteRoute(routeId)
                _uiState.update { it.copy(routes = it.routes.filter { route -> route.id != routeId }) }
                invalidateTab(AdminTab.OVERVIEW)
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete route: ${e.message}") }
            }
        }
    }

    fun assignRouteToWorker(routeId: Int, workerId: Int, isTest: Boolean, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                apiClient.adminAssignRoute(routeId, workerId, isTest)
                loadRouteAssignments(routeId)
                onResult(true)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Assignment failed: ${e.message}") }
                onResult(false)
            }
        }
    }

    fun loadRouteAssignments(routeId: Int) {
        scope.launch {
            _uiState.update {
                it.copy(
                    loadingRouteAssignmentIds = it.loadingRouteAssignmentIds + routeId,
                    error = null,
                )
            }
            try {
                val assignments = apiClient.getAdminRouteAssignments(routeId)
                _uiState.update {
                    it.copy(
                        routeAssignments = it.routeAssignments + (routeId to assignments),
                        loadingRouteAssignmentIds = it.loadingRouteAssignmentIds - routeId,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadingRouteAssignmentIds = it.loadingRouteAssignmentIds - routeId,
                        error = "Failed to load assignments: ${e.message}",
                    )
                }
            }
        }
    }

    fun cancelRouteAssignment(routeId: Int, jobId: Int) {
        scope.launch {
            _uiState.update {
                it.copy(
                    cancellingRouteAssignmentJobIds = it.cancellingRouteAssignmentJobIds + jobId,
                    error = null,
                )
            }
            try {
                apiClient.cancelAdminRouteAssignment(routeId, jobId)
                val assignments = apiClient.getAdminRouteAssignments(routeId)
                _uiState.update {
                    it.copy(
                        routeAssignments = it.routeAssignments + (routeId to assignments),
                        cancellingRouteAssignmentJobIds = it.cancellingRouteAssignmentJobIds - jobId,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        cancellingRouteAssignmentJobIds = it.cancellingRouteAssignmentJobIds - jobId,
                        error = "Cancel failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── Payout Approval Actions ────────────────────────────────────────

    fun approveJob(jobId: Int, onResult: (success: Boolean, error: String?) -> Unit) {
        scope.launch {
            _uiState.update { it.copy(payoutActionError = null) }
            try {
                apiClient.approveJob(jobId)
                // Remove the approved job from the local list immediately
                _uiState.update { it.copy(pendingReviews = it.pendingReviews.filter { review -> review.jobId != jobId }) }
                invalidateTab(AdminTab.PAYOUTS)
                onResult(true, null)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to approve job"
                _uiState.update { it.copy(payoutActionError = msg) }
                onResult(false, msg)
            }
        }
    }

    fun denyJob(jobId: Int, reason: String, onResult: (success: Boolean, error: String?) -> Unit) {
        scope.launch {
            _uiState.update { it.copy(payoutActionError = null) }
            try {
                apiClient.denyJob(jobId, reason)
                _uiState.update { it.copy(pendingReviews = it.pendingReviews.filter { review -> review.jobId != jobId }) }
                invalidateTab(AdminTab.PAYOUTS)
                onResult(true, null)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to deny job"
                _uiState.update { it.copy(payoutActionError = msg) }
                onResult(false, msg)
            }
        }
    }

    fun clearPayoutActionError() {
        _uiState.update { it.copy(payoutActionError = null) }
    }

    // ── Ad-hoc Payout Actions ──────────────────────────────────────────

    /**
     * One-off payout to a worker. Server routes by the worker's payout
     * method (Stripe vs checks-to-mail queue). idempotencyKey is generated
     * once per dialog open so a retried tap can't double-send money.
     */
    fun sendPayout(
        workerId: Int,
        amountCents: Int,
        note: String?,
        idempotencyKey: String,
        onResult: (success: Boolean, message: String?) -> Unit,
    ) {
        scope.launch {
            try {
                val response = apiClient.adminCreatePayout(
                    workerId,
                    CreatePayoutRequest(
                        amountCents = amountCents,
                        note = note?.trim()?.ifBlank { null },
                        idempotencyKey = idempotencyKey,
                    ),
                )
                invalidateTab(AdminTab.PAYOUTS)
                val message = when {
                    response.alreadyProcessed -> "Payout was already sent"
                    response.payout?.status == "check_pending" ->
                        "Queued as a check to mail — see Payouts tab"
                    else -> "Payout sent via Stripe"
                }
                onResult(true, message)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Failed to send payout")
            }
        }
    }

    fun markPayoutMailed(payoutId: Int, onResult: (success: Boolean, error: String?) -> Unit) {
        scope.launch {
            _uiState.update { it.copy(payoutActionError = null) }
            try {
                apiClient.markPayoutMailed(payoutId)
                _uiState.update { it.copy(pendingChecks = it.pendingChecks.filter { check -> check.id != payoutId }) }
                invalidateTab(AdminTab.PAYOUTS)
                onResult(true, null)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to mark check as mailed"
                _uiState.update { it.copy(payoutActionError = msg) }
                onResult(false, msg)
            }
        }
    }

    // ── Block Proposal Review Actions ────────────────────────────────────
    // Approving inserts a real Block (+ its exclusion zones and, best
    // effort, an inner boundary) on the server — nothing here is live
    // until this call succeeds. See site/src/app/api/admin/block-proposals.

    fun approveBlockProposal(proposalId: Int, onResult: (success: Boolean, error: String?) -> Unit) {
        scope.launch {
            _uiState.update { it.copy(blockProposalActionError = null) }
            try {
                apiClient.approveBlockProposal(proposalId)
                _uiState.update { it.copy(blockProposals = it.blockProposals.filter { proposal -> proposal.id != proposalId }) }
                invalidateTab(AdminTab.BLOCK_PROPOSALS)
                onResult(true, null)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to approve block proposal"
                _uiState.update { it.copy(blockProposalActionError = msg) }
                onResult(false, msg)
            }
        }
    }

    fun rejectBlockProposal(proposalId: Int, reason: String?, onResult: (success: Boolean, error: String?) -> Unit) {
        scope.launch {
            _uiState.update { it.copy(blockProposalActionError = null) }
            try {
                apiClient.rejectBlockProposal(proposalId, reason)
                _uiState.update { it.copy(blockProposals = it.blockProposals.filter { proposal -> proposal.id != proposalId }) }
                invalidateTab(AdminTab.BLOCK_PROPOSALS)
                onResult(true, null)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to reject block proposal"
                _uiState.update { it.copy(blockProposalActionError = msg) }
                onResult(false, msg)
            }
        }
    }

    fun clearBlockProposalActionError() {
        _uiState.update { it.copy(blockProposalActionError = null) }
    }
}
