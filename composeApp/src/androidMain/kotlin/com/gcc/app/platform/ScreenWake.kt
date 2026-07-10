package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * No-op. Android's FLAG_KEEP_SCREEN_ON needs the host Activity's Window,
 * which this platform layer does not hold. This increment is iOS-first
 * (spec non-goal: "Android raw reconstruction capture in this increment");
 * Android only has to compile.
 */
actual fun setKeepScreenAwake(enabled: Boolean) {
    // Intentionally empty — see KDoc.
}

/** No-op on Android. */
@Composable
actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        setKeepScreenAwake(true)
        onDispose { setKeepScreenAwake(false) }
    }
}
