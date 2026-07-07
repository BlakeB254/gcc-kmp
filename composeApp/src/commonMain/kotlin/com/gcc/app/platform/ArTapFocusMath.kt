package com.gcc.app.platform

/**
 * Inverts the env-scan's image→view reticle mapping so a [ArEnvScanOverlay.focusAt]
 * tap can be raycast against the same normalized image-space coordinates
 * (top-left origin) the Vision saliency bounding boxes and `raycastImage` use.
 *
 * The iosMain `analyzeTrash` projects a raycast image-space point to a view
 * point with `viewX = 1 - imageY; viewY = imageX` (the camera sensor's
 * native orientation is landscape, rotated 90° relative to the portrait
 * screen the reticle draws on). This is the inverse of that mapping, solved
 * algebraically:
 * ```
 * viewX = 1 - imageY   =>   imageY = 1 - viewX
 * viewY = imageX       =>   imageX = viewY
 * ```
 * Pure/platform-agnostic and given its own file (rather than left inline in
 * iosMain, or folded into commonMain's ArEnvScan.kt) specifically so it has
 * a desktopTest — the surrounding ARKit code has no test harness (no
 * simulator runs in this repo's environment), but getting this inversion
 * backwards is the classic bug in tap-to-focus features, so the arithmetic
 * itself is worth pinning down with a test. (Living in its own file also
 * sidesteps a real Kotlin/JVM gotcha: a top-level function here sharing
 * ArEnvScan.kt's filename with the platform actuals would collide on the
 * generated `ArEnvScanKt` JVM facade class when the desktop target compiles
 * commonMain + desktopMain together.)
 */
fun viewTapToImageCoords(viewX: Double, viewY: Double): Pair<Double, Double> =
    viewY to (1.0 - viewX)
