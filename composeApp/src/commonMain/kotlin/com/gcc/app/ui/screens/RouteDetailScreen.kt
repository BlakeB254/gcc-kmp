package com.gcc.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.CheckpointMarker
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.app.viewmodel.RouteDetailViewModel
import com.gcc.app.util.fmt
import com.gcc.app.util.padTwo
import com.gcc.shared.model.CheckpointType
import com.gcc.shared.model.Coordinates
import org.koin.compose.koinInject

@Composable
fun RouteDetailScreen(
    routeId: Int = 0,
    onBack: () -> Unit = {},
    onStartJob: (jobId: Int) -> Unit = {},
) {
    val viewModel = koinInject<RouteDetailViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(routeId) {
        viewModel.loadRoute(routeId)
    }

    // Navigate to ActiveJob on successful claim
    LaunchedEffect(uiState.claimedJobId) {
        uiState.claimedJobId?.let { jobId ->
            onStartJob(jobId)
        }
    }

    val route = uiState.route

    if (route == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(color = GccGreen)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Route not found")
                    uiState.error?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                }
            }
        }
        return
    }

    val payoutString = route.payoutAmount?.let { cents ->
        "$${cents / 100}.${(cents % 100).padTwo()}"
    }

    // Compute route path from checkpoints for polyline rendering
    val sortedCheckpoints = remember(route) {
        route.checkpoints.sortedBy { it.sequenceOrder }
    }
    val routePath = remember(sortedCheckpoints) {
        listOf(sortedCheckpoints.map { it.coordinates })
    }

    // Compute map center as centroid of all checkpoints
    val mapCenter = remember(sortedCheckpoints) {
        if (sortedCheckpoints.isEmpty()) {
            Coordinates(lng = MapStyles.CHICAGO_CENTER_LNG, lat = MapStyles.CHICAGO_CENTER_LAT)
        } else {
            val avgLat = sortedCheckpoints.sumOf { it.coordinates.lat } / sortedCheckpoints.size
            val avgLng = sortedCheckpoints.sumOf { it.coordinates.lng } / sortedCheckpoints.size
            Coordinates(lng = avgLng, lat = avgLat)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero map with route polyline and gradient overlay
            item {
                Box(modifier = Modifier.height(260.dp)) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = mapCenter.lat,
                        centerLng = mapCenter.lng,
                        zoom = MapStyles.ZOOM_ROUTE,
                        routeCoordinates = routePath,
                        animateRoutes = true,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                                )
                            )
                    )
                }
            }

            // Floating info card overlapping hero
            item {
                GccCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .offset(y = (-24).dp),
                    elevation = 6.dp,
                ) {
                    Text(
                        route.displayName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        "Code: ${route.code}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        route.distanceMeters?.let { meters ->
                            InfoStat(
                                value = if (meters >= 1000) "${(meters / 1000).fmt(1)} km"
                                else "${meters.toInt()} m",
                                label = "Distance",
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(32.dp)
                                    .background(DividerColor)
                            )
                        }
                        route.estimatedDurationMinutes?.let { mins ->
                            InfoStat(value = "$mins min", label = "Duration")
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(32.dp)
                                    .background(DividerColor)
                            )
                        }
                        payoutString?.let {
                            InfoStat(value = it, label = "Payout", highlight = true)
                        }
                    }
                }
            }

            // Checkpoint timeline header
            item {
                SectionHeader(
                    title = "Checkpoints (${route.checkpoints.size})",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Checkpoint timeline with location thumbnails
            itemsIndexed(
                sortedCheckpoints,
                key = { _, cp -> cp.sequenceOrder },
            ) { index, checkpoint ->
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Timeline marker + connector
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CheckpointMarker(
                            sequenceNumber = index + 1,
                            type = checkpoint.type,
                            status = CheckpointStatus.UPCOMING,
                            photoRequired = checkpoint.photoRequired,
                        )
                        if (index < sortedCheckpoints.size - 1) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(56.dp)
                                    .background(DividerColor)
                            )
                        }
                    }

                    // Checkpoint info + location thumbnail
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                checkpoint.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                checkpoint.type.name.replace("_", " "),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                            if (checkpoint.photoRequired) {
                                Text(
                                    "\uD83D\uDCF7 Photo required",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GccGreen,
                                )
                            }
                        }

                        // Location reference thumbnail
                        CheckpointThumbnail(
                            type = checkpoint.type,
                            sequenceNumber = index + 1,
                            totalCheckpoints = sortedCheckpoints.size,
                            modifier = Modifier.size(55.dp),
                        )
                    }
                }
            }

            // Claim route button
            item {
                Spacer(modifier = Modifier.height(16.dp))

                uiState.claimError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = GccError,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                GccButton(
                    text = "Claim Route",
                    onClick = { viewModel.claimRoute(routeId) },
                    variant = GccButtonVariant.PRIMARY,
                    trailingText = payoutString,
                    enabled = !uiState.isClaiming,
                    isLoading = uiState.isClaiming,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Floating back button
        TextButton(
            onClick = onBack,
            modifier = Modifier
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
                Text(
                    "\u2190",
                    style = MaterialTheme.typography.titleMedium,
                    color = GccGreenDark,
                )
            }
        }
    }
}

// Procedural location thumbnail for checkpoint visual identification
@Composable
private fun CheckpointThumbnail(
    type: CheckpointType,
    sequenceNumber: Int,
    totalCheckpoints: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(6.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (type) {
                CheckpointType.ROUTE_START -> drawStartScene()
                CheckpointType.ROUTE_END -> drawEndScene()
                CheckpointType.PHOTO_POINT -> drawPhotoPointScene(sequenceNumber)
                CheckpointType.ALLEY_ENTRANCE -> drawAlleyPointScene()
            }
        }
    }
}

private fun DrawScope.drawStartScene() {
    val w = size.width
    val h = size.height
    // Green gradient background
    drawRect(Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF81C784))))
    // Road
    drawRect(Color(0xFF616161).copy(alpha = 0.4f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    // Center line
    drawRect(Color(0xFFFFEB3B).copy(alpha = 0.6f), Offset(w * 0.48f, 0f), Size(w * 0.04f, h))
    // Start flag
    val flagPole = Offset(w * 0.55f, h * 0.15f)
    drawLine(Color.White, flagPole, Offset(flagPole.x, h * 0.65f), strokeWidth = 3f)
    val flag = Path().apply {
        moveTo(flagPole.x, flagPole.y)
        lineTo(flagPole.x + w * 0.3f, flagPole.y + h * 0.1f)
        lineTo(flagPole.x, flagPole.y + h * 0.2f)
        close()
    }
    drawPath(flag, Color(0xFF4CAF50))
    // Arrow pointing down
    val arrow = Path().apply {
        moveTo(w * 0.25f, h * 0.3f)
        lineTo(w * 0.25f, h * 0.6f)
        lineTo(w * 0.15f, h * 0.6f)
        lineTo(w * 0.25f, h * 0.75f)
        lineTo(w * 0.35f, h * 0.6f)
        lineTo(w * 0.25f, h * 0.6f)
    }
    drawPath(arrow, Color.White.copy(alpha = 0.8f))
}

private fun DrawScope.drawEndScene() {
    val w = size.width
    val h = size.height
    // Red-orange gradient background
    drawRect(Brush.linearGradient(listOf(Color(0xFFC62828), Color(0xFFEF5350))))
    // Road
    drawRect(Color(0xFF424242).copy(alpha = 0.4f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    // Checkered finish pattern
    val cellSize = w * 0.12f
    for (row in 0..3) {
        for (col in 0..3) {
            if ((row + col) % 2 == 0) {
                drawRect(
                    Color.White.copy(alpha = 0.7f),
                    Offset(w * 0.2f + col * cellSize, h * 0.15f + row * cellSize),
                    Size(cellSize, cellSize),
                )
            } else {
                drawRect(
                    Color.Black.copy(alpha = 0.5f),
                    Offset(w * 0.2f + col * cellSize, h * 0.15f + row * cellSize),
                    Size(cellSize, cellSize),
                )
            }
        }
    }
    // Finish flag pole
    drawLine(Color.White, Offset(w * 0.15f, h * 0.1f), Offset(w * 0.15f, h * 0.7f), strokeWidth = 3f)
}

private fun DrawScope.drawPhotoPointScene(sequenceNumber: Int) {
    val w = size.width
    val h = size.height
    // Blue-teal gradient
    drawRect(Brush.linearGradient(listOf(Color(0xFF00796B), Color(0xFF4DB6AC))))
    // Intersection cross-roads
    drawRect(Color(0xFF616161).copy(alpha = 0.35f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    drawRect(Color(0xFF616161).copy(alpha = 0.35f), Offset(0f, h * 0.35f), Size(w, h * 0.3f))
    // Camera icon shape
    val camBody = Size(w * 0.4f, h * 0.25f)
    val camOffset = Offset(w * 0.3f, h * 0.55f)
    drawRoundRect(
        Color.White.copy(alpha = 0.7f),
        camOffset,
        camBody,
        CornerRadius(4f),
    )
    // Camera lens
    drawCircle(
        Color(0xFF00695C),
        w * 0.08f,
        Offset(camOffset.x + camBody.width / 2, camOffset.y + camBody.height / 2),
    )
    // Camera flash/viewfinder top
    drawRoundRect(
        Color.White.copy(alpha = 0.5f),
        Offset(camOffset.x + camBody.width * 0.3f, camOffset.y - h * 0.06f),
        Size(camBody.width * 0.3f, h * 0.07f),
        CornerRadius(2f),
    )
}

private fun DrawScope.drawAlleyPointScene() {
    val w = size.width
    val h = size.height
    // Brown-amber gradient
    drawRect(Brush.linearGradient(listOf(Color(0xFF5D4037), Color(0xFF8D6E63))))
    // Alley perspective walls
    val leftWall = Path().apply {
        moveTo(0f, 0f)
        lineTo(w * 0.3f, h * 0.25f)
        lineTo(w * 0.3f, h)
        lineTo(0f, h)
        close()
    }
    drawPath(leftWall, Color(0xFF795548).copy(alpha = 0.6f))
    val rightWall = Path().apply {
        moveTo(w, 0f)
        lineTo(w * 0.7f, h * 0.25f)
        lineTo(w * 0.7f, h)
        lineTo(w, h)
        close()
    }
    drawPath(rightWall, Color(0xFF795548).copy(alpha = 0.6f))
    // Alley floor
    drawRect(Color(0xFF9E9E9E).copy(alpha = 0.3f), Offset(w * 0.3f, h * 0.25f), Size(w * 0.4f, h * 0.75f))
    // Arrow pointing into alley
    val arrow = Path().apply {
        moveTo(w * 0.5f, h * 0.1f)
        lineTo(w * 0.4f, h * 0.25f)
        lineTo(w * 0.45f, h * 0.25f)
        lineTo(w * 0.45f, h * 0.45f)
        lineTo(w * 0.55f, h * 0.45f)
        lineTo(w * 0.55f, h * 0.25f)
        lineTo(w * 0.6f, h * 0.25f)
        close()
    }
    drawPath(arrow, Color.White.copy(alpha = 0.6f))
}

@Composable
private fun InfoStat(
    value: String,
    label: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (highlight) GccGreen else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}
