package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.BlockStatusBadge
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.BlockManagementViewModel
import org.koin.compose.koinInject

@Composable
fun AdminBlockListScreen() {
    val viewModel = koinInject<BlockManagementViewModel>()
    val blocks by viewModel.blocks.collectAsState()
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
            Text("Blocks", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // ── Data Table ──────────────────────────────────────────────
        DataTable(
            items = blocks,
            columns = listOf(
                DataColumn(header = "Code", weight = 0.8f) { block ->
                    Text(block.code, style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Name", weight = 1.5f) { block ->
                    Text(block.name, style = MaterialTheme.typography.bodyMedium)
                },
                DataColumn(header = "Status", weight = 0.8f) { block ->
                    BlockStatusBadge(block.status)
                },
                DataColumn(header = "Progress", weight = 1.2f) { block ->
                    val goal = block.fundingGoal ?: 0
                    val current = block.fundingCurrent
                    val progress = if (goal > 0) (current.toFloat() / goal).coerceIn(0f, 1f) else 0f

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.weight(1f).height(8.dp),
                            color = AdminSuccess,
                            trackColor = AdminSurfaceVariant,
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = AdminOnSurfaceVariant,
                        )
                    }
                },
                DataColumn(header = "Goal", weight = 0.8f) { block ->
                    Text(
                        text = block.fundingGoal?.let { "$${it / 100}" } ?: "--",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                DataColumn(header = "Current", weight = 0.8f) { block ->
                    Text(
                        text = "$${block.fundingCurrent / 100}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
