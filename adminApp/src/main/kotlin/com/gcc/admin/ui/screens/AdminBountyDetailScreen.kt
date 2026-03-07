package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.BountyStatusBadge
import com.gcc.admin.ui.components.ConfirmDialog
import com.gcc.admin.ui.components.DetailRow
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.BountyManagementViewModel
import com.gcc.shared.model.BountyStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminBountyDetailScreen(bountyId: Int, onBack: () -> Unit) {
    val viewModel = koinInject<BountyManagementViewModel>()
    val bounties by viewModel.bounties.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val bounty = bounties.find { it.id == bountyId }

    var showForceCloseDialog by remember { mutableStateOf(false) }
    var showForceReassignDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // -- Header with back button ----------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Bounty #$bountyId", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        if (bounty == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Bounty not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AdminOnSurfaceVariant,
                )
            }
            return
        }

        // -- Info Section ---------------------------------------------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    Text("Details", style = MaterialTheme.typography.titleMedium)
                    BountyStatusBadge(bounty.status)
                }
                Spacer(Modifier.height(AdminTheme.spacing.xs))
                DetailRow("Title", bounty.title)
                DetailRow("Description", bounty.description ?: "N/A")
                DetailRow("Amount", "$${"%,d".format(bounty.amount / 100)}")
                DetailRow("Location", bounty.communityArea ?: bounty.neighborhood ?: "N/A")
                DetailRow("Claimed By", bounty.claimedBy?.toString() ?: "Unclaimed")
                bounty.expiresAt?.let { expires ->
                    val local = expires.toLocalDateTime(TimeZone.currentSystemDefault())
                    DetailRow(
                        "Expires At",
                        "${local.monthNumber}/${local.dayOfMonth}/${local.year} ${local.hour}:${local.minute.toString().padStart(2, '0')}",
                    )
                }
                DetailRow("Pledges", bounty.pledgeCount.toString())
            }
        }

        // -- Claimer Info ---------------------------------------------------
        if (bounty.claimedBy != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                ) {
                    Text("Claimer Info", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.xs))
                    DetailRow("Claimed By (Worker ID)", bounty.claimedBy.toString())
                    bounty.jobId?.let { DetailRow("Linked Job ID", it.toString()) }
                }
            }
        }

        // -- Spotter Tip Info -----------------------------------------------
        val tipAmount = bounty.spotterTipAmount
        if (tipAmount != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                ) {
                    Text("Spotter Tip", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.xs))
                    DetailRow("Tip Amount", "$${"%,d".format(tipAmount / 100)}")
                    bounty.spotterTipPaidAt?.let { paidAt ->
                        val local = paidAt.toLocalDateTime(TimeZone.currentSystemDefault())
                        DetailRow(
                            "Paid At",
                            "${local.monthNumber}/${local.dayOfMonth}/${local.year} ${local.hour}:${local.minute.toString().padStart(2, '0')}",
                        )
                    } ?: DetailRow("Status", "Unpaid")
                }
            }
        }

        // -- Media URLs -----------------------------------------------------
        if (bounty.mediaUrls.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                ) {
                    Text("Media", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.xs))
                    bounty.mediaUrls.forEachIndexed { index, url ->
                        Text(
                            text = "${index + 1}. $url",
                            style = MaterialTheme.typography.bodySmall,
                            color = AdminInfo,
                        )
                    }
                }
            }
        }

        // -- Actions --------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            val isReassignable = bounty.status == BountyStatus.IN_PROGRESS || bounty.status == BountyStatus.CLAIMED

            if (isReassignable) {
                OutlinedButton(
                    onClick = { showForceReassignDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AdminWarning),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(AdminTheme.spacing.xs))
                    Text("Force Reassign")
                }
                Spacer(Modifier.width(AdminTheme.spacing.md))
            }

            Button(
                onClick = { showForceCloseDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AdminError),
            ) {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(AdminTheme.spacing.xs))
                Text("Force Close")
            }
        }
    }

    // -- Force Close Confirm Dialog -----------------------------------------
    if (showForceCloseDialog) {
        ConfirmDialog(
            title = "Force Close Bounty",
            message = "Are you sure you want to force close bounty #${bounty?.id}? This action cannot be undone.",
            confirmText = "Force Close",
            onConfirm = {
                bounty?.let { viewModel.forceClose(it.id) }
                onBack()
            },
            onDismiss = { showForceCloseDialog = false },
        )
    }

    // -- Force Reassign Confirm Dialog --------------------------------------
    if (showForceReassignDialog) {
        ConfirmDialog(
            title = "Force Reassign Bounty",
            message = "Are you sure you want to force reassign bounty #${bounty?.id}? " +
                "The current claimer will be removed and the bounty returned to OPEN status.",
            confirmText = "Reassign",
            onConfirm = {
                bounty?.let { viewModel.forceReassign(it.id) }
                showForceReassignDialog = false
            },
            onDismiss = { showForceReassignDialog = false },
        )
    }
}
