package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.BountyManagementViewModel
import org.koin.compose.koinInject

/**
 * Detail screen for a single bounty with admin force actions.
 */
@Composable
fun AdminBountyDetailScreen(
    bountyId: Int,
    onBack: () -> Unit,
    viewModel: BountyManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showExpireDialog by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bountyId) {
        viewModel.selectBounty(bountyId)
    }

    AdminScaffold(
        title = "Bounty Detail",
        subtitle = "Bounties",
        actions = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        val bounty = state.selectedBounty
        if (bounty == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Column(modifier = Modifier.padding(24.dp)) {
                if (state.error != null) {
                    Text(
                        text = state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Bounty Info
                    DetailPanel(
                        title = "Bounty Information",
                        items = listOf(
                            "ID" to bounty.id.toString(),
                            "Title" to bounty.title,
                            "Description" to (bounty.description ?: "N/A"),
                            "Amount" to ("$" + (bounty.amount / 100).toString()),
                            "Status" to bounty.status.name,
                            "Poster Type" to bounty.posterType.name,
                            "Poster ID" to bounty.posterId.toString(),
                            "Pledge Count" to bounty.pledgeCount.toString(),
                            "Created" to bounty.createdAt.toString().take(10),
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    // Location & Assignment
                    DetailPanel(
                        title = "Location & Assignment",
                        items = listOf(
                            "Latitude" to bounty.locationLat.toString(),
                            "Longitude" to bounty.locationLng.toString(),
                            "Radius" to (bounty.radiusMeters.toInt().toString() + " m"),
                            "City" to bounty.city,
                            "Community Area" to (bounty.communityArea ?: "N/A"),
                            "Neighborhood" to (bounty.neighborhood ?: "N/A"),
                            "Claimed By" to (bounty.claimedBy?.toString() ?: "Unclaimed"),
                            "Job ID" to (bounty.jobId?.toString() ?: "N/A"),
                            "Observer ID" to (bounty.observerId?.toString() ?: "N/A"),
                            "Expires At" to (bounty.expiresAt?.toString()?.take(16) ?: "N/A"),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showExpireDialog = true },
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusWarning,
                        ),
                    ) {
                        Text("Force Expire")
                    }

                    Button(
                        onClick = { showCloseDialog = true },
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Force Close")
                    }
                }
            }
        }
    }

    if (showExpireDialog) {
        ConfirmDialog(
            title = "Force Expire Bounty",
            message = "Are you sure you want to force expire bounty #$bountyId? Pledges will be refunded.",
            confirmText = "Force Expire",
            onConfirm = {
                viewModel.forceExpire(bountyId)
                showExpireDialog = false
            },
            onDismiss = { showExpireDialog = false },
        )
    }

    if (showCloseDialog) {
        ConfirmDialog(
            title = "Force Close Bounty",
            message = "Are you sure you want to force close bounty #$bountyId? This action cannot be undone.",
            confirmText = "Force Close",
            onConfirm = {
                viewModel.forceClose(bountyId)
                showCloseDialog = false
            },
            onDismiss = { showCloseDialog = false },
        )
    }
}
