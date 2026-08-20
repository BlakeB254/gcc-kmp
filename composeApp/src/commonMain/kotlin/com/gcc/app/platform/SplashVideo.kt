package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Full-bleed splash hero video (plan §5 Splash v2).
 *
 * iOS hosts an `AVPlayer` (non-looping, aspect-fill, no transport controls)
 * playing a bundled resource named [assetName] and calls [onFinished] when
 * playback reaches the end. Platforms without a player — and iOS when the
 * asset isn't bundled — call [onFinished] immediately so the caller can fall
 * back to the Canvas splash animation seamlessly.
 */
@Composable
expect fun SplashVideoPlayer(assetName: String, onFinished: () -> Unit, modifier: Modifier = Modifier)
