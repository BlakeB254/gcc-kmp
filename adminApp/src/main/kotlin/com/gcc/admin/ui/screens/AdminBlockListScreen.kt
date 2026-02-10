package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.map.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.BlockManagementViewModel
import com.gcc.shared.model.AdminBlockRequest
import com.gcc.shared.model.BlockStatus
import org.koin.compose.koinInject

/**
 * List screen showing all blocks with create capability.
 */
@Composable
fun AdminBlockListScreen(
    viewModel: BlockManagementViewModel = koinInject(),
    mapViewModel: AdminMapViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCode by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newAreaId by remember { mutableStateOf("") }
    var newFundingGoal by remember { mutableStateOf("") }

    AdminScaffold(
        title = "Blocks",
        subtitle = "Management",
        scrollable = false,
        actions = {
            Button(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Create Block")
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Mini-map card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().height(250.dp).padding(bottom = 16.dp),
            ) {
                AdminMap(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = mapViewModel,
                    initialZoom = ZOOM_NEIGHBORHOOD,
                    enableGestures = false,
                )
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
                    items = state.blocks,
                    columns = listOf(
                        TableColumn(header = "Code", weight = 1f) { block ->
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        TableColumn(header = "Name", weight = 1.5f) { block ->
                            Text(
                                text = block.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Area", weight = 0.8f) { block ->
                            Text(
                                text = block.serviceAreaId?.toString() ?: "N/A",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Status", weight = 0.8f) { block ->
                            StatusBadge(
                                text = block.status.name,
                                color = when (block.status) {
                                    BlockStatus.AVAILABLE -> StatusAvailable
                                    BlockStatus.FUNDED -> StatusInProgress
                                    BlockStatus.IN_PROGRESS -> StatusWarning
                                    BlockStatus.COMPLETED -> StatusCompleted
                                },
                            )
                        },
                        TableColumn(header = "Funding", weight = 1f) { block ->
                            val current = block.fundingCurrent / 100
                            val goal = (block.fundingGoal ?: 0) / 100
                            Text(
                                text = "$$current / $$goal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Sponsor", weight = 0.8f) { block ->
                            Text(
                                text = block.sponsorId?.toString() ?: "None",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // Create Block Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Block") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCode,
                        onValueChange = { newCode = it },
                        label = { Text("Block Code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newAreaId,
                        onValueChange = { newAreaId = it },
                        label = { Text("Service Area ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newFundingGoal,
                        onValueChange = { newFundingGoal = it },
                        label = { Text("Funding Goal (cents)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createBlock(
                            AdminBlockRequest(
                                code = newCode,
                                name = newName,
                                serviceAreaId = newAreaId.toIntOrNull(),
                                fundingGoal = newFundingGoal.toIntOrNull(),
                            ),
                        )
                        newCode = ""
                        newName = ""
                        newAreaId = ""
                        newFundingGoal = ""
                        showCreateDialog = false
                    },
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}
