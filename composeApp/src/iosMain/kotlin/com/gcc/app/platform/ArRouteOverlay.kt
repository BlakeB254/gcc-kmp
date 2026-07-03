package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.flow.StateFlow
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.SceneKit.SCNLookAtConstraint
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNPlane
import platform.SceneKit.SCNPyramid
import platform.SceneKit.SCNVector3Make
import platform.UIKit.UIColor
import platform.darwin.simd_float4x4
import kotlin.math.PI
import kotlin.math.cos

/**
 * iOS SceneKit-based AR overlay for the "Begin Route" experience.
 *
 * Uses `ARSCNView` (platform.ARKit) + SceneKit nodes rather than RealityKit:
 * RealityKit's modern Swift-only API surface does not reliably bridge through
 * Kotlin/Native's Objective-C interop, whereas SceneKit is fully
 * Objective-C-compatible and matches the cinterop pattern already established
 * by `ArScanner.kt` / `CameraManager.kt`.
 *
 * Coordinate model (the math Task 17 deferred, now implemented):
 * the ARSession runs with [ARWorldAlignment.ARWorldAlignmentGravityAndHeading]
 * (same alignment `ArScanner` already uses), so AR world axes are compass-true:
 * +X = east, -Z = true north, +Y = up. [updateGeoReference] pairs each GPS fix
 * with the camera's AR-space position at that moment; a cell at (lat, lng) is
 * then placed at `refPosition + (eastMeters, 0, -northMeters)` using the
 * standard meters-per-degree scale at the reference latitude. Re-pairing on
 * every fix keeps world placement self-correcting against ARKit drift.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ArRouteOverlay actual constructor() {

    companion object {
        /** Only render fog planes within this radius of the worker — caps node count. */
        private const val FOG_RENDER_RADIUS_METERS = 35.0
        /** Hard ceiling on live fog nodes, protects the SceneKit frame rate. */
        private const val MAX_FOG_NODES = 600
        /** Fog planes sit this far below the camera (approx. phone-at-chest ground offset). */
        private const val GROUND_OFFSET_METERS = 1.4f
        private const val METERS_PER_DEG_LAT = 111_320.0
    }

    private var sceneView: ARSCNView? = null
    private val fogNodes = mutableMapOf<Pair<Int, Int>, SCNNode>()
    private var boundaryVeil: SCNNode? = null
    private var arrowNode: SCNNode? = null
    private var frontierTarget: SCNNode? = null

    private val sessionHealth = ArSessionHealth()
    actual val trackingState: StateFlow<TrackingState> = sessionHealth.trackingState

    // Latest GPS fix <-> AR camera position pairing (see class KDoc).
    private var hasGeoRef = false
    private var refLat = 0.0
    private var refLng = 0.0
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    private var lngScale = 1.0 // cos(refLat), cached when the reference updates

    /** Called by the Compose UIKitView factory (Task 16) once the ARSCNView exists. */
    fun bind(view: ARSCNView) {
        sceneView = view
        val config = ARWorldTrackingConfiguration()
        config.worldAlignment = ARWorldAlignment.ARWorldAlignmentGravityAndHeading
        view.session.runWithConfiguration(config)
        sessionHealth.bind(view.session)
    }

    actual fun start() {
        // Session start is driven by `bind`, called from the Compose UIKitView
        // factory in Task 16 — nothing to do here beyond what `bind` already does.
    }

    actual fun stop() {
        sceneView?.session?.pause()
    }

    /**
     * Pair the worker's latest GPS fix with the camera's current AR position.
     * Must be called on every location update (ArRouteScreen wires this) —
     * fog/arrow placement is a no-op until the first pairing exists.
     */
    actual fun updateGeoReference(lat: Double, lng: Double) {
        val position = cameraPosition() ?: return
        refLat = lat
        refLng = lng
        refX = position[0]
        refY = position[1]
        refZ = position[2]
        lngScale = cos(lat * PI / 180.0)
        hasGeoRef = true
    }

    /**
     * One translucent SCNPlane per unscanned cell near the worker; cells no
     * longer in [unscannedCells] (scanned = revealed) have their node removed.
     * Also retargets the guidance arrow at the nearest unscanned cell.
     */
    actual fun updateFog(unscannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {
        val scene = sceneView?.scene ?: return
        if (!hasGeoRef) return

        // Reveal: drop nodes for cells that are no longer unscanned.
        for ((cell, node) in fogNodes.toMap()) {
            if (cell !in unscannedCells) {
                node.removeFromParentNode()
                fogNodes.remove(cell)
            }
        }

        // Cover: create planes for nearby unscanned cells that lack one.
        var nearestDistSq = Double.MAX_VALUE
        var nearestLocal: Pair<Double, Double>? = null
        for (cell in unscannedCells) {
            val (lat, lng) = cellCenter(cell.first, cell.second)
            val local = latLngToLocal(lat, lng)
            val dx = local.first - refX.toDouble()
            val dz = local.second - refZ.toDouble()
            val distSq = dx * dx + dz * dz
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearestLocal = local
            }
            if (cell in fogNodes) continue
            if (distSq > FOG_RENDER_RADIUS_METERS * FOG_RENDER_RADIUS_METERS) continue
            if (fogNodes.size >= MAX_FOG_NODES) continue
            fogNodes[cell] = makeFogNode(local.first.toFloat(), local.second.toFloat()).also {
                scene.rootNode.addChildNode(it)
            }
        }

        updateFrontierTarget(nearestLocal)
    }

    actual fun updateBoundaryStatus(isOutside: Boolean) {
        val veil = boundaryVeil ?: makeBoundaryVeil() ?: return
        veil.hidden = !isOutside
    }

    // ── SceneKit node construction ───────────────────────────────────

    private fun makeFogNode(x: Float, z: Float): SCNNode {
        val plane = SCNPlane.planeWithWidth(
            CoverageTracker.cellSizeMeters,
            CoverageTracker.cellSizeMeters,
        )
        val material = SCNMaterial()
        material.diffuse.contents = UIColor.grayColor.colorWithAlphaComponent(0.45)
        material.doubleSided = true
        plane.materials = listOf(material)

        val node = SCNNode.nodeWithGeometry(plane)
        // SCNPlane is vertical (XY) by default — lay it flat on the ground.
        node.eulerAngles = SCNVector3Make((-PI / 2).toFloat(), 0f, 0f)
        node.position = SCNVector3Make(x, refY - GROUND_OFFSET_METERS, z)
        return node
    }

    /**
     * Camera-attached red veil shown while the worker is outside the assigned
     * polygon. Lazily created because `pointOfView` only exists once the
     * ARSCNView has started rendering.
     */
    private fun makeBoundaryVeil(): SCNNode? {
        val camera = sceneView?.pointOfView ?: return null
        val plane = SCNPlane.planeWithWidth(2.0, 2.0)
        val material = SCNMaterial()
        material.diffuse.contents = UIColor.redColor.colorWithAlphaComponent(0.35)
        material.doubleSided = true
        plane.materials = listOf(material)

        val node = SCNNode.nodeWithGeometry(plane)
        node.position = SCNVector3Make(0f, 0f, -0.8f)
        node.hidden = true
        camera.addChildNode(node)
        boundaryVeil = node
        return node
    }

    /**
     * Guidance arrow: a camera-attached pyramid with an SCNLookAtConstraint
     * aimed at an invisible node on the nearest unscanned frontier. SceneKit
     * re-evaluates the constraint every frame, so the arrow tracks smoothly
     * between GPS/coverage ticks with no per-frame Kotlin work.
     */
    private fun updateFrontierTarget(local: Pair<Double, Double>?) {
        val scene = sceneView?.scene ?: return
        if (local == null) {
            arrowNode?.hidden = true
            return
        }

        val target = frontierTarget ?: SCNNode().also {
            frontierTarget = it
            scene.rootNode.addChildNode(it)
        }
        target.position = SCNVector3Make(
            local.first.toFloat(),
            refY - GROUND_OFFSET_METERS,
            local.second.toFloat(),
        )

        val arrow = arrowNode ?: makeArrowNode(target) ?: return
        arrow.hidden = false
    }

    private fun makeArrowNode(target: SCNNode): SCNNode? {
        val camera = sceneView?.pointOfView ?: return null
        val pyramid = SCNPyramid.pyramidWithWidth(0.12, 0.25, 0.12)
        val material = SCNMaterial()
        material.diffuse.contents = UIColor.greenColor
        pyramid.materials = listOf(material)

        // SCNPyramid points +Y; rotate so the tip points -Z, the axis
        // SCNLookAtConstraint aims at its target.
        val geometryNode = SCNNode.nodeWithGeometry(pyramid)
        geometryNode.eulerAngles = SCNVector3Make((-PI / 2).toFloat(), 0f, 0f)

        val container = SCNNode()
        container.addChildNode(geometryNode)
        container.position = SCNVector3Make(0f, -0.5f, -1.2f)
        val constraint = SCNLookAtConstraint.lookAtConstraintWithTarget(target)
        constraint.gimbalLockEnabled = true
        container.constraints = listOf(constraint)

        camera.addChildNode(container)
        arrowNode = container
        return container
    }

    // ── Coordinate math ──────────────────────────────────────────────

    /** (lat, lng) -> AR-local (x, z) meters, via the current geo reference. */
    private fun latLngToLocal(lat: Double, lng: Double): Pair<Double, Double> {
        val northMeters = (lat - refLat) * METERS_PER_DEG_LAT
        val eastMeters = (lng - refLng) * METERS_PER_DEG_LAT * lngScale
        return (refX + eastMeters) to (refZ - northMeters)
    }

    /** Current camera world position [x, y, z], or null before the first AR frame. */
    private fun cameraPosition(): List<Float>? {
        val transform = sceneView?.session?.currentFrame?.camera?.transform ?: return null
        val floats = matrixFloats(transform)
        // Column-major simd_float4x4: translation lives at indices 12..14.
        return listOf(floats[12], floats[13], floats[14])
    }

    private fun matrixFloats(transform: CValue<simd_float4x4>): List<Float> = memScoped {
        val matrix = alloc<simd_float4x4>()
        transform.place(matrix.ptr)
        val values = matrix.ptr.reinterpret<FloatVar>()
        List(16) { index -> values[index] }
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
