package com.gcc.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Heat level color system for spot density visualization.
 * Adapted from SCGO's nightlife heat levels, recolored for GCC's
 * cleanliness context (green = clean, red = dirty).
 */
object HeatColors {
    // Spot density heat levels (low → high)
    val Clean = Color(0xFF4CAF50)       // Green — area is clean
    val Mild = Color(0xFF8BC34A)        // Light green — few spots
    val Moderate = Color(0xFFFFC107)    // Amber — moderate density
    val Heavy = Color(0xFFFF9800)       // Orange — many spots
    val Critical = Color(0xFFF44336)    // Red — critical density

    // Glass morphism for overlays (from SCGO)
    val GlassBackground = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x33FFFFFF)
    val GlassDark = Color(0x66000000)

    /**
     * Map a spot count to a heat color.
     * @param count Number of active spots in the area
     */
    fun forSpotCount(count: Int): Color = when {
        count == 0 -> Clean
        count <= 2 -> Mild
        count <= 5 -> Moderate
        count <= 10 -> Heavy
        else -> Critical
    }

    /**
     * Map a cleanliness score (0-100) to a color.
     * Higher score = cleaner area.
     */
    fun forCleanlinessScore(score: Int): Color = when {
        score >= 80 -> Clean
        score >= 60 -> Mild
        score >= 40 -> Moderate
        score >= 20 -> Heavy
        else -> Critical
    }
}
