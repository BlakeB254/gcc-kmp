package com.gcc.admin.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminSurface

/**
 * A dashboard stats card for displaying a single metric.
 *
 * Shows a large value with a descriptive title and optional subtitle.
 * An optional icon is rendered in the top-right corner with a subtle
 * primary tint.
 *
 * @param title Metric label (e.g. "Active Workers").
 * @param value The metric value displayed prominently (e.g. "142").
 * @param subtitle Optional secondary text (e.g. "+12 this week").
 * @param icon Optional icon displayed in the corner.
 * @param modifier Modifier applied to the outer Card.
 */
@Composable
fun StatsCard(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = AdminSurface,
        ),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            // ── Icon (top-right) ─────────────────────────────────────────
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.TopEnd),
                )
            }

            // ── Content ──────────────────────────────────────────────────
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminOnSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
