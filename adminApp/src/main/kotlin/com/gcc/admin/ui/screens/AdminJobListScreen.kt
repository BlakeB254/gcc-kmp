package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.components.JobStatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.JobManagementViewModel
import com.gcc.shared.model.JobStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminJobListScreen(onJobClick: (Int) -> Unit) {
    val viewModel = koinInject<JobManagementViewModel>()
    val jobs by viewModel.jobs.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val filterOptions = listOf(
        null to "All",
        "available" to "Available",
        "claimed" to "Claimed",
        "in_progress" to "In Progress",
        "completed" to "Completed",
        "disputed" to "Disputed",
    )

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
            Text("Jobs", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // ── Status Filter Chips ─────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
        ) {
            filterOptions.forEach { (value, label) ->
                val selected = statusFilter == value
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.onStatusFilterChange(value) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AdminPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = AdminPrimary,
                    ),
                )
            }
        }

        // ── Data Table ──────────────────────────────────────────────
        DataTable(
            items = jobs,
            columns = listOf(
                DataColumn(header = "ID", weight = 0.5f) { job ->
                    Text("#${job.id}", style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Route", weight = 0.7f) { job ->
                    Text(
                        text = job.routeId?.toString() ?: "--",
                        style = MaterialTheme.typography.bodySmall,
                        color = AdminOnSurfaceVariant,
                    )
                },
                DataColumn(header = "Worker", weight = 0.7f) { job ->
                    Text(
                        text = job.workerId?.toString() ?: "Unassigned",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.workerId != null) MaterialTheme.colorScheme.onSurface else AdminOnSurfaceVariant,
                    )
                },
                DataColumn(header = "Status", weight = 0.8f) { job ->
                    JobStatusBadge(job.status)
                },
                DataColumn(header = "Payout", weight = 0.7f) { job ->
                    Text(
                        text = job.payoutAmount?.let { "$${it / 100}" } ?: "--",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                DataColumn(header = "Created", weight = 1f) { job ->
                    val localTime = job.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
                    Text(
                        text = "${localTime.monthNumber}/${localTime.dayOfMonth}/${localTime.year}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AdminOnSurfaceVariant,
                    )
                },
            ),
            onRowClick = { job -> onJobClick(job.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
