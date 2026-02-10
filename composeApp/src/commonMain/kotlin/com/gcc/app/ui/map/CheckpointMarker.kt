package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.shared.model.CheckpointType

/**
 * Custom marker composable for route checkpoints.
 *
 * Displays a numbered circle with status-appropriate coloring
 * and an icon indicating the checkpoint type.
 */
@Composable
fun CheckpointMarker(
    sequenceNumber: Int,
    type: CheckpointType,
    status: CheckpointStatus,
    photoRequired: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (bgColor, borderColor, textColor) = when (status) {
        CheckpointStatus.COMPLETED -> Triple(MapCheckpointCompleted, MapCheckpointCompleted, Color.White)
        CheckpointStatus.CURRENT -> Triple(MapCheckpointCurrent, MapCheckpointCurrent, Color.White)
        CheckpointStatus.UPCOMING -> Triple(Color.White, MapCheckpointUpcoming, MapCheckpointUpcoming)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(2.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                CheckpointStatus.COMPLETED -> Text(
                    "\u2713", // checkmark
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                else -> Text(
                    "$sequenceNumber",
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
        // Type icon label
        Text(
            text = when (type) {
                CheckpointType.ROUTE_START -> "START"
                CheckpointType.PHOTO_POINT -> if (photoRequired) "PHOTO" else "PT"
                CheckpointType.ALLEY_ENTRANCE -> "ALLEY"
                CheckpointType.ROUTE_END -> "END"
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
            color = borderColor,
        )
    }
}
