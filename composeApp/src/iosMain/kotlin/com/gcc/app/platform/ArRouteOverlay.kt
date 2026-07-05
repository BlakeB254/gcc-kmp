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
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNVector3Make
import platform.UIKit.UIColor
import platform.darwin.simd_float4x4
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

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

        /** Only build wall strips for boundary edges within this radius of the camera. */
        private const val WALL_RENDER_RADIUS_METERS = 45.0
        /** Boundary edges are sampled into strips no longer than this. */
        private const val WALL_SEGMENT_MAX_METERS = 5.0
        /** Vertical extent of each wall strip. */
        private const val WALL_HEIGHT_METERS = 0.9f
        /** Wall strips sit half their height above the ground so the bottom edge meets it. */
        private const val WALL_HALF_HEIGHT_METERS = 0.45f
        /** Hard ceiling on live wall nodes, protects the SceneKit frame rate. */
        private const val MAX_WALL_SEGMENTS = 200
        /** Rebuild walls after at most this many geo-reference updates… */
        private const val WALL_REBUILD_GEO_INTERVAL = 3
        /** …or as soon as the camera reference has moved more than this. */
        private const val WALL_REBUILD_MOVE_METERS = 5.0
    }

    private var sceneView: ARSCNView? = null
    /** Native RealityKit render surface — when set, SceneKit paths are inert. */
    private var nativeSurface: ArNativeSurface? = null
    private val fogNodes = mutableMapOf<Pair<Int, Int>, SCNNode>()
    private var boundaryVeil: SCNNode? = null

    // World-anchored boundary walls (see [updateBoundaries]).
    private var boundaryOuter: List<Pair<Double, Double>> = emptyList()
    private var boundaryIslands: List<List<Pair<Double, Double>>> = emptyList()
    private val boundaryWallNodes = mutableListOf<SCNNode>()
    private var hasBuiltWalls = false
    private var geoUpdatesSinceWallRebuild = 0
    private var lastWallRebuildX = 0f
    private var lastWallRebuildZ = 0f

    private var arrowNode: SCNNode? = null
    private var frontierTarget: SCNNode? = null

    private val sessionHealth = ArSessionHealth()
    actual val trackingState: StateFlow<TrackingState> = sessionHealth.trackingState

    private val frameFeedback = ArFrameFeedback({ sceneView }, { nativeSurface })
    actual val liveDetections: StateFlow<List<LiveDetection>> = frameFeedback.liveDetections

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
        frameFeedback.start()
    }

    /**
     * Bind to the native RealityKit surface instead of an ARSCNView. The
     * Swift side renders fog/arrow/veil/walls/markers from the primitives
     * this class ships; all route logic stays identical to SceneKit mode.
     */
    fun bindNative(surface: ArNativeSurface) {
        nativeSurface = surface
        surface.setGroundGridVisible(false) // fog owns the ground on routes
        frameFeedback.start()
        DiagnosticsLog.info("ar-route", "RealityKit native route surface started")
    }

    actual fun start() {
        // Session start is driven by `bind`, called from the Compose UIKitView
        // factory in Task 16 — nothing to do here beyond what `bind` already does.
    }

    actual fun stop() {
        frameFeedback.stop()
        nativeSurface?.pause()
        sceneView?.session?.pause()
    }

    actual fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?) {
        frameFeedback.sceneVoteListener = listener
    }

    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {
        frameFeedback.ceilingListener = listener
    }

    actual fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String) -> Unit)?) {
        frameFeedback.markerPlacedListener = if (listener == null) null else { x, _, z, label ->
            if (hasGeoRef) {
                val (lat, lng) = localToLatLng(x, z)
                listener(lat, lng, label)
            }
            // else: no geo reference yet (GPS hasn't paired with the AR camera).
            // Silently drop this promotion event rather than persist garbage
            // coordinates — the marker still renders locally in the AR scene
            // (that path is unaffected); only server persistence is skipped.
        }
    }

    actual fun setFrameAnalysisEnabled(enabled: Boolean) {
        frameFeedback.setEnabled(enabled)
    }

    actual fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind) {
        if (!hasGeoRef) return
        val local = latLngToLocal(lat, lng)
        frameFeedback.addMarkerAt(
            local.first.toFloat(),
            refY - GROUND_OFFSET_METERS + 0.05f,
            local.second.toFloat(),
            kind,
        )
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
        lngScale = lngScaleFor(lat)
        hasGeoRef = true
        // Native mode has no ARSessionDelegate — mirror the surface's
        // tracking quality into the shared flow on the same cadence.
        nativeSurface?.let { surface ->
            sessionHealth.overrideState(
                when (surface.trackingStateRaw()) {
                    0 -> TrackingState.NORMAL
                    1 -> TrackingState.LIMITED
                    else -> TrackingState.NOT_AVAILABLE
                }
            )
        }
        maybeRebuildWallsOnGeoUpdate()
    }

    /**
     * One translucent SCNPlane per unscanned cell near the worker; cells no
     * longer in [unscannedCells] (scanned = revealed) have their node removed.
     * Also retargets the guidance arrow at the nearest unscanned cell.
     */
    actual fun updateFog(unscannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {
        if (!hasGeoRef) return
        nativeSurface?.let { surface ->
            // Native mode: ship nearby cells (same radius/cap policy) as
            // AR-local coords; the surface owns reveal-by-omission.
            val flat = ArrayList<Double>()
            var nearestDistSq = Double.MAX_VALUE
            var nearest: Pair<Double, Double>? = null
            var shipped = 0
            for (cell in unscannedCells) {
                val (lat, lng) = cellCenter(cell.first, cell.second)
                val local = latLngToLocal(lat, lng)
                val dx = local.first - refX.toDouble()
                val dz = local.second - refZ.toDouble()
                val distSq = dx * dx + dz * dz
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq
                    nearest = local
                }
                if (distSq > FOG_RENDER_RADIUS_METERS * FOG_RENDER_RADIUS_METERS) continue
                if (shipped >= MAX_FOG_NODES) continue
                flat.add(local.first)
                flat.add(local.second)
                shipped++
            }
            surface.updateFogCells(flat, CoverageTracker.cellSizeMeters)
            val target = nearest
            if (target != null) surface.setGuidanceTarget(target.first, target.second)
            else surface.clearGuidanceTarget()
            return
        }
        val scene = sceneView?.scene ?: return

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
        nativeSurface?.let { surface ->
            surface.setBoundaryVeilVisible(isOutside)
            return
        }
        val veil = boundaryVeil ?: makeBoundaryVeil() ?: return
        veil.hidden = !isOutside
    }

    /**
     * Store the route/block boundary polygons and (re)build the world-anchored
     * wall strips. Placement needs the geo reference, so if no GPS fix has been
     * paired yet the walls are deferred — the first [updateGeoReference] builds
     * them (see [maybeRebuildWallsOnGeoUpdate]).
     */
    actual fun updateBoundaries(outer: List<Pair<Double, Double>>, islands: List<List<Pair<Double, Double>>>) {
        boundaryOuter = outer
        boundaryIslands = islands
        if (hasGeoRef) rebuildBoundaryWalls()
    }

    /**
     * Rebuild walls as the camera reference shifts: the render window is
     * camera-relative (only edges within [WALL_RENDER_RADIUS_METERS] get nodes),
     * so a moving worker needs the nearby slice recomputed. Throttled to avoid
     * rebuilding on every GPS tick — at most every [WALL_REBUILD_GEO_INTERVAL]
     * updates, or immediately once the camera has moved [WALL_REBUILD_MOVE_METERS].
     */
    private fun maybeRebuildWallsOnGeoUpdate() {
        if (!hasBoundaryData()) return
        geoUpdatesSinceWallRebuild++
        val dx = refX - lastWallRebuildX
        val dz = refZ - lastWallRebuildZ
        val movedFar = dx * dx + dz * dz > (WALL_REBUILD_MOVE_METERS * WALL_REBUILD_MOVE_METERS).toFloat()
        if (!hasBuiltWalls || geoUpdatesSinceWallRebuild >= WALL_REBUILD_GEO_INTERVAL || movedFar) {
            rebuildBoundaryWalls()
        }
    }

    private fun hasBoundaryData(): Boolean = boundaryOuter.size >= 3 || boundaryIslands.isNotEmpty()

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
     * Rebuild the world-anchored boundary walls against the current geo
     * reference: green strips for the outer ring, amber for inner islands.
     * Old nodes are dropped first. Only edges near the camera get nodes, and
     * the total is capped at [MAX_WALL_SEGMENTS] to protect the frame rate.
     */
    private fun rebuildBoundaryWalls() {
        if (!hasGeoRef) return
        nativeSurface?.let { surface ->
            geoUpdatesSinceWallRebuild = 0
            lastWallRebuildX = refX
            lastWallRebuildZ = refZ
            hasBuiltWalls = true
            if (!hasBoundaryData()) {
                surface.updateBoundaryWalls(emptyList())
                return
            }
            val radiusSq = WALL_RENDER_RADIUS_METERS * WALL_RENDER_RADIUS_METERS
            val flat = ArrayList<Double>()
            if (boundaryOuter.size >= 3) collectRingSegments(boundaryOuter, isOuter = true, radiusSq, flat)
            for (island in boundaryIslands) {
                if (island.size < 3) continue
                collectRingSegments(island, isOuter = false, radiusSq, flat)
            }
            surface.updateBoundaryWalls(flat)
            return
        }
        val scene = sceneView?.scene ?: return

        for (node in boundaryWallNodes) node.removeFromParentNode()
        boundaryWallNodes.clear()

        // Record the reference this build was placed against, for throttling.
        geoUpdatesSinceWallRebuild = 0
        lastWallRebuildX = refX
        lastWallRebuildZ = refZ
        hasBuiltWalls = true

        if (!hasBoundaryData()) return

        val outerColor = UIColor.colorWithRed(0.13, 0.77, 0.37, 1.0)
        val amberColor = UIColor.colorWithRed(1.0, 0.72, 0.0, 1.0)
        val radiusSq = WALL_RENDER_RADIUS_METERS * WALL_RENDER_RADIUS_METERS

        var capped = false
        if (boundaryOuter.size >= 3) {
            capped = addRingWalls(scene, boundaryOuter, outerColor, radiusSq)
        }
        for (island in boundaryIslands) {
            if (island.size < 3) continue
            if (addRingWalls(scene, island, amberColor, radiusSq)) capped = true
        }
        if (capped) {
            DiagnosticsLog.info(
                "ar-boundary",
                "boundary walls capped at $MAX_WALL_SEGMENTS segments near camera",
            )
        }
    }

    /**
     * Sample one closed ring's edges into ≤[WALL_SEGMENT_MAX_METERS] strips and
     * add a wall node for each whose midpoint is within [radiusSq] of the camera
     * reference. Returns true if the [MAX_WALL_SEGMENTS] cap was hit.
     */
    private fun addRingWalls(
        scene: SCNScene,
        ring: List<Pair<Double, Double>>,
        color: UIColor,
        radiusSq: Double,
    ): Boolean {
        val locals = ring.map { latLngToLocal(it.first, it.second) }
        val n = locals.size
        for (i in 0 until n) {
            val a = locals[i]
            val b = locals[(i + 1) % n]
            val ax = a.first; val az = a.second
            val dx = b.first - ax; val dz = b.second - az
            val edgeLen = hypot(dx, dz)
            if (edgeLen < 0.5) continue // skip degenerate/closing duplicate edges
            val segCount = ceil(edgeLen / WALL_SEGMENT_MAX_METERS).toInt()
            val segLen = edgeLen / segCount
            // SCNPlane is a vertical XY quad facing +Z; a yaw about Y turns its
            // width axis (+X) to run along the edge direction in the ground plane.
            val yaw = atan2(-dz, dx).toFloat()
            for (s in 0 until segCount) {
                if (boundaryWallNodes.size >= MAX_WALL_SEGMENTS) return true
                val t = (s + 0.5) / segCount
                val mx = ax + dx * t
                val mz = az + dz * t
                val ddx = mx - refX
                val ddz = mz - refZ
                if (ddx * ddx + ddz * ddz > radiusSq) continue
                val node = makeWallSegment(mx.toFloat(), mz.toFloat(), segLen, yaw, color)
                scene.rootNode.addChildNode(node)
                boundaryWallNodes.add(node)
            }
        }
        return false
    }

    /**
     * Native-mode variant of [addRingWalls]: same sampling/window/cap policy,
     * but emits flat [midX, midZ, length, yaw, isOuter] records for the
     * RealityKit surface instead of SceneKit nodes.
     */
    private fun collectRingSegments(
        ring: List<Pair<Double, Double>>,
        isOuter: Boolean,
        radiusSq: Double,
        out: MutableList<Double>,
    ) {
        val locals = ring.map { latLngToLocal(it.first, it.second) }
        val n = locals.size
        for (i in 0 until n) {
            val a = locals[i]
            val b = locals[(i + 1) % n]
            val ax = a.first; val az = a.second
            val dx = b.first - ax; val dz = b.second - az
            val edgeLen = hypot(dx, dz)
            if (edgeLen < 0.5) continue
            val segCount = ceil(edgeLen / WALL_SEGMENT_MAX_METERS).toInt()
            val segLen = edgeLen / segCount
            val yaw = atan2(-dz, dx)
            for (s in 0 until segCount) {
                if (out.size / 5 >= MAX_WALL_SEGMENTS) return
                val t = (s + 0.5) / segCount
                val mx = ax + dx * t
                val mz = az + dz * t
                val ddx = mx - refX
                val ddz = mz - refZ
                if (ddx * ddx + ddz * ddz > radiusSq) continue
                out.add(mx)
                out.add(mz)
                out.add(segLen)
                out.add(yaw)
                out.add(if (isOuter) 1.0 else 0.0)
            }
        }
    }

    private fun makeWallSegment(x: Float, z: Float, length: Double, yaw: Float, color: UIColor): SCNNode {
        val plane = SCNPlane.planeWithWidth(length, WALL_HEIGHT_METERS.toDouble())
        val material = SCNMaterial()
        material.diffuse.contents = color
        material.emission.contents = color // glow, so the strip reads in low light
        material.transparency = 0.3
        material.doubleSided = true
        material.writesToDepthBuffer = false
        plane.materials = listOf(material)

        val node = SCNNode.nodeWithGeometry(plane)
        node.eulerAngles = SCNVector3Make(0f, yaw, 0f)
        node.position = SCNVector3Make(x, refY - GROUND_OFFSET_METERS + WALL_HALF_HEIGHT_METERS, z)
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
    private fun latLngToLocal(lat: Double, lng: Double): Pair<Double, Double> =
        latLngToLocal(lat, lng, refLat, refLng, refX, refZ, lngScale)

    /** AR-local (x, z) meters -> (lat, lng), via the current geo reference. */
    private fun localToLatLng(x: Float, z: Float): Pair<Double, Double> =
        localToLatLng(x, z, refLat, refLng, refX, refZ, lngScale)

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
            // Native RealityKit surface when registered; any failure falls
            // back to the proven SceneKit path (same kill switch as the
            // environment scan).
            val nativeView = try {
                ArNativeRegistry.surfaceFactory?.invoke()?.let { surface ->
                    overlay.bindNative(surface)
                    surface.view()
                }
            } catch (e: Throwable) {
                DiagnosticsLog.error("ar-route", "native AR surface failed — SceneKit fallback", e)
                null
            }
            nativeView ?: ARSCNView().also { overlay.bind(it) }
        },
        modifier = modifier,
        onRelease = {
            overlay.stop()
        },
    )
}
