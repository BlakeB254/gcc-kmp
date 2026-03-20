package com.gcc.app.ui.components

import androidx.compose.foundation.background
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

/**
 * Reusable vertical status timeline with step indicators.
 * Adapted from Community Farm Table's OrderTrackingScreen.
 *
 * @param steps Ordered list of step labels
 * @param currentStepIndex Zero-based index of the current (active) step
 * @param accentColor Color for completed/active steps (defaults to GccGreen)
 */
@Composable
fun StatusTimeline(
    steps: List<String>,
    currentStepIndex: Int,
    modifier: Modifier = Modifier,
    accentColor: Color = GccGreen,
) {
    Column(modifier = modifier) {
        steps.forEachIndexed { index, stepLabel ->
            val isComplete = index < currentStepIndex
            val isCurrent = index == currentStepIndex
            val isFuture = index > currentStepIndex

            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Step indicator column (circle + connecting bar)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCurrent -> accentColor
                                    isComplete -> accentColor.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                isComplete -> "\u2713" // ✓
                                isCurrent -> "\u25CF" // ●
                                else -> "${index + 1}"
                            },
                            fontSize = if (isCurrent) 10.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isFuture -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> Color.White
                            },
                        )
                    }
                    // Connecting bar (not on last step)
                    if (index < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(24.dp)
                                .background(
                                    if (isComplete) accentColor.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Step label
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = stepLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isCurrent -> accentColor
                            isComplete -> MaterialTheme.colorScheme.onSurface
                            else -> TextMuted
                        },
                    )
                }
            }
        }
    }
}
