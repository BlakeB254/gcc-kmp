package com.gcc.app.platform

import platform.UIKit.UIApplication
import kotlinx.cinterop.ExperimentalForeignApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * `idleTimerDisabled` is a main-thread UIKit property. Compose calls
 * DisposableEffect on the main dispatcher, so no hop is needed here.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun setKeepScreenAwake(enabled: Boolean) {
    UIApplication.sharedApplication.idleTimerDisabled = enabled
}

/** Holds the display awake for as long as this composable is in composition. */
@Composable
actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        setKeepScreenAwake(true)
        onDispose { setKeepScreenAwake(false) }
    }
}
