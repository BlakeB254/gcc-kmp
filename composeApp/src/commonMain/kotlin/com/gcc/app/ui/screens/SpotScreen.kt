package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import kotlin.math.cos
import kotlin.math.pow
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.SpotPhase
import com.gcc.app.viewmodel.SpotViewModel
import com.gcc.shared.model.Bounty
import com.gcc.shared.model.Coordinates
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
            Row(
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
                        SpotPhase.CAMERA -> "Take Photo"
                        SpotPhase.LOCATION -> "Confirm Location"
                        SpotPhase.DESCRIPTION -> "Add Details"
                        SpotPhase.SUBMITTING -> "Submitting..."
                        SpotPhase.RESULT -> "Report Submitted"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
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
                )

                SpotPhase.LOCATION -> LocationPhase(
                    gpsLat = uiState.currentLat,
                    gpsLng = uiState.currentLng,
                    spotLat = uiState.spotLat ?: uiState.currentLat,
                    spotLng = uiState.spotLng ?: uiState.currentLng,
                    radiusMeters = uiState.affectedRadiusMeters,
                    onRadiusChange = { viewModel.setRadius(it.toDouble()) },
                    onSpotMoved = { lat, lng -> viewModel.setSpotLocation(lat, lng) },
                    onNext = { viewModel.nextPhase() },
                )

                SpotPhase.DESCRIPTION -> DescriptionPhase(
                    description = uiState.description,
                    onDescriptionChange = { viewModel.setDescription(it) },
                    photoCount = uiState.capturedPhotos.size,
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
                    error = uiState.error,
                    onAcceptBounty = { viewModel.acceptBounty(it) },
                    onVolunteerCleanup = {
                        uiState.submittedReport?.let { report ->
                            onVolunteerCleanup(report.id)
                        }
                    },
                    onNewReport = { viewModel.reset() },
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun CameraPhase(
    photos: List<CapturedPhoto>,
    onCapture: (CapturedPhoto) -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val locationProvider = koinInject<LocationProvider>()
    val currentLocation by locationProvider.currentLocation.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CameraCapture(
            capturedPhotos = photos,
            onCapture = onCapture,
            onRemovePhoto = onRemovePhoto,
            modifier = Modifier.weight(1f),
            showMiniMap = true,
            currentLocation = currentLocation,
        )

        Spacer(modifier = Modifier.height(12.dp))

        GccButton(
            text = if (photos.isNotEmpty()) "Next: Set Location" else "Skip Photo",
            onClick = onNext,
            variant = if (photos.isNotEmpty()) GccButtonVariant.PRIMARY else GccButtonVariant.OUTLINED,
        )
    }
}

/**
 * Convert a radius in meters to screen pixels at a given Mercator zoom level and latitude.
 * Formula: pixels = meters / (156543.03392 * cos(lat) / 2^zoom)
 */
private fun metersToPixels(meters: Double, lat: Double, zoom: Double): Float {
    val metersPerPixel = 156543.03392 * cos(lat * kotlin.math.PI / 180.0) / 2.0.pow(zoom)
    return (meters / metersPerPixel).toFloat()
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

@Composable
private fun LocationPhase(
    gpsLat: Double,
    gpsLng: Double,
    spotLat: Double,
    spotLng: Double,
    radiusMeters: Double,
    onRadiusChange: (Float) -> Unit,
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
            "Drag the circle to position it on the mess. Adjust the slider for radius.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Map with location pin and draggable radius circle overlay
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

            // Draggable radius circle overlay
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(spotLat, spotLng, mapZoom) {
                        detectDragGestures { _, dragAmount ->
                            val (dLat, dLng) = pixelDeltaToLatLng(
                                dragAmount.x, dragAmount.y, spotLat, mapZoom,
                            )
                            // Bound within ~500m of GPS position for regular users
                            val maxOffsetDeg = 500.0 / 111320.0 // ~0.0045 degrees
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
                val pixelRadius = metersToPixels(radiusMeters, spotLat, mapZoom)
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                // Semi-transparent fill
                drawCircle(
                    color = GccGold.copy(alpha = 0.2f),
                    radius = pixelRadius,
                    center = center,
                )
                // Stroke border
                drawCircle(
                    color = GccGold,
                    radius = pixelRadius,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f),
                )
                // Center crosshair dot
                drawCircle(
                    color = GccGold,
                    radius = 4f,
                    center = center,
                )
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
                    Text(
                        "\uD83D\uDCCD",
                        style = MaterialTheme.typography.titleMedium,
                    )
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

            // Radius label badge at bottom center
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
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Radius slider card
        GccCard(accentColor = CardAccentGold) {
            Text(
                "Affected Area",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { onRadiusChange((radiusMeters - 5).coerceAtLeast(5.0).toFloat()) },
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGold),
                ) {
                    Text("\u2212", style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = radiusMeters.toFloat(),
                    onValueChange = onRadiusChange,
                    valueRange = 5f..100f,
                    steps = 18,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = GccGold,
                        activeTrackColor = GccGold,
                    ),
                )
                OutlinedButton(
                    onClick = { onRadiusChange((radiusMeters + 5).coerceAtMost(100.0).toFloat()) },
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGold),
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${radiusMeters.toInt()}m",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = GccGold,
                )
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

@Composable
private fun DescriptionPhase(
    description: String,
    onDescriptionChange: (String) -> Unit,
    photoCount: Int,
    error: String?,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            "Describe What You See",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Optional: Add a description to help others.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
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
        )

        Spacer(modifier = Modifier.height(16.dp))

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
                    if (description.isNotBlank()) "Description added" else "No description",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (description.isNotBlank()) GccGreen else TextSubtle,
                )
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
        }

        Spacer(modifier = Modifier.weight(1f))

        GccButton(
            text = "Submit Report",
            onClick = onSubmit,
            variant = GccButtonVariant.PRIMARY,
        )
    }
}

@Composable
private fun ResultPhase(
    report: com.gcc.shared.model.CommunityReport?,
    matchingBounties: List<Bounty>,
    error: String?,
    onAcceptBounty: (Int) -> Unit,
    onVolunteerCleanup: () -> Unit,
    onNewReport: () -> Unit,
    onBack: () -> Unit,
) {
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
                }
            }
        }

        // Points awarded
        item {
            report?.let {
                if (it.observerRewardPoints > 0) {
                    GccCard(accentColor = CardAccentGold) {
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
                                color = GccGold,
                            )
                        }
                    }
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
    GccCard(accentColor = CardAccentGold) {
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
                    color = GccGold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onAccept) {
                    Text("Accept", color = GccGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
