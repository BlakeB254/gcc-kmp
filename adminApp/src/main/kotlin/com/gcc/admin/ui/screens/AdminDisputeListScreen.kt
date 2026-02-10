package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.DisputeViewModel
import com.gcc.shared.model.DisputeStatus
import org.koin.compose.koinInject

/**
 * List screen showing all disputes with status filter chips and count badges.
 */
@Composable
fun AdminDisputeListScreen(
    onDisputeClick: (Int) -> Unit,
    viewModel: DisputeViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Disputes",
        subtitle = "Operations",
        scrollable = false,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Status filter chips with counts
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                FilterChip(
                    selected = state.statusFilter == null,
                    onClick = { viewModel.setStatusFilter(null) },
                    label = {
                        Text("All (${state.disputes.size})")
                    },
                )
                DisputeStatus.entries.forEach { status ->
                    val count = state.counts[status] ?: 0
                    FilterChip(
                        selected = state.statusFilter == status,
                        onClick = { viewModel.setStatusFilter(status) },
                        label = {
                            Text("${status.name} ($count)")
                        },
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
                    items = state.disputes,
                    columns = listOf(
                        TableColumn(header = "ID", weight = 0.5f) { dispute ->
                            Text(
                                text = dispute.id.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Type", weight = 1f) { dispute ->
                            Text(
                                text = dispute.type.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Reporter", weight = 1f) { dispute ->
                            Text(
                                text = dispute.reporterType.name + " #" + dispute.reporterId.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Respondent", weight = 1f) { dispute ->
                            Text(
                                text = dispute.respondentType.name + " #" + dispute.respondentId.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Status", weight = 0.8f) { dispute ->
                            StatusBadge(
                                text = dispute.status.name,
                                color = when (dispute.status) {
                                    DisputeStatus.OPEN -> StatusError
                                    DisputeStatus.UNDER_REVIEW -> StatusWarning
                                    DisputeStatus.RESOLVED -> StatusAvailable
                                    DisputeStatus.ESCALATED -> StatusPending
                                },
                            )
                        },
                        TableColumn(header = "Date", weight = 1f) { dispute ->
                            Text(
                                text = dispute.createdAt.toString().take(10),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                    onRowClick = { dispute -> onDisputeClick(dispute.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
