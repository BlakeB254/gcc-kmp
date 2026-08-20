package com.gcc.app.platform

/**
 * True when the OS asks apps to minimize animation (iOS Settings →
 * Accessibility → Reduce Motion; Android "Remove animations" / animator
 * duration scale 0). Gate continuous decorative motion (counters, shimmer,
 * pulses, camera glides) on this — state changes still apply instantly.
 *
 * Platforms with no such setting return a safe default of `false`.
 */
expect fun isReducedMotionEnabled(): Boolean
