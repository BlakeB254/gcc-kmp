package com.gcc.app.platform

/**
 * Safe default: this increment is iOS-first (same stance as
 * [setKeepScreenAwake]); the platform layer here holds no Context to read
 * `Settings.Global.ANIMATOR_DURATION_SCALE` with, so Android reports no
 * reduced-motion preference. Android only has to compile.
 */
actual fun isReducedMotionEnabled(): Boolean = false
