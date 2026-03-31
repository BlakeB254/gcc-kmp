package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CameraPreview
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.pickFromLibrary
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteCheckpoint
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRouteDetailScreen(
    routeId: Int,
    onBack: () -> Unit,
    onTestRoute: (Int) -> Unit = {},
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val route = uiState.routes.find { it.id == routeId }
    val scope = rememberCoroutineScope()
    val cameraManager = remember { CameraManager() }
    val locationProvider = koinInject<LocationProvider>()
    val currentLocation by locationProvider.currentLocation.collectAsState()

    var isUploading by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showAddCheckpoint by remember { mutableStateOf(false) }
    var uploadSuccess by remember { mutableStateOf(false) }
    var checkpointList by remember(route) { mutableStateOf(route?.checkpoints ?: emptyList()) }
    var editDisplayName by remember(route) { mutableStateOf(route?.displayName ?: "") }
    var editDescription by remember(route) { mutableStateOf(route?.description ?: "") }
    var nameDescDirty by remember { mutableStateOf(false) }
    var savingNameDesc by remember { mutableStateOf(false) }
    // Track imageUrl to detect when upload completes
    var previousImageUrl by remember { mutableStateOf(route?.imageUrl) }

    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose { locationProvider.stopLocationUpdates() }
    }

    if (route == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Route not found")
        }
        return
    }

    // Detect when imageUrl changes (upload succeeded)
    LaunchedEffect(route.imageUrl) {
        if (route.imageUrl != previousImageUrl && isUploading) {
            isUploading = false
            uploadSuccess = true
        }
        previousImageUrl = route.imageUrl
    }

    // Detect upload error
    LaunchedEffect(uiState.error) {
        if (uiState.error != null && isUploading) {
            isUploading = false
        }
    }

    // Auto-dismiss success message after 3 seconds
    LaunchedEffect(uploadSuccess) {
        if (uploadSuccess) {
            kotlinx.coroutines.delay(3000)
            uploadSuccess = false
        }
    }

    fun uploadAndSetCover(photoBytes: ByteArray) {
        isUploading = true
        uploadSuccess = false
        viewModel.clearError()
        viewModel.updateRouteImage(routeId, photoBytes)
    }

    // Camera overlay
    if (showCamera) {
        CameraOverlay(
            onDismiss = { showCamera = false },
            onCapture = { photoBytes ->
                showCamera = false
                uploadAndSetCover(photoBytes)
            },
        )
        return
    }

    // Add checkpoint dialog
    if (showAddCheckpoint) {
        AdminAddCheckpointDialog(
            routeId = routeId,
            nextSequenceOrder = checkpointList.size + 1,
            currentLat = currentLocation?.lat ?: 0.0,
            currentLng = currentLocation?.lng ?: 0.0,
            onDismiss = { showAddCheckpoint = false },
            onSave = { request ->
                viewModel.addCheckpoint(routeId, request) { newCheckpoint ->
                    checkpointList = checkpointList + newCheckpoint
                }
                showAddCheckpoint = false
            },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(route.displayName) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190 Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGoldDark,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Error banner
            if (uiState.error != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = GccError.copy(alpha = 0.1f),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiState.error ?: "", style = MaterialTheme.typography.bodySmall, color = GccError, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            // Success banner
            if (uploadSuccess) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = GccGreen.copy(alpha = 0.1f),
                    ) {
                        Text(
                            "Cover image updated successfully!",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccGreen,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            // Route image
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isUploading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GccGreen, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Uploading...", style = MaterialTheme.typography.bodySmall, color = TextSubtle)
                        }
                    } else if (route.imageUrl != null) {
                        NetworkImage(
                            url = route.imageUrl!!,
                            contentDescription = route.displayName,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("\uD83D\uDDFA\uFE0F", style = MaterialTheme.typography.displaySmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("No display photo", style = MaterialTheme.typography.bodySmall, color = TextSubtle)
                        }
                    }
                }
            }

            // Photo buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showCamera = true },
                        modifier = Modifier.weight(1f),
                        enabled = !isUploading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                    ) {
                        Text(if (isUploading) "Uploading..." else "\uD83D\uDCF7 Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val photo = pickFromLibrary()
                                if (photo != null) uploadAndSetCover(photo.bytes)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isUploading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                    ) {
                        Text("\uD83D\uDDBC Library")
                    }
                }
            }

            // Test Route button
            item {
                OutlinedButton(
                    onClick = { onTestRoute(routeId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GccGold,
                    ),
                    border = BorderStroke(1.dp, GccGold),
                ) {
                    Text("\uD83E\uDDEA Test Route")
                }
            }

            // ── Assign Route to Worker ──────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "\uD83D\uDC77 Assign Route",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Worker picker
                val workers = uiState.workers
                var selectedWorkerId by remember { mutableStateOf<Int?>(null) }
                var isTestAssignment by remember { mutableStateOf(false) }
                var isAssigning by remember { mutableStateOf(false) }
                var assignSuccess by remember { mutableStateOf<String?>(null) }

                if (workers.isEmpty()) {
                    Text(
                        "Loading workers...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                } else {
                    // Worker dropdown
                    var expanded by remember { mutableStateOf(false) }
                    val selectedWorker = workers.find { it.id == selectedWorkerId }

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedWorker?.name ?: "Select a worker",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GccGold,
                            ),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            workers.forEach { worker ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                worker.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                worker.email,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSubtle,
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedWorkerId = worker.id
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Real vs Test toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                if (isTestAssignment) "\uD83E\uDDEA Test Assignment" else "\uD83D\uDCB5 Real Assignment",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isTestAssignment) GccGold else GccGreen,
                            )
                            Text(
                                if (isTestAssignment) "No payment, no DB records — for TestFlight testing"
                                else "Paid route — payout on completion + verification",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtle,
                            )
                        }
                        Switch(
                            checked = isTestAssignment,
                            onCheckedChange = { isTestAssignment = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GccGold,
                                checkedTrackColor = GccGold.copy(alpha = 0.3f),
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Assign button
                    Button(
                        onClick = {
                            val wid = selectedWorkerId ?: return@Button
                            isAssigning = true
                            assignSuccess = null
                            viewModel.assignRouteToWorker(routeId, wid, isTestAssignment) { success ->
                                isAssigning = false
                                assignSuccess = if (success) {
                                    val workerName = workers.find { it.id == wid }?.name ?: "Worker"
                                    val mode = if (isTestAssignment) "test" else "real"
                                    "$workerName assigned ($mode)"
                                } else "Assignment failed"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedWorkerId != null && !isAssigning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTestAssignment) GccGold else GccGreen,
                        ),
                    ) {
                        if (isAssigning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                "Assign to ${selectedWorker?.name?.split(" ")?.firstOrNull() ?: "Worker"}",
                                color = Color.White,
                            )
                        }
                    }

                    // Success/error feedback
                    assignSuccess?.let { msg ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.contains("failed")) GccError else GccGreen,
                        )
                    }
                }
            }

            // Remove image button (only shown when an image exists)
            if (route.imageUrl != null) {
                item {
                    OutlinedButton(
                        onClick = {
                            viewModel.removeRouteImage(routeId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isUploading,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("\uD83D\uDDD1 Remove Cover Image")
                    }
                }
            }

            // Image location — slide star along the route line
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "\u2B50 Photo Location",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (route.imageLocationLat != null) {
                        TextButton(
                            onClick = { viewModel.updateRouteImageLocation(routeId, null, null) },
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Build route polyline points
                val routePoints = remember(route) {
                    route.geometry?.coordinates?.map { pt ->
                        Coordinates(lng = pt[0], lat = pt[1])
                    } ?: route.checkpoints.sortedBy { it.sequenceOrder }.map { it.coordinates }
                }

                if (routePoints.size >= 2) {
                    // Compute cumulative distances along the polyline
                    val segmentDistances = remember(routePoints) {
                        routePoints.zipWithNext().map { (a, b) ->
                            kotlin.math.sqrt(
                                (b.lat - a.lat) * (b.lat - a.lat) + (b.lng - a.lng) * (b.lng - a.lng)
                            )
                        }
                    }
                    val totalDist = remember(segmentDistances) { segmentDistances.sum() }

                    // Initialize slider position from saved location (or default to midpoint)
                    val initialFraction = remember(route.imageLocationLat, route.imageLocationLng, routePoints, totalDist) {
                        val lat = route.imageLocationLat
                        val lng = route.imageLocationLng
                        if (lat != null && lng != null && totalDist > 0) {
                            // Find closest point on polyline
                            var bestFraction = 0.5f
                            var bestDistSq = Double.MAX_VALUE
                            var cumDist = 0.0
                            for (i in routePoints.indices) {
                                val dSq = (routePoints[i].lat - lat) * (routePoints[i].lat - lat) +
                                        (routePoints[i].lng - lng) * (routePoints[i].lng - lng)
                                if (dSq < bestDistSq) {
                                    bestDistSq = dSq
                                    bestFraction = (cumDist / totalDist).toFloat()
                                }
                                if (i < segmentDistances.size) cumDist += segmentDistances[i]
                            }
                            bestFraction.coerceIn(0f, 1f)
                        } else 0.5f
                    }
                    var sliderFraction by remember(route.id) { mutableStateOf(initialFraction) }

                    // Interpolate position along polyline at the given fraction
                    fun interpolateAlongPath(fraction: Float): Coordinates {
                        if (routePoints.size < 2) return routePoints.first()
                        val targetDist = fraction * totalDist
                        var cumDist = 0.0
                        for (i in segmentDistances.indices) {
                            val nextCum = cumDist + segmentDistances[i]
                            if (nextCum >= targetDist || i == segmentDistances.size - 1) {
                                val segFraction = if (segmentDistances[i] > 0)
                                    ((targetDist - cumDist) / segmentDistances[i]).coerceIn(0.0, 1.0)
                                else 0.0
                                val a = routePoints[i]
                                val b = routePoints[i + 1]
                                return Coordinates(
                                    lng = a.lng + (b.lng - a.lng) * segFraction,
                                    lat = a.lat + (b.lat - a.lat) * segFraction,
                                )
                            }
                            cumDist = nextCum
                        }
                        return routePoints.last()
                    }

                    val starCoord = interpolateAlongPath(sliderFraction)

                    // Map center + zoom to fit entire route
                    val mapCenter = remember(routePoints) {
                        Coordinates(
                            lng = routePoints.sumOf { it.lng } / routePoints.size,
                            lat = routePoints.sumOf { it.lat } / routePoints.size,
                        )
                    }
                    // Zoom out enough to show the full route with generous padding
                    val fitZoom = remember(routePoints) {
                        val latSpan = routePoints.maxOf { it.lat } - routePoints.minOf { it.lat }
                        val lngSpan = routePoints.maxOf { it.lng } - routePoints.minOf { it.lng }
                        // Add 60% padding so endpoints aren't at the edge
                        val span = maxOf(latSpan * 1.6, lngSpan * 1.8)
                        when {
                            span < 0.002 -> 16.0
                            span < 0.004 -> 15.5
                            span < 0.006 -> 15.0
                            span < 0.009 -> 14.5
                            span < 0.015 -> 14.0
                            span < 0.025 -> 13.5
                            else         -> 13.0
                        }
                    }

                    Text(
                        "Slide to position the star along the route.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Mini map — taller to give route room to breathe
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        GccMap(
                            modifier = Modifier.fillMaxSize(),
                            centerLat = mapCenter.lat,
                            centerLng = mapCenter.lng,
                            zoom = fitZoom,
                            enableGestures = true,
                            routeCoordinates = listOf(routePoints),
                            imageStars = listOf(starCoord),
                            showBuildings = false,
                            showGridLines = false,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Slider to move star along route
                    Slider(
                        value = sliderFraction,
                        onValueChange = { sliderFraction = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFB300),
                            activeTrackColor = Color(0xFFFFB300),
                            inactiveTrackColor = Color(0xFFFFB300).copy(alpha = 0.3f),
                        ),
                    )

                    // Save button
                    val savedLat = route.imageLocationLat
                    val savedLng = route.imageLocationLng
                    val isDirty = savedLat == null || savedLng == null ||
                            kotlin.math.abs(starCoord.lat - savedLat) > 0.000001 ||
                            kotlin.math.abs(starCoord.lng - savedLng) > 0.000001
                    Button(
                        onClick = {
                            viewModel.updateRouteImageLocation(routeId, starCoord.lat, starCoord.lng)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                        enabled = isDirty,
                    ) {
                        Text(
                            if (isDirty) "\u2B50 Save Photo Location" else "\u2B50 Saved",
                            color = Color.White,
                        )
                    }
                } else {
                    Text(
                        "Route needs at least 2 points to place a photo location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Editable name + description
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Display Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = editDisplayName,
                    onValueChange = { editDisplayName = it; nameDescDirty = true },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("e.g. S Pulaski Block 1") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Street Description", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = editDescription,
                    onValueChange = { editDescription = it; nameDescDirty = true },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("e.g. S Pulaski Rd from W Roosevelt Rd to W 16th St") },
                )
                if (nameDescDirty) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            savingNameDesc = true
                            nameDescDirty = false
                            viewModel.updateRouteNameDesc(
                                routeId,
                                editDisplayName.trim(),
                                editDescription.trim().ifEmpty { null },
                            )
                            savingNameDesc = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        enabled = !savingNameDesc && editDisplayName.isNotBlank(),
                    ) {
                        Text(if (savingNameDesc) "Saving..." else "Save Name & Description")
                    }
                }
            }

            // Route info
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(route.code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Payout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            route.payoutAmount?.let { cents -> "$${cents / 100}" + if (cents % 100 != 0) ".${(cents % 100).toString().padStart(2, '0')}" else "" } ?: "N/A",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GccGreen,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Distance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val distMi = route.distanceMeters?.let { m -> "${((m / 1609.34 * 10).toInt() / 10.0)} mi" } ?: "N/A"
                        Text(distMi, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Checkpoints", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${checkpointList.size}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Checkpoints section
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Checkpoints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showAddCheckpoint = true }) {
                        Text("+ Add", color = GccGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            items(checkpointList.sortedBy { it.sequenceOrder }, key = { it.id ?: it.sequenceOrder }) { cp ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(GccGreen),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${cp.sequenceOrder}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cp.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                "${cp.type.name.lowercase().replace('_', ' ')}${if (cp.photoRequired) " \u2022 photo" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        cp.id?.let { cpId ->
                            TextButton(
                                onClick = {
                                    viewModel.deleteCheckpoint(routeId, cpId) {
                                        checkpointList = checkpointList.filter { it.id != cpId }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text("\u2715", color = GccError, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraOverlay(
    onDismiss: () -> Unit,
    onCapture: (ByteArray) -> Unit,
) {
    val cameraManager = remember { CameraManager() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(modifier = Modifier.fillMaxSize())

        // Close button
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Text("Cancel", color = Color.White)
        }

        // Capture button
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 32.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val photo = cameraManager.capturePhoto()
                        if (photo != null) onCapture(photo.bytes)
                    }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
            ) {
                Text("\uD83D\uDCF8", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
