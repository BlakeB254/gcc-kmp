package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DetailRow
import com.gcc.admin.ui.components.DisputeStatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.DisputeViewModel
import com.gcc.shared.model.DisputeResolution
import com.gcc.shared.model.DisputeStatus
import com.gcc.shared.model.DisputeType
import com.gcc.shared.model.ResolveDisputeRequest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminDisputeDetailScreen(disputeId: Int, onBack: () -> Unit) {
    val viewModel = koinInject<DisputeViewModel>()
    val disputes by viewModel.disputes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val dispute = disputes.find { it.id == disputeId }

    // Resolution form state
    var selectedResolution by remember { mutableStateOf(DisputeResolution.DISMISSED) }
    var resolutionNotes by remember { mutableStateOf("") }
    var refundAmount by remember { mutableStateOf("") }
    var payoutAmount by remember { mutableStateOf("") }
    var resolutionDropdownExpanded by remember { mutableStateOf(false) }

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
            Text("Dispute #$disputeId", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        if (dispute == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Dispute not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AdminOnSurfaceVariant,
                )
            }
            return
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = AdminTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
        ) {
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
                        DisputeStatusBadge(dispute.status)
                    }
                    Spacer(Modifier.height(AdminTheme.spacing.xs))
                    DetailRow("Job ID", "#${dispute.jobId}")
                    DetailRow("Type", disputeTypeDisplayName(dispute.type))
                    DetailRow("Description", dispute.description)
                }
            }

            // -- Evidence URLs --------------------------------------------------
            if (dispute.evidence.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = AdminTheme.elevation.card,
                ) {
                    Column(
                        modifier = Modifier.padding(AdminTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                    ) {
                        Text("Evidence", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(AdminTheme.spacing.xs))
                        dispute.evidence.forEachIndexed { index, url ->
                            Text(
                                text = "${index + 1}. $url",
                                style = MaterialTheme.typography.bodySmall,
                                color = AdminInfo,
                            )
                        }
                    }
                }
            }

            // -- Reporter / Respondent ------------------------------------------
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                ) {
                    Text("Parties", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.xs))
                    DetailRow(
                        "Reporter",
                        "${dispute.reporterType.name.lowercase().replaceFirstChar { it.uppercase() }} #${dispute.reporterId}",
                    )
                    DetailRow(
                        "Respondent",
                        "${dispute.respondentType.name.lowercase().replaceFirstChar { it.uppercase() }} #${dispute.respondentId}",
                    )
                }
            }

            // -- Resolution Section ---------------------------------------------
            if (dispute.status == DisputeStatus.RESOLVED) {
                // Show existing resolution
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = AdminTheme.elevation.card,
                ) {
                    Column(
                        modifier = Modifier.padding(AdminTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                    ) {
                        Text("Resolution", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(AdminTheme.spacing.xs))
                        DetailRow(
                            "Type",
                            dispute.resolution?.name?.lowercase()
                                ?.replace("_", " ")
                                ?.replaceFirstChar { it.uppercase() }
                                ?: "N/A",
                        )
                        DetailRow("Notes", dispute.resolutionNotes ?: "N/A")
                        DetailRow("Resolved By", dispute.resolvedBy?.toString() ?: "N/A")
                        dispute.resolvedAt?.let { resolvedAt ->
                            val local = resolvedAt.toLocalDateTime(TimeZone.currentSystemDefault())
                            DetailRow(
                                "Resolved At",
                                "${local.monthNumber}/${local.dayOfMonth}/${local.year} ${local.hour}:${local.minute.toString().padStart(2, '0')}",
                            )
                        }
                    }
                }
            } else {
                // Show resolution form
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = AdminTheme.elevation.card,
                ) {
                    Column(
                        modifier = Modifier.padding(AdminTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                    ) {
                        Text("Resolve Dispute", style = MaterialTheme.typography.titleMedium)

                        // Resolution type dropdown
                        Box {
                            OutlinedButton(onClick = { resolutionDropdownExpanded = true }) {
                                Text(
                                    text = selectedResolution.name.lowercase()
                                        .replace("_", " ")
                                        .replaceFirstChar { it.uppercase() },
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = resolutionDropdownExpanded,
                                onDismissRequest = { resolutionDropdownExpanded = false },
                            ) {
                                DisputeResolution.entries.forEach { resolution ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                resolution.name.lowercase()
                                                    .replace("_", " ")
                                                    .replaceFirstChar { it.uppercase() },
                                            )
                                        },
                                        onClick = {
                                            selectedResolution = resolution
                                            resolutionDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Notes text field
                        OutlinedTextField(
                            value = resolutionNotes,
                            onValueChange = { resolutionNotes = it },
                            label = { Text("Resolution Notes") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )

                        // Optional amount fields
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                        ) {
                            OutlinedTextField(
                                value = refundAmount,
                                onValueChange = { refundAmount = it.filter { c -> c.isDigit() } },
                                label = { Text("Refund Amount (cents)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = payoutAmount,
                                onValueChange = { payoutAmount = it.filter { c -> c.isDigit() } },
                                label = { Text("Payout Amount (cents)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }

                        // Submit button
                        Button(
                            onClick = {
                                viewModel.resolveDispute(
                                    disputeId,
                                    ResolveDisputeRequest(
                                        resolution = selectedResolution,
                                        notes = resolutionNotes,
                                        refundAmount = refundAmount.toIntOrNull(),
                                        payoutAmount = payoutAmount.toIntOrNull(),
                                    ),
                                )
                                onBack()
                            },
                            enabled = resolutionNotes.isNotBlank(),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(AdminTheme.spacing.xs))
                            Text("Submit Resolution")
                        }
                    }
                }
            }
        }
    }
}

private fun disputeTypeDisplayName(type: DisputeType): String {
    return when (type) {
        DisputeType.QUALITY -> "Quality"
        DisputeType.INCOMPLETE -> "Incomplete"
        DisputeType.NO_SHOW -> "No Show"
        DisputeType.PAYMENT -> "Payment"
        DisputeType.MISCONDUCT -> "Misconduct"
        DisputeType.OTHER -> "Other"
    }
}
