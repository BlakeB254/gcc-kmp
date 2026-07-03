package com.gcc.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Live session-points pill for the AR HUD. Estimate only — the completion
 * screen shows the server-authoritative total.
 */
@Composable
fun SessionPointsPill(
    points: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "≈ $points pts",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = GccGreen,
        )
    }
}

/** One floating "+N pts" burst. */
data class PointsPip(val id: Long, val amount: Int)

/**
 * Hosts floating "+N pts" pips that drift up and fade — the in-the-moment
 * reward feedback while coverage points accrue. Render centered over the
 * camera view; expired pips are reported back for removal.
 */
@Composable
fun PointsPipOverlay(
    pips: List<PointsPip>,
    onExpired: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        pips.forEach { pip ->
            key(pip.id) {
                FloatingPip(pip = pip, onExpired = onExpired)
            }
        }
    }
}

@Composable
private fun FloatingPip(
    pip: PointsPip,
    onExpired: (Long) -> Unit,
) {
    val offsetY = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(pip.id) {
        launch { offsetY.animateTo(-140f, animationSpec = tween(durationMillis = 1200)) }
        alpha.animateTo(0f, animationSpec = tween(durationMillis = 1200))
        onExpired(pip.id)
    }
    Text(
        text = "+${pip.amount} pts",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = GccGreen,
        modifier = Modifier
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .alpha(alpha.value),
    )
}
