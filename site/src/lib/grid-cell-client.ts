/**
 * Client-safe duplicate of the grid-cell math in world-observations.ts.
 * That file imports the Neon DB client at module scope, which must never
 * end up in a browser bundle — so this file re-implements just the pure
 * math, kept byte-identical in output to the server-side gridCell/cellCenter
 * so the map overlay and the backend agree on cell boundaries.
 */
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'

const CELL_SIZE_METERS = 10

export function gridCell(lat: number, lng: number, cellSizeMeters = CELL_SIZE_METERS): string {
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  const latIdx = Math.floor(lat / latStep)
  const lngIdx = Math.floor(lng / lngStep)
  return `${latIdx}:${lngIdx}`
}

export function cellCenter(cellId: string, cellSizeMeters = CELL_SIZE_METERS): { lat: number; lng: number } {
  const [latIdxStr, lngIdxStr] = cellId.split(':')
  const latIdx = Number(latIdxStr)
  const lngIdx = Number(lngIdxStr)
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  return {
    lat: (latIdx + 0.5) * latStep,
    lng: (lngIdx + 0.5) * lngStep,
  }
}

/**
 * Every cell id whose center falls within the given viewport bounds.
 * Restricted to close-zoom callers only (see Task 2) — at wide zoom this
 * would enumerate thousands of cells; the caller is responsible for that
 * gate, this function has no zoom awareness of its own.
 */
export function enumerateCellsInBounds(
  bounds: { west: number; south: number; east: number; north: number },
  cellSizeMeters = CELL_SIZE_METERS
): string[] {
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  const cells: string[] = []
  const minLatIdx = Math.floor(bounds.south / latStep)
  const maxLatIdx = Math.floor(bounds.north / latStep)
  const minLngIdx = Math.floor(bounds.west / lngStep)
  const maxLngIdx = Math.floor(bounds.east / lngStep)

  // Safety cap: if a caller passes overly wide bounds despite the zoom gate,
  // don't hang enumerating tens of thousands of cells.
  const cellCount = (maxLatIdx - minLatIdx + 1) * (maxLngIdx - minLngIdx + 1)
  if (cellCount > 5000) {
    console.warn('enumerateCellsInBounds: bounds too wide, returning empty (expected close-zoom caller)')
    return []
  }

  for (let latIdx = minLatIdx; latIdx <= maxLatIdx; latIdx++) {
    for (let lngIdx = minLngIdx; lngIdx <= maxLngIdx; lngIdx++) {
      cells.push(`${latIdx}:${lngIdx}`)
    }
  }
  return cells
}
