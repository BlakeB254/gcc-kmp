package com.gcc.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.SurfaceMuted

/**
 * Modifier that applies an animated shimmer gradient sweep effect.
 * Uses infinite horizontal translation of a transparent-white-transparent gradient.
 */
fun Modifier.shimmerEffect(): Modifier = composed {
    val shimmerColors = listOf(
        Color.LightGray.copy(alpha = 0.3f),
        Color.LightGray.copy(alpha = 0.6f),
        Color.LightGray.copy(alpha = 0.3f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -300f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslateX",
    )
    background(
        brush = Brush.linearGradient(
            colors = shimmerColors,
            start = Offset(translateX, 0f),
            end = Offset(translateX + 300f, 0f),
        )
    )
}

/**
 * A shimmer-animated rectangle placeholder.
 */
@Composable
fun SkeletonBox(
    width: Dp,
    height: Dp,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .shimmerEffect()
    )
}

/**
 * A card-shaped skeleton placeholder mimicking GccCard layout.
 * Uses Fibonacci spacing (13dp between elements).
 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(SurfaceMuted)
            .padding(13.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Title line
            SkeletonBox(width = 180.dp, height = 16.dp)
            // Subtitle line (shorter)
            SkeletonBox(width = 120.dp, height = 12.dp)
            Spacer(modifier = Modifier.height(5.dp))
            // Body line 1
            SkeletonBox(width = 240.dp, height = 12.dp)
            // Body line 2
            SkeletonBox(width = 200.dp, height = 12.dp)
        }
    }
}

/**
 * A column of skeleton cards with Fibonacci spacing.
 */
@Composable
fun SkeletonList(
    count: Int = 3,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 13.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        repeat(count) {
            SkeletonCard()
        }
    }
}
