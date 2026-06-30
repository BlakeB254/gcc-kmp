package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Floating Apple-Weather-style intensity legend (bottom = Light, top = Extreme). */
@Composable
fun PrecipitationLegend(modifier: Modifier = Modifier) {
    // Top-to-bottom = Extreme → Light, matching the vertical bar.
    val rampTopToBottom = listOf(
        MapStyles.SPOT_HEAT_PEAK, MapStyles.SPOT_HEAT_NEAR_PEAK, MapStyles.SPOT_HEAT_EXTREME,
        MapStyles.SPOT_HEAT_CRITICAL, MapStyles.SPOT_HEAT_HIGH, MapStyles.SPOT_HEAT_MEDIUM,
        MapStyles.SPOT_HEAT_LOW, MapStyles.SPOT_HEAT_EDGE,
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.95f))
            .padding(10.dp),
    ) {
        Box(
            Modifier.width(12.dp).height(96.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(rampTopToBottom)),
        )
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.height(96.dp)) {
            listOf("Extreme", "Heavy", "Moderate", "Light").forEach {
                Text(it, fontSize = 11.sp, color = Color(0xFF525252))
            }
        }
    }
}
