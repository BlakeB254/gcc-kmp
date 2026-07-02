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
import platform.SceneKit.SCNCylinder
import platform.SceneKit.SCNLookAtConstraint
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNPyramid
import platform.SceneKit.SCNVector3Make
import platform.UIKit.UIColor
import platform.darwin.simd_float4x4
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS SceneKit-based AR overlay for spot-scan mode: a ring of sector markers
 * around the litter item showing which capture angles remain, plus a look-at
 * guidance arrow toward the nearest uncovered marker.
 *
 * Uses the same coordinate model as ArRouteOverlay (see its KDoc):
 * ARWorldAlignmentGravityAndHeading makes AR axes compass-true, and
 * [updateGeoReference] pairs each GPS fix with the camera's AR position so
 * the item's lat/lng projects to `refPosition + (eastMeters, 0, -northMeters)`.
 * Structurally parallel to ArRouteOverlay by design — see the 2026-07-02
 * plan's "Deviation" note.
 */
@OptIn(ExperimentalForeignApi::class)
actual class SpotArOverlay actual constructor() {

    companion object {
        /** Radius of the sector-marker ring around the item, in meters. */
        private const val MARKER_RING_RADIUS_METERS = 2.5
        /** Marker posts sit this far below the camera (phone-at-chest ground offset). */
        private const val GROUND_OFFSET_METERS = 1.4f
        private const val MARKER_HEIGHT_METERS = 0.5
        private const val METERS_PER_DEG_LAT = 111_320.0
    }

    private var sceneView: ARSCNView? = null
    private val sectorNodes = mutableMapOf<Int, SCNNode>()
    private var arrowNode: SCNNode? = null
    private var guideTarget: SCNNode? = null

    private val sessionHealth = ArSessionHealth()
    actual val trackingState: StateFlow<TrackingState> = sessionHealth.trackingState

    private var hasItem = false
    private var itemLat = 0.0
    private var itemLng = 0.0

    // Latest GPS fix <-> AR camera position pairing (same model as ArRouteOverlay).
    private var hasGeoRef = false
    private var refLat = 0.0
    private var refLng = 0.0
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    private var lngScale = 1.0

    /** Called by the Compose UIKitView factory once the ARSCNView exists. */
    fun bind(view: ARSCNView) {
        sceneView = view
        val config = ARWorldTrackingConfiguration()
        config.worldAlignment = ARWorldAlignment.ARWorldAlignmentGravityAndHeading
        view.session.runWithConfiguration(config)
        sessionHealth.bind(view.session)
    }

    actual fun start() {
        // Session start is driven by `bind`, called from the Compose UIKitView factory.
    }

    actual fun stop() {
        sceneView?.session?.pause()
    }

    actual fun setItemLocation(lat: Double, lng: Double) {
        itemLat = lat
        itemLng = lng
        hasItem = true
    }

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
     * One marker post per uncovered sector, on a ring around the item at
     * [MARKER_RING_RADIUS_METERS]. Covered sectors have their marker removed.
     * Also retargets the guidance arrow at the nearest uncovered marker.
     */
    actual fun updateCoverage(coveredSectors: Set<Int>, uncoveredBearings: List<Double>) {
        val scene = sceneView?.scene ?: return
        if (!hasGeoRef || !hasItem) return

        val sectorWidth = 360.0 / ObjectCoverageTracker.SECTOR_COUNT
        val uncoveredSectors = uncoveredBearings
            .map { (it / sectorWidth).toInt().coerceIn(0, ObjectCoverageTracker.SECTOR_COUNT - 1) }
            .toSet()

        // Remove markers for sectors that are now covered (or no longer reported).
        for ((sector, node) in sectorNodes.toMap()) {
            if (sector !in uncoveredSectors) {
                node.removeFromParentNode()
                sectorNodes.remove(sector)
            }
        }

        val itemLocal = latLngToLocal(itemLat, itemLng)
        var nearestDistSq = Double.MAX_VALUE
        var nearestLocal: Pair<Double, Double>? = null

        for (bearing in uncoveredBearings) {
            val sector = (bearing / sectorWidth).toInt().coerceIn(0, ObjectCoverageTracker.SECTOR_COUNT - 1)
            val rad = bearing * PI / 180.0
            val x = itemLocal.first + MARKER_RING_RADIUS_METERS * sin(rad)
            val z = itemLocal.second - MARKER_RING_RADIUS_METERS * cos(rad)

            val dx = x - refX.toDouble()
            val dz = z - refZ.toDouble()
            val distSq = dx * dx + dz * dz
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearestLocal = x to z
            }

            if (sector in sectorNodes) continue
            sectorNodes[sector] = makeMarkerNode(x.toFloat(), z.toFloat()).also {
                scene.rootNode.addChildNode(it)
            }
        }

        updateGuideTarget(nearestLocal)
    }

    // ── SceneKit node construction ───────────────────────────────────

    private fun makeMarkerNode(x: Float, z: Float): SCNNode {
        val cylinder = SCNCylinder.cylinderWithRadius(0.05, MARKER_HEIGHT_METERS)
        val material = SCNMaterial()
        material.diffuse.contents = UIColor.orangeColor.colorWithAlphaComponent(0.9)
        cylinder.materials = listOf(material)

        val node = SCNNode.nodeWithGeometry(cylinder)
        node.position = SCNVector3Make(
            x,
            refY - GROUND_OFFSET_METERS + (MARKER_HEIGHT_METERS / 2).toFloat(),
            z,
        )
        return node
    }

    /** Same camera-attached look-at arrow pattern as ArRouteOverlay. */
    private fun updateGuideTarget(local: Pair<Double, Double>?) {
        val scene = sceneView?.scene ?: return
        if (local == null) {
            arrowNode?.hidden = true
            return
        }

        val target = guideTarget ?: SCNNode().also {
            guideTarget = it
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

    // ── Coordinate math (same model as ArRouteOverlay) ──────────────

    private fun latLngToLocal(lat: Double, lng: Double): Pair<Double, Double> {
        val northMeters = (lat - refLat) * METERS_PER_DEG_LAT
        val eastMeters = (lng - refLng) * METERS_PER_DEG_LAT * lngScale
        return (refX + eastMeters) to (refZ - northMeters)
    }

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
 * iOS host for [SpotArOverlay]: a Compose `UIKitView` wrapping an `ARSCNView`,
 * mirroring ArRouteOverlayView's factory/onRelease shape.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SpotArOverlayView(overlay: SpotArOverlay, modifier: Modifier) {
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
