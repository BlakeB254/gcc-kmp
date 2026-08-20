package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.gcc.app.platform.ReconstructionReadiness
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

/**
 * F4 readiness meter: three pips (baseline / tracking / depth) fed by the
 * same [ReconstructionReadiness] flow the guidance caption reads, so the
 * worker can see WHICH prerequisite is still unmet instead of guessing from
 * a text line that loses to every higher-priority caption. Advisory only —
 * like [ScanQualityBadge], it never gates capture or completion.
 */
@Composable
fun ReadinessPips(readiness: ReconstructionReadiness, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        ReadinessPip(label = "Baseline", met = readiness.hasHorizontalBaseline)
        ReadinessPip(label = "Tracking", met = readiness.hasEnoughNormalTracking)
        ReadinessPip(label = "Depth", met = readiness.hasUsableDepth)
    }
}

@Composable
private fun ReadinessPip(label: String, met: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (met) GccAvailable else Color.White.copy(alpha = 0.35f)),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (met) Color.White else Color.White.copy(alpha = 0.6f),
        )
    }
}
