package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.CameraCapture
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.app.viewmodel.AreaMode
import com.gcc.shared.model.AffectedArea
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.CommunityReportType
import com.gcc.shared.model.CreateSpotRequest
import com.gcc.shared.model.SpotTag
import org.koin.compose.koinInject
import kotlin.math.cos
import kotlin.math.pow

private enum class SpotCreateStep { CAMERA, MAP, DETAILS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdminSpotCreateScreen(
    initialLat: Double,
    initialLng: Double,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val locationProvider = koinInject<LocationProvider>()
    val currentLocation by locationProvider.currentLocation.collectAsState()
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
    val defaultLat = if (initialLat == 0.0 && initialLng == 0.0) MapStyles.NORTH_LAWNDALE_CENTER_LAT else initialLat
    val defaultLng = if (initialLat == 0.0 && initialLng == 0.0) MapStyles.NORTH_LAWNDALE_CENTER_LNG else initialLng
    var spotLat by remember { mutableStateOf(defaultLat) }
    var spotLng by remember { mutableStateOf(defaultLng) }
    var hasLockedToGps by remember { mutableStateOf(false) }
    val mapZoom = MapStyles.ZOOM_ACTIVE_JOB

    LaunchedEffect(currentLocation) {
        if (!hasLockedToGps && currentLocation != null) {
            spotLat = currentLocation!!.lat
            spotLng = currentLocation!!.lng
            hasLockedToGps = true
        }
    }

    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose { locationProvider.stopLocationUpdates() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            SpotCreateStep.CAMERA -> "Take Photo"
                            SpotCreateStep.MAP -> "Set Affected Area"
                            SpotCreateStep.DETAILS -> "Add Details"
                        },
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        when (step) {
                            SpotCreateStep.CAMERA -> onBack()
                            SpotCreateStep.MAP -> step = SpotCreateStep.CAMERA
                            SpotCreateStep.DETAILS -> step = SpotCreateStep.MAP
                        }
                    }) {
                        Text(
                            if (step == SpotCreateStep.CAMERA) "Cancel" else "\u2190 Back",
                            color = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccSkyDark,
                    titleContentColor = Color.White,
                ),
            )
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

                    Button(
                        onClick = { step = SpotCreateStep.MAP },
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (capturedPhotos.isNotEmpty()) "Next: Set Area" else "Skip Photo")
                    }
                }

                SpotCreateStep.MAP -> {
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
                                        val metersPerPixel = 156543.03392 * cos(spotLat * kotlin.math.PI / 180.0) / 2.0.pow(mapZoom)
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
                                    val pixelRadius = metersToPixels(radiusMeters.toDouble(), spotLat, mapZoom)
                                    drawCircle(color = GccSky.copy(alpha = 0.2f), radius = pixelRadius, center = center)
                                    drawCircle(color = GccSky, radius = pixelRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                                }
                                AreaMode.RECTANGLE -> {
                                    val pxW = metersToPixels(rectWidth.toDouble(), spotLat, mapZoom)
                                    val pxH = metersToPixels(rectHeight.toDouble(), spotLat, mapZoom)
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
                                Text("${formatCoord(spotLat)}, ${formatCoord(spotLng)}", style = MaterialTheme.typography.bodySmall)
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

                    Button(
                        onClick = { step = SpotCreateStep.DETAILS },
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Next: Details") }
                }

                SpotCreateStep.DETAILS -> {
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

                    // Tag multiselect
                    Text("Tags", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
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
                        } \u2022 ${formatCoord(spotLat)}, ${formatCoord(spotLng)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            val affectedArea: AffectedArea? = when (areaMode) {
                                AreaMode.CIRCLE -> AffectedArea.Circle(radiusMeters = radiusMeters.toDouble())
                                AreaMode.RECTANGLE -> AffectedArea.Rectangle(
                                    widthMeters = rectWidth.toDouble(),
                                    heightMeters = rectHeight.toDouble(),
                                )
                            }
                            viewModel.createSpot(
                                CreateSpotRequest(
                                    type = if (isSighting) CommunityReportType.SIGHTING else CommunityReportType.VOLUNTEER_CLEANUP,
                                    lat = spotLat,
                                    lng = spotLng,
                                    description = description.ifBlank { null },
                                    affectedRadiusMeters = radiusMeters.toDouble(),
                                    affectedArea = affectedArea,
                                    tags = selectedTags,
                                    reporterName = "GCC",
                                ),
                                capturedPhotos,
                            )
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = spotLat != 0.0 || spotLng != 0.0,
                    ) { Text("Create Report") }
                }
            }
        }
    }
}
