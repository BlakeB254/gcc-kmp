package com.gcc.shared.model

import kotlinx.serialization.Serializable

/**
 * One environment-scan session = one capture, from screen-open to the
 * Complete Scan press. The manifest plus its photos upload to R2
 * (raw/{locationCellId}/{deviceId}/…) where the DGX reconstruction pipeline
 * discovers them — capture, GPS track, facing direction, timing, and the
 * scanned map area travel together as a single unit of work.
 */
@Serializable
data class EnvScanPathSample(
    val lat: Double,
    val lng: Double,
    /** Compass facing at this fix, 0=north; null when unavailable. */
    val headingDegrees: Float? = null,
    /** GPS horizontal accuracy in meters; null when unknown. */
    val accuracyMeters: Double? = null,
    val atMs: Long,
)

@Serializable
data class EnvScanCell(val lat: Double, val lng: Double)

/**
 * One periodically-captured color+depth frame pair from an active
 * environment scan, for the DGX reconstruction pipeline (Task 7 — before
 * this, a session uploaded only detection metadata and a couple of manual
 * photos, nothing usable as raw multi-view input). Depth is optional: it's
 * only present on PRO_SCANNER (LiDAR) devices, and even there capture can
 * fail/skip a given tick — a color-only frame is still valid raw input for
 * classical structure-from-motion, just not depth-fused reconstruction.
 */
@Serializable
data class CapturedFrame(
    /** R2 key of the JPEG color frame. */
    val colorKey: String,
    /** R2 key of the 16-bit grayscale PNG depth frame, when captured.
     *  Values are millimeters (see [DEPTH_PNG_SCALE_MM_PER_UNIT]), matching
     *  the common RGBD-dataset convention so any generic tool can read it
     *  without GCC-specific documentation. */
    val depthKey: String? = null,
    val lat: Double,
    val lng: Double,
    val headingDegrees: Float? = null,
    /** Camera-to-world transform, row-major 4x4 (16 floats), when ARKit
     *  tracking was healthy at capture time -- the registration step of the
     *  DGX fusion pipeline needs this per frame, not just per session. */
    val cameraTransform: List<Float>? = null,
    /** fx, fy, cx, cy in pixels. */
    val intrinsics: List<Float>? = null,
    val capturedAtMs: Long,
) {
    companion object {
        const val DEPTH_PNG_SCALE_MM_PER_UNIT = 1000.0
    }
}

@Serializable
data class EnvScanManifest(
    val sessionId: String,
    val deviceId: String,
    val deviceModel: String,
    val osVersion: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    /** GPS + facing-direction track sampled during the scan (~1Hz). */
    val path: List<EnvScanPathSample>,
    /** Centers of the coverage cells this session credited. */
    val scannedCells: List<EnvScanCell>,
    val scannedCellSizeMeters: Double,
    /** Geo-tagged trash items observed during the session. */
    val observedItems: List<ObservedSpotItem>,
    /** R2 keys of the photos uploaded alongside this manifest. */
    val photoKeys: List<String>,
    /** The community report this session produced, when submission succeeded. */
    val communityReportId: Int? = null,
    /** Guided 3D capture (measured USDZ model), when performed — rides the
     *  durable manifest so an offline submission can't lose the 3D scan. */
    val objectCapture: ObjectCaptureResult? = null,
    /**
     * Which lap of a block-route fulfillment this session records:
     * "baseline" (first lap, documents pre-clean state) or "clean" (post-
     * cleanup lap). Null for free/explorer environment scans, which are not
     * lap-structured. Route-v2 design doc, step 1.
     */
    val lapKind: String? = null,
    /** The route this session fulfilled, when it was a block-route lap. */
    val routeId: Int? = null,
    /** Audit trail of gate flips, manual overrides, and privacy purges during
     *  this session — additive/optional so older manifests deserialize
     *  unchanged. Null for sessions recorded before this field existed. */
    val gateEvents: List<String>? = null,
    val schemaVersion: Int = 1,
    /** R2 keys of background-capture chunks (HEVC video + pose/depth
     *  sidecars) uploaded so far for this session, per the session-capture
     *  plan — additive/optional so older manifests deserialize unchanged.
     *  Late-uploaded chunks are still keyed by sessionId in R2 regardless
     *  of whether they made it into this snapshot. */
    val captureChunks: List<String>? = null,
    /** Sidecar/chunk format version for `captureChunks` (currently 1). Null
     *  for manifests recorded before background capture existed. */
    val captureSchema: Int? = null,
    /** Periodic raw color+depth frames captured during the session for the
     *  DGX reconstruction pipeline (Task 7). Empty for sessions recorded
     *  before this field existed, or when every frame upload attempt failed
     *  -- frames are best-effort like [photoKeys], never queued/retried, so
     *  this list only ever contains frames actually confirmed in R2. */
    val capturedFrames: List<CapturedFrame> = emptyList(),
    /** Candidates the user tapped and explicitly marked "Not litter". Negative
     *  training examples for the litter detector — deliberately NOT sent to
     *  world_observations, where they would paint the map red. */
    val rejectedItems: List<ObservedSpotItem>? = null,
    /** Tail of the on-device diagnostics/error log for this session. Ships with
     *  the R2 bundle so scan problems are debuggable from the backend without
     *  asking the tester for a screen recording. Capped — see DIAGNOSTICS_TAIL_MAX. */
    val diagnosticsLog: List<String>? = null,
)

@Serializable
data class CaptureUploadTicket(
    val uploadUrl: String,
    val r2Key: String,
    val expiresInSeconds: Int = 300,
)

@Serializable
data class CaptureUploadUrlRequest(
    val locationCellId: String,
    val deviceId: String,
    val capturedAt: String,
    val ext: String,
    /** Session-artifact path (Pillar 1 background capture chunks): when both
     *  this and [artifactPath] are set, the server keys the object under
     *  raw/{locationCellId}/{sessionId}/{artifactPath} instead of the legacy
     *  raw/{locationCellId}/{deviceId}/{capturedAt}.{ext} layout. Last params,
     *  defaulted, so existing callers are unaffected. */
    val sessionId: String? = null,
    val artifactPath: String? = null,
)
