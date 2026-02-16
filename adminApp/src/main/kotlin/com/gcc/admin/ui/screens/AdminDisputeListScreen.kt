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
import com.gcc.admin.ui.components.DisputeStatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.DisputeViewModel
import com.gcc.shared.model.Dispute
import com.gcc.shared.model.DisputeType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminDisputeListScreen(onDisputeClick: (Int) -> Unit) {
    val viewModel = koinInject<DisputeViewModel>()
    val disputes by viewModel.disputes.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
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
            Text("Disputes", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // -- Status Filter Chips --------------------------------------------
        Row(
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
        ) {
            val filterOptions = listOf(
                null to "All",
                "open" to "Open",
                "under_review" to "Under Review",
                "resolved" to "Resolved",
                "escalated" to "Escalated",
            )
            filterOptions.forEach { (value, label) ->
                FilterChip(
                    selected = statusFilter == value,
                    onClick = { viewModel.onStatusFilterChange(value) },
                    label = { Text(label) },
                )
            }
        }

        // -- Table ----------------------------------------------------------
        val columns = remember {
            listOf(
                DataColumn<Dispute>(
                    header = "ID",
                    weight = 0.5f,
                    content = { dispute ->
                        Text("#${dispute.id}", style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<Dispute>(
                    header = "Job ID",
                    weight = 0.6f,
                    content = { dispute ->
                        Text("#${dispute.jobId}", style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<Dispute>(
                    header = "Type",
                    weight = 1f,
                    content = { dispute ->
                        Text(
                            text = disputeTypeLabel(dispute.type),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<Dispute>(
                    header = "Status",
                    weight = 1f,
                    content = { dispute ->
                        DisputeStatusBadge(dispute.status)
                    },
                ),
                DataColumn<Dispute>(
                    header = "Reporter",
                    weight = 0.8f,
                    content = { dispute ->
                        Text(
                            text = "${dispute.reporterType.name.lowercase()} #${dispute.reporterId}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                ),
                DataColumn<Dispute>(
                    header = "Created",
                    weight = 0.9f,
                    content = { dispute ->
                        val local = dispute.createdAt
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                        Text(
                            text = "${local.monthNumber}/${local.dayOfMonth}/${local.year}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AdminOnSurfaceVariant,
                        )
                    },
                ),
            )
        }

        DataTable(
            items = disputes,
            columns = columns,
            onRowClick = { dispute -> onDisputeClick(dispute.id) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

private fun disputeTypeLabel(type: DisputeType): String {
    return when (type) {
        DisputeType.QUALITY -> "Quality"
        DisputeType.INCOMPLETE -> "Incomplete"
        DisputeType.NO_SHOW -> "No Show"
        DisputeType.PAYMENT -> "Payment"
        DisputeType.MISCONDUCT -> "Misconduct"
        DisputeType.OTHER -> "Other"
    }
}
