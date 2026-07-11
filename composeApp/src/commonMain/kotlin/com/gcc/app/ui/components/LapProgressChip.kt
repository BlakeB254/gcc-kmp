package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreenDark
import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot
import com.gcc.shared.model.ScanPhase

/**
 * The persistent "where am I in the 2-lap flow" label. Null for phases where
 * a lap chip carries no information (submitting/complete screens narrate
 * themselves).
 */
fun lapProgressLabel(phase: ScanPhase): String? = when (phase) {
    ScanPhase.SCOUTING -> "Lap 1 of 2 · Document"
    ScanPhase.SCOUT_REVIEW -> "Lap 1 done · Review"
    ScanPhase.CLEANING -> "Lap 2 of 2 · Clean"
    ScanPhase.SUBMITTING, ScanPhase.COMPLETE -> null
}

/**
 * Warning copy for completing the cleanup lap without any confirmed cleaning
 * — null means complete silently. Lap 2's completion gate ("no DETECTED
 * hotspots left") is satisfiable with zero work: lap 1 found nothing, or
 * every target was skipped/documented-only. Both are legitimate outcomes,
 * but they must be an explicit choice, not a default a stray tap can hit.
 */
fun cleanLapCompletionWarning(hotspots: List<ScanHotspot>): String? {
    if (hotspots.any { it.status == HotspotStatus.CLEANED }) return null
    return if (hotspots.isEmpty()) {
        "Lap 1 found no problem areas, so there was nothing to clean. Complete the scan?"
    } else {
        val documented = hotspots.count { it.status == HotspotStatus.SKIPPED }
        "You documented $documented problem area(s) without confirming any cleanup. Complete anyway?"
    }
}

/** Small pill showing the current lap of the 2-lap scout → clean flow. */
@Composable
fun LapProgressChip(phase: ScanPhase, modifier: Modifier = Modifier) {
    val label = lapProgressLabel(phase) ?: return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(GccGreenDark.copy(alpha = 0.88f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
