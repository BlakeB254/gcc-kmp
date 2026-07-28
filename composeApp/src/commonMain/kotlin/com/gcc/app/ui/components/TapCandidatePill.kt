package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.TapCandidateState
import com.gcc.app.platform.TapPhase
import com.gcc.app.platform.tapPillCopy
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import kotlinx.coroutines.delay

/** An unconfirmed candidate self-dismisses after this long. Accidental taps cost nothing. */
private const val TAP_CANDIDATE_TIMEOUT_MS = 12_000L

/** Quick manual-label chips (display labels; canonicalized downstream).
 *  Ordered by field frequency. "Other…" opens free-text entry. */
private val QUICK_LABELS = listOf(
    "Plastic bottle", "Can", "Wrapper", "Plastic bag", "Cup", "Paper", "Glass bottle",
)

/**
 * Bottom confirm/cancel pill for a tapped litter candidate (global constraint 2).
 * Nothing is persisted until [onConfirm] / [onConfirmTyped]. [onDismiss]
 * records nothing.
 *
 * Manual labelling (Blake 2026-07-28): when [onConfirmTyped] is provided and
 * the candidate awaits confirmation, a chip row lets the user assign the
 * type directly — including free-text via "Other…". A chip tap confirms AND
 * labels in one gesture (labelSource "user_chip" / "user_text" downstream —
 * these are the Stage-2 training labels).
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
    /** Confirm with a user-assigned label: (displayLabel, isFreeText). Null hides the chip row. */
    onConfirmTyped: ((label: String, freeText: Boolean) -> Unit)? = null,
) {
    val derived = tapPillCopy(state) ?: return
    val copy = if (titleOverride != null) derived.copy(title = titleOverride) else derived
    var otherEntryOpen by remember(state.phase) { mutableStateOf(false) }
    var otherText by remember(state.phase) { mutableStateOf("") }

    // Any phase change restarts the timer; an untouched candidate expires.
    // The timer pauses while the user is typing a label (otherEntryOpen).
    LaunchedEffect(state.phase, state.normalizedX, state.normalizedY, otherEntryOpen) {
        if (state.phase == TapPhase.IDLE || otherEntryOpen) return@LaunchedEffect
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
        val awaiting = state.phase == TapPhase.AWAITING_CONFIRMATION
        if (awaiting && onConfirmTyped != null && !otherEntryOpen) {
            LazyRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(QUICK_LABELS) { label ->
                    SuggestionChip(
                        onClick = { onConfirmTyped(label, false) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            labelColor = Color.White,
                        ),
                    )
                }
                items(listOf("Other…")) { _ ->
                    SuggestionChip(
                        onClick = { otherEntryOpen = true },
                        label = { Text("Other…", style = MaterialTheme.typography.labelMedium) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            labelColor = Color.White,
                        ),
                    )
                }
            }
        }
        if (awaiting && onConfirmTyped != null && otherEntryOpen) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = otherText,
                    onValueChange = { otherText = it },
                    singleLine = true,
                    placeholder = { Text("What is it?", color = Color.White.copy(alpha = 0.5f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = GccGreen,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Button(
                    onClick = { if (otherText.isNotBlank()) onConfirmTyped(otherText.trim(), true) },
                    enabled = otherText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                ) {
                    Text("Label", color = Color.White)
                }
            }
        }
        if (copy.confirmLabel != null && copy.rejectLabel != null && !otherEntryOpen) {
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
