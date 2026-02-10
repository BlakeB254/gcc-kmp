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
import com.gcc.admin.viewmodel.RouteManagementViewModel
import com.gcc.shared.model.RouteStatus
import org.koin.compose.koinInject

/**
 * List screen showing all routes with a "Create Route" action button.
 */
@Composable
fun AdminRouteListScreen(
    onRouteClick: (Int) -> Unit,
    onCreateRoute: () -> Unit,
    viewModel: RouteManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Routes",
        subtitle = "Management",
        scrollable = false,
        actions = {
            Button(
                onClick = onCreateRoute,
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
                Text("Create Route")
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                DataTable(
                    items = state.routes,
                    columns = listOf(
                        TableColumn(header = "Code", weight = 1f) { route ->
                            Text(
                                text = route.code,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        TableColumn(header = "Name", weight = 1.5f) { route ->
                            Text(
                                text = route.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Area", weight = 0.8f) { route ->
                            Text(
                                text = route.serviceAreaId.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Status", weight = 0.8f) { route ->
                            StatusBadge(
                                text = route.status.name,
                                color = when (route.status) {
                                    RouteStatus.DRAFT -> StatusWarning
                                    RouteStatus.PUBLISHED -> StatusAvailable
                                    RouteStatus.ARCHIVED -> StatusError
                                },
                            )
                        },
                        TableColumn(header = "Distance", weight = 0.8f) { route ->
                            val meters = route.distanceMeters ?: 0.0
                            val display = if (meters > 1000) {
                                (meters / 1000).toInt().toString() + " km"
                            } else {
                                meters.toInt().toString() + " m"
                            }
                            Text(
                                text = display,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Payout", weight = 0.8f) { route ->
                            val payout = route.payoutAmount
                            Text(
                                text = if (payout != null) "$${payout / 100}" else "N/A",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    ),
                    onRowClick = { route -> onRouteClick(route.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
