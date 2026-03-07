package com.gcc.shared.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Shared formatting utilities for GCC apps.
 */
object GccFormatters {

    /** Format cents (Int) as dollar string, e.g. 1250 -> "$12.50" */
    fun formatCents(cents: Int): String {
        val dollars = cents / 100
        val remainder = (cents % 100).toString().padStart(2, '0')
        return "$$dollars.$remainder"
    }

    /** Format cents (Long) as dollar string, e.g. 1250L -> "$12.50" */
    fun formatCentsLong(cents: Long): String {
        val dollars = cents / 100
        val remainder = (cents % 100).toString().padStart(2, '0')
        return "$$dollars.$remainder"
    }

    /** Format distance in meters to human-readable string */
    fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> "${(meters / 1000.0).fmt(1)} km"
        else -> "${meters.toInt()} m"
    }

    /** Format duration in minutes to human-readable string */
    fun formatDuration(minutes: Int): String = when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

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
