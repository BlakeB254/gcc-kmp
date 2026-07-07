package com.gcc.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gcc.app.platform.ScanQualitySignal
import com.gcc.app.platform.ScanQualityTier
import com.gcc.app.platform.ScanQualityScorer
import com.gcc.app.ui.theme.GccAvailable
import com.gcc.app.ui.theme.GccClaimed
import com.gcc.app.ui.theme.GccError

/**
 * Voluntary, informational-only scan-quality pill -- fuses AR tracking
 * state, GPS accuracy, and detection confidence into one glanceable signal.
 * Nothing in the capture flow reads this back to gate or block a scan; a
 * worker can submit at any tier.
 */
@Composable
fun ScanQualityBadge(signal: ScanQualitySignal, modifier: Modifier = Modifier) {
    val score = ScanQualityScorer.score(signal)
    val tier = ScanQualityScorer.tierOf(score)
    val reason = ScanQualityScorer.limitingFactor(signal)
    val color = when (tier) {
        ScanQualityTier.GOOD -> GccAvailable
        ScanQualityTier.FAIR -> GccClaimed
        ScanQualityTier.POOR -> GccError
    }
    val label = when (tier) {
        ScanQualityTier.GOOD -> "Scan quality: Good"
        ScanQualityTier.FAIR -> "Scan quality: Fair" + (reason?.let { " · $it" } ?: "")
        ScanQualityTier.POOR -> "Scan quality: Poor" + (reason?.let { " · $it" } ?: "")
    }
    StatusChip(text = label, dotColor = color, backgroundColor = color.copy(alpha = 0.15f), modifier = modifier)
}
