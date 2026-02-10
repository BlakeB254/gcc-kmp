package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.map.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.ServiceAreaViewModel
import com.gcc.shared.model.AdminServiceAreaRequest
import com.gcc.shared.model.ServiceAreaStatus
import org.koin.compose.koinInject

@Composable
fun AdminServiceAreaMapScreen(
    viewModel: ServiceAreaViewModel = koinInject(),
    mapViewModel: AdminMapViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val mapState by mapViewModel.state.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(ViewMode.MAP) }
    var newName by remember { mutableStateOf("") }
    var newCity by remember { mutableStateOf("Chicago") }
    var newStatus by remember { mutableStateOf("planning") }

    AdminScaffold(
        title = "Network Map",
        subtitle = "Operations",
        scrollable = false,
        actions = {
            IconButton(onClick = { viewMode = if (viewMode == ViewMode.MAP) ViewMode.LIST else ViewMode.MAP }) {
                Icon(
                    imageVector = if (viewMode == ViewMode.MAP) Icons.AutoMirrored.Filled.ViewList else Icons.Default.Map,
                    contentDescription = if (viewMode == ViewMode.MAP) "Switch to List" else "Switch to Map",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Create Area")
            }
        },
    ) {
        when (viewMode) {
            ViewMode.MAP -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    AdminMap(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = mapViewModel,
                        enableGestures = true,
                        onBlockClick = { block -> mapViewModel.selectBlock(block.id) },
                        onSpotClick = { spot -> mapViewModel.selectSpot(spot.id) },
                        onBountyClick = { bounty -> mapViewModel.selectBounty(bounty.id) },
                    ) {
                        AdminMapControls(
                            currentZoom = ZOOM_CITY,
                            layerState = mapState,
                            onZoomIn = { /* TODO */ },
                            onZoomOut = { /* TODO */ },
                            onPresetSelect = { /* TODO */ },
                            onToggleLayer = { mapViewModel.toggleLayer(it) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                        AdminMapLegend(
                            currentZoom = ZOOM_CITY,
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                        )
                    }

                    AdminMapInfoPanel(
                        state = mapState,
                        blocks = mapState.blocks,
                        serviceAreas = mapState.serviceAreas,
                        spots = mapState.spots,
                        bounties = mapState.bounties,
                        onClose = { mapViewModel.clearSelection() },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            ViewMode.LIST -> {
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
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        DataTable(
                            items = state.serviceAreas,
                            columns = listOf(
                                TableColumn(header = "Name", weight = 1.5f) { area ->
                                    Text(area.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                },
                                TableColumn(header = "City", weight = 1f) { area ->
                                    Text(area.city + ", " + area.state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text(area.activeWorkers.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                },
                                TableColumn(header = "Blocks", weight = 0.6f) { area ->
                                    Text(area.totalBlocks.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

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

private enum class ViewMode { MAP, LIST }
