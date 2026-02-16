package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DetailRow
import com.gcc.admin.ui.components.JobStatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.JobManagementViewModel
import com.gcc.shared.model.ForceJobStatusRequest
import com.gcc.shared.model.JobStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminJobDetailScreen(jobId: Int, onBack: () -> Unit) {
    val viewModel = koinInject<JobManagementViewModel>()
    val jobs by viewModel.jobs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val job = remember(jobs, jobId) {
        jobs.find { it.id == jobId }
    }

    var selectedStatus by remember { mutableStateOf<JobStatus?>(null) }
    var statusExpanded by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // ── Header ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Job #$jobId", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                Spacer(Modifier.width(AdminTheme.spacing.sm))
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        if (job == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text("Job not found", style = MaterialTheme.typography.bodyLarge, color = AdminOnSurfaceVariant)
                }
            }
        } else {
            // ── Job Info ────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                ) {
                    Text("Job Information", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.sm))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xl),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                        ) {
                            DetailRow("Route ID", job.routeId?.toString() ?: "--")
                            DetailRow("Block ID", job.blockId?.toString() ?: "--")
                            DetailRow("Worker ID", job.workerId?.toString() ?: "Unassigned")
                            DetailRow("Bounty ID", job.bountyId?.toString() ?: "--")
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                            ) {
                                Text("Status:", style = MaterialTheme.typography.bodySmall, color = AdminOnSurfaceVariant)
                                JobStatusBadge(job.status)
                            }
                            DetailRow("Payout", job.payoutAmount?.let { "$${it / 100}" } ?: "--")
                            DetailRow("Funding", job.fundingSource ?: "--")
                            val createdDate = job.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
                            DetailRow(
                                "Created",
                                "${createdDate.monthNumber}/${createdDate.dayOfMonth}/${createdDate.year}",
                            )
                        }
                    }

                    // Timing details
                    Spacer(Modifier.height(AdminTheme.spacing.sm))
                    Text("Timing", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xl),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                        ) {
                            DetailRow(
                                "Claimed",
                                job.timing.claimedAt?.let {
                                    val dt = it.toLocalDateTime(TimeZone.currentSystemDefault())
                                    "${dt.monthNumber}/${dt.dayOfMonth}/${dt.year} ${dt.hour}:${dt.minute.toString().padStart(2, '0')}"
                                } ?: "--",
                            )
                            DetailRow(
                                "Started",
                                job.timing.startedAt?.let {
                                    val dt = it.toLocalDateTime(TimeZone.currentSystemDefault())
                                    "${dt.monthNumber}/${dt.dayOfMonth}/${dt.year} ${dt.hour}:${dt.minute.toString().padStart(2, '0')}"
                                } ?: "--",
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                        ) {
                            DetailRow(
                                "Completed",
                                job.timing.completedAt?.let {
                                    val dt = it.toLocalDateTime(TimeZone.currentSystemDefault())
                                    "${dt.monthNumber}/${dt.dayOfMonth}/${dt.year} ${dt.hour}:${dt.minute.toString().padStart(2, '0')}"
                                } ?: "--",
                            )
                            DetailRow("Duration", job.timing.durationMinutes?.let { "$it min" } ?: "--")
                        }
                    }
                }
            }

            // ── Force Status Section ────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    Text("Force Status Change", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Override the job status manually. This should only be used in exceptional cases.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AdminOnSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Status dropdown
                        Box(modifier = Modifier.width(200.dp)) {
                            OutlinedButton(
                                onClick = { statusExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    selectedStatus?.name?.lowercase()?.replace("_", " ")?.replaceFirstChar { it.uppercase() }
                                        ?: "Select Status",
                                )
                            }
                            DropdownMenu(
                                expanded = statusExpanded,
                                onDismissRequest = { statusExpanded = false },
                            ) {
                                JobStatus.entries.forEach { status ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(status.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() })
                                        },
                                        onClick = {
                                            selectedStatus = status
                                            statusExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Reason field
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it },
                            label = { Text("Reason") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )

                        // Submit button
                        Button(
                            onClick = {
                                val status = selectedStatus ?: return@Button
                                viewModel.forceStatus(
                                    jobId = job.id,
                                    request = ForceJobStatusRequest(status = status, reason = reason),
                                )
                                selectedStatus = null
                                reason = ""
                            },
                            enabled = selectedStatus != null && reason.isNotBlank() && !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = AdminWarning),
                        ) {
                            Text("Submit")
                        }
                    }
                }
            }
        }
    }
}
