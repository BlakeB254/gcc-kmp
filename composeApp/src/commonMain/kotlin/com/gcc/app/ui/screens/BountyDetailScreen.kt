package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.util.padTwo
import com.gcc.shared.model.BountyStatus
import org.koin.compose.koinInject

@Composable
fun BountyDetailScreen(
    bountyId: Int = 0,
    onBack: () -> Unit = {},
    onNavigateToActiveJob: (jobId: Int, routeId: Int) -> Unit = { _, _ -> },
) {
    val viewModel = koinInject<BountyViewModel>()
    val locationProvider = koinInject<LocationProvider>()
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by locationProvider.currentLocation.collectAsState()

    LaunchedEffect(Unit) {
        locationProvider.startLocationUpdates()
    }

    DisposableEffect(Unit) {
        onDispose { locationProvider.stopLocationUpdates() }
    }

    LaunchedEffect(bountyId) {
        viewModel.loadBountyById(bountyId)
    }

    // Navigate to active job when claim succeeds and a jobId is present
    val bounty = uiState.selectedBounty
    LaunchedEffect(bounty?.status, bounty?.jobId) {
        if (bounty != null && bounty.status == BountyStatus.CLAIMED && bounty.jobId != null) {
            onNavigateToActiveJob(bounty.jobId!!, 0)
        }
    }

    if (bounty == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(color = GccGreen)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bounty not found")
                    uiState.error?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                }
            }
        }
        return
    }

    val dollars = bounty.amount / 100
    val cents = bounty.amount % 100
    val payoutString = "$$dollars.${cents.padTwo()}"

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero map with bounty zone
            item {
                Box(modifier = Modifier.height(240.dp)) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = bounty.locationLat,
                        centerLng = bounty.locationLng,
                        zoom = MapStyles.ZOOM_ROUTE,
                        currentLocation = currentLocation,
                    )
                    // Bounty zone circle indicator
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(GccGold.copy(alpha = 0.2f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color.Transparent)
                                .then(Modifier),
                        )
                    }
                    // Gradient fade at bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                                ),
                            ),
                    )
                }
            }

            // Floating info card
            item {
                GccCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .offset(y = (-24).dp),
                    elevation = 6.dp,
                ) {
                    Text(
                        bounty.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BountyDetailStatusChip(bounty.status)
                        bounty.communityArea?.let { area ->
                            Text(
                                area,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BountyInfoStat(value = payoutString, label = "Payout", highlight = true)
                        Box(
                            modifier = Modifier.width(1.dp).height(32.dp).background(DividerColor),
                        )
                        BountyInfoStat(
                            value = "${bounty.radiusMeters.toInt()}m",
                            label = "Radius",
                        )
                        Box(
                            modifier = Modifier.width(1.dp).height(32.dp).background(DividerColor),
                        )
                        BountyInfoStat(
                            value = bounty.neighborhood ?: bounty.city,
                            label = "Location",
                        )
                    }
                }
            }

            // Description
            bounty.description?.let { desc ->
                item {
                    SectionHeader(
                        title = "Description",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Before photos from spot report
            if (bounty.mediaUrls.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Before Photos (${bounty.mediaUrls.size})",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(bounty.mediaUrls) { url ->
                            NetworkImage(
                                url = url,
                                contentDescription = "Bounty photo",
                                modifier = Modifier.size(120.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // AI analysis info
            bounty.aiAnalysis?.let { analysis ->
                item {
                    SectionHeader(
                        title = "AI Analysis",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GccCard(
                        accentColor = CardAccentPurple,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        analysis.estimatedWeightLbs?.let { weight ->
                            Text(
                                "Estimated weight: ${weight.toInt()} lbs",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (analysis.trashTypes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Types: ${analysis.trashTypes.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        analysis.severity?.let { severity ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Severity: $severity",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // First-refusal timer
            if (bounty.status == BountyStatus.FIRST_REFUSAL && bounty.firstRefusalExpiresAt != null) {
                item {
                    GccCard(
                        accentColor = CardAccentGold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            "First Refusal Active",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = GccGold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "The observer who reported this spot has priority to accept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Accept Bounty button
            if (bounty.status == BountyStatus.OPEN) {
                item {
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
                        text = "Accept Bounty",
                        onClick = { viewModel.claimBounty(bountyId) },
                        variant = GccButtonVariant.PRIMARY,
                        trailingText = payoutString,
                        enabled = !uiState.isClaimingBounty,
                        isLoading = uiState.isClaimingBounty,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Floating back button
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
                Text(
                    "\u2190",
                    style = MaterialTheme.typography.titleMedium,
                    color = GccGreenDark,
                )
            }
        }
    }
}

@Composable
private fun BountyInfoStat(
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

@Composable
private fun BountyDetailStatusChip(status: BountyStatus, modifier: Modifier = Modifier) {
    val (text, dotColor, bgColor) = when (status) {
        BountyStatus.OPEN -> Triple("Open", GccAvailable, ChipBgAvailable)
        BountyStatus.FIRST_REFUSAL -> Triple("First Refusal", GccClaimed, ChipBgClaimed)
        BountyStatus.CLAIMED -> Triple("Claimed", GccClaimed, ChipBgClaimed)
        BountyStatus.IN_PROGRESS -> Triple("In Progress", GccInProgress, ChipBgInProgress)
        BountyStatus.COMPLETED -> Triple("Completed", GccCompleted, ChipBgCompleted)
        BountyStatus.EXPIRED -> Triple("Expired", TextMuted, SurfaceMuted)
        BountyStatus.CANCELLED -> Triple("Cancelled", GccError, ChipBgDisputed)
    }
    StatusChip(text = text, dotColor = dotColor, backgroundColor = bgColor, modifier = modifier)
}
