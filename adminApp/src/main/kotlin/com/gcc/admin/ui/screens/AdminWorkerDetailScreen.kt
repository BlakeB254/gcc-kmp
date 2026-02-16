package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DetailRow
import com.gcc.admin.ui.components.StatsCard
import com.gcc.admin.ui.components.VerificationStatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.WorkerManagementViewModel
import com.gcc.shared.model.UpdateWorkerRoleRequest
import com.gcc.shared.model.UserRole
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.VerifyWorkerRequest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminWorkerDetailScreen(workerId: Int, onBack: () -> Unit) {
    val viewModel = koinInject<WorkerManagementViewModel>()
    val workers by viewModel.workers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val worker = remember(workers, workerId) {
        workers.find { it.id == workerId }
    }

    var selectedRole by remember { mutableStateOf<UserRole?>(null) }
    var roleExpanded by remember { mutableStateOf(false) }

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
            Text("Worker Detail", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                Spacer(Modifier.width(AdminTheme.spacing.sm))
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        if (worker == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text("Worker not found", style = MaterialTheme.typography.bodyLarge, color = AdminOnSurfaceVariant)
                }
            }
        } else {
            // ── Info + Stats Row ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
            ) {
                // Left: Worker Info
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = AdminTheme.elevation.card,
                ) {
                    Column(
                        modifier = Modifier.padding(AdminTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                    ) {
                        Text("Worker Information", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(AdminTheme.spacing.sm))
                        DetailRow("Name", worker.name)
                        DetailRow("Email", worker.email)
                        DetailRow("Phone", worker.phone)
                        DetailRow(
                            "Address",
                            "${worker.address.street}, ${worker.address.city}, ${worker.address.state} ${worker.address.zip}",
                        )
                        val registeredDate = worker.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
                        DetailRow(
                            "Registered",
                            "${registeredDate.monthNumber}/${registeredDate.dayOfMonth}/${registeredDate.year}",
                        )
                        DetailRow("Referral Code", worker.referralCode ?: "--")
                    }
                }

                // Right: Stats Cards
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                    ) {
                        StatsCard(
                            title = "Jobs Completed",
                            value = worker.stats.jobsCompleted.toString(),
                            icon = Icons.Default.CheckCircle,
                            modifier = Modifier.weight(1f),
                        )
                        StatsCard(
                            title = "Total Earnings",
                            value = "$${worker.stats.totalEarnings / 100}",
                            icon = Icons.Default.Favorite,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                    ) {
                        StatsCard(
                            title = "Average Rating",
                            value = worker.stats.averageRating?.let { String.format("%.1f", it) } ?: "--",
                            icon = Icons.Default.Star,
                            modifier = Modifier.weight(1f),
                        )
                        StatsCard(
                            title = "Tier",
                            value = worker.earlyAdopterTier.name.lowercase().replaceFirstChar { it.uppercase() },
                            icon = Icons.Default.ThumbUp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ── Verification Section ────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    Text("Verification", style = MaterialTheme.typography.titleMedium)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                            ) {
                                Text("Overall:", style = MaterialTheme.typography.bodyMedium, color = AdminOnSurfaceVariant)
                                VerificationStatusBadge(worker.verification.status)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                            ) {
                                Text("Gov ID:", style = MaterialTheme.typography.bodySmall, color = AdminOnSurfaceVariant)
                                Text(
                                    worker.verification.governmentIdStatus.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                            ) {
                                Text("Address Proof:", style = MaterialTheme.typography.bodySmall, color = AdminOnSurfaceVariant)
                                Text(
                                    worker.verification.proofOfAddressStatus.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Row(horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm)) {
                            Button(
                                onClick = {
                                    viewModel.verifyWorker(
                                        worker.id,
                                        VerifyWorkerRequest(status = VerificationStatus.APPROVED),
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AdminSuccess),
                                enabled = worker.verification.status != VerificationStatus.APPROVED,
                            ) {
                                Text("Verify")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.verifyWorker(
                                        worker.id,
                                        VerifyWorkerRequest(status = VerificationStatus.REJECTED),
                                    )
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AdminError),
                                enabled = worker.verification.status != VerificationStatus.REJECTED,
                            ) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }

            // ── Role Management ─────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    Text("Role Management", style = MaterialTheme.typography.titleMedium)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                    ) {
                        Text(
                            "Current role: ${worker.role}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AdminOnSurfaceVariant,
                        )

                        Box {
                            OutlinedButton(onClick = { roleExpanded = true }) {
                                Text(selectedRole?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Select Role")
                            }
                            DropdownMenu(
                                expanded = roleExpanded,
                                onDismissRequest = { roleExpanded = false },
                            ) {
                                UserRole.entries.forEach { role ->
                                    DropdownMenuItem(
                                        text = { Text(role.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                        onClick = {
                                            selectedRole = role
                                            roleExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        selectedRole?.let { role ->
                            Button(
                                onClick = {
                                    viewModel.updateRole(worker.id, UpdateWorkerRoleRequest(role = role))
                                    selectedRole = null
                                },
                            ) {
                                Text("Update Role")
                            }
                        }
                    }
                }
            }
        }
    }
}
