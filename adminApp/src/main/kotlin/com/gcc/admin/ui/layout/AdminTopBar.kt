package com.gcc.admin.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminSurface

/**
 * Top bar for admin content pages.
 *
 * Displays a breadcrumb-style title (with optional subtitle) on the left
 * and action controls (notification bell, admin avatar) on the right.
 */
@Composable
fun AdminTopBar(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(AdminSurface)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // ── Breadcrumb / Title ───────────────────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminOnSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // ── Custom Actions ───────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )

        Spacer(modifier = Modifier.width(12.dp))

        // ── Notification Bell ────────────────────────────────────────────
        IconButton(onClick = { /* TODO: notifications panel */ }) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "Notifications",
                tint = AdminOnSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // ── Admin Avatar Placeholder ─────────────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Admin profile",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
