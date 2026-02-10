package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.RouteManagementViewModel
import com.gcc.shared.model.AdminCheckpointRequest
import com.gcc.shared.model.AdminRouteRequest
import org.koin.compose.koinInject

/**
 * Route editor screen for creating and editing routes.
 *
 * When [routeId] is null, operates in create mode.
 * When [routeId] is not null, loads and edits the existing route.
 */
@Composable
fun AdminRouteEditorScreen(
    routeId: Int?,
    onBack: () -> Unit,
    viewModel: RouteManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val isEditMode = routeId != null

    // Initialize form state
    LaunchedEffect(routeId) {
        if (routeId != null) {
            // Find and load the route for editing
            val route = state.routes.find { it.id == routeId }
            if (route != null) {
                viewModel.startEditRoute(route)
            }
        } else {
            viewModel.startNewRoute()
        }
    }

    // Local form state
    var code by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var serviceAreaId by remember { mutableStateOf("") }
    var distanceMeters by remember { mutableStateOf("") }
    var durationMinutes by remember { mutableStateOf("") }
    var payoutAmount by remember { mutableStateOf("") }

    // Checkpoint dialog state
    var showCheckpointDialog by remember { mutableStateOf(false) }
    var cpName by remember { mutableStateOf("") }
    var cpLat by remember { mutableStateOf("") }
    var cpLng by remember { mutableStateOf("") }

    // Sync from editing route
    LaunchedEffect(state.editingRoute) {
        state.editingRoute?.let { route ->
            code = route.code
            displayName = route.displayName
            serviceAreaId = route.serviceAreaId.toString()
            distanceMeters = route.distanceMeters?.toString() ?: ""
            durationMinutes = route.estimatedDurationMinutes?.toString() ?: ""
            payoutAmount = route.payoutAmount?.toString() ?: ""
        }
    }

    AdminScaffold(
        title = if (isEditMode) "Edit Route" else "Create Route",
        subtitle = "Routes",
        actions = {
            IconButton(onClick = {
                viewModel.cancelEdit()
                onBack()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            if (state.error != null) {
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Form fields
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Route Code") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = serviceAreaId,
                    onValueChange = { serviceAreaId = it },
                    label = { Text("Service Area ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = distanceMeters,
                    onValueChange = { distanceMeters = it },
                    label = { Text("Distance (meters)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = durationMinutes,
                    onValueChange = { durationMinutes = it },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = payoutAmount,
                    onValueChange = { payoutAmount = it },
                    label = { Text("Payout (cents)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Checkpoints section
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Checkpoints (${state.checkpoints.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { showCheckpointDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Checkpoint",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (state.checkpoints.isNotEmpty()) {
                DataTable(
                    items = state.checkpoints,
                    columns = listOf(
                        TableColumn(header = "#", weight = 0.3f) { cp ->
                            Text(
                                text = cp.sequenceOrder.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Name", weight = 1.5f) { cp ->
                            Text(
                                text = cp.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Lat", weight = 1f) { cp ->
                            Text(
                                text = cp.lat.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Lng", weight = 1f) { cp ->
                            Text(
                                text = cp.lng.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Type", weight = 0.8f) { cp ->
                            Text(
                                text = cp.type,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "", weight = 0.4f) { cp ->
                            val index = state.checkpoints.indexOf(cp)
                            IconButton(onClick = { viewModel.removeCheckpoint(index) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    ),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save / Cancel / Publish / Archive buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val request = AdminRouteRequest(
                            code = code,
                            displayName = displayName,
                            serviceAreaId = serviceAreaId.toIntOrNull() ?: 0,
                            distanceMeters = distanceMeters.toDoubleOrNull(),
                            estimatedDurationMinutes = durationMinutes.toIntOrNull(),
                            payoutAmount = payoutAmount.toIntOrNull(),
                        )
                        if (isEditMode && routeId != null) {
                            viewModel.updateRoute(routeId, request)
                        } else {
                            viewModel.createRoute(request)
                        }
                        onBack()
                    },
                    enabled = !state.isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(if (isEditMode) "Save Changes" else "Create Route")
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.cancelEdit()
                        onBack()
                    },
                ) {
                    Text("Cancel")
                }

                if (isEditMode && routeId != null) {
                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = { viewModel.publishRoute(routeId) },
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusAvailable,
                        ),
                    ) {
                        Text("Publish")
                    }

                    Button(
                        onClick = { viewModel.archiveRoute(routeId) },
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusWarning,
                        ),
                    ) {
                        Text("Archive")
                    }
                }
            }
        }
    }

    // Add Checkpoint Dialog
    if (showCheckpointDialog) {
        AlertDialog(
            onDismissRequest = { showCheckpointDialog = false },
            title = { Text("Add Checkpoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cpName,
                        onValueChange = { cpName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cpLat,
                            onValueChange = { cpLat = it },
                            label = { Text("Latitude") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = cpLng,
                            onValueChange = { cpLng = it },
                            label = { Text("Longitude") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addCheckpoint(
                            AdminCheckpointRequest(
                                name = cpName,
                                lat = cpLat.toDoubleOrNull() ?: 0.0,
                                lng = cpLng.toDoubleOrNull() ?: 0.0,
                                sequenceOrder = state.checkpoints.size + 1,
                            ),
                        )
                        cpName = ""
                        cpLat = ""
                        cpLng = ""
                        showCheckpointDialog = false
                    },
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCheckpointDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}
