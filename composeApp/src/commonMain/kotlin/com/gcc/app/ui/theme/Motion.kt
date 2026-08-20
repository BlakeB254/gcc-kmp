package com.gcc.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gcc.app.platform.isReducedMotionEnabled

/**
 * Theme-level read of the platform reduced-motion setting (F9). Gate
 * continuous decorative motion (counters, shimmer, pulses, camera glides) on
 * this; state changes still apply instantly. Read once per composition —
 * the OS setting changing mid-session picks up on the next screen.
 */
@Composable
fun reducedMotionEnabled(): Boolean = remember { isReducedMotionEnabled() }
