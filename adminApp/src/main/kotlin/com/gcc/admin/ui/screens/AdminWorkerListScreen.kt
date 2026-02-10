package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.WorkerManagementViewModel
import com.gcc.shared.model.VerificationStatus
import org.koin.compose.koinInject

/**
 * List screen showing all workers with search and status filtering.
 */
@Composable
fun AdminWorkerListScreen(
    onWorkerClick: (Int) -> Unit,
    viewModel: WorkerManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Workers",
        subtitle = "Management",
        scrollable = false,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Search bar
            SearchBar(
                query = state.searchQuery,
                onQueryChange = { viewModel.searchWorkers(it) },
                placeholder = "Search workers by name or email...",
                modifier = Modifier.padding(bottom = 16.dp),
            )

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
                VerificationStatus.entries.forEach { status ->
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
                    items = state.workers,
                    columns = listOf(
                        TableColumn(header = "ID", weight = 0.5f) { worker ->
                            Text(
                                text = worker.id.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Name", weight = 1.5f) { worker ->
                            Text(
                                text = worker.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Email", weight = 1.5f) { worker ->
                            Text(
                                text = worker.email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Status", weight = 1f) { worker ->
                            StatusBadge(
                                text = worker.verification.status.name,
                                color = when (worker.verification.status) {
                                    VerificationStatus.APPROVED -> StatusAvailable
                                    VerificationStatus.PENDING -> StatusPending
                                    VerificationStatus.REJECTED -> StatusError
                                    VerificationStatus.NEEDS_INFO -> StatusWarning
                                },
                            )
                        },
                        TableColumn(header = "Role", weight = 0.8f) { worker ->
                            Text(
                                text = worker.role,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Joined", weight = 1f) { worker ->
                            Text(
                                text = worker.createdAt.toString().take(10),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                    onRowClick = { worker -> onWorkerClick(worker.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
