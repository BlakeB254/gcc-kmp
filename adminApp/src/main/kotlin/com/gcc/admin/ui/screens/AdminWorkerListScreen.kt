package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.components.SearchBar
import com.gcc.admin.ui.components.VerificationStatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.WorkerManagementViewModel
import org.koin.compose.koinInject

@Composable
fun AdminWorkerListScreen(onWorkerClick: (Int) -> Unit) {
    val viewModel = koinInject<WorkerManagementViewModel>()
    val workers by viewModel.workers.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
    ) {
        // ── Header ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Workers", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        SearchBar(
            query = searchQuery,
            onQueryChange = viewModel::onSearchChange,
            placeholder = "Search workers by name or email...",
        )

        // ── Data Table ──────────────────────────────────────────────
        DataTable(
            items = workers,
            columns = listOf(
                DataColumn(header = "Name", weight = 1.5f) { worker ->
                    Text(worker.name, style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Email", weight = 1.5f) { worker ->
                    Text(worker.email, style = MaterialTheme.typography.bodySmall, color = AdminOnSurfaceVariant)
                },
                DataColumn(header = "Status", weight = 1f) { worker ->
                    VerificationStatusBadge(worker.verification.status)
                },
                DataColumn(header = "Jobs", weight = 0.7f) { worker ->
                    Text(worker.stats.jobsCompleted.toString(), style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Earnings", weight = 0.8f) { worker ->
                    Text("$${worker.stats.totalEarnings / 100}", style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Rating", weight = 0.7f) { worker ->
                    val rating = worker.stats.averageRating
                    Text(
                        text = if (rating != null) String.format("%.1f", rating) else "--",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                DataColumn(header = "Tier", weight = 0.8f) { worker ->
                    Text(
                        text = worker.earlyAdopterTier.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            ),
            onRowClick = { worker -> onWorkerClick(worker.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
