package com.gcc.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccBlue
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted

/**
 * Animated horizontal progress bar showing scout coverage %.
 *
 * Fill transitions from green at 0% to blue at 100% with a smooth
 * animateFloatAsState-driven width.
 */
@Composable
fun CoverageBar(
    percent: Double,
    label: String = "SCOUTING",
    modifier: Modifier = Modifier,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = (percent / 100.0).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "coverage_fraction",
    )

    Column(modifier = modifier) {
        // Label + percentage row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = TextMuted,
            )
            Text(
                text = "${percent.toInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = GccGreen,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(GccGreen.copy(alpha = 0.15f)),
        ) {
            // Animated fill — green → blue gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(GccGreen, GccBlue),
                        )
                    )
            )
        }
    }
}
