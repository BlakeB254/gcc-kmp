package com.gcc.app.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.RouteScanSectionStatus
import com.gcc.app.viewmodel.RouteScanUiState
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.CheckpointGenerator
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cleaning lap screen — shown during the CLEANING phase.
 *
 * Layout:
 *  - Top 60%: GccMap with route polyline, GPS dot, and animated hotspot markers
 *    (green=cleaned, red pulsing=nearby within 20m, amber=pending, gray=skipped)
 *  - Bottom 40%: Scrollable panel with progress bar, hotspot list, Complete button
 *  - Floating: ProximityAlertCard docked at the bottom of the map area
 *  - Modal: HotspotCaptureSheet opens when the user taps CLEAN
 */
@Composable
fun CleanScreen(
    uiState: RouteScanUiState,
    onCompleteScan: () -> Unit,
    onCleanHotspot: (ScanHotspot) -> Unit,
    onSkipHotspot: (ScanHotspot) -> Unit,
    onBack: () -> Unit,
    onConfirmCleaned: ((hotspotId: Int, afterPhotoUrl: String, score: Float) -> Unit)? = null,
) {
    val apiClient = koinInject<GccApiClient>()
    val coroutineScope = rememberCoroutineScope()

    val cleaned = uiState.hotspots.count { it.status == HotspotStatus.CLEANED }
    val documentedOnly = uiState.hotspots.count { it.status == HotspotStatus.SKIPPED }
    val totalCleanupTargets = uiState.hotspots.count { it.status != HotspotStatus.SKIPPED }
    val allDone = uiState.hotspots.none { it.status == HotspotStatus.DETECTED }
    val currentCleanupTarget = uiState.hotspots.firstOrNull { it.status == HotspotStatus.DETECTED }

    val nearbyDist = uiState.nearbyDistMeters

    // Current location for map center
    val currentLocation = if (uiState.currentLat != 0.0 || uiState.currentLng != 0.0) {
        Coordinates(lat = uiState.currentLat, lng = uiState.currentLng)
    } else null

    val effectiveCheckpoints = remember(uiState.route) {
        uiState.route
            ?.let { CheckpointGenerator.generateIfMissing(it) }
            .orEmpty()
            .sortedBy { it.sequenceOrder }
    }

    // Route polyline from checkpoint coordinates
    val routeCoords = remember(effectiveCheckpoints) {
        effectiveCheckpoints
            .map { Coordinates(lat = it.coordinates.lat, lng = it.coordinates.lng) }
            .let { if (it.isNotEmpty()) listOf(it) else emptyList() }
    }

    // Map centre: user → route start → Chicago default
    val mapCenterLat = currentLocation?.lat
        ?: effectiveCheckpoints.firstOrNull()?.coordinates?.lat
        ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
    val mapCenterLng = currentLocation?.lng
        ?: effectiveCheckpoints.firstOrNull()?.coordinates?.lng
        ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG

    // Hotspot capture sheet state
    var captureHotspot by remember { mutableStateOf<ScanHotspot?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Map section (60% height) ────────────────────────────────
            Box(modifier = Modifier.weight(0.6f)) {
                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = mapCenterLat,
                    centerLng = mapCenterLng,
                    zoom = MapStyles.ZOOM_ACTIVE_JOB,
                    currentLocation = currentLocation,
                    enableGestures = true,
                    routeCoordinates = routeCoords,
                    bountyLocations = uiState.hotspots
                        .filter { it.status == HotspotStatus.DETECTED }
                        .map { Coordinates(lat = it.lat, lng = it.lng) },
                    showBuildings = true,
                    showGridLines = true,
                    showControls = true,
                    statusBarPadding = true,
                ) {
                    // ── Hotspot status overlays ─────────────────────────
                    uiState.hotspots.forEach { hotspot ->
                        HotspotMapMarker(
                            hotspot = hotspot,
                            isNearby = hotspot.id == uiState.nearbyHotspot?.id,
                        )
                    }
                }

                // ── ProximityAlertCard docked at bottom of map ──────────
                ProximityAlertCard(
                    hotspot = uiState.nearbyHotspot,
                    distanceMeters = nearbyDist,
                    onClean = { hotspot ->
                        captureHotspot = hotspot
                    },
                    onSkip = onSkipHotspot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )

                // ── Back button ─────────────────────────────────────────
                TextButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .align(Alignment.TopStart),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("\u2190", style = MaterialTheme.typography.titleMedium, color = GccGreenDark)
                    }
                }
            }

            // ── Bottom panel (40% height) ───────────────────────────────
            Card(
                modifier = Modifier.weight(0.4f),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextSubtle.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Progress row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Cleanup Targets",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = "$cleaned / $totalCleanupTargets cleaned",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = GccGreen,
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Progress bar
                    val progressFraction = if (totalCleanupTargets > 0) {
                        cleaned.toFloat() / totalCleanupTargets
                    } else {
                        1f
                    }
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = GccGreen,
                        trackColor = GccGreen.copy(alpha = 0.15f),
                    )

                    if (documentedOnly > 0) {
                        Text(
                            text = "$documentedOnly documented only",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }

                    captureError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                    uiState.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    CurrentCleanupSectionCard(
                        uiState = uiState,
                        currentTarget = currentCleanupTarget,
                        onRecordAfterScan = { target -> captureHotspot = target },
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable hotspot list
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (uiState.hotspots.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Hotspots",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextMuted,
                                )
                            }
                            items(uiState.hotspots, key = { it.id }) { hotspot ->
                                HotspotListItem(
                                    hotspot = hotspot,
                                    isNearby = hotspot.id == uiState.nearbyHotspot?.id,
                                )
                            }
                        } else {
                            item {
                                Text(
                                    text = "No hotspots detected on this route",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Complete scan button
                    GccButton(
                        text = if (allDone) "Complete Scan" else "Cleaning\u2026 $cleaned/$totalCleanupTargets done",
                        onClick = { if (allDone) onCompleteScan() },
                        variant = GccButtonVariant.PRIMARY,
                        enabled = allDone,
                        isLoading = uiState.isSubmitting,
                    )
                }
            }
        }
    }

    // ── HotspotCaptureSheet modal ──────────────────────────────────────
    val hotspotToCapture = captureHotspot
    if (hotspotToCapture != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            HotspotCaptureSheet(
                hotspot = hotspotToCapture,
                onConfirmed = { afterBytes, score ->
                    captureError = null
                    coroutineScope.launch {
                        try {
                            // Upload after-photo bytes and resolve URL
                            val afterPhotoUrl = if (afterBytes.isNotEmpty()) {
                                val urls = apiClient.uploadSpotPhotos(listOf(afterBytes))
                                urls.firstOrNull() ?: "after_${hotspotToCapture.id}.jpg"
                            } else {
                                "after_${hotspotToCapture.id}.jpg"
                            }

                            // Delegate to parent callback (RouteScanScreen → ViewModel)
                            captureHotspot = null
                            onConfirmCleaned?.invoke(hotspotToCapture.id, afterPhotoUrl, score)
                                ?: onCleanHotspot(hotspotToCapture)
                        } catch (e: Exception) {
                            captureHotspot = null
                            captureError = "Upload failed: ${e.message}"
                        }
                    }
                },
                onDismiss = { captureHotspot = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            )
        }
    }
}

@Composable
private fun CurrentCleanupSectionCard(
    uiState: RouteScanUiState,
    currentTarget: ScanHotspot?,
    onRecordAfterScan: (ScanHotspot) -> Unit,
) {
    val approvedCount = uiState.routeSections.count { it.status == RouteScanSectionStatus.APPROVED }
    val cleanupTargetCount = uiState.hotspots.count { it.status != HotspotStatus.SKIPPED }
    val sectionIndex = currentTarget?.arAnchorData
        ?.substringAfter("section:", missingDelimiterValue = "")
        ?.takeWhile { it.isDigit() }
        ?.toIntOrNull()
    val sectionName = sectionIndex
        ?.let { uiState.routeSections.getOrNull(it)?.name }
        ?: "Current cleanup area"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = GccGreen.copy(alpha = 0.10f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (currentTarget == null) "All cleanup targets approved" else sectionName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = GccGreenDark,
            )
            Text(
                text = uiState.sectionPrompt ?: if (currentTarget == null) {
                    "Complete the scan to submit the route recording."
                } else if (uiState.deviceType == com.gcc.shared.model.DeviceType.AR) {
                    "Run the after scan here. When AI sees the area as clean, this section approves automatically."
                } else {
                    "Take the after photo for this section. The app will approve it and move you to the next area."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Text(
                text = "$approvedCount / $cleanupTargetCount cleanup sections approved",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = GccGreen,
            )
            if (currentTarget != null) {
                GccButton(
                    text = if (uiState.deviceType == com.gcc.shared.model.DeviceType.AR) {
                        "Run After Scan"
                    } else {
                        "Capture After Photo"
                    },
                    onClick = { onRecordAfterScan(currentTarget) },
                    variant = GccButtonVariant.SECONDARY,
                )
            }
        }
    }
}

// ── Hotspot map marker with status colour + pulse on nearby ──────────────

@Composable
private fun HotspotMapMarker(
    hotspot: ScanHotspot,
    isNearby: Boolean,
) {
    val color = when {
        hotspot.status == HotspotStatus.CLEANED -> GccGreen
        hotspot.status == HotspotStatus.SKIPPED -> TextSubtle
        isNearby -> GccError
        else -> GccSky
    }

    // Pulse animation for the nearby hotspot
    val scale by if (isNearby) {
        val infiniteTransition = rememberInfiniteTransition(label = "hotspot-pulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse-scale",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Box(
        modifier = Modifier
            .size(if (isNearby) 20.dp else 14.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isNearby) 12.dp else 8.dp)
                .clip(CircleShape)
                .background(color),
        )
        if (hotspot.status == HotspotStatus.CLEANED) {
            Text(
                text = "\u2713",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }
}

// ── Hotspot list item ─────────────────────────────────────────────────────

@Composable
private fun HotspotListItem(
    hotspot: ScanHotspot,
    isNearby: Boolean = false,
) {
    val (statusText, statusColor) = when (hotspot.status) {
        HotspotStatus.DETECTED -> if (isNearby) "Nearby" to GccError else "Pending" to GccSky
        HotspotStatus.CLEANED -> "Cleaned" to GccGreen
        HotspotStatus.SKIPPED -> "Documented" to TextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isNearby) GccError.copy(alpha = 0.06f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            val typesLabel = if (hotspot.trashTypes.isNotEmpty())
                hotspot.trashTypes.take(2).joinToString(", ") {
                    it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                }
            else "Hotspot"
            Text(
                text = typesLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = "Severity ${"★".repeat(hotspot.severity.coerceIn(1, 5))}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = statusColor,
            )
        }
    }
}
