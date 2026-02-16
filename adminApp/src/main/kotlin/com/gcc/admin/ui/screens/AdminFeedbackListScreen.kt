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
import com.gcc.admin.ui.components.FeedbackStatusBadge
import com.gcc.admin.ui.components.StatusBadge
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.FeedbackViewModel
import com.gcc.shared.model.FeedbackCategory
import com.gcc.shared.model.FeedbackPriority
import com.gcc.shared.model.FeedbackTicket
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminFeedbackListScreen() {
    val viewModel = koinInject<FeedbackViewModel>()
    val tickets by viewModel.tickets.collectAsState()
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
            Text("Feedback", style = MaterialTheme.typography.headlineMedium)
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
                "in_progress" to "In Progress",
                "waiting_response" to "Waiting",
                "resolved" to "Resolved",
                "closed" to "Closed",
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
                DataColumn<FeedbackTicket>(
                    header = "ID",
                    weight = 0.4f,
                    content = { ticket ->
                        Text("#${ticket.id}", style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<FeedbackTicket>(
                    header = "Subject",
                    weight = 2f,
                    content = { ticket ->
                        Text(
                            text = ticket.subject,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    },
                ),
                DataColumn<FeedbackTicket>(
                    header = "Category",
                    weight = 1.2f,
                    content = { ticket ->
                        Text(
                            text = categoryLabel(ticket.category),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<FeedbackTicket>(
                    header = "Status",
                    weight = 0.9f,
                    content = { ticket ->
                        FeedbackStatusBadge(ticket.status)
                    },
                ),
                DataColumn<FeedbackTicket>(
                    header = "Priority",
                    weight = 0.7f,
                    content = { ticket ->
                        PriorityBadge(ticket.priority)
                    },
                ),
                DataColumn<FeedbackTicket>(
                    header = "User Type",
                    weight = 0.7f,
                    content = { ticket ->
                        Text(
                            text = ticket.userType.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<FeedbackTicket>(
                    header = "Assigned To",
                    weight = 1f,
                    content = { ticket ->
                        Text(
                            text = ticket.assignedTo ?: "Unassigned",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ticket.assignedTo != null)
                                MaterialTheme.colorScheme.onSurface
                            else AdminOnSurfaceVariant,
                        )
                    },
                ),
                DataColumn<FeedbackTicket>(
                    header = "Created",
                    weight = 0.8f,
                    content = { ticket ->
                        val local = ticket.createdAt
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
            items = tickets,
            columns = columns,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun PriorityBadge(priority: FeedbackPriority) {
    val (label, color) = when (priority) {
        FeedbackPriority.LOW -> "Low" to AdminOnSurfaceVariant
        FeedbackPriority.MEDIUM -> "Medium" to AdminInfo
        FeedbackPriority.HIGH -> "High" to AdminWarning
        FeedbackPriority.URGENT -> "Urgent" to AdminError
    }
    StatusBadge(text = label, color = color)
}

private fun categoryLabel(category: FeedbackCategory): String {
    return when (category) {
        FeedbackCategory.SAFETY_CONCERN -> "Safety Concern"
        FeedbackCategory.APP_ISSUE -> "App Issue"
        FeedbackCategory.VERIFICATION_PROBLEM -> "Verification"
        FeedbackCategory.PAYMENT_ISSUE -> "Payment Issue"
        FeedbackCategory.GENERAL_IMPROVEMENT -> "Improvement"
        FeedbackCategory.FEATURE_REQUEST -> "Feature Request"
        FeedbackCategory.OTHER -> "Other"
    }
}
