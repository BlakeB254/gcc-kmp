package com.gcc.shared.geo

import kotlin.math.*

/**
 * Web Mercator projection utilities for converting between lat/lng
 * geographic coordinates and pixel coordinates on a slippy-map canvas.
 *
 * Used by both the admin (desktop) and worker (mobile/desktop) map composables.
 */
object MapProjection {

    /**
     * Convert lat/lng to pixel coordinates relative to a canvas center.
     *
     * @param lat       Latitude to project
     * @param lng       Longitude to project
     * @param centerLat Center latitude of the viewport
     * @param centerLng Center longitude of the viewport
     * @param zoom      Tile zoom level
     * @param canvasWidth  Canvas width in pixels
     * @param canvasHeight Canvas height in pixels
     * @return Pair of (pixelX, pixelY) relative to the canvas origin
     */
    fun latLngToPixel(
        lat: Double, lng: Double,
        centerLat: Double, centerLng: Double,
        zoom: Double, canvasWidth: Float, canvasHeight: Float,
    ): Pair<Float, Float> {
        val scale = (256.0 * (1 shl zoom.toInt())).toFloat()
        val worldX = ((lng + 180.0) / 360.0 * scale).toFloat()
        val worldY = ((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
        val centerWorldX = ((centerLng + 180.0) / 360.0 * scale).toFloat()
        val centerWorldY = ((1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
        return Pair(
            canvasWidth / 2f + (worldX - centerWorldX),
            canvasHeight / 2f + (worldY - centerWorldY),
        )
    }

    /**
     * Inverse Mercator: convert pixel Y to latitude.
     */
    fun pixelToLat(
        pixelY: Float,
        centerLat: Double,
        zoom: Double,
        canvasHeight: Float,
    ): Double {
        val scale = (256.0 * (1 shl zoom.toInt()))
        val centerWorldY = (1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale
        val worldY = centerWorldY + (pixelY - canvasHeight / 2.0)
        val n = PI - 2.0 * PI * worldY / scale
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /**
     * Inverse Mercator: convert pixel X to longitude.
     */
    fun pixelToLng(
        pixelX: Float,
        centerLng: Double,
        zoom: Double,
        canvasWidth: Float,
    ): Double {
        val scale = (256.0 * (1 shl zoom.toInt()))
        val centerWorldX = (centerLng + 180.0) / 360.0 * scale
        val worldX = centerWorldX + (pixelX - canvasWidth / 2.0)
        return worldX / scale * 360.0 - 180.0
    }
}
