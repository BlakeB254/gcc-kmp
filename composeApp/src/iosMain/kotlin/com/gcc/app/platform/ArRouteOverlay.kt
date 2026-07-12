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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNPlane
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNVector3Make
import platform.UIKit.UIColor
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
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
        /** Ground-anchored nodes sit this far below the camera (approx. phone-at-chest ground offset). */
        private const val GROUND_OFFSET_METERS = 1.4f

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
    private var boundaryVeil: SCNNode? = null

    // World-anchored boundary walls (see [updateBoundaries]).
    private var boundaryOuter: List<Pair<Double, Double>> = emptyList()
    private var boundaryIslands: List<List<Pair<Double, Double>>> = emptyList()
    private val boundaryWallNodes = mutableListOf<SCNNode>()
    private var hasBuiltWalls = false
    private var geoUpdatesSinceWallRebuild = 0
    private var lastWallRebuildX = 0f
    private var lastWallRebuildZ = 0f

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

    private var coverageHitListener: ((List<RaycastCoverageHit>) -> Unit)? = null
    private var coverageFanJob: Job? = null
    private val coverageFanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Background session capture (route-lap parity with ArEnvScanOverlay):
    // the same native recorder, drained on the existing 1s coverage-fan tick.
    private var captureSessionId: String? = null
    private var captureLocationCellId: String? = null

    // Geographic origin of the CoverageTracker cell lattice — the ground grid
    // (native path) phases its lines to it so AR grid == minimap grid.
    private var latticeLat: Double? = null
    private var latticeLng: Double? = null
    /** ONE-SHOT LOCK mirror (Task 2): true once [pushGridPhase] has sent a
     *  phase to the native surface. The Swift side already guards this
     *  terminally (`RealityKitArSurface.setGridPhaseOrigin`), but every GPS
     *  fix calls [updateGeoReference] → [pushGridPhase] regardless — this
     *  skips the now-pointless repeat cross-language call instead of relying
     *  solely on the far side of the bridge to no-op it. */
    private var gridPhaseSent = false

    /** Called by the Compose UIKitView factory (Task 16) once the ARSCNView exists. */
    fun bind(view: ARSCNView) {
        sceneView = view
        val config = ARWorldTrackingConfiguration()
        config.worldAlignment = ARWorldAlignment.ARWorldAlignmentGravityAndHeading
        view.session.runWithConfiguration(config)
        sessionHealth.bind(view.session)
        frameFeedback.start()
        startCoverageFanLoopIfNeeded()
    }

    /**
     * Bind to the native RealityKit surface instead of an ARSCNView. The
     * Swift side renders fog/arrow/veil/walls/markers from the primitives
     * this class ships; all route logic stays identical to SceneKit mode.
     */
    fun bindNative(surface: ArNativeSurface) {
        nativeSurface = surface
        // Routes show the same ground grid as env scans (direction 2026-07-05:
        // both scan modes surface the grid when ground is recognized); fog
        // covers UNscanned cells on top of it, vector quads mark credited ones.
        surface.setGroundGridVisible(true)
        frameFeedback.start()
        startCoverageFanLoopIfNeeded()
        DiagnosticsLog.info("ar-route", "RealityKit native route surface started")
    }

    actual fun start() {
        // Session start is driven by `bind`, called from the Compose UIKitView
        // factory in Task 16 — nothing to do here beyond what `bind` already does.
    }

    actual fun stop() {
        // Abandoned/backgrounded lap: drop the Kotlin-side capture state —
        // native teardown owns finalizing or discarding whatever the recorder
        // was mid-writing (same policy as ArEnvScanOverlay.stop()).
        captureSessionId = null
        captureLocationCellId = null
        coverageFanJob?.cancel()
        coverageFanJob = null
        frameFeedback.stop()
        // Full teardown (not just pause): cancels the native surface's
        // per-frame scene subscription and releases every created entity —
        // pause() alone left every entity anchored in the scene holding GPU
        // resources (the Complete Scan crash-on-tap root cause).
        nativeSurface?.teardown()
        nativeSurface = null
        sceneView?.session?.pause()
        // SceneKit fallback: release every node this overlay created.
        for (node in boundaryWallNodes) node.removeFromParentNode()
        boundaryWallNodes.clear()
        boundaryVeil?.removeFromParentNode()
        boundaryVeil = null
    }

    actual fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?) {
        frameFeedback.sceneVoteListener = listener
    }

    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {
        frameFeedback.ceilingListener = listener
    }

    /**
     * The fan loop intentionally gates only on [hasGeoRef], not the
     * frame-analysis flag: raycasts are Vision-free and cheap, and crediting
     * is gate-checked by the common-code listener anyway.
     */
    actual fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?) {
        coverageHitListener = listener
        if (listener == null) {
            coverageFanJob?.cancel()
            coverageFanJob = null
            return
        }
        startCoverageFanLoopIfNeeded()
    }

    /**
     * (Re)launch the 1s coverage fan loop when a listener is wired and no
     * loop is live — called from [setCoverageHitListener] and from
     * [bind]/[bindNative], so a stop() → rebind cycle restarts the fan
     * instead of leaving it silently dead.
     */
    private fun startCoverageFanLoopIfNeeded() {
        if (coverageHitListener == null || coverageFanJob != null) return
        coverageFanJob = coverageFanScope.launch {
            while (true) {
                delay(1_000L)
                // Poll-drain finished capture chunks (route-lap parity with
                // the env scan's tick drain) — a locked list swap, no-op
                // while capture isn't running.
                val capSession = captureSessionId
                val capCell = captureLocationCellId
                if (capSession != null && capCell != null) {
                    nativeSurface?.let { surface ->
                        try {
                            val packets = surface.drainFinishedCaptureChunks()
                            val infos = packets.mapNotNull(::parseCaptureChunkPacket)
                            if (infos.isNotEmpty()) bookCaptureChunksAsync(coverageFanScope, capSession, capCell, infos)
                        } catch (e: Throwable) {
                            DiagnosticsLog.error("capture", "route chunk drain failed", e)
                        }
                    }
                }
                if (!hasGeoRef) continue
                val listenerNow = coverageHitListener ?: continue
                val frame = if (nativeSurface == null) sceneView?.session?.currentFrame else null
                val fanHits = try {
                    CoverageRayFan.cast(nativeSurface, frame) { x, z -> localToLatLng(x, z) }
                } catch (e: Throwable) {
                    DiagnosticsLog.error("ar-route", "coverage fan tick failed", e)
                    emptyList()
                }
                if (fanHits.isNotEmpty()) {
                    dispatch_async(dispatch_get_main_queue()) { listenerNow(fanHits) }
                }
            }
        }
    }

    // ── Background session capture (route-lap parity) ────────────────
    // Same shape as ArEnvScanOverlay's capture block; booking goes through
    // the shared bookCaptureChunksAsync so the two paths can't drift.

    actual fun startCapture(sessionId: String): Boolean {
        val surface = nativeSurface ?: return false // SceneKit fallback: no recorder
        return try {
            if (!hasGeoRef) {
                DiagnosticsLog.info("capture", "route startCapture($sessionId) skipped — no geo reference yet")
                return false
            }
            val (latIdx, lngIdx) = SessionPointsEstimator.gridCell10m(refLat, refLng)
            val cellId = "$latIdx:$lngIdx"
            val outputDir = documentsPath() + "/capture-sessions/" + sessionId
            platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
                outputDir, withIntermediateDirectories = true, attributes = null, error = null,
            )
            captureSessionId = sessionId
            captureLocationCellId = cellId
            surface.startCapture(sessionId, outputDir)
            DiagnosticsLog.info("capture", "route lap capture started: $sessionId (cell $cellId)")
            true
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "route startCapture($sessionId) failed — capture disabled for this lap", e)
            captureSessionId = null
            captureLocationCellId = null
            false
        }
    }

    actual fun stopCapture(): List<CaptureChunkInfo> {
        val surface = nativeSurface
        val sessionId = captureSessionId
        val cellId = captureLocationCellId
        captureSessionId = null
        captureLocationCellId = null
        if (surface == null || sessionId == null || cellId == null) return emptyList()
        return try {
            val packets = surface.stopCaptureAndDrain()
            val infos = packets.mapNotNull(::parseCaptureChunkPacket)
            if (infos.isNotEmpty()) bookCaptureChunksAsync(coverageFanScope, sessionId, cellId, infos)
            infos
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "route stopCapture failed for $sessionId", e)
            emptyList()
        }
    }

    actual fun discardCurrentCaptureChunk() {
        try {
            nativeSurface?.discardCurrentCaptureChunk()
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "route discardCurrentCaptureChunk failed", e)
        }
    }

    actual fun setCaptureEnabled(enabled: Boolean) {
        try {
            nativeSurface?.setCaptureEnabled(enabled)
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "route setCaptureEnabled($enabled) failed", e)
        }
    }

    actual fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String, photoBytes: ByteArray?) -> Unit)?) {
        frameFeedback.markerPlacedListener = if (listener == null) null else { x, _, z, label, photoBytes ->
            if (hasGeoRef) {
                val (lat, lng) = localToLatLng(x, z)
                listener(lat, lng, label, photoBytes)
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

    actual fun setSceneVoteCadence(fast: Boolean) {
        frameFeedback.fastSceneVotes = fast
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
     * world-anchored node placement is a no-op until the first pairing exists.
     */
    actual fun updateGeoReference(lat: Double, lng: Double, accuracyMeters: Double?): com.gcc.shared.model.GeoRefEvent? {
        val position = cameraPosition() ?: return null
        // Accuracy-fused anchor — see ArEnvScan.updateGeoReference / GeoRefFuser.
        val fused = if (hasGeoRef && accuracyMeters != null) {
            val (curLat, curLng) = localToLatLng(position[0], position[2])
            GeoRefFuser.fuse(GeoRefFuser.Anchor(curLat, curLng), GeoRefFuser.Anchor(lat, lng), accuracyMeters)
        } else GeoRefFuser.Anchor(lat, lng)
        refLat = fused.lat
        refLng = fused.lng
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
        pushGridPhase()
        maybeRebuildWallsOnGeoUpdate()
        // The AR↔world pairing record for the DGX splat pipeline — RAW fix
        // coordinates, not the fused anchor: registration wants the actual
        // GPS observation paired with the camera position; the fusion above
        // is a display/placement smoothing concern.
        return com.gcc.shared.model.GeoRefEvent(
            atMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            lat = lat,
            lng = lng,
            accuracyMeters = accuracyMeters,
            arX = position[0],
            arY = position[1],
            arZ = position[2],
            arTimestampSeconds = nativeSurface?.currentFrameTimestamp()?.takeIf { it > 0.0 }
                ?: sceneView?.session?.currentFrame?.timestamp,
        )
    }

    actual fun setGridLatticeOrigin(lat: Double, lng: Double) {
        latticeLat = lat
        latticeLng = lng
        pushGridPhase()
    }

    /**
     * Re-derive the AR-local grid phase from the lattice origin + current geo
     * reference and hand it to the native surface, which snaps its ground-grid
     * planes onto the lattice. SceneKit fallback renders no ground grid in
     * route mode, so there is nothing to phase on that path.
     */
    private fun pushGridPhase() {
        if (gridPhaseSent) return
        val lat = latticeLat ?: return
        val lng = latticeLng ?: return
        if (!hasGeoRef) return
        val local = latLngToLocal(lat, lng)
        nativeSurface?.setGridPhaseOrigin(local.first, local.second)
        gridPhaseSent = true
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
