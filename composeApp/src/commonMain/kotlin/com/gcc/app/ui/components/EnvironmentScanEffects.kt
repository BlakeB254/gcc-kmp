package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreen
import kotlinx.coroutines.delay

/**
 * "The environment is being scanned" feedback for the spot camera: a brief
 * full-screen green pulse fires whenever [creditTick] increments (a new zone
 * was credited or an item registered) — data registration made visible as it
 * happens. The old continuous green sweep-band was removed (WS6 declutter,
 * field feedback): it read as clutter without conveying anything the pulse and
 * the minimap don't already show.
 */
@Composable
fun EnvironmentScanEffects(
    active: Boolean,
    creditTick: Int,
    modifier: Modifier = Modifier,
) {
    if (!active) return

    // Registration pulse: flare on each credit, fade over ~300ms. Subtler than
    // before (peak 0.28 -> 0.18) now that it's the only effect on screen.
    var pulseAlpha by remember { mutableStateOf(0f) }
    LaunchedEffect(creditTick) {
        if (creditTick == 0) return@LaunchedEffect
        pulseAlpha = 0.18f
        while (pulseAlpha > 0.01f) {
            delay(30)
            pulseAlpha *= 0.8f
        }
        pulseAlpha = 0f
    }
    if (pulseAlpha <= 0f) return
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = GccGreen.copy(alpha = pulseAlpha))
    }
}

/**
 * Transient bottom-center popup ("Trash tagged on the map", "Hold steady",
 * …). Shows [message] whenever it changes, auto-hides after ~2.2s.
 */
@Composable
fun ScanToast(
    message: String?,
    modifier: Modifier = Modifier,
) {
    var visibleMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        visibleMessage = message
        delay(2200)
        visibleMessage = null
    }
    val text = visibleMessage ?: return
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 110.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }
    }
}
