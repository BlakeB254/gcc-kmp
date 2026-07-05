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
import androidx.compose.ui.unit.dp

/**
 * Small status pill shown while the OutdoorGate is still UNCERTAIN: the
 * camera preview runs but capture/credit is locked until outdoors is proven
 * (deny-by-default). Clears within ~1-2s for a legitimately outdoor worker.
 */
@Composable
fun ConfirmingOutdoorsChip(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Text(
        text = "Confirming you're outside…",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
