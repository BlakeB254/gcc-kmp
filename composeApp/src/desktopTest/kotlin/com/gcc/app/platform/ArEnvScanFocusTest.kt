package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [viewTapToImageCoords] is the only piece of Task 5's tap-to-focus math
 * that can be pulled out of the ARKit-bound iosMain code and actually
 * tested (no simulator runs in this repo's environment, so the rest --
 * raycasting, saliency selection, the promotion gate -- has no harness).
 *
 * The round-trip below pins the inversion against the *documented* forward
 * mapping iosMain `analyzeTrash` uses (`viewX = 1 - imageY; viewY =
 * imageX`), written out literally here rather than imported, so a future
 * accidental edit to either side shows up as a test failure instead of
 * silently staying "consistent" with itself.
 */
class ArEnvScanFocusTest {

    /** Mirrors iosMain `analyzeTrash`'s image -> view projection exactly. */
    private fun imageToViewCoords(imageX: Double, imageY: Double): Pair<Double, Double> =
        (1.0 - imageY) to imageX

    @Test
    fun viewTapToImageCoordsInvertsTheDocumentedImageToViewMapping() {
        val cases = listOf(
            0.5 to 0.5,
            0.0 to 0.0,
            1.0 to 1.0,
            0.0 to 1.0,
            1.0 to 0.0,
            0.25 to 0.75,
        )
        for ((imageX, imageY) in cases) {
            val (viewX, viewY) = imageToViewCoords(imageX, imageY)
            val (recoveredImageX, recoveredImageY) = viewTapToImageCoords(viewX, viewY)
            assertEquals(imageX, recoveredImageX, "imageX round-trip for ($imageX, $imageY)")
            assertEquals(imageY, recoveredImageY, "imageY round-trip for ($imageX, $imageY)")
        }
    }

    @Test
    fun viewTapToImageCoordsMatchesHandComputedValues() {
        // A tap at the view's center maps to the image's center either way.
        assertEquals(0.5 to 0.5, viewTapToImageCoords(0.5, 0.5))
        // Top-left of the view (0,0) -> imageX = viewY = 0, imageY = 1 - viewX = 1.
        assertEquals(0.0 to 1.0, viewTapToImageCoords(0.0, 0.0))
        // Bottom-right of the view (1,1) -> imageX = 1, imageY = 0.
        assertEquals(1.0 to 0.0, viewTapToImageCoords(1.0, 1.0))
    }
}
