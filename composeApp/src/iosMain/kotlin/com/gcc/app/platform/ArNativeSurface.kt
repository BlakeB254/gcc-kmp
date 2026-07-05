package com.gcc.app.platform

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
     * Raycast from a normalized image-space point (top-left origin) onto
     * estimated planes. Returns the world hit as [x, y, z], or null.
     */
    fun raycast(imageX: Double, imageY: Double): List<Double>?

    /** True once at least one floor plane is being rendered (grid live). */
    fun groundDetected(): Boolean

    /**
     * Vector-scan overlay: credited cells as a flat [x0, z0, x1, z1, …]
     * AR-local list. The surface places quads on its own detected floor
     * height and owns the pulse animation + node cap.
     */
    fun updateVectorCells(flatXZ: List<Double>, cellSizeMeters: Double)

    /** Persistent confirmed-trash marker at an AR world position. */
    fun addMarker(x: Double, y: Double, z: Double)

    /** One-shot lock-on effect (particles on iOS 18+, pulse otherwise). */
    fun emitLockEffect(x: Double, y: Double, z: Double)
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
