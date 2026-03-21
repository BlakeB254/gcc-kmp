/**
 * Geographic utility functions for route creation and grid generation.
 * All coordinates follow GeoJSON convention: [lng, lat] unless noted otherwise.
 */

// At Chicago latitude (~41.86°N)
export const METERS_PER_DEG_LAT = 111_000
export const METERS_PER_DEG_LNG = 82_300

const EARTH_RADIUS_M = 6_371_000

function toRad(deg: number): number {
  return (deg * Math.PI) / 180
}

/** Haversine distance between two [lng, lat] points, in meters */
function haversinePair(
  a: [number, number],
  b: [number, number]
): number {
  const [lng1, lat1] = a
  const [lng2, lat2] = b
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const sinLat = Math.sin(dLat / 2)
  const sinLng = Math.sin(dLng / 2)
  const h =
    sinLat * sinLat +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * sinLng * sinLng
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h))
}

/** Total distance in meters along a coordinate array of [lng, lat] pairs */
export function haversineDistance(coords: [number, number][]): number {
  let total = 0
  for (let i = 1; i < coords.length; i++) {
    total += haversinePair(coords[i - 1], coords[i])
  }
  return total
}

/** Estimate walking time in minutes (80 meters/min average pace) */
export function estimateWalkingMinutes(meters: number): number {
  return Math.round(meters / 80)
}

/**
 * Buffer a LineString into a rectangular polygon.
 * Takes [lng, lat] coordinate pairs and offsets latitude by ±buffer.
 * Returns GeoJSON Polygon coordinates (outer ring only).
 */
export function lineStringToBlockPolygon(
  lineCoords: [number, number][],
  bufferMeters = 90
): [number, number][][] {
  if (lineCoords.length < 2) {
    throw new Error('LineString must have at least 2 coordinates')
  }

  const bufferDeg = bufferMeters / METERS_PER_DEG_LAT

  // Get the bounding extents of the line
  let minLng = Infinity, maxLng = -Infinity
  let minLat = Infinity, maxLat = -Infinity
  for (const [lng, lat] of lineCoords) {
    if (lng < minLng) minLng = lng
    if (lng > maxLng) maxLng = lng
    if (lat < minLat) minLat = lat
    if (lat > maxLat) maxLat = lat
  }

  // Create rectangle: extend north and south by buffer
  const south = minLat - bufferDeg
  const north = maxLat + bufferDeg
  const west = minLng
  const east = maxLng

  // GeoJSON polygon ring (counter-clockwise exterior)
  const ring: [number, number][] = [
    [west, south],
    [east, south],
    [east, north],
    [west, north],
    [west, south], // close ring
  ]

  return [ring]
}

/** Compute the centroid of a GeoJSON polygon (first ring). Returns { lng, lat }. */
export function computeCenter(
  polygon: [number, number][][]
): { lng: number; lat: number } {
  const ring = polygon[0]
  // Exclude closing point (last == first)
  const count = ring.length > 1 && ring[0][0] === ring[ring.length - 1][0] && ring[0][1] === ring[ring.length - 1][1]
    ? ring.length - 1
    : ring.length

  let lngSum = 0, latSum = 0
  for (let i = 0; i < count; i++) {
    lngSum += ring[i][0]
    latSum += ring[i][1]
  }
  return { lng: lngSum / count, lat: latSum / count }
}

/**
 * Ray-casting point-in-polygon test.
 * Polygon points are in [lat, lng] format (matching chicago-geo-data.ts convention).
 */
export function pointInPolygon(
  lat: number,
  lng: number,
  polygon: [number, number][]
): boolean {
  let inside = false
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [yi, xi] = polygon[i]
    const [yj, xj] = polygon[j]
    if (
      yi > lat !== yj > lat &&
      lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi
    ) {
      inside = !inside
    }
  }
  return inside
}
