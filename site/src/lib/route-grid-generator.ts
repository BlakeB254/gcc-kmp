/**
 * Generate a grid of route/block proposals for a Chicago community area.
 *
 * Algorithm:
 * 1. Look up community area polygon + bounds
 * 2. Generate grid rows (north→south, spaced blockHeight apart)
 * 3. Each row generates cells (west→east, blockWidth wide)
 * 4. Point-in-polygon filters cells to those inside the community area
 * 5. Skip cells that overlap existing routes
 * 6. Assign sequential codes per side (N/S relative to area midpoint)
 */

import { COMMUNITY_AREAS } from './chicago-geo-data'

export const PROPOSAL_ID_PREFIX = 'proposal-'
import {
  METERS_PER_DEG_LAT,
  METERS_PER_DEG_LNG,
  haversineDistance,
  estimateWalkingMinutes,
  lineStringToBlockPolygon,
  computeCenter,
  pointInPolygon,
} from './geo-utils'
import { deriveAreaPrefix, generateNextCode } from './route-code-generator'

export interface RouteProposal {
  id: string
  routeCode: string
  blockCode: string
  displayName: string
  geometry: { type: 'LineString'; coordinates: [number, number][] }
  blockPolygon: [number, number][][]
  center: { lng: number; lat: number }
  distanceMeters: number
  estimatedMinutes: number
  side: 'north' | 'south'
  row: number
}

interface GenerateOptions {
  communityAreaNumber: number
  blockWidth?: number   // meters, default 130
  blockHeight?: number  // meters, default 200
  existingCodes?: string[]
  existingGeometries?: { type: string; coordinates: [number, number][] }[]
}

/** Check if a new route center is too close to any existing route center */
function overlapsExisting(
  lng: number,
  lat: number,
  existingCenters: [number, number][],
  minDistanceM = 50
): boolean {
  for (const [eLng, eLat] of existingCenters) {
    const dLat = (lat - eLat) * METERS_PER_DEG_LAT
    const dLng = (lng - eLng) * METERS_PER_DEG_LNG
    const dist = Math.sqrt(dLat * dLat + dLng * dLng)
    if (dist < minDistanceM) return true
  }
  return false
}

/** Compute the center of existing route geometries */
function extractCenters(
  geometries: { type: string; coordinates: [number, number][] }[]
): [number, number][] {
  return geometries.map((g) => {
    const coords = g.coordinates
    if (coords.length === 0) return [0, 0] as [number, number]
    let lngSum = 0, latSum = 0
    for (const [lng, lat] of coords) {
      lngSum += lng
      latSum += lat
    }
    return [lngSum / coords.length, latSum / coords.length] as [number, number]
  })
}

export function generateRouteGrid(options: GenerateOptions): RouteProposal[] {
  const {
    communityAreaNumber,
    blockWidth = 130,
    blockHeight = 200,
    existingCodes = [],
    existingGeometries = [],
  } = options

  const area = COMMUNITY_AREAS.find((a) => a.number === communityAreaNumber)
  if (!area) {
    throw new Error(`Community area ${communityAreaNumber} not found`)
  }

  const { bounds, polygon } = area
  const existingCenters = extractCenters(existingGeometries)
  const prefix = deriveAreaPrefix(area.displayName)

  // Convert spacing to degrees
  const rowStepLat = blockHeight / METERS_PER_DEG_LAT
  const colStepLng = blockWidth / METERS_PER_DEG_LNG

  // Midpoint latitude determines north/south side
  const midLat = (bounds.north + bounds.south) / 2

  // Collect all cells, grouped by side
  const northCells: { lng: number; lat: number; row: number }[] = []
  const southCells: { lng: number; lat: number; row: number }[] = []

  let rowIndex = 0
  // Iterate rows from north to south
  for (let lat = bounds.north - rowStepLat / 2; lat > bounds.south; lat -= rowStepLat) {
    // Iterate columns from west to east
    // Chicago longitudes are negative; west is more negative, east is less negative
    for (let lng = bounds.west + colStepLng / 2; lng < bounds.east; lng += colStepLng) {
      // Point-in-polygon test (polygon uses [lat, lng] format)
      if (!pointInPolygon(lat, lng, polygon)) continue

      // Skip if too close to existing route
      if (overlapsExisting(lng, lat, existingCenters)) continue

      const cell = { lng, lat, row: rowIndex }
      if (lat >= midLat) {
        northCells.push(cell)
      } else {
        southCells.push(cell)
      }
    }
    rowIndex++
  }

  // Generate codes and proposals
  const proposals: RouteProposal[] = []
  const allCodes = [...existingCodes]
  let idCounter = 0

  function processCells(
    cells: { lng: number; lat: number; row: number }[],
    side: 'north' | 'south'
  ) {
    for (const cell of cells) {
      const { routeCode, blockCode } = generateNextCode(allCodes, prefix, side)
      allCodes.push(routeCode, blockCode)

      // Create a horizontal LineString spanning blockWidth centered on cell
      const halfWidth = colStepLng / 2
      const lineCoords: [number, number][] = [
        [cell.lng - halfWidth, cell.lat],
        [cell.lng + halfWidth, cell.lat],
      ]

      const geometry = {
        type: 'LineString' as const,
        coordinates: lineCoords,
      }

      const blockPolygon = lineStringToBlockPolygon(lineCoords, blockHeight / 2)
      const center = computeCenter(blockPolygon)
      const distanceMeters = haversineDistance(lineCoords)
      const estimatedMinutes = estimateWalkingMinutes(distanceMeters)

      proposals.push({
        id: `${PROPOSAL_ID_PREFIX}${idCounter++}`,
        routeCode,
        blockCode,
        displayName: `${area!.displayName} ${routeCode}`,
        geometry,
        blockPolygon,
        center,
        distanceMeters: Math.round(distanceMeters),
        estimatedMinutes,
        side,
        row: cell.row,
      })
    }
  }

  processCells(northCells, 'north')
  processCells(southCells, 'south')

  return proposals
}
