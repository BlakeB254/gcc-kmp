package com.gcc.admin.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminOutlineVariant
import com.gcc.admin.ui.theme.AdminSurface

/**
 * A card-based panel for displaying labeled key-value data.
 *
 * Commonly used on detail screens to group related information
 * (e.g. "Worker Info", "Address", "Statistics").
 *
 * @param title Section heading displayed at the top of the card.
 * @param items List of label-value pairs to render as rows.
 * @param modifier Modifier applied to the outer Card.
 */
@Composable
fun DetailPanel(
    title: String,
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = AdminSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // ── Title ────────────────────────────────────────────────────
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Key-Value Rows ───────────────────────────────────────────
            items.forEachIndexed { index, (label, value) ->
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AdminOnSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.5f),
                    )
                }

                if (index < items.lastIndex) {
                    HorizontalDivider(
                        color = AdminOutlineVariant,
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}
