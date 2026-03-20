package com.gcc.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.HeatColors

/**
 * Color-coded badge showing cleanliness or density level.
 * Adapted from SCGO's HeatBadge.
 */
@Composable
fun HeatBadge(
    label: String,
    score: Int,
    modifier: Modifier = Modifier,
) {
    val color = HeatColors.forCleanlinessScore(score)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = "$label $score",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Badge for spot density count.
 */
@Composable
fun SpotDensityBadge(
    spotCount: Int,
    modifier: Modifier = Modifier,
) {
    val color = HeatColors.forSpotCount(spotCount)
    val label = when {
        spotCount == 0 -> "Clean"
        spotCount <= 2 -> "Mild"
        spotCount <= 5 -> "Moderate"
        spotCount <= 10 -> "Heavy"
        else -> "Critical"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = "$label \u2022 $spotCount spots",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
