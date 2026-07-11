package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** No screen-sleep concept for the desktop test/dev harness. */
actual fun setKeepScreenAwake(enabled: Boolean) {
    // Intentionally empty.
}

/** No-op on desktop. */
@Composable
actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        setKeepScreenAwake(true)
        onDispose { setKeepScreenAwake(false) }
    }
}
