package com.gcc.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*

/**
 * Overlapping avatar stack showing initials.
 * Adapted from SCGO's FriendAvatarStack.
 */
@Composable
fun AvatarStack(
    names: List<String>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3,
    size: Int = 28,
) {
    val visible = names.take(maxVisible)
    val extraCount = names.size - visible.size
    val overlapPx = size - 8

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            visible.forEachIndexed { index, name ->
                val initial = name.firstOrNull()?.uppercaseChar() ?: '?'
                val bgColor = avatarColors[index % avatarColors.size]

                Surface(
                    modifier = Modifier
                        .size(size.dp)
                        .offset { IntOffset((index * overlapPx).dp.roundToPx(), 0) },
                    shape = CircleShape,
                    color = bgColor.copy(alpha = 0.25f),
                    border = BorderStroke(1.5.dp, bgColor.copy(alpha = 0.6f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial.toString(),
                            fontSize = (size / 2.3).sp,
                            fontWeight = FontWeight.Bold,
                            color = bgColor,
                        )
                    }
                }
            }
        }
        // Reserve space for stacked avatars
        Spacer(Modifier.width(((visible.size - 1) * overlapPx + size).dp))
        if (extraCount > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+$extraCount",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted,
            )
        }
    }
}

private val avatarColors = listOf(
    GccGreen,
    Color(0xFF2196F3), // Blue
    Color(0xFFFF9800), // Orange
    Color(0xFF9C27B0), // Purple
    Color(0xFFE91E63), // Pink
)
