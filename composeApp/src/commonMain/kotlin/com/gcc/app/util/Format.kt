package com.gcc.app.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Formats a Double to a string with a fixed number of decimal places.
 *
 * This is a KMP-compatible replacement for JVM-only `"%.Nf".format(value)`.
 * Example: `3.14159.fmt(2)` returns `"3.14"`.
 */
fun Double.fmt(decimals: Int): String {
    val factor = 10.0.pow(decimals).toLong()
    val rounded = (this * factor).roundToLong()
    val whole = rounded / factor
    val frac = abs(rounded % factor)
    return if (decimals == 0) "$whole"
    else "$whole.${frac.toString().padStart(decimals, '0')}"
}

/**
 * Pads an Int to at least 2 digits with a leading zero.
 *
 * KMP-compatible replacement for `"%02d".format(value)`.
 * Example: `5.padTwo()` returns `"05"`, `42.padTwo()` returns `"42"`.
 */
fun Int.padTwo(): String = this.toString().padStart(2, '0')
