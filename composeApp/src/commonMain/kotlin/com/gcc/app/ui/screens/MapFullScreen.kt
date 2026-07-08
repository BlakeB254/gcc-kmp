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
import com.gcc.app.ui.map.RouteOverlay
import com.gcc.app.ui.map.findCommunityAreaForLocation
import com.gcc.app.ui.map.latestIsoTimestamp
import com.gcc.app.ui.map.mergeCoverageCells
import com.gcc.app.ui.map.mergeObservations
import com.gcc.app.ui.map.resolveMapCenter
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.TrashItemCatalog
import com.gcc.app.ui.components.GccBottomNavBar
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.components.PhotoStrip
import com.gcc.app.ui.components.QuickActionGlyph
import com.gcc.app.ui.theme.*
import gcc_kmp.composeapp.generated.resources.*
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
import com.gcc.shared.cache.ScanAreaCacheStore
import com.gcc.shared.offline.SeedData
import kotlin.math.ln
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

// Half-width (degrees) of the coverage/observation fetch box, ~2.2km. Used for
// BOTH map modes so scanned tiles show on My Area from the first frame — see
// the fetch LaunchedEffect for why a tighter My-Area box lost cells.
private const val COVERAGE_FETCH_PAD_DEGREES = 0.02

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
    val gccApiClient = koinInject<com.gcc.shared.api.GccApiClient>()
    val scanAreaCache = koinInject<ScanAreaCacheStore>()
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

    // Selected litter-footprint observation for its detail card (Pillar 8
    // Task 2, Part C) — same pattern as selectedSpot/selectedCoverageCell.
    var selectedObservation by remember { mutableStateOf<com.gcc.shared.model.WorldObservation?>(null) }

    // Camera override for zooming to a tapped spot/bounty/route
    var overrideCenterLat by remember { mutableStateOf<Double?>(null) }
    var overrideCenterLng by remember { mutableStateOf<Double?>(null) }
    var overrideZoom by remember { mutableStateOf<Double?>(null) }
    var selectedRecordIndex by remember { mutableStateOf<Int?>(null) }

    // Precipitation/heat + spot-density data always flows to GccMap now
    // (Pillar 8 Task 2 Part D2 + owner direction 2026-07-07, converged in
    // merge): the top-right "spots visibility" dot toggle was removed along
    // with the spot pins themselves — heat is always on, and its legend
    // visibility follows zoom only (see the AnimatedVisibility below).

    // Needs-scanning lens UI removed (owner direction 2026-07-07); the P5
    // layer stays dormant behind showNeedsScanningLens = false below.

    // Selected route for zoom-to-fit before navigation
    var selectedRouteForZoom by remember { mutableStateOf<com.gcc.shared.model.Route?>(null) }

    // Navigate to route detail after brief zoom animation
    LaunchedEffect(selectedRouteForZoom) {
        val route = selectedRouteForZoom ?: return@LaunchedEffect
        kotlinx.coroutines.delay(800) // Let map animate to show full route
        onRouteClick(route.id)
        selectedRouteForZoom = null
    }

    // Keyed on location AVAILABILITY, not the location value: the camera
    // should center once when the first GPS fix arrives, then leave the user
    // alone — keying on the value recentered on every 1Hz tick, yanking the
    // map back to the dot mid-pan (build 116 field report).
    val hasLocation = currentLocation != null
    val resolved = remember(uiState.serviceArea, hasLocation) {
        resolveMapCenter(
            serviceArea = uiState.serviceArea,
            currentLocation = currentLocation,
        )
    }
    val centerLat = overrideCenterLat ?: resolved.lat
    val centerLng = overrideCenterLng ?: resolved.lng

    // Explorer scan-state coloring: analytics-derived states merged with a
    // client-side derivation from the spots already loaded (analytics tables
    // are empty until the aggregation pipeline runs; the map shouldn't wait).
    var analyticsAreaStates by remember { mutableStateOf(mapOf<String, String>()) }
    val spotDerivedAreaStates = remember(uiState.spots) {
        val active = uiState.spots.filter { it.status != com.gcc.shared.model.CommunityReportStatus.RESOLVED }
        com.gcc.app.ui.map.chicagoCommunityAreas.mapNotNull { area ->
            val inArea = active.count { spot ->
                spot.coordinates.lat in area.bounds.south..area.bounds.north &&
                    spot.coordinates.lng in area.bounds.west..area.bounds.east
            }
            when {
                inArea > 0 -> area.displayName.replace("\n", " ") to "dirty"
                else -> null
            }
        }.toMap()
    }
    val explorerAreaStates = analyticsAreaStates + spotDerivedAreaStates
    LaunchedEffect(mapMode) {
        if (mapMode == MapMode.MY_AREA || analyticsAreaStates.isNotEmpty()) return@LaunchedEffect
        try {
            val overview = gccApiClient.getCityCleanlinessOverview()
            analyticsAreaStates = overview.areas.associate { stats ->
                stats.area to when {
                    stats.activeSpots > 0 -> "dirty"
                    stats.resolvedSpots > 0 -> "clean"
                    else -> "unscanned"
                }
            }
        } catch (_: Exception) {
            // Non-fatal: all areas render as unscanned grey.
        }
    }

    // Foundational coverage-state tiles: ledger cells around the map center.
    // Explorer mode pulls a wider radius (the whole point is surveying scan
    // state), and every successful fetch snapshots into the scan-area cache so
    // the map has a last-known coverage picture offline / at next open.
    var coverageCells by remember { mutableStateOf(listOf<com.gcc.shared.model.MapCoverageCell>()) }
    // Current observations near the viewport — the "what the last scan found"
    // payload behind the coverage-cell info card.
    var mapObservations by remember { mutableStateOf(listOf<com.gcc.shared.model.WorldObservation>()) }
    // Coverage tile the user tapped (fresh/stale) — shows the last-scan card.
    var selectedCoverageCell by remember { mutableStateOf<com.gcc.shared.model.MapCoverageCell?>(null) }
    // Delta-poll cursors (Pillar 2 live map): SERVER clock authority, never
    // the device clock — each is the max timestamp seen in that endpoint's
    // OWN responses and is passed back as `since` on that endpoint's next
    // poll. The two are deliberately separate: coverage advances on
    // lastScannedAt while observations advance on capturedAt, and a shared
    // max would starve whichever endpoint updates less often (its rows would
    // fall behind the artificially-advanced cursor and never return).
    var coverageSinceCursor by remember { mutableStateOf<String?>(null) }
    var observationSinceCursor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(centerLat, centerLng, mapMode) {
        // Coverage/observations always fetch the wider (~2.2km) box in BOTH
        // modes. The map center is a fixed service-area/GPS anchor that does
        // not follow the camera, so a tight My-Area box (~650m) missed cells
        // the user scanned farther out — they only appeared after toggling to
        // Explorer's wider box (which merges + persists). Fetch wide, render
        // clips to the viewport, so scanned data shows on My Area from the
        // first frame.
        val pad = COVERAGE_FETCH_PAD_DEGREES
        val west = centerLng - pad
        val south = centerLat - pad
        val east = centerLng + pad
        val north = centerLat + pad

        // Cache-first: render the last-known snapshot immediately.
        if (coverageCells.isEmpty()) {
            val cachedAreas = withContext(Dispatchers.Default) { scanAreaCache.getAll() }
            val cached = cachedAreas
                .flatMap { it.coverageCells }
                .filter { it.lat in south..north && it.lng in west..east }
                .distinctBy { it.locationCellId }
            if (cached.isNotEmpty()) coverageCells = cached
            if (mapObservations.isEmpty()) {
                mapObservations = cachedAreas
                    .flatMap { it.observations }
                    .filter { it.lat in south..north && it.lng in west..east }
                    .distinctBy { it.id }
            }
        }

        try {
            val bbox = listOf(west, south, east, north).joinToString(",")
            val fetched = gccApiClient.getMapCoverage(bbox).cells
            // Merge (not wholesale-assign) so a slow-resolving initial load
            // can never stomp newer rows the 20s poll already merged in.
            coverageCells = mergeCoverageCells(coverageCells, fetched)
            val observations = try {
                gccApiClient.getMapObservations(bbox)
            } catch (_: Exception) {
                emptyList()
            }
            if (observations.isNotEmpty()) {
                mapObservations = mergeObservations(mapObservations, observations)
            }
            // Seed each poll cursor from ITS OWN endpoint's response so the
            // first 20s poll only asks for what changed since this load.
            coverageSinceCursor = latestIsoTimestamp(
                coverageSinceCursor,
                fetched.map { it.lastScannedAt },
            )
            observationSinceCursor = latestIsoTimestamp(
                observationSinceCursor,
                observations.map { it.capturedAt },
            )
            withContext(Dispatchers.Default) {
                val now = Clock.System.now().toEpochMilliseconds()
                scanAreaCache.put(
                    ScanAreaCacheStore.areaKeyFor(centerLat, centerLng),
                    fetched,
                    observations,
                    now,
                )
                scanAreaCache.prune(now)
            }
        } catch (_: Exception) {
            // Non-fatal: the cached snapshot (if any) stays on screen.
        }
    }

    // Live-map delta polling (Pillar 2): while this screen stays composed,
    // fetch only what changed since the last fetch (server-clock `since`
    // cursor) every 20s and upsert it into the SAME coverageCells/
    // mapObservations state the effect above fills — never replace
    // wholesale, and never fight that initial-load effect (this loop is
    // keyed on Unit so it survives center/mode changes; it just reads the
    // latest viewport via rememberUpdatedState on each tick). Errors are
    // logged and the loop keeps polling — no user-facing noise.
    val latestViewport = rememberUpdatedState(Triple(centerLat, centerLng, mapMode))
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            val (pollLat, pollLng, _) = latestViewport.value
            try {
                val pad = COVERAGE_FETCH_PAD_DEGREES // wide in both modes — see initial-load note
                val bbox = listOf(
                    pollLng - pad,
                    pollLat - pad,
                    pollLng + pad,
                    pollLat + pad,
                ).joinToString(",")

                val coverageDelta = gccApiClient.getMapCoverage(bbox, since = coverageSinceCursor).cells
                if (coverageDelta.isNotEmpty()) {
                    coverageCells = mergeCoverageCells(coverageCells, coverageDelta)
                }
                val observationDelta = try {
                    gccApiClient.getMapObservations(bbox, since = observationSinceCursor)
                } catch (_: Exception) {
                    emptyList()
                }
                if (observationDelta.isNotEmpty()) {
                    mapObservations = mergeObservations(mapObservations, observationDelta)
                }

                // Advance each cursor ONLY from its own endpoint's timestamps.
                coverageSinceCursor = latestIsoTimestamp(
                    coverageSinceCursor,
                    coverageDelta.map { it.lastScannedAt },
                )
                observationSinceCursor = latestIsoTimestamp(
                    observationSinceCursor,
                    observationDelta.map { it.capturedAt },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // leaving composition is not an error — let the loop die
            } catch (e: Exception) {
                DiagnosticsLog.error("live-map-poll", "20s delta poll failed (will retry)", e)
            }
        }
    }

    val mapZoom = overrideZoom ?: resolved.zoom

    // Live camera zoom reported by GccMap — gates the heat legend to the
    // zoom band where the heatmap actually renders (an intensity legend
    // floating over street-zoom pins labels a layer that isn't on screen).
    var currentMapZoom by remember { mutableStateOf(mapZoom) }
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
    // The assigned route's own boundary, kept separate from its GPS guidance
    // line so the boundary can be located by value inside mapRouteCoordinates
    // below (drives the active-route highlight index).
    val assignedRouteBoundaryCoordinates: List<List<Coordinates>> = remember(assignedJobRoutePairs) {
        assignedJobRoutePairs.map { (_, route) -> route.routePathCoordinates() }
    }
    val assignedGuidanceLines: List<List<Coordinates>> = remember(assignedJobRoutePairs, currentLocation) {
        val location = currentLocation
        if (location != null) {
            assignedJobRoutePairs.mapNotNull { (_, route) ->
                route.routePathCoordinates().firstOrNull()?.let { start -> listOf(location, start) }
            }
        } else {
            emptyList()
        }
    }
    val blockRecordCoordinates: List<List<Coordinates>> = remember(displayBlocks) {
        displayBlocks.mapNotNull { block ->
            block.blockRouteCoordinates().takeIf { it.size >= 2 }
        }
    }
    // Authored inner boundaries (route-v2): every ring from every visible
    // route's MultiPolygon (multiple exclusion islands per route supported);
    // centroid pairing in the map builders nests them under the right loop.
    val innerBoundaryRings: List<List<Coordinates>> = remember(uiState.routes, assignedJobRoutePairs, displayBlocks) {
        // Routes AND blocks: with 0 available routes the map shows block
        // loops, whose authored exclusion zones ride the blocks feed — the
        // route list alone left the map falling back to the synthetic 8m
        // inset ('the inner boundary that mirrors the outer').
        ((uiState.routes + assignedJobRoutePairs.map { it.second }).mapNotNull { it.innerBoundary } +
            displayBlocks.mapNotNull { it.innerBoundary })
            .flatMap { multi ->
                multi.coordinates.mapNotNull { poly ->
                    poly.firstOrNull()?.mapNotNull { pt ->
                        val lng = pt.getOrNull(0) ?: return@mapNotNull null
                        val lat = pt.getOrNull(1) ?: return@mapNotNull null
                        Coordinates(lat = lat, lng = lng)
                    }?.takeIf { it.size >= 3 }
                }
            }
            .distinct()
    }

    // Selectable record set: every block/route boundary the < > controls and
    // bottom inventory chips page through. Available (published) routes take
    // priority; block records fill in the rest of the service area.
    val selectableRecordCoordinates: List<List<Coordinates>> =
        availableRouteCoordinates.ifEmpty { blockRecordCoordinates }

    // What actually renders on the map: the UNION of every selectable
    // boundary PLUS the worker's assigned route and its GPS guidance line.
    // Previously this was an exclusive if/else where an active assignment's
    // coordinates won outright over everything else, hiding the rest of the
    // worker's ~10-block service area and the inner exclusion rings that
    // only pair up (by centroid containment) against a boundary that is
    // actually present in this list (Blake: "only see that one block
    // instead of all 10"; "1500 S Millard St block isn't displaying the
    // correct internal exclusion areas").
    val mapRouteCoordinates: List<List<Coordinates>> = remember(
        selectableRecordCoordinates, assignedRouteBoundaryCoordinates, assignedGuidanceLines,
    ) {
        (selectableRecordCoordinates + assignedRouteBoundaryCoordinates + assignedGuidanceLines).distinct()
    }

    // Index of EACH assigned route's boundary within mapRouteCoordinates →
    // its (job, route) pair. Drives both the distinct (thicker/brighter)
    // highlight for active blocks now that they're no longer drawn
    // exclusively, and tap routing: only taps that land on an assigned
    // boundary open the active job — every other block keeps the normal
    // select/zoom behavior.
    val activeRouteIndexToPair: Map<Int, Pair<Job, Route>> = remember(
        mapRouteCoordinates, assignedRouteBoundaryCoordinates, assignedJobRoutePairs,
    ) {
        assignedRouteBoundaryCoordinates.mapIndexedNotNull { i, boundary ->
            val pair = assignedJobRoutePairs.getOrNull(i) ?: return@mapIndexedNotNull null
            mapRouteCoordinates.indexOf(boundary).takeIf { it >= 0 }?.let { it to pair }
        }.toMap()
    }

    val selectedRouteCoordinates = selectedRecordIndex
        ?.let { selectableRecordCoordinates.getOrNull(it) }
        ?.let { listOf(it) }
        ?: emptyList()
    val routeRecordCount = selectableRecordCoordinates.size

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
            coverageCells = coverageCells,
            showNeedsScanningLens = false,
            observationFootprints = mapObservations,
            innerBoundaryRings = innerBoundaryRings,
            explorerAreaStates = explorerAreaStates,
            enableGestures = true,
            showControls = true,
            statusBarPadding = true,
            routeCoordinates = if (mapMode == MapMode.MY_AREA) {
                selectedRouteCoordinates.ifEmpty { mapRouteCoordinates }
            } else emptyList(),
            // Only meaningful against the full union list — a manually
            // selected single record has nothing to highlight against.
            activeRouteIndices = if (mapMode == MapMode.MY_AREA && selectedRouteCoordinates.isEmpty()) {
                activeRouteIndexToPair.keys.toList()
            } else emptyList(),
            imageStars = if (mapMode == MapMode.MY_AREA) imageStars else emptyList(),
            onAreaTap = { /* tooltip handled internally by GccMap */ },
            onRouteTap = if (mapMode == MapMode.MY_AREA) { routeIndex ->
                // Tap indices refer to whatever list GccMap is rendering: the
                // union (mapRouteCoordinates) normally, or the single selected
                // record when one is zoomed. Only a tap that actually lands on
                // an assigned route's boundary opens the active job; all other
                // blocks get the normal select/zoom behavior.
                val assigned = if (selectedRecordIndex == null) activeRouteIndexToPair[routeIndex] else null
                if (assigned != null) {
                    onActiveJobClick(assigned.first.id, assigned.second.id)
                } else {
                    val actualIndex = selectedRecordIndex ?: routeIndex
                    if (actualIndex in 0 until routeRecordCount) selectVisualRoute(actualIndex)
                }
            } else null,
            onSpotTap = { spotId ->
                val spot = (uiState.spots).find { it.id == spotId }
                if (spot != null) {
                    selectedSpot = spot
                    selectedObservation = null
                    selectedRecordIndex = null
                    overrideCenterLat = spot.coordinates.lat
                    overrideCenterLng = spot.coordinates.lng
                    overrideZoom = MapStyles.ZOOM_ROUTE
                }
            },
            onBountyTap = { bountyId ->
                onBountyClick(bountyId)
            },
            onObservationTap = { obsId ->
                selectedObservation = mapObservations.find { it.id == obsId }
                if (selectedObservation != null) {
                    selectedSpot = null
                    selectedCoverageCell = null
                }
            },
            cameraBounds = null,
            bounties = visibleBounties,
            spots = visibleSpots,
            mapMode = mapMode,
            onZoomChanged = { currentMapZoom = it },
            onCoverageCellTap = { cellId ->
                selectedCoverageCell = coverageCells.find { it.locationCellId == cellId }
                if (selectedCoverageCell != null) {
                    selectedSpot = null
                    selectedObservation = null
                }
            },
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
                        selectedObservation = null
                        selectedRecordIndex = null
                    },
                )

                // Owner direction 2026-07-07: no extra chrome beside the mode
                // toggle — the needs-scanning lens chip (crashed in the field)
                // and the heatmap dot toggle are both removed; the heatmap is
                // always on and speaks through its own zoom gating. (Pillar 8
                // Task 2 Part D2 removed the same dot toggle independently —
                // spot pins are gone, so it had nothing left to control.)
            }

            // Scan-state legend — labels the ONE health colour system used at
            // every zoom: the far-zoom cell-health choropleth (aggregated
            // tiles) and the close-zoom per-cell coverage tiles are the same
            // green/amber/red/grey vocabulary, so a single legend describes
            // both. It used to swap to the retired "precipitation intensity"
            // (Extreme/Heavy/Moderate/Light) legend below the footprint
            // fade-in zoom, which mislabeled the choropleth's health colours
            // as density — removed 2026-07-08 so the legend on screen always
            // matches the colours on screen. Hidden only while a coverage
            // cell is selected (its own info card explains that tile).
            androidx.compose.animation.AnimatedVisibility(
                visible = selectedCoverageCell == null,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                // Bottom-right, above the map controls — off the map's prime
                // real estate (owner direction 2026-07-07: not mid-screen).
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 112.dp),
            ) {
                ScanCoverageLegend()
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

            // Single scan button for the map tab, regardless of mode: this used
            // to sit alongside a second "Explorer Scan" pill that only showed
            // in EXPLORER mode — same destination, same callback, redundant
            // side-by-side buttons (Blake). SpotCreateButton below already
            // launches the env scan in both MY_AREA and EXPLORER, so the
            // EXPLORER-only pill was pure duplication; removed.

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

            // Coverage-cell info card — tap a scanned (green/amber) tile to
            // see when it was last scanned and what that scan found nearby.
            selectedCoverageCell?.let { cell ->
                CoverageCellInfoCard(
                    cell = cell,
                    observations = mapObservations,
                    onDismiss = { selectedCoverageCell = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                )
            }

            // Litter-footprint info card (Pillar 8 Task 2, Part C) — tap a
            // close-zoom footprint shape to see its photo/type/weight.
            selectedObservation?.let { observation ->
                ObservationInfoCard(
                    observation = observation,
                    onDismiss = { selectedObservation = null },
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
        QuickActionGlyph(
            resource = Res.drawable.ic_quick_scan,
            tint = Color.White,
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

/**
 * Legend for the foundational coverage tiles: the green/amber fills and grey
 * fog communicate scan state, and the grey rows is the call to action —
 * "this ground still needs scanning".
 */
@Composable
private fun ScanCoverageLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Map colours",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            ScanLegendRow(Color(0xFF22C55E), "Scanned · clean")
            ScanLegendRow(Color(0xFF22C55E), "Light green · needs detail scan", swatchAlpha = 0.35f)
            ScanLegendRow(Color(0xFFEF4444), "Litter / reported mess")
            ScanLegendRow(Color(0xFFF59E0B), "Stale · re-scan")
            ScanLegendRow(Color(0xFF4B5563), "Never scanned")
            Text(
                "Tap a tile for last results",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun ScanLegendRow(color: Color, label: String, swatchAlpha: Float = 0.7f) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = swatchAlpha)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** "N min/h/d ago" from an ISO timestamp; the raw string when unparseable. */
private fun lastScannedLabel(iso: String): String {
    val instant = runCatching { kotlinx.datetime.Instant.parse(iso) }.getOrNull()
        ?: return iso
    val minutes = (Clock.System.now() - instant).inWholeMinutes
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

/**
 * Bottom info card for a tapped coverage tile: when the cell was last
 * scanned plus what current observations sit within it (~10m server cell) —
 * the map's answer to "what did the last scan of this spot find".
 */
@Composable
private fun CoverageCellInfoCard(
    cell: com.gcc.shared.model.MapCoverageCell,
    observations: List<com.gcc.shared.model.WorldObservation>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lngScale = kotlin.math.cos(cell.lat * kotlin.math.PI / 180.0)
    val nearby = remember(cell.locationCellId, observations) {
        observations.filter { obs ->
            val dLatM = (obs.lat - cell.lat) * 111_320.0
            val dLngM = (obs.lng - cell.lng) * 111_320.0 * lngScale
            dLatM * dLatM + dLngM * dLngM < 8.0 * 8.0
        }
    }
    val dirty = nearby.filter { it.state == "dirty" && (it.domain == null || it.domain == "litter") }
    val stale = cell.state == "stale"

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
                    when {
                        stale -> "Scanned — needs re-scan"
                        dirty.isNotEmpty() -> "Scanned — litter on record"
                        else -> "Scanned — clean"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = when {
                        stale -> Color(0xFFB45309)
                        dirty.isNotEmpty() -> Color(0xFFDC2626)
                        else -> GccGreenDark
                    },
                )
                TextButton(onClick = onDismiss) { Text("✕", color = TextMuted) }
            }
            Text(
                "Last scanned ${lastScannedLabel(cell.lastScannedAt)}" +
                    if (stale) " — over 24h old, scan it again to keep it fresh" else "",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (dirty.isEmpty()) {
                Text(
                    "Last scan found no litter here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GccGreen,
                )
            } else {
                Text(
                    "Litter still on record here:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(modifier = Modifier.height(4.dp))
                dirty.take(4).forEach { obs ->
                    Text(
                        "• ${(obs.itemType ?: "litter").replace('_', ' ')}" +
                            (obs.severity?.let { " · severity $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                if (dirty.size > 4) {
                    Text(
                        "…and ${dirty.size - 4} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                }
            }
        }
    }
}

/**
 * Litter-footprint detail card (Pillar 8 Task 2, Part C) — shown when a
 * close-zoom observation-footprint shape (GccMap's observation-footprint-
 * fill-* layers) is tapped. Modeled on [SpotInfoCard]/[CoverageCellInfoCard]
 * for visual consistency (same card chrome/dismiss pattern), but a new
 * composable rather than force-fitting a [WorldObservation] into
 * [CommunityReport]'s shape — they aren't the same thing.
 *
 * Shows: photo (mediaUrl, via [PhotoStrip] — the same async-image component
 * SpotInfoCard uses), item type (human label via
 * [TrashItemCatalog.infoFor]), and estimated weight when present. There is
 * no "materials" field anywhere in the schema/attributes today (confirmed
 * by a repo-wide search) — that display is deferred pending a real data
 * source, not a task-2 gap.
 */
@Composable
private fun ObservationInfoCard(
    observation: com.gcc.shared.model.WorldObservation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemLabel = observation.itemType?.let { TrashItemCatalog.infoFor(it).displayName }
        ?: "Litter"

    // Tap-to-enlarge: opening the full-screen gallery (shared with SpotInfoCard)
    // so the footprint card's photo behaves like the spot card's — tap to view
    // large, swipe / arrows to page when there's more than one image.
    var fullScreenImageIndex by remember { mutableStateOf<Int?>(null) }
    val photoUrls = listOfNotNull(observation.mediaUrl)

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
                    itemLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // State chip (Dirty / Clean) — quick colour cue in the header.
                val dirty = observation.state.equals("dirty", ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (dirty) Color(0xFFEF4444) else GccGreen).copy(alpha = 0.15f),
                ) {
                    Text(
                        if (dirty) "Litter" else "Clean",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) Color(0xFFEF4444) else GccGreen,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(24.dp),
                ) {
                    Text("✕", color = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (photoUrls.isNotEmpty()) {
                PhotoStrip(
                    urls = photoUrls,
                    photoSize = 120,
                    onPhotoClick = { index -> fullScreenImageIndex = index },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Tap photo to enlarge",
                    style = MaterialTheme.typography.bodySmall,
                    color = GccGreen,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Structured detail rows — only the fields that are actually
            // present render, so the card stays compact for a bare detection
            // and grows richer for a fully-diagnosed one.
            ObservationDetailRow("Category", observation.domain?.let { humanizeObservationField(it) })
            ObservationDetailRow("Severity", observation.severity?.let { severityDisplayLabel(it) })
            ObservationDetailRow(
                "Est. weight",
                observation.estimatedWeightGrams?.let { grams ->
                    // String.format is JVM-only — round to one decimal by hand.
                    if (grams >= 1000) "~${kotlin.math.round(grams / 100.0) / 10.0} kg" else "~$grams g"
                },
            )
            ObservationDetailRow(
                "Size",
                observation.widthMeters?.takeIf { it.isFinite() && it > 0.0 }?.let {
                    "~${kotlin.math.round(it * 10.0) / 10.0} m wide"
                },
            )
            ObservationDetailRow("Source", observationSourceLabel(observation.sourceType))
            ObservationDetailRow(
                "Confirmation",
                when (observation.diagnosticStatus) {
                    "observed" -> "Observed from afar"
                    "diagnosed" -> "Diagnosed (close capture)"
                    else -> null
                },
            )
            ObservationDetailRow("Reported", formatIsoTimestamp(observation.capturedAt))
        }
    }

    fullScreenImageIndex?.let { startIndex ->
        FullScreenImageGallery(
            urls = photoUrls,
            startIndex = startIndex,
            onDismiss = { fullScreenImageIndex = null },
        )
    }
}

/** One "Label   value" row for the observation detail card; renders nothing
 *  when [value] is null/blank so absent fields don't leave empty rows. */
@Composable
private fun ObservationDetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSubtle)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextMuted,
        )
    }
}

/** Title-cases a snake_case field like "land_use" -> "Land use". */
private fun humanizeObservationField(raw: String): String =
    raw.split('_').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

private fun observationSourceLabel(sourceType: String): String = when (sourceType) {
    "route_scan" -> "Route scan"
    "spot_report" -> "Community report"
    "ar_auto_detect" -> "AR detection"
    else -> humanizeObservationField(sourceType)
}

/** Numeric 1-5 severity -> a word; passes small numbers through as a label. */
private fun severityDisplayLabel(severity: Int): String = when {
    severity >= 4 -> "High"
    severity >= 2 -> "Medium"
    else -> "Low"
}

/**
 * Formats an ISO-8601 timestamp string (e.g. "2026-07-08T14:47:00Z") into a
 * short local "Jul 8, 2026 · 2:47 PM". Returns null on a null/unparseable
 * input so the caller's row simply doesn't render. commonMain-safe (no
 * java.text) — kotlinx.datetime + a hand-rolled month table.
 */
private fun formatIsoTimestamp(iso: String?): String? {
    val raw = iso?.takeIf { it.isNotBlank() } ?: return null
    return try {
        val dt = Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault())
        val months = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        val month = months.getOrElse(dt.monthNumber - 1) { dt.monthNumber.toString() }
        val hour12 = when {
            dt.hour == 0 -> 12
            dt.hour > 12 -> dt.hour - 12
            else -> dt.hour
        }
        val ampm = if (dt.hour < 12) "AM" else "PM"
        val minute = dt.minute.toString().padStart(2, '0')
        "$month ${dt.dayOfMonth}, ${dt.year} · $hour12:$minute $ampm"
    } catch (_: Exception) {
        null
    }
}
