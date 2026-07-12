package com.gcc.shared.model

import kotlinx.serialization.Serializable

/**
 * Admin scan-session review DTOs (scan-session-replay PRD Phase 2) — the
 * wire contract of GET /api/admin/scan-sessions[/{sessionId}], mirrored here
 * per the schema-ownership rule so the iOS admin panel consumes the review
 * surface through the SAME API patterns as every other admin feature
 * (ApiResponse envelope + GccApiClient method + shared @Serializable model).
 *
 * Every field is defaulted so the DTO tolerates route evolution in either
 * direction (the site adds a column → older apps ignore it; the app updates
 * first → missing fields decode to defaults).
 */
@Serializable
data class AdminScanSessionSummary(
    val id: Int = 0,
    val sessionId: String,
    val deviceId: String = "",
    val deviceModel: String? = null,
    val platform: String? = null,
    val osVersion: String? = null,
    val lapKind: String? = null,
    val routeId: Int? = null,
    val locationCellId: String? = null,
    /** ISO-8601 strings as the site API emits them (Drizzle string mode). */
    val startedAt: String? = null,
    val completedAt: String? = null,
    val photoCount: Int = 0,
    val chunkCount: Int = 0,
    val observedItemCount: Int = 0,
    val scannedCellCount: Int = 0,
    val totalBytes: Long? = null,
    val geoRefEventCount: Int = 0,
    val eventLogR2Key: String? = null,
    // Flight-recorder headline metrics (null = predates the recorder or the
    // event log isn't indexed yet, never zero activity).
    val eventCount: Int? = null,
    val gateFlipCount: Int? = null,
    val tapCount: Int? = null,
    val confirmCount: Int? = null,
    val rejectCount: Int? = null,
    val raycastMissCount: Int? = null,
    val detectionCount: Int? = null,
    val uploadAttemptCount: Int? = null,
    val uploadFailureCount: Int? = null,
    val errorCount: Int? = null,
    val indexedAt: String? = null,
    /** SERVER-derived (site/src/lib/scan-session-flags.ts) so every admin
     *  client renders the same flags from one threshold set: `crashed`,
     *  `gate-flapped`, `upload-failures`, `errors`, `no-capture`,
     *  `raycast-misses`. */
    val redFlags: List<String> = emptyList(),
)

/** One indexed R2 artifact of a session, with a short-lived presigned GET
 *  URL when R2 is configured server-side (null otherwise). */
@Serializable
data class AdminScanChunk(
    val id: Int = 0,
    val sessionId: String = "",
    val locationCellId: String? = null,
    val artifactType: String,
    val r2Key: String,
    val format: String? = null,
    val byteSize: Long? = null,
    val capturedAt: String? = null,
    val url: String? = null,
)

/**
 * Full replay payload: the indexed row, every artifact (with expiring
 * presigned URLs), the manifest fetched server-side from R2, and the
 * flight-recorder event stream gunzipped + parsed server-side. `manifest`/
 * `events` are null when R2 is unreachable or the session predates the
 * recorder.
 */
@Serializable
data class AdminScanSessionDetail(
    val session: AdminScanSessionSummary,
    val chunks: List<AdminScanChunk> = emptyList(),
    val manifest: EnvScanManifest? = null,
    val events: List<ScanSessionEvent>? = null,
    val presignExpiresInSeconds: Int? = null,
)
