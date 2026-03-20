/**
 * Generate realistic coordinates at Chicago grid intersections.
 *
 * Chicago's street grid is remarkably regular:
 * - 8 blocks per mile (east-west), numbered in 100s from State St
 * - 8 blocks per mile (north-south), numbered in 100s from Madison St
 * - Major streets every half mile (400 addresses)
 *
 * We generate coordinates within community area polygons by:
 * 1. Computing a bounding box
 * 2. Snapping to Chicago grid intersections (~125m spacing)
 * 3. Filtering to points inside the polygon
 * 4. Randomly selecting from valid intersections
 */

import { COMMUNITY_AREAS, type CommunityAreaGeo } from '../lib/chicago-geo-data'

// Chicago grid spacing in degrees (approximate at Chicago latitude)
// 1 block ≈ 100m ≈ 0.0009° lat, 0.0012° lng
const BLOCK_LAT = 0.0009
const BLOCK_LNG = 0.0012

// Grid spacing: every 2 blocks for intersections
const GRID_LAT = BLOCK_LAT * 2
const GRID_LNG = BLOCK_LNG * 2

/** Ray-casting point-in-polygon */
function pointInPolygon(lat: number, lng: number, polygon: [number, number][]): boolean {
  let inside = false
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [yi, xi] = polygon[i]
    const [yj, xj] = polygon[j]
    if (((yi > lat) !== (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
      inside = !inside
    }
  }
  return inside
}

/** Get all valid grid intersections within a community area polygon */
function getGridPoints(area: CommunityAreaGeo): [number, number][] {
  const { bounds, polygon } = area
  if (polygon.length === 0) return []

  const points: [number, number][] = []
  // Add small jitter to avoid exact boundary issues
  for (let lat = bounds.south + GRID_LAT; lat < bounds.north; lat += GRID_LAT) {
    for (let lng = bounds.west + GRID_LNG; lng > bounds.east; lng -= GRID_LNG) {
      // Chicago longitudes are negative, east is more negative
      // Wait — east is LESS negative (closer to 0). Let me fix:
      // bounds.west is more negative, bounds.east is less negative
      // So we go from west (more negative) to east (less negative)
    }
    for (let lng = bounds.west; lng <= bounds.east; lng += GRID_LNG) {
      if (pointInPolygon(lat, lng, polygon)) {
        points.push([lat, lng])
      }
    }
  }
  return points
}

/** Cache of grid points per community area */
const gridCache = new Map<number, [number, number][]>()

/** Get N random grid intersection coordinates within a community area */
export function getRandomGridPoints(areaNumber: number, count: number): [number, number][] {
  // Find the area
  const area = COMMUNITY_AREAS.find(a => a.number === areaNumber)
  if (!area) return []

  // Get or compute grid points
  if (!gridCache.has(areaNumber)) {
    gridCache.set(areaNumber, getGridPoints(area))
  }
  const allPoints = gridCache.get(areaNumber)!

  if (allPoints.length === 0) {
    // Fallback: generate random points within bounding box
    const result: [number, number][] = []
    for (let i = 0; i < count; i++) {
      const lat = area.bounds.south + Math.random() * (area.bounds.north - area.bounds.south)
      const lng = area.bounds.west + Math.random() * (area.bounds.east - area.bounds.west)
      result.push([lat, lng])
    }
    return result
  }

  // Shuffle and pick count points (with replacement if needed)
  const result: [number, number][] = []
  const shuffled = [...allPoints].sort(() => Math.random() - 0.5)
  for (let i = 0; i < count; i++) {
    const base = shuffled[i % shuffled.length]
    // Add small random offset to avoid exact duplicates (±50m)
    const lat = base[0] + (Math.random() - 0.5) * 0.0009
    const lng = base[1] + (Math.random() - 0.5) * 0.0012
    result.push([lat, lng])
  }
  return result
}

/** Get the display name for a community area by number */
export function getAreaDisplayName(number: number): string {
  return COMMUNITY_AREAS.find(a => a.number === number)?.displayName || `Area ${number}`
}
