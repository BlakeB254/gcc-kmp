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
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.CommunityReportType
import com.gcc.shared.model.CreateSpotRequest
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
    var isSighting by remember { mutableStateOf(true) }
    var description by remember { mutableStateOf("") }
    // Spot pin position — admin can drag anywhere unrestricted
    var spotLat by remember { mutableStateOf(initialLat) }
    var spotLng by remember { mutableStateOf(initialLng) }
    val mapZoom = MapStyles.ZOOM_ACTIVE_JOB

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
                    containerColor = GccGoldDark,
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
                        onCapture = { photo -> capturedPhotos = capturedPhotos + photo },
                        onRemovePhoto = { index ->
                            capturedPhotos = capturedPhotos.toMutableList().also { it.removeAt(index) }
                        },
                        modifier = Modifier.weight(1f),
                        showMiniMap = true,
                        currentLocation = currentLocation,
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
                    // Map with draggable radius circle — admin has unrestricted drag
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

                        // Draggable circle overlay — no bounds for admin
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(spotLat, spotLng, mapZoom) {
                                    detectDragGestures { _, dragAmount ->
                                        val metersPerPixel = 156543.03392 * cos(spotLat * kotlin.math.PI / 180.0) / 2.0.pow(mapZoom)
                                        val dLat = -(dragAmount.y * metersPerPixel) / 111320.0
                                        val dLng = (dragAmount.x * metersPerPixel) / (111320.0 * cos(spotLat * kotlin.math.PI / 180.0))
                                        spotLat = (spotLat + dLat).coerceIn(-85.0, 85.0)
                                        spotLng = (spotLng + dLng).coerceIn(-180.0, 180.0)
                                    }
                                },
                        ) {
                            val pixelRadius = metersToPixels(radiusMeters.toDouble(), spotLat, mapZoom)
                            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                            drawCircle(color = GccGold.copy(alpha = 0.2f), radius = pixelRadius, center = center)
                            drawCircle(color = GccGold, radius = pixelRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                            // Center crosshair dot
                            drawCircle(color = GccGold, radius = 4f, center = center)
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                "${radiusMeters.toInt()}m radius",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = GccGold,
                            )
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
                                Text(
                                    "Drag to position",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GccGoldDark,
                                )
                                Text(
                                    "${formatCoord(spotLat)}, ${formatCoord(spotLng)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Radius slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { radiusMeters = (radiusMeters - 5).coerceAtLeast(5f) },
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGold),
                        ) {
                            Text("\u2212", style = MaterialTheme.typography.titleMedium)
                        }
                        Slider(
                            value = radiusMeters,
                            onValueChange = { radiusMeters = it },
                            valueRange = 5f..100f,
                            steps = 18,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = GccGold, activeTrackColor = GccGold),
                        )
                        OutlinedButton(
                            onClick = { radiusMeters = (radiusMeters + 5).coerceAtMost(100f) },
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGold),
                        ) {
                            Text("+", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${radiusMeters.toInt()}m", color = GccGold, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { step = SpotCreateStep.DETAILS },
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Next: Details")
                    }
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

                    Text(
                        "${capturedPhotos.size} photo${if (capturedPhotos.size != 1) "s" else ""} \u2022 ${radiusMeters.toInt()}m radius \u2022 ${formatCoord(spotLat)}, ${formatCoord(spotLng)}",
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
                            viewModel.createSpot(
                                CreateSpotRequest(
                                    type = if (isSighting) CommunityReportType.SIGHTING else CommunityReportType.VOLUNTEER_CLEANUP,
                                    lat = spotLat,
                                    lng = spotLng,
                                    description = description.ifBlank { null },
                                    affectedRadiusMeters = radiusMeters.toDouble(),
                                    reporterName = "GCC",
                                ),
                                capturedPhotos,
                            )
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = spotLat != 0.0 || spotLng != 0.0,
                    ) {
                        Text("Create Report")
                    }
                }
            }
        }
    }
}
