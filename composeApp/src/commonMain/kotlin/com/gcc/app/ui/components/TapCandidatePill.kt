package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.TapCandidateState
import com.gcc.app.platform.TapPhase
import com.gcc.app.platform.tapPillCopy
import com.gcc.app.ui.theme.GccError
import kotlinx.coroutines.delay

/** An unconfirmed candidate self-dismisses after this long. Accidental taps cost nothing. */
private const val TAP_CANDIDATE_TIMEOUT_MS = 12_000L

/**
 * Bottom confirm/cancel pill for a tapped litter candidate (global constraint 2).
 * Nothing is persisted until [onConfirm]. [onDismiss] records nothing.
 */
@Composable
fun TapCandidatePill(
    state: TapCandidateState,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the phase-derived title — used by the ground-scan intent
     *  prompt, which reuses this pill with its own question. */
    titleOverride: String? = null,
) {
    val derived = tapPillCopy(state) ?: return
    val copy = if (titleOverride != null) derived.copy(title = titleOverride) else derived

    // Any phase change restarts the timer; an untouched candidate expires.
    LaunchedEffect(state.phase, state.normalizedX, state.normalizedY) {
        if (state.phase == TapPhase.IDLE) return@LaunchedEffect
        delay(TAP_CANDIDATE_TIMEOUT_MS)
        onDismiss()
    }

    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = copy.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        if (copy.confirmLabel != null && copy.rejectLabel != null) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onReject) {
                    Text(copy.rejectLabel, color = Color.White.copy(alpha = 0.75f))
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = GccError),
                ) {
                    Text(copy.confirmLabel, color = Color.White)
                }
            }
        }
    }
}
