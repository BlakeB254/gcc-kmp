package com.gcc.admin.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DetailPanel
import com.gcc.admin.ui.components.StatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.shared.model.*

@Composable
fun AdminMapInfoPanel(
    state: AdminMapState,
    blocks: List<Block>,
    serviceAreas: List<ServiceArea>,
    spots: List<CommunityReport>,
    bounties: List<Bounty>,
    onClose: () -> Unit,
    onViewInList: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasSelection = state.selectedBlockId != null ||
            state.selectedServiceAreaId != null ||
            state.selectedSpotId != null ||
            state.selectedBountyId != null

    AnimatedVisibility(
        visible = hasSelection,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight(),
            color = AdminSurface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = AdminOnSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Content based on selection
                when {
                    state.selectedBlockId != null -> {
                        val block = blocks.find { it.id == state.selectedBlockId }
                        if (block != null) BlockDetail(block)
                    }
                    state.selectedServiceAreaId != null -> {
                        val sa = serviceAreas.find { it.id == state.selectedServiceAreaId }
                        if (sa != null) ServiceAreaDetail(sa)
                    }
                    state.selectedSpotId != null -> {
                        val spot = spots.find { it.id == state.selectedSpotId }
                        if (spot != null) SpotDetail(spot)
                    }
                    state.selectedBountyId != null -> {
                        val bounty = bounties.find { it.id == state.selectedBountyId }
                        if (bounty != null) BountyDetail(bounty)
                    }
                }

                // View in List button
                if (onViewInList != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(
                        onClick = onViewInList,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("View in List", color = AdminPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockDetail(block: Block) {
    Text(
        text = "Block: ${block.code}",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = AdminOnSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    StatusBadge(
        text = block.status.name.replace("_", " "),
        color = when (block.status) {
            BlockStatus.AVAILABLE -> StatusAvailable
            BlockStatus.FUNDED -> StatusCompleted
            BlockStatus.IN_PROGRESS -> StatusInProgress
            BlockStatus.COMPLETED -> AdminOnSurfaceVariant
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    val goal = block.fundingGoal ?: 0
    val current = block.fundingCurrent

    DetailPanel(
        title = "Block Info",
        items = listOfNotNull(
            "Name" to block.name,
            "Code" to block.code,
            "Status" to block.status.name.replace("_", " "),
            if (goal > 0) "Funding" to "\$${current / 100} / \$${goal / 100}" else null,
            block.sponsorId?.let { "Sponsor ID" to it.toString() },
        ),
    )

    // Funding progress bar
    if (goal > 0) {
        Spacer(modifier = Modifier.height(12.dp))
        val progress = (current.toFloat() / goal).coerceIn(0f, 1f)
        Text(
            text = "Funding Progress",
            style = MaterialTheme.typography.labelMedium,
            color = AdminOnSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = AdminPrimary,
            trackColor = AdminOutlineVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = AdminOnSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceAreaDetail(sa: ServiceArea) {
    Text(
        text = "Service Area",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = AdminOnSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    StatusBadge(
        text = sa.status.name.replace("_", " "),
        color = when (sa.status) {
            ServiceAreaStatus.ACTIVE -> StatusAvailable
            ServiceAreaStatus.PILOT -> StatusInProgress
            ServiceAreaStatus.PLANNING -> StatusPending
            ServiceAreaStatus.PAUSED -> StatusWarning
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    DetailPanel(
        title = "Area Info",
        items = listOf(
            "Name" to sa.name,
            "City" to sa.city,
            "State" to sa.state,
            "Status" to sa.status.name.replace("_", " "),
            "Total Blocks" to sa.totalBlocks.toString(),
            "Active Workers" to sa.activeWorkers.toString(),
        ),
    )
}

@Composable
private fun SpotDetail(spot: CommunityReport) {
    Text(
        text = "Spot Report",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = AdminOnSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    StatusBadge(
        text = spot.status.name.replace("_", " "),
        color = when (spot.status) {
            CommunityReportStatus.PENDING -> StatusPending
            CommunityReportStatus.VERIFIED -> StatusAvailable
            CommunityReportStatus.RESOLVED -> StatusCompleted
            CommunityReportStatus.ACKNOWLEDGED -> StatusInProgress
            CommunityReportStatus.REJECTED -> StatusError
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    DetailPanel(
        title = "Report Details",
        items = listOfNotNull(
            "Type" to spot.type.name.replace("_", " "),
            spot.description?.let { "Description" to it },
            "Latitude" to "%.6f".format(spot.coordinates.lat),
            "Longitude" to "%.6f".format(spot.coordinates.lng),
            "Status" to spot.status.name.replace("_", " "),
            "Upvotes" to spot.upvotes.toString(),
            "Created" to spot.createdAt.toString(),
        ),
    )
}

@Composable
private fun BountyDetail(bounty: Bounty) {
    Text(
        text = "Bounty",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = AdminOnSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    StatusBadge(
        text = bounty.status.name.replace("_", " "),
        color = when (bounty.status) {
            BountyStatus.OPEN -> StatusAvailable
            BountyStatus.FIRST_REFUSAL -> StatusWarning
            BountyStatus.CLAIMED -> StatusInProgress
            BountyStatus.IN_PROGRESS -> StatusInProgress
            BountyStatus.COMPLETED -> StatusCompleted
            BountyStatus.EXPIRED -> AdminOnSurfaceVariant
            BountyStatus.CANCELLED -> StatusError
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    DetailPanel(
        title = "Bounty Details",
        items = listOfNotNull(
            "Title" to bounty.title,
            "Amount" to "\$${bounty.amount / 100}.${"%02d".format(bounty.amount % 100)}",
            "Status" to bounty.status.name.replace("_", " "),
            "Latitude" to "%.6f".format(bounty.locationLat),
            "Longitude" to "%.6f".format(bounty.locationLng),
            "Radius" to "${bounty.radiusMeters.toInt()}m",
            bounty.claimedBy?.let { "Claimed By" to "Worker #$it" },
            bounty.expiresAt?.let { "Expires At" to it.toString() },
            "Pledges" to bounty.pledgeCount.toString(),
        ),
    )
}
