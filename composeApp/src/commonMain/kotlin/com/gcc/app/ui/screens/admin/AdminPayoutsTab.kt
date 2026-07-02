package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccSky
import com.gcc.shared.api.PendingReview

@Composable
internal fun AdminPayoutsTab(
    reviews: List<PendingReview>,
    payoutActionError: String?,
    onApprove: (jobId: Int, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onDeny: (jobId: Int, reason: String, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onDismissActionError: () -> Unit,
) {
    // Deny dialog state
    var denyTargetJobId by remember { mutableStateOf<Int?>(null) }
    var denyReason by remember { mutableStateOf("") }
    var actionInProgress by remember { mutableStateOf(false) }

    // Dismiss payout action error snackbar
    payoutActionError?.let { error ->
        LaunchedEffect(error) {
            // error is shown via AlertDialog below; clear after user dismisses
        }
    }

    if (reviews.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "💰\nNo pending approvals",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(reviews, key = { it.jobId }) { review ->
            PayoutReviewCard(
                review = review,
                actionInProgress = actionInProgress,
                onApprove = {
                    actionInProgress = true
                    onApprove(review.jobId) { _, _ -> actionInProgress = false }
                },
                onDenyClick = {
                    denyTargetJobId = review.jobId
                    denyReason = ""
                },
            )
        }
    }

    // Deny reason dialog
    denyTargetJobId?.let { jobId ->
        AlertDialog(
            onDismissRequest = { denyTargetJobId = null; denyReason = "" },
            title = { Text("Deny Job #$jobId") },
            text = {
                Column {
                    Text("Enter the reason for denial:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = denyReason,
                        onValueChange = { denyReason = it },
                        label = { Text("Reason") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = denyReason.isNotBlank() && !actionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val reason = denyReason.trim()
                        actionInProgress = true
                        onDeny(jobId, reason) { _, _ ->
                            actionInProgress = false
                            denyTargetJobId = null
                            denyReason = ""
                        }
                    },
                ) { Text("Deny") }
            },
            dismissButton = {
                TextButton(onClick = { denyTargetJobId = null; denyReason = "" }) { Text("Cancel") }
            },
        )
    }

    // Action error dialog
    payoutActionError?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissActionError,
            title = { Text("Action Failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = onDismissActionError) { Text("OK") }
            },
        )
    }
}

@Composable
private fun PayoutReviewCard(
    review: PendingReview,
    actionInProgress: Boolean,
    onApprove: () -> Unit,
    onDenyClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: worker + payout amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = review.workerName ?: "Unknown Worker",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Job #${review.jobId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val amountText = review.payoutAmount
                    ?.let { com.gcc.shared.util.GccFormatters.formatCents(it.toInt()) }
                    ?: "N/A"
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = GccGreen,
                )
            }

            // Scan indicator
            if (review.hasScan) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (review.scanDepthAvailable) "🔬 Scan + Depth Data" else "📡 Route Scan",
                        style = MaterialTheme.typography.labelMedium,
                        color = GccSky,
                    )
                }
            }

            // Before / After photos side-by-side
            if (review.beforePhotos.isNotEmpty() || review.afterPhotos.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Before column
                    if (review.beforePhotos.isNotEmpty()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Before",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            NetworkImage(
                                url = review.beforePhotos.first(),
                                contentDescription = "Before photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            )
                            if (review.beforePhotos.size > 1) {
                                Text(
                                    "+${review.beforePhotos.size - 1} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // After column
                    if (review.afterPhotos.isNotEmpty()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "After",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            NetworkImage(
                                url = review.afterPhotos.first(),
                                contentDescription = "After photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            )
                            if (review.afterPhotos.size > 1) {
                                Text(
                                    "+${review.afterPhotos.size - 1} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Approve / Deny buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDenyClick,
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Deny") }
                Button(
                    onClick = onApprove,
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                ) { Text("Approve") }
            }
        }
    }
}
