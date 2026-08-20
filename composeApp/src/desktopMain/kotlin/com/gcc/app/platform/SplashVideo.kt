package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * Desktop stub — no bundled video surface; renders nothing and finishes
 * immediately so the caller falls back to the Canvas splash animation.
 */
@Composable
actual fun SplashVideoPlayer(assetName: String, onFinished: () -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) { onFinished() }
}
