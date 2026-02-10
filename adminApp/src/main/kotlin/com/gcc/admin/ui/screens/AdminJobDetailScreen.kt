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
import com.gcc.admin.viewmodel.JobManagementViewModel
import com.gcc.shared.model.JobStatus
import org.koin.compose.koinInject

/**
 * Detail screen for a single job with force-status action buttons.
 */
@Composable
fun AdminJobDetailScreen(
    jobId: Int,
    onBack: () -> Unit,
    viewModel: JobManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showForceDialog by remember { mutableStateOf(false) }
    var forceTargetStatus by remember { mutableStateOf<JobStatus?>(null) }

    LaunchedEffect(jobId) {
        viewModel.selectJob(jobId)
    }

    AdminScaffold(
        title = "Job Detail",
        subtitle = "Jobs",
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
        val job = state.selectedJob
        if (job == null) {
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
                    // Job Info
                    DetailPanel(
                        title = "Job Information",
                        items = listOf(
                            "ID" to job.id.toString(),
                            "Route ID" to (job.routeId?.toString() ?: "N/A"),
                            "Block ID" to (job.blockId?.toString() ?: "N/A"),
                            "Worker ID" to (job.workerId?.toString() ?: "Unassigned"),
                            "Status" to job.status.name,
                            "Payout" to (job.payoutAmount?.let { "$${it / 100}" } ?: "N/A"),
                            "Payout Status" to (job.payoutStatus?.name ?: "N/A"),
                            "Funding Source" to (job.fundingSource ?: "N/A"),
                            "Bounty ID" to (job.bountyId?.toString() ?: "N/A"),
                            "Sponsor ID" to (job.sponsorId?.toString() ?: "N/A"),
                            "Created" to job.createdAt.toString().take(10),
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    // Timing Info
                    DetailPanel(
                        title = "Timing",
                        items = listOf(
                            "Claimed At" to (job.timing.claimedAt?.toString()?.take(19) ?: "N/A"),
                            "Started At" to (job.timing.startedAt?.toString()?.take(19) ?: "N/A"),
                            "Completed At" to (job.timing.completedAt?.toString()?.take(19) ?: "N/A"),
                            "Duration (min)" to (job.timing.durationMinutes?.toString() ?: "N/A"),
                            "Rating" to (job.rating?.toString() ?: "N/A"),
                            "Review Notes" to (job.reviewNotes ?: "None"),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Force Status Actions
                Text(
                    text = "Force Status Change",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JobStatus.entries.forEach { status ->
                        if (status != job.status) {
                            OutlinedButton(
                                onClick = {
                                    forceTargetStatus = status
                                    showForceDialog = true
                                },
                                enabled = !state.isLoading,
                            ) {
                                Text(status.name)
                            }
                        }
                    }
                }
            }
        }
    }

    // Force status confirmation dialog
    if (showForceDialog && forceTargetStatus != null) {
        ConfirmDialog(
            title = "Force Status Change",
            message = "Force job #$jobId to status ${forceTargetStatus?.name}? This is an admin override.",
            confirmText = "Force Change",
            onConfirm = {
                forceTargetStatus?.let { status ->
                    viewModel.forceJobStatus(jobId, status, "Admin force status change")
                }
                showForceDialog = false
                forceTargetStatus = null
            },
            onDismiss = {
                showForceDialog = false
                forceTargetStatus = null
            },
        )
    }
}
