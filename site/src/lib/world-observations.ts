import { db } from '@/db'
import { worldObservations, blockExclusionZones } from '@/db/schema'
import { eq, and } from 'drizzle-orm'
import { pointInPolygon, METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'

const CELL_SIZE_METERS = 10

/**
 * Deterministic ~10m grid cell key for a lat/lng, used to group observations
 * of "the same physical spot" across time and across source type.
 */
export function gridCell(lat: number, lng: number, cellSizeMeters = CELL_SIZE_METERS): string {
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  const latIdx = Math.floor(lat / latStep)
  const lngIdx = Math.floor(lng / lngStep)
  return `${latIdx}:${lngIdx}`
}

export interface RecordObservationInput {
  sourceType: 'route_scan' | 'spot_report'
  scanHotspotId?: number
  communityReportId?: number
  lat: number
  lng: number
  itemType: string | null
  state: 'dirty' | 'clean'
  severity?: number
  estimatedVolumeLiters?: number
  estimatedWeightLbs?: number
  confidence?: number
  mediaUrl?: string
  depthData?: string
  arAnchorData?: string
  capturedAt: string
  blockId?: number
}

/**
 * Record a new observation, superseding whatever was previously "current"
 * at the same physical spot. Nothing is ever deleted or updated in place
 * except the isCurrent flag — every insert is a new immutable row, so the
 * full chronological history (dirty -> clean -> dirty) survives per cell.
 */
export async function recordWorldObservation(input: RecordObservationInput) {
  const cellId = gridCell(input.lat, input.lng)

  const excluded = input.blockId != null
    ? await isWithinExclusionZone(input.blockId, input.lat, input.lng)
    : false

  if (input.state === 'clean') {
    // A clean re-scan supersedes ALL currently-dirty items in this cell,
    // regardless of their itemType — a clean scan means "nothing dirty here now."
    await db
      .update(worldObservations)
      .set({ isCurrent: false })
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        eq(worldObservations.state, 'dirty'),
        eq(worldObservations.isCurrent, true)
      ))
  } else {
    // A new dirty observation of the same itemType in the same cell supersedes
    // only the prior observation of that itemType (a couch and glass 1m apart
    // in the same cell are both allowed to be concurrently "current").
    await db
      .update(worldObservations)
      .set({ isCurrent: false })
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        eq(worldObservations.itemType, input.itemType ?? ''),
        eq(worldObservations.isCurrent, true)
      ))
  }

  const [row] = await db
    .insert(worldObservations)
    .values({
      scanHotspotId: input.scanHotspotId ?? null,
      communityReportId: input.communityReportId ?? null,
      sourceType: input.sourceType,
      lat: input.lat,
      lng: input.lng,
      locationCellId: cellId,
      itemType: input.itemType,
      state: input.state,
      severity: input.severity ?? null,
      estimatedVolumeLiters: input.estimatedVolumeLiters ?? null,
      estimatedWeightLbs: input.estimatedWeightLbs ?? null,
      confidence: input.confidence ?? null,
      mediaUrl: input.mediaUrl ?? null,
      depthData: input.depthData ?? null,
      arAnchorData: input.arAnchorData ?? null,
      capturedAt: input.capturedAt,
      isCurrent: true,
      excludedPrivateProperty: excluded,
    })
    .returning()

  return row
}

/**
 * Placeholder until Task 9 adds blockExclusionZones — returns false (no
 * exclusion zones exist yet). Task 9 replaces this implementation in place.
 */
async function isWithinExclusionZone(blockId: number, lat: number, lng: number): Promise<boolean> {
  const zones = await db
    .select()
    .from(blockExclusionZones)
    .where(eq(blockExclusionZones.blockId, blockId))
  for (const zone of zones) {
    const ring = (zone.polygon as any)?.coordinates?.[0] as [number, number][] | undefined
    if (ring && pointInPolygon(lat, lng, ring.map(([lng2, lat2]) => [lat2, lng2] as [number, number]))) {
      return true
    }
  }
  return false
}
