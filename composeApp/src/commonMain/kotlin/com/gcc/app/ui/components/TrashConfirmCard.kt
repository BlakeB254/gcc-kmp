package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.TrashCandidate
import com.gcc.app.platform.TrashItemCatalog
import com.gcc.app.ui.theme.GccGreen

/** Types offered as one-tap corrections on the confirm card. */
private val QUICK_TYPES = listOf(
    "plastic_bottle", "glass_bottle", "can", "plastic_bag",
    "cup", "paper", "wrapper", "other",
)

/**
 * Close-range confirm card for a locked AR trash candidate: shows the
 * classifier's type with the LiDAR-derived size/weight estimate, offers
 * one-tap type correction chips, and Confirm / Not trash actions.
 */
@Composable
fun TrashConfirmCard(
    candidate: TrashCandidate,
    onConfirm: (selectedItemType: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reset the selection whenever a new candidate locks.
    var selectedType by remember(candidate.id) { mutableStateOf(candidate.itemType) }
    val info = TrashItemCatalog.infoFor(selectedType)
    val weightGrams = TrashItemCatalog.estimateWeightGrams(selectedType, candidate.estimatedWidthMeters)
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
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(QUICK_TYPES.size) { index ->
                val type = QUICK_TYPES[index]
                val selected = type == selectedType
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) GccGreen else Color.White.copy(alpha = 0.12f))
                        .clickable { selectedType = type }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = TrashItemCatalog.infoFor(type).displayName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = Color.White,
                    )
                }
            }
        }
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
                    .clickable { onConfirm(selectedType) }
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
