package com.gcc.app.ui.screens.admin

// Re-export shared utilities for admin screens
import com.gcc.app.ui.map.formatCoord as sharedFormatCoord
import com.gcc.app.ui.map.metersToPixels as sharedMetersToPixels

internal fun formatCoord(value: Double): String = sharedFormatCoord(value)

internal fun metersToPixels(meters: Double, lat: Double, zoom: Double): Float =
    sharedMetersToPixels(meters, lat, zoom)
