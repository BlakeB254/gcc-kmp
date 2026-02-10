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
import com.gcc.admin.viewmodel.DisputeViewModel
import com.gcc.shared.model.DisputeResolution
import com.gcc.shared.model.DisputeStatus
import com.gcc.shared.model.ResolveDisputeRequest
import org.koin.compose.koinInject

/**
 * Detail screen for a single dispute with review and resolution actions.
 */
@Composable
fun AdminDisputeDetailScreen(
    disputeId: Int,
    onBack: () -> Unit,
    viewModel: DisputeViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showResolveDialog by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf(DisputeResolution.DISMISSED) }
    var resolutionNotes by remember { mutableStateOf("") }

    LaunchedEffect(disputeId) {
        viewModel.selectDispute(disputeId)
    }

    AdminScaffold(
        title = "Dispute Detail",
        subtitle = "Disputes",
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
        val dispute = state.selectedDispute
        if (dispute == null) {
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
                    // Dispute Info
                    DetailPanel(
                        title = "Dispute Information",
                        items = listOf(
                            "ID" to dispute.id.toString(),
                            "Job ID" to dispute.jobId.toString(),
                            "Type" to dispute.type.name,
                            "Status" to dispute.status.name,
                            "Description" to dispute.description,
                            "Created" to dispute.createdAt.toString().take(10),
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        // Parties
                        DetailPanel(
                            title = "Parties",
                            items = listOf(
                                "Reporter" to (dispute.reporterType.name + " #" + dispute.reporterId.toString()),
                                "Respondent" to (dispute.respondentType.name + " #" + dispute.respondentId.toString()),
                                "Resolved By" to (dispute.resolvedBy?.toString() ?: "N/A"),
                                "Resolved At" to (dispute.resolvedAt?.toString()?.take(10) ?: "N/A"),
                            ),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Resolution
                        val resolution = dispute.resolution
                        if (resolution != null) {
                            DetailPanel(
                                title = "Resolution",
                                items = listOf(
                                    "Resolution" to resolution.name,
                                    "Notes" to (dispute.resolutionNotes ?: "N/A"),
                                ),
                            )
                        }
                    }
                }

                // Evidence
                if (dispute.evidence.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Evidence",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            dispute.evidence.forEachIndexed { index, url ->
                                Text(
                                    text = "${index + 1}. $url",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dispute.status == DisputeStatus.OPEN) {
                        Button(
                            onClick = { viewModel.startReview(disputeId) },
                            enabled = !state.isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusInProgress,
                            ),
                        ) {
                            Text("Start Review")
                        }
                    }

                    if (dispute.status == DisputeStatus.OPEN || dispute.status == DisputeStatus.UNDER_REVIEW) {
                        Button(
                            onClick = { showResolveDialog = true },
                            enabled = !state.isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusAvailable,
                            ),
                        ) {
                            Text("Resolve")
                        }
                    }
                }
            }
        }
    }

    // Resolve Dialog
    if (showResolveDialog) {
        AlertDialog(
            onDismissRequest = { showResolveDialog = false },
            title = { Text("Resolve Dispute") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Select resolution:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    DisputeResolution.entries.forEach { resolution ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = selectedResolution == resolution,
                                onClick = { selectedResolution = resolution },
                            )
                            Text(
                                text = resolution.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = resolutionNotes,
                        onValueChange = { resolutionNotes = it },
                        label = { Text("Resolution Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resolveDispute(
                            disputeId,
                            ResolveDisputeRequest(
                                resolution = selectedResolution,
                                notes = resolutionNotes,
                            ),
                        )
                        showResolveDialog = false
                    },
                ) {
                    Text("Resolve")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResolveDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}
