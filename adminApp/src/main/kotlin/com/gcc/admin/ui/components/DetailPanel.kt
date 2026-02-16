package com.gcc.admin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminTheme
import com.gcc.admin.ui.theme.TableBorder

/**
 * A side panel surface used for displaying entity details alongside a
 * data table. Fixed width of 400 dp, fills available height, and scrolls
 * its content vertically.
 */
@Composable
fun DetailPanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = AdminTheme.spacing
    val elevation = AdminTheme.elevation

    Surface(
        modifier = modifier
            .width(400.dp)
            .fillMaxHeight(),
        tonalElevation = elevation.raised,
        shadowElevation = elevation.raised,
    ) {
        Column {
            // ── Header ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                actions()

                Spacer(Modifier.width(spacing.sm))

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close panel",
                    )
                }
            }

            Divider(color = TableBorder)

            // ── Scrollable Content ───────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                content = content,
            )
        }
    }
}

/**
 * Helper row for displaying a label/value pair inside a [DetailPanel].
 */
@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AdminOnSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
