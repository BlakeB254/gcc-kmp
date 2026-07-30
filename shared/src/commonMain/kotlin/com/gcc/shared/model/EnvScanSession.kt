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
 * Durable identity for one session photo. Legacy manifests only carried a
 * bare [EnvScanManifest.photoKeys] list, which preserved the JPEG but lost
 * when and where it was captured. Current clients keep the compatibility
 * list and add these timestamped/geotagged references so reconstruction,
 * review, and map recovery can associate each individual image without
 * guessing from list order.
 */
@Serializable
data class EnvScanPhotoRef(
    val r2Key: String,
    val atMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
)

/**
 * One AR↔world registration pairing: the AR camera's local position at the
 * instant a usable GPS fix arrived, with BOTH clocks. This is the record
 * that lets the DGX splat pipeline place an AR-local reconstruction into the
 * world splat layer:
 *
 *  - the capture-chunk pose sidecar is entirely AR-LOCAL (`transform[16]`
 *    stamped with ARKit's boot-relative `t`);
 *  - the manifest [EnvScanPathSample]s are entirely WORLD (lat/lng at epoch
 *    ms);
 *  - each GeoRefEvent bridges the two: `(arX, arY, arZ) @ arTimestampSeconds`
 *    is the same instant as `(lat, lng) @ atMs`, so poses↔GPS can be aligned
 *    in time AND the AR frame can be anchored in the world frame. Multiple
 *    events over a session over-determine the alignment (least-squares over
 *    pairings beats trusting any single fix). [headingDegrees] and
 *    [headingAccuracyDegrees] carry the device's own compass reading at the
 *    same instant — an independent check on the heading implied by the AR
 *    pose.
 *
 * Axes: see [EnvScanManifest.AR_FRAME_CONVENTION].
 */
@Serializable
data class GeoRefEvent(
    /** Wall-clock epoch millis of the pairing (same clock as [EnvScanPathSample.atMs]). */
    val atMs: Long,
    val lat: Double,
    val lng: Double,
    /** GPS horizontal accuracy at the fix, meters; null when unknown. */
    val accuracyMeters: Double? = null,
    /** AR-local camera position at the pairing instant, meters. */
    val arX: Float,
    val arY: Float,
    val arZ: Float,
    /** ARKit frame timestamp (boot-relative seconds — the SAME clock as the
     *  pose sidecar's per-frame `t`); null when no frame was available. */
    val arTimestampSeconds: Double? = null,
    /** Compass heading at the fix, degrees (0=north); null when unavailable.
     *  Explicit alongside (arX, arY, arZ) so the DGX splat pipeline can
     *  cross-check the AR-local heading implied by the pose against the
     *  device's own compass reading at the same pairing instant. */
    val headingDegrees: Double? = null,
    /** Compass heading accuracy in degrees (larger = less reliable); null
     *  when unknown OR when the device reported the heading as invalid
     *  (e.g. magnetic interference — never negative here). Lets the
     *  pipeline down-weight noisy compass readings the same way
     *  [accuracyMeters] down-weights noisy GPS fixes. */
    val headingAccuracyDegrees: Double? = null,
)

/**
 * Session-level capture-quality summary from the native session recorder
 * (splat-capture reliability spec, Task 5) — lets the backend flag
 * splat-degraded sessions at index time without parsing the pose sidecar.
 * Every field is independently optional: the native recorder may never have
 * run (off-iOS, SceneKit fallback, capture never started), or the health
 * JSON it emitted may have failed to decode — a partial summary is still
 * worth shipping rather than dropping the whole block.
 */
@Serializable
data class CaptureHealth(
    /** Wall-clock session length in seconds ((completedAtMs - startedAtMs) /
     *  1000) — filled in by Kotlin at manifest-build time; the native health
     *  JSON does not carry this. */
    val sessionSeconds: Double? = null,
    /** Total seconds of video actually encoded across every finalized chunk
     *  this session (sum of each chunk's last-minus-first append time). */
    val videoCoveredSeconds: Double? = null,
    /** Highest `ProcessInfo.ThermalState` reached this session: "nominal",
     *  "fair", "serious", or "critical". */
    val maxThermalState: String? = null,
    /** Chunks finalized while the recorder was at the thermal-reduced video
     *  tier (see `ArSessionRecorder.VideoTier.reduced`). */
    val degradedChunkCount: Int? = null,
    /** Chunks finalized with zero video samples — the writer never got a
     *  usable frame (distinct from a chunk that legitimately carries no
     *  video, e.g. depth-only). */
    val droppedVideoChunkCount: Int? = null,
    /** AVAssetWriter/file errors logged this session (stale-writer
     *  recreates, write failures — see `ArSessionRecorder.noteWriterError`). */
    val writerErrorCount: Int? = null,
)

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
    /** Timestamped/geotagged form of [photoKeys]. Additive and null for
     * legacy sessions whose individual photo capture stamps are unknown. */
    val photos: List<EnvScanPhotoRef>? = null,
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
    /** Candidates the user tapped and explicitly marked "Not litter". Negative
     *  training examples for the litter detector — deliberately NOT sent to
     *  world_observations, where they would paint the map red. */
    val rejectedItems: List<ObservedSpotItem>? = null,
    /** Tail of the on-device diagnostics/error log for this session. Ships with
     *  the R2 bundle so scan problems are debuggable from the backend without
     *  asking the tester for a screen recording. Capped — see
     *  `DiagnosticsLog.DIAGNOSTICS_TAIL_MAX` (composeApp), which every
     *  construction site applies via `DiagnosticsLog.tailForManifest()`. */
    val diagnosticsLog: List<String>? = null,
    /**
     * R2 keys (a subset of [photoKeys]) of photos captured while >= 2
     * identified-litter reticles were on screen at once — the "best shots
     * for our display image in the backend extraction mechanisms" (Task 17).
     * Additive/optional so manifests recorded before this field existed
     * still decode; null (not just empty) when no photo in this session
     * qualified.
     */
    val multiItemPhotoKeys: List<String>? = null,
    /**
     * AR↔world registration pairings recorded during the session — the
     * bridge between the pose sidecar's AR-local frame and the world frame
     * (see [GeoRefEvent]). Null for sessions recorded before this existed;
     * a session with capture chunks but no events (e.g. geo-ref never
     * paired) is NOT world-registrable and the reconstruction pipeline
     * should treat its splat as local-only.
     */
    val geoRefEvents: List<GeoRefEvent>? = null,
    /**
     * Axes/alignment convention of every AR-local value in this manifest
     * (pose sidecar transforms, [geoRefEvents] positions). Always
     * [AR_FRAME_CONVENTION] when written by current clients; null on older
     * manifests (same convention, just undeclared).
     */
    val arFrameConvention: String? = null,
    /**
     * R2 key of this session's flight-recorder event stream — a gzipped JSONL
     * file (`…/events.jsonl.gz`) with one structured entry per UX/system
     * event (captions, tap→raycast→pill transitions, confirm/reject,
     * auto-detections, gate changes, coverage milestones, lap transitions,
     * upload results, errors), timestamped session-relative. Uploaded ALWAYS
     * on completion (scan-session-replay PRD Phase 1); the backend indexer
     * fetches it to extract headline metrics at index time. Null for sessions
     * recorded before the flight recorder existed, or when the upload is
     * still pending retry.
     */
    val eventLogKey: String? = null,
    /** Event-stream schema version for [eventLogKey] (currently 1). */
    val eventLogSchema: Int? = null,
    /**
     * Session-level capture-quality summary (video coverage, thermal
     * high-water mark, degraded/dropped chunk counts, writer errors) — see
     * [CaptureHealth]. Null for sessions recorded before this existed,
     * off-iOS, or when the native health JSON failed to decode.
     */
    val captureHealth: CaptureHealth? = null,
    /**
     * Ground-surface breakdown of the credited coverage cells at completion —
     * wire surface name ("grass" | "pavement" | "street" | "dirt") to cell
     * count. Reconstruction/backdrop context for the DGX pipeline and the
     * Stage-2 detector's training data ("identify litter against its
     * backdrop"). Null for sessions recorded before surface tagging existed
     * or when no cell was close enough to tag.
     */
    val surfaceBreakdown: Map<String, Int>? = null,
    /**
     * COMPLETE error-level log for the session (chronological, capped at
     * 200 lines client-side) — unlike [diagnosticsLog] (a mixed-severity
     * tail that can rotate errors out), this is every error the session
     * hit. Feeds scripts/reconstruction/analyze_scan_errors.py, whose
     * findings drive the /api/scan-config tuning loop. Null = clean
     * session or pre-175 client.
     */
    val errorLog: List<String>? = null,
) {
    companion object {
        /** ARKit `gravityAndHeading`: +X = east, +Y = up (gravity-aligned),
         *  -Z = true north; units meters. The pose sidecar's `transform[16]`
         *  is the camera-to-world simd_float4x4 flattened COLUMN-major
         *  (columns.0.xyzw, columns.1.xyzw, …), `intrinsics[9]` likewise
         *  column-major. */
        const val AR_FRAME_CONVENTION = "gravityAndHeading:+x=east,+y=up,-z=north;meters;colmajor"
    }
}

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
