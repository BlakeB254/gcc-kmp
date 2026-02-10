package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*

enum class GccButtonVariant { PRIMARY, SECONDARY, OUTLINED }

@Composable
fun GccButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: GccButtonVariant = GccButtonVariant.PRIMARY,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    trailingText: String? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val alpha = if (enabled && !isLoading) 1f else 0.6f

    when (variant) {
        GccButtonVariant.PRIMARY -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(GradientButtonStart, GradientButtonEnd),
                        ),
                        alpha = alpha,
                    )
                    .then(
                        if (enabled && !isLoading) Modifier.clickable(onClick = onClick)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                        if (trailingText != null) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = trailingText,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }
        }

        GccButtonVariant.SECONDARY -> {
            Button(
                onClick = onClick,
                modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = enabled && !isLoading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GccGold,
                    contentColor = Color.White,
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }

        GccButtonVariant.OUTLINED -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = enabled && !isLoading,
                shape = shape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = GccGreen,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}
