package com.gcc.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gcc.app.ui.theme.GccAvailable
import com.gcc.app.ui.theme.GccClaimed

/**
 * "3 items · ≈1.2 kg" — the live session tally the chip shows. Weight is the
 * sum of per-item LiDAR estimates, so it's omitted until at least one item
 * carries one (a count of taps with no size data must not read as "0 g").
 */
fun scanSessionStatsLabel(itemCount: Int, totalWeightGrams: Int): String {
    val items = if (itemCount == 1) "1 item" else "$itemCount items"
    val weight = when {
        totalWeightGrams <= 0 -> null
        totalWeightGrams < 1000 -> "≈$totalWeightGrams g"
        else -> {
            val kgTenths = totalWeightGrams / 100
            "≈${kgTenths / 10}.${kgTenths % 10} kg"
        }
    }
    return if (weight == null) items else "$items · $weight"
}

/**
 * Live litter tally for the environment scanner's top chrome: how many
 * incidents this session has recorded and their summed estimated weight.
 * Informational only — nothing gates on it (same contract as
 * [ScanQualityBadge]).
 */
@Composable
fun ScanSessionStatsChip(
    itemCount: Int,
    totalWeightGrams: Int,
    modifier: Modifier = Modifier,
) {
    val color = if (itemCount > 0) GccClaimed else GccAvailable
    StatusChip(
        text = scanSessionStatsLabel(itemCount, totalWeightGrams),
        dotColor = color,
        backgroundColor = color.copy(alpha = 0.15f),
        emoji = "🗑",
        modifier = modifier,
    )
}
