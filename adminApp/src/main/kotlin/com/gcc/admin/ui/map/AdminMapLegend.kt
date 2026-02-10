package com.gcc.admin.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.admin.ui.theme.AdminOnSurface
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminSurface

@Composable
fun AdminMapLegend(
    currentZoom: Double,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = AdminSurface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Legend",
                style = MaterialTheme.typography.labelMedium,
                color = AdminOnSurface,
            )

            // Block statuses
            LegendDotRow(color = MapBlockAvailable, label = "Available")
            LegendDotRow(color = MapBlockFunded, label = "Funded")
            LegendDotRow(color = MapBlockInProgress, label = "In Progress")
            LegendDotRow(color = MapBlockCompleted, label = "Completed")

            Spacer(modifier = Modifier.height(2.dp))

            // Markers
            LegendDotRow(color = MapSpotMarker, label = "Spot Report")
            LegendRingRow(color = MapBountyZone, label = "Bounty Zone")

            Spacer(modifier = Modifier.height(2.dp))

            // Line types
            LegendDashedRow(color = MapServiceAreaBorder, label = "Service Area")
            LegendLineRow(color = MapRouteLine, label = "Route")

            Spacer(modifier = Modifier.height(4.dp))

            // Current zoom
            Text(
                text = "Zoom: ${"%.1f".format(currentZoom)}",
                fontSize = 9.sp,
                color = AdminOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDotRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color, radius = 5f, center = center)
        }
        Text(text = label, fontSize = 10.sp, color = AdminOnSurfaceVariant)
    }
}

@Composable
private fun LegendRingRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color, radius = 4f, center = center, style = Stroke(width = 2f))
        }
        Text(text = label, fontSize = 10.sp, color = AdminOnSurfaceVariant)
    }
}

@Composable
private fun LegendDashedRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 2f), 0f),
            )
        }
        Text(text = label, fontSize = 10.sp, color = AdminOnSurfaceVariant)
    }
}

@Composable
private fun LegendLineRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2.5f,
            )
        }
        Text(text = label, fontSize = 10.sp, color = AdminOnSurfaceVariant)
    }
}
