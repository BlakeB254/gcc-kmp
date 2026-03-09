package com.gcc.app.ui.screens.admin

import kotlin.math.cos
import kotlin.math.pow

internal fun formatCoord(value: Double): String {
    val intPart = value.toInt()
    val fracPart = ((kotlin.math.abs(value) - kotlin.math.abs(intPart.toDouble())) * 10000).toInt()
    return "$intPart.${fracPart.toString().padStart(4, '0')}"
}

internal fun metersToPixels(meters: Double, lat: Double, zoom: Double): Float {
    val metersPerPixel = 156543.03392 * cos(lat * kotlin.math.PI / 180.0) / 2.0.pow(zoom)
    return (meters / metersPerPixel).toFloat()
}
