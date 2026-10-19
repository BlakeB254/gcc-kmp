/**
 * Server-side checks on route-scan hotspots (POST /api/scans/{id}/hotspots,
 * PATCH …/hotspots/{hid}) and the scan points they feed (POST
 * /api/scans/{id}/complete).
 *
 * Before this, a hotspot's volume, position and verification score were
 * whatever the client sent, and the completion bonus added one point per
 * claimed litre — so one hotspot claiming 1e6 litres paid 1e6 points.
 *
 * No `@/db` import — so `tsx --test` loads it without a DB.
 */
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'
import { routeLines } from '@/lib/scan-verification'
import { isOwnEvidenceUrl } from '@/lib/job-lifecycle'

/**
 * Largest volume one hotspot may count, litres. 200 L is one 55-gallon
 * (208 L) contractor bag — the most a single worker bags from one pile on a
 * walked route; anything bigger is a dumping site, which is its own job, not
 * a route hotspot. The client estimates volume from depth
 * (RouteScanScreen: estimatedVolumeCubicFt × 28.3), which can overshoot
 * wildly on a bad depth map, so the cap also bounds honest errors.
 */
export const MAX_HOTSPOT_VOLUME_LITERS = 200

/**
 * How far a hotspot may sit from its route line, metres: the client corridor
 * half-width (15 m, CoverageTracker.corridorWidthMeters) + consumer GPS
 * error on a street (≈10 m) + reach to litter photographed just off the
 * sidewalk, in a tree lawn or alley mouth (≈15 m). A hotspot further out is
 * not on the block the scan is for.
 */
export const HOTSPOT_ROUTE_BUFFER_METERS = 40

/** Flat completion bonus, per-cleaned-hotspot bonus (unchanged values). */
export const SCAN_COMPLETION_BASE_POINTS = 50
export const POINTS_PER_CLEANED_HOTSPOT = 10

/**
 * The volume the server will count: null when the client sent none (or
 * nonsense), never negative, never above MAX_HOTSPOT_VOLUME_LITERS.
 */
export function capHotspotVolume(value: unknown): number | null {
  if (value == null || value === '') return null
  const n = Number(value)
  if (!Number.isFinite(n)) return null
  return Math.min(Math.max(n, 0), MAX_HOTSPOT_VOLUME_LITERS)
}

/** Client verification score kept as a claim: a number in [0, 1], else null. */
export function clientVerificationScore(value: unknown): number | null {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? Math.min(Math.max(n, 0), 1) : null
}

function pointSegmentMeters(
  px: number, py: number, ax: number, ay: number, bx: number, by: number,
): number {
  const dx = bx - ax
  const dy = by - ay
  const len2 = dx * dx + dy * dy
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2))
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/**
 * Shortest distance from a point to the route's lines, metres, on the frozen
 * planar projection (docs/CONVENTIONS.md §2). Null when the geometry has no
 * lines — the caller cannot check, and says so.
 */
export function distanceToRouteMeters(lat: number, lng: number, geometry: unknown): number | null {
  const lines = routeLines(geometry)
  if (lines.length === 0) return null
  // Project around the point so the numbers stay small.
  const proj = ([pLng, pLat]: [number, number]): [number, number] => [
    (pLng - lng) * METERS_PER_DEG_LNG,
    (pLat - lat) * METERS_PER_DEG_LAT,
  ]
  let best = Infinity
  for (const line of lines) {
    if (line.length === 1) {
      const [x, y] = proj(line[0])
      best = Math.min(best, Math.hypot(x, y))
      continue
    }
    for (let i = 1; i < line.length; i++) {
      const [ax, ay] = proj(line[i - 1])
      const [bx, by] = proj(line[i])
      best = Math.min(best, pointSegmentMeters(0, 0, ax, ay, bx, by))
    }
  }
  return best
}

/**
 * A hotspot photo must be an upload through /api/spots/upload by the person
 * walking the scan — the scan's worker, or the admin performing it (uploads
 * land under the uploader's own spots/{id}/ prefix).
 */
export function isScanPhotoUrl(url: string, scan: { workerId: number }, callerId: number): boolean {
  return isOwnEvidenceUrl(url, callerId) || isOwnEvidenceUrl(url, scan.workerId)
}

export type HotspotPlacement = 'ok' | 'off-route' | 'no-geometry'

export function hotspotPlacement(lat: number, lng: number, geometry: unknown): HotspotPlacement {
  const d = distanceToRouteMeters(lat, lng, geometry)
  if (d == null) return 'no-geometry'
  return d <= HOTSPOT_ROUTE_BUFFER_METERS ? 'ok' : 'off-route'
}

export type HotspotForPoints = {
  status: string
  estimatedVolumeLiters: number | null
  beforePhotoUrl: string | null
  afterPhotoUrl: string | null
}

/**
 * A cleaned hotspot pays only when the server can see the work: it has an
 * after photo (origin-checked when it was set) that is not the before photo.
 * The client's verificationScore is not an input.
 */
export function hotspotCountsAsCleaned(h: HotspotForPoints): boolean {
  return h.status === 'cleaned' && !!h.afterPhotoUrl && h.afterPhotoUrl !== h.beforePhotoUrl
}

/**
 * Scan completion points: base + per cleaned hotspot + one point per litre,
 * from capped volumes only (legacy rows stored uncapped values, so the cap
 * is re-applied here).
 */
export function scanCompletionPoints(hotspots: HotspotForPoints[]): number {
  const cleaned = hotspots.filter(hotspotCountsAsCleaned)
  const volume = cleaned.reduce((sum, h) => sum + (capHotspotVolume(h.estimatedVolumeLiters) ?? 0), 0)
  return SCAN_COMPLETION_BASE_POINTS + cleaned.length * POINTS_PER_CLEANED_HOTSPOT + Math.floor(volume)
}
