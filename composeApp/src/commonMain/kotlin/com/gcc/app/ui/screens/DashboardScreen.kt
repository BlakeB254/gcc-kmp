package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapCenter
import com.gcc.app.ui.map.MapMode
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.MyLocationButton
import com.gcc.app.ui.map.RouteThumbnail
import com.gcc.app.ui.map.resolveMapCenter
import com.gcc.app.ui.theme.*
import com.gcc.app.platform.FirstRunStore
import com.gcc.app.platform.LocationProvider
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.geo.blockPathCoordinates
import com.gcc.shared.geo.routePathCoordinates
import com.gcc.shared.geo.routeStartCoordinates
import com.gcc.shared.model.Block
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.Job
import com.gcc.shared.model.JobStatus
import com.gcc.shared.model.Route
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.isTestAssignment
import com.gcc.shared.model.testAssignmentStartDeadlineMillis
import com.gcc.app.util.padTwo
import gcc_kmp.composeapp.generated.resources.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onRouteClick: (Int) -> Unit = {},
    onActiveJobClick: (jobId: Int, routeId: Int) -> Unit = { _, _ -> },
    onProfileClick: () -> Unit = {},
    onMapClick: () -> Unit = {},
    onSpotClick: () -> Unit = {},
    onBountyListClick: () -> Unit = {},
    onWalletClick: () -> Unit = {},
    onLeaderboardClick: () -> Unit = {},
    onFeedClick: () -> Unit = {},
    onAchievementsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAdminClick: () -> Unit = {},
    onPointsClick: () -> Unit = {},
    onImpactClick: () -> Unit = {},
    onSponsorWallClick: () -> Unit = {},
) {
    val viewModel = koinInject<DashboardViewModel>()
    val tokenManager = koinInject<TokenManager>()
    val locationProvider = koinInject<LocationProvider>()
    val firstRunStore = koinInject<FirstRunStore>()
    val isAdmin = remember { tokenManager.isAdmin() }
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    var nowMillis by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    var alertedAssignedJobIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showFirstBlockSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        locationProvider.startLocationUpdates()
        viewModel.loadDashboard()
        viewModel.loadWorkerProfile()
        viewModel.loadServiceArea()
        viewModel.loadSpots()
    }

    LaunchedEffect(uiState.assignedJobs) {
        while (uiState.assignedJobs.any { job ->
                job.isTestAssignment() &&
                    job.status == JobStatus.CLAIMED &&
                    job.testAssignmentStartDeadlineMillis()?.let { it > Clock.System.now().toEpochMilliseconds() } == true
            }
        ) {
            nowMillis = Clock.System.now().toEpochMilliseconds()
            delay(1000L)
        }
        nowMillis = Clock.System.now().toEpochMilliseconds()
    }

    LaunchedEffect(uiState.assignedJobs, uiState.assignedRoutesById) {
        val newAssignedJob = uiState.assignedJobs.firstOrNull { job ->
            job.id !in alertedAssignedJobIds &&
                job.isDashboardVisible(nowMillis) &&
                job.routeId?.let { uiState.assignedRoutesById[it] } != null
        }
        if (newAssignedJob != null) {
            alertedAssignedJobIds = alertedAssignedJobIds + newAssignedJob.id
            // §4: the FIRST assignment opens the one-shot tutorial sheet
            // (replaces the old snackbar); later assignments stay quiet.
            if (!firstRunStore.tutorialBlockSeen) {
                showFirstBlockSheet = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { locationProvider.stopLocationUpdates() }
    }

    val greeting = remember {
        val now = kotlinx.datetime.Clock.System.now()
        val hour = now.toLocalDateTime(TimeZone.currentSystemDefault()).hour
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }
    val workerName = uiState.worker?.name?.split(" ")?.firstOrNull() ?: "Worker"
    val dashboardAreaName = uiState.serviceAreaName
        ?: uiState.worker?.address?.neighborhood?.takeIf { it.isNotBlank() }
        ?: "Your community"
    val workerZip = uiState.worker?.address?.zip?.takeIf { it.isNotBlank() }

    // Extract route coordinates for the map preview
    val visibleAssignedJobs = remember(uiState.assignedJobs, nowMillis) {
        uiState.assignedJobs.filter { it.isDashboardVisible(nowMillis) }
    }

    val assignedJobRoutePairs = remember(visibleAssignedJobs, uiState.assignedRoutesById) {
        visibleAssignedJobs.mapNotNull { job ->
            val route = job.routeId?.let { uiState.assignedRoutesById[it] } ?: return@mapNotNull null
            job to route
        }
    }
    val primaryAssignedPair = assignedJobRoutePairs.firstOrNull()
    val routeCoordinates: List<List<Coordinates>> = remember(uiState.routes, assignedJobRoutePairs, currentLocation) {
        val assignedRoutes = assignedJobRoutePairs.map { (_, route) -> route.routePathCoordinates() }
        val location = currentLocation
        val guidanceLines = if (location != null) {
            assignedJobRoutePairs.mapNotNull { (_, route) ->
                route.routeStartCoordinates()?.let { start -> listOf(location, start) }
            }
        } else {
            emptyList()
        }
        val availableRoutes = uiState.routes.map { it.routePathCoordinates() }
        guidanceLines + assignedRoutes + availableRoutes
    }
    val blockCoordinates: List<List<Coordinates>> = remember(uiState.blocks) {
        uiState.blocks.mapNotNull { block ->
            block.blockPathCoordinates().takeIf { it.size >= 2 }
        }
    }
    val previewRouteCoordinates = routeCoordinates.ifEmpty { blockCoordinates }

    val mapCenter = remember(uiState.serviceArea, currentLocation, primaryAssignedPair) {
        primaryAssignedPair?.second?.assignedRouteMapCenter(currentLocation)
            ?: resolveMapCenter(
            serviceArea = uiState.serviceArea,
            currentLocation = currentLocation,
            defaultZoom = MapStyles.ZOOM_NEIGHBORHOOD,
        )
    }

    // §4 first-block tutorial sheet (one-shot; FirstRunStore.tutorialBlockSeen)
    val firstBlockSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (showFirstBlockSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showFirstBlockSheet = false
                // Dismissed without the CTA: tutorial considered skipped. The
                // CTA deliberately leaves the flag unset so the RouteDetail
                // coach mark can finish the hand-off (entering the scan sets it).
                firstRunStore.tutorialBlockSeen = true
            },
            sheetState = firstBlockSheetState,
        ) {
            FirstBlockTutorialSheet(
                routeName = primaryAssignedPair?.second?.displayName ?: "Assigned route",
                mapCenter = mapCenter,
                routeCoordinates = primaryAssignedPair
                    ?.let { listOf(it.second.routePathCoordinates()) }
                    .orEmpty(),
                currentLocation = currentLocation,
                onOpenRoute = {
                    showFirstBlockSheet = false
                    primaryAssignedPair?.first?.routeId?.let(onRouteClick)
                },
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onSpotClick,
                containerColor = GccGreen,
            ) {
                QuickActionGlyph(
                    resource = Res.drawable.ic_quick_scan,
                    tint = Color.White,
                )
            }
        },
        bottomBar = {
            GccBottomNavBar(
                selectedTab = 0,
                isAdmin = isAdmin,
                onTabSelected = { tab ->
                    when (tab) {
                        1 -> onMapClick()
                        2 -> if (isAdmin) onAdminClick() else onProfileClick()
                        3 -> if (isAdmin) onProfileClick()
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        // Pre-compute nearby spot counts per route (avoids O(routes*spots) inside items)
        val spotCountByRoute = remember(uiState.routes, uiState.spots) {
            if (uiState.spots.isEmpty()) emptyMap()
            else uiState.routes.associate { route ->
                val routePoints = route.routePathCoordinates()
                route.id to if (routePoints.isEmpty()) 0
                else {
                    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                    var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
                    for (coord in routePoints) {
                        if (coord.lat < minLat) minLat = coord.lat
                        if (coord.lat > maxLat) maxLat = coord.lat
                        if (coord.lng < minLng) minLng = coord.lng
                        if (coord.lng > maxLng) maxLng = coord.lng
                    }
                    val latRange = (minLat - 0.002)..(maxLat + 0.002)
                    val lngRange = (minLng - 0.002)..(maxLng + 0.002)
                    uiState.spots.count { spot ->
                        spot.coordinates.lat in latRange && spot.coordinates.lng in lngRange
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // --- Header with greeting (respects safe area) ---
            item {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "$greeting,",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextMuted,
                        )
                        Text(
                            workerName,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        uiState.serviceAreaName?.let { area ->
                            Text(area, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                    GccLogo(
                        size = 72.dp,
                        modifier = Modifier.clickable(onClick = onProfileClick),
                    )
                }
            }

            // --- Map preview (clean — no overlays, no controls) ---
            item {
                Box(
                    modifier = Modifier
                        .height(233.dp)
                        .padding(horizontal = 13.dp)
                        .clip(RoundedCornerShape(13.dp)),
                ) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = mapCenter.lat,
                        centerLng = mapCenter.lng,
                        zoom = mapCenter.zoom,
                        currentLocation = currentLocation,
                        routeCoordinates = previewRouteCoordinates,
                        spots = uiState.spots,
                        showControls = false,
                        mapMode = MapMode.MY_AREA,
                    )

                    // Transparent overlay to catch taps (map consumes touch for pan/zoom)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onMapClick),
                    )

                    // Expand affordance + my-location button
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentLocation != null) {
                            MyLocationButton(
                                onClick = onMapClick,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Surface(
                            modifier = Modifier.size(28.dp),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.85f),
                            shadowElevation = 2.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "\u2922",
                                    fontSize = 14.sp,
                                    color = GccGreenDark,
                                )
                            }
                        }
                    }
                }
            }

            // Stats moved to Profile screen — more dashboard space for map + routes

            // --- Quick Actions (circle icon grid) ---
            item {
                SectionHeader(
                    title = "Quick Actions",
                    modifier = Modifier.padding(horizontal = 13.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    // Consolidated to five: Impact and the Sponsor Wall now live
                    // inside Community (Feed) as entry cards, and points/awards
                    // are already tabs within Rankings.
                    QuickActionButton(
                        emoji = "\uD83D\uDCF8",
                        label = "Environment\nScan",
                        bgColor = GccBlue,
                        onClick = onSpotClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_scan, GccBlue) },
                    )
                    QuickActionButton(
                        emoji = "\uD83C\uDFC6",
                        label = "Bounties",
                        bgColor = CardAccentSky,
                        onClick = onBountyListClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_bounty, CardAccentSky) },
                    )
                    QuickActionButton(
                        emoji = "\uD83D\uDCB0",
                        label = "Wallet",
                        bgColor = CardAccentBlue,
                        onClick = onWalletClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_wallet, CardAccentBlue) },
                    )
                    QuickActionButton(
                        emoji = "\uD83C\uDFC5",
                        label = "Rankings",
                        bgColor = CardAccentPurple,
                        onClick = onLeaderboardClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_rankings, CardAccentPurple) },
                    )
                    QuickActionButton(
                        emoji = "\uD83D\uDCF0",
                        label = "Community",
                        bgColor = GccGreen,
                        onClick = onFeedClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_community, GccGreen) },
                    )
                }
            }

            item {
                val firstAvailableRoute = uiState.routes.firstOrNull()
                val verificationStatus = uiState.worker?.verification?.status
                RouteQueueStatusPanel(
                    serviceAreaName = dashboardAreaName,
                    zipCode = workerZip,
                    verificationStatus = verificationStatus,
                    availableRouteCount = uiState.routes.size,
                    assignedRouteCount = visibleAssignedJobs.size,
                    reportCount = uiState.spots.size,
                    actionLabel = when {
                        firstAvailableRoute != null -> "View route"
                        verificationStatus == VerificationStatus.APPROVED -> "View area"
                        else -> "Check verification"
                    },
                    onPrimaryClick = when {
                        firstAvailableRoute != null -> ({ onRouteClick(firstAvailableRoute.id) })
                        verificationStatus == VerificationStatus.APPROVED -> onMapClick
                        else -> onProfileClick
                    },
                    modifier = Modifier.padding(horizontal = 13.dp),
                )
            }

            if (visibleAssignedJobs.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Assigned Routes",
                        modifier = Modifier.padding(horizontal = 13.dp),
                    )
                }

                items(visibleAssignedJobs, key = { it.id }) { job ->
                    val route = job.routeId?.let { uiState.assignedRoutesById[it] }
                    if (route != null) {
                        AssignedRouteDashboardCard(
                            job = job,
                            route = route,
                            onClick = { onActiveJobClick(job.id, route.id) },
                            nearbySpotCount = spotCountByRoute[route.id] ?: 0,
                            statusText = if (job.status == JobStatus.IN_PROGRESS) "In Progress" else "Assigned",
                            statusDotColor = if (job.status == JobStatus.IN_PROGRESS) GccGreen else GccClaimed,
                            statusBackgroundColor = if (job.status == JobStatus.IN_PROGRESS) ChipBgAvailable else ChipBgClaimed,
                            assignmentCountdownText = testAssignmentCountdownText(job, nowMillis),
                            onDeclineAssignment = if (job.status == JobStatus.CLAIMED) {
                                { viewModel.declineAssignedJob(job.id) }
                            } else null,
                            isDecliningAssignment = job.id in uiState.decliningAssignedJobIds,
                            modifier = Modifier.padding(horizontal = 13.dp),
                        )
                    } else {
                        AssignedRouteFallbackItem(
                            job = job,
                            onRefresh = { viewModel.refresh() },
                            onDecline = if (job.status == JobStatus.CLAIMED) {
                                { viewModel.declineAssignedJob(job.id) }
                            } else null,
                            isDeclining = job.id in uiState.decliningAssignedJobIds,
                            modifier = Modifier.padding(horizontal = 13.dp),
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                item {
                    SkeletonList(count = 3)
                }
            } else if (uiState.routes.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Available Routes",
                        modifier = Modifier.padding(horizontal = 13.dp),
                    )
                }

                items(uiState.routes, key = { it.id }) { route ->
                    AvailableRouteOpportunityCard(
                        route = route,
                        onClick = { onRouteClick(route.id) },
                        nearbySpotCount = spotCountByRoute[route.id] ?: 0,
                        modifier = Modifier.padding(horizontal = 13.dp),
                    )
                }
            }

            // --- Error banner ---
            uiState.error?.let { error ->
                item {
                    GccCard(
                        accentColor = CardAccentRed,
                        modifier = Modifier.padding(horizontal = 13.dp),
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
        }
    }
}

// --- Quick Action circle button ---

@Composable
private fun QuickActionButton(
    emoji: String,
    label: String,
    bgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(64.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(55.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(bgColor.copy(alpha = 0.12f))
                .background(Color.White.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(55.dp)
                    .clip(CircleShape)
                    .background(bgColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    icon()
                } else {
                    Text(
                        emoji,
                        fontSize = 24.sp,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 12.sp,
        )
    }
}

// --- Route cards ---

@Composable
private fun RouteQueueStatusPanel(
    serviceAreaName: String,
    zipCode: String?,
    verificationStatus: VerificationStatus?,
    availableRouteCount: Int,
    assignedRouteCount: Int,
    reportCount: Int,
    actionLabel: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVerified = verificationStatus == VerificationStatus.APPROVED
    val accentColor = when {
        availableRouteCount > 0 -> GccGreen
        isVerified -> CardAccentSky
        verificationStatus == VerificationStatus.NEEDS_INFO -> GccError
        else -> GccClaimed
    }
    val headline = when {
        availableRouteCount > 0 -> "${availableRouteCount.routeCountLabel()} ready now"
        assignedRouteCount > 0 -> "Assigned route is waiting"
        isVerified -> "You are in the route queue"
        verificationStatus == VerificationStatus.NEEDS_INFO -> "Verification needs an update"
        else -> "Verify to unlock route offers"
    }
    val body = when {
        availableRouteCount > 0 -> "Choose an open block in your neighborhood before someone else claims it."
        assignedRouteCount > 0 -> "Open your assignment when you are ready to review the boundary and start flow."
        isVerified -> "We will send email and push alerts when your place in line opens for $serviceAreaName."
        verificationStatus == VerificationStatus.NEEDS_INFO -> "Update your documents so you can receive neighborhood-only route offers."
        else -> "Proof of address sets your place in line for community route releases."
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, accentColor.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "Route queue",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    headline,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = GccOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            QueueStatusBadge(
                text = verificationStatus.routeQueueStatusLabel(),
                color = accentColor,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QueueSignalCell(
                label = "Neighborhood",
                value = serviceAreaName,
                modifier = Modifier.weight(1f),
            )
            QueueSignalCell(
                label = "Address ZIP",
                value = zipCode ?: "On file",
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QueueSignalCell(
                label = "Open routes",
                value = availableRouteCount.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (availableRouteCount > 0) GccGreen else GccOnSurface,
            )
            QueueSignalCell(
                label = "Nearby reports",
                value = reportCount.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (reportCount > 0) CardAccentBlue else GccOnSurface,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            QueueStepPill(
                text = if (isVerified) "Verified area" else "Verify address",
                color = if (isVerified) GccGreen else GccClaimed,
                modifier = Modifier.weight(1f),
            )
            QueueStepPill(
                text = "Email + push",
                color = CardAccentBlue,
                modifier = Modifier.weight(1f),
            )
            QueueStepPill(
                text = "Daytime window",
                color = CardAccentSky,
                modifier = Modifier.weight(1f),
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPrimaryClick),
            shape = RoundedCornerShape(8.dp),
            color = accentColor.copy(alpha = 0.10f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "\u2192",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = accentColor,
                )
            }
        }
    }
}

@Composable
private fun QueueStatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QueueSignalCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = GccOnSurface,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QueueStepPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.10f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Int.routeCountLabel(): String =
    "$this ${if (this == 1) "route" else "routes"}"

private fun VerificationStatus?.routeQueueStatusLabel(): String =
    when (this) {
        VerificationStatus.APPROVED -> "Verified"
        VerificationStatus.PENDING -> "In review"
        VerificationStatus.REJECTED -> "Rejected"
        VerificationStatus.NEEDS_INFO -> "Needs info"
        null -> "Not started"
    }

@Composable
private fun AssignedRouteDashboardCard(
    job: Job,
    route: Route,
    onClick: () -> Unit,
    nearbySpotCount: Int = 0,
    statusText: String,
    statusDotColor: Color = GccAvailable,
    statusBackgroundColor: Color = ChipBgAvailable,
    assignmentCountdownText: String? = null,
    onDeclineAssignment: (() -> Unit)? = null,
    isDecliningAssignment: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val payout = job.payoutAmount ?: route.payoutAmount

    GccCard(
        modifier = modifier,
        accentColor = if (job.status == JobStatus.IN_PROGRESS) CardAccentGreen else CardAccentSky,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RouteThumbnail(
                    route = route,
                    modifier = Modifier.size(76.dp),
                    decodeWidth = 240,
                    decodeHeight = 240,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        route.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(
                            text = statusText,
                            dotColor = statusDotColor,
                            backgroundColor = statusBackgroundColor,
                        )
                        if (nearbySpotCount > 0) {
                            SpotDensityBadge(spotCount = nearbySpotCount)
                        }
                    }
                    Text(
                        route.description?.takeIf { it.isNotBlank() } ?: "Start from anywhere along the assigned boundary.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RouteFact(
                    label = "Window",
                    value = assignmentCountdownText ?: if (job.status == JobStatus.IN_PROGRESS) "Session active" else "Open now",
                    modifier = Modifier.weight(1f),
                    valueColor = if (job.status == JobStatus.IN_PROGRESS) GccGreen else GccClaimed,
                )
                RouteFact(
                    label = "Route",
                    value = routeDistanceText(route),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RouteFact(
                    label = "Scan area",
                    value = routeDistanceText(route).takeUnless { it == "Boundary" } ?: "Mapped",
                    modifier = Modifier.weight(1f),
                )
                RouteFact(
                    label = "Compensation",
                    value = routePayoutText(payout),
                    modifier = Modifier.weight(1f),
                    valueColor = if ((payout ?: 0) == 0) GccSky else GccGreen,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Open assignment",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = GccGreen,
                )
                onDeclineAssignment?.let { decline ->
                    TextButton(
                        onClick = decline,
                        enabled = !isDecliningAssignment,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            if (isDecliningAssignment) "Declining..." else "Decline",
                            style = MaterialTheme.typography.labelMedium,
                            color = GccError,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailableRouteOpportunityCard(
    route: Route,
    onClick: () -> Unit,
    nearbySpotCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val payoutText = routePayoutText(route.payoutAmount)
    val distanceText = routeDistanceText(route)
    val estimatedMinutes = route.estimatedDurationMinutes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GccGreen.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            GccGreenDark,
                            GccGreen,
                            GccSky,
                        ),
                    ),
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Route opportunity",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    payoutText,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RouteThumbnail(
                    route = route,
                    modifier = Modifier.size(width = 108.dp, height = 92.dp),
                    decodeWidth = 360,
                    decodeHeight = 306,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        route.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    route.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(
                            text = "Open now",
                            dotColor = GccAvailable,
                            backgroundColor = ChipBgAvailable,
                        )
                        if (nearbySpotCount > 0) {
                            SpotDensityBadge(spotCount = nearbySpotCount)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RouteMetricPill(
                    label = "Route",
                    value = distanceText.takeUnless { it == "Boundary" } ?: "Mapped",
                    modifier = Modifier.weight(1f),
                )
                RouteMetricPill(
                    label = "Scan",
                    value = "Area",
                    modifier = Modifier.weight(1f),
                )
                RouteMetricPill(
                    label = "Time",
                    value = estimatedMinutes?.let { "${it}m" } ?: "Ready",
                    modifier = Modifier.weight(1f),
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = GccGreen.copy(alpha = 0.10f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Choose this route",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = GccGreenDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Review boundary, scan sections, and start flow",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "\u2192",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = GccGreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = SurfaceMuted,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = GccOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RouteFact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    val resolvedValueColor = if (valueColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        valueColor
    }

    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = resolvedValueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun routePayoutText(payoutAmount: Int?): String =
    when {
        payoutAmount == null -> "Pending"
        payoutAmount <= 0 -> "Test route"
        else -> "$${payoutAmount / 100}.${(payoutAmount % 100).padTwo()}"
    }

private fun routeDistanceText(route: Route): String =
    route.distanceMeters?.let { meters ->
        if (meters >= 1000.0) {
            val tenths = ((meters / 1609.34) * 10.0).roundToInt()
            "${tenths / 10}.${tenths % 10} mi"
        } else {
            "${meters.roundToInt()} m"
        }
    } ?: "Boundary"

@Composable
private fun AssignedRouteFallbackItem(
    job: Job,
    onRefresh: () -> Unit,
    onDecline: (() -> Unit)?,
    isDeclining: Boolean,
    modifier: Modifier = Modifier,
) {
    GccCard(
        modifier = modifier,
        accentColor = CardAccentSky,
    ) {
        Text(
            "Assigned route",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "The assignment was created, but route details did not finish loading. Pull to refresh or use the refresh button.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Job #${job.id}${job.routeId?.let { " • Route #$it" } ?: ""}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtle,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GccButton(
                text = "Refresh",
                onClick = onRefresh,
                variant = GccButtonVariant.SECONDARY,
                modifier = Modifier.weight(1f),
            )
            if (onDecline != null) {
                GccButton(
                    text = if (isDeclining) "Declining..." else "Decline",
                    onClick = onDecline,
                    variant = GccButtonVariant.OUTLINED,
                    enabled = !isDeclining,
                    isLoading = isDeclining,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun testAssignmentCountdownText(job: Job, nowMillis: Long): String? {
    if (!job.isTestAssignment()) return null
    if (job.status == JobStatus.IN_PROGRESS) return "Test route started"
    if (job.status != JobStatus.CLAIMED) return null
    val deadline = job.testAssignmentStartDeadlineMillis() ?: return null
    val remainingMillis = deadline - nowMillis
    if (remainingMillis <= 0) return null
    val totalSeconds = remainingMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "Start test route in ${minutes}:${seconds.toString().padStart(2, '0')}"
}

private fun Job.isDashboardVisible(nowMillis: Long): Boolean {
    val deadline = testAssignmentStartDeadlineMillis()
    return deadline == null || status == JobStatus.IN_PROGRESS || deadline > nowMillis
}

private fun Route.assignedRouteMapCenter(currentLocation: Coordinates?): MapCenter {
    val routePoints = routePathCoordinates()
    val points = if (currentLocation != null) routePoints + currentLocation else routePoints
    if (points.isEmpty()) {
        return MapCenter(
            lat = MapStyles.NORTH_LAWNDALE_CENTER_LAT,
            lng = MapStyles.NORTH_LAWNDALE_CENTER_LNG,
            zoom = MapStyles.ZOOM_ROUTE,
        )
    }
    return MapCenter(
        lat = points.map { it.lat }.average(),
        lng = points.map { it.lng }.average(),
        zoom = if (currentLocation != null) MapStyles.ZOOM_NEIGHBORHOOD else MapStyles.ZOOM_ROUTE,
    )
}
