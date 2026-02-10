package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.FeedbackViewModel
import com.gcc.shared.model.FeedbackPriority
import com.gcc.shared.model.FeedbackStatus
import org.koin.compose.koinInject

/**
 * List screen showing feedback tickets from workers and sponsors.
 */
@Composable
fun AdminFeedbackListScreen(
    viewModel: FeedbackViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Feedback",
        subtitle = "Operations",
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
                    items = state.feedbackList,
                    columns = listOf(
                        TableColumn(header = "ID", weight = 0.4f) { ticket ->
                            Text(
                                text = ticket.id.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Subject", weight = 2f) { ticket ->
                            Text(
                                text = ticket.subject,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        },
                        TableColumn(header = "Category", weight = 1f) { ticket ->
                            Text(
                                text = ticket.category.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Priority", weight = 0.8f) { ticket ->
                            StatusBadge(
                                text = ticket.priority.name,
                                color = when (ticket.priority) {
                                    FeedbackPriority.LOW -> StatusAvailable
                                    FeedbackPriority.MEDIUM -> StatusInProgress
                                    FeedbackPriority.HIGH -> StatusWarning
                                    FeedbackPriority.URGENT -> StatusError
                                },
                            )
                        },
                        TableColumn(header = "Status", weight = 0.8f) { ticket ->
                            StatusBadge(
                                text = ticket.status.name,
                                color = when (ticket.status) {
                                    FeedbackStatus.OPEN -> StatusError
                                    FeedbackStatus.IN_PROGRESS -> StatusInProgress
                                    FeedbackStatus.WAITING_RESPONSE -> StatusWarning
                                    FeedbackStatus.RESOLVED -> StatusAvailable
                                    FeedbackStatus.CLOSED -> StatusCompleted
                                },
                            )
                        },
                        TableColumn(header = "User", weight = 0.8f) { ticket ->
                            Text(
                                text = ticket.userType.name + " #" + ticket.userId.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Date", weight = 0.8f) { ticket ->
                            Text(
                                text = ticket.createdAt.toString().take(10),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
