package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.CameraCapture
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.GccCard
import com.gcc.app.ui.components.GccTextField
import com.gcc.app.ui.components.SectionHeader
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.app.viewmodel.AreaMode
import com.gcc.shared.model.AffectedArea
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.CommunityReportType
import com.gcc.shared.model.CreateSpotRequest
import com.gcc.shared.model.SpotTag
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.cos
import kotlin.math.pow

private enum class SpotCreateStep { CAMERA, MAP, DETAILS }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AdminSpotCreateScreen(
    initialLat: Double,
    initialLng: Double,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val locationProvider = koinInject<LocationProvider>()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    val focusManager = LocalFocusManager.current
    val gpsLat = currentLocation?.lat ?: initialLat
    val gpsLng = currentLocation?.lng ?: initialLng

    var step by remember { mutableStateOf(SpotCreateStep.CAMERA) }
    var capturedPhotos by remember { mutableStateOf<List<CapturedPhoto>>(emptyList()) }
    var radiusMeters by remember { mutableStateOf(15f) }
    var areaMode by remember { mutableStateOf(AreaMode.CIRCLE) }
    var rectWidth by remember { mutableStateOf(20f) }
    var rectHeight by remember { mutableStateOf(10f) }
    var isSighting by remember { mutableStateOf(true) }
    var description by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf<List<SpotTag>>(emptyList()) }
    var selectedDiscoveredByWorkerId by remember { mutableStateOf<Int?>(null) }
    var isDiscoveredBySheetOpen by remember { mutableStateOf(false) }
    var workerSearchQuery by remember { mutableStateOf("") }
    var showWorkersNotLoadedMessage by remember { mutableStateOf(false) }
    val defaultLat = if (initialLat == 0.0 && initialLng == 0.0) MapStyles.NORTH_LAWNDALE_CENTER_LAT else initialLat
    val defaultLng = if (initialLat == 0.0 && initialLng == 0.0) MapStyles.NORTH_LAWNDALE_CENTER_LNG else initialLng
    var spotLat by remember { mutableStateOf(defaultLat) }
    var spotLng by remember { mutableStateOf(defaultLng) }
    var hasLockedToGps by remember { mutableStateOf(false) }
    val mapZoom = MapStyles.ZOOM_ACTIVE_JOB
    // Physical-pixel draw/drag conversions must bias the point-space camera
    // zoom (see mapOverlayZoomBias) or the ring draws too small vs the basemap.
    val overlayZoom = mapZoom +
        com.gcc.app.ui.map.mapOverlayZoomBias(androidx.compose.ui.platform.LocalDensity.current.density)
    val initialSuccessNonce = remember { uiState.spotCreateSuccessNonce }
    val selectedDiscoveredByWorker = uiState.workers.find { it.id == selectedDiscoveredByWorkerId }
    val discoveredBySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val filteredWorkers = remember(uiState.workers, workerSearchQuery) {
        val query = workerSearchQuery.trim().lowercase()
        if (query.isBlank()) {
            uiState.workers
        } else {
            uiState.workers.filter { worker ->
                worker.name.lowercase().contains(query) || worker.email.lowercase().contains(query)
            }
        }
    }

    LaunchedEffect(uiState.spotCreateSuccessNonce) {
        if (uiState.spotCreateSuccessNonce > initialSuccessNonce) {
            onBack()
        }
    }

    LaunchedEffect(currentLocation) {
        if (!hasLockedToGps && currentLocation != null) {
            spotLat = currentLocation!!.lat
            spotLng = currentLocation!!.lng
            hasLockedToGps = true
        }
    }

    LaunchedEffect(uiState.workers.isEmpty()) {
        if (uiState.workers.isEmpty()) {
            showWorkersNotLoadedMessage = false
            delay(700)
            showWorkersNotLoadedMessage = true
        } else {
            showWorkersNotLoadedMessage = false
        }
    }

    DisposableEffect(Unit) {
        viewModel.clearSpotCreateError()
        locationProvider.startLocationUpdates()
        onDispose { locationProvider.stopLocationUpdates() }
    }

    if (isDiscoveredBySheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isDiscoveredBySheetOpen = false },
            sheetState = discoveredBySheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    "Discovered by",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(12.dp))
                GccTextField(
                    value = workerSearchQuery,
                    onValueChange = { workerSearchQuery = it },
                    label = "Search workers",
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AttributionPickerRow(
                        title = "GCC (admin)",
                        subtitle = "Attribute this spot to the admin account",
                        selected = selectedDiscoveredByWorkerId == null,
                        onClick = {
                            selectedDiscoveredByWorkerId = null
                            isDiscoveredBySheetOpen = false
                            workerSearchQuery = ""
                        },
                    )

                    if (uiState.workers.isEmpty()) {
                        Text(
                            if (showWorkersNotLoadedMessage) {
                                "No workers loaded - open Workers tab once"
                            } else {
                                "Loading workers..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else if (filteredWorkers.isEmpty()) {
                        Text(
                            "No workers match \"$workerSearchQuery\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        filteredWorkers.forEach { worker ->
                            AttributionPickerRow(
                                title = worker.name,
                                subtitle = worker.email,
                                selected = worker.id == selectedDiscoveredByWorkerId,
                                onClick = {
                                    selectedDiscoveredByWorkerId = worker.id
                                    isDiscoveredBySheetOpen = false
                                    workerSearchQuery = ""
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        when (step) {
                            SpotCreateStep.CAMERA -> onBack()
                            SpotCreateStep.MAP -> step = SpotCreateStep.CAMERA
                            SpotCreateStep.DETAILS -> step = SpotCreateStep.MAP
                        }
                    },
                ) {
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
                    text = when (step) {
                        SpotCreateStep.CAMERA -> "Take Photo"
                        SpotCreateStep.MAP -> "Set Affected Area"
                        SpotCreateStep.DETAILS -> "Add Details"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            when (step) {
                SpotCreateStep.CAMERA -> {
                    CameraCapture(
                        capturedPhotos = capturedPhotos,
                        onCapture = { photo ->
                            if (capturedPhotos.size < 3) capturedPhotos = capturedPhotos + photo
                        },
                        onRemovePhoto = { index ->
                            capturedPhotos = capturedPhotos.toMutableList().also { it.removeAt(index) }
                        },
                        modifier = Modifier.weight(1f),
                        showMiniMap = true,
                        currentLocation = currentLocation,
                        allowLibraryPick = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    GccButton(
                        text = if (capturedPhotos.isNotEmpty()) "Next: Set Area" else "Skip Photo",
                        onClick = { step = SpotCreateStep.MAP },
                        variant = GccButtonVariant.PRIMARY,
                    )
                }

                SpotCreateStep.MAP -> {
                    SectionHeader(title = "Location")
                    GccCard(accentColor = CardAccentSky) {
                        Text(
                            "Chosen spot",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${formatLat(spotLat)}, ${formatLng(spotLng)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GccSkyDark,
                        )
                        Text(
                            "Drag the crosshair to make small placement adjustments.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Area mode toggle
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = areaMode == AreaMode.CIRCLE,
                            onClick = { areaMode = AreaMode.CIRCLE },
                            label = { Text("Circle") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GccSky.copy(alpha = 0.15f),
                                selectedLabelColor = GccSky,
                            ),
                        )
                        FilterChip(
                            selected = areaMode == AreaMode.RECTANGLE,
                            onClick = { areaMode = AreaMode.RECTANGLE },
                            label = { Text("Rectangle") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GccSky.copy(alpha = 0.15f),
                                selectedLabelColor = GccSky,
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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

                        // Draggable overlay — no bounds for admin
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(spotLat, spotLng, mapZoom) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val metersPerPixel = 156543.03392 * cos(spotLat * kotlin.math.PI / 180.0) / 2.0.pow(overlayZoom)
                                        val dLat = -(dragAmount.y * metersPerPixel) / 111320.0
                                        val dLng = (dragAmount.x * metersPerPixel) / (111320.0 * cos(spotLat * kotlin.math.PI / 180.0))
                                        spotLat = (spotLat + dLat).coerceIn(-85.0, 85.0)
                                        spotLng = (spotLng + dLng).coerceIn(-180.0, 180.0)
                                    }
                                },
                        ) {
                            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)

                            when (areaMode) {
                                AreaMode.CIRCLE -> {
                                    val pixelRadius = metersToPixels(radiusMeters.toDouble(), spotLat, overlayZoom)
                                    drawCircle(color = GccSky.copy(alpha = 0.2f), radius = pixelRadius, center = center)
                                    drawCircle(color = GccSky, radius = pixelRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                                }
                                AreaMode.RECTANGLE -> {
                                    val pxW = metersToPixels(rectWidth.toDouble(), spotLat, overlayZoom)
                                    val pxH = metersToPixels(rectHeight.toDouble(), spotLat, overlayZoom)
                                    val topLeft = androidx.compose.ui.geometry.Offset(center.x - pxW, center.y - pxH)
                                    val rectSize = androidx.compose.ui.geometry.Size(pxW * 2, pxH * 2)
                                    drawRect(color = GccSky.copy(alpha = 0.2f), topLeft = topLeft, size = rectSize)
                                    drawRect(color = GccSky, topLeft = topLeft, size = rectSize, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                                }
                            }

                            // Big crosshair target
                            drawCircle(color = GccSky, radius = 20f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                            drawLine(color = GccSky.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(center.x - 30f, center.y), end = androidx.compose.ui.geometry.Offset(center.x + 30f, center.y), strokeWidth = 1.5f)
                            drawLine(color = GccSky.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(center.x, center.y - 30f), end = androidx.compose.ui.geometry.Offset(center.x, center.y + 30f), strokeWidth = 1.5f)
                            drawCircle(color = GccSky, radius = 3f, center = center)
                        }

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
                            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = GccSky)
                        }

                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shadowElevation = 2.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("Drag to position", style = MaterialTheme.typography.labelSmall, color = GccSkyDark)
                                Text("${formatLat(spotLat)}, ${formatLng(spotLng)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Size sliders
                    when (areaMode) {
                        AreaMode.CIRCLE -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { radiusMeters = (radiusMeters - 5).coerceAtLeast(5f) },
                                    modifier = Modifier.size(36.dp),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccSky),
                                ) { Text("\u2212", style = MaterialTheme.typography.titleMedium) }
                                Slider(
                                    value = radiusMeters,
                                    onValueChange = { radiusMeters = it },
                                    valueRange = 5f..100f,
                                    steps = 18,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = GccSky, activeTrackColor = GccSky),
                                )
                                OutlinedButton(
                                    onClick = { radiusMeters = (radiusMeters + 5).coerceAtMost(100f) },
                                    modifier = Modifier.size(36.dp),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccSky),
                                ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${radiusMeters.toInt()}m", color = GccSky, fontWeight = FontWeight.Bold)
                            }
                        }
                        AreaMode.RECTANGLE -> {
                            Text("Width", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Slider(
                                    value = rectWidth,
                                    onValueChange = { rectWidth = it },
                                    valueRange = 5f..100f,
                                    steps = 18,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = GccSky, activeTrackColor = GccSky),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${rectWidth.toInt()}m", color = GccSky, fontWeight = FontWeight.Bold)
                            }
                            Text("Height", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Slider(
                                    value = rectHeight,
                                    onValueChange = { rectHeight = it },
                                    valueRange = 5f..100f,
                                    steps = 18,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = GccSky, activeTrackColor = GccSky),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${rectHeight.toInt()}m", color = GccSky, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    GccButton(
                        text = "Next: Details",
                        onClick = { step = SpotCreateStep.DETAILS },
                        variant = GccButtonVariant.PRIMARY,
                    )
                }

                SpotCreateStep.DETAILS -> {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { focusManager.clearFocus() },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .imePadding(),
                        ) {
                            // Type toggle
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = isSighting,
                                    onClick = { isSighting = true },
                                    label = { Text("Sighting") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GccGreen.copy(alpha = 0.15f),
                                        selectedLabelColor = GccGreen,
                                    ),
                                )
                                FilterChip(
                                    selected = !isSighting,
                                    onClick = { isSighting = false },
                                    label = { Text("Volunteer Cleanup") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GccGreen.copy(alpha = 0.15f),
                                        selectedLabelColor = GccGreen,
                                    ),
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            SectionHeader(title = "Tags")
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SpotTag.entries.forEach { tag ->
                                    FilterChip(
                                        selected = tag in selectedTags,
                                        onClick = {
                                            selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                                        },
                                        label = { Text(tag.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = GccSky.copy(alpha = 0.15f),
                                            selectedLabelColor = GccSky,
                                        ),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "${capturedPhotos.size} photo${if (capturedPhotos.size != 1) "s" else ""} \u2022 ${
                                    when (areaMode) {
                                        AreaMode.CIRCLE -> "${radiusMeters.toInt()}m radius"
                                        AreaMode.RECTANGLE -> "${rectWidth.toInt()}m x ${rectHeight.toInt()}m"
                                    }
                                } \u2022 ${formatLat(spotLat)}, ${formatLng(spotLng)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            GccTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = "Description",
                                modifier = Modifier.heightIn(min = 100.dp),
                                singleLine = false,
                                imeAction = ImeAction.Done,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            SectionHeader(title = "Discovered by")
                            Spacer(modifier = Modifier.height(6.dp))
                            GccCard(accentColor = CardAccentGreen) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (selectedDiscoveredByWorkerId == null) {
                                                "GCC (admin)"
                                            } else if (selectedDiscoveredByWorker == null) {
                                                "Worker #$selectedDiscoveredByWorkerId"
                                            } else {
                                                "${selectedDiscoveredByWorker.name} \u00B7 ${selectedDiscoveredByWorker.email}"
                                            },
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            if (selectedDiscoveredByWorkerId == null) {
                                                "This spot will be attributed to GCC (admin)."
                                            } else if (selectedDiscoveredByWorker == null) {
                                                "This spot will be attributed to the selected worker and they'll get +10 observer pts."
                                            } else {
                                                "This spot will be attributed to ${selectedDiscoveredByWorker.name} and they'll get +10 observer pts."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                        )
                                        if (uiState.workers.isEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (showWorkersNotLoadedMessage) {
                                                    "No workers loaded - open Workers tab once"
                                                } else {
                                                    "Loading workers..."
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSubtle,
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            if (selectedDiscoveredByWorkerId == null) {
                                                // Pull the workers list before opening the
                                                // sheet so the admin never has to visit the
                                                // Workers tab manually.
                                                viewModel.ensureWorkersLoaded()
                                                isDiscoveredBySheetOpen = true
                                            } else {
                                                selectedDiscoveredByWorkerId = null
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(if (selectedDiscoveredByWorkerId == null) "Change" else "Clear")
                                    }
                                }
                            }

                            uiState.spotCreateError?.let { error ->
                                Spacer(modifier = Modifier.height(12.dp))
                                GccCard(accentColor = CardAccentRed) {
                                    Text(
                                        "Could not create spot",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = GccError,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            GccButton(
                                text = "Create Report",
                                onClick = {
                                    focusManager.clearFocus()
                                    val affectedArea: AffectedArea = when (areaMode) {
                                        AreaMode.CIRCLE -> AffectedArea.Circle(radiusMeters = radiusMeters.toDouble())
                                        AreaMode.RECTANGLE -> AffectedArea.Rectangle(
                                            widthMeters = rectWidth.toDouble(),
                                            heightMeters = rectHeight.toDouble(),
                                        )
                                    }
                                    val request = CreateSpotRequest(
                                        type = if (isSighting) CommunityReportType.SIGHTING else CommunityReportType.VOLUNTEER_CLEANUP,
                                        lat = spotLat,
                                        lng = spotLng,
                                        description = description.ifBlank { null },
                                        affectedRadiusMeters = radiusMeters.toDouble(),
                                        affectedArea = affectedArea,
                                        tags = selectedTags,
                                        reporterName = "GCC",
                                    )
                                    viewModel.createSpot(
                                        request.copy(
                                            discoveredByWorkerId = selectedDiscoveredByWorkerId,
                                        ),
                                        capturedPhotos,
                                    )
                                },
                                variant = GccButtonVariant.PRIMARY,
                                enabled = (spotLat != 0.0 || spotLng != 0.0) && !uiState.isCreatingSpot,
                                isLoading = uiState.isCreatingSpot,
                            )

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttributionPickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(if (selected) GccGreen.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Text(
                "\u2713",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = GccGreen,
                modifier = Modifier.padding(start = 8.dp),
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
