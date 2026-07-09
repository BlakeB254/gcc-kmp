package com.gcc.app.ui.map

import com.gcc.shared.geo.pointInGeoJsonRing

/**
 * A rectangular building footprint defined by lat/lng bounds.
 *
 * @param north  Northern latitude of the footprint
 * @param south  Southern latitude of the footprint
 * @param east   Eastern longitude of the footprint
 * @param west   Western longitude of the footprint
 * @param height Relative building height in stories (used for isometric extrusion)
 */
data class BuildingFootprint(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val height: Int = 2,
)

/**
 * Representative building footprints for the North Lawndale service area.
 *
 * These are simplified approximations of actual building locations in the
 * North Lawndale community area of Chicago. Each footprint represents a
 * rectangular building outline suitable for isometric rendering at street zoom.
 *
 * Organized roughly from north to south along major streets:
 * - Roosevelt Rd (north boundary ~41.866)
 * - Fillmore St (~41.863)
 * - 5th Ave / Arthington St (~41.860)
 * - Polk St (~41.857)
 * - Lexington St (~41.854)
 * - Ogden Ave (diagonal, ~41.855)
 * - Cermak Rd (south boundary ~41.851)
 */
val northLawndaleBuildings = listOf(
    // === Roosevelt Rd corridor (commercial) ===
    BuildingFootprint(
        north = 41.86630, south = 41.86600,
        east = -87.7160, west = -87.7168,
        height = 3,
    ),
    BuildingFootprint(
        north = 41.86630, south = 41.86605,
        east = -87.7145, west = -87.7152,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.86625, south = 41.86600,
        east = -87.7128, west = -87.7138,
        height = 4,
    ),
    BuildingFootprint(
        north = 41.86630, south = 41.86610,
        east = -87.7100, west = -87.7112,
        height = 3,
    ),

    // === Fillmore St residential ===
    BuildingFootprint(
        north = 41.86350, south = 41.86320,
        east = -87.7190, west = -87.7198,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.86350, south = 41.86325,
        east = -87.7175, west = -87.7182,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.86345, south = 41.86315,
        east = -87.7155, west = -87.7163,
        height = 3,
    ),
    BuildingFootprint(
        north = 41.86340, south = 41.86318,
        east = -87.7130, west = -87.7140,
        height = 2,
    ),

    // === Arthington St residential ===
    BuildingFootprint(
        north = 41.86050, south = 41.86020,
        east = -87.7200, west = -87.7210,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.86045, south = 41.86020,
        east = -87.7182, west = -87.7190,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.86050, south = 41.86025,
        east = -87.7160, west = -87.7170,
        height = 3,
    ),
    BuildingFootprint(
        north = 41.86040, south = 41.86018,
        east = -87.7140, west = -87.7148,
        height = 2,
    ),

    // === Polk St - mixed use ===
    BuildingFootprint(
        north = 41.85750, south = 41.85720,
        east = -87.7210, west = -87.7220,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.85745, south = 41.85718,
        east = -87.7190, west = -87.7200,
        height = 3,
    ),
    BuildingFootprint(
        north = 41.85750, south = 41.85725,
        east = -87.7168, west = -87.7178,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.85740, south = 41.85715,
        east = -87.7148, west = -87.7155,
        height = 2,
    ),

    // === Lexington St residential ===
    BuildingFootprint(
        north = 41.85450, south = 41.85420,
        east = -87.7200, west = -87.7210,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.85445, south = 41.85420,
        east = -87.7180, west = -87.7188,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.85450, south = 41.85425,
        east = -87.7158, west = -87.7168,
        height = 3,
    ),

    // === Ogden Ave diagonal corridor (commercial) ===
    BuildingFootprint(
        north = 41.85560, south = 41.85530,
        east = -87.7100, west = -87.7114,
        height = 3,
    ),
    BuildingFootprint(
        north = 41.85520, south = 41.85495,
        east = -87.7115, west = -87.7126,
        height = 4,
    ),
    BuildingFootprint(
        north = 41.85480, south = 41.85455,
        east = -87.7130, west = -87.7140,
        height = 3,
    ),

    // === Cermak Rd southern boundary ===
    BuildingFootprint(
        north = 41.85150, south = 41.85120,
        east = -87.7180, west = -87.7195,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.85150, south = 41.85125,
        east = -87.7155, west = -87.7168,
        height = 3,
    ),
    BuildingFootprint(
        north = 41.85145, south = 41.85118,
        east = -87.7130, west = -87.7142,
        height = 2,
    ),

    // === Central Park Ave (north-south spine) ===
    BuildingFootprint(
        north = 41.86500, south = 41.86470,
        east = -87.7160, west = -87.7170,
        height = 3,
    ),
    BuildingFootprint(
        north = 41.86200, south = 41.86175,
        east = -87.7158, west = -87.7168,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.85900, south = 41.85870,
        east = -87.7160, west = -87.7172,
        height = 2,
    ),
    BuildingFootprint(
        north = 41.85600, south = 41.85575,
        east = -87.7158, west = -87.7168,
        height = 3,
    ),
)


/**
 * In-memory registry of real building footprints near the user, keyed by the
 * ~1.1km tile they were fetched for. Populated by
 * [com.gcc.app.platform.BuildingFootprintLoader] from the SQLDelight cache +
 * `GET /api/map/buildings`. Rings are GeoJSON linear rings ([lng,lat]).
 *
 * Keyed by tile so a user near a tile boundary keeps footprints from both
 * tiles live; [current] flattens all registered tiles. Single-writer by
 * construction — only [com.gcc.app.platform.BuildingFootprintLoader] mutates
 * it, from the one active scan's location-flow coroutine — and readers only
 * read the whole-list [flattened] reference, so a plain reference-swap is
 * safe (no JVM-only @Synchronized, which isn't available in commonMain).
 */
object MappedBuildingRegistry {
    private val ringsByTile = mutableMapOf<String, List<List<List<Double>>>>()
    private var flattened: List<List<List<Double>>> = emptyList()

    fun setRingsForTile(tileKey: String, rings: List<List<List<Double>>>) {
        ringsByTile[tileKey] = rings
        flattened = ringsByTile.values.flatten()
    }

    fun clear() {
        ringsByTile.clear()
        flattened = emptyList()
    }

    fun current(): List<List<List<Double>>> = flattened
    fun hasFootprints(): Boolean = flattened.isNotEmpty()
}

/**
 * True when (lat, lng) falls inside a mapped building.
 *
 * When real footprints have been cached/fetched for the area
 * ([MappedBuildingRegistry] non-empty), this is a point-in-polygon test over
 * those OSM building rings. When nothing is cached yet (offline, or Overpass
 * unreachable), it falls back to the hardcoded North Lawndale rectangles so
 * the gate never regresses vs. the old behavior. Either way, "my dot is in the
 * middle of a building" and the OutdoorGate's map evidence can never disagree.
 */
fun isInsideMappedBuilding(lat: Double, lng: Double): Boolean {
    val rings = MappedBuildingRegistry.current()
    if (rings.isNotEmpty()) {
        return rings.any { ring -> pointInGeoJsonRing(lng = lng, lat = lat, ring = ring) }
    }
    // Offline fallback: hardcoded North Lawndale rectangles.
    return northLawndaleBuildings.any { b ->
        lat <= b.north && lat >= b.south && lng <= b.east && lng >= b.west
    }
}
