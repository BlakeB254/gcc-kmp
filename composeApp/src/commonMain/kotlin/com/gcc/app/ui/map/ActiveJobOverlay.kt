package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.app.viewmodel.CheckpointUiItem
import com.gcc.shared.model.Coordinates

/**
 * Map overlay for active GPS tracking during a job.
 *
 * Shows completed/remaining route segments and checkpoint markers.
 * In production, renders as MapLibre layers with real polylines.
 */
@Composable
fun ActiveJobOverlay(
    checkpoints: List<CheckpointUiItem>,
    currentLocation: Coordinates?,
    currentCheckpointIndex: Int,
    isWithinGeofence: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Route progress indicator
        RouteProgressBar(
            totalCheckpoints = checkpoints.size,
            completedCheckpoints = currentCheckpointIndex,
        )

        // Geofence indicator
        if (isWithinGeofence && currentCheckpointIndex < checkpoints.size) {
            GeofenceIndicator(
                checkpointName = checkpoints[currentCheckpointIndex].checkpoint.name,
            )
        }
    }
}

@Composable
private fun RouteProgressBar(
    totalCheckpoints: Int,
    completedCheckpoints: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.95f))
            .padding(12.dp),
    ) {
        Text(
            "Route Progress",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(totalCheckpoints) { index ->
                val color = when {
                    index < completedCheckpoints -> MapCheckpointCompleted
                    index == completedCheckpoints -> MapCheckpointCurrent
                    else -> MapCheckpointUpcoming
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "$completedCheckpoints / $totalCheckpoints checkpoints",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun GeofenceIndicator(
    checkpointName: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MapCheckpointCurrent.copy(alpha = 0.15f))
            .border(1.dp, MapCheckpointCurrent, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(MapCheckpointCurrent)
        )
        Column {
            Text(
                "Within Geofence",
                style = MaterialTheme.typography.labelLarge,
                color = MapCheckpointCurrent,
            )
            Text(
                checkpointName,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
