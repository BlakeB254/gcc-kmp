package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
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
import com.gcc.app.platform.TrashCandidate
import com.gcc.app.platform.TrashItemCatalog
import com.gcc.app.ui.theme.GccGreen

/**
 * Close-range confirm card for a locked AR trash candidate: one question,
 * two answers. No type-selection UI (Blake 2026-07-28: "we don't want to
 * ask the users anymore" — the backend analysis/reconstruction pipeline
 * owns typing and mass estimation; the classifier's guess shown here is
 * informational and rides the manifest as a hint, nothing more).
 */
@Composable
fun TrashConfirmCard(
    candidate: TrashCandidate,
    onConfirm: (selectedItemType: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = TrashItemCatalog.infoFor(candidate.itemType)
    val weightGrams = TrashItemCatalog.estimateWeightGrams(candidate.itemType, candidate.estimatedWidthMeters)
    val weightText = if (weightGrams >= 1000) {
        "~${(weightGrams / 100).toDouble() / 10} kg"
    } else {
        "~$weightGrams g"
    }
    val sizeText = candidate.estimatedWidthMeters?.let { "${(it * 100).toInt()} cm" }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = buildString {
                append(info.displayName)
                sizeText?.let { append(" · $it") }
                append(" · $weightText")
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Text(
            text = "${candidate.distanceMeters.toInt()}m away — is this trash?",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { onDismiss() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("Not trash", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(GccGreen)
                    .clickable { onConfirm(candidate.itemType) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    "Confirm ✓",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}
