package com.gcc.app.util

/**
 * Backward-compatibility delegates to shared formatters.
 * New code should import directly from com.gcc.shared.util.
 */

import com.gcc.shared.util.fmt as sharedFmt
import com.gcc.shared.util.padTwo as sharedPadTwo

fun Double.fmt(decimals: Int): String = this.sharedFmt(decimals)
fun Int.padTwo(): String = this.sharedPadTwo()
