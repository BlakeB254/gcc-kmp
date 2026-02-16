package com.gcc.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.StatusAvailable
import com.gcc.admin.ui.theme.StatusCompleted
import com.gcc.admin.ui.theme.StatusError
import com.gcc.admin.ui.theme.StatusInProgress
import com.gcc.admin.ui.theme.StatusPending
import com.gcc.admin.ui.theme.StatusWarning
import com.gcc.shared.model.BlockStatus
import com.gcc.shared.model.BountyStatus
import com.gcc.shared.model.DisputeStatus
import com.gcc.shared.model.FeedbackStatus
import com.gcc.shared.model.JobStatus
import com.gcc.shared.model.RouteStatus
import com.gcc.shared.model.VerificationStatus

/**
 * A small coloured pill badge for displaying status labels.
 *
 * The background is rendered at 15 % alpha of the supplied [color] while
 * the text uses the full colour.
 */
@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// ── Convenience wrappers for domain enum types ───────────────────────────────

@Composable
fun VerificationStatusBadge(status: VerificationStatus) {
    val (label, color) = when (status) {
        VerificationStatus.PENDING -> "Pending" to StatusWarning
        VerificationStatus.APPROVED -> "Approved" to StatusAvailable
        VerificationStatus.REJECTED -> "Rejected" to StatusError
        VerificationStatus.NEEDS_INFO -> "Needs Info" to StatusPending
    }
    StatusBadge(text = label, color = color)
}

@Composable
fun JobStatusBadge(status: JobStatus) {
    val (label, color) = when (status) {
        JobStatus.AVAILABLE -> "Available" to StatusAvailable
        JobStatus.CLAIMED -> "Claimed" to StatusInProgress
        JobStatus.IN_PROGRESS -> "In Progress" to StatusInProgress
        JobStatus.PENDING_REVIEW -> "Pending Review" to StatusPending
        JobStatus.COMPLETED -> "Completed" to StatusCompleted
        JobStatus.DISPUTED -> "Disputed" to StatusError
    }
    StatusBadge(text = label, color = color)
}

@Composable
fun RouteStatusBadge(status: RouteStatus) {
    val (label, color) = when (status) {
        RouteStatus.DRAFT -> "Draft" to AdminOnSurfaceVariant
        RouteStatus.PUBLISHED -> "Published" to StatusAvailable
        RouteStatus.ARCHIVED -> "Archived" to StatusWarning
    }
    StatusBadge(text = label, color = color)
}

@Composable
fun BlockStatusBadge(status: BlockStatus) {
    val (label, color) = when (status) {
        BlockStatus.AVAILABLE -> "Available" to StatusAvailable
        BlockStatus.FUNDED -> "Funded" to StatusInProgress
        BlockStatus.IN_PROGRESS -> "In Progress" to StatusWarning
        BlockStatus.COMPLETED -> "Completed" to StatusCompleted
    }
    StatusBadge(text = label, color = color)
}

@Composable
fun BountyStatusBadge(status: BountyStatus) {
    val (label, color) = when (status) {
        BountyStatus.OPEN -> "Open" to StatusAvailable
        BountyStatus.FIRST_REFUSAL -> "First Refusal" to StatusPending
        BountyStatus.CLAIMED -> "Claimed" to StatusInProgress
        BountyStatus.IN_PROGRESS -> "In Progress" to StatusWarning
        BountyStatus.COMPLETED -> "Completed" to StatusCompleted
        BountyStatus.EXPIRED -> "Expired" to AdminOnSurfaceVariant
        BountyStatus.CANCELLED -> "Cancelled" to StatusError
    }
    StatusBadge(text = label, color = color)
}

@Composable
fun DisputeStatusBadge(status: DisputeStatus) {
    val (label, color) = when (status) {
        DisputeStatus.OPEN -> "Open" to StatusWarning
        DisputeStatus.UNDER_REVIEW -> "Under Review" to StatusPending
        DisputeStatus.RESOLVED -> "Resolved" to StatusCompleted
        DisputeStatus.ESCALATED -> "Escalated" to StatusError
    }
    StatusBadge(text = label, color = color)
}

@Composable
fun FeedbackStatusBadge(status: FeedbackStatus) {
    val (label, color) = when (status) {
        FeedbackStatus.OPEN -> "Open" to StatusAvailable
        FeedbackStatus.IN_PROGRESS -> "In Progress" to StatusInProgress
        FeedbackStatus.WAITING_RESPONSE -> "Waiting" to StatusWarning
        FeedbackStatus.RESOLVED -> "Resolved" to StatusCompleted
        FeedbackStatus.CLOSED -> "Closed" to AdminOnSurfaceVariant
    }
    StatusBadge(text = label, color = color)
}
