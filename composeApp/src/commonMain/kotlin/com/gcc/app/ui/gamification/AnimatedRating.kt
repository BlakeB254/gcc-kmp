package com.gcc.app.ui.gamification

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.BadgeGold
import com.gcc.app.util.fmt

/**
 * Animated star rating that fills stars sequentially.
 *
 * Each star fades in with a stagger delay, creating a cascading
 * reveal effect. The numeric rating appears after all stars.
 */
@Composable
fun AnimatedRating(
    rating: Double?,
    maxStars: Int = 5,
    staggerDelayMs: Int = 100,
    style: TextStyle = TextStyle.Default,
    modifier: Modifier = Modifier,
) {
    if (rating == null) return

    val filledStars = rating.toInt().coerceIn(0, maxStars)
    val hasHalfStar = (rating - filledStars) >= 0.5

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(maxStars) { index ->
            val delay = index * staggerDelayMs
            var visible by remember { mutableStateOf(false) }
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(300, delayMillis = delay),
                label = "star_$index",
            )

            LaunchedEffect(Unit) { visible = true }

            val symbol = when {
                index < filledStars -> "\u2605" // filled star
                index == filledStars && hasHalfStar -> "\u2605" // half star (shown as filled)
                else -> "\u2606" // empty star
            }

            Text(
                text = symbol,
                modifier = Modifier.alpha(alpha),
                style = style.copy(fontSize = 18.sp),
                color = if (index < filledStars || (index == filledStars && hasHalfStar))
                    BadgeGold else BadgeGold.copy(alpha = 0.3f),
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = rating.fmt(1),
            style = style,
        )
    }
}
