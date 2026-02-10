package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*

@Composable
fun StatusChip(
    text: String,
    dotColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    emoji: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        if (emoji != null) {
            Text(emoji, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = dotColor,
        )
    }
}

@Composable
fun JobStatusChip(status: String, modifier: Modifier = Modifier) {
    val (text, dotColor, bgColor) = when (status.lowercase()) {
        "available" -> Triple("Available", GccAvailable, ChipBgAvailable)
        "claimed" -> Triple("Claimed", GccClaimed, ChipBgClaimed)
        "in_progress", "in progress" -> Triple("In Progress", GccInProgress, ChipBgInProgress)
        "pending_review", "pending review" -> Triple("Pending Review", GccPendingReview, ChipBgPendingReview)
        "completed" -> Triple("Completed", GccCompleted, ChipBgCompleted)
        "disputed" -> Triple("Disputed", GccError, ChipBgDisputed)
        else -> Triple(status, TextMuted, SurfaceMuted)
    }
    StatusChip(text = text, dotColor = dotColor, backgroundColor = bgColor, modifier = modifier)
}

@Composable
fun BlockStatusChip(status: String, modifier: Modifier = Modifier) {
    val (text, dotColor, bgColor) = when (status.lowercase()) {
        "available" -> Triple("Available", GccAvailable, ChipBgAvailable)
        "claimed" -> Triple("Claimed", GccClaimed, ChipBgClaimed)
        "in_progress", "in progress" -> Triple("Active", GccInProgress, ChipBgInProgress)
        "completed" -> Triple("Done", GccCompleted, ChipBgCompleted)
        else -> Triple(status, TextMuted, SurfaceMuted)
    }
    StatusChip(text = text, dotColor = dotColor, backgroundColor = bgColor, modifier = modifier)
}
