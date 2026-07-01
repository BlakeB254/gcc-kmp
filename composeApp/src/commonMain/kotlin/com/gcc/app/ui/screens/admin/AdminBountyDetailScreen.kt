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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.PhotoStrip
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.shared.model.BountyStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminBountyDetailScreen(
    bountyId: Int,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val bounty = uiState.bounties.find { it.id == bountyId }

    if (bounty == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Bounty not found")
        }
        return
    }

    val statusColor = when (bounty.status) {
        BountyStatus.OPEN -> GccGreen
        BountyStatus.FIRST_REFUSAL, BountyStatus.CLAIMED -> GccSky
        BountyStatus.IN_PROGRESS -> GccBlue
        BountyStatus.COMPLETED -> GccGreen
        BountyStatus.EXPIRED, BountyStatus.CANCELLED -> GccError
    }

    val mapZoom = MapStyles.ZOOM_ACTIVE_JOB

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
                            "←",
                            style = MaterialTheme.typography.titleMedium,
                            color = GccGreenDark,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Bounty #$bountyId",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = statusColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        bounty.status.name.lowercase().replace('_', ' '),
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
                        centerLat = bounty.locationLat,
                        centerLng = bounty.locationLng,
                        zoom = mapZoom,
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "${bounty.radiusMeters.toInt()}m radius",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = GccSky,
                        )
                    }
                }
            }

            // Title & amount
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            bounty.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        bounty.description?.let { desc ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = GccGreen.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "$${bounty.amount}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = GccGreen,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // Location info
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "Community Area",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            bounty.communityArea ?: bounty.neighborhood ?: bounty.city.ifBlank { "Unknown" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Coordinates",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${formatCoord(bounty.locationLat)}, ${formatCoord(bounty.locationLng)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Pledge & poster info
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "Pledges",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${bounty.pledgeCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Poster",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${bounty.posterType.name.lowercase()} #${bounty.posterId}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Claimed by
            bounty.claimedBy?.let { workerId ->
                item {
                    Text(
                        "Claimed By",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Worker #$workerId",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = GccBlue,
                    )
                }
            }

            // Linked job
            bounty.jobId?.let { jobId ->
                item {
                    Text(
                        "Linked Job",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Job #$jobId", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Dates
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "Created",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatInstant(bounty.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    bounty.expiresAt?.let { exp ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Expires",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatInstant(exp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // Media / cover images
            if (bounty.mediaUrls.isNotEmpty()) {
                item {
                    Text(
                        "Photos (${bounty.mediaUrls.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    PhotoStrip(urls = bounty.mediaUrls)
                }
            }

            // Completion media
            bounty.completionMediaUrls?.takeIf { it.isNotEmpty() }?.let { urls ->
                item {
                    Text(
                        "Completion Photos (${urls.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    PhotoStrip(urls = urls)
                }
            }

            // Read-only note — no server-side admin actions exist for bounties yet
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        "ℹ️ This is a read-only admin view. Bounty management actions (cancel, close) are not yet available from this panel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

private fun formatInstant(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.year}-${local.monthNumber.toString().padStart(2, '0')}-${local.dayOfMonth.toString().padStart(2, '0')}"
}
