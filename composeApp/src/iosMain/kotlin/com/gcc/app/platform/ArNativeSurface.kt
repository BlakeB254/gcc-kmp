package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCPointer
import platform.UIKit.UIView

/**
 * Contract for a native-Swift AR render surface (RealityKit) hosted behind
 * the Kotlin AR overlays.
 *
 * Why this exists: RealityKit has no ObjC headers, so Kotlin can never call
 * it — and iosApp's Swift can't be imported by the ComposeApp framework
 * either (the dependency arrow points the other way). The bridge is the
 * codebase's registration pattern (PushNotificationManager /
 * GoogleSignInBridge precedent): Kotlin defines this interface, Swift
 * implements it with a RealityKit `ARView` and registers a factory in
 * [ArNativeRegistry] at app launch. Kotlin keeps owning ALL logic — Vision
 * pipeline, outdoor gate, candidate state machine, geo-referencing, capture
 * — and treats the surface as a dumb, fast renderer + sensor tap.
 *
 * Coordinate conventions: AR world space is compass-true (the Swift side
 * must configure `gravityAndHeading`, mirroring the Kotlin sessions).
 * [raycast] takes a NORMALIZED IMAGE-SPACE point, top-left origin —
 * `ARFrame.raycastQuery(from:)` semantics, which is exactly what the Kotlin
 * Vision code already computes from classification bounding boxes.
 */
interface ArNativeSurface {
    /** The live ARView, hosted by the Compose UIKitView. */
    fun view(): UIView

    /** Pause the underlying ARSession (composition leaving the screen). */
    fun pause()

    /**
     * Full teardown: cancel the per-frame scene subscription and remove
     * EVERY created entity (planes, vector/fog cells, sector posts, walls,
     * arrow, veil) from the scene graph, then pause the session. `pause()`
     * alone left the per-frame update subscription live and every entity —
     * potentially 750+ on a large scan — anchored in the scene holding GPU
     * resources; that's the scan-end (Complete Scan tap) crash class this
     * closes. Overlays call this instead of [pause] from `stop()`.
     */
    fun teardown()

    /**
     * Latest camera frame's CVPixelBuffer as a raw address (0 = none yet).
     * A Long because cinterop pointer types are silently dropped from the
     * ObjC framework header, taking the whole protocol with them — the Swift
     * side keeps the backing ARFrame strongly held until the next frame, so
     * the pointer stays valid for the duration of one Vision tick.
     */
    fun currentPixelBufferAddress(): Long

    /** Camera AR-world position as [x, y, z], or null before the first frame. */
    fun cameraPosition(): List<Double>?

    /**
     * ARKit timestamp of the latest frame (boot-relative seconds — the SAME
     * clock the capture recorder's pose sidecar stamps every `t` with), or
     * -1.0 before the first frame. Paired with a wall-clock read at geo-ref
     * time, this is what lets the DGX pipeline align the pose stream with
     * the GPS track (see GeoRefEvent).
     */
    fun currentFrameTimestamp(): Double

    /**
     * Raycast from a normalized image-space point (top-left origin) onto
     * estimated planes. Returns the world hit as [x, y, z], or null.
     */
    fun raycast(imageX: Double, imageY: Double): List<Double>?

    /** True once at least one floor plane is being rendered (grid live). */
    fun groundDetected(): Boolean

    /** True when the session currently tracks a ceiling-classified plane
     *  (ARPlaneClassification.ceiling). Outdoors this is essentially never
     *  true — the OutdoorGate's strongest camera-side indoor cue. */
    fun ceilingDetected(): Boolean

    /** Camera tracking quality: 0 normal, 1 limited, 2 unavailable. */
    fun trackingStateRaw(): Int

    /** Swift-side Vision scene classification of the current frame — top-15
     *  "identifier|confidence" entries, empty when no frame. Native path only;
     *  keeps ALL Vision work in Swift (Kotlin applies the shared scoring). */
    fun classifySceneLabels(): List<String>

    /** Ground grid on detected planes. Both scan modes show it (env scan AND
     *  block route — direction 2026-07-05); spot mode hides it. */
    fun setGroundGridVisible(visible: Boolean)

    /**
     * Phase origin for the ground-grid lattice, in AR-local meters: the
     * geo-anchored CoverageTracker lattice origin converted through the
     * current geo reference. Grid planes snap their bounds — and therefore
     * every repeated tile line — to multiples of the tile size from this
     * point, so the AR geogrid registers with the minimap's cell lattice.
     * Re-sent on every geo-reference re-pair; the surface re-phases live
     * planes when it moves meaningfully.
     */
    fun setGridPhaseOrigin(x: Double, z: Double)

    /**
     * Fog-of-war: unscanned cells as a flat [x0, z0, x1, z1, …] AR-local
     * list (Kotlin applies the 35m/600 policy). Removed keys reveal.
     */
    fun updateFogCells(flatXZ: List<Double>, cellSizeMeters: Double)

    /**
     * Plant the frontier beacon (ONE pooled world-anchored ground ring —
     * spec 2026-07-10; never an entity per target) at an AR-local ground
     * point. Repeat calls MOVE the same entity.
     */
    fun setGuidanceTarget(x: Double, z: Double)

    fun clearGuidanceTarget()

    /** Fade the beacon out (its patch reached CLOSE credit) without
     *  destroying the pooled entity; the next [setGuidanceTarget] re-shows it. */
    fun dissolveGuidanceBeacon()

    /** Temporarily hide (true) / restore (false) the beacon while a confirm
     *  flow owns the screen. Restore only re-shows if a target is live. */
    fun setBeaconSuppressed(suppressed: Boolean)

    /** Camera-attached red warning veil (worker outside the boundary). */
    fun setBoundaryVeilVisible(visible: Boolean)

    /**
     * Spot mode: uncovered capture-angle posts as flat [x0, z0, …] AR-local
     * positions; sectors absent from the list disappear (reveal-by-omission).
     */
    fun updateSectorPosts(flatXZ: List<Double>)

    /**
     * World-anchored boundary wall strips as flat records of
     * [midX, midZ, lengthMeters, yawRadians, isOuter(1/0)] — Kotlin owns the
     * segment sampling policy; each call replaces the previous walls.
     */
    fun updateBoundaryWalls(flatSegments: List<Double>)

    /**
     * Vector-scan overlay: credited cells as a flat [x0, z0, x1, z1, …]
     * AR-local list. The surface places quads on its own detected floor
     * height and owns the pulse animation + node cap.
     */
    fun updateVectorCells(flatXZ: List<Double>, cellSizeMeters: Double)

    /** Persistent trash marker: kind 0 = confirmed (green), 1 = expected (orange). */
    fun addMarker(x: Double, y: Double, z: Double, kind: Int)

    /** One-shot lock-on effect (particles on iOS 18+, pulse otherwise). */
    fun emitLockEffect(x: Double, y: Double, z: Double)

    // ── Session capture (Pillar 1) ───────────────────────────────────
    // Poll-drain design, deliberately callback-free: the Swift recorder
    // spools HEVC chunks + pose sidecar + depth keyframes to disk and
    // Kotlin polls finished chunks on its existing 1s tick — no
    // cross-language callback plumbing. Chunk packets are K/N-friendly
    // "index|kind|path|sizeBytes" strings (kind: video|sidecar|depth),
    // the classifySceneLabels packet-string precedent. The recorder can
    // NEVER degrade the live scan: every Swift-side failure is logged
    // (GCC-DIAG) and disables capture for the session; nothing throws
    // across this boundary.

    /**
     * Begin background session capture into [outputDirPath] (created if
     * needed — a Documents subdirectory owned by Kotlin). Chunks rotate at
     * 60s / 64MB; thermal `.serious` degrades to sidecar-only appends and
     * `.critical` auto-pauses until the device cools.
     */
    fun startCapture(sessionId: String, outputDirPath: String)

    /**
     * Take (and clear) the finished-chunk packets — files fully finalized
     * on disk, safe to hand to the bookkeeping store / uploader.
     */
    fun drainFinishedCaptureChunks(): List<String>

    /**
     * Privacy path (INDOOR gate flip): delete the in-progress chunk's files
     * entirely (≤60s over-purge, deliberate) — capture resumes into a fresh
     * chunk index when appends restart.
     */
    fun discardCurrentCaptureChunk()

    /**
     * Stop capture, finalize the in-progress chunk, and return ALL
     * not-yet-drained packets. Blocks briefly (bounded) for the video
     * finalize — call from the scan-stop path BEFORE [teardown], never
     * from a per-frame context.
     */
    fun stopCaptureAndDrain(): List<String>

    /** Pause/resume capture without teardown (outdoor-gate driven). */
    fun setCaptureEnabled(enabled: Boolean)
}

/**
 * Set from Swift (`AppDelegate.didFinishLaunching`) before any scan screen
 * opens; null keeps the pure-Kotlin SceneKit path. [ArEnvScanOverlay] treats
 * a factory that throws as "not available" and falls back — the native
 * render layer must never be able to brick the scan.
 */
object ArNativeRegistry {
    var surfaceFactory: (() -> ArNativeSurface)? = null
}

/** Shared address→CVPixelBuffer bridge (see [ArNativeSurface.currentPixelBufferAddress]). */
@OptIn(ExperimentalForeignApi::class)
internal fun ArNativeSurface.pixelBufferOrNull(): platform.CoreVideo.CVPixelBufferRef? {
    val address = currentPixelBufferAddress()
    if (address == 0L) return null
    return address.toCPointer()
}

/** Parses Swift's `"identifier|confidence"` scene-label packets (see
 *  [ArNativeSurface.classifySceneLabels]) into the shared [ClassificationObservation]
 *  type the [TrashDetectionRules] scoring runs on. */
internal fun parseSceneLabels(entries: List<String>): List<ClassificationObservation> =
    entries.mapNotNull { entry ->
        val i = entry.lastIndexOf('|')
        if (i <= 0) return@mapNotNull null
        val confidence = entry.substring(i + 1).toFloatOrNull() ?: return@mapNotNull null
        ClassificationObservation(entry.substring(0, i), confidence)
    }

/** Parses Swift's `"index|kind|path|sizeBytes"` capture-chunk packets (see
 *  [ArNativeSurface.drainFinishedCaptureChunks]/[ArNativeSurface.stopCaptureAndDrain])
 *  into [CaptureChunkInfo]. Malformed entries are dropped rather than
 *  thrown — capture bookkeeping must never crash the scan. */
internal fun parseCaptureChunkPacket(packet: String): CaptureChunkInfo? {
    val parts = packet.split('|')
    if (parts.size != 4) return null
    val index = parts[0].toLongOrNull() ?: return null
    val sizeBytes = parts[3].toLongOrNull() ?: return null
    return CaptureChunkInfo(index = index, kind = parts[1], filePath = parts[2], sizeBytes = sizeBytes)
}
