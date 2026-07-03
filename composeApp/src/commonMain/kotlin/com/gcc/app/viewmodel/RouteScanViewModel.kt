package com.gcc.app.viewmodel

import com.gcc.app.platform.AiDirectionEngine
import com.gcc.app.platform.ArScanner
import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.CoverageEventQueue
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.OutdoorGate
import com.gcc.app.platform.OutdoorState
import com.gcc.app.platform.SceneVote
import com.gcc.app.platform.SessionPointsEstimator
import com.gcc.app.platform.coverageTier
import com.gcc.app.platform.detectDeviceCapabilities
import com.gcc.app.platform.detectDeviceType
import com.gcc.app.platform.getPlatformName
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.CheckpointGenerator
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.geo.pointInPolygon
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

// ── UI state ─────────────────────────────────────────────────────────

enum class RouteScanSectionStatus {
    PENDING,
    RECORDED,
    DOCUMENTED,
    APPROVED,
}

data class RouteScanSectionUiItem(
    val index: Int,
    val name: String,
    val coordinates: Coordinates,
    val status: RouteScanSectionStatus = RouteScanSectionStatus.PENDING,
)

data class RouteScanUiState(
    val scanId: Int? = null,
    val routeId: Int = 0,
    val route: Route? = null,
    val deviceType: DeviceType = DeviceType.GPS,
    val phase: ScanPhase = ScanPhase.SCOUTING,
    val coveragePercent: Double = 0.0,
    val hotspots: List<ScanHotspot> = emptyList(),
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val currentLat: Double = 0.0,
    val currentLng: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val nearbyHotspot: ScanHotspot? = null,
    val nearbyDistMeters: Double? = null,
    val isLoopClosed: Boolean = false,
    val isSubmitting: Boolean = false,
    val isComplete: Boolean = false,
    val pointsAwarded: Int = 0,
    val showArRoute: Boolean = false,
    val error: String? = null,
    val submitError: String? = null,
    val aiDirectionText: String? = null,
    val routeSections: List<RouteScanSectionUiItem> = emptyList(),
    val currentSectionIndex: Int = 0,
    val sectionPrompt: String? = null,
)

// ── ViewModel ─────────────────────────────────────────────────────────

class RouteScanViewModel(
    private val apiClient: GccApiClient,
    private val locationProvider: LocationProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(RouteScanUiState())
    val uiState: StateFlow<RouteScanUiState> = _uiState.asStateFlow()

    // Whether the worker's current GPS position is outside the route's assigned
    // block boundary. Consumed by the AR overlay (boundary-crossing warning).
    private val _isOutsideBoundary = MutableStateFlow(false)
    val isOutsideBoundary: StateFlow<Boolean> = _isOutsideBoundary.asStateFlow()

    // Block boundary polygon as (lat, lng) pairs, matching PolygonContainment's
    // pointInPolygon convention. Empty when no boundary is loaded for the route.
    private var boundaryPolygon: List<Pair<Double, Double>> = emptyList()

    private val coverageTracker = CoverageTracker()
    private val aiDirectionEngine = AiDirectionEngine()
    private val arScanner = ArScanner()

    // Coverage-ledger event batching (design spec §1.6): route-mode scan
    // coverage events queue locally and flush every 30s + on session end.
    private val coverageEventQueue = CoverageEventQueue(
        submitFn = { events ->
            try {
                apiClient.submitCoverageEvents(events)
            } catch (e: Exception) {
                DiagnosticsLog.error("coverage-flush", "route flush of ${events.size} events failed (kept queued)", e)
                throw e
            }
        },
    )
    private val coverageDeviceTier by lazy { detectDeviceCapabilities().deviceClass.coverageTier() }
    private var lastCoverageFix: Pair<Double, Double>? = null
    private var coverageFlushJob: Job? = null

    // "Go outside" gate: GPS-accuracy evidence is fed here from the location
    // stream; the AR screen feeds camera scene votes via [onSceneVote]. While
    // INDOOR, coverage accrual (ledger events + tracker cells) is suppressed.
    private val outdoorGate = OutdoorGate()
    val outdoorState: StateFlow<OutdoorState> = outdoorGate.state
    private var accuracyJob: Job? = null

    /** Scene-classification vote from the AR camera feed (ArRouteScreen). */
    fun onSceneVote(vote: SceneVote) {
        outdoorGate.onSceneVote(vote, Clock.System.now().toEpochMilliseconds())
    }

    // Live points HUD: client-side estimate + one-shot "+N pts" pip events.
    // Display-only; the server ledger total lands on the completion screen.
    private val pointsEstimator = SessionPointsEstimator()
    private val _sessionPointsEstimate = MutableStateFlow(0)
    val sessionPointsEstimate: StateFlow<Int> = _sessionPointsEstimate.asStateFlow()
    private val _pointsPips = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val pointsPips: SharedFlow<Int> = _pointsPips

    // Prior coverage state per client grid cell ("fresh" | "stale"), from the
    // production ledger. Cells absent from the map were never scanned.
    // Consumed by the live points estimator; "fresh" cells also start revealed.
    private var cellPriorStates: Map<Pair<Int, Int>, String> = emptyMap()

    // ── Expected-observation (rectify) flow ──────────────────────────
    // Dirty world observations near the route, rendered as expected-trash AR
    // markers. Walking within RECTIFY_PROXIMITY_METERS of one surfaces a
    // confirm sheet: "Still there" dismisses for the session, "Already clean"
    // posts to /api/scans/{id}/rectify (25-pt bonus when it supersedes a real
    // prior dirty observation).
    private val _expectedObservations = MutableStateFlow<List<WorldObservation>>(emptyList())
    val expectedObservations: StateFlow<List<WorldObservation>> = _expectedObservations.asStateFlow()
    private val _nearbyExpectedObservation = MutableStateFlow<WorldObservation?>(null)
    val nearbyExpectedObservation: StateFlow<WorldObservation?> = _nearbyExpectedObservation.asStateFlow()
    private val resolvedObservationIds = mutableSetOf<Int>()
    private var rectifyBonusPoints = 0

    /** "Still there" — keep the marker but stop prompting this session. */
    fun dismissExpectedObservation(observation: WorldObservation) {
        resolvedObservationIds.add(observation.id)
        _nearbyExpectedObservation.value = null
    }

    /** "Already clean" — record the rectification and credit the bonus. */
    fun rectifyObservation(observation: WorldObservation) {
        val scanId = _uiState.value.scanId ?: return
        resolvedObservationIds.add(observation.id)
        _nearbyExpectedObservation.value = null
        _expectedObservations.value = _expectedObservations.value.filterNot { it.id == observation.id }
        scope.launch {
            try {
                val result = apiClient.rectifyScanObservation(scanId, observation.lat, observation.lng)
                if (result.pointsAwarded > 0) {
                    rectifyBonusPoints += result.pointsAwarded
                    _sessionPointsEstimate.value = pointsEstimator.total + rectifyBonusPoints
                    _pointsPips.tryEmit(result.pointsAwarded)
                }
            } catch (e: Exception) {
                // Put it back so the worker can retry when they pass again.
                DiagnosticsLog.error("rectify", "rectify of observation ${observation.id} failed (will re-prompt)", e)
                resolvedObservationIds.remove(observation.id)
                _expectedObservations.value = _expectedObservations.value + observation
            }
        }
    }

    /**
     * Bring the digital twin into the AR fog: fetch `/api/map/coverage` for
     * the route's bounding box and (a) pre-reveal cells the ledger marks
     * fresh, (b) remember fresh/stale priors for live points estimation.
     * Server cells are 10m; each maps onto the 3m client cells inside its
     * footprint. Fetch failures degrade silently to session-local fog.
     */
    private fun seedFogFromServerCoverage(waypoints: List<Coordinates>) {
        cellPriorStates = emptyMap()
        if (waypoints.isEmpty()) return
        scope.launch {
            try {
                val pad = 0.0005 // ~50m of slack around the route bbox
                val bbox = listOf(
                    waypoints.minOf { it.lng } - pad,
                    waypoints.minOf { it.lat } - pad,
                    waypoints.maxOf { it.lng } + pad,
                    waypoints.maxOf { it.lat } + pad,
                ).joinToString(",")
                val serverCells = apiClient.getMapCoverage(bbox).cells

                val priors = mutableMapOf<Pair<Int, Int>, String>()
                val fresh = mutableSetOf<Pair<Int, Int>>()
                for (serverCell in serverCells) {
                    for (cell in clientCellsForServerCell(serverCell.lat, serverCell.lng)) {
                        priors[cell] = serverCell.state
                        if (serverCell.state == "fresh") fresh.add(cell)
                    }
                }
                cellPriorStates = priors
                if (fresh.isNotEmpty()) coverageTracker.seedScannedCells(fresh)
                DiagnosticsLog.info("fog-seed", "seeded ${fresh.size} fresh cells, ${priors.size} priors from ${serverCells.size} server cells")
            } catch (e: Exception) {
                // Non-fatal: fog stays session-local, estimator treats all as new.
                DiagnosticsLog.error("fog-seed", "coverage fetch failed — fog is session-local", e)
            }
        }
        scope.launch {
            try {
                val pad = 0.0005
                val bbox = listOf(
                    waypoints.minOf { it.lng } - pad,
                    waypoints.minOf { it.lat } - pad,
                    waypoints.maxOf { it.lng } + pad,
                    waypoints.maxOf { it.lat } + pad,
                ).joinToString(",")
                _expectedObservations.value = apiClient.getMapObservations(bbox)
                    .filter { it.state == "dirty" }
                DiagnosticsLog.info("expected-obs", "${_expectedObservations.value.size} dirty observations near route")
            } catch (e: Exception) {
                // Non-fatal: no expected-trash markers this session.
                DiagnosticsLog.error("expected-obs", "observations fetch failed — no expected markers", e)
            }
        }
    }

    /** Client 3m grid cells whose centers fall inside a 10m server cell. */
    private fun clientCellsForServerCell(centerLat: Double, centerLng: Double): Set<Pair<Int, Int>> {
        val center = coverageTracker.cellForLatLng(centerLat, centerLng) ?: return emptySet()
        val halfLat = 5.0 / 111_320.0
        val halfLng = 5.0 / (111_320.0 * cos(centerLat * PI / 180.0))
        val out = mutableSetOf<Pair<Int, Int>>()
        for (dr in -2..2) {
            for (dc in -2..2) {
                val row = center.first + dr
                val col = center.second + dc
                val (lat, lng) = coverageTracker.cellCenterLatLng(row, col)
                if (abs(lat - centerLat) <= halfLat && abs(lng - centerLng) <= halfLng) {
                    out.add(row to col)
                }
            }
        }
        return out
    }

    // ── Read-only projections for the AR route overlay (Task 16) ─────────
    // Exposed as read-only accessors rather than the mutable CoverageTracker /
    // LocationProvider instances, so ArRouteScreen can drive the overlay without
    // being able to mutate scan/coverage internals.

    /** Set of scanned coverage-grid cells (row, col). */
    val scannedCells: StateFlow<Set<Pair<Int, Int>>> get() = coverageTracker.scannedCells

    /** Real-world (lat, lng) center of a coverage-grid cell. */
    fun cellCenterLatLng(row: Int, col: Int): Pair<Double, Double> =
        coverageTracker.cellCenterLatLng(row, col)

    /** Corridor cells (row, col) not yet scanned — the AR fog overlay's node set. */
    fun unscannedCells(): Set<Pair<Int, Int>> = coverageTracker.getUnscannedCells()

    /** Device compass heading in degrees, or null when unavailable. */
    val headingDegrees: StateFlow<Float?> get() = locationProvider.headingDegrees

    // GPS breadcrumb paths for GeoJSON.
    // These grow at ~1 Hz; a 30-min scan produces ~1800 entries. Downsampled to
    // ≤500 points in buildGeoJsonLineString() before transmission to the server.
    private val scoutPath = mutableListOf<Pair<Double, Double>>()
    private val cleanPath = mutableListOf<Pair<Double, Double>>()
    private var allowUnpublishedRoute = false
    private var onBehalfOfWorkerId: Int? = null

    // Coroutine jobs
    private var locationJob: Job? = null
    private var timerJob: Job? = null
    private var coverageJob: Job? = null
    private var aiDirectionJob: Job? = null

    // ── Initialisation ───────────────────────────────────────────────

    fun initForRoute(route: Route, allowUnpublished: Boolean = false, onBehalfOfWorkerId: Int? = null) {
        allowUnpublishedRoute = allowUnpublished
        this.onBehalfOfWorkerId = onBehalfOfWorkerId
        timerJob?.cancel()
        locationJob?.cancel()
        coverageJob?.cancel()
        aiDirectionJob?.cancel()
        arScanner.stopSession()
        scoutPath.clear()
        cleanPath.clear()
        setBoundary(route.blockBoundary)

        if (!route.isWorkerAvailableRoute(allowUnpublished)) {
            _uiState.value = RouteScanUiState(
                routeId = route.id,
                route = route,
                error = "This route is not available yet. An admin must publish it and configure checkpoints before scouting can start.",
            )
            return
        }

        val deviceType = detectDeviceType()
        val effectiveCheckpoints = CheckpointGenerator.generateIfMissing(route)
            .sortedBy { it.sequenceOrder }
        val sections = effectiveCheckpoints.mapIndexed { index, checkpoint ->
            RouteScanSectionUiItem(
                index = index,
                name = checkpoint.name,
                coordinates = checkpoint.coordinates,
            )
        }

        _uiState.value = RouteScanUiState(
            routeId = route.id,
            route = route,
            deviceType = deviceType,
            phase = ScanPhase.SCOUTING,
            routeSections = sections,
            sectionPrompt = if (deviceType == DeviceType.AR) {
                "Start the area scan at section 1."
            } else {
                "Start the fallback photo lap at section 1."
            },
        )

        // Initialise coverage grid from route checkpoints (auto-generate if missing)
        val waypoints = effectiveCheckpoints
            .map { it.coordinates }
        coverageTracker.initFromRoute(waypoints)
        seedFogFromServerCoverage(waypoints)

        // Observe coverage changes
        coverageJob = scope.launch {
            coverageTracker.coveragePercent.collect { pct ->
                _uiState.value = _uiState.value.copy(coveragePercent = pct)
            }
        }

        // Collect AI direction text into UI state
        aiDirectionJob = scope.launch {
            aiDirectionEngine.directionText.collect { text ->
                _uiState.value = _uiState.value.copy(aiDirectionText = text)
            }
        }
    }

    // ── Scout phase ──────────────────────────────────────────────────

    fun startScout() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                val state = _uiState.value
                val route = state.route
                if (route?.isWorkerAvailableRoute(allowUnpublishedRoute) != true) {
                    throw IllegalStateException(
                        "This route is not available yet. An admin must publish it and configure checkpoints before scouting can start."
                    )
                }

                // Get first GPS fix
                locationProvider.startLocationUpdates()
                val location = awaitInitialLocation()
                    ?: throw IllegalStateException("Location unavailable. Enable Location Services and wait for a GPS fix before starting the scan.")

                val lat = location.lat
                val lng = location.lng

                val scan = apiClient.createScan(
                    CreateScanRequest(
                        routeId = state.routeId,
                        deviceType = state.deviceType,
                        startLat = lat,
                        startLng = lng,
                    ),
                    onBehalfOfWorkerId = onBehalfOfWorkerId,
                )

                _uiState.value = _uiState.value.copy(
                    scanId = scan.id,
                    startLat = lat,
                    startLng = lng,
                    currentLat = lat,
                    currentLng = lng,
                    isSubmitting = false,
                )

                startLocationTracking()
                if (state.deviceType == DeviceType.AR) {
                    arScanner.startSession()
                }
                startTimer()
            } catch (e: Exception) {
                DiagnosticsLog.error("scout", "startScout failed", e)
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = e.message ?: "Failed to start scout",
                )
            }
        }
    }

    // ── Hotspot management ───────────────────────────────────────────

    fun addDetectedHotspot(
        lat: Double,
        lng: Double,
        trashTypes: List<SpotTag> = emptyList(),
        severity: Int = 1,
        beforePhotoUrl: String,
        confidence: Float = 0f,
        estimatedVolumeLiters: Double? = null,
        estimatedWeightLbs: Double? = null,
        arAnchorData: String? = null,
        depthData: String? = null,
        cleanupTarget: Boolean = true,
        sectionIndex: Int? = null,
    ) {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                val metadata = sectionIndex?.let { "section:$it" }
                val createdHotspot = apiClient.addScanHotspot(
                    scanId,
                    CreateHotspotRequest(
                        lat = lat,
                        lng = lng,
                        trashTypes = trashTypes,
                        severity = severity,
                        estimatedVolumeLiters = estimatedVolumeLiters,
                        estimatedWeightLbs = estimatedWeightLbs,
                        confidence = confidence,
                        beforePhotoUrl = beforePhotoUrl,
                        arAnchorData = arAnchorData ?: metadata,
                        depthData = depthData,
                    )
                )
                val hotspot = if (cleanupTarget) {
                    createdHotspot
                } else {
                    apiClient.updateScanHotspot(
                        scanId,
                        createdHotspot.id,
                        UpdateHotspotRequest(status = HotspotStatus.SKIPPED),
                    )
                }
                val currentState = _uiState.value
                val updatedSections = currentState.routeSections.map { section ->
                    if (section.index == sectionIndex) {
                        section.copy(
                            status = if (cleanupTarget) {
                                RouteScanSectionStatus.RECORDED
                            } else {
                                RouteScanSectionStatus.DOCUMENTED
                            }
                        )
                    } else {
                        section
                    }
                }
                val nextSectionIndex = if (sectionIndex != null) {
                    (sectionIndex + 1).coerceAtMost(currentState.routeSections.size)
                } else {
                    currentState.currentSectionIndex
                }
                val prompt = when {
                    sectionIndex == null -> null
                    cleanupTarget -> "Section ${sectionIndex + 1} recorded. Continue to the next scan section."
                    else -> "Section ${sectionIndex + 1} documented only. Continue to the next scan section."
                }
                _uiState.value = currentState.copy(
                    hotspots = currentState.hotspots + hotspot,
                    routeSections = updatedSections,
                    currentSectionIndex = nextSectionIndex,
                    sectionPrompt = prompt,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to add hotspot: ${e.message}",
                )
            }
        }
    }

    // ── AR route overlay (Task 16) ───────────────────────────────────

    /**
     * Enter the full-screen AR route guidance overlay during SCOUTING.
     * Gated: only when an active scan exists and the worker's last GPS fix is
     * confirmed inside the assigned block boundary — we never launch AR guidance
     * for a worker who is already outside their route polygon.
     */
    fun beginRoute() {
        val state = _uiState.value
        if (state.phase != ScanPhase.SCOUTING) return
        if (state.scanId == null) return
        if (_isOutsideBoundary.value) return
        _uiState.value = state.copy(showArRoute = true)
    }

    /** Leave the AR route overlay and return to the scout map. */
    fun exitArRoute() {
        _uiState.value = _uiState.value.copy(showArRoute = false)
    }

    // ── Phase transitions ────────────────────────────────────────────

    fun completeScout() {
        if (!canCompleteScout()) return
        val state = _uiState.value
        val scanId = state.scanId ?: return

        scope.launch {
            try {
                val geoJson = buildGeoJsonLineString(scoutPath)
                apiClient.advanceScanPhase(
                    scanId,
                    AdvancePhaseRequest(
                        phase = ScanPhase.SCOUT_REVIEW,
                        coveragePercent = state.coveragePercent,
                        scoutPathGeoJson = geoJson,
                    )
                )
                _uiState.value = _uiState.value.copy(
                    phase = ScanPhase.SCOUT_REVIEW,
                    error = null,
                )
            } catch (e: Exception) {
                DiagnosticsLog.error("scout", "completeScout failed", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to complete scout: ${e.message}",
                )
            }
        }
    }

    fun startCleaning() {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                apiClient.advanceScanPhase(
                    scanId,
                    AdvancePhaseRequest(phase = ScanPhase.CLEANING)
                )
                cleanPath.clear()
                _uiState.value = _uiState.value.copy(
                    phase = ScanPhase.CLEANING,
                    error = null,
                    sectionPrompt = "Record the after scan for the first cleanup target.",
                )
            } catch (e: Exception) {
                DiagnosticsLog.error("scan-phase", "startCleaning failed", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to start cleaning: ${e.message}",
                )
            }
        }
    }

    fun confirmHotspotCleaned(
        hotspotId: Int,
        afterPhotoUrl: String,
        verificationScore: Float,
    ) {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                val updated = apiClient.updateScanHotspot(
                    scanId,
                    hotspotId,
                    UpdateHotspotRequest(
                        afterPhotoUrl = afterPhotoUrl,
                        verificationScore = verificationScore,
                        status = HotspotStatus.CLEANED,
                    )
                )
                val currentState = _uiState.value
                val updatedHotspots = currentState.hotspots.map {
                        if (it.id == hotspotId) updated else it
                    }
                val sectionIndex = updated.sectionIndex()
                val updatedSections = currentState.routeSections.map { section ->
                    if (section.index == sectionIndex) {
                        section.copy(status = RouteScanSectionStatus.APPROVED)
                    } else {
                        section
                    }
                }
                _uiState.value = currentState.copy(
                    hotspots = updatedHotspots,
                    routeSections = updatedSections,
                    nearbyHotspot = null,
                    sectionPrompt = "Area approved clean. Continue to the next section.",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to confirm hotspot: ${e.message}",
                )
            }
        }
    }

    fun skipHotspot(hotspotId: Int) {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                val updated = apiClient.updateScanHotspot(
                    scanId,
                    hotspotId,
                    UpdateHotspotRequest(status = HotspotStatus.SKIPPED)
                )
                val currentState = _uiState.value
                val sectionIndex = updated.sectionIndex()
                _uiState.value = currentState.copy(
                    hotspots = currentState.hotspots.map {
                        if (it.id == hotspotId) updated else it
                    },
                    routeSections = currentState.routeSections.map { section ->
                        if (section.index == sectionIndex) {
                            section.copy(status = RouteScanSectionStatus.DOCUMENTED)
                        } else {
                            section
                        }
                    },
                    nearbyHotspot = null,
                    sectionPrompt = "Section documented without cleanup. Continue to the next target.",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to skip hotspot: ${e.message}",
                )
            }
        }
    }

    fun completeScan() {
        val scanId = _uiState.value.scanId ?: return
        val state = _uiState.value

        timerJob?.cancel()
        locationJob?.cancel()
        coverageFlushJob?.cancel()
        locationProvider.stopLocationUpdates()
        arScanner.stopSession()

        // Final coverage-ledger flush for the session — independent of the
        // scan-completion request below, and non-fatal if it fails (events
        // stay queued for a future session's flush).
        scope.launch { coverageEventQueue.flush() }

        scope.launch {
            _uiState.value = state.copy(
                phase = ScanPhase.SUBMITTING,
                isSubmitting = true,
                error = null,
            )
            try {
                val cleanedHotspots = state.hotspots.filter { it.status == HotspotStatus.CLEANED }
                val totalVolume = cleanedHotspots.sumOf { it.estimatedVolumeLiters ?: 0.0 }
                    .takeIf { it > 0 }
                val totalWeight = cleanedHotspots.sumOf { it.estimatedWeightLbs ?: 0.0 }
                    .takeIf { it > 0 }

                val response = apiClient.completeScan(
                    scanId,
                    CompleteScanRequest(
                        cleanPathGeoJson = buildGeoJsonLineString(cleanPath),
                        totalTrashVolumeLiters = totalVolume,
                        totalTrashWeightLbs = totalWeight,
                    )
                )
                _uiState.value = _uiState.value.copy(
                    phase = ScanPhase.COMPLETE,
                    isSubmitting = false,
                    isComplete = true,
                    pointsAwarded = response.pointsAwarded,
                )
            } catch (e: Exception) {
                DiagnosticsLog.error("scan-submit", "completeScan submission failed", e)
                _uiState.value = _uiState.value.copy(
                    phase = ScanPhase.SUBMITTING,
                    isSubmitting = false,
                    submitError = e.message ?: "Couldn't submit your scan",
                )
            }
        }
    }

    fun retrySubmit() {
        _uiState.value = _uiState.value.copy(submitError = null)
        completeScan()
    }

    // ── State queries ────────────────────────────────────────────────

    fun canCompleteScout(): Boolean {
        val state = _uiState.value
        val allSectionsRecorded = state.routeSections.isNotEmpty() &&
            state.routeSections.none { it.status == RouteScanSectionStatus.PENDING }
        return (state.coveragePercent >= 85.0 && state.isLoopClosed) || allSectionsRecorded
    }

    fun allHotspotsDone(): Boolean {
        return _uiState.value.hotspots.none { it.status == HotspotStatus.DETECTED }
    }

    fun setError(message: String?) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    // ── Internal: location tracking ──────────────────────────────────

    private fun startLocationTracking() {
        lastCoverageFix = null
        coverageFlushJob?.cancel()
        coverageFlushJob = scope.launch {
            while (true) {
                delay(30_000L)
                coverageEventQueue.flush() // failed flushes keep events queued
            }
        }
        accuracyJob?.cancel()
        accuracyJob = scope.launch {
            locationProvider.horizontalAccuracyMeters.collect { accuracy ->
                outdoorGate.onAccuracySample(accuracy, Clock.System.now().toEpochMilliseconds())
            }
        }
        scope.launch {
            var lastState: OutdoorState? = null
            outdoorGate.state.collect { gateState ->
                if (gateState != lastState) {
                    DiagnosticsLog.info("outdoor-gate", "route gate → $gateState")
                    lastState = gateState
                }
            }
        }
        locationJob?.cancel()
        locationJob = scope.launch {
            locationProvider.locationFlow.collect { location ->
                val state = _uiState.value
                val lat = location.lat
                val lng = location.lng

                _uiState.value = state.copy(currentLat = lat, currentLng = lng)

                when (state.phase) {
                    ScanPhase.SCOUTING -> {
                        updateBoundaryStatus(lat, lng)
                        outdoorGate.onMapEvidence(
                            insideBuilding = com.gcc.app.ui.map.isInsideMappedBuilding(lat, lng),
                            timestampMs = Clock.System.now().toEpochMilliseconds(),
                        )

                        // "Go outside" gate: while judged indoors, accrue nothing —
                        // no path point, no ledger event, no coverage cells. Reset
                        // the segment anchor so no phantom distance spans the gap.
                        if (outdoorGate.state.value == OutdoorState.INDOOR) {
                            lastCoverageFix = null
                            return@collect
                        }
                        scoutPath.add(Pair(lat, lng))

                        // Coverage-ledger event: distance walked since the last fix.
                        // Desktop is dev-only and the endpoint rejects its platform
                        // value — skip so the queue can't fill with unsendable events.
                        val prevFix = lastCoverageFix
                        lastCoverageFix = Pair(lat, lng)
                        val segmentMeters = prevFix?.let { (prevLat, prevLng) ->
                            GeofenceVerification.distanceMeters(prevLat, prevLng, lat, lng)
                        } ?: 0.0
                        if (getPlatformName() != "desktop") coverageEventQueue.enqueue(
                            ScanCoverageEventRequest(
                                deviceId = CoverageDeviceId.value,
                                platform = getPlatformName(),
                                lat = lat,
                                lng = lng,
                                distanceMeters = segmentMeters,
                                scanMode = "route",
                                deviceTier = coverageDeviceTier,
                                capturedAt = Clock.System.now().toString(),
                            )
                        )

                        // Live points HUD: estimate what this segment earned.
                        val gridCell = coverageTracker.cellForLatLng(lat, lng)
                        val pointsDelta = pointsEstimator.onSegment(
                            gridCell, cellPriorStates[gridCell], segmentMeters,
                        )
                        if (pointsDelta > 0) {
                            _sessionPointsEstimate.value = pointsEstimator.total + rectifyBonusPoints
                            _pointsPips.tryEmit(pointsDelta)
                        }

                        // Expected-observation proximity: prompt when within
                        // range of an unresolved dirty observation.
                        _nearbyExpectedObservation.value = _expectedObservations.value
                            .asSequence()
                            .filter { it.id !in resolvedObservationIds }
                            .map { it to GeofenceVerification.distanceMeters(lat, lng, it.lat, it.lng) }
                            .filter { (_, dist) -> dist <= RECTIFY_PROXIMITY_METERS }
                            .minByOrNull { (_, dist) -> dist }
                            ?.first

                        // AR tier: use camera frustum for coverage; GPS tier: use radius
                        val heading = arScanner.getArHeading() ?: locationProvider.headingDegrees.value
                        if (state.deviceType == DeviceType.AR && heading != null) {
                            coverageTracker.updateWithFrustum(
                                lat = lat,
                                lng = lng,
                                headingDegrees = heading,
                                fovDegrees = arScanner.getCameraFovDegrees(),
                            )
                        } else {
                            coverageTracker.updatePosition(lat, lng)
                        }

                        // Update AI direction engine with unscanned cluster centers
                        aiDirectionEngine.update(
                            userLat = lat,
                            userLng = lng,
                            userHeading = heading,
                            unscannedCenters = coverageTracker.getUnscannedCenters(),
                        )

                        // Check loop closure: within 20m of start and already moved at least 50m
                        val distFromStart = GeofenceVerification.distanceMeters(
                            lat, lng, state.startLat, state.startLng
                        )
                        if (distFromStart <= 20.0 && scoutPath.size > 10) {
                            _uiState.value = _uiState.value.copy(isLoopClosed = true)
                        }

                        // Periodically push coverage to server (every 20 points)
                        if (scoutPath.size % 20 == 0 && state.scanId != null) {
                            try {
                                apiClient.updateScanCoverage(
                                    state.scanId,
                                    AdvancePhaseRequest(
                                        phase = ScanPhase.SCOUTING,
                                        coveragePercent = _uiState.value.coveragePercent,
                                    )
                                )
                            } catch (_: Exception) { /* non-fatal */ }
                        }
                    }

                    ScanPhase.CLEANING -> {
                        cleanPath.add(Pair(lat, lng))

                        // Check proximity to uncleaned hotspots (20m threshold).
                        // Compute distance once per hotspot to avoid double calculation.
                        val proximityAlertMeters = 20.0
                        val hotspotsWithDist = _uiState.value.hotspots
                            .filter { it.status == HotspotStatus.DETECTED }
                            .map { it to GeofenceVerification.distanceMeters(lat, lng, it.lat, it.lng) }
                        val (nearestHotspot, nearestDist) = hotspotsWithDist
                            .minByOrNull { (_, dist) -> dist }
                            ?: (null to null)

                        _uiState.value = _uiState.value.copy(
                            nearbyHotspot = if (nearestDist != null && nearestDist <= proximityAlertMeters) nearestHotspot else null,
                            nearbyDistMeters = nearestDist,
                        )
                    }

                    else -> { /* no location action needed */ }
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (!_uiState.value.isComplete) {
                delay(1000L)
                _uiState.value = _uiState.value.copy(
                    elapsedSeconds = _uiState.value.elapsedSeconds + 1,
                )
            }
        }
    }

    // ── Boundary containment ─────────────────────────────────────────

    private fun setBoundary(polygon: GeoJSONPolygon?) {
        val ring = polygon?.coordinates?.firstOrNull()
        boundaryPolygon = ring?.mapNotNull { point ->
            val lng = point.getOrNull(0) ?: return@mapNotNull null
            val lat = point.getOrNull(1) ?: return@mapNotNull null
            lat to lng
        } ?: emptyList()
        _isOutsideBoundary.value = false
    }

    private fun updateBoundaryStatus(lat: Double, lng: Double) {
        if (boundaryPolygon.size < 3) {
            _isOutsideBoundary.value = false // no boundary loaded — fail open, don't block the worker
            return
        }
        _isOutsideBoundary.value = !pointInPolygon(lat, lng, boundaryPolygon)
    }

    // ── GeoJSON helper ───────────────────────────────────────────────

    fun buildGeoJsonLineString(path: List<Pair<Double, Double>>): String? {
        if (path.size < 2) return null
        // Downsample long paths to keep GeoJSON payload under ~500 coordinate pairs.
        val sampled = if (path.size > 500) {
            val step = path.size / 500
            path.filterIndexed { i, _ -> i % step == 0 || i == path.size - 1 }
        } else path
        val coords = sampled.joinToString(",") { (lat, lng) -> "[${lng},${lat}]" }
        return """{"type":"LineString","coordinates":[$coords]}"""
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    fun onCleared() {
        timerJob?.cancel()
        locationJob?.cancel()
        coverageJob?.cancel()
        coverageFlushJob?.cancel()
        scope.launch { coverageEventQueue.flush() }
        aiDirectionJob?.cancel()
        aiDirectionEngine.clear()
        locationProvider.stopLocationUpdates()
        arScanner.stopSession()
    }

    private suspend fun awaitInitialLocation(): Coordinates? {
        locationProvider.currentLocation.value?.let { return it }
        repeat(10) {
            delay(1000L)
            locationProvider.currentLocation.value?.let { return it }
        }
        return null
    }
}

private fun Route.isWorkerAvailableRoute(allowUnpublished: Boolean = false): Boolean =
    (status == RouteStatus.PUBLISHED || allowUnpublished) &&
        CheckpointGenerator.generateIfMissing(this).size >= 2

private fun ScanHotspot.sectionIndex(): Int? =
    arAnchorData
        ?.substringAfter("section:", missingDelimiterValue = "")
        ?.takeWhile { it.isDigit() }
        ?.toIntOrNull()

/** Distance at which an expected-dirty observation surfaces the confirm sheet. */
private const val RECTIFY_PROXIMITY_METERS = 5.0
