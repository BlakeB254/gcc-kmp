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
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.DeviceCapabilities
import com.gcc.app.platform.DeviceClass
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.CameraDetectionBus
import com.gcc.app.platform.CameraSceneVoteBus
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.OutdoorGate
import com.gcc.app.platform.OutdoorState
import com.gcc.app.ui.components.*
import kotlinx.datetime.Clock
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.metersToPixels
import kotlin.math.cos
import kotlin.math.pow
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AreaMode
import com.gcc.app.viewmodel.DuplicateMatch
import com.gcc.app.viewmodel.SpotPhase
import com.gcc.app.viewmodel.SpotViewModel
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.ObservedSpotItem
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
                        SpotPhase.RESULT -> "Report Submitted"
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
            when (uiState.phase) {
                SpotPhase.CAMERA -> CameraPhase(
                    photos = uiState.capturedPhotos,
                    onCapture = { photo -> viewModel.addPhoto(photo) },
                    onRemovePhoto = { index -> viewModel.removePhoto(index) },
                    onNext = { viewModel.nextPhase() },
                    onCompleteScan = { viewModel.completeScan() },
                    onBack = onBack,
                    spotLocation = currentLocation,
                    onScanCapture = { scanPhoto -> viewModel.addScanPhoto(scanPhoto) },
                    observedItems = uiState.observedItems,
                    onTrashObserved = { item -> viewModel.addObservedItem(item) },
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
    onCompleteScan: () -> Unit,
    onBack: () -> Unit,
    spotLocation: Coordinates? = null,
    onScanCapture: (ScanCapturedPhoto) -> Unit = {},
    observedItems: List<ObservedSpotItem> = emptyList(),
    onTrashObserved: (ObservedSpotItem) -> Unit = {},
) {
    val locationProvider = koinInject<LocationProvider>()
    val deviceCapabilities = koinInject<DeviceCapabilities>()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    val gpsHeading by locationProvider.headingDegrees.collectAsState()
    val isScanMode = deviceCapabilities.deviceClass != DeviceClass.BASIC
    val scanLocation = spotLocation
        ?: currentLocation
        ?: Coordinates(MapStyles.NORTH_LAWNDALE_CENTER_LAT, MapStyles.NORTH_LAWNDALE_CENTER_LNG)

    // "Go outside" gate — GPS-accuracy evidence only here (the AVCapture
    // viewfinder has no frame stream to classify; AR mode adds camera votes).
    val gate = remember { OutdoorGate() }
    val outdoorState by gate.state.collectAsState()
    val isIndoor = outdoorState == OutdoorState.INDOOR
    LaunchedEffect(Unit) {
        locationProvider.horizontalAccuracyMeters.collect { accuracy ->
            gate.onAccuracySample(accuracy, Clock.System.now().toEpochMilliseconds())
        }
    }
    // The viewfinder itself votes indoor/outdoor (Vision pass every ~2s on
    // the AVCapture stream) — same camera evidence AR mode gets.
    LaunchedEffect(Unit) {
        CameraSceneVoteBus.votes.collect { vote ->
            gate.onSceneVote(vote, Clock.System.now().toEpochMilliseconds())
        }
    }
    LaunchedEffect(outdoorState) {
        DiagnosticsLog.info("outdoor-gate", "camera gate → $outdoorState")
    }

    // ── Environment-scan session: coverage credit + minimap layers ──
    val apiClient = koinInject<GccApiClient>()
    val tracker = remember { CoverageTracker() }
    var trackerReady by remember { mutableStateOf(false) }
    var fogCells by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
    var staleCells by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }

    // Initialise the scan grid at the first real fix (single-point "route"
    // gives a ~15m-corridor zone around the spot).
    LaunchedEffect(currentLocation, spotLocation) {
        if (trackerReady) return@LaunchedEffect
        val anchor = spotLocation ?: currentLocation ?: return@LaunchedEffect
        tracker.initFromRoute(listOf(anchor))
        trackerReady = true

        // Ledger freshness around the scan site (display-only; failures fine).
        try {
            val pad = 0.0012 // ~130m
            val bbox = listOf(
                anchor.lng - pad, anchor.lat - pad,
                anchor.lng + pad, anchor.lat + pad,
            ).joinToString(",")
            val serverCells = apiClient.getMapCoverage(bbox).cells
            staleCells = serverCells.filter { it.state == "stale" }.map { it.lat to it.lng }
            fogCells = neverScannedGrid(anchor, serverCells)
            DiagnosticsLog.info(
                "env-scan",
                "freshness: ${serverCells.size} ledger cells, ${staleCells.size} stale, ${fogCells.size} fog",
            )
        } catch (e: Exception) {
            DiagnosticsLog.error("env-scan", "coverage freshness fetch failed (minimap stays plain)", e)
        }
    }

    // Credit zones while outdoors: camera frustum sweep when heading exists.
    LaunchedEffect(trackerReady) {
        if (!trackerReady) return@LaunchedEffect
        locationProvider.locationFlow.collect { loc ->
            if (gate.state.value == OutdoorState.INDOOR) return@collect
            val heading = locationProvider.headingDegrees.value
            if (heading != null) {
                tracker.updateWithFrustum(lat = loc.lat, lng = loc.lng, headingDegrees = heading)
            } else {
                tracker.updatePosition(loc.lat, loc.lng)
            }
        }
    }
    val scannedCells by tracker.scannedCells.collectAsState()
    val scanCellCenters = remember(scannedCells) {
        scannedCells.map { (row, col) -> tracker.cellCenterLatLng(row, col) }
    }
    val trashItems = remember(observedItems) {
        observedItems.map { Coordinates(lat = it.lat, lng = it.lng) }
    }

    // Live viewfinder trash reticle (frame tap on the AVCapture stream).
    val liveDetections by CameraDetectionBus.detections.collectAsState()

    // Registration feedback: toast on tags, pulse on any credit.
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(observedItems.size) {
        if (observedItems.isNotEmpty()) {
            toast = "Trash tagged on the map ✓"
        }
    }
    val creditTick = scannedCells.size + observedItems.size * 10

    // Full-bleed viewfinder; everything else floats over it, inset-aware.
    Box(modifier = Modifier.fillMaxSize()) {
        val scanStatus = when {
            liveDetections.isNotEmpty() -> "Trash spotted — move closer and tap to tag it"
            scannedCells.isNotEmpty() -> "Scanning environment — ${scannedCells.size} zones mapped"
            else -> "Environment scan active — sweep the camera slowly"
        }
        if (isScanMode) {
            SpotScanCapture(
                spotLocation = scanLocation,
                geofenceRadius = 50.0,
                maxPhotos = 3,
                requireGeofence = spotLocation != null || currentLocation != null,
                capturedPhotos = photos,
                onPhotoCaptured = onScanCapture,
                onRemovePhoto = onRemovePhoto,
                captureBlocked = isIndoor,
                onTrashObserved = onTrashObserved,
                scanCellCenters = scanCellCenters,
                trashItems = trashItems,
                environmentScanMode = true,
                statusText = scanStatus,
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
                fogCellCenters = fogCells,
                staleCellCenters = staleCells,
                environmentScanMode = true,
                statusText = scanStatus,
            )
        }

        // Scanning feedback: sweep + registration pulse, live trash reticle,
        // transient popups — the "data is registering" layer.
        EnvironmentScanEffects(active = !isIndoor, creditTick = creditTick)
        DetectionReticleOverlay(detections = liveDetections)
        ScanToast(message = toast)

        GoOutsideOverlay(visible = isIndoor)

        // Exit (top-left) — the camera phase has no header bar.
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium, color = GccGreenDark)
            }
        }

        // Manual path (bottom-left): the old location/details phases.
        TextButton(
            onClick = onNext,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 12.dp, bottom = 16.dp),
        ) {
            Text(
                text = "Add details",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }

        // One-tap completion (bottom-right corner).
        val canComplete = photos.isNotEmpty() && !isIndoor
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (canComplete) GccGreen else GccGreen.copy(alpha = 0.4f))
                .clickable(enabled = canComplete) { onCompleteScan() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "Complete Scan ✓",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
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
                                dragAmount.x, dragAmount.y, spotLat, mapZoom,
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
                        val pixelRadius = metersToPixels(radiusMeters, spotLat, mapZoom)
                        drawCircle(color = GccSky.copy(alpha = 0.2f), radius = pixelRadius, center = center)
                        drawCircle(color = GccSky, radius = pixelRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                    }
                    AreaMode.RECTANGLE -> {
                        val halfPxW = metersToPixels(rectWidth, spotLat, mapZoom) / 2f
                        val halfPxH = metersToPixels(rectHeight, spotLat, mapZoom) / 2f
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
        // Success card
        item {
            GccCard(accentColor = CardAccentGreen) {
                Text(
                    "Report Created",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = GccGreen,
                )
                Spacer(modifier = Modifier.height(4.dp))
                report?.let {
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
                    text = "Spot Another",
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
 * Display-only "never scanned" fog for the minimap: 10m grid points within
 * ~75m of the anchor that have no ledger cell (fresh OR stale) within 7m.
 */
private fun neverScannedGrid(
    anchor: Coordinates,
    serverCells: List<MapCoverageCell>,
): List<Pair<Double, Double>> {
    val latStep = 10.0 / 111_320.0
    val lngStep = 10.0 / (111_320.0 * cos(anchor.lat * kotlin.math.PI / 180.0))
    val out = mutableListOf<Pair<Double, Double>>()
    for (dr in -7..7) {
        for (dc in -7..7) {
            if (dr * dr + dc * dc > 7 * 7) continue
            val lat = anchor.lat + dr * latStep
            val lng = anchor.lng + dc * lngStep
            val covered = serverCells.any { cell ->
                val dLatM = (cell.lat - lat) * 111_320.0
                val dLngM = (cell.lng - lng) * 111_320.0 * cos(anchor.lat * kotlin.math.PI / 180.0)
                dLatM * dLatM + dLngM * dLngM < 7.0 * 7.0
            }
            if (!covered) out.add(lat to lng)
        }
    }
    return out
}
