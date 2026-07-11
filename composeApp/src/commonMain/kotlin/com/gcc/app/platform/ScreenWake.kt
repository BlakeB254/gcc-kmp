package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Hold the display awake. The environment scan runs for 60–90 seconds with no
 * touch input by design; without this the screen dims and users tap the
 * viewfinder purely to wake it, which used to fire a silent frame capture
 * (see [com.gcc.app.ui.components.CameraCapture]).
 *
 * Always paired: every `true` must be followed by a `false`. Prefer
 * [KeepScreenAwake], which pairs them for you.
 */
expect fun setKeepScreenAwake(enabled: Boolean)

/** Holds the display awake for as long as this composable is in composition. */
@Composable
expect fun KeepScreenAwake()
