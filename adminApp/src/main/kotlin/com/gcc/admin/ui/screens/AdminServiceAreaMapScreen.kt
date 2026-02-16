package com.gcc.admin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.ServiceAreaViewModel
import com.gcc.shared.model.ServiceArea
import org.koin.compose.koinInject

@Composable
fun AdminServiceAreaMapScreen() {
    val viewModel = koinInject<ServiceAreaViewModel>()
    val serviceAreas by viewModel.serviceAreas.collectAsState()
    val blocks by viewModel.blocks.collectAsState()
    val routes by viewModel.routes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // -- Left Panel: Service Area List (300dp) --------------------------
        Surface(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            tonalElevation = AdminTheme.elevation.raised,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Panel header
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                    ) {
                        Text(
                            "Service Areas",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(AdminTheme.spacing.sm))
                        Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Divider(color = TableBorder)

                // Service area list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AdminTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                ) {
                    items(serviceAreas) { area ->
                        ServiceAreaListItem(area)
                    }

                    if (serviceAreas.isEmpty() && !isLoading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AdminTheme.spacing.xl),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No service areas",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AdminOnSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // -- Right: Map Placeholder -----------------------------------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AdminBackground),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(
                    modifier = Modifier.padding(AdminTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = AdminOnSurfaceVariant,
                    )
                    Text(
                        "Service Area Map",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Interactive map coming in Phase 4",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AdminOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(AdminTheme.spacing.sm))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
                    ) {
                        StatChip(
                            label = "Areas",
                            value = serviceAreas.size.toString(),
                        )
                        StatChip(
                            label = "Blocks",
                            value = blocks.size.toString(),
                        )
                        StatChip(
                            label = "Routes",
                            value = routes.size.toString(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceAreaListItem(area: ServiceArea) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = AdminTheme.elevation.card,
    ) {
        Column(
            modifier = Modifier.padding(AdminTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = AdminPrimary,
                )
                Text(
                    text = area.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
            ) {
                Text(
                    text = "${area.totalBlocks} blocks",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminOnSurfaceVariant,
                )
                Text(
                    text = "${area.activeWorkers} workers",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminOnSurfaceVariant,
                )
            }
            Text(
                text = "${area.city}, ${area.state}",
                style = MaterialTheme.typography.labelSmall,
                color = AdminOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = AdminPrimary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AdminOnSurfaceVariant,
        )
    }
}
