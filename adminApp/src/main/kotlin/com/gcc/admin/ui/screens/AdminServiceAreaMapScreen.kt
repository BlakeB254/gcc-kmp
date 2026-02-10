package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.ServiceAreaViewModel
import com.gcc.shared.model.AdminServiceAreaRequest
import com.gcc.shared.model.ServiceAreaStatus
import org.koin.compose.koinInject

/**
 * Service areas management screen.
 *
 * Displays a DataTable of service areas. A real map will come later
 * when MapLibre is integrated. Currently supports create/edit inline.
 */
@Composable
fun AdminServiceAreaMapScreen(
    viewModel: ServiceAreaViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newCity by remember { mutableStateOf("Chicago") }
    var newStatus by remember { mutableStateOf("planning") }

    AdminScaffold(
        title = "Service Areas",
        subtitle = "Operations",
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
                Text("Create Area")
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            if (state.error != null) {
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
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
                    items = state.serviceAreas,
                    columns = listOf(
                        TableColumn(header = "Name", weight = 1.5f) { area ->
                            Text(
                                text = area.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "City", weight = 1f) { area ->
                            Text(
                                text = area.city + ", " + area.state,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Status", weight = 0.8f) { area ->
                            StatusBadge(
                                text = area.status.name,
                                color = when (area.status) {
                                    ServiceAreaStatus.PLANNING -> StatusPending
                                    ServiceAreaStatus.PILOT -> StatusWarning
                                    ServiceAreaStatus.ACTIVE -> StatusAvailable
                                    ServiceAreaStatus.PAUSED -> StatusError
                                },
                            )
                        },
                        TableColumn(header = "Workers", weight = 0.6f) { area ->
                            Text(
                                text = area.activeWorkers.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Blocks", weight = 0.6f) { area ->
                            Text(
                                text = area.totalBlocks.toString(),
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

    // Create Service Area Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Service Area") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Area Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newCity,
                        onValueChange = { newCity = it },
                        label = { Text("City") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newStatus,
                        onValueChange = { newStatus = it },
                        label = { Text("Status (planning/pilot/active/paused)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createServiceArea(
                            AdminServiceAreaRequest(
                                name = newName,
                                city = newCity,
                                status = newStatus,
                            ),
                        )
                        newName = ""
                        newCity = "Chicago"
                        newStatus = "planning"
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
