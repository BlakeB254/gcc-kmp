package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.AddressChangeViewModel
import com.gcc.shared.model.AddressChangeRequest
import org.koin.compose.koinInject

@Composable
fun AdminAddressChangesScreen() {
    val viewModel = koinInject<AddressChangeViewModel>()
    val changes by viewModel.changes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // -- Header ---------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Address Changes", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // -- Table ----------------------------------------------------------
        val columns = remember {
            listOf(
                DataColumn<AddressChangeRequest>(
                    header = "Worker ID",
                    weight = 0.7f,
                    content = { change ->
                        Text("#${change.workerId}", style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<AddressChangeRequest>(
                    header = "Street",
                    weight = 2f,
                    content = { change ->
                        Text(
                            text = change.newAddress.street,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<AddressChangeRequest>(
                    header = "City / State / Zip",
                    weight = 1.5f,
                    content = { change ->
                        Text(
                            text = "${change.newAddress.city}, ${change.newAddress.state} ${change.newAddress.zip}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<AddressChangeRequest>(
                    header = "Neighborhood",
                    weight = 1.2f,
                    content = { change ->
                        Text(
                            text = change.newAddress.neighborhood ?: "N/A",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (change.newAddress.neighborhood != null)
                                MaterialTheme.colorScheme.onSurface
                            else AdminOnSurfaceVariant,
                        )
                    },
                ),
                DataColumn<AddressChangeRequest>(
                    header = "Reason",
                    weight = 1.5f,
                    content = { change ->
                        Text(
                            text = change.reason ?: "N/A",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (change.reason != null)
                                MaterialTheme.colorScheme.onSurface
                            else AdminOnSurfaceVariant,
                            maxLines = 2,
                        )
                    },
                ),
            )
        }

        DataTable(
            items = changes,
            columns = columns,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
