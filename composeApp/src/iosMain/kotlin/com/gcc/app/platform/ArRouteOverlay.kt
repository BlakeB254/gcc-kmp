package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldTrackingConfiguration
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNMaterial
import platform.UIKit.UIColor

/**
 * iOS SceneKit-based AR overlay for the "Begin Route" experience.
 *
 * Uses `ARSCNView` (platform.ARKit) + SceneKit nodes rather than RealityKit:
 * RealityKit's modern Swift-only API surface does not reliably bridge through
 * Kotlin/Native's Objective-C interop, whereas SceneKit is fully
 * Objective-C-compatible and matches the cinterop pattern already established
 * by `ArScanner.kt` / `CameraManager.kt`.
 *
 * NOTE: This file cannot be compiled on the Linux DGX (Kotlin/Native iOS
 * targets are unavailable here). It is authored to the codebase's proven
 * cinterop conventions and is compiled + visually iterated on the MacBook
 * during Task 17, where a live ARSession is available to verify the
 * lat/lng -> AR-local-meters coordinate math this file intentionally defers.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ArRouteOverlay actual constructor() {

    private var sceneView: ARSCNView? = null
    private val fogNodes = mutableMapOf<Pair<Int, Int>, SCNNode>()
    private var boundaryNode: SCNNode? = null

    /** Called by the Compose UIKitView factory (Task 16) once the ARSCNView exists. */
    fun bind(view: ARSCNView) {
        sceneView = view
        val config = ARWorldTrackingConfiguration()
        view.session.runWithConfiguration(config)
    }

    actual fun start() {
        // Session start is driven by `bind`, called from the Compose UIKitView
        // factory in Task 16 — nothing to do here beyond what `bind` already does.
    }

    actual fun stop() {
        sceneView?.session?.pause()
    }

    /**
     * One SCNNode per unscanned cell, positioned at its real-world lat/lng
     * projected into the AR session's local coordinate space via the same
     * ARSession.currentFrame.camera.transform math ArScanner.getArHeading()
     * already uses. Scanned cells simply have their node removed (revealed).
     */
    actual fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {
        // `scene` is resolved to guard against calls before `bind`; node creation
        // uses it during Task 17 on-device iteration (see deferred note below).
        sceneView?.scene ?: return
        for ((cell, node) in fogNodes.toMap()) {
            if (cell in scannedCells) {
                node.removeFromParentNode()
                fogNodes.remove(cell)
            }
        }
        // DEFERRED to Task 17 (MacBook / live ARSession):
        // New unscanned cells are added by the caller invoking this with the
        // full unscanned set on each CoverageTracker update; node creation for
        // cells not yet in `fogNodes` uses a translucent grey SCNPlane sized to
        // the cell's real-world footprint (see cellSizeMeters, Task 14) and is
        // positioned via world-space translation computed from the AR session's
        // origin. This coordinate conversion (lat/lng -> AR-local meters) needs
        // ARSession.currentFrame.camera.transform captured at bind time as the
        // origin reference, and is finished during MacBook iteration where the
        // actual conversion math can be verified against a live session instead
        // of guessed blind. `cellCenter` supplies the per-cell lat/lng once that
        // origin math lands.
    }

    actual fun updateBoundaryStatus(isOutside: Boolean) {
        val node = boundaryNode ?: return
        val material = SCNMaterial()
        material.diffuse.contents = if (isOutside) {
            UIColor.redColor.colorWithAlphaComponent(0.35)
        } else {
            UIColor.whiteColor.colorWithAlphaComponent(0.12)
        }
        node.geometry?.materials = listOf(material)
    }

    actual fun updateHeading(degrees: Float) {
        // DEFERRED to Task 17: directional-arrow node rotation about the Y axis,
        // degrees -> radians. Wired to a screen-space or world-anchored arrow
        // node during on-device iteration, where the arrow's actual mesh/asset
        // is added and the rotation can be verified against a live session.
    }
}

/**
 * iOS host for [ArRouteOverlay]: a Compose `UIKitView` wrapping an `ARSCNView`.
 *
 * Mirrors the `UIKitView` factory pattern established by `CameraManager`'s
 * `CameraPreviewWithAVCapture`. The factory creates the `ARSCNView`, hands it to
 * [ArRouteOverlay.bind] (which starts the ARSession), and pauses the session on
 * release. `overlay.stop()` on release keeps the ARSession from running once the
 * view leaves the composition.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier) {
    UIKitView(
        factory = {
            val view = ARSCNView()
            overlay.bind(view)
            view
        },
        modifier = modifier,
        onRelease = {
            overlay.stop()
        },
    )
}
