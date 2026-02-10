package com.gcc.app.ui.gamification

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.EarlyAdopterTier

/**
 * Metallic tier badge component with scale-in animation.
 *
 * Shows the worker's early adopter tier (FOUNDER, PIONEER, etc.)
 * with a metallic gradient background and bounce entrance.
 */
@Composable
fun TierBadge(
    tier: EarlyAdopterTier,
    modifier: Modifier = Modifier,
) {
    var animationPlayed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "tier_badge_scale",
    )

    LaunchedEffect(Unit) { animationPlayed = true }

    val (gradientColors, label) = when (tier) {
        EarlyAdopterTier.FOUNDER -> listOf(
            BadgeGold,
            Color(0xFFDAA520),
            BadgeGold,
        ) to "FOUNDER"
        EarlyAdopterTier.PIONEER -> listOf(
            BadgePlatinum,
            Color(0xFFBEC2CB),
            BadgePlatinum,
        ) to "PIONEER"
        EarlyAdopterTier.EARLY -> listOf(
            BadgeSilver,
            Color(0xFFA8A8A8),
            BadgeSilver,
        ) to "EARLY ADOPTER"
        EarlyAdopterTier.STANDARD -> listOf(
            BadgeBronze,
            Color(0xFFB87333),
            BadgeBronze,
        ) to "MEMBER"
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(gradientColors))
            .border(2.dp, gradientColors.first().copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "\u26A1 $label \u26A1",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp,
            letterSpacing = 2.sp,
            color = Color(0xFF1A1A1A),
        )
    }
}
