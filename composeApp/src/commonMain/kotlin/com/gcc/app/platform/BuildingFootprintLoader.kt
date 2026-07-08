package com.gcc.app.platform

import com.gcc.app.ui.map.MappedBuildingRegistry
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.cache.BuildingFootprintStore

/**
 * Populates [MappedBuildingRegistry] with real building footprints near the
 * user so the indoor gate's `isInsideMappedBuilding` runs point-in-polygon
 * over OSM buildings instead of the hardcoded rectangles.
 *
 * Cache-first (seed the registry from the local [BuildingFootprintStore]
 * immediately), then lazily fetch from `GET /api/map/buildings` on a
 * miss/stale tile, persist, and publish. NEVER throws — an Overpass/network
 * failure just leaves the rectangle fallback (or a stale-but-usable tile) in
 * place (spec §1: never block a scan on the footprint fetch).
 */
class BuildingFootprintLoader(
    private val apiClient: GccApiClient,
    private val store: BuildingFootprintStore,
) {
    // Tiles already loaded this session (registry populated). A tile whose
    // fetch failed is NOT retained here, so a later fix retries it.
    private val loadedTiles = mutableSetOf<String>()

    suspend fun ensureFootprintsFor(lat: Double, lng: Double, nowMs: Long) {
        val tileKey = BuildingFootprintStore.tileKeyFor(lat, lng)
        if (tileKey in loadedTiles) return

        // Cache-first: seed the registry from the local store immediately.
        val cached = store.get(tileKey)
        if (cached != null) {
            MappedBuildingRegistry.setRingsForTile(tileKey, cached.buildings.map { it.polygon })
            if (nowMs - cached.cachedAtMs < BuildingFootprintStore.DEFAULT_MAX_AGE_MS) {
                loadedTiles.add(tileKey)
                return
            }
        }

        // Cache-miss/stale: lazily fetch. Non-fatal — a failure keeps whatever
        // the registry already has (rectangles, or a stale-but-usable tile).
        val fetched = try {
            apiClient.getBuildingFootprints(BuildingFootprintStore.tileBbox(tileKey))
        } catch (e: Exception) {
            DiagnosticsLog.error(
                "building-footprints",
                "fetch failed for $tileKey (rectangle/stale fallback retained)",
                e,
            )
            return // not marked loaded → retried on a later fix
        }
        store.put(tileKey, fetched, nowMs)
        store.prune(nowMs)
        MappedBuildingRegistry.setRingsForTile(tileKey, fetched.map { it.polygon })
        loadedTiles.add(tileKey)
    }
}
