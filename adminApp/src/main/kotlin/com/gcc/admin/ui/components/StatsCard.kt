package com.gcc.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminPrimary
import com.gcc.admin.ui.theme.AdminTheme
import com.gcc.admin.ui.theme.StatusError
import com.gcc.admin.ui.theme.StatusErrorBg

/**
 * Metric card displaying a single KPI with icon, value, title, and
 * optional percentage change indicator.
 */
@Composable
fun StatsCard(
    title: String,
    value: String,
    icon: ImageVector,
    change: String? = null,
    changePositive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val elevation = AdminTheme.elevation
    val spacing = AdminTheme.spacing

    Surface(
        modifier = modifier.widthIn(min = 200.dp),
        shape = RoundedCornerShape(13.dp),
        tonalElevation = elevation.card,
        shadowElevation = elevation.card,
    ) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Icon in tinted circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AdminPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = AdminPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.height(spacing.md))

            // Value
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(spacing.xs))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = AdminOnSurfaceVariant,
            )

            // Optional change badge
            if (change != null) {
                Spacer(Modifier.height(spacing.sm))

                val badgeColor = if (changePositive) AdminPrimary else StatusError
                val badgeBg = if (changePositive) AdminPrimary.copy(alpha = 0.15f) else StatusErrorBg

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = change,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                    )
                }
            }
        }
    }
}
