package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.JobManagementViewModel
import com.gcc.shared.model.JobStatus
import org.koin.compose.koinInject

/**
 * List screen showing all jobs with status filter chips.
 */
@Composable
fun AdminJobListScreen(
    onJobClick: (Int) -> Unit,
    viewModel: JobManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Jobs",
        subtitle = "Management",
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
                    JobStatus.AVAILABLE,
                    JobStatus.IN_PROGRESS,
                    JobStatus.COMPLETED,
                    JobStatus.DISPUTED,
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
                    items = state.jobs,
                    columns = listOf(
                        TableColumn(header = "ID", weight = 0.5f) { job ->
                            Text(
                                text = job.id.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Route", weight = 1f) { job ->
                            Text(
                                text = job.routeId?.toString() ?: "N/A",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Worker", weight = 1f) { job ->
                            Text(
                                text = job.workerId?.toString() ?: "Unassigned",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Status", weight = 1f) { job ->
                            StatusBadge(
                                text = job.status.name,
                                color = when (job.status) {
                                    JobStatus.AVAILABLE -> StatusAvailable
                                    JobStatus.CLAIMED -> StatusPending
                                    JobStatus.IN_PROGRESS -> StatusInProgress
                                    JobStatus.PENDING_REVIEW -> StatusWarning
                                    JobStatus.COMPLETED -> StatusCompleted
                                    JobStatus.DISPUTED -> StatusError
                                },
                            )
                        },
                        TableColumn(header = "Pay", weight = 0.8f) { job ->
                            val payout = job.payoutAmount
                            Text(
                                text = if (payout != null) "$${payout / 100}" else "N/A",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Date", weight = 1f) { job ->
                            Text(
                                text = job.createdAt.toString().take(10),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                    onRowClick = { job -> onJobClick(job.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
