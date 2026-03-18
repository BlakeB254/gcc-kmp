package com.gcc.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccBlue

/**
 * AI navigation guidance banner that slides in from the top.
 *
 * Shows contextual scanning directions like "Pan left — alley not scanned".
 * Hides when [text] is null.
 */
@Composable
fun AiDirectionBanner(
    text: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(GccBlue.copy(alpha = 0.88f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "\uD83E\uDD16",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = text ?: "",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                )
            }
        }
    }
}
