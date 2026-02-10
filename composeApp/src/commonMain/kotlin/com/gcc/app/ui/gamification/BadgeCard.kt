package com.gcc.app.ui.gamification

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.Badge
import com.gcc.shared.model.BadgeTier

/**
 * Individual badge display component with locked/unlocked states.
 *
 * Locked: grayscale with progress ring
 * Unlocked: full color with glow, "NEW" indicator if recent
 */
@Composable
fun BadgeCard(
    badge: Badge,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tierColor = badge.tier.toColor()
    val progress = if (badge.requirement > 0) {
        badge.progress.toFloat() / badge.requirement.toFloat()
    } else 1f

    Column(
        modifier = modifier
            .width(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (badge.isUnlocked) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
            .border(
                width = if (badge.isUnlocked) 2.dp else 1.dp,
                color = if (badge.isUnlocked) tierColor else Color.Gray.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Badge icon with progress ring
        if (badge.isUnlocked) {
            // Unlocked: full color icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(tierColor.copy(alpha = 0.15f))
                    .border(2.dp, tierColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge.category.toEmoji(),
                    fontSize = 22.sp,
                )
            }
        } else {
            // Locked: progress ring
            ProgressRing(
                progress = progress,
                color = tierColor,
                size = 48.dp,
                strokeWidth = 3.dp,
            ) {
                Text(
                    text = badge.category.toEmoji(),
                    fontSize = 18.sp,
                    modifier = Modifier.alpha(0.4f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = badge.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (badge.isUnlocked) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        if (!badge.isUnlocked) {
            Text(
                text = "${badge.progress}/${badge.requirement}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }

        // Tier label
        Text(
            text = badge.tier.name,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
            color = tierColor,
        )
    }
}

fun BadgeTier.toColor(): Color = when (this) {
    BadgeTier.BRONZE -> BadgeBronze
    BadgeTier.SILVER -> BadgeSilver
    BadgeTier.GOLD -> BadgeGold
    BadgeTier.PLATINUM -> BadgePlatinum
    BadgeTier.DIAMOND -> BadgeDiamond
}

fun com.gcc.shared.model.BadgeCategory.toEmoji(): String = when (this) {
    com.gcc.shared.model.BadgeCategory.JOBS -> "\uD83E\uDDF9"       // broom
    com.gcc.shared.model.BadgeCategory.EARNINGS -> "\uD83D\uDCB0"   // money bag
    com.gcc.shared.model.BadgeCategory.STREAK -> "\uD83D\uDD25"     // fire
    com.gcc.shared.model.BadgeCategory.COMMUNITY -> "\uD83E\uDD1D"  // handshake
    com.gcc.shared.model.BadgeCategory.EARLY_ADOPTER -> "\u2B50"     // star
    com.gcc.shared.model.BadgeCategory.OBSERVE -> "\uD83D\uDC41"    // eye
    com.gcc.shared.model.BadgeCategory.VOLUNTEER -> "\uD83D\uDC9A"  // green heart
    com.gcc.shared.model.BadgeCategory.FIRST_RESPONDER -> "\u26A1"   // lightning
    com.gcc.shared.model.BadgeCategory.SPONSOR -> "\uD83C\uDFC6"    // trophy
}
