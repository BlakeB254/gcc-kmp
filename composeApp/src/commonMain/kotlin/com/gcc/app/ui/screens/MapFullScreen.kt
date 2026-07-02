package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.CommunityInfoPanel
import com.gcc.app.ui.map.BlockOverlay
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapMode
import com.gcc.app.ui.map.MapModeToggle
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.MyLocationButton
import com.gcc.app.ui.map.PrecipitationLegend
import com.gcc.app.ui.map.RouteOverlay
import com.gcc.app.ui.map.findCommunityAreaForLocation
import com.gcc.app.ui.map.resolveMapCenter
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.GccBottomNavBar
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.components.PhotoStrip
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AffectedBlocksViewModel
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.model.Block
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CommunityReportStatus
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.Job
import com.gcc.shared.model.JobStatus
import com.gcc.shared.model.Route
import com.gcc.shared.model.isTestAssignment
import com.gcc.shared.model.testAssignmentStartDeadlineMillis
import com.gcc.shared.offline.SeedData
import kotlin.math.ln
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

@Composable
fun MapFullScreen(
    onBack: () -> Unit = {},
    onRouteClick: (Int) -> Unit = {},
    onBountyClick: (Int) -> Unit = {},
    onVolunteerCleanup: (reportId: Int) -> Unit = {},
    onSpotVerification: (reportId: Int) -> Unit = {},
    onActiveJobClick: (jobId: Int, routeId: Int) -> Unit = { _, _ -> },
    onAdminClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onNavigateToSpot: () -> Unit = {},
) {
    val viewModel = koinInject<DashboardViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val bountyViewModel = koinInject<BountyViewModel>()
    val bountyState by bountyViewModel.uiState.collectAsState()
    val affectedBlocksVm = koinInject<AffectedBlocksViewModel>()
    val affectedState by affectedBlocksVm.uiState.collectAsState()
    val locationProvider = koinInject<LocationProvider>()
    val tokenManager = koinInject<TokenManager>()
    val isAdmin = remember { tokenManager.isAdmin() }

    // Wire real GPS location
    val currentLocation by locationProvider.currentLocation.collectAsState()
    var nowMillis by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }

    LaunchedEffect(Unit) {
        locationProvider.startLocationUpdates()
        viewModel.loadDashboard()
        if (uiState.worker == null) viewModel.loadWorkerProfile()
        if (uiState.serviceAreaName == null) viewModel.loadServiceArea()
        // Always refresh spots (admin may have deleted/updated them)
        viewModel.loadSpots()
        bountyViewModel.loadBounties()
    }

    DisposableEffect(Unit) {
        // Location updates are started in the LaunchedEffect above; this
        // handler owns the heatmap polling lifecycle so it cleans up when
        // the user leaves the map screen.
        affectedBlocksVm.startPolling()
        onDispose {
            locationProvider.stopLocationUpdates()
            affectedBlocksVm.stopPolling()
        }
    }

    LaunchedEffect(uiState.assignedJobs) {
        while (uiState.assignedJobs.any { it.isTestAssignment() && it.status == JobStatus.CLAIMED }) {
            nowMillis = Clock.System.now().toEpochMilliseconds()
            delay(1000L)
        }
    }

    // Dual-mode state
    var mapMode by remember { mutableStateOf(MapMode.MY_AREA) }

    // Selected spot for info card
    var selectedSpot by remember { mutableStateOf<CommunityReport?>(null) }

    // Camera override for zooming to a tapped spot/bounty/route
    var overrideCenterLat by remember { mutableStateOf<Double?>(null) }
    var overrideCenterLng by remember { mutableStateOf<Double?>(null) }
    var overrideZoom by remember { mutableStateOf<Double?>(null) }
    var selectedRecordIndex by remember { mutableStateOf<Int?>(null) }

    // Precipitation overlay visibility (legend + heat + scrim + dots)
    var heatmapVisible by remember { mutableStateOf(true) }

    // Selected route for zoom-to-fit before navigation
    var selectedRouteForZoom by remember { mutableStateOf<com.gcc.shared.model.Route?>(null) }

    // Navigate to route detail after brief zoom animation
    LaunchedEffect(selectedRouteForZoom) {
        val route = selectedRouteForZoom ?: return@LaunchedEffect
        kotlinx.coroutines.delay(800) // Let map animate to show full route
        onRouteClick(route.id)
        selectedRouteForZoom = null
    }

    val resolved = remember(uiState.serviceArea, currentLocation) {
        resolveMapCenter(
            serviceArea = uiState.serviceArea,
            currentLocation = currentLocation,
        )
    }
    val centerLat = overrideCenterLat ?: resolved.lat
    val centerLng = overrideCenterLng ?: resolved.lng
    val mapZoom = overrideZoom ?: resolved.zoom
    // GPS-gated visibility: admin sees all data globally,
    // normal users see only their current community area
    val currentGpsArea = remember(currentLocation) {
        currentLocation?.let { findCommunityAreaForLocation(it.lat, it.lng) }
    }
    val visibleBounties = remember(bountyState.bounties, mapMode, currentGpsArea, isAdmin) {
        when {
            mapMode == MapMode.MY_AREA -> emptyList()
            isAdmin -> bountyState.bounties // admin sees all bounties globally
            else -> {
                val area = currentGpsArea
                if (area != null) {
                    bountyState.bounties.filter { bounty ->
                        bounty.communityArea == area.name ||
                        (bounty.communityArea == null &&
                            bounty.locationLat in area.bounds.south..area.bounds.north &&
                            bounty.locationLng in area.bounds.west..area.bounds.east)
                    }
                } else {
                    emptyList() // no GPS fix = no bounties for normal users
                }
            }
        }
    }
    val visibleSpots = remember(uiState.spots, mapMode, currentGpsArea, isAdmin) {
        when {
            mapMode == MapMode.MY_AREA -> uiState.spots // show all spots in service area view
            isAdmin -> uiState.spots // admin sees all spots globally
            else -> {
                val area = currentGpsArea
                if (area != null) {
                    uiState.spots.filter { spot ->
                        spot.coordinates.lat in area.bounds.south..area.bounds.north &&
                        spot.coordinates.lng in area.bounds.west..area.bounds.east
                    }
                } else {
                    emptyList()
                }
            }
        }
    }
    val assignedJobRoutePairs = remember(uiState.assignedJobs, uiState.assignedRoutesById) {
        uiState.assignedJobs.mapNotNull { job ->
            val route = job.routeId?.let { uiState.assignedRoutesById[it] } ?: return@mapNotNull null
            job to route
        }
    }
    val primaryAssignedPair = assignedJobRoutePairs.firstOrNull()

    // Worker info for the panel
    val areaName = uiState.serviceAreaName ?: "Your Area"
    val displayBlocks = remember(uiState.blocks) {
        (if (uiState.blocks.isNotEmpty()) uiState.blocks else SeedData.blocks)
            .sortedByMapAlignment()
    }
    val availableRoutes = uiState.routes.size
    val totalRouteRecords = when {
        displayBlocks.isNotEmpty() -> displayBlocks.size
        uiState.serviceArea?.totalBlocks ?: 0 > 0 -> uiState.serviceArea?.totalBlocks ?: 0
        else -> uiState.routes.size
    }
    val hasBottomRecords = mapMode == MapMode.MY_AREA &&
        selectedSpot == null &&
        (uiState.routes.isNotEmpty() || displayBlocks.isNotEmpty())
    val jobsCompleted = uiState.worker?.stats?.jobsCompleted ?: 0
    val totalEarnings = uiState.worker?.stats?.totalEarnings ?: 0

    // Visual map routes are separate from worker-available routes. When no
    // route is claimable, block records still draw their route boundary so
    // the inventory can be reviewed without exposing a start action.
    val availableRouteCoordinates: List<List<Coordinates>> = remember(uiState.routes) {
        uiState.routes.map { it.routePathCoordinates() }
    }
    val assignedRouteCoordinates: List<List<Coordinates>> = remember(assignedJobRoutePairs, currentLocation) {
        val assignedRoutes = assignedJobRoutePairs.map { (_, route) -> route.routePathCoordinates() }
        val location = currentLocation
        val guidanceLines = if (location != null) {
            assignedJobRoutePairs.mapNotNull { (_, route) ->
                route.routePathCoordinates().firstOrNull()?.let { start -> listOf(location, start) }
            }
        } else {
            emptyList()
        }
        guidanceLines + assignedRoutes
    }
    val blockRecordCoordinates: List<List<Coordinates>> = remember(displayBlocks) {
        displayBlocks.mapNotNull { block ->
            block.blockRouteCoordinates().takeIf { it.size >= 2 }
        }
    }
    val mapRouteCoordinates = if (assignedRouteCoordinates.isNotEmpty()) {
        assignedRouteCoordinates
    } else if (availableRouteCoordinates.isNotEmpty()) {
        availableRouteCoordinates
    } else {
        blockRecordCoordinates
    }
    val selectedRouteCoordinates = selectedRecordIndex
        ?.let { mapRouteCoordinates.getOrNull(it) }
        ?.let { listOf(it) }
        ?: emptyList()
    val routeRecordCount = mapRouteCoordinates.size

    LaunchedEffect(routeRecordCount) {
        val selected = selectedRecordIndex
        if (selected != null && selected !in 0 until routeRecordCount) {
            selectedRecordIndex = null
        }
    }

    val imageStars: List<Coordinates> = remember(uiState.routes, displayBlocks) {
        uiState.routes.mapNotNull { route ->
            route.imageLocationCoordinates()
        }.ifEmpty {
            displayBlocks.mapNotNull { block -> block.imageLocationCoordinates() }
        }
    }

    fun zoomToCoordinates(points: List<Coordinates>, fallback: Coordinates? = null) {
        if (points.size >= 2) {
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLng = points.minOf { it.lng }
            val maxLng = points.maxOf { it.lng }
            overrideCenterLat = (minLat + maxLat) / 2.0
            overrideCenterLng = (minLng + maxLng) / 2.0
            val latSpan = (maxLat - minLat) * 1.35
            val lngSpan = (maxLng - minLng) * 1.35
            val latZoom = if (latSpan > 0) ln(360.0 / latSpan) / ln(2.0) else 17.0
            val lngZoom = if (lngSpan > 0) ln(360.0 / lngSpan) / ln(2.0) else 17.0
            overrideZoom = minOf(latZoom, lngZoom).coerceIn(12.0, 18.0)
        } else if (fallback != null) {
            overrideCenterLat = fallback.lat
            overrideCenterLng = fallback.lng
            overrideZoom = MapStyles.ZOOM_ROUTE
        }
    }

    fun selectBlockRecord(index: Int) {
        val block = displayBlocks.getOrNull(index) ?: return
        selectedRecordIndex = index
        zoomToCoordinates(
            points = block.blockRouteCoordinates(),
            fallback = block.centerCoordinates(),
        )
    }

    fun selectAvailableRoute(index: Int, navigateAfterZoom: Boolean) {
        val route = uiState.routes.getOrNull(index) ?: return
        selectedRecordIndex = index
        zoomToCoordinates(route.routePathCoordinates())
        if (navigateAfterZoom) selectedRouteForZoom = route
    }

    fun selectVisualRoute(index: Int) {
        when {
            uiState.routes.isNotEmpty() -> selectAvailableRoute(index, navigateAfterZoom = false)
            displayBlocks.isNotEmpty() -> selectBlockRecord(index)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            GccBottomNavBar(
                selectedTab = 1,
                isAdmin = isAdmin,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> onBack()
                        1 -> { /* already on map */ }
                        2 -> if (isAdmin) onAdminClick() else onProfileClick()
                        3 -> if (isAdmin) onProfileClick()
                    }
                },
            )
        },
    ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        GccMap(
            modifier = Modifier.fillMaxSize(),
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = mapZoom,
            currentLocation = currentLocation,
            enableGestures = true,
            showControls = true,
            statusBarPadding = true,
            routeCoordinates = if (mapMode == MapMode.MY_AREA) {
                selectedRouteCoordinates.ifEmpty { mapRouteCoordinates }
            } else emptyList(),
            imageStars = if (mapMode == MapMode.MY_AREA) imageStars else emptyList(),
            onAreaTap = { /* tooltip handled internally by GccMap */ },
            onRouteTap = if (mapMode == MapMode.MY_AREA) { routeIndex ->
                val assigned = primaryAssignedPair
                if (assigned != null && assignedRouteCoordinates.isNotEmpty()) {
                    onActiveJobClick(assigned.first.id, assigned.second.id)
                } else {
                    val actualIndex = selectedRecordIndex ?: routeIndex
                    selectVisualRoute(actualIndex)
                }
            } else null,
            onSpotTap = { spotId ->
                val spot = (uiState.spots).find { it.id == spotId }
                if (spot != null) {
                    selectedSpot = spot
                    selectedRecordIndex = null
                    overrideCenterLat = spot.coordinates.lat
                    overrideCenterLng = spot.coordinates.lng
                    overrideZoom = MapStyles.ZOOM_ROUTE
                }
            },
            onBountyTap = { bountyId ->
                onBountyClick(bountyId)
            },
            cameraBounds = null,
            bounties = visibleBounties,
            spots = if (heatmapVisible) visibleSpots else emptyList(),
            mapMode = mapMode,
            affectedBlocks = affectedState.blocks,
            onAffectedBlockTap = { blockId ->
                affectedBlocksVm.selectBlock(blockId)
                // Zoom in so the polygon fill resolves clearly — same
                // camera override pattern routes + spots already use.
                val block = affectedState.blocks.firstOrNull { it.blockId == blockId }
                val center = block?.center
                if (center != null) {
                    overrideCenterLat = center.lat
                    overrideCenterLng = center.lng
                    overrideZoom = MapStyles.ZOOM_ROUTE
                }
            },
        ) {
            // Top bar: toggle + community info inline
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Community info panel (left) — only in My Area
                if (mapMode == MapMode.MY_AREA && selectedSpot == null) {
                    CommunityInfoPanel(
                        communityAreaName = areaName,
                        totalRoutes = totalRouteRecords,
                        registeredRoutes = availableRoutes,
                        jobsCompleted = jobsCompleted,
                        totalEarnings = totalEarnings,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Mode toggle (right)
                MapModeToggle(
                    currentMode = mapMode,
                    onModeChange = {
                        mapMode = it
                        overrideCenterLat = null
                        overrideCenterLng = null
                        overrideZoom = null
                        selectedSpot = null
                        selectedRecordIndex = null
                    },
                )

                // Heatmap layer toggle — sits beside MapModeToggle, inherits Row's statusBarsPadding
                FilledTonalIconButton(
                    onClick = { heatmapVisible = !heatmapVisible },
                ) {
                    Text(
                        if (heatmapVisible) "●" else "○",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // Precipitation intensity legend — right edge, vertically centered so it
            // never overlaps the top-left community info panel (routes/jobs/earnings).
            if (heatmapVisible) {
                PrecipitationLegend(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                )
            }

            selectedRecordIndex?.let { selectedIndex ->
                if (
                    mapMode == MapMode.MY_AREA &&
                    selectedSpot == null &&
                    routeRecordCount > 1
                ) {
                    RouteNavigationControls(
                        selectedIndex = selectedIndex,
                        total = routeRecordCount,
                        onPrevious = { selectVisualRoute(selectedIndex - 1) },
                        onNext = { selectVisualRoute(selectedIndex + 1) },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
            }

            // Bottom-right map actions: keep Spot and Location peers in one row.
            if (selectedSpot == null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 16.dp,
                            bottom = if (hasBottomRecords) 80.dp else 16.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpotCreateButton(onClick = onNavigateToSpot)

                    if (currentLocation != null) {
                        MyLocationButton(
                            onClick = {
                                overrideCenterLat = currentLocation?.lat
                                overrideCenterLng = currentLocation?.lng
                                overrideZoom = MapStyles.ZOOM_ACTIVE_JOB
                            },
                        )
                    }
                }
            }

            // Inventory chips at the bottom. Published routes are actionable;
            // block records are review-only until an admin publishes a route.
            if (mapMode == MapMode.MY_AREA && selectedSpot == null) {
                when {
                    uiState.routes.isNotEmpty() -> RouteOverlay(
                        routes = uiState.routes,
                        selectedRouteId = selectedRecordIndex?.let { uiState.routes.getOrNull(it)?.id },
                        onRouteClick = { route ->
                            // Zoom to fit the full route path, then navigate after a brief pause
                            val index = uiState.routes.indexOfFirst { it.id == route.id }.coerceAtLeast(0)
                            selectAvailableRoute(index, navigateAfterZoom = true)
                        },
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                    displayBlocks.isNotEmpty() -> BlockOverlay(
                        blocks = displayBlocks,
                        selectedBlockId = selectedRecordIndex?.let { displayBlocks.getOrNull(it)?.id },
                        onBlockClick = { block ->
                            val index = displayBlocks.indexOfFirst { it.id == block.id }.coerceAtLeast(0)
                            selectBlockRecord(index)
                        },
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }

            primaryAssignedPair?.let { (job, route) ->
                if (mapMode == MapMode.MY_AREA && selectedSpot == null) {
                    AssignedMapRouteBanner(
                        job = job,
                        route = route,
                        nowMillis = nowMillis,
                        onOpen = { onActiveJobClick(job.id, route.id) },
                        onDecline = { viewModel.declineAssignedJob(job.id) },
                        isDeclining = job.id in uiState.decliningAssignedJobIds,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                start = 12.dp,
                                end = 12.dp,
                                bottom = if (hasBottomRecords) 94.dp else 16.dp,
                            ),
                    )
                }
            }

            // Spot info card — shown when a spot is tapped
            selectedSpot?.let { spot ->
                SpotInfoCard(
                    spot = spot,
                    onDismiss = {
                        selectedSpot = null
                        overrideCenterLat = null
                        overrideCenterLng = null
                        overrideZoom = null
                    },
                    onVolunteerCleanup = {
                        onVolunteerCleanup(spot.id)
                        selectedSpot = null
                    },
                    onVerifySpot = {
                        onSpotVerification(spot.id)
                        selectedSpot = null
                    },
                    onPlaceBounty = {
                        onBountyClick(-spot.id)
                        selectedSpot = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                )
            }

            // Affected-block info card — shown when a heatmap polygon is tapped
            affectedState.selectedBlock?.let { block ->
                AffectedBlockInfoCard(
                    block = block,
                    onDismiss = {
                        affectedBlocksVm.clearSelection()
                        overrideCenterLat = null
                        overrideCenterLng = null
                        overrideZoom = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun AssignedMapRouteBanner(
    job: Job,
    route: Route,
    nowMillis: Long,
    onOpen: () -> Unit,
    onDecline: () -> Unit,
    isDeclining: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (job.status == JobStatus.IN_PROGRESS) "Route in progress" else "Assigned route",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (job.status == JobStatus.IN_PROGRESS) GccGreen else GccClaimed,
                )
                Text(
                    route.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                assignedCountdownText(job, nowMillis)?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.testAssignmentStartDeadlineMillis()?.let { it <= nowMillis } == true) GccError else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                modifier = Modifier.width(112.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GccButton(
                    text = if (job.status == JobStatus.IN_PROGRESS) "Continue" else "Start",
                    onClick = onOpen,
                    variant = GccButtonVariant.SECONDARY,
                )
                if (job.status == JobStatus.CLAIMED) {
                    TextButton(
                        onClick = onDecline,
                        enabled = !isDeclining,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (isDeclining) "Declining" else "Decline",
                            style = MaterialTheme.typography.labelSmall,
                            color = GccError,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotCreateButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = "Report spot" },
        shape = RoundedCornerShape(12.dp),
        containerColor = GccGreen,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp,
        ),
    ) {
        com.gcc.app.ui.components.EnvironmentScanIcon(
            beamColor = Color.White,
            natureColor = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun RouteNavigationControls(
    selectedIndex: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
    ) {
        RouteArrowButton(
            text = "<",
            enabled = selectedIndex > 0,
            onClick = onPrevious,
            contentDescription = "Previous route",
            modifier = Modifier.align(Alignment.CenterStart),
        )
        RouteArrowButton(
            text = ">",
            enabled = selectedIndex < total - 1,
            onClick = onNext,
            contentDescription = "Next route",
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun RouteArrowButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = Color.White.copy(alpha = if (enabled) 0.92f else 0.42f),
        shadowElevation = if (enabled) 4.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) GccGreenDark else TextSubtle,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun Route.routePathCoordinates(): List<Coordinates> =
    (geometry?.toCoordinates().orEmpty().withoutClosingDuplicate())
        .ifEmpty {
            checkpoints
                .sortedBy { it.sequenceOrder }
                .map { it.coordinates }
                .withoutClosingDuplicate()
        }

private fun Block.blockRouteCoordinates(): List<Coordinates> =
    boundary?.coordinates
        ?.firstOrNull()
        ?.mapNotNull { point ->
            val lng = point.getOrNull(0)
            val lat = point.getOrNull(1)
            if (lng != null && lat != null) Coordinates(lng = lng, lat = lat) else null
        }
        .orEmpty()
        .withoutClosingDuplicate()
        .ifEmpty {
            checkpoints
                .sortedBy { it.sequenceOrder }
                .map { it.coordinates }
                .withoutClosingDuplicate()
                .ifEmpty { generatedBlockRectangle() }
        }

private fun Route.imageLocationCoordinates(): Coordinates? {
    val lat = imageLocationLat
    val lng = imageLocationLng
    return if (lat != null && lng != null) Coordinates(lng = lng, lat = lat) else null
}

private fun Block.imageLocationCoordinates(): Coordinates? {
    val lat = imageLocationLat
    val lng = imageLocationLng
    return if (lat != null && lng != null) Coordinates(lng = lng, lat = lat) else null
}

private fun Block.centerCoordinates(): Coordinates? {
    val lng = center?.coordinates?.getOrNull(0)
    val lat = center?.coordinates?.getOrNull(1)
    return when {
        lat != null && lng != null -> Coordinates(lng = lng, lat = lat)
        else -> imageLocationCoordinates()
    }
}

private fun Block.generatedBlockRectangle(): List<Coordinates> {
    val center = centerCoordinates() ?: return emptyList()
    val halfLat = 0.00092
    val halfLng = 0.00042
    return listOf(
        Coordinates(lng = center.lng - halfLng, lat = center.lat - halfLat),
        Coordinates(lng = center.lng - halfLng, lat = center.lat + halfLat),
        Coordinates(lng = center.lng + halfLng, lat = center.lat + halfLat),
        Coordinates(lng = center.lng + halfLng, lat = center.lat - halfLat),
    )
}

private fun List<Block>.sortedByMapAlignment(): List<Block> =
    sortedWith(
        compareBy<Block>(
            { it.centerCoordinates()?.lng ?: it.blockRouteCoordinates().centerLng() ?: Double.MAX_VALUE },
            { it.centerCoordinates()?.lat ?: it.blockRouteCoordinates().centerLat() ?: Double.MAX_VALUE },
            { it.code },
        ),
    )

private fun List<Coordinates>.centerLng(): Double? =
    takeIf { it.isNotEmpty() }?.map { it.lng }?.average()

private fun List<Coordinates>.centerLat(): Double? =
    takeIf { it.isNotEmpty() }?.map { it.lat }?.average()

private fun List<Coordinates>.withoutClosingDuplicate(): List<Coordinates> {
    if (size < 2) return this
    val first = first()
    val last = last()
    return if (first.lat == last.lat && first.lng == last.lng) dropLast(1) else this
}

private fun assignedCountdownText(job: Job, nowMillis: Long): String? {
    if (!job.isTestAssignment()) return null
    if (job.status == JobStatus.IN_PROGRESS) return "Session active"
    if (job.status != JobStatus.CLAIMED) return null
    val deadline = job.testAssignmentStartDeadlineMillis() ?: return null
    val remainingMillis = deadline - nowMillis
    if (remainingMillis <= 0) return "Start window expired"
    val totalSeconds = remainingMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "Start window ${minutes}:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun AffectedBlockInfoCard(
    block: com.gcc.shared.model.AffectedBlock,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (block.status) {
        com.gcc.shared.model.AffectedBlockStatus.IN_PROGRESS -> Color(0xFFFF9800)
        com.gcc.shared.model.AffectedBlockStatus.COMPLETED -> GccGreen
        com.gcc.shared.model.AffectedBlockStatus.FUNDED -> GccSky
        com.gcc.shared.model.AffectedBlockStatus.AVAILABLE -> Color(0xFF9E9E9E)
    }
    val fundingPct = if (block.fundingGoal > 0) {
        ((block.fundingCurrent / block.fundingGoal) * 100).toInt().coerceIn(0, 100)
    } else 0

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.code ?: "Block #${block.blockId}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            block.status.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(24.dp),
                    ) {
                        Text("✕", color = TextMuted)
                    }
                }
            }

            block.name?.let { name ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    maxLines = 2,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(
                        "Funding",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSubtle,
                    )
                    Text(
                        "$fundingPct%",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = GccGreen,
                    )
                }
                Column {
                    Text(
                        "Sponsors",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSubtle,
                    )
                    Text(
                        "${block.sponsorCount}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                block.scheduledCleaningAt?.let { scheduled ->
                    Column {
                        Text(
                            "Scheduled",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSubtle,
                        )
                        Text(
                            // ISO timestamp — trim to YYYY-MM-DD for glance
                            scheduled.take(10),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotInfoCard(
    spot: CommunityReport,
    onDismiss: () -> Unit,
    onVolunteerCleanup: () -> Unit = {},
    onVerifySpot: () -> Unit = {},
    onPlaceBounty: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusColor = when (spot.status) {
        com.gcc.shared.model.CommunityReportStatus.PENDING -> Color(0xFFFF9800)
        com.gcc.shared.model.CommunityReportStatus.VERIFIED -> GccGreen
        com.gcc.shared.model.CommunityReportStatus.RESOLVED -> GccSky
        com.gcc.shared.model.CommunityReportStatus.ACKNOWLEDGED -> Color(0xFF2196F3)
        com.gcc.shared.model.CommunityReportStatus.REJECTED -> Color(0xFFFF5252)
    }

    // Full-screen image viewer state
    var fullScreenImageIndex by remember { mutableStateOf<Int?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    spot.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            spot.status.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(24.dp),
                    ) {
                        Text("\u2715", color = TextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Photos — larger thumbnails, tappable for full-screen view
            if (spot.mediaUrls.isNotEmpty()) {
                PhotoStrip(
                    urls = spot.mediaUrls,
                    photoSize = 120,
                    onPhotoClick = { index -> fullScreenImageIndex = index },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            spot.description?.let { desc ->
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 24-hour lifecycle chip (fresh / due soon / overdue)
            LifecycleChip(spot)

            Spacer(modifier = Modifier.height(10.dp))

            // 4-stage progression: Reported -> Confirmed -> Cleaned -> Bounty
            SpotProgressionStrip(spot)

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "by ${spot.reporterName ?: "Anonymous"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (spot.reporterName == "GCC") GccGreen else TextSubtle,
                )
                Text(
                    "${spot.affectedRadiusMeters.toInt()}m radius",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtle,
                )
                if (spot.mediaUrls.isNotEmpty()) {
                    Text(
                        "${spot.mediaUrls.size} photo${if (spot.mediaUrls.size != 1) "s" else ""} \u2022 tap to enlarge",
                        style = MaterialTheme.typography.bodySmall,
                        color = GccGreen,
                    )
                }
            }

            // Action buttons (only for pending/verified sightings)
            if (spot.status == com.gcc.shared.model.CommunityReportStatus.PENDING ||
                spot.status == com.gcc.shared.model.CommunityReportStatus.VERIFIED
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onVerifySpot,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                    ) {
                        Text(
                            "Verify",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    Button(
                        onClick = onVolunteerCleanup,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                    ) {
                        Text(
                            "Clean Up",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                if (spot.needsVerification) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This spot needs re-verification",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPlaceBounty,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccSky),
                ) {
                    Text(
                        "Fund Spot",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }

    // Full-screen image gallery overlay
    fullScreenImageIndex?.let { startIndex ->
        FullScreenImageGallery(
            urls = spot.mediaUrls,
            captions = spot.mediaCaptions,
            startIndex = startIndex,
            onDismiss = { fullScreenImageIndex = null },
        )
    }
}

@Composable
private fun FullScreenImageGallery(
    urls: List<String>,
    captions: List<String> = emptyList(),
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    var currentIndex by remember { mutableStateOf(startIndex) }
    val currentCaption = captions.getOrNull(currentIndex)?.takeIf { it.isNotBlank() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable(onClick = onDismiss)
            .pointerInput(urls.size) {
                // Swipe left/right to navigate
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag < -80f && currentIndex < urls.size - 1) {
                            currentIndex++
                        } else if (totalDrag > 80f && currentIndex > 0) {
                            currentIndex--
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                )
            },
    ) {
        // Close button
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2715", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        // Current image — large
        NetworkImage(
            url = urls[currentIndex],
            contentDescription = "Photo ${currentIndex + 1} of ${urls.size}",
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.Center)
                .padding(16.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )

        // Navigation, caption, and counter at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Caption
            currentCaption?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Page indicator
            Text(
                "${currentIndex + 1} / ${urls.size}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )

            if (urls.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Swipe to navigate",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TextButton(
                        onClick = { if (currentIndex > 0) currentIndex-- },
                        enabled = currentIndex > 0,
                    ) {
                        Text(
                            "\u2190 Prev",
                            color = if (currentIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                    TextButton(
                        onClick = { if (currentIndex < urls.size - 1) currentIndex++ },
                        enabled = currentIndex < urls.size - 1,
                    ) {
                        Text(
                            "Next \u2192",
                            color = if (currentIndex < urls.size - 1) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

// \u2500\u2500 24-hour lifecycle chip \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
//
// Each spot expects re-verification within 24h of last verification. This
// chip surfaces that lifecycle so reporters and verifiers can see at a
// glance whether a spot is fresh, due soon, or overdue.

private enum class LifecycleState(val label: String, val color: Color) {
    FRESH("Fresh", GccGreen),
    DUE_SOON("Due Soon", Color(0xFFFF9800)),
    OVERDUE("Overdue", Color(0xFFE53935)),
    UNKNOWN("\u2014", Color(0xFF9E9E9E)),
}

private fun lifecycleStateFor(spot: CommunityReport): Pair<LifecycleState, String> {
    val due = spot.nextVerificationDue
    if (due == null) {
        return LifecycleState.UNKNOWN to ""
    }
    val now = Clock.System.now()
    val deltaSeconds = (due - now).inWholeSeconds
    val absHours = (deltaSeconds.absoluteValue / 3600.0)
    val countdown = when {
        absHours >= 24 -> "${(absHours / 24).toInt()}d"
        absHours >= 1 -> "${absHours.toInt()}h"
        else -> "${(deltaSeconds.absoluteValue / 60).coerceAtLeast(1L)}m"
    }
    return when {
        deltaSeconds <= 0 -> LifecycleState.OVERDUE to "Overdue $countdown"
        deltaSeconds < 6 * 3600 -> LifecycleState.DUE_SOON to "$countdown left"
        else -> LifecycleState.FRESH to "$countdown left"
    }
}

@Composable
private fun LifecycleChip(spot: CommunityReport) {
    val (state, countdown) = lifecycleStateFor(spot)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = state.color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(state.color),
            )
            Text(
                state.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = state.color,
            )
            if (countdown.isNotBlank()) {
                Text(
                    "\u00b7 $countdown",
                    style = MaterialTheme.typography.labelMedium,
                    color = state.color.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// \u2500\u2500 Spot lifecycle progression strip \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
//
// 4-stage horizontal progression that surfaces the spot's full lifecycle:
//   Reported -> Confirmed -> Cleaned -> Bounty
// Each stage either filled (completed), active (the next milestone), or
// muted (not yet reached). The bounty stage shows a "?" coin until the
// cleanup completes \u2014 this is the "secret bounty" UX.

private data class ProgressionStage(
    val label: String,
    val icon: String,
    val filled: Boolean,
    val isCurrent: Boolean,
)

@Composable
private fun SpotProgressionStrip(spot: CommunityReport) {
    val confirmed = spot.verificationCount >= 1 ||
        spot.status == CommunityReportStatus.VERIFIED ||
        spot.status == CommunityReportStatus.RESOLVED ||
        spot.status == CommunityReportStatus.ACKNOWLEDGED
    val cleaned = spot.status == CommunityReportStatus.RESOLVED || spot.resolvedAt != null
    val bountyPaid = spot.bountyId != null && cleaned
    val secretBounty = spot.bountyId != null && !cleaned

    val stages = listOf(
        ProgressionStage(
            label = "Reported",
            icon = "\ud83d\udccd",
            filled = true,
            isCurrent = !confirmed,
        ),
        ProgressionStage(
            label = "Confirmed",
            icon = "\u2714",
            filled = confirmed,
            isCurrent = confirmed && !cleaned,
        ),
        ProgressionStage(
            label = "Cleaned",
            icon = "\ud83e\uddf9",
            filled = cleaned,
            isCurrent = cleaned && !bountyPaid && spot.bountyId != null,
        ),
        ProgressionStage(
            label = if (secretBounty) "Bounty?" else "Bounty",
            icon = if (secretBounty) "?" else "\ud83d\udcb0",
            filled = bountyPaid,
            isCurrent = false,
        ),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEachIndexed { index, stage ->
            ProgressionNode(stage)
            if (index < stages.lastIndex) {
                ProgressionConnector(filled = stages[index + 1].filled || stage.filled && stages[index + 1].isCurrent)
            }
        }
    }
}

@Composable
private fun ProgressionNode(stage: ProgressionStage) {
    val activeColor = when {
        stage.filled -> GccGreen
        stage.isCurrent -> Color(0xFFFF9800)
        else -> Color(0xFF9E9E9E).copy(alpha = 0.4f)
    }
    val labelColor = when {
        stage.filled -> GccGreen
        stage.isCurrent -> Color(0xFFFF9800)
        else -> TextSubtle
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(activeColor.copy(alpha = if (stage.filled) 1f else 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stage.icon,
                style = MaterialTheme.typography.bodySmall,
                color = if (stage.filled) Color.White else activeColor,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stage.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = labelColor,
        )
    }
}

@Composable
private fun RowScope.ProgressionConnector(filled: Boolean) {
    Box(
        modifier = Modifier
            .height(2.dp)
            .weight(1f)
            .padding(horizontal = 6.dp)
            .background(
                if (filled) GccGreen.copy(alpha = 0.6f)
                else Color(0xFF9E9E9E).copy(alpha = 0.25f)
            ),
    )
}
