package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.BountyManagementViewModel
import com.gcc.shared.model.BountyStatus
import org.koin.compose.koinInject

/**
 * List screen showing all bounties with status filtering.
 */
@Composable
fun AdminBountyListScreen(
    onBountyClick: (Int) -> Unit,
    viewModel: BountyManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Bounties",
        subtitle = "Operations",
        scrollable = false,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Status filter chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                FilterChip(
                    selected = state.statusFilter == null,
                    onClick = { viewModel.setStatusFilter(null) },
                    label = { Text("All") },
                )
                listOf(
                    BountyStatus.OPEN,
                    BountyStatus.CLAIMED,
                    BountyStatus.IN_PROGRESS,
                    BountyStatus.COMPLETED,
                    BountyStatus.EXPIRED,
                ).forEach { status ->
                    FilterChip(
                        selected = state.statusFilter == status,
                        onClick = { viewModel.setStatusFilter(status) },
                        label = { Text(status.name) },
                    )
                }
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                DataTable(
                    items = state.bounties,
                    columns = listOf(
                        TableColumn(header = "ID", weight = 0.5f) { bounty ->
                            Text(
                                text = bounty.id.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Title", weight = 1.5f) { bounty ->
                            Text(
                                text = bounty.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Amount", weight = 0.8f) { bounty ->
                            Text(
                                text = "$" + (bounty.amount / 100).toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        TableColumn(header = "Status", weight = 1f) { bounty ->
                            StatusBadge(
                                text = bounty.status.name,
                                color = when (bounty.status) {
                                    BountyStatus.OPEN -> StatusAvailable
                                    BountyStatus.FIRST_REFUSAL -> StatusWarning
                                    BountyStatus.CLAIMED -> StatusInProgress
                                    BountyStatus.IN_PROGRESS -> StatusInProgress
                                    BountyStatus.COMPLETED -> StatusCompleted
                                    BountyStatus.EXPIRED -> StatusError
                                    BountyStatus.CANCELLED -> StatusError
                                },
                            )
                        },
                        TableColumn(header = "Pledges", weight = 0.6f) { bounty ->
                            Text(
                                text = bounty.pledgeCount.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Expires", weight = 1f) { bounty ->
                            Text(
                                text = bounty.expiresAt?.toString()?.take(16) ?: "N/A",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                    onRowClick = { bounty -> onBountyClick(bounty.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
