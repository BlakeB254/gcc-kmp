package com.gcc.app.ui.map

import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.GeoJSONMultiPolygon

/**
 * Authored inner-exclusion rings (the outer ring of each island polygon) as
 * map-ready coordinate lists for GccMap's `innerBoundaryRings` parameter.
 *
 * Convention (route-v2): when this returns a non-empty list the caller MUST
 * pass `innerBoundaryFallback = false` — the synthetic 8m-inset ring is a
 * fallback for blocks with no authored islands ONLY, never an overlay on top
 * of authored data.
 */
fun GeoJSONMultiPolygon?.authoredInnerRings(): List<List<Coordinates>> =
    this?.coordinates.orEmpty().mapNotNull { polygon ->
        polygon.firstOrNull()?.mapNotNull { point ->
            val lng = point.getOrNull(0) ?: return@mapNotNull null
            val lat = point.getOrNull(1) ?: return@mapNotNull null
            Coordinates(lat = lat, lng = lng)
        }?.takeIf { it.size >= 3 }
    }
