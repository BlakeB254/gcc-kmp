@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mirrors `BlockCompletionReportRow` from
 * `site/src/db/schema.ts`/`site/src/lib/block-completion-report.ts` (Pillar 9,
 * Task 1) — the response body of `GET /api/jobs/{id}/completion-report`.
 *
 * Not to be confused with [CompletionReport] in this same package, which
 * models a different concept (a job's *payout* summary — `payoutAmount`,
 * `fundType`, etc. — currently always null server-side, see `Job.kt`). This
 * type is the post-scan block-cleanup report: duration, GPS track, before/
 * after photo pairs, weights, and counts assembled from the route_scans
 * session behind the job.
 *
 * `gpsTrack`/`areasCleaned`/`imagePairs`/`countsByType` are all `jsonb`
 * columns server-side and can be null if the underlying session never
 * recorded that data (e.g. no `completedAt` yet at generation time — see
 * `generateBlockCompletionReport`). `gpsTrack.scout`/`.clean` are left as
 * `JsonElement` (raw, possibly-null GeoJSON LineString-ish objects) rather
 * than a typed GeoJSON class since callers here only need a coordinate list
 * for a lightweight polyline preview — see [asLineStringCoordinates].
 */
@Serializable
data class BlockCompletionReport(
    val id: Int,
    val jobId: Int,
    val routeId: Int,
    val workerId: Int,
    val sessionId: Int,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val durationSeconds: Int? = null,
    val gpsTrack: BlockCompletionGpsTrack? = null,
    val areasCleaned: List<BlockCompletionAreaCleaned>? = null,
    val imagePairs: List<BlockCompletionImagePair>? = null,
    val totalEstimatedWeightLbs: Double? = null,
    val cleanedEstimatedWeightLbs: Double? = null,
    val countsByType: BlockCompletionCounts? = null,
    val createdAt: Instant,
)

@Serializable
data class BlockCompletionGpsTrack(
    /** Parsed scoutPathGeoJson (lap 1) — raw GeoJSON LineString-ish, or null. */
    val scout: JsonElement? = null,
    /** Parsed cleanPathGeoJson (lap 2) — raw GeoJSON LineString-ish, or null. */
    val clean: JsonElement? = null,
)

@Serializable
data class BlockCompletionAreaCleaned(
    val locationCellId: String,
    val lat: Double,
    val lng: Double,
    val domain: String,
    val observationCount: Int,
)

@Serializable
data class BlockCompletionPhotoSide(
    val photoUrl: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Instant,
)

@Serializable
data class BlockCompletionImagePair(
    val locationCellId: String,
    val itemType: String? = null,
    val before: BlockCompletionPhotoSide,
    /** null when no after-candidate matched this before-candidate's cell+type. */
    val after: BlockCompletionPhotoSide? = null,
)

@Serializable
data class BlockCompletionCounts(
    val byType: Map<String, Int> = emptyMap(),
    val bySeverity: Map<String, Int> = emptyMap(),
)

/**
 * Best-effort extraction of a coordinate list from a raw GeoJSON
 * LineString-shaped [JsonElement] (`{"type":"LineString","coordinates":[[lng,lat],...]}`).
 * Returns an empty list for null input or anything that doesn't match that
 * shape — this is a lightweight preview helper, not a general GeoJSON parser.
 */
fun JsonElement?.asLineStringCoordinates(): List<Coordinates> {
    val coordinates = (this as? JsonObject)?.get("coordinates") as? JsonArray ?: return emptyList()
    return coordinates.mapNotNull { point ->
        val pair = point as? JsonArray ?: return@mapNotNull null
        val lng = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lat = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        Coordinates(lng = lng, lat = lat)
    }
}
