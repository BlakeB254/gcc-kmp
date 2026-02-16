package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.components.RouteStatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.RouteManagementViewModel
import org.koin.compose.koinInject

@Composable
fun AdminRouteListScreen(
    onRouteClick: (Int) -> Unit,
    onNewRoute: () -> Unit,
) {
    val viewModel = koinInject<RouteManagementViewModel>()
    val routes by viewModel.routes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
    ) {
        // ── Header ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                Text("Routes", style = MaterialTheme.typography.headlineMedium)
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Button(
                onClick = {
                    viewModel.startNewRoute()
                    onNewRoute()
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AdminTheme.spacing.xs))
                Text("New Route")
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // ── Data Table ──────────────────────────────────────────────
        DataTable(
            items = routes,
            columns = listOf(
                DataColumn(header = "Code", weight = 0.8f) { route ->
                    Text(route.code, style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Name", weight = 1.5f) { route ->
                    Text(route.displayName, style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Status", weight = 0.8f) { route ->
                    RouteStatusBadge(route.status)
                },
                DataColumn(header = "Distance", weight = 0.8f) { route ->
                    val distText = route.distanceMeters?.let {
                        String.format("%.1f mi", it / 1609.344)
                    } ?: "--"
                    Text(distText, style = MaterialTheme.typography.bodySmall)
                },
                DataColumn(header = "Duration", weight = 0.8f) { route ->
                    Text(
                        text = route.estimatedDurationMinutes?.let { "${it} min" } ?: "--",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                DataColumn(header = "Payout", weight = 0.7f) { route ->
                    Text(
                        text = route.payoutAmount?.let { "$${it / 100}" } ?: "--",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                DataColumn(header = "Checkpoints", weight = 0.7f) { route ->
                    Text(route.checkpoints.size.toString(), style = MaterialTheme.typography.bodyMedium)
                },
            ),
            onRowClick = { route -> onRouteClick(route.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
