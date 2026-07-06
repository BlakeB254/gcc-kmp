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
import com.gcc.shared.model.AdminPayout
import com.gcc.shared.util.GccFormatters

@Composable
internal fun AdminPayoutsTab(
    reviews: List<PendingReview>,
    pendingChecks: List<AdminPayout>,
    recentPayouts: List<AdminPayout>,
    payoutActionError: String?,
    onApprove: (jobId: Int, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onDeny: (jobId: Int, reason: String, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onMarkMailed: (payoutId: Int, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onDismissActionError: () -> Unit,
) {
    // Deny dialog state
    var denyTargetJobId by remember { mutableStateOf<Int?>(null) }
    var denyReason by remember { mutableStateOf("") }
    var actionInProgress by remember { mutableStateOf(false) }
    // Mark-as-mailed confirm state
    var mailTarget by remember { mutableStateOf<AdminPayout?>(null) }

    // Dismiss payout action error snackbar
    payoutActionError?.let { error ->
        LaunchedEffect(error) {
            // error is shown via AlertDialog below; clear after user dismisses
        }
    }

    if (reviews.isEmpty() && pendingChecks.isEmpty() && recentPayouts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "💰\nNo pending approvals or checks",
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
        if (reviews.isNotEmpty()) {
            item(key = "header-approvals") {
                PayoutSectionHeader("Job approvals (${reviews.size})")
            }
            items(reviews, key = { "review-${it.jobId}" }) { review ->
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

        if (pendingChecks.isNotEmpty()) {
            item(key = "header-checks") {
                PayoutSectionHeader("Checks to mail (${pendingChecks.size})")
            }
            items(pendingChecks, key = { "check-${it.id}" }) { payout ->
                CheckToMailCard(
                    payout = payout,
                    actionInProgress = actionInProgress,
                    onMarkMailed = { mailTarget = payout },
                )
            }
        }

        if (recentPayouts.isNotEmpty()) {
            item(key = "header-recent") {
                PayoutSectionHeader("Recent payouts")
            }
            items(recentPayouts, key = { "recent-${it.id}" }) { payout ->
                RecentPayoutRow(payout)
            }
        }
    }

    // Mark-as-mailed confirm dialog
    mailTarget?.let { payout ->
        AlertDialog(
            onDismissRequest = { mailTarget = null },
            title = { Text("Mark check as mailed?") },
            text = {
                Text(
                    "${GccFormatters.formatCents(payout.amountCents)} to " +
                        "${payout.workerName ?: "worker #${payout.workerId}"}. This records " +
                        "the check as sent and notifies the worker — make sure it's " +
                        "actually in the mail.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    enabled = !actionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                    onClick = {
                        actionInProgress = true
                        onMarkMailed(payout.id) { _, _ ->
                            actionInProgress = false
                            mailTarget = null
                        }
                    },
                ) { Text("Mark as mailed") }
            },
            dismissButton = {
                TextButton(onClick = { mailTarget = null }) { Text("Cancel") }
            },
        )
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
private fun PayoutSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CheckToMailCard(
    payout: AdminPayout,
    actionInProgress: Boolean,
    onMarkMailed: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        payout.workerName ?: "Worker #${payout.workerId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    payout.workerEmail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    GccFormatters.formatCents(payout.amountCents),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = GccGreen,
                )
            }

            payout.mailingAddress?.let { address ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "📬 ${address.name}\n${address.street}\n${address.city}, ${address.state} ${address.zip}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            payout.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "“$note”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onMarkMailed,
                enabled = !actionInProgress,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
            ) { Text("Mark as mailed") }
        }
    }
}

@Composable
private fun RecentPayoutRow(payout: AdminPayout) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    payout.workerName ?: "Worker #${payout.workerId}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    adminPayoutStatusLabel(payout),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                GccFormatters.formatCents(payout.amountCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (payout.status == "failed") MaterialTheme.colorScheme.error else GccGreen,
            )
        }
    }
}

private fun adminPayoutStatusLabel(payout: AdminPayout): String = when (payout.status) {
    "processing" -> "Stripe payout processing"
    "completed" -> "Paid via Stripe"
    "failed" -> "Stripe payout FAILED — reconcile"
    "check_pending" -> "Check waiting to be mailed"
    "check_mailed" -> "Check mailed"
    else -> payout.status
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
