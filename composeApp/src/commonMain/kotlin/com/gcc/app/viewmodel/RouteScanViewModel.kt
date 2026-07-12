package com.gcc.app.viewmodel

import com.gcc.app.platform.AiDirectionEngine
import com.gcc.app.platform.ArMarkerQueue
import com.gcc.app.platform.ArScanner
import com.gcc.app.platform.BuildingFootprintLoader
import com.gcc.app.platform.CellCredit
import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.CoverageEventQueue
import com.gcc.app.platform.CoverageQuality
import com.gcc.app.platform.CoverageRetractionQueue
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.purgeAndRetractCoverage
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.EnvScanSessionRecorder
import com.gcc.app.platform.EnvScanSessionUploader
import com.gcc.app.platform.capture.CaptureChunkUploader
import com.gcc.app.platform.deleteLocalFile
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.OutdoorGate
import com.gcc.app.platform.OutdoorState
import com.gcc.app.platform.RaycastCoverageHit
import com.gcc.app.platform.SceneVote
import com.gcc.app.platform.SessionPointsEstimator
import com.gcc.app.platform.bestOf
import com.gcc.app.platform.classifyFogAndStale
import com.gcc.app.platform.coverageTier
import com.gcc.app.platform.detectDeviceCapabilities
import com.gcc.app.platform.detectDeviceType
import com.gcc.app.platform.fanHitQuality
import com.gcc.app.platform.getDeviceModel
import com.gcc.app.platform.getOsVersion
import com.gcc.app.platform.getPlatformName
import com.gcc.app.platform.retractionOutcome
import com.gcc.app.platform.runOrphanRecoverySweepOnce
import com.gcc.app.platform.sessionJournalPath
import com.gcc.app.platform.wireQualityForSegment
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.geo.gridCell10m
import com.gcc.shared.geo.hasScannableGeometry
import com.gcc.shared.geo.innerRingCoordinates
import com.gcc.shared.geo.outerRingCoordinates
import com.gcc.shared.geo.pointInPolygon
import com.gcc.shared.geo.routeCenterCoordinates
import com.gcc.shared.geo.routePathCoordinates
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * A cluster of trash landmarks documented during the first (baseline) lap.
 * Lap 2 guides the worker through these, largest problem first.
 */
data class ProblemArea(
    val center: Coordinates,
    val landmarkCount: Int,
    val severityWeight: Int,
)

data class RouteScanUiState(
    val scanId: Int? = null,
    val routeId: Int = 0,
    val route: Route? = null,
    val deviceType: DeviceType = DeviceType.GPS,
    val phase: ScanPhase = ScanPhase.SCOUTING,
    val coveragePercent: Double = 0.0,
    val hotspots: List<ScanHotspot> = emptyList(),
    /** True while the worker is walking too fast for good scan coverage. */
    val movingTooFast: Boolean = false,
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
    /** 1 = baseline documentation lap; 2 = cleanup lap guided by landmarks. */
    val lapNumber: Int = 1,
    /** [elapsedSeconds] value when the CURRENT lap started — lap clock =
     *  elapsedSeconds - this. 0 for lap 1 (the session clock IS its clock). */
    val lapStartedAtElapsedSeconds: Long = 0,
    /** Landmark clusters from lap 1, largest problem first. */
    val problemAreas: List<ProblemArea> = emptyList(),
    /** The problem area lap 2 is currently steering the worker toward. */
    val guidanceTarget: ProblemArea? = null,
    /**
     * Pillar 9 block-completion report (duration, mini route path, weight
     * cleaned, before/after photo pairs, counts), fetched after job
     * completion. Null while [completionReportLoading] is true, and also
     * null (with loading false and no error) after a fetch that 404'd —
     * that's the "still generating" state; see
     * [RouteScanViewModel.retryCompletionReportFetch].
     */
    val completionReport: BlockCompletionReport? = null,
    val completionReportLoading: Boolean = false,
    val completionReportError: String? = null,
)

// ── ViewModel ─────────────────────────────────────────────────────────

class RouteScanViewModel(
    private val apiClient: GccApiClient,
    private val locationProvider: LocationProvider,
    private val sessionUploader: EnvScanSessionUploader,
    private val captureUploader: CaptureChunkUploader,
    private val captureChunkStore: CaptureChunkStore,
    private val buildingFootprintLoader: BuildingFootprintLoader,
) {
    companion object {
        /**
         * Corridor fill that completes the baseline lap. Not a hard 100%:
         * GPS/frustum coverage can never credit slivers pinched between the
         * boundary padding and parked cars, so a strict 100% would strand
         * workers circling for unreachable cells. Displayed as the fill bar's
         * "complete" state.
         */
        const val LAP_COMPLETE_PERCENT = 95.0

        /** Landmarks within this distance cluster into one problem area. */
        const val PROBLEM_CLUSTER_METERS = 30.0

        /** Reaching this close to a guidance target advances to the next one. */
        const val PROBLEM_AREA_REACHED_METERS = 15.0

        /**
         * Block-completion report generation (Pillar 9) is fire-and-forget on
         * the server, kicked off right after the job-completion PATCH — so
         * the first fetch can easily 404 if it lands before generation
         * finishes. One retry after this delay covers the common case
         * without blocking the completion screen for long; a persistent
         * "still generating" state after that falls back to the manual
         * refresh action ([retryCompletionReportFetch]) surfaced in the UI.
         */
        const val COMPLETION_REPORT_RETRY_DELAY_MS = 2500L
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fire-and-forget work that must SURVIVE [onCleared]'s `scope.cancel()`
     *  (queue flushes, the once-per-process orphan sweep whose guard is
     *  consumed even on cancellation, marker enqueues mid-upload). Each call
     *  runs in its own short-lived scope that self-cancels on completion, so
     *  nothing leaks while [scope] stays free to be cancelled immediately. */
    private fun launchDetached(block: suspend () -> Unit) {
        val detached = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        detached.launch { block() }.invokeOnCompletion { detached.cancel() }
    }

    private val _uiState = MutableStateFlow(RouteScanUiState())
    val uiState: StateFlow<RouteScanUiState> = _uiState.asStateFlow()

    // Whether the worker's current GPS position is outside the route's assigned
    // block boundary. Consumed by the AR overlay (boundary-crossing warning).
    private val _isOutsideBoundary = MutableStateFlow(false)
    val isOutsideBoundary: StateFlow<Boolean> = _isOutsideBoundary.asStateFlow()

    // WS2: scene-aware flashlight beam ring (lat, lng), recomputed each
    // raycast tick — ArRouteScreen renders it on the route minimap.
    private val _visibilityBeam = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val visibilityBeam: StateFlow<List<Pair<Double, Double>>> = _visibilityBeam.asStateFlow()

    // Block boundary polygon as (lat, lng) pairs, matching PolygonContainment's
    // pointInPolygon convention. Empty when no boundary is loaded for the route.
    private var boundaryPolygon: List<Pair<Double, Double>> = emptyList()

    // Boundary rings for the minimap: outer block outline + inner exclusion
    // islands, as (lat, lng). Set once per initForRoute.
    private val _minimapBoundaries = MutableStateFlow<Pair<List<Pair<Double, Double>>, List<List<Pair<Double, Double>>>>>(
        emptyList<Pair<Double, Double>>() to emptyList()
    )
    val minimapBoundaries: StateFlow<Pair<List<Pair<Double, Double>>, List<List<Pair<Double, Double>>>>> =
        _minimapBoundaries.asStateFlow()

    /** Required-corridor cell centers (static per route) — the minimap fill base. */
    fun corridorCellCenters(): List<Pair<Double, Double>> = coverageTracker.getCorridorCenters()

    /**
     * Cells this scan session's [coverageTracker] actually swept at GRAZED+
     * quality (the same set backing the minimap fill/fog), translated to
     * server `gridCell()` ids for the Pillar 8 task 1 cell-supersede sweep
     * (site/src/lib/world-observations.ts's `resolveByCoveredCells`).
     * Capped at 2000 to match the server's `parseCoveredCellIds` truncation.
     *
         * SpotViewModel sends the equivalent set on its `/api/spots`
         * submission. Route scans include this set in `CompleteScanRequest` so
         * final submission can resolve stale observations across the swept cells,
         * not only at hotspot anchors.
     */
    fun coveredCellIdsForSubmission(): List<String> =
        coverageTracker.scannedCells.value
            .map { (row, col) -> coverageTracker.cellCenterLatLng(row, col) }
            .map { (lat, lng) -> gridCell10m(lat, lng) }
            .distinct()
            .take(2000)

    // One recorder per lap: the baseline lap and the clean lap each upload as
    // their own session manifest (route-v2 design step 1/5). journalPath is
    // computed from a pre-generated sessionId (Pillar 6) — null on desktop/
    // android, where the recorder stays pure in-memory.
    private var lapSessionRecorder = newLapSessionRecorder()
    private var lapTransitionFired = false

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
    // Coverage-ledger retractions (design spec §2): undoes already-flushed
    // events for the trailing purge window on an INDOOR flip. Same api-client
    // reference coverageEventQueue's submitFn uses above.
    private val retractionQueue = CoverageRetractionQueue(
        submitFn = { request -> retractionOutcome(request, apiClient::retractCoverageEvents) },
    )
    private val coverageDeviceTier by lazy { detectDeviceCapabilities().deviceClass.coverageTier() }
    private var lastCoverageFix: Pair<Double, Double>? = null
    private var lastCoverageFixAtMs: Long? = null
    private var fastFixStreak = 0
    private var coverageFlushJob: Job? = null
    /** Best fan-hit quality (close > grazed > seen) credited since the last
     *  coverage event, or null when no fan hit has landed yet this window.
     *  Volatile: written from the main-thread fan listener (onCoverageRayHits),
     *  read/reset from the Dispatchers.Default location collector. */
    @kotlin.concurrent.Volatile
    private var bestFanQualitySinceLastEvent: CoverageQuality? = null

    // AR-detected marker queue: live-detected (CONFIRMED_TRASH) markers from the
    // AR screen queue locally and flush alongside the coverage-ledger events.
    private val arMarkerQueue = ArMarkerQueue(
        submitFn = { markers ->
            try {
                apiClient.reportArDetectedMarkers(markers)
            } catch (e: Exception) {
                DiagnosticsLog.error("ar-marker-flush", "flush of ${markers.size} markers failed (kept queued)", e)
                throw e
            }
        },
    )

    init {
        // Catch up lap manifests that couldn't reach R2 last time (offline /
        // storage unprovisioned). Spot scans always did this on open; route
        // manifests queued here used to strand until the user happened to
        // open an ENV scan (the only other flushPending caller).
        launchDetached { sessionUploader.flushPending() }
        // Same opportunistic catch-up for background capture chunks (Pillar
        // 1) — route scans don't produce them (v1 scope guard: ENV/SPOT
        // only), but any pending from a prior Spot session still deserve a
        // chance to flush whenever ANY scan screen opens.
        launchDetached { captureUploader.flushPending() }
        // Pillar 6 orphan recovery — same call SpotViewModel makes. This VM
        // is a Koin `factory` (recreated per screen open), but the sweep's
        // internal once-per-process guard means only the FIRST scan-screen VM
        // constructed after launch actually lists the directories; every
        // later construction is a no-op. Active-session exclusion comes from
        // lazy journal-file creation (the lapSessionRecorder property
        // initializer above creates no file — see the sweep's KDoc).
        launchDetached { runOrphanRecoverySweepOnce(sessionUploader, captureChunkStore) }
    }

    /** Pre-generates the sessionId so [sessionJournalPath] can be resolved
     *  BEFORE constructing the recorder (journalPath is a constructor arg). */
    private fun newLapSessionRecorder(): EnvScanSessionRecorder {
        val sessionId = EnvScanSessionRecorder.generateSessionId()
        return EnvScanSessionRecorder(sessionId = sessionId, journalPath = sessionJournalPath(sessionId))
    }

    /** Live-detected AR marker (ArRouteScreen), with its converted lat/lng and
     *  a best-effort JPEG captured at promotion time (null if capture/upload
     *  wasn't possible — the marker is never blocked on the photo). */
    fun onMarkerPlaced(lat: Double, lng: Double, label: String, photoBytes: ByteArray? = null) {
        if (!outdoorGate.captureAllowed.value) return
        if (!lat.isFinite() || !lng.isFinite() || label.isBlank()) {
            DiagnosticsLog.error("ar-marker", "dropping invalid marker: lat=$lat lng=$lng label='$label'")
            return
        }
        // Detached (not [scope]): a marker placed right before leaving the
        // screen must still finish its upload-then-enqueue — onCleared's
        // scope.cancel() would otherwise drop it before it reaches the
        // persisted queue.
        launchDetached {
            // Upload-then-enqueue: the photo is best-effort and must never
            // block the marker, and raw bytes never enter the persisted
            // ArMarkerQueue — only the resolved URL (or null on failure).
            val mediaUrl = photoBytes?.let { bytes ->
                try {
                    apiClient.uploadSpotPhotos(listOf(bytes)).firstOrNull()
                } catch (e: Exception) {
                    DiagnosticsLog.error("ar-marker", "marker photo upload failed (enqueuing without photo)", e)
                    null
                }
            }
            arMarkerQueue.enqueue(
                ArDetectedMarkerRequest(
                    lat = lat, lng = lng,
                    // Canonical type, not the display label — the server
                    // stores this verbatim as world_observations.itemType.
                    label = com.gcc.app.platform.TrashItemCatalog.canonicalType(label),
                    capturedAt = Clock.System.now(),
                    mediaUrl = mediaUrl,
                ),
            )
            // Real-time streaming (spec system 3), same immediate kick as the
            // env-scan path (SpotViewModel.addObservedItem): don't wait for
            // the periodic sweep to put a just-placed marker on the public
            // map. The periodic flushes elsewhere stay as the retry sweep.
            arMarkerQueue.flush()
        }
    }

    // "Go outside" gate: GPS-accuracy evidence is fed here from the location
    // stream; the AR screen feeds camera scene votes via [onSceneVote]. While
    // INDOOR, coverage accrual (ledger events + tracker cells) is suppressed.
    private val outdoorGate = OutdoorGate()
    val outdoorState: StateFlow<OutdoorState> = outdoorGate.state
    val captureAllowed: StateFlow<Boolean> = outdoorGate.captureAllowed
    private var accuracyJob: Job? = null

    /** Scene-classification vote from the AR camera feed (ArRouteScreen). */
    /** Field escape hatch — user confirmed they're outdoors. */
    fun forceOutdoors() {
        outdoorGate.forceOutdoors()
        lapSessionRecorder.noteGateEvent("override@${Clock.System.now().toEpochMilliseconds()}")
    }

    fun onSceneVote(vote: SceneVote) {
        outdoorGate.onSceneVote(vote, Clock.System.now().toEpochMilliseconds())
    }

    /** Ceiling-plane evidence from the AR screen (ArRouteScreen). */
    fun onCeilingPlane(detected: Boolean) {
        outdoorGate.onCeilingPlane(detected, Clock.System.now().toEpochMilliseconds())
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
    private val _cellPriorStates = MutableStateFlow<Map<Pair<Int, Int>, String>>(emptyMap())
    private var cellPriorStates: Map<Pair<Int, Int>, String>
        get() = _cellPriorStates.value
        set(value) { _cellPriorStates.value = value }

    // Minimap projections (route-scan AR screen): fog = never scanned by
    // anyone; stale = scanned before, ledger says re-visit. Both recompute
    // whenever either the server priors arrive or a cell gets scanned this
    // session (see the coverageJob collector in initForRoute).
    private val _staleCellCenters = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val staleCellCenters: StateFlow<List<Pair<Double, Double>>> = _staleCellCenters.asStateFlow()
    private val _freshCellCenters = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val freshCellCenters: StateFlow<List<Pair<Double, Double>>> = _freshCellCenters.asStateFlow()

    private fun refreshFogAndStaleCenters() {
        // Classify against the session-only unscanned set (ignores ledger
        // fog-exemption) so seeding stale cells as fog-exempt (below) doesn't
        // erase the "needs revisit" minimap indicator for cells not yet
        // re-scanned this session.
        val classification = classifyFogAndStale(coverageTracker.getUnscannedCellsIgnoringSeed(), cellPriorStates)
        _staleCellCenters.value = classification.staleCells.map { (row, col) -> coverageTracker.cellCenterLatLng(row, col) }
        // Ledger-fresh cells punch out of the minimap veil (already-scanned sky).
        _freshCellCenters.value = cellPriorStates
            .filterValues { it == "fresh" }
            .keys.map { (row, col) -> coverageTracker.cellCenterLatLng(row, col) }
    }

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

    // ── Lap-2 weight progress ────────────────────────────────────────
    // Percentage = confirmed-cleaned estimated weight ÷ total estimated
    // weight identified in lap 1 (see Lap2WeightMath). Snapshot of every
    // dirty observation surfaced for the route (rectified ones are removed
    // from _expectedObservations, so they need their own ledger here).
    private var lap1Observations: List<WorldObservation> = emptyList()
    private val rectifiedObservationIds = mutableSetOf<Int>()
    private val _lap2WeightStats = MutableStateFlow(Lap2WeightProgress())
    /** Weight-based lap-2 stats (lbs + item counts) for the cleaning UI. */
    val lap2WeightStats: StateFlow<Lap2WeightProgress> = _lap2WeightStats.asStateFlow()
    private val _lap2WeightProgress = MutableStateFlow(0.0)
    /** Lap-2 progress percentage, 0–100 (cleaned est. weight ÷ lap-1 total). */
    val lap2WeightProgress: StateFlow<Double> = _lap2WeightProgress.asStateFlow()

    private fun recomputeLap2Weight() {
        val stats = Lap2WeightMath.compute(
            hotspots = _uiState.value.hotspots,
            observations = lap1Observations,
            rectifiedObservationIds = rectifiedObservationIds,
        )
        _lap2WeightStats.value = stats
        _lap2WeightProgress.value = stats.percent
    }

    /** "Still there" — keep the marker but stop prompting this session. */
    fun dismissExpectedObservation(observation: WorldObservation) {
        resolvedObservationIds.add(observation.id)
        _nearbyExpectedObservation.value = null
    }

    /** "Already clean" — record the rectification and credit the bonus. */
    fun rectifyObservation(observation: WorldObservation) {
        val scanId = _uiState.value.scanId ?: return
        resolvedObservationIds.add(observation.id)
        rectifiedObservationIds.add(observation.id)
        recomputeLap2Weight()
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
                rectifiedObservationIds.remove(observation.id)
                recomputeLap2Weight()
                _expectedObservations.value = _expectedObservations.value + observation
            }
        }
    }

    /**
     * Bring the digital twin into the AR fog: fetch `/api/map/coverage` for
     * the route's bounding box and (a) pre-reveal every cell the ledger
     * knows about at all — fresh OR stale, ledger presence lifts fog — (b)
     * remember fresh/stale priors for live points estimation AND the
     * minimap's separate "needs revisit" stale indicator. Server cells are
     * 10m; each maps onto the 3m client cells inside its footprint. Fetch
     * failures degrade silently to session-local fog.
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
                // Ledger presence lifts fog regardless of freshness (owner law):
                // fresh AND stale cells are both fog-exempt. "Stale" still gets
                // its own minimap revisit indicator via cellPriorStates +
                // getUnscannedCellsIgnoringSeed (see refreshFogAndStaleCenters) —
                // seeding it here only suppresses the AR fog overlay, not the
                // revisit prompt.
                val ledgerKnown = mutableSetOf<Pair<Int, Int>>()
                for (serverCell in serverCells) {
                    for (cell in clientCellsForServerCell(serverCell.lat, serverCell.lng)) {
                        priors[cell] = serverCell.state
                        ledgerKnown.add(cell)
                    }
                }
                cellPriorStates = priors
                refreshFogAndStaleCenters()
                if (ledgerKnown.isNotEmpty()) coverageTracker.seedScannedCells(ledgerKnown)
                DiagnosticsLog.info("fog-seed", "seeded ${ledgerKnown.size} ledger-known cells, ${priors.size} priors from ${serverCells.size} server cells")
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
                lap1Observations = _expectedObservations.value
                recomputeLap2Weight()
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

    /** Set of scanned (GRAZED+) coverage-grid cells (row, col). */
    val scannedCells: StateFlow<Set<Pair<Int, Int>>> get() = coverageTracker.scannedCells

    /** Set of SEEN-only coverage-grid cells (row, col) — sighted at a
     *  distance via the raycast fan, never walked. */
    val seenCells: StateFlow<Set<Pair<Int, Int>>> get() = coverageTracker.seenCells

    /** Per-cell credit (row, col) → tier/timestamp — the source ArRouteScreen
     *  mirrors to build CLOSE-tier cell centers for the minimap. */
    val creditedCells: StateFlow<Map<Pair<Int, Int>, CellCredit>> get() = coverageTracker.creditedCells

    /** Real-world (lat, lng) center of a coverage-grid cell. */
    fun cellCenterLatLng(row: Int, col: Int): Pair<Double, Double> =
        coverageTracker.cellCenterLatLng(row, col)

    /** Geographic origin of the coverage cell lattice (null before the route
     *  grid initializes) — phases the AR ground grid onto the same lattice
     *  the minimap draws. */
    fun latticeOrigin(): Pair<Double, Double>? = coverageTracker.latticeOrigin()

    /** Device compass heading in degrees, or null when unavailable. */
    val headingDegrees: StateFlow<Float?> get() = locationProvider.headingDegrees

    /** GPS horizontal accuracy in meters (null = no valid fix) — screens gate
     *  AR geo-reference pairing on this (see [isGeoRefFixUsable]). */
    val gpsAccuracyMeters: StateFlow<Double?> get() = locationProvider.horizontalAccuracyMeters

    // GPS breadcrumb paths for GeoJSON.
    // These grow at ~1 Hz; a 30-min scan produces ~1800 entries. Downsampled to
    // ≤500 points in buildGeoJsonLineString() before transmission to the server.
    private val scoutPath = mutableListOf<Pair<Double, Double>>()
    private val cleanPath = mutableListOf<Pair<Double, Double>>()
    private var allowUnpublishedRoute = false
    private var onBehalfOfWorkerId: Int? = null

    // Assignment path: job to mark COMPLETED once the scan submits. Null on
    // the free-scan path (route detail / admin proxy).
    private var completeJobId: Int? = null

    // Coroutine jobs
    private var locationJob: Job? = null
    private var timerJob: Job? = null
    private var coverageJob: Job? = null
    private var aiDirectionJob: Job? = null

    // ── Initialisation ───────────────────────────────────────────────

    fun initForRoute(
        route: Route,
        allowUnpublished: Boolean = false,
        onBehalfOfWorkerId: Int? = null,
        completeJobId: Int? = null,
    ) {
        allowUnpublishedRoute = allowUnpublished
        this.onBehalfOfWorkerId = onBehalfOfWorkerId
        this.completeJobId = completeJobId
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
                error = "This route is not available yet. An admin must publish it and give it a block boundary (or path) before scanning can start.",
            )
            return
        }

        val deviceType = detectDeviceType()
        val routePath = route.routePathCoordinates()
        val sectionAnchor = route.routeCenterCoordinates() ?: routePath.firstOrNull()
        val sections = sectionAnchor?.let { anchor ->
            listOf(
                RouteScanSectionUiItem(
                    index = 0,
                    name = "Route scan area",
                    coordinates = anchor,
                )
            )
        }.orEmpty()

        _uiState.value = RouteScanUiState(
            routeId = route.id,
            route = route,
            deviceType = deviceType,
            phase = ScanPhase.SCOUTING,
            routeSections = sections,
            sectionPrompt = when {
                sections.isEmpty() -> "Lap 1: walk the block and scan the full area."
                deviceType == DeviceType.AR -> "Start the area scan and walk the full route boundary."
                else -> "Start the route scan and capture observations as you find them."
            },
        )

        // Initialise coverage grid. When the route carries authored block
        // Boundaries define the required scan area. Older route geometry remains
        // as a line-proximity fallback when a block boundary is not available.
        val waypoints = routePath
        val outerRing = route.blockBoundary?.outerRingCoordinates().orEmpty()
        val innerRings = route.innerBoundary.innerRingCoordinates()
        if (outerRing.size >= 3) {
            coverageTracker.initFromBlock(outerRing, innerRings, waypoints)
        } else {
            coverageTracker.initFromRoute(waypoints)
        }
        _minimapBoundaries.value =
            outerRing.map { it.lat to it.lng } to innerRings.map { ring -> ring.map { it.lat to it.lng } }
        lapSessionRecorder = newLapSessionRecorder()
        lapTransitionFired = false
        visitedProblemAreas.clear()
        lap1Observations = emptyList()
        rectifiedObservationIds.clear()
        recomputeLap2Weight()
        seedFogFromServerCoverage(waypoints)

        // Observe coverage changes
        coverageJob = scope.launch {
            coverageTracker.coveragePercent.collect { pct ->
                _uiState.value = _uiState.value.copy(coveragePercent = pct)
            }
        }
        scope.launch {
            coverageTracker.scannedCells.collect { refreshFogAndStaleCenters() }
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
                        "This route is not available yet. An admin must publish it and give it a block boundary (or path) before scanning can start."
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
                recomputeLap2Weight()
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

    /**
     * Advance SCOUTING → SCOUT_REVIEW. [force] bypasses [canCompleteScout] so a
     * worker is never stranded in the scout lap when coverage stalls below the
     * thresholds and a section can't be photo-recorded (the only otherwise-
     * reachable escape is all-sections-recorded, which is all-or-nothing). The
     * UI gates the forced path behind a confirm dialog; coverage still uploads.
     */
    fun completeScout(force: Boolean = false) {
        if (!force && !canCompleteScout()) return
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
                    problemAreas = clusterProblemAreas(_uiState.value.hotspots),
                )
                uploadLapSession(lapKind = "baseline")
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
                lapSessionRecorder = newLapSessionRecorder() // lap 2 = its own session
                val currentState = _uiState.value
                val areas = currentState.problemAreas
                    .ifEmpty { clusterProblemAreas(currentState.hotspots) }
                _uiState.value = currentState.copy(
                    phase = ScanPhase.CLEANING,
                    error = null,
                    lapNumber = 2,
                    lapStartedAtElapsedSeconds = currentState.elapsedSeconds,
                    problemAreas = areas,
                    guidanceTarget = areas.firstOrNull(),
                    sectionPrompt = if (areas.isNotEmpty()) {
                        "Lap 2: head to the biggest problem area (${areas.first().landmarkCount} landmark(s))."
                    } else {
                        "Record the after scan for the first cleanup target."
                    },
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
                recomputeLap2Weight()
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
                recomputeLap2Weight()
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
        scope.launch { coverageEventQueue.flush(); arMarkerQueue.flush() }

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
                        coveredCellIds = coveredCellIdsForSubmission(),
                    )
                )
                _uiState.value = _uiState.value.copy(
                    phase = ScanPhase.COMPLETE,
                    isSubmitting = false,
                    isComplete = true,
                    pointsAwarded = response.pointsAwarded,
                )
                uploadLapSession(lapKind = "clean")

                // Assignment path: the submitted scan IS the job's work —
                // flip the job to COMPLETED. Non-fatal on failure: the scan
                // stays COMPLETE and an admin can complete the job manually.
                completeJobId?.let { jobId ->
                    try {
                        apiClient.completeJob(jobId)
                    } catch (e: Exception) {
                        DiagnosticsLog.error(
                            "scan-submit",
                            "scan $scanId submitted but completing job $jobId failed",
                            e,
                        )
                    }
                    // Best-effort: the job is COMPLETE either way once we've
                    // reached this point, and the report is a nice-to-have on
                    // top of it. fetchCompletionReport handles its own
                    // errors/404s and never throws.
                    fetchCompletionReport(jobId)
                }
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

    /**
     * Fetches the Pillar 9 block-completion report for [jobId]. The server
     * generates it non-fatally right after job completion (see
     * completeScan's completeJob call above), so the first GET can 404 if it
     * lands before generation finishes — this retries once after
     * [COMPLETION_REPORT_RETRY_DELAY_MS] before settling into the "still
     * generating" state (completionReport == null, loading == false, error
     * == null), which the UI surfaces with a manual-refresh affordance via
     * [retryCompletionReportFetch]. Never throws: all failures (including a
     * persistent 404) land in uiState, not as an exception the caller has to
     * handle.
     */
    private suspend fun fetchCompletionReport(jobId: Int) {
        _uiState.value = _uiState.value.copy(
            completionReportLoading = true,
            completionReportError = null,
        )
        try {
            var report = apiClient.getCompletionReport(jobId)
            if (report == null) {
                delay(COMPLETION_REPORT_RETRY_DELAY_MS)
                report = apiClient.getCompletionReport(jobId)
            }
            _uiState.value = _uiState.value.copy(
                completionReport = report,
                completionReportLoading = false,
            )
        } catch (e: Exception) {
            DiagnosticsLog.error(
                "scan-submit",
                "completion report fetch failed for job $jobId",
                e,
            )
            _uiState.value = _uiState.value.copy(
                completionReportLoading = false,
                completionReportError = e.message ?: "Couldn't load the completion report",
            )
        }
    }

    /** Manual-refresh entry point for the "still generating" completion-report state. */
    fun retryCompletionReportFetch() {
        val jobId = completeJobId ?: return
        scope.launch { fetchCompletionReport(jobId) }
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
        return state.coveragePercent >= LAP_COMPLETE_PERCENT ||
            (state.coveragePercent >= 85.0 && state.isLoopClosed) ||
            allSectionsRecorded
    }

    fun allHotspotsDone(): Boolean {
        return _uiState.value.hotspots.none { it.status == HotspotStatus.DETECTED }
    }

    // ── Lap sessions + problem-area guidance ─────────────────────────

    private val visitedProblemAreas = mutableSetOf<ProblemArea>()

    /**
     * Greedy proximity clustering of lap-1 landmarks: each cluster's weight is
     * its severity sum, and the list comes back largest-problem-first — the
     * order lap 2 walks them in.
     */
    private fun clusterProblemAreas(hotspots: List<ScanHotspot>): List<ProblemArea> {
        val remaining = hotspots.toMutableList()
        val areas = mutableListOf<ProblemArea>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val cluster = mutableListOf(seed)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (GeofenceVerification.distanceMeters(
                        seed.lat, seed.lng, candidate.lat, candidate.lng,
                    ) <= PROBLEM_CLUSTER_METERS
                ) {
                    cluster.add(candidate)
                    iterator.remove()
                }
            }
            areas.add(
                ProblemArea(
                    center = Coordinates(
                        lat = cluster.map { it.lat }.average(),
                        lng = cluster.map { it.lng }.average(),
                    ),
                    landmarkCount = cluster.size,
                    severityWeight = cluster.sumOf { it.severity },
                )
            )
        }
        return areas.sortedByDescending { it.severityWeight }
    }

    /**
     * Archive the current lap's capture session to R2 (fire-and-forget; a
     * failed upload queues persistently in [EnvScanSessionUploader]).
     */
    private fun uploadLapSession(lapKind: String) {
        val recorder = lapSessionRecorder
        val state = _uiState.value
        scope.launch {
            try {
                val draft = recorder.draft(
                    coverageTracker.getScannedCorridorCells()
                        .map { (row, col) -> coverageTracker.cellCenterLatLng(row, col) }
                )
                // Pillar 6: this recorder's session is done either way (an
                // empty draft archives nothing below) — its on-disk journal
                // has no further use. No-op on desktop/android (journalPath
                // is always null there).
                sessionJournalPath(draft.sessionId)?.let { deleteLocalFile(it) }
                if (draft.path.isEmpty()) return@launch // nothing recorded — nothing to archive
                val manifest = EnvScanManifest(
                    sessionId = draft.sessionId,
                    deviceId = CoverageDeviceId.value,
                    deviceModel = getDeviceModel(),
                    osVersion = getOsVersion(),
                    startedAtMs = draft.startedAtMs,
                    completedAtMs = Clock.System.now().toEpochMilliseconds(),
                    path = draft.path,
                    scannedCells = draft.scannedCells,
                    scannedCellSizeMeters = CoverageTracker.cellSizeMeters,
                    // The lap's hotspots ARE its litter record — without them
                    // the R2 bundle the DGX world-model pipeline reads carried
                    // no items at all (hotspots only reached their own API).
                    // itemType uses the SpotTag vocabulary (lowercase), not
                    // TrashItemCatalog canonical types.
                    observedItems = state.hotspots.map { h ->
                        ObservedSpotItem(
                            lat = h.lat,
                            lng = h.lng,
                            itemType = h.trashTypes.firstOrNull()?.name?.lowercase() ?: "trash",
                            severity = h.severity,
                            confidence = h.confidence,
                            atMs = h.detectedAt.toEpochMilliseconds(),
                        )
                    },
                    photoKeys = emptyList(),
                    lapKind = lapKind,
                    routeId = state.routeId,
                    gateEvents = draft.gateEvents,
                    // Route laps don't run background capture today, so this
                    // is normally empty — carried anyway so the manifest shape
                    // stays uniform for the DGX pipeline.
                    geoRefEvents = draft.geoRefEvents.ifEmpty { null },
                    arFrameConvention = EnvScanManifest.AR_FRAME_CONVENTION,
                    diagnosticsLog = DiagnosticsLog.tailForManifest(),
                )
                sessionUploader.uploadOrQueue(manifest)
            } catch (e: Exception) {
                DiagnosticsLog.error("env-capture", "$lapKind lap session archive failed", e)
            }
        }
    }

    fun setError(message: String?) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    /** Raycast-fan hits from the route AR overlay (ArRouteScreen wires the listener).
     *  [deepAllowed] gates the deep CLOSE shade on scan quality (WS3);
     *  submission quality via [fanHitQuality] stays ungated. */
    fun onCoverageRayHits(hits: List<RaycastCoverageHit>, deepAllowed: Boolean = true) {
        if (!outdoorGate.captureAllowed.value) return
        if (_uiState.value.phase != ScanPhase.SCOUTING) return
        coverageTracker.updateWithRaycastHits(hits, deepAllowed = deepAllowed)
        bestFanQualitySinceLastEvent = bestOf(bestFanQualitySinceLastEvent, fanHitQuality(hits))
        // Only overwrite the beam when this tick produced one — a transient
        // no-hit tick must not blank it (flicker).
        val s = _uiState.value
        val beam = com.gcc.app.ui.map.visibilityBeamPolygon(s.currentLat, s.currentLng, hits)
        if (beam.isNotEmpty()) _visibilityBeam.value = beam
    }

    // ── Internal: location tracking ──────────────────────────────────

    private fun startLocationTracking() {
        lastCoverageFix = null
        coverageFlushJob?.cancel()
        coverageFlushJob = scope.launch {
            while (true) {
                delay(30_000L)
                coverageEventQueue.flush() // failed flushes keep events queued
                arMarkerQueue.flush()
                retractionQueue.flush()
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
                    // Hide the flashlight beam whenever we're not OUTDOOR — it
                    // must not linger frozen on the minimap indoors (spec:
                    // beam hidden inside a building).
                    if (gateState != OutdoorState.OUTDOOR) _visibilityBeam.value = emptyList()
                    val nowMs = Clock.System.now().toEpochMilliseconds()
                    // Audit trail rides with whichever lap recorder is current
                    // at event time — a lap boundary may have swapped it out.
                    lapSessionRecorder.noteGateEvent("gate@$nowMs state=$gateState")
                    if (gateState == OutdoorState.INDOOR) {
                        // Privacy purge: drop the trailing walk-inside seconds
                        // recorded just before this flip so they never upload.
                        val cutoff = (outdoorGate.lastIndoorFlipAtMs ?: nowMs) - OutdoorGate.PURGE_WINDOW_MS
                        lapSessionRecorder.purgeSince(cutoff)
                        // Coverage companions (spec §2): local cells, pending
                        // events, server retraction of flushed ones.
                        coverageTracker.purgeSince(cutoff)
                        scope.launch {
                            purgeAndRetractCoverage(cutoff, nowMs, coverageEventQueue, retractionQueue)
                        }
                    }
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
                        // Ensure real footprints for this tile are cached +
                        // registered so isInsideMappedBuilding runs
                        // point-in-polygon (not the hardcoded rectangles).
                        // Best-effort; never blocks the gate below.
                        buildingFootprintLoader.ensureFootprintsFor(
                            lat, lng, Clock.System.now().toEpochMilliseconds(),
                        )
                        outdoorGate.onMapEvidence(
                            insideBuilding = com.gcc.app.ui.map.isInsideMappedBuilding(lat, lng),
                            timestampMs = Clock.System.now().toEpochMilliseconds(),
                        )

                        // "Go outside" gate: while capture isn't allowed (INDOOR, or
                        // still UNCERTAIN pending outdoor confirmation), accrue
                        // nothing — no path point, no ledger event, no coverage
                        // cells. Reset the segment anchor so no phantom distance
                        // bridges the gap once capture resumes.
                        if (!outdoorGate.captureAllowed.value) {
                            lastCoverageFix = null
                            fastFixStreak = 0
                            if (_uiState.value.movingTooFast) {
                                _uiState.value = _uiState.value.copy(movingTooFast = false)
                            }
                            return@collect
                        }
                        scoutPath.add(Pair(lat, lng))
                        lapSessionRecorder.addSample(
                            lat = lat,
                            lng = lng,
                            headingDegrees = arScanner.getArHeading() ?: locationProvider.headingDegrees.value,
                            accuracyMeters = locationProvider.horizontalAccuracyMeters.value,
                        )

                        // Coverage-ledger event: distance walked since the last fix.
                        // Desktop is dev-only and the endpoint rejects its platform
                        // value — skip so the queue can't fill with unsendable events.
                        val prevFix = lastCoverageFix
                        lastCoverageFix = Pair(lat, lng)
                        val segmentMeters = prevFix?.let { (prevLat, prevLng) ->
                            GeofenceVerification.distanceMeters(prevLat, prevLng, lat, lng)
                        } ?: 0.0

                        // Walking-pace check: fast walking leaves sparse,
                        // motion-blurred coverage the scan can't analyze.
                        // Two consecutive fast fixes trip the banner (single
                        // GPS jitter spikes don't); one slow fix clears it.
                        val fixAtMs = Clock.System.now().toEpochMilliseconds()
                        val dtMs = lastCoverageFixAtMs?.let { fixAtMs - it } ?: 0L
                        lastCoverageFixAtMs = fixAtMs
                        val speed = if (dtMs in 300..10_000) segmentMeters / (dtMs / 1000.0) else 0.0
                        fastFixStreak = if (speed > 1.8) fastFixStreak + 1 else 0
                        val tooFast = fastFixStreak >= 2
                        if (_uiState.value.movingTooFast != tooFast) {
                            _uiState.value = _uiState.value.copy(movingTooFast = tooFast)
                        }
                        if (getPlatformName() != "desktop") {
                            coverageEventQueue.enqueue(
                                ScanCoverageEventRequest(
                                    deviceId = CoverageDeviceId.value,
                                    platform = getPlatformName(),
                                    lat = lat,
                                    lng = lng,
                                    distanceMeters = segmentMeters,
                                    scanMode = "route",
                                    deviceTier = coverageDeviceTier,
                                    capturedAt = Clock.System.now().toString(),
                                    quality = wireQualityForSegment(bestFanQualitySinceLastEvent),
                                )
                            )
                            bestFanQualitySinceLastEvent = null
                        }

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

                        // Grazed floor at the walked position; the raycast fan
                        // (onCoverageRayHits) supplies measured grazed/close
                        // credit — the blind 15m frustum is gone (spec §1).
                        val heading = arScanner.getArHeading() ?: locationProvider.headingDegrees.value
                        coverageTracker.updatePosition(lat, lng)

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

                        // First-lap completion: the required area is filled —
                        // archive the baseline session and advance to review
                        // (the gateway into the cleanup lap).
                        if (!lapTransitionFired &&
                            _uiState.value.coveragePercent >= LAP_COMPLETE_PERCENT
                        ) {
                            lapTransitionFired = true
                            _uiState.value = _uiState.value.copy(
                                sectionPrompt = "Lap 1 complete — block documented. Review what you found.",
                            )
                            completeScout()
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
                        lapSessionRecorder.addSample(
                            lat = lat,
                            lng = lng,
                            headingDegrees = locationProvider.headingDegrees.value,
                            accuracyMeters = locationProvider.horizontalAccuracyMeters.value,
                        )

                        // Lap-2 guidance: when the current problem area is
                        // reached, advance to the next-largest unvisited one.
                        val target = _uiState.value.guidanceTarget
                        if (target != null &&
                            GeofenceVerification.distanceMeters(
                                lat, lng, target.center.lat, target.center.lng,
                            ) <= PROBLEM_AREA_REACHED_METERS
                        ) {
                            visitedProblemAreas.add(target)
                            val next = _uiState.value.problemAreas
                                .firstOrNull { it !in visitedProblemAreas }
                            _uiState.value = _uiState.value.copy(
                                guidanceTarget = next,
                                sectionPrompt = if (next != null) {
                                    "Area reached. Next problem area: ${next.landmarkCount} landmark(s)."
                                } else {
                                    "All problem areas visited — finish the remaining cleanups."
                                },
                            )
                        }

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
        accuracyJob?.cancel()
        aiDirectionJob?.cancel()
        aiDirectionEngine.clear()
        locationProvider.stopLocationUpdates()
        arScanner.stopSession()
        // Final queue flush must outlive this ViewModel, so it runs detached —
        // that lets the main [scope] be cancelled immediately, which also
        // kills the untracked collectors launched in initForRoute/
        // startLocationTracking (scannedCells, outdoorGate.state) that job
        // fields never covered.
        launchDetached {
            coverageEventQueue.flush()
            arMarkerQueue.flush()
        }
        scope.cancel()
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
        hasScannableGeometry()

private fun ScanHotspot.sectionIndex(): Int? =
    arAnchorData
        ?.substringAfter("section:", missingDelimiterValue = "")
        ?.takeWhile { it.isDigit() }
        ?.toIntOrNull()

/** Distance at which an expected-dirty observation surfaces the confirm sheet. */
private const val RECTIFY_PROXIMITY_METERS = 5.0
