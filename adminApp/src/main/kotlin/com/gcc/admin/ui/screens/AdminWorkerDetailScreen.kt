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
import com.gcc.admin.viewmodel.WorkerManagementViewModel
import com.gcc.shared.model.*
import org.koin.compose.koinInject

/**
 * Detail screen for a single worker with actions for verification,
 * role management, and deletion.
 */
@Composable
fun AdminWorkerDetailScreen(
    workerId: Int,
    onBack: () -> Unit,
    viewModel: WorkerManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRoleDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(workerId) {
        viewModel.selectWorker(workerId)
    }

    AdminScaffold(
        title = "Worker Detail",
        subtitle = "Workers",
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
        val worker = state.selectedWorker
        if (worker == null) {
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
                    // Worker Info Panel
                    DetailPanel(
                        title = "Worker Information",
                        items = listOf(
                            "ID" to worker.id.toString(),
                            "Name" to worker.name,
                            "Email" to worker.email,
                            "Phone" to worker.phone,
                            "Role" to worker.role,
                            "Early Adopter Tier" to worker.earlyAdopterTier.name,
                            "Referral Code" to (worker.referralCode ?: "N/A"),
                            "Joined" to worker.createdAt.toString().take(10),
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        // Address Panel
                        DetailPanel(
                            title = "Address",
                            items = listOf(
                                "Street" to worker.address.street,
                                "City" to worker.address.city,
                                "State" to worker.address.state,
                                "ZIP" to worker.address.zip,
                                "Neighborhood" to (worker.address.neighborhood ?: "N/A"),
                            ),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Verification Panel
                        DetailPanel(
                            title = "Verification",
                            items = listOf(
                                "Overall Status" to worker.verification.status.name,
                                "Government ID" to worker.verification.governmentIdStatus.name,
                                "Proof of Address" to worker.verification.proofOfAddressStatus.name,
                                "Review Notes" to (worker.verification.reviewNotes ?: "None"),
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stats Panel
                DetailPanel(
                    title = "Statistics",
                    items = listOf(
                        "Jobs Completed" to worker.stats.jobsCompleted.toString(),
                        "Total Earnings" to ("$" + (worker.stats.totalEarnings / 100).toString()),
                        "Average Rating" to (worker.stats.averageRating?.toString() ?: "N/A"),
                        "Last Job" to (worker.stats.lastJobAt?.toString()?.take(10) ?: "Never"),
                    ),
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Verify Button
                    if (worker.verification.status != VerificationStatus.APPROVED) {
                        Button(
                            onClick = {
                                viewModel.verifyWorker(
                                    worker.id,
                                    VerifyWorkerRequest(
                                        status = VerificationStatus.APPROVED,
                                        governmentIdStatus = DocumentStatus.APPROVED,
                                        proofOfAddressStatus = DocumentStatus.APPROVED,
                                    ),
                                )
                            },
                            enabled = !state.isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusAvailable,
                            ),
                        ) {
                            Text("Approve")
                        }
                    }

                    // Reject Button
                    if (worker.verification.status != VerificationStatus.REJECTED) {
                        Button(
                            onClick = {
                                viewModel.verifyWorker(
                                    worker.id,
                                    VerifyWorkerRequest(status = VerificationStatus.REJECTED),
                                )
                            },
                            enabled = !state.isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusError,
                            ),
                        ) {
                            Text("Reject")
                        }
                    }

                    // Role Dropdown
                    Box {
                        OutlinedButton(onClick = { showRoleDropdown = true }) {
                            Text("Change Role")
                        }
                        DropdownMenu(
                            expanded = showRoleDropdown,
                            onDismissRequest = { showRoleDropdown = false },
                        ) {
                            UserRole.entries.forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.name) },
                                    onClick = {
                                        viewModel.updateRole(worker.id, role)
                                        showRoleDropdown = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Delete Button
                    Button(
                        onClick = { showDeleteDialog = true },
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete Worker")
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete Worker",
            message = "Are you sure you want to delete this worker? This action cannot be undone.",
            confirmText = "Delete",
            onConfirm = {
                viewModel.deleteWorker(workerId)
                showDeleteDialog = false
                onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}
