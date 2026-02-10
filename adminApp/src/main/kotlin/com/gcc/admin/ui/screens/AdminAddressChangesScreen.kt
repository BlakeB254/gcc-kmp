package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.AddressChangeViewModel
import org.koin.compose.koinInject

/**
 * Screen showing workers with pending address changes.
 *
 * Each row shows the current and new address with approve/reject buttons.
 */
@Composable
fun AdminAddressChangesScreen(
    viewModel: AddressChangeViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Address Changes",
        subtitle = "System",
        scrollable = false,
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
                    items = state.pendingChanges,
                    columns = listOf(
                        TableColumn(header = "Worker", weight = 1.2f) { change ->
                            Column {
                                Text(
                                    text = change.workerName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "ID: " + change.workerId.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        TableColumn(header = "Current Address", weight = 1.5f) { change ->
                            Text(
                                text = change.currentAddress.street + ", " +
                                    change.currentAddress.city + " " +
                                    change.currentAddress.zip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "New Address", weight = 1.5f) { change ->
                            Text(
                                text = change.newAddress.street + ", " +
                                    change.newAddress.city + " " +
                                    change.newAddress.zip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Status", weight = 0.8f) { change ->
                            StatusBadge(
                                text = change.status.name,
                                color = StatusWarning,
                            )
                        },
                        TableColumn(header = "Actions", weight = 1.2f) { change ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.approveChange(change.workerId) },
                                    enabled = !state.isLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = StatusAvailable,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text(
                                        text = "Approve",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Button(
                                    onClick = { viewModel.rejectChange(change.workerId) },
                                    enabled = !state.isLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = StatusError,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text(
                                        text = "Reject",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
