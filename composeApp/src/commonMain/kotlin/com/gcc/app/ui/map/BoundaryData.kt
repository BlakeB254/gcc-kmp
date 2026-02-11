package com.gcc.app.ui.map

/**
 * Simplified geographic boundary polygons for map rendering.
 *
 * These are approximate outlines (~15-20 vertices each) for rendering at
 * state and city zoom levels. They provide geographic context when zoomed
 * out beyond community area level.
 */

/**
 * Simplified Illinois state outline (~15 key vertices).
 * Covers the rough shape: NW corner, NE along WI border, down the lake,
 * southern tip at Cairo, and back up the Mississippi.
 */
val illinoisOutline: List<Pair<Double, Double>> = listOf(
    42.4970 to -90.6390,   // NW corner (Galena area)
    42.5080 to -89.3640,   // N border midpoint
    42.4950 to -87.8000,   // NE near Waukegan
    42.0230 to -87.5230,   // Chicago lakefront north
    41.6210 to -87.5240,   // Chicago lakefront south (Gary border)
    41.1720 to -87.5300,   // Kankakee area
    40.4820 to -87.5260,   // Danville area
    39.3510 to -87.5330,   // E border mid
    38.7360 to -87.5090,   // Vincennes area
    37.7770 to -87.6900,   // SE curve toward Ohio River
    37.0050 to -89.1710,   // Southern tip (Cairo)
    37.3590 to -89.5210,   // Cape Girardeau area
    38.2270 to -90.5740,   // St. Louis area
    38.7660 to -90.1580,   // Alton area
    39.7180 to -91.3750,   // Quincy area
    40.6270 to -91.1200,   // Ft. Madison area
    41.2380 to -91.0530,   // Burlington area
    42.1570 to -90.1530,   // Dubuque area
)

/**
 * Simplified Chicago city boundary (~20 key vertices).
 * Follows the rough outline: north along lake, west to O'Hare,
 * south along western edge, east along southern edge back to lake.
 */
val chicagoCityOutline: List<Pair<Double, Double>> = listOf(
    42.0230 to -87.6690,   // Rogers Park (N lakefront)
    42.0190 to -87.6340,   // Juneway Terrace (NE corner)
    41.8850 to -87.6100,   // Lincoln Park lakefront
    41.8430 to -87.6060,   // Grant Park / downtown lakefront
    41.7930 to -87.5910,   // Hyde Park lakefront
    41.7450 to -87.5430,   // South Shore / Rainbow Beach
    41.6440 to -87.5240,   // SE corner (Hegewisch)
    41.6440 to -87.5520,   // Hegewisch inland
    41.6440 to -87.6240,   // Riverdale / far south
    41.6530 to -87.6820,   // Beverly area
    41.6900 to -87.7580,   // Clearing / Midway area
    41.7260 to -87.7870,   // SW corner (Mt. Greenwood)
    41.7880 to -87.7870,   // Garfield Ridge
    41.8380 to -87.7900,   // W side mid (Cicero border)
    41.8810 to -87.8050,   // Austin / W boundary
    41.9160 to -87.8360,   // Montclare
    41.9450 to -87.8390,   // O'Hare south edge
    41.9860 to -87.8360,   // O'Hare west
    42.0060 to -87.8360,   // O'Hare north
    42.0190 to -87.7900,   // Edgebrook area
    42.0230 to -87.7570,   // West Ridge (NW)
    42.0230 to -87.6870,   // Rogers Park (N west)
)
