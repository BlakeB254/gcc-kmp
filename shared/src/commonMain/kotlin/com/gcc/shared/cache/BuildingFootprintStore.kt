package com.gcc.shared.cache

import app.cash.sqldelight.db.SqlDriver
import com.gcc.shared.model.BuildingPolygon
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.floor

/** One cached tile of building footprints from `GET /api/map/buildings`. */
data class CachedBuildingFootprints(
    val tileKey: String,
    val buildings: List<BuildingPolygon>,
    val cachedAtMs: Long,
)

/**
 * Local per-tile cache of real building footprints — offline mirror of
 * `GET /api/map/buildings`. Keyed by the same ~1.1km lat/lng tile the server
 * quantizes to ([tileKeyFor]), one row per tile, footprints serialized as JSON.
 * Mirrors [ScanAreaCacheStore]. Feeds [com.gcc.app.ui.map.MappedBuildingRegistry]
 * so the indoor gate's containment check works offline / before a fetch lands.
 */
class BuildingFootprintStore(private val driver: SqlDriver) {
    private val queries = GccDatabase(driver).buildingFootprintCacheQueries

    init {
        ensureTable()
    }

    /**
     * keep in sync — upgraded installs create this table via
     * BuildingFootprintStore.ensureTable, fresh installs via Schema.create.
     *
     * Same hazard [CaptureChunkStore.ensureTable] documents: SQLDelight's
     * `GccDatabase.Schema.create()` only runs against a brand new "gcc.db",
     * and adding this table never bumped `PRAGMA user_version`, so an upgrade
     * from an older build lands on a gcc.db with routeCache/scanAreaCache but
     * NOT buildingFootprintCache — the first [get] would throw "no such
     * table". The DDL must stay byte-consistent with
     * BuildingFootprintCache.sq's `CREATE TABLE buildingFootprintCache`.
     */
    private fun ensureTable() {
        driver.execute(
            null,
            """
            |CREATE TABLE IF NOT EXISTS buildingFootprintCache (
            |  tileKey TEXT NOT NULL PRIMARY KEY,
            |  buildingsJson TEXT NOT NULL,
            |  cachedAtMs INTEGER NOT NULL
            |)
            """.trimMargin(),
            0,
        )
    }

    fun put(tileKey: String, buildings: List<BuildingPolygon>, nowMs: Long) {
        queries.insertOrReplace(tileKey, GccJson.encodeToString(buildings), nowMs)
    }

    fun get(tileKey: String): CachedBuildingFootprints? =
        queries.selectByTile(tileKey).executeAsOneOrNull()?.let(::decodeRow)

    /** Drop tiles older than [maxAgeMs]; call opportunistically after writes. */
    fun prune(nowMs: Long, maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
        queries.deleteOlderThan(nowMs - maxAgeMs)
    }

    private fun decodeRow(row: BuildingFootprintCache): CachedBuildingFootprints? =
        try {
            CachedBuildingFootprints(
                tileKey = row.tileKey,
                buildings = GccJson.decodeFromString(row.buildingsJson),
                cachedAtMs = row.cachedAtMs,
            )
        } catch (e: Exception) {
            // A corrupt/schema-drifted row must not break the whole read.
            null
        }

    companion object {
        /** ~0.01° ≈ 1.1km — matches ScanAreaCacheStore.TILE_DEGREES and the
         *  server's TILE_DEGREES so client/server quantize identically. */
        private const val TILE_DEGREES = 0.01
        const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** Stable tile key for a coordinate: `"b:{latIdx}:{lngIdx}"`. */
        fun tileKeyFor(lat: Double, lng: Double): String {
            val latIdx = floor(lat / TILE_DEGREES).toInt()
            val lngIdx = floor(lng / TILE_DEGREES).toInt()
            return "b:$latIdx:$lngIdx"
        }

        /** The tile's bbox as `"minLng,minLat,maxLng,maxLat"` for the fetch. */
        fun tileBbox(tileKey: String): String {
            val (_, latPart, lngPart) = tileKey.split(":")
            val latIdx = latPart.toInt()
            val lngIdx = lngPart.toInt()
            val minLat = latIdx * TILE_DEGREES
            val minLng = lngIdx * TILE_DEGREES
            val maxLat = (latIdx + 1) * TILE_DEGREES
            val maxLng = (lngIdx + 1) * TILE_DEGREES
            return "$minLng,$minLat,$maxLng,$maxLat"
        }
    }
}
