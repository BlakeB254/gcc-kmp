package com.gcc.app.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Renders isometric building shapes on a Canvas at street-level zoom.
 *
 * Buildings are drawn as simple parallelogram extrusions from rectangular
 * footprints. Each building has three visible faces:
 * - Top face (lightest shade — MapStyles.BUILDING_TOP)
 * - Front face (medium shade — MapStyles.BUILDING_FRONT)
 * - Side face (darkest shade — MapStyles.BUILDING_SIDE)
 *
 * Only buildings whose footprints fall within the visible viewport
 * are rendered, for performance.
 *
 * The isometric offset creates a southeast-to-northwest viewing angle,
 * consistent with conventional map isometric projections.
 */
object BuildingRenderer {

    /** Pixels per building story for isometric height extrusion. */
    private const val PX_PER_STORY = 5f

    /** Isometric angle offset factors (dx, dy per story). */
    private const val ISO_DX = -3f
    private const val ISO_DY = -4f

    /**
     * Draw all buildings visible in the current viewport.
     *
     * @param buildings    The list of building footprints to consider
     * @param centerLat    Current map center latitude
     * @param centerLng    Current map center longitude
     * @param zoom         Current zoom level (should be >= ZOOM_STREET)
     * @param canvasWidth  Canvas pixel width
     * @param canvasHeight Canvas pixel height
     * @param offsetX      Gesture-based pan X offset
     * @param offsetY      Gesture-based pan Y offset
     * @param scale        Gesture-based zoom scale factor
     */
    fun DrawScope.drawBuildings(
        buildings: List<BuildingFootprint>,
        centerLat: Double,
        centerLng: Double,
        zoom: Double,
        canvasWidth: Float,
        canvasHeight: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        scale: Float = 1f,
    ) {
        // Viewport bounds in canvas coordinates (expanded by margin for partially-visible buildings)
        val margin = 50f
        val viewLeft = -margin
        val viewTop = -margin
        val viewRight = canvasWidth + margin
        val viewBottom = canvasHeight + margin

        for (building in buildings) {
            // Convert building corners to canvas pixels
            val nw = latLngToPixel(building.north, building.west, centerLat, centerLng, zoom, canvasWidth, canvasHeight)
            val se = latLngToPixel(building.south, building.east, centerLat, centerLng, zoom, canvasWidth, canvasHeight)

            // Quick viewport culling (pre-transform check)
            val bLeft = minOf(nw.x, se.x)
            val bRight = maxOf(nw.x, se.x)
            val bTop = minOf(nw.y, se.y)
            val bBottom = maxOf(nw.y, se.y)

            if (bRight < viewLeft || bLeft > viewRight || bBottom < viewTop || bTop > viewBottom) {
                continue
            }

            drawIsometricBuilding(
                topLeftGround = Offset(bLeft, bTop),
                bottomRightGround = Offset(bRight, bBottom),
                stories = building.height,
            )
        }
    }

    /**
     * Draws a single isometric building with three visible faces.
     */
    private fun DrawScope.drawIsometricBuilding(
        topLeftGround: Offset,
        bottomRightGround: Offset,
        stories: Int,
    ) {
        val heightPx = stories * PX_PER_STORY
        val dx = stories * ISO_DX
        val dy = stories * ISO_DY

        // Ground rectangle corners
        val gTL = topLeftGround
        val gTR = Offset(bottomRightGround.x, topLeftGround.y)
        val gBR = bottomRightGround
        val gBL = Offset(topLeftGround.x, bottomRightGround.y)

        // Roof rectangle corners (offset by isometric projection)
        val rTL = Offset(gTL.x + dx, gTL.y + dy)
        val rTR = Offset(gTR.x + dx, gTR.y + dy)
        val rBR = Offset(gBR.x + dx, gBR.y + dy)
        val rBL = Offset(gBL.x + dx, gBL.y + dy)

        // --- Side face (left wall) ---
        val sidePath = Path().apply {
            moveTo(gBL.x, gBL.y)
            lineTo(gTL.x, gTL.y)
            lineTo(rTL.x, rTL.y)
            lineTo(rBL.x, rBL.y)
            close()
        }
        drawPath(path = sidePath, color = MapStyles.BUILDING_SIDE)
        drawPath(path = sidePath, color = MapStyles.BUILDING_STROKE, style = Stroke(width = 0.5f))

        // --- Front face (bottom wall) ---
        val frontPath = Path().apply {
            moveTo(gBL.x, gBL.y)
            lineTo(gBR.x, gBR.y)
            lineTo(rBR.x, rBR.y)
            lineTo(rBL.x, rBL.y)
            close()
        }
        drawPath(path = frontPath, color = MapStyles.BUILDING_FRONT)
        drawPath(path = frontPath, color = MapStyles.BUILDING_STROKE, style = Stroke(width = 0.5f))

        // --- Top face (roof) ---
        val topPath = Path().apply {
            moveTo(rTL.x, rTL.y)
            lineTo(rTR.x, rTR.y)
            lineTo(rBR.x, rBR.y)
            lineTo(rBL.x, rBL.y)
            close()
        }
        drawPath(path = topPath, color = MapStyles.BUILDING_TOP)
        drawPath(path = topPath, color = MapStyles.BUILDING_STROKE, style = Stroke(width = 0.5f))
    }
}
