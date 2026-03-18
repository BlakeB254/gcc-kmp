package com.gcc.app.viewmodel

import com.gcc.app.platform.AiDirectionEngine
import com.gcc.app.platform.ArScanner
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.detectDeviceType
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── UI state ─────────────────────────────────────────────────────────

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
    val isLoopClosed: Boolean = false,
    val isSubmitting: Boolean = false,
    val isComplete: Boolean = false,
    val pointsAwarded: Int = 0,
    val error: String? = null,
    val aiDirectionText: String? = null,
)

// ── ViewModel ─────────────────────────────────────────────────────────

class RouteScanViewModel(
    private val apiClient: GccApiClient,
    private val locationProvider: LocationProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(RouteScanUiState())
    val uiState: StateFlow<RouteScanUiState> = _uiState.asStateFlow()

    private val coverageTracker = CoverageTracker()
    private val aiDirectionEngine = AiDirectionEngine()
    private val arScanner = ArScanner()

    // GPS breadcrumb paths for GeoJSON
    private val scoutPath = mutableListOf<Pair<Double, Double>>()
    private val cleanPath = mutableListOf<Pair<Double, Double>>()

    // Coroutine jobs
    private var locationJob: Job? = null
    private var timerJob: Job? = null
    private var coverageJob: Job? = null
    private var aiDirectionJob: Job? = null

    // ── Initialisation ───────────────────────────────────────────────

    fun initForRoute(route: Route) {
        timerJob?.cancel()
        locationJob?.cancel()
        coverageJob?.cancel()
        aiDirectionJob?.cancel()
        scoutPath.clear()
        cleanPath.clear()

        val deviceType = detectDeviceType()

        _uiState.value = RouteScanUiState(
            routeId = route.id,
            route = route,
            deviceType = deviceType,
            phase = ScanPhase.SCOUTING,
        )

        // Initialise coverage grid from route checkpoints
        val waypoints = route.checkpoints
            .sortedBy { it.sequenceOrder }
            .map { it.coordinates }
        coverageTracker.initFromRoute(waypoints)

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
                // Get first GPS fix
                locationProvider.startLocationUpdates()
                val location = locationProvider.currentLocation.value
                    ?: run {
                        // Wait briefly for first fix
                        delay(2000)
                        locationProvider.currentLocation.value
                    }

                val lat = location?.lat ?: 0.0
                val lng = location?.lng ?: 0.0

                val state = _uiState.value
                val scan = apiClient.createScan(
                    CreateScanRequest(
                        routeId = state.routeId,
                        deviceType = state.deviceType,
                        startLat = lat,
                        startLng = lng,
                    )
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
                startTimer()
            } catch (e: Exception) {
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
    ) {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                val hotspot = apiClient.addScanHotspot(
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
                        arAnchorData = arAnchorData,
                        depthData = depthData,
                    )
                )
                _uiState.value = _uiState.value.copy(
                    hotspots = _uiState.value.hotspots + hotspot,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to add hotspot: ${e.message}",
                )
            }
        }
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
                )
            } catch (e: Exception) {
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
                _uiState.value = _uiState.value.copy(
                    hotspots = _uiState.value.hotspots.map {
                        if (it.id == hotspotId) updated else it
                    },
                    nearbyHotspot = null,
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
                _uiState.value = _uiState.value.copy(
                    hotspots = _uiState.value.hotspots.map {
                        if (it.id == hotspotId) updated else it
                    },
                    nearbyHotspot = null,
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
        locationProvider.stopLocationUpdates()

        scope.launch {
            _uiState.value = state.copy(
                phase = ScanPhase.SUBMITTING,
                isSubmitting = true,
                error = null,
            )
            try {
                val totalVolume = state.hotspots.sumOf { it.estimatedVolumeLiters ?: 0.0 }
                    .takeIf { it > 0 }
                val totalWeight = state.hotspots.sumOf { it.estimatedWeightLbs ?: 0.0 }
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
                _uiState.value = _uiState.value.copy(
                    phase = ScanPhase.CLEANING,
                    isSubmitting = false,
                    error = "Failed to submit scan: ${e.message}",
                )
            }
        }
    }

    // ── State queries ────────────────────────────────────────────────

    fun canCompleteScout(): Boolean {
        val state = _uiState.value
        return state.coveragePercent >= 85.0 && state.isLoopClosed
    }

    fun allHotspotsDone(): Boolean {
        return _uiState.value.hotspots.none { it.status == HotspotStatus.DETECTED }
    }

    // ── Internal: location tracking ──────────────────────────────────

    private fun startLocationTracking() {
        locationJob?.cancel()
        locationJob = scope.launch {
            locationProvider.locationFlow.collect { location ->
                val state = _uiState.value
                val lat = location.lat
                val lng = location.lng

                _uiState.value = state.copy(currentLat = lat, currentLng = lng)

                when (state.phase) {
                    ScanPhase.SCOUTING -> {
                        scoutPath.add(Pair(lat, lng))

                        // AR tier: use camera frustum for coverage; GPS tier: use radius
                        val heading = locationProvider.headingDegrees.value
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

                        // Check proximity to uncleaned hotspots (20m threshold)
                        val nearby = _uiState.value.hotspots
                            .filter { it.status == HotspotStatus.DETECTED }
                            .minByOrNull { hotspot ->
                                GeofenceVerification.distanceMeters(lat, lng, hotspot.lat, hotspot.lng)
                            }
                            ?.takeIf { hotspot ->
                                GeofenceVerification.distanceMeters(lat, lng, hotspot.lat, hotspot.lng) <= 20.0
                            }

                        _uiState.value = _uiState.value.copy(nearbyHotspot = nearby)
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

    // ── GeoJSON helper ───────────────────────────────────────────────

    fun buildGeoJsonLineString(path: List<Pair<Double, Double>>): String? {
        if (path.size < 2) return null
        val coords = path.joinToString(",") { (lat, lng) -> "[${lng},${lat}]" }
        return """{"type":"LineString","coordinates":[$coords]}"""
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    fun onCleared() {
        timerJob?.cancel()
        locationJob?.cancel()
        coverageJob?.cancel()
        aiDirectionJob?.cancel()
        aiDirectionEngine.clear()
        locationProvider.stopLocationUpdates()
    }
}
