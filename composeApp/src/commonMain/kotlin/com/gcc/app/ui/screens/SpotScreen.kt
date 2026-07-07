package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.platform.ArEnvScanOverlay
import com.gcc.app.platform.TrashCandidate
import com.gcc.app.platform.isGeoRefFixUsable
import com.gcc.app.platform.MAX_GEO_REF_ACCURACY_METERS
import com.gcc.app.platform.EnvScanGuidance
import com.gcc.app.platform.GuidancePhase
import com.gcc.app.platform.arEnvScanSupported
import com.gcc.app.platform.objectCaptureSupported
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.DeviceCapabilities
import com.gcc.app.platform.DeviceClass
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.CameraDetectionBus
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CameraSceneVoteBus
import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.CoverageEventQueue
import com.gcc.app.platform.CoverageQuality
import com.gcc.app.platform.CoverageRetractionQueue
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.purgeAndRetractCoverage
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.EnvScanSessionDraft
import com.gcc.app.platform.EnvScanSessionRecorder
import com.gcc.app.platform.coverageTier
import com.gcc.app.platform.getPlatformName
import com.gcc.app.platform.OutdoorGate
import com.gcc.app.platform.OutdoorState
import com.gcc.app.platform.retractionOutcome
import com.gcc.app.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.map.metersToPixels
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AreaMode
import com.gcc.app.viewmodel.DuplicateMatch
import com.gcc.app.viewmodel.SpotPhase
import com.gcc.app.viewmodel.SpotViewModel
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.WorldObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import com.gcc.shared.cache.ScanAreaCacheStore
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.shared.model.Bounty
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.SpotTag
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SpotScreen(
    onBack: () -> Unit = {},
    onVolunteerCleanup: (reportId: Int) -> Unit = {},
    onNavigateToActiveJob: (jobId: Int, routeId: Int) -> Unit = { _, _ -> },
) {
    val viewModel = koinInject<SpotViewModel>()
    val locationProvider = koinInject<LocationProvider>()
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by locationProvider.currentLocation.collectAsState()

    // The VM is a Koin singleton: CAMERA (the default SpotUiState phase) is
    // the only "fresh entry" state. Anything else on first composition means
    // the previous session never finished cleanly — back button from
    // LOCATION/DESCRIPTION/RESULT instead of Complete Scan, or a killed
    // process mid-flow — so force a full reset before this session starts
    // rather than letting stale photos/items/draft bleed into it (field
    // report: submitting a second scan after the first crashed).
    LaunchedEffect(Unit) {
        if (viewModel.uiState.value.phase != SpotPhase.CAMERA) {
            viewModel.reset()
        }
    }

    // Start GPS on entry, stop on leave
    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose { locationProvider.stopLocationUpdates() }
    }

    // Update viewModel location when GPS emits
    LaunchedEffect(currentLocation) {
        currentLocation?.let { loc ->
            viewModel.setLocation(loc.lat, loc.lng)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            // The camera phase is a full-bleed viewfinder with floating chrome
            // only — the header (back / title / logo) belongs to the other phases.
            if (uiState.phase != SpotPhase.CAMERA) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SurfaceMuted),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "\u2190",
                            style = MaterialTheme.typography.titleMedium,
                            color = GccGreenDark,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (uiState.phase) {
                        SpotPhase.CAMERA -> "Environment Scan"
                        SpotPhase.LOCATION -> "Confirm Location"
                        SpotPhase.DESCRIPTION -> "Add Details"
                        SpotPhase.SUBMITTING -> "Submitting..."
                        SpotPhase.RESULT -> "Scan Session Complete"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                GccLogo(size = 72.dp, modifier = Modifier.padding(end = 4.dp))
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LaunchedEffect(uiState.scanFinishedWithoutReport) {
                if (uiState.scanFinishedWithoutReport) {
                    // Clear before leaving: the VM is a singleton, so a stale
                    // true here instantly closed every later scan open.
                    viewModel.consumeScanFinishedSignal()
                    onBack()
                }
            }
            when (uiState.phase) {
                SpotPhase.CAMERA -> CameraPhase(
                    photos = uiState.capturedPhotos,
                    onCapture = { photo -> viewModel.addPhoto(photo) },
                    onRemovePhoto = { index -> viewModel.removePhoto(index) },
                    onNext = { viewModel.nextPhase() },
                    onCompleteScan = { draft -> viewModel.completeScan(draft) },
                    onObjectCaptured = { outcome -> viewModel.attachObjectCapture(outcome) },
                    onBack = onBack,
                    spotLocation = currentLocation,
                    onScanCapture = { scanPhoto -> viewModel.addScanPhoto(scanPhoto) },
                    observedItems = uiState.observedItems,
                    onTrashObserved = { item -> viewModel.addObservedItem(item) },
                    submitError = uiState.error,
                    onPurgeRecentCaptures = { cutoff -> viewModel.purgeRecentCaptures(cutoff) },
                    onFlushMarkers = { viewModel.flushArMarkers() },
                )

                SpotPhase.LOCATION -> LocationPhase(
                    gpsLat = uiState.currentLat,
                    gpsLng = uiState.currentLng,
                    spotLat = uiState.spotLat ?: uiState.currentLat,
                    spotLng = uiState.spotLng ?: uiState.currentLng,
                    radiusMeters = uiState.affectedRadiusMeters,
                    areaMode = uiState.areaMode,
                    rectWidth = uiState.rectWidth,
                    rectHeight = uiState.rectHeight,
                    onAreaModeChange = { viewModel.setAreaMode(it) },
                    onRadiusChange = { viewModel.setRadius(it.toDouble()) },
                    onRectDimensionsChange = { w, h -> viewModel.setRectDimensions(w, h) },
                    onSpotMoved = { lat, lng -> viewModel.setSpotLocation(lat, lng) },
                    onNext = { viewModel.nextPhase() },
                )

                SpotPhase.DESCRIPTION -> DescriptionPhase(
                    description = uiState.description,
                    onDescriptionChange = { viewModel.setDescription(it) },
                    photoCount = uiState.capturedPhotos.size,
                    selectedTags = uiState.selectedTags,
                    onToggleTag = { viewModel.toggleTag(it) },
                    aiAnalysis = uiState.aiAnalysis,
                    isAnalyzing = uiState.isAnalyzing,
                    duplicateMatch = uiState.duplicateMatch,
                    onDuplicateOverride = { reason ->
                        viewModel.setDuplicateOverrideReason(reason)
                        viewModel.dismissDuplicate()
                    },
                    onDuplicateDismiss = { viewModel.dismissDuplicate() },
                    error = uiState.error,
                    onSubmit = { viewModel.nextPhase() },
                )

                SpotPhase.SUBMITTING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GccGreen)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Analyzing and submitting...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextMuted,
                            )
                        }
                    }
                }

                SpotPhase.RESULT -> ResultPhase(
                    report = uiState.submittedReport,
                    session = uiState.completedSession,
                    itemCount = uiState.observedItems.size,
                    photoCount = uiState.capturedPhotos.size,
                    matchingBounties = uiState.matchingBounties,
                    createdBounty = uiState.createdBounty,
                    isCreatingBounty = uiState.isCreatingBounty,
                    bountyCreateError = uiState.bountyCreateError,
                    error = uiState.error,
                    onAcceptBounty = { viewModel.acceptBounty(it) },
                    onPlaceBounty = { dollars -> viewModel.createBountyForSubmittedSpot(dollars) },
                    onVolunteerCleanup = {
                        uiState.submittedReport?.let { report ->
                            onVolunteerCleanup(report.id)
                        }
                    },
                    onNewReport = { viewModel.reset() },
                    onBack = onBack,
                )
            }

            DiagnosticsOverlay()
        }
    }
}

@Composable
private fun CameraPhase(
    photos: List<CapturedPhoto>,
    onCapture: (CapturedPhoto) -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onNext: () -> Unit,
    onCompleteScan: (EnvScanSessionDraft) -> Unit,
    onBack: () -> Unit,
    onObjectCaptured: (com.gcc.app.platform.ObjectCaptureOutcome) -> Unit = {},
    spotLocation: Coordinates? = null,
    onScanCapture: (ScanCapturedPhoto) -> Unit = {},
    observedItems: List<ObservedSpotItem> = emptyList(),
    onTrashObserved: (ObservedSpotItem) -> Unit = {},
    submitError: String? = null,
    onPurgeRecentCaptures: (Long) -> Unit = {},
    /** Drains the confirmed-litter marker queue (30s cadence + dispose). */
    onFlushMarkers: () -> Unit = {},
) {
    val locationProvider = koinInject<LocationProvider>()
    val deviceCapabilities = koinInject<DeviceCapabilities>()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    val gpsHeading by locationProvider.headingDegrees.collectAsState()
    val gpsAccuracy by locationProvider.horizontalAccuracyMeters.collectAsState()
    val isScanMode = deviceCapabilities.deviceClass != DeviceClass.BASIC
    val scanLocation = spotLocation
        ?: currentLocation
        ?: Coordinates(lat = MapStyles.NORTH_LAWNDALE_CENTER_LAT, lng = MapStyles.NORTH_LAWNDALE_CENTER_LNG)

    // ARKit environment-scan surface (ground grid + vector-scan overlay +
    // trash lock-on). Null on unsupported hardware → AVCapture viewfinder.
    val arOverlay = remember { if (isScanMode && arEnvScanSupported()) ArEnvScanOverlay() else null }
    val groundDetected by (arOverlay?.groundDetected ?: remember { MutableStateFlow(false) }).collectAsState()
    val trashCandidate by (arOverlay?.trashCandidate ?: remember { MutableStateFlow<TrashCandidate?>(null) }).collectAsState()
    // Gate pauses AR frame analysis indoors (same behavior the route AR has),
    // and geo-references pair every GPS fix with the AR camera position —
    // except fixes worse than MAX_GEO_REF_ACCURACY_METERS, which must not
    // re-anchor the frame (one wild fix drags all geo-derived AR geometry).
    var geoRefPaired by remember { mutableStateOf(false) }
    LaunchedEffect(arOverlay) {
        if (arOverlay == null) return@LaunchedEffect
        locationProvider.locationFlow.collect { loc ->
            if (isGeoRefFixUsable(locationProvider.horizontalAccuracyMeters.value)) {
                arOverlay.updateGeoReference(loc.lat, loc.lng)
                geoRefPaired = true
            }
        }
    }

    // "Go outside" gate — fused from GPS accuracy, mapped-building containment,
    // AR/AVCapture camera scene votes, and ARKit ceiling-plane evidence; capture,
    // coverage credit, and tagging are all gated on gate.captureAllowed.
    val gate = remember { OutdoorGate() }
    val outdoorState by gate.state.collectAsState()
    val isIndoor = outdoorState == OutdoorState.INDOOR
    val captureAllowed by gate.captureAllowed.collectAsState()
    // One session = one capture: this recorder's snapshot rides with submit.
    // Declared here (ahead of its other uses below) so the outdoor-state gate
    // effect can reach it for purge/audit-trail events.
    val sessionRecorder = remember { EnvScanSessionRecorder() }
    // Coverage tracker: hoisted above the AR-overlay DisposableEffect below
    // (rather than down with its other env-scan state) because the
    // raycast-fan listener registered there needs it in scope.
    val tracker = remember { CoverageTracker() }
    // Set by the raycast-fan listener when any CLOSE-tier hit credits; read +
    // reset per coverage event so the event carries the best tier since the
    // previous fix. Plain holder (not Compose state): mutated off-composition.
    val closeCreditSinceLastEvent = remember { object { var value = false } }

    DisposableEffect(arOverlay) {
        // Landmark elements (trees, fences, gates…) auto-register into the
        // same observed-items packet as trash — domain-tagged, so the server
        // records them as vegetation/construction world observations and the
        // grid expects them next scan.
        arOverlay?.setElementObservedListener { lat, lng, itemType, domain, confidence, widthMeters, yawDegrees ->
            if (!gate.captureAllowed.value) return@setElementObservedListener
            onTrashObserved(
                ObservedSpotItem(
                    lat = lat,
                    lng = lng,
                    itemType = itemType,
                    confidence = confidence,
                    estimatedWidthMeters = widthMeters,
                    domain = domain,
                    yawDegrees = yawDegrees,
                )
            )
        }
        arOverlay?.setCeilingListener { detected ->
            gate.onCeilingPlane(detected, Clock.System.now().toEpochMilliseconds())
        }
        arOverlay?.setCoverageHitListener { hits ->
            if (!gate.captureAllowed.value) return@setCoverageHitListener
            tracker.updateWithRaycastHits(hits)
            if (hits.any { it.distanceMeters <= CoverageTracker.CLOSE_RANGE_METERS }) {
                closeCreditSinceLastEvent.value = true
            }
        }
        onDispose {
            arOverlay?.setElementObservedListener(null)
            arOverlay?.setCeilingListener(null)
            arOverlay?.setCoverageHitListener(null)
            arOverlay?.stop()
        }
    }

    LaunchedEffect(Unit) {
        locationProvider.horizontalAccuracyMeters.collect { accuracy ->
            gate.onAccuracySample(accuracy, Clock.System.now().toEpochMilliseconds())
        }
    }
    // Map evidence: GPS fix inside a mapped building footprint — the same
    // rectangles the minimap draws, so "my dot is inside a building" always
    // agrees with the gate. PRE-EMPTIVE: the last known position is checked
    // the instant the scan opens (one containment hit flips the gate), so a
    // user opening the scan at home sees "go outside" immediately instead of
    // after evidence accumulates.
    LaunchedEffect(Unit) {
        locationProvider.currentLocation.value?.let { loc ->
            gate.onMapEvidence(
                insideBuilding = com.gcc.app.ui.map.isInsideMappedBuilding(loc.lat, loc.lng),
                timestampMs = Clock.System.now().toEpochMilliseconds(),
            )
        }
        locationProvider.locationFlow.collect { loc ->
            gate.onMapEvidence(
                insideBuilding = com.gcc.app.ui.map.isInsideMappedBuilding(loc.lat, loc.lng),
                timestampMs = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }
    // The viewfinder itself votes indoor/outdoor (Vision pass every ~2s on
    // the AVCapture stream) — same camera evidence AR mode gets.
    LaunchedEffect(Unit) {
        CameraSceneVoteBus.votes.collect { vote ->
            gate.onSceneVote(vote, Clock.System.now().toEpochMilliseconds())
        }
    }
    // apiClient/coverageQueue/flushScope/retractQueue are hoisted here (ahead of
    // their other env-scan uses below) so the outdoor-state gate effect's INDOOR
    // branch can reach them for the purge + server-retraction flow.
    val apiClient = koinInject<GccApiClient>()
    // Ledger emission: environment scans register real coverage (never-scanned
    // completions + 24h recency updates) exactly like the AR modes do.
    val coverageQueue = remember(apiClient) {
        CoverageEventQueue(submitFn = { events ->
            try {
                apiClient.submitCoverageEvents(events)
            } catch (e: Exception) {
                DiagnosticsLog.error("coverage-flush", "env-scan flush of ${events.size} events failed (kept queued)", e)
                throw e
            }
        })
    }
    val flushScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // Retractions: already-flushed indoor-window events the server must undo.
    val retractQueue = remember(apiClient) {
        CoverageRetractionQueue(submitFn = { request ->
            retractionOutcome(request, apiClient::retractCoverageEvents)
        })
    }

    LaunchedEffect(outdoorState) {
        DiagnosticsLog.info("outdoor-gate", "camera gate → $outdoorState")
        arOverlay?.setFrameAnalysisEnabled(outdoorState != OutdoorState.INDOOR)
        arOverlay?.setSceneVoteCadence(fast = outdoorState != OutdoorState.OUTDOOR)
        CameraManager.sceneVoteFastMode = outdoorState != OutdoorState.OUTDOOR
        if (outdoorState == OutdoorState.INDOOR) {
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val cutoff = (gate.lastIndoorFlipAtMs ?: nowMs) - OutdoorGate.PURGE_WINDOW_MS
            sessionRecorder.purgeSince(cutoff)      // SpotScreen only (owns the recorder)
            onPurgeRecentCaptures(cutoff)           // new param → AppNavigation → viewModel::purgeRecentCaptures
            // Coverage companion purges (spec §2): local cells, pending
            // events, and a server retraction for anything already flushed.
            tracker.purgeSince(cutoff)
            flushScope.launch {
                purgeAndRetractCoverage(cutoff, nowMs, coverageQueue, retractQueue)
            }
        }
        sessionRecorder.noteGateEvent("gate@${Clock.System.now().toEpochMilliseconds()} state=$outdoorState")
    }

    // ── Environment-scan session: coverage credit + minimap layers ──
    val scanAreaCache = koinInject<ScanAreaCacheStore>()
    var trackerReady by remember { mutableStateOf(false) }
    var fogCells by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
    var staleCells by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
    var freshCells by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
    /** Ledger-fresh cells whose best-recorded quality is "close" — the
     *  minimap's brighter fresh wash (spec §3/§4 two-tier read). */
    var freshCloseCells by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }

    DisposableEffect(Unit) {
        onDispose {
            // Final flush on leaving the screen, then tear down flushScope once
            // that flush completes — cancelling from WITHIN the launch's own
            // completion handler (not right after launching it) preserves the
            // exit-flush: cancelling the scope immediately here would race the
            // just-launched coroutine and could kill it before flush() finishes.
            flushScope.launch { coverageQueue.flush(); retractQueue.flush() }
                .invokeOnCompletion { flushScope.cancel() }
            onFlushMarkers()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            coverageQueue.flush()
            retractQueue.flush()
            // Confirmed litter reaches the public map mid-scan on the same
            // cadence route AR markers use (was submit-only before).
            onFlushMarkers()
        }
    }

    // "Previously littered, now clean" flow: nearby expected-dirty
    // observations from the world model; within range a confirm sheet offers
    // Still there / Already clean (rectification bonus).
    var expectedDirty by remember { mutableStateOf(listOf<WorldObservation>()) }
    // Known landmark elements from the world model (trees, fences, gates…):
    // depicted on the minimap as context, never as trash.
    var knownElements by remember { mutableStateOf(listOf<WorldObservation>()) }
    fun isLitterObservation(obs: WorldObservation) = obs.domain == null || obs.domain == "litter"
    fun isElementObservation(obs: WorldObservation) = obs.domain == "vegetation" || obs.domain == "construction"
    val resolvedObservationIds = remember { mutableSetOf<Int>() }
    var nearbyDirty by remember { mutableStateOf<WorldObservation?>(null) }

    // Initialise the scan grid at the first real fix (single-point "route"
    // gives a ~15m-corridor zone around the spot). Keyed on Unit on purpose:
    // spotLocation is the LIVE GPS value at the call site, so keying on it
    // restarted this effect on every fix, cancelling the in-flight coverage/
    // observation fetches below (field diagnostics: "observations fetch
    // failed … CancellationException") — and because trackerReady was already
    // set, the restarted effect bailed out and the data never loaded at all.
    // Await the first fix from the flow instead of restarting.
    LaunchedEffect(Unit) {
        if (trackerReady) return@LaunchedEffect
        val anchor = spotLocation
            ?: locationProvider.currentLocation.filterNotNull().first()
        tracker.initFromRoute(listOf(anchor))
        trackerReady = true
        // Phase the AR ground grid to the tracker's cell lattice so the
        // on-camera grid lines register with the minimap's grid (the overlay
        // re-derives the AR-local phase on every geo-reference re-pair).
        tracker.latticeOrigin()?.let { (lat0, lng0) ->
            arOverlay?.setGridLatticeOrigin(lat0, lng0)
        }

        // Ledger freshness around the scan site (display-only; failures fall
        // back to the last cached snapshot of this area).
        val pad = 0.0012 // ~130m
        val bbox = listOf(
            anchor.lng - pad, anchor.lat - pad,
            anchor.lng + pad, anchor.lat + pad,
        ).joinToString(",")
        var fetchedCells: List<MapCoverageCell>? = null
        try {
            val serverCells = apiClient.getMapCoverage(bbox).cells
            fetchedCells = serverCells
            staleCells = serverCells.filter { it.state == "stale" }.map { it.lat to it.lng }
            freshCells = serverCells.filter { it.state == "fresh" && it.quality != "close" }.map { it.lat to it.lng }
            freshCloseCells = serverCells.filter { it.state == "fresh" && it.quality == "close" }.map { it.lat to it.lng }
            fogCells = neverScannedGrid(anchor, serverCells)
            DiagnosticsLog.info(
                "env-scan",
                "freshness: ${serverCells.size} ledger cells, ${staleCells.size} stale, ${fogCells.size} fog",
            )
        } catch (e: CancellationException) {
            throw e // scan screen closing — not a fetch failure
        } catch (e: Exception) {
            DiagnosticsLog.error("env-scan", "coverage freshness fetch failed — trying cache", e)
            val cached = withContext(Dispatchers.Default) {
                scanAreaCache.get(ScanAreaCacheStore.areaKeyFor(anchor.lat, anchor.lng))
            }
            if (cached != null) {
                staleCells = cached.coverageCells.filter { it.state == "stale" }.map { it.lat to it.lng }
                freshCells = cached.coverageCells.filter { it.state == "fresh" && it.quality != "close" }.map { it.lat to it.lng }
                freshCloseCells = cached.coverageCells.filter { it.state == "fresh" && it.quality == "close" }.map { it.lat to it.lng }
                fogCells = neverScannedGrid(anchor, cached.coverageCells)
                DiagnosticsLog.info("env-scan", "freshness from cache (${cached.coverageCells.size} cells)")
            }
        }
        try {
            val allObs = apiClient.getMapObservations(bbox)
            expectedDirty = allObs.filter { it.state == "dirty" && isLitterObservation(it) }
            knownElements = allObs.filter { isElementObservation(it) }
            DiagnosticsLog.info(
                "env-scan",
                "${expectedDirty.size} expected-dirty + ${knownElements.size} landmark observations nearby",
            )
        } catch (e: CancellationException) {
            throw e // scan screen closing — not a fetch failure
        } catch (e: Exception) {
            DiagnosticsLog.error("env-scan", "observations fetch failed — trying cache", e)
            val cached = withContext(Dispatchers.Default) {
                scanAreaCache.get(ScanAreaCacheStore.areaKeyFor(anchor.lat, anchor.lng))
            }
            if (cached != null) {
                expectedDirty = cached.observations.filter { it.state == "dirty" && isLitterObservation(it) }
                knownElements = cached.observations.filter { isElementObservation(it) }
            }
        }
        // Snapshot whatever we fetched live so the next offline open has it.
        if (fetchedCells != null) {
            withContext(Dispatchers.Default) {
                scanAreaCache.put(
                    ScanAreaCacheStore.areaKeyFor(anchor.lat, anchor.lng),
                    fetchedCells,
                    expectedDirty,
                    Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
    }

    // Credit zones while capture is allowed: while INDOOR, or still UNCERTAIN
    // pending outdoor confirmation, accrue nothing — no tracker cells, no
    // ledger event. Reset the segment anchor so no phantom distance bridges
    // the gap once capture resumes. Camera frustum sweep when heading exists.
    LaunchedEffect(trackerReady) {
        if (!trackerReady) return@LaunchedEffect
        var lastLat: Double? = null
        var lastLng: Double? = null
        locationProvider.locationFlow.collect { loc ->
            if (!gate.captureAllowed.value) {
                lastLat = null; lastLng = null
                return@collect
            }
            // Grazed floor: the ground you walk over is observed at sweep
            // quality; everything richer comes from the raycast fan (spec §1).
            val heading = locationProvider.headingDegrees.value
            tracker.updatePosition(loc.lat, loc.lng)
            sessionRecorder.addSample(
                lat = loc.lat,
                lng = loc.lng,
                headingDegrees = heading,
                accuracyMeters = locationProvider.horizontalAccuracyMeters.value,
            )

            // Ledger event (spot mode): distance since the previous fix.
            val segmentMeters = lastLat?.let { prevLat ->
                GeofenceVerification.distanceMeters(prevLat, lastLng!!, loc.lat, loc.lng)
            } ?: 0.0
            lastLat = loc.lat
            lastLng = loc.lng
            if (getPlatformName() != "desktop") {
                coverageQueue.enqueue(
                    ScanCoverageEventRequest(
                        deviceId = CoverageDeviceId.value,
                        platform = getPlatformName(),
                        lat = loc.lat,
                        lng = loc.lng,
                        distanceMeters = segmentMeters,
                        scanMode = "spot",
                        deviceTier = deviceCapabilities.deviceClass.coverageTier(),
                        capturedAt = Clock.System.now().toString(),
                        quality = if (closeCreditSinceLastEvent.value) "close" else "grazed",
                    )
                )
                closeCreditSinceLastEvent.value = false
            }

            // Clean-confirm proximity (5m, unresolved only).
            nearbyDirty = expectedDirty
                .asSequence()
                .filter { it.id !in resolvedObservationIds }
                .map { it to GeofenceVerification.distanceMeters(loc.lat, loc.lng, it.lat, it.lng) }
                .filter { (_, dist) -> dist <= 5.0 }
                .minByOrNull { (_, dist) -> dist }
                ?.first
        }
    }
    val scannedCells by tracker.scannedCells.collectAsState()
    val scanCellCenters = remember(scannedCells) {
        scannedCells.map { (row, col) -> tracker.cellCenterLatLng(row, col) }
    }
    val creditedCells by tracker.creditedCells.collectAsState()
    val closeCellCenters = remember(creditedCells) {
        creditedCells.filterValues { it.quality == CoverageQuality.CLOSE }
            .keys.map { (row, col) -> tracker.cellCenterLatLng(row, col) }
    }
    // Vector-scan effect: CLOSE-tier cells render as "digitized" ground quads
    // in the AR camera view, mirroring the minimap's vivid fill — fog still
    // lifts on any credit (below), so the AR read becomes: fog = unscanned,
    // clear ground = grazed, vivid quad = close (spec §4).
    LaunchedEffect(closeCellCenters) {
        arOverlay?.updateScannedCells(closeCellCenters)
    }
    // AR fog + steering: uncredited cells render as ground fog on the camera
    // that dissipates as they credit, and the frontier pick aims the minimap
    // ring + AR arrow at the nearest remaining patch (sticky vs GPS jitter).
    var guidanceTarget by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var guidancePhase by remember { mutableStateOf(GuidancePhase.COVER) }
    val coveragePercentValue by tracker.coveragePercent.collectAsState()
    LaunchedEffect(scanCellCenters, closeCellCenters, trackerReady, currentLocation) {
        if (!trackerReady) return@LaunchedEffect
        val uncredited = tracker.getUnscannedCells()
            .map { (row, col) -> tracker.cellCenterLatLng(row, col) }
        arOverlay?.updateFog(uncredited)
        val grazedNotClose = creditedCells
            .filterValues { it.quality == CoverageQuality.GRAZED }
            .keys.map { (row, col) -> tracker.cellCenterLatLng(row, col) }
        val loc = currentLocation
        val pick = loc?.let {
            EnvScanGuidance.pickTarget(
                current = guidanceTarget,
                uncredited = uncredited,
                grazedNotClose = grazedNotClose,
                userLat = it.lat, userLng = it.lng,
                coveragePercent = coveragePercentValue,
            )
        }
        guidanceTarget = pick?.target
        guidancePhase = pick?.phase ?: GuidancePhase.COVER
        if (arOverlay != null) {
            val target = pick?.target
            if (target != null) arOverlay.setGuidanceTarget(target.first, target.second)
            else arOverlay.clearGuidanceTarget()
        }
    }
    // Idle nudge: no new cell credited for a while → steer via the status chip.
    // Restarting on scannedCells.size IS the idle timer reset.
    var idleNudge by remember { mutableStateOf(false) }
    LaunchedEffect(trackerReady, scannedCells.size, captureAllowed) {
        idleNudge = false
        if (!trackerReady || !captureAllowed) return@LaunchedEffect
        delay(8_000)
        idleNudge = true
    }
    // Litter vs landmark elements: elements ride the same submission packet
    // but must never read as trash in the UI (dots, toasts, points).
    val litterItems = remember(observedItems) {
        observedItems.filter { it.domain == null || it.domain == "litter" }
    }
    val trashItems = remember(litterItems) {
        litterItems.map { Coordinates(lat = it.lat, lng = it.lng) }
    }
    // Minimap glyphs: this session's confirmed items PLUS the world model's
    // known litter nearby — previously scanned trash persists on the minimap
    // as what it IS (bottle/can/bag glyph), not an anonymous dot. Items the
    // user resolves ("Already clean") drop out with expectedDirty.
    val typedMinimapItems = remember(observedItems, expectedDirty, knownElements) {
        observedItems + (expectedDirty + knownElements).map { obs ->
            ObservedSpotItem(
                lat = obs.lat,
                lng = obs.lng,
                itemType = obs.itemType ?: "other",
                severity = obs.severity,
                domain = obs.domain,
            )
        }
    }

    // Live viewfinder trash reticle (frame tap on the AVCapture stream).
    val liveDetections by CameraDetectionBus.detections.collectAsState()

    // Registration feedback: toast on tags, pulse on any credit.
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(litterItems.size) {
        if (litterItems.isNotEmpty()) {
            toast = "Trash tagged on the map ✓"
        }
    }
    LaunchedEffect(submitError) {
        if (submitError != null) toast = "⚠ $submitError"
    }
    val rectifyScope = rememberCoroutineScope()
    val creditTick = scannedCells.size + litterItems.size * 10

    // Guided 3D capture (Object Capture) — optional sub-flow for large
    // litter. While open, the scan camera surface leaves the composition
    // (UIKitView onRelease pauses the AR session) so ObjectCaptureSession
    // can own the camera; closing it recomposes the scan surface.
    var showObjectCapture by remember { mutableStateOf(false) }

    // Full-bleed viewfinder; everything else floats over it, inset-aware.
    Box(modifier = Modifier.fillMaxSize()) {
        val arTracking by (arOverlay?.trackingState
            ?: remember { MutableStateFlow(com.gcc.app.platform.TrackingState.NORMAL) }).collectAsState()
        val scanStatus = when {
            arTracking == com.gcc.app.platform.TrackingState.LIMITED ->
                "Hold steady — AR tracking is settling"
            trashCandidate != null -> "Item locked — confirm below"
            liveDetections.isNotEmpty() -> "Trash spotted — walk up close to identify it"
            // Geo-ref can't pair on a >20m fix — AR fog/cells/arrow are all
            // suppressed until it does, so say WHY instead of failing silently.
            arOverlay != null && !geoRefPaired &&
                (gpsAccuracy ?: 0.0) > MAX_GEO_REF_ACCURACY_METERS ->
                "GPS too weak to anchor the scan — move toward open sky"
            arOverlay != null && !groundDetected -> "Point the camera at the ground to map it"
            idleNudge && guidanceTarget != null ->
                "Turn toward the highlighted area on the minimap"
            else -> "Environment scan active — sweep the camera slowly"
        }
        // DETAIL-phase caption is a sibling chip, not a scanStatus branch —
        // it must not fight the idle nudge for the same status line.
        val showDetailCaption = guidancePhase == GuidancePhase.DETAIL &&
            !(idleNudge && guidanceTarget != null)
        if (!showObjectCapture) if (isScanMode) {
            SpotScanCapture(
                spotLocation = scanLocation,
                geofenceRadius = 50.0,
                maxPhotos = 3,
                // World-scan model: no target-spot geofence — the scan IS the
                // location source ("0m from spot" badge was legacy noise).
                requireGeofence = false,
                capturedPhotos = photos,
                onPhotoCaptured = onScanCapture,
                onRemovePhoto = onRemovePhoto,
                captureBlocked = !captureAllowed,
                onTrashObserved = onTrashObserved,
                scanCellCenters = scanCellCenters,
                trashItems = trashItems,
                typedTrashItems = typedMinimapItems,
                staleCellCenters = staleCells,
                freshCellCenters = freshCells,
                closeCellCenters = closeCellCenters,
                freshCloseCellCenters = freshCloseCells,
                guidanceTarget = guidanceTarget?.let { Coordinates(lat = it.first, lng = it.second) },
                environmentScanMode = true,
                statusText = scanStatus,
                detailPhase = showDetailCaption,
                arOverlay = arOverlay,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CameraCapture(
                capturedPhotos = photos,
                onCapture = { photo ->
                    onScanCapture(
                        ScanCapturedPhoto(
                            photoBytes = photo.bytes,
                            photoWidth = photo.width,
                            photoHeight = photo.height,
                            depthAnalysis = photo.depthAnalysis,
                            attestation = photo.attestation,
                        )
                    )
                },
                onRemovePhoto = onRemovePhoto,
                modifier = Modifier.fillMaxSize(),
                showMiniMap = true,
                currentLocation = currentLocation,
                gpsHeading = gpsHeading,
                scanCellCenters = scanCellCenters,
                trashItems = trashItems,
                typedTrashItems = typedMinimapItems,
                staleCellCenters = staleCells,
                freshCellCenters = freshCells,
                closeCellCenters = closeCellCenters,
                freshCloseCellCenters = freshCloseCells,
                environmentScanMode = true,
                statusText = scanStatus,
            )
        }

        // "Previously littered — is it clean now?" confirm sheet.
        nearbyDirty?.let { obs ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 104.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Trash was reported here — is it still there?",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable {
                                resolvedObservationIds.add(obs.id)
                                nearbyDirty = null
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Still there", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(GccGreen)
                            .clickable {
                                resolvedObservationIds.add(obs.id)
                                expectedDirty = expectedDirty.filterNot { it.id == obs.id }
                                nearbyDirty = null
                                rectifyScope.launch {
                                    try {
                                        val result = apiClient.rectifyObservationAtLocation(obs.lat, obs.lng)
                                        toast = if (result.pointsAwarded > 0) {
                                            "Area marked clean · +${result.pointsAwarded} pts ✓"
                                        } else {
                                            "Area marked clean ✓"
                                        }
                                    } catch (e: Exception) {
                                        DiagnosticsLog.error("rectify", "env-scan rectify failed", e)
                                        toast = "⚠ Couldn't record the clean update"
                                        resolvedObservationIds.remove(obs.id)
                                        expectedDirty = expectedDirty + obs
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "Already clean",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Scanning feedback: credited zones painted onto the ground ahead
        // (spatial replacement for the old zone counter), sweep + pulse,
        // live trash reticle, transient popups. The pinhole-projected zone
        // overlay draws only on the non-AR fallback — the AR surface renders
        // real world-anchored vector cells for the same information.
        if (arOverlay == null) {
            ZoneOutlineOverlay(
                userLat = currentLocation?.lat,
                userLng = currentLocation?.lng,
                headingDegrees = gpsHeading,
                cellCenters = scanCellCenters,
            )
        }
        EnvironmentScanEffects(active = captureAllowed, creditTick = creditTick)
        DetectionReticleOverlay(detections = liveDetections)
        ScanToast(message = toast)

        if (isScanMode && objectCaptureSupported() && captureAllowed && !showObjectCapture) {
            // One-time caption so the pill explains itself (field feedback:
            // "I don't know what the 3D scan button is supposed to do").
            var captionVisible by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(6_000)
                captionVisible = false
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 96.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable {
                            captionVisible = false
                            showObjectCapture = true
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        "◧ 3D scan",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
                if (captionVisible) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "Measure a large pile in 3D",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }
        // Close-range confirm card: a locked candidate with type + LiDAR
        // size/weight estimates awaits Confirm / Not trash (above the
        // Complete pill, below the rectify sheet's band).
        trashCandidate?.let { candidate ->
            TrashConfirmCard(
                candidate = candidate,
                onConfirm = { selectedType ->
                    if (!gate.captureAllowed.value) return@TrashConfirmCard
                    val final = arOverlay?.confirmCandidate(selectedType)
                    if (final != null) {
                        onTrashObserved(
                            ObservedSpotItem(
                                lat = final.lat,
                                lng = final.lng,
                                itemType = final.itemType,
                                severity = null,
                                confidence = final.confidence,
                                estimatedWeightGrams = final.estimatedWeightGrams,
                                estimatedWidthMeters = final.estimatedWidthMeters,
                                yawDegrees = final.yawDegrees,
                            )
                        )
                    }
                },
                onDismiss = { arOverlay?.dismissCandidate() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 104.dp, start = 16.dp, end = 16.dp),
            )
        }

        // Indoors in a residence: nothing scans, nothing submits (capture,
        // coverage, tagging all gate-suppressed) — offer Close Scan instead.
        GoOutsideOverlay(
            visible = isIndoor,
            onCloseScan = onBack,
            onImOutside = {
                gate.forceOutdoors()
                sessionRecorder.noteGateEvent("override@${Clock.System.now().toEpochMilliseconds()}")
            },
        )
        ConfirmingOutdoorsChip(
            visible = outdoorState == OutdoorState.UNCERTAIN,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
        )

        // No top-left ✕ here by design: once an environment scan session is
        // running, the bottom Complete Scan pill is the only way out — every
        // finished scan submits, none are silently abandoned. (Indoors the
        // GoOutsideOverlay above still offers Close Scan, since nothing can
        // scan or submit from inside.)

        // Primary action: Complete Scan, bottom-center (the Pokéball spot).
        // ALWAYS tappable — it is the sole exit (no top ✕ by design), and the
        // VM handles every state: photos → submit, none → archive + close.
        // Gating the tap on captureAllowed stranded users whenever the gate
        // sat in UNCERTAIN (no GoOutside escape there) or no photo was ever
        // taken; capture itself is already gated upstream, so an always-on
        // exit leaks no indoor data. Dim = "nothing to submit yet" cue only.
        val canComplete = photos.isNotEmpty() && captureAllowed
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(28.dp))
                // Field feedback (Blake): the dimmed 0.4-alpha fill over a
                // bright outdoor background (concrete, sky) washed the label
                // out into an unlabeled green blob. Raised the floor so the
                // "Complete Scan" text stays legible while still reading
                // dimmer than the fully-active pill.
                .background(if (canComplete) GccGreen else GccGreen.copy(alpha = 0.55f))
                .clickable { onCompleteScan(sessionRecorder.draft(scanCellCenters)) }
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Complete Scan ✓",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        if (showObjectCapture) {
            ObjectCapturePhase(
                onDone = { outcome ->
                    onObjectCaptured(outcome)
                    showObjectCapture = false
                },
                onCancel = { showObjectCapture = false },
            )
        }
    }
}

@Composable
private fun ScanModeBadge(
    deviceClass: DeviceClass,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (deviceClass) {
        DeviceClass.PRO_SCANNER -> "\uD83D\uDD35 LiDAR scan" to GccBlue
        DeviceClass.SCANNER -> "\uD83D\uDFE2 AR-aware scan" to GccGreen
        DeviceClass.BASIC -> "\uD83D\uDFE1 Photo capture" to GccClaimed
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

/**
 * Converts a pixel drag delta into a latitude/longitude delta at the given zoom level.
 * Uses Mercator projection: meters per pixel ≈ 156543 * cos(lat) / 2^zoom,
 * then converts meters to degrees (111320 m/deg for lat, cos-adjusted for lng).
 */
private fun pixelDeltaToLatLng(
    dx: Float,
    dy: Float,
    lat: Double,
    zoom: Double,
): Pair<Double, Double> {
    val metersPerPixel = 156543.03392 * cos(lat * kotlin.math.PI / 180.0) / 2.0.pow(zoom)
    val dLat = -(dy * metersPerPixel) / 111320.0 // negative because screen Y is inverted
    val dLng = (dx * metersPerPixel) / (111320.0 * cos(lat * kotlin.math.PI / 180.0))
    return Pair(dLat, dLng)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationPhase(
    gpsLat: Double,
    gpsLng: Double,
    spotLat: Double,
    spotLng: Double,
    radiusMeters: Double,
    areaMode: AreaMode,
    rectWidth: Double,
    rectHeight: Double,
    onAreaModeChange: (AreaMode) -> Unit,
    onRadiusChange: (Float) -> Unit,
    onRectDimensionsChange: (Double, Double) -> Unit,
    onSpotMoved: (lat: Double, lng: Double) -> Unit,
    onNext: () -> Unit,
) {
    val mapZoom = MapStyles.ZOOM_ACTIVE_JOB
    // Draw + drag conversions work in physical pixels; the basemap camera
    // zoom is point-space (see mapOverlayZoomBias). Both directions must use
    // the SAME biased zoom or the radius ring and drag speed desync from the map.
    val overlayZoom = mapZoom + mapOverlayZoomBias(androidx.compose.ui.platform.LocalDensity.current.density)

    // Track whether user has dragged the spot away from GPS
    val hasMoved = spotLat != gpsLat || spotLng != gpsLng

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            "Set Affected Area",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Drag to position. Choose circle or rectangle shape.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Area mode toggle
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = areaMode == AreaMode.CIRCLE,
                onClick = { onAreaModeChange(AreaMode.CIRCLE) },
                label = { Text("Circle") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GccSky.copy(alpha = 0.15f),
                    selectedLabelColor = GccSky,
                ),
            )
            FilterChip(
                selected = areaMode == AreaMode.RECTANGLE,
                onClick = { onAreaModeChange(AreaMode.RECTANGLE) },
                label = { Text("Rectangle") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GccSky.copy(alpha = 0.15f),
                    selectedLabelColor = GccSky,
                ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Map with draggable overlay
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
        ) {
            GccMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = spotLat,
                centerLng = spotLng,
                zoom = mapZoom,
                currentLocation = Coordinates(lng = gpsLng, lat = gpsLat),
                enableGestures = false,
            )

            // Draggable area overlay
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(spotLat, spotLng, mapZoom) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val (dLat, dLng) = pixelDeltaToLatLng(
                                dragAmount.x, dragAmount.y, spotLat, overlayZoom,
                            )
                            val maxOffsetDeg = 100.0 / 111320.0
                            val newLat = (spotLat + dLat).coerceIn(
                                gpsLat - maxOffsetDeg, gpsLat + maxOffsetDeg,
                            )
                            val newLng = (spotLng + dLng).coerceIn(
                                gpsLng - maxOffsetDeg, gpsLng + maxOffsetDeg,
                            )
                            onSpotMoved(newLat, newLng)
                        }
                    },
            ) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)

                when (areaMode) {
                    AreaMode.CIRCLE -> {
                        val pixelRadius = metersToPixels(radiusMeters, spotLat, overlayZoom)
                        drawCircle(color = GccSky.copy(alpha = 0.2f), radius = pixelRadius, center = center)
                        drawCircle(color = GccSky, radius = pixelRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                    }
                    AreaMode.RECTANGLE -> {
                        val halfPxW = metersToPixels(rectWidth, spotLat, overlayZoom) / 2f
                        val halfPxH = metersToPixels(rectHeight, spotLat, overlayZoom) / 2f
                        val topLeft = androidx.compose.ui.geometry.Offset(center.x - halfPxW, center.y - halfPxH)
                        val rectSize = androidx.compose.ui.geometry.Size(halfPxW * 2, halfPxH * 2)
                        drawRect(color = GccSky.copy(alpha = 0.2f), topLeft = topLeft, size = rectSize)
                        drawRect(color = GccSky, topLeft = topLeft, size = rectSize, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                    }
                }

                // Center crosshair — big target ring + crosshair lines
                drawCircle(color = GccSky, radius = 20f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                drawLine(color = GccSky.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(center.x - 30f, center.y), end = androidx.compose.ui.geometry.Offset(center.x + 30f, center.y), strokeWidth = 1.5f)
                drawLine(color = GccSky.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(center.x, center.y - 30f), end = androidx.compose.ui.geometry.Offset(center.x, center.y + 30f), strokeWidth = 1.5f)
                drawCircle(color = GccSky, radius = 3f, center = center)
            }

            // Floating info card
            GccCard(
                accentColor = CardAccentGreen,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("\uD83D\uDCCD", style = MaterialTheme.typography.titleMedium)
                    Column {
                        Text(
                            if (hasMoved) "Spot Location" else "You are here",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = GccGreen,
                        )
                        Text(
                            "${formatLat(spotLat)}, ${formatLng(spotLng)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }

            // "Drag to position" hint
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val label = when (areaMode) {
                    AreaMode.CIRCLE -> "${radiusMeters.toInt()}m radius"
                    AreaMode.RECTANGLE -> "${rectWidth.toInt()}m x ${rectHeight.toInt()}m"
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = GccSky,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Size controls
        GccCard(accentColor = CardAccentSky) {
            Text(
                "Affected Area",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (areaMode) {
                AreaMode.CIRCLE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { onRadiusChange((radiusMeters - 5).coerceAtLeast(5.0).toFloat()) },
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GccSky),
                        ) { Text("\u2212", style = MaterialTheme.typography.titleMedium) }
                        Slider(
                            value = radiusMeters.toFloat(),
                            onValueChange = onRadiusChange,
                            valueRange = 5f..100f,
                            steps = 18,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = GccSky, activeTrackColor = GccSky),
                        )
                        OutlinedButton(
                            onClick = { onRadiusChange((radiusMeters + 5).coerceAtMost(100.0).toFloat()) },
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GccSky),
                        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${radiusMeters.toInt()}m", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = GccSky)
                    }
                }
                AreaMode.RECTANGLE -> {
                    // Width slider
                    Text("Width", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = rectWidth.toFloat(),
                            onValueChange = { onRectDimensionsChange(it.toDouble(), rectHeight) },
                            valueRange = 5f..100f,
                            steps = 18,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = GccSky, activeTrackColor = GccSky),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${rectWidth.toInt()}m", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = GccSky)
                    }
                    // Height slider
                    Text("Height", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = rectHeight.toFloat(),
                            onValueChange = { onRectDimensionsChange(rectWidth, it.toDouble()) },
                            valueRange = 5f..100f,
                            steps = 18,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = GccSky, activeTrackColor = GccSky),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${rectHeight.toInt()}m", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = GccSky)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        GccButton(
            text = "Next: Add Details",
            onClick = onNext,
            variant = GccButtonVariant.PRIMARY,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DescriptionPhase(
    description: String,
    onDescriptionChange: (String) -> Unit,
    photoCount: Int,
    selectedTags: List<SpotTag>,
    onToggleTag: (SpotTag) -> Unit,
    aiAnalysis: OnDeviceAnalysisResult?,
    isAnalyzing: Boolean,
    duplicateMatch: DuplicateMatch?,
    onDuplicateOverride: (String) -> Unit,
    onDuplicateDismiss: () -> Unit,
    error: String?,
    onSubmit: () -> Unit,
) {
    // Duplicate override reason state
    var overrideReason by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()
    fun dismissKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { dismissKeyboard() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .imePadding()
                .padding(16.dp),
        ) {
            // AI Analysis preview card (when available)
            if (isAnalyzing) {
                GccCard(accentColor = CardAccentPurple) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = CardAccentPurple,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "Analyzing photo...",
                            style = MaterialTheme.typography.bodySmall,
                            color = CardAccentPurple,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else if (aiAnalysis != null) {
                OnDeviceAiCard(aiAnalysis)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                "Describe What You See",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tag the types of debris and add a description.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tag multiselect chips
            Text(
                "Tags (select all that apply)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpotTag.entries.forEach { tag ->
                    FilterChip(
                        selected = tag in selectedTags,
                        onClick = { onToggleTag(tag) },
                        label = { Text(tag.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GccSky.copy(alpha = 0.15f),
                            selectedLabelColor = GccSky,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                placeholder = {
                    Text(
                        "e.g. Dumped tires near alley entrance...",
                        color = TextSubtle,
                    )
                },
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GccGreen,
                    cursorColor = GccGreen,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Summary row
            GccCard(accentColor = CardAccentGreen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "$photoCount photo${if (photoCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (selectedTags.isNotEmpty()) "${selectedTags.size} tag${if (selectedTags.size != 1) "s" else ""}" else "No tags",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedTags.isNotEmpty()) GccSky else TextSubtle,
                    )
                }
            }

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
            }

            Spacer(modifier = Modifier.height(20.dp))

            GccButton(
                text = "Submit Report",
                onClick = {
                    focusManager.clearFocus()
                    onSubmit()
                },
                variant = GccButtonVariant.PRIMARY,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Duplicate detection blocking overlay
        if (duplicateMatch != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = false) { },
                contentAlignment = Alignment.Center,
            ) {
                GccCard(
                    accentColor = CardAccentRed,
                    modifier = Modifier
                        .padding(24.dp)
                        .imePadding()
                        .fillMaxWidth(),
                ) {
                    Text(
                        "Possible Duplicate",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = GccError,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "A spot report already exists ${duplicateMatch.distanceMeters.toInt()}m away.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    duplicateMatch.existingSpot.description?.let { desc ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "\"$desc\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 2,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "If this is a different spot, explain why:",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = overrideReason,
                        onValueChange = { overrideReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. Different alley, other side of street...", color = TextSubtle) },
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GccError,
                            cursorColor = GccError,
                        ),
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GccButton(
                            text = "Go Back",
                            onClick = onDuplicateDismiss,
                            variant = GccButtonVariant.OUTLINED,
                            modifier = Modifier.weight(1f),
                        )
                        GccButton(
                            text = "Override",
                            onClick = {
                                if (overrideReason.isNotBlank()) {
                                    onDuplicateOverride(overrideReason)
                                }
                            },
                            variant = GccButtonVariant.PRIMARY,
                            modifier = Modifier.weight(1f),
                            enabled = overrideReason.isNotBlank(),
                        )
                    }
                }
            }
        }
    }
}

/** On-device AI analysis preview card shown in the DESCRIPTION phase. */
@Composable
private fun OnDeviceAiCard(analysis: OnDeviceAnalysisResult) {
    GccCard(accentColor = CardAccentPurple) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(CardAccentPurple)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Text(
                "On-Device Analysis",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = CardAccentPurple,
            )
            Spacer(modifier = Modifier.weight(1f))
            analysis.confidence?.let {
                Text(
                    "${(it * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (analysis.trashTypes.isNotEmpty()) {
            Text(
                "Detected: ${analysis.trashTypes.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        analysis.severity?.let { sev ->
            val label = when (sev) {
                1 -> "Minor"; 2 -> "Small"; 3 -> "Moderate"; 4 -> "Large"; 5 -> "Severe"; else -> "Unknown"
            }
            Text(
                "Severity: $label ($sev/5)",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    sev >= 4 -> GccError
                    sev >= 3 -> CardAccentSky
                    else -> TextMuted
                },
            )
        }
        analysis.estimatedWeightLbs?.let {
            Text(
                "Est. Weight: ~${it.toInt()} lbs",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun ResultPhase(
    report: com.gcc.shared.model.CommunityReport?,
    session: EnvScanSessionDraft?,
    itemCount: Int,
    photoCount: Int,
    matchingBounties: List<Bounty>,
    createdBounty: Bounty?,
    isCreatingBounty: Boolean,
    bountyCreateError: String?,
    error: String?,
    onAcceptBounty: (Int) -> Unit,
    onPlaceBounty: (Int) -> Unit,
    onVolunteerCleanup: () -> Unit,
    onNewReport: () -> Unit,
    onBack: () -> Unit,
) {
    var showBountyDialog by remember(report?.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Session summary: shaded coverage map + headline stats.
        item {
            ScanSessionSummaryCard(
                session = session,
                itemCount = itemCount,
                photoCount = photoCount,
            )
        }

        // Report confirmation (only when a report was actually filed).
        if (report != null) item {
            GccCard(accentColor = CardAccentGreen) {
                Text(
                    "Report Created",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = GccGreen,
                )
                Spacer(modifier = Modifier.height(4.dp))
                report.let {
                    if (it.reporterName == "GCC") {
                        Text(
                            "Spotted by GCC",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = GccGreen,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        "Report #${it.id} submitted successfully.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                    it.description?.let { desc ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtle,
                        )
                    }
                    it.aiAnalysis?.let { analysis ->
                        Spacer(modifier = Modifier.height(8.dp))
                        AiAnalysisCard(analysis)
                    }
                    it.depthAnalysis?.let { depth ->
                        if (depth.hasDepth) {
                            Spacer(modifier = Modifier.height(8.dp))
                            DepthDataCard(depth)
                        }
                    }
                }
            }
        }

        // Points awarded
        item {
            report?.let {
                if (it.observerRewardPoints > 0) {
                    GccCard(accentColor = CardAccentSky) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "Observer Reward",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    "Thanks for spotting this!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                            Text(
                                "+${it.observerRewardPoints} pts",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = GccSky,
                            )
                        }
                    }
                }
            }
        }

        // Bounty progression: every reported spot can become a funded cleanup.
        item {
            report?.let {
                GccCard(accentColor = CardAccentSky) {
                    Text(
                        "Fund This Spot",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (createdBounty != null) {
                        Text(
                            "Bounty posted at \$${createdBounty.amount / 100}. It now appears in cleanup opportunities.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    } else {
                        Text(
                            "Turn this report into a cleanup bounty so a worker can claim the payout after documenting the cleanup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    bountyCreateError?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    GccButton(
                        text = if (createdBounty != null) "Add More Funding" else "Place Bounty",
                        onClick = { showBountyDialog = true },
                        variant = GccButtonVariant.OUTLINED,
                        enabled = !isCreatingBounty,
                        isLoading = isCreatingBounty,
                    )
                }
            }
        }

        // Matching bounties
        if (matchingBounties.isNotEmpty()) {
            item {
                SectionHeader(title = "Matching Bounties")
            }

            items(matchingBounties, key = { it.id }) { bounty ->
                BountyMatchCard(
                    bounty = bounty,
                    onAccept = { onAcceptBounty(bounty.id) },
                )
            }
        }

        // Volunteer cleanup option
        item {
            report?.let {
                GccCard(accentColor = CardAccentBlue) {
                    Text(
                        "Volunteer to Clean",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Start a volunteer cleanup at this spot to earn points and make an impact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GccButton(
                        text = "Start Volunteer Cleanup",
                        onClick = onVolunteerCleanup,
                        variant = GccButtonVariant.SECONDARY,
                    )
                }
            }
        }

        error?.let {
            item {
                Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
            }
        }

        // Action buttons
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GccButton(
                    text = "Scan Another Area",
                    onClick = onNewReport,
                    variant = GccButtonVariant.OUTLINED,
                )
                GccButton(
                    text = "Back to Dashboard",
                    onClick = onBack,
                    variant = GccButtonVariant.PRIMARY,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }

    if (showBountyDialog && report != null) {
        SpotBountyDialog(
            isLoading = isCreatingBounty,
            onDismiss = { showBountyDialog = false },
            onConfirm = { dollars ->
                onPlaceBounty(dollars)
                showBountyDialog = false
            },
        )
    }
}

@Composable
private fun SpotBountyDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var amountText by remember { mutableStateOf("25") }
    val amountDollars = amountText.toIntOrNull()
    val isValid = amountDollars != null && amountDollars >= 10

    fun dismissKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place Bounty") },
        text = {
            Column(
                modifier = Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Attach a cash reward to this reported spot. Minimum \$10.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() }) amountText = input
                    },
                    label = { Text("Amount") },
                    prefix = { Text("\$ ") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && !isValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid && !isLoading,
                onClick = {
                    dismissKeyboard()
                    amountDollars?.let(onConfirm)
                },
            ) {
                Text("Place")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    dismissKeyboard()
                    onDismiss()
                },
                enabled = !isLoading,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AiAnalysisCard(analysis: com.gcc.shared.model.AiAnalysis) {
    Column {
        Text(
            "AI Analysis",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = CardAccentPurple,
        )
        Spacer(modifier = Modifier.height(4.dp))
        analysis.severity?.let {
            Text(
                "Severity: $it",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        analysis.estimatedWeightLbs?.let {
            Text(
                "Est. Weight: ${it.toInt()} lbs",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        if (analysis.trashTypes.isNotEmpty()) {
            Text(
                "Types: ${analysis.trashTypes.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun DepthDataCard(depth: com.gcc.shared.model.DepthAnalysis) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF3B82F6))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "3D SCAN",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "LiDAR Depth Analysis",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFF3B82F6),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        depth.estimatedVolumeCubicFt?.let {
            Text(
                "Est. Volume: ~${it} cu ft",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        depth.estimatedAreaSqFt?.let {
            Text(
                "Est. Area: ~${it} sq ft",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        depth.maxDepthMeters?.let {
            Text(
                "Max Height: ${it}m",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        depth.depthConfidence?.let {
            Text(
                "Confidence: ${(it * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtle,
            )
        }
    }
}

private fun formatLat(lat: Double): String {
    val abs = kotlin.math.abs(lat)
    val intPart = abs.toInt()
    val fracPart = ((abs - intPart) * 10000).toInt().toString().padStart(4, '0')
    val dir = if (lat >= 0) "N" else "S"
    return "$intPart.$fracPart\u00B0$dir"
}

private fun formatLng(lng: Double): String {
    val abs = kotlin.math.abs(lng)
    val intPart = abs.toInt()
    val fracPart = ((abs - intPart) * 10000).toInt().toString().padStart(4, '0')
    val dir = if (lng >= 0) "E" else "W"
    return "$intPart.$fracPart\u00B0$dir"
}

@Composable
private fun BountyMatchCard(
    bounty: Bounty,
    onAccept: () -> Unit,
) {
    GccCard(accentColor = CardAccentSky) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    bounty.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                bounty.description?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 2,
                    )
                }
                bounty.communityArea?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$${bounty.amount / 100}",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = GccSky,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onAccept) {
                    Text("Accept", color = GccGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


/**
 * Display-only "never scanned" fog for the minimap: canonical ~10m ledger
 * cells within ~75m of the anchor that have no ledger cell (fresh OR stale)
 * within 7m. Cell centers are quantized to the SAME fixed-constant lattice
 * as [SessionPointsEstimator.gridCell10m] / the server's `gridCell`
 * (111_000 / 82_300, floor-indexed), so fog tiles sit flush with the
 * fresh/stale cells the server returns instead of on an anchor-relative
 * grid that straddles them.
 */
internal fun neverScannedGrid(
    anchor: Coordinates,
    serverCells: List<MapCoverageCell>,
): List<Pair<Double, Double>> {
    val latStep = 10.0 / 111_000.0
    val lngStep = 10.0 / 82_300.0
    val baseRow = floor(anchor.lat / latStep).toInt()
    val baseCol = floor(anchor.lng / lngStep).toInt()
    val cosLat = cos(anchor.lat * kotlin.math.PI / 180.0)
    val out = mutableListOf<Pair<Double, Double>>()
    for (dr in -7..7) {
        for (dc in -7..7) {
            if (dr * dr + dc * dc > 7 * 7) continue
            val lat = (baseRow + dr + 0.5) * latStep
            val lng = (baseCol + dc + 0.5) * lngStep
            val covered = serverCells.any { cell ->
                val dLatM = (cell.lat - lat) * 111_320.0
                val dLngM = (cell.lng - lng) * 111_320.0 * cosLat
                dLatM * dLatM + dLngM * dLngM < 7.0 * 7.0
            }
            if (!covered) out.add(lat to lng)
        }
    }
    return out
}
