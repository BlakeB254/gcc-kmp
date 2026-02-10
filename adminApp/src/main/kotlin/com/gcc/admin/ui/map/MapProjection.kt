package com.gcc.admin.ui.map

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

object MapProjection {
    fun latLngToPixel(
        lat: Double, lng: Double,
        centerLat: Double, centerLng: Double,
        zoom: Double, canvasWidth: Float, canvasHeight: Float,
    ): Offset {
        val scale = (256.0 * (1 shl zoom.toInt())).toFloat()
        val worldX = ((lng + 180.0) / 360.0 * scale).toFloat()
        val worldY = ((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
        val centerWorldX = ((centerLng + 180.0) / 360.0 * scale).toFloat()
        val centerWorldY = ((1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
        return Offset(
            x = canvasWidth / 2f + (worldX - centerWorldX),
            y = canvasHeight / 2f + (worldY - centerWorldY),
        )
    }

    fun pixelToLat(pixelY: Float, centerLat: Double, zoom: Double, canvasHeight: Float): Double {
        val scale = (256.0 * (1 shl zoom.toInt()))
        val centerWorldY = (1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale
        val worldY = centerWorldY + (pixelY - canvasHeight / 2.0)
        val n = PI - 2.0 * PI * worldY / scale
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    fun pixelToLng(pixelX: Float, centerLng: Double, zoom: Double, canvasWidth: Float): Double {
        val scale = (256.0 * (1 shl zoom.toInt()))
        val centerWorldX = (centerLng + 180.0) / 360.0 * scale
        val worldX = centerWorldX + (pixelX - canvasWidth / 2.0)
        return worldX / scale * 360.0 - 180.0
    }

    fun tileX(lng: Double, zoom: Int): Int =
        floor((lng + 180.0) / 360.0 * (1 shl zoom).toDouble()).toInt()

    fun tileY(lat: Double, zoom: Int): Int {
        val latRad = lat * PI / 180.0
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom).toDouble()).toInt()
    }

    fun tileLng(x: Int, zoom: Int): Double =
        x.toDouble() / (1 shl zoom).toDouble() * 360.0 - 180.0

    fun tileLat(y: Int, zoom: Int): Double {
        val n = PI - 2.0 * PI * y.toDouble() / (1 shl zoom).toDouble()
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }
}
