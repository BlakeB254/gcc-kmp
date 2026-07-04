package com.gcc.shared.cache

import app.cash.sqldelight.db.SqlDriver
import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.WorldObservation
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.floor

/** One cached area snapshot: what the map endpoints last returned for it. */
data class CachedAreaScan(
    val areaKey: String,
    val coverageCells: List<MapCoverageCell>,
    val observations: List<WorldObservation>,
    val cachedAtMs: Long,
)

/**
 * Local cache of the latest scan state per map area — the offline mirror of
 * `/api/map/coverage` + `/api/map/observations`. Keyed by a ~1.1km lat/lng
 * tile (matches the ~650m fetch pad the map screens use), one row per tile,
 * lists serialized as JSON like [RouteCacheStore]. Screens write on every
 * successful fetch and read when offline/at open, so the map and scan
 * minimaps always have the last-known coverage picture instead of nothing.
 */
class ScanAreaCacheStore(driver: SqlDriver) {
    private val queries = GccDatabase(driver).scanAreaCacheQueries

    fun put(
        areaKey: String,
        coverageCells: List<MapCoverageCell>,
        observations: List<WorldObservation>,
        nowMs: Long,
    ) {
        queries.insertOrReplace(
            areaKey,
            GccJson.encodeToString(coverageCells),
            GccJson.encodeToString(observations),
            nowMs,
        )
    }

    fun get(areaKey: String): CachedAreaScan? =
        queries.selectByArea(areaKey).executeAsOneOrNull()?.let(::decodeRow)

    fun getAll(): List<CachedAreaScan> =
        queries.selectAll().executeAsList().mapNotNull(::decodeRow)

    /** Drop snapshots older than [maxAgeMs]; call opportunistically after writes. */
    fun prune(nowMs: Long, maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
        queries.deleteOlderThan(nowMs - maxAgeMs)
    }

    private fun decodeRow(row: ScanAreaCache): CachedAreaScan? =
        try {
            CachedAreaScan(
                areaKey = row.areaKey,
                coverageCells = GccJson.decodeFromString(row.coverageJson),
                observations = GccJson.decodeFromString(row.observationsJson),
                cachedAtMs = row.cachedAtMs,
            )
        } catch (e: Exception) {
            // A corrupt/schema-drifted row must not break the whole read.
            null
        }

    companion object {
        /** ~0.01° ≈ 1.1km N-S — tile granularity for area keys. */
        private const val TILE_DEGREES = 0.01
        const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** Stable tile key for a coordinate: `"scan:{latIdx}:{lngIdx}"`. */
        fun areaKeyFor(lat: Double, lng: Double): String {
            val latIdx = floor(lat / TILE_DEGREES).toInt()
            val lngIdx = floor(lng / TILE_DEGREES).toInt()
            return "scan:$latIdx:$lngIdx"
        }
    }
}
