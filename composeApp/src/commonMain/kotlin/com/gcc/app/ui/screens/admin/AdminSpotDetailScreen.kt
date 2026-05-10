package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.gcc.app.platform.pickFromLibrary
import com.gcc.app.ui.components.PhotoStrip
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.shared.model.CommunityReportStatus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AdminSpotDetailScreen(
    spotId: Int,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val report = uiState.spotReports.find { it.id == spotId }
    val scope = rememberCoroutineScope()
    val cameraManager = remember { CameraManager() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isAddingPhotos by remember { mutableStateOf(false) }
    var photoUrls by remember(report) { mutableStateOf(report?.mediaUrls ?: emptyList()) }

    if (report == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Spot report not found")
        }
        return
    }

    val statusColor = when (report.status) {
        CommunityReportStatus.PENDING -> GccSky
        CommunityReportStatus.VERIFIED -> GccGreen
        CommunityReportStatus.RESOLVED -> GccSky
        CommunityReportStatus.ACKNOWLEDGED -> GccBlue
        CommunityReportStatus.REJECTED -> GccError
    }
    val mapZoom = MapStyles.ZOOM_ACTIVE_JOB

    fun addPhotoToSpot(photoBytes: ByteArray) {
        scope.launch {
            isAddingPhotos = true
            viewModel.addSpotPhotos(spotId, listOf(photoBytes))
            isAddingPhotos = false
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
                    "Spot Report #$spotId",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = statusColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        report.status.name.lowercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Map
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = report.coordinates.lat,
                        centerLng = report.coordinates.lng,
                        zoom = mapZoom,
                    )
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val pixelRadius = metersToPixels(report.affectedRadiusMeters, report.coordinates.lat, mapZoom)
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        drawCircle(color = GccSky.copy(alpha = 0.2f), radius = pixelRadius, center = center)
                        drawCircle(color = GccSky, radius = pixelRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                    }
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("${report.affectedRadiusMeters.toInt()}m radius", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = GccSky)
                    }
                }
            }

            // Reporter info
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Reporter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            report.reporterName ?: "Anonymous",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (report.reporterName == "GCC") GccGreen else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Type", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(report.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Location
            item {
                Text("Location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatCoord(report.coordinates.lat)}, ${formatCoord(report.coordinates.lng)}", style = MaterialTheme.typography.bodyMedium)
            }

            // Description
            report.description?.let { desc ->
                item {
                    Text("Description", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Photos
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Photos (${photoUrls.size})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val photo = cameraManager.capturePhoto()
                                    if (photo != null) addPhotoToSpot(photo.bytes)
                                }
                            },
                            enabled = !isAddingPhotos,
                        ) {
                            Text("\uD83D\uDCF7", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val photo = pickFromLibrary()
                                    if (photo != null) addPhotoToSpot(photo.bytes)
                                }
                            },
                            enabled = !isAddingPhotos,
                        ) {
                            Text("\uD83D\uDDBC", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (isAddingPhotos) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = GccGreen)
                }
                if (photoUrls.isNotEmpty()) {
                    PhotoStrip(urls = photoUrls)
                }
            }

            // Admin actions — verify, acknowledge, resolve
            if (report.status != CommunityReportStatus.RESOLVED) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Status info
                    Text(
                        "Current status: ${report.status.name.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Row 1: Verify + Acknowledge (only for pending spots)
                        if (report.status == CommunityReportStatus.PENDING) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.updateSpotStatus(spotId, "verified")
                                        onBack()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                                ) {
                                    Text("\u2705 Verify")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.updateSpotStatus(spotId, "acknowledged")
                                        onBack()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccBlue),
                                ) {
                                    Text("\uD83D\uDC41 Acknowledge")
                                }
                            }
                        }

                        // Row 2: Resolve (mark as cleaned/done)
                        Button(
                            onClick = {
                                viewModel.updateSpotStatus(spotId, "resolved")
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = GccSky),
                        ) {
                            Text("\u2705 Mark Resolved (Cleaned)")
                        }
                    }
                }
            } else {
                // Already resolved — show status
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = GccGreen.copy(alpha = 0.1f),
                    ) {
                        Text(
                            "\u2705 This spot has been resolved",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GccGreen,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // Delete
            item {
                if (showDeleteConfirm) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = GccError.copy(alpha = 0.1f)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Archive this spot report? It will be hidden from the map but kept for records.", style = MaterialTheme.typography.bodySmall, color = GccError, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showDeleteConfirm = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                Button(
                                    onClick = {
                                        viewModel.deleteSpot(spotId)
                                        onBack()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = GccError),
                                ) { Text("Archive") }
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("Archive Report", color = GccError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
