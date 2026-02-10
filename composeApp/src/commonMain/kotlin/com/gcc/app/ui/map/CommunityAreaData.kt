package com.gcc.app.ui.map

data class AreaBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

data class CommunityArea(
    val number: Int,
    val name: String,
    val displayName: String,
    val bounds: AreaBounds,
    /** Simplified polygon outline (lat/lng pairs, 5-8 vertices). Used for Canvas rendering. */
    val polygon: List<Pair<Double, Double>> = emptyList(),
    val isServiceArea: Boolean = false,
)

/**
 * 10 Chicago community areas centered on North Lawndale.
 *
 * Polygon points are simplified approximations (~6 vertices each) derived from
 * Chicago open data community area boundaries. These give a much more realistic
 * outline than bounding-box rectangles while keeping Canvas performance high.
 *
 * When we upgrade to MapLibre (Kotlin 2.2+), real GeoJSON polygons will replace these.
 */
val chicagoCommunityAreas = listOf(
    CommunityArea(
        number = 29,
        name = "north_lawndale",
        displayName = "North\nLawndale",
        bounds = AreaBounds(41.8750, 41.8440, -87.6920, -87.7410),
        polygon = listOf(
            41.8750 to -87.7410,
            41.8750 to -87.6920,
            41.8600 to -87.6920,
            41.8440 to -87.6950,
            41.8440 to -87.7350,
            41.8550 to -87.7410,
        ),
        isServiceArea = true,
    ),
    CommunityArea(
        number = 30,
        name = "south_lawndale",
        displayName = "South\nLawndale",
        bounds = AreaBounds(41.8440, 41.8140, -87.6920, -87.7350),
        polygon = listOf(
            41.8440 to -87.7350,
            41.8440 to -87.6950,
            41.8440 to -87.6920,
            41.8200 to -87.6920,
            41.8140 to -87.7000,
            41.8140 to -87.7350,
        ),
    ),
    CommunityArea(
        number = 27,
        name = "east_garfield_park",
        displayName = "E Garfield\nPark",
        bounds = AreaBounds(41.8870, 41.8750, -87.6920, -87.7150),
        polygon = listOf(
            41.8870 to -87.7150,
            41.8870 to -87.6920,
            41.8790 to -87.6920,
            41.8750 to -87.6920,
            41.8750 to -87.7150,
        ),
    ),
    CommunityArea(
        number = 26,
        name = "west_garfield_park",
        displayName = "W Garfield\nPark",
        bounds = AreaBounds(41.8870, 41.8750, -87.7150, -87.7440),
        polygon = listOf(
            41.8870 to -87.7440,
            41.8870 to -87.7150,
            41.8750 to -87.7150,
            41.8750 to -87.7410,
            41.8800 to -87.7440,
        ),
    ),
    CommunityArea(
        number = 25,
        name = "austin",
        displayName = "Austin",
        bounds = AreaBounds(41.8980, 41.8640, -87.7440, -87.7790),
        polygon = listOf(
            41.8980 to -87.7790,
            41.8980 to -87.7440,
            41.8870 to -87.7440,
            41.8640 to -87.7440,
            41.8640 to -87.7790,
        ),
    ),
    CommunityArea(
        number = 23,
        name = "humboldt_park",
        displayName = "Humboldt\nPark",
        bounds = AreaBounds(41.9070, 41.8870, -87.6920, -87.7280),
        polygon = listOf(
            41.9070 to -87.7280,
            41.9070 to -87.6920,
            41.8960 to -87.6920,
            41.8870 to -87.6920,
            41.8870 to -87.7150,
            41.8870 to -87.7280,
        ),
    ),
    CommunityArea(
        number = 28,
        name = "near_west_side",
        displayName = "Near\nWest Side",
        bounds = AreaBounds(41.8870, 41.8630, -87.6310, -87.6920),
        polygon = listOf(
            41.8870 to -87.6920,
            41.8870 to -87.6640,
            41.8870 to -87.6310,
            41.8630 to -87.6310,
            41.8630 to -87.6920,
        ),
    ),
    CommunityArea(
        number = 31,
        name = "lower_west_side",
        displayName = "Lower\nWest Side",
        bounds = AreaBounds(41.8630, 41.8380, -87.6510, -87.6920),
        polygon = listOf(
            41.8630 to -87.6920,
            41.8630 to -87.6510,
            41.8380 to -87.6510,
            41.8380 to -87.6750,
            41.8450 to -87.6920,
        ),
    ),
    CommunityArea(
        number = 35,
        name = "douglas",
        displayName = "Douglas",
        bounds = AreaBounds(41.8440, 41.8140, -87.6050, -87.6510),
        polygon = listOf(
            41.8440 to -87.6510,
            41.8440 to -87.6050,
            41.8140 to -87.6050,
            41.8140 to -87.6510,
        ),
    ),
    CommunityArea(
        number = 24,
        name = "west_town",
        displayName = "West\nTown",
        bounds = AreaBounds(41.9070, 41.8870, -87.6520, -87.6920),
        polygon = listOf(
            41.9070 to -87.6920,
            41.9070 to -87.6520,
            41.8920 to -87.6520,
            41.8870 to -87.6640,
            41.8870 to -87.6920,
        ),
    ),
)
