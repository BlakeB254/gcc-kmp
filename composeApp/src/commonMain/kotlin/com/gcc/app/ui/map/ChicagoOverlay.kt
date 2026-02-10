package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*

/**
 * Legend card overlay for the community area map.
 *
 * Displays legend items: Service Area, Boundary, Community Areas, and optionally
 * the current zoom level. Uses Fibonacci spacing tokens throughout (5dp, 8dp, 13dp).
 *
 * @param onAreaClick   Optional click callback for the legend card
 * @param zoomLevel     Optional current zoom level to display as an indicator
 * @param modifier      Modifier for positioning
 */
@Composable
fun ChicagoOverlay(
    onAreaClick: ((areaName: String) -> Unit)? = null,
    zoomLevel: Double? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = GccTheme.spacing

    Surface(
        modifier = modifier
            .padding(spacing.sm)  // 8dp Fibonacci
            .clickable(enabled = onAreaClick != null) { onAreaClick?.invoke("North Lawndale") },
        shape = RoundedCornerShape(spacing.sm),  // 8dp Fibonacci radius
        shadowElevation = GccTheme.elevation.card,
        color = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),  // 13dp Fibonacci
            verticalArrangement = Arrangement.spacedBy(spacing.xs),  // 5dp Fibonacci
        ) {
            // Service Area legend
            LegendItem(
                indicator = {
                    Box(
                        modifier = Modifier
                            .size(spacing.sm)  // 8dp
                            .clip(RoundedCornerShape(spacing.xxs))  // 3dp
                            .background(GccGreen)
                    )
                },
                label = "Service Area",
            )

            // Boundary legend (dashed orange line)
            LegendItem(
                indicator = {
                    Box(
                        modifier = Modifier
                            .width(21.dp)  // Fibonacci: 21
                            .height(0.dp)
                            .border(
                                width = 2.dp,
                                color = MapServiceBoundary,
                            )
                    )
                },
                label = "Boundary",
            )

            // Community Areas legend
            LegendItem(
                indicator = {
                    Box(
                        modifier = Modifier
                            .size(spacing.sm)  // 8dp
                            .clip(RoundedCornerShape(spacing.xxs))  // 3dp
                            .background(MapCommunityStroke)
                    )
                },
                label = "Community Areas",
            )

            // Zoom level indicator (optional)
            if (zoomLevel != null) {
                Spacer(modifier = Modifier.height(spacing.xxs))  // 3dp separator

                val zoomLabel = when {
                    zoomLevel >= MapStyles.ZOOM_STREET -> "Street"
                    zoomLevel >= MapStyles.ZOOM_ACTIVE_JOB -> "Job"
                    zoomLevel >= MapStyles.ZOOM_ROUTE -> "Route"
                    zoomLevel >= MapStyles.ZOOM_NEIGHBORHOOD -> "Area"
                    else -> "City"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),  // 8dp
                ) {
                    Text(
                        "Zoom:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Text(
                        zoomLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = GccGreenDark,
                    )
                }
            }
        }
    }
}

/**
 * A single legend row with an indicator widget and a label.
 *
 * Uses Fibonacci spacing (8dp) between indicator and label.
 */
@Composable
private fun LegendItem(
    indicator: @Composable () -> Unit,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GccTheme.spacing.sm),  // 8dp Fibonacci
    ) {
        indicator()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}
