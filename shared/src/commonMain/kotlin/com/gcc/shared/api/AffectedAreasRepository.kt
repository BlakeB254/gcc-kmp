package com.gcc.shared.api

import com.gcc.shared.model.AffectedBlock
import com.gcc.shared.model.AffectedBlockStatus
import com.gcc.shared.model.AffectedAreasCollection
import com.gcc.shared.model.Coordinates
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches affected-areas GeoJSON and exposes a typed list for the map.
 *
 * The server returns raw GeoJSON (not wrapped in `ApiResponse`), so we
 * call through the underlying [GccApiClient.httpClient] directly rather
 * than the usual unwrap helpers.
 *
 * State model:
 *   - [areas] — last successful fetch (empty list on first load)
 *   - [isRefreshing] — true while an HTTP call is in flight
 *   - [lastError] — most recent error message, nulled on success
 */
class AffectedAreasRepository(
    private val apiClient: GccApiClient,
) {
    private val _areas = MutableStateFlow<List<AffectedBlock>>(emptyList())
    val areas: Flow<List<AffectedBlock>> = _areas.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: Flow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: Flow<String?> = _lastError.asStateFlow()

    /**
     * Single-shot fetch. Returns the decoded list; also pushes into [areas].
     * Throws on transport / decode error — callers that want silent refresh
     * should use [refreshSafely].
     */
    suspend fun fetch(
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
    ): List<AffectedBlock> {
        _isRefreshing.value = true
        try {
            val url = buildString {
                append(apiClient.baseUrl)
                append("/api/affected-areas?minStatus=")
                append(minStatus.wireName())
                if (serviceAreaId != null) append("&serviceAreaId=").append(serviceAreaId)
            }
            val response = apiClient.httpClient.get(url)
            if (!response.status.isSuccess()) {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                val msg = "HTTP ${response.status.value}: $body"
                _lastError.value = msg
                throw IllegalStateException(msg)
            }
            val collection: AffectedAreasCollection = response.body()
            val decoded = collection.features.mapNotNull { feature ->
                val props = feature.properties
                AffectedBlock(
                    blockId = props.blockId,
                    code = props.code,
                    name = props.name,
                    status = AffectedBlockStatus.fromString(props.status),
                    intensity = props.intensity,
                    scheduledCleaningAt = props.scheduledCleaningAt,
                    sponsorCount = props.sponsorCount,
                    fundingCurrent = props.fundingCurrent,
                    fundingGoal = props.fundingGoal,
                    completedAt = props.completedAt,
                    serviceAreaName = props.serviceAreaName,
                    center = if (props.centerLat != null && props.centerLng != null) {
                        Coordinates(lng = props.centerLng, lat = props.centerLat)
                    } else null,
                    polygon = extractPolygonRing(feature.geometry),
                )
            }
            _areas.value = decoded
            _lastError.value = null
            return decoded
        } finally {
            _isRefreshing.value = false
        }
    }

    /** Never throws — swallows into [lastError]. Safe to call from a timer loop. */
    suspend fun refreshSafely(
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
    ) {
        try {
            fetch(serviceAreaId, minStatus)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Unknown error"
        }
    }

    /**
     * Polling loop — intended for use inside a `LaunchedEffect` keyed on
     * the screen's lifetime. Calls [refreshSafely] every [intervalMs] ms
     * until the coroutine is cancelled.
     *
     * 30-second default matches the perceptible-change cadence for
     * assignment-queue + cleaning-status transitions.
     */
    suspend fun pollForever(
        intervalMs: Long = 30_000L,
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
    ) {
        while (true) {
            refreshSafely(serviceAreaId, minStatus)
            delay(intervalMs)
        }
    }
}

private fun AffectedBlockStatus.wireName(): String = when (this) {
    AffectedBlockStatus.AVAILABLE -> "available"
    AffectedBlockStatus.FUNDED -> "funded"
    AffectedBlockStatus.IN_PROGRESS -> "in_progress"
    AffectedBlockStatus.COMPLETED -> "completed"
}

/**
 * Pull the outer ring out of a GeoJSON Polygon.
 *
 * Server only emits simple Polygons today but the model holds the raw
 * element so more geometry types can be added without a migration. For
 * anything that isn't a Polygon with at least one ring, we return an
 * empty list — the Canvas renderer then uses the feature's center point.
 */
private fun extractPolygonRing(geometry: JsonElement): List<Coordinates> {
    val obj = runCatching {
        (geometry as? kotlinx.serialization.json.JsonObject) ?: return emptyList()
    }.getOrNull() ?: return emptyList()

    val type = obj["type"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
    if (type != "Polygon") return emptyList()

    val coordinates = obj["coordinates"] as? JsonArray ?: return emptyList()
    val outerRing = (coordinates.firstOrNull() as? JsonArray) ?: return emptyList()

    return outerRing.mapNotNull { pointElement ->
        val pointArray = pointElement as? JsonArray ?: return@mapNotNull null
        if (pointArray.size < 2) return@mapNotNull null
        val lng = pointArray[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
        val lat = pointArray[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
        Coordinates(lng = lng, lat = lat)
    }
}

