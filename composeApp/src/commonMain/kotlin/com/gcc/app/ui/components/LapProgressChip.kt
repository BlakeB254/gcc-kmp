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
 * themselves). [lapElapsedSeconds] (elapsed within the CURRENT lap, not the
 * whole session) appends a live mm:ss timer — block assignments are timed
 * work, and the worker should always see the lap clock.
 */
fun lapProgressLabel(phase: ScanPhase, lapElapsedSeconds: Long? = null): String? {
    val base = when (phase) {
        ScanPhase.SCOUTING -> "Lap 1 of 2 · Document"
        ScanPhase.SCOUT_REVIEW -> "Lap 1 done · Review"
        ScanPhase.CLEANING -> "Lap 2 of 2 · Clean"
        ScanPhase.SUBMITTING, ScanPhase.COMPLETE -> return null
    }
    // The review interstitial isn't a timed lap — no clock there.
    if (lapElapsedSeconds == null || phase == ScanPhase.SCOUT_REVIEW) return base
    return "$base · ${formatLapClock(lapElapsedSeconds)}"
}

/** mm:ss, rolling to h:mm:ss past an hour. */
fun formatLapClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${two(minutes)}:${two(seconds)}" else "${two(minutes)}:${two(seconds)}"
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
fun LapProgressChip(
    phase: ScanPhase,
    modifier: Modifier = Modifier,
    lapElapsedSeconds: Long? = null,
) {
    val label = lapProgressLabel(phase, lapElapsedSeconds) ?: return
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
