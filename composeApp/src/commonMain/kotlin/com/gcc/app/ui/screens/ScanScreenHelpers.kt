package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.TextMuted

/**
 * Shared helpers for scan-phase screens.
 */

@Composable
internal fun ScanSummaryRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
        )
    }
}

/** Format a Double to one decimal place (KMP-safe, no String.format). */
internal fun formatOneDecimal(value: Double): String {
    val intPart = value.toLong()
    val fracPart = ((value - intPart) * 10 + 0.5).toInt() % 10
    return "$intPart.$fracPart"
}

internal fun formatElapsed(totalSeconds: Long): String {
    val h = (totalSeconds / 3600).toInt()
    val m = ((totalSeconds % 3600) / 60).toInt()
    val s = (totalSeconds % 60).toInt()
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}
