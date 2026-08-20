package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** iOS: Settings → Accessibility → Motion → Reduce Motion. */
@OptIn(ExperimentalForeignApi::class)
actual fun isReducedMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
