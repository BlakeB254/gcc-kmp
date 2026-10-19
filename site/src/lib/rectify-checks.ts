/**
 * Anti-farming rules for the rectification bonus (POST
 * /api/observations/rectify and POST /api/scans/{id}/rectify).
 *
 * Before this, the bonus paid whenever ANY current dirty observation existed
 * in the 10 m cell named by the request's lat/lng — so a worker could report
 * litter, then "rectify" it from anywhere, or rectify any dirty cell in the
 * city without going there. Now the bonus needs:
 *   1. a current dirty observation in the cell reported by SOMEONE ELSE (an
 *      observation with no known reporter — legacy rows, 311 imports — counts
 *      as someone else); and
 *   2. evidence the rectifier is at the cell: either the fix the request
 *      itself carries (accepted only when it is fresh and plausible against
 *      the worker's recorded trajectory), or a recorded coverage fix whose
 *      allowed reach grows with its age — the app flushes fixes every 30 s,
 *      so the newest recorded one trails a walking worker.
 * The clean observation is still recorded either way; only the bonus is
 * withheld, and the response says why (`bonusWithheld`).
 *
 * Pure module — no `@/db` import. DB reads are in rectify-checks-db.ts.
 */
import { cellCenter } from '@/lib/grid-cell-client'
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'
import { MAX_FUTURE_SKEW_MS, MAX_PLAUSIBLE_SPEED_MPS, fixDistanceMeters, type GpsFix } from '@/lib/gps-plausibility'

/** Half the 10 m gridCell (world-observations.ts CELL_SIZE_METERS). */
const CELL_HALF_METERS = 5

/**
 * Reach beyond the cell for a fix taken right now, metres: the client prompts
 * at 5 m from the marker (RouteScanViewModel RECTIFY_PROXIMITY_METERS) and
 * phone GPS on a street is off by 5–10 m; 30 m covers both with margin.
 */
export const RECTIFY_POSITION_BUFFER_METERS = 30

/** Walking speed used to grow an older fix's reach (brisk walk, m/s). */
export const RECTIFY_WALK_SPEED_MPS = 1.6

/**
 * Age up to which a recorded fix's reach keeps growing, seconds: one 30 s
 * coverage flush plus 15 s of upload lag. Past it the reach stops growing, so
 * an old fix never vouches for a position far away (max reach 30 + 72 m).
 */
export const RECTIFY_MAX_TRAIL_SECONDS = 45

/**
 * How far back recorded fixes are read. Fixes can lag on a weak connection;
 * 15 minutes keeps a worker who paused at the spot eligible.
 */
export const RECTIFY_POSITION_WINDOW_MS = 15 * 60 * 1000

/**
 * A request-carried fix must be at most this old to be trusted as "now" — and
 * so must the recorded fix it is checked against.
 */
export const RECTIFY_REQUEST_FIX_MAX_AGE_MS = 2 * 60 * 1000

/** Distance a request fix may be from the recorded fix regardless of time (GPS error both ways), metres. */
export const RECTIFY_REQUEST_FIX_SLACK_METERS = 30

/** The largest reach any fix can get — used to bound the DB read. */
export const RECTIFY_MAX_REACH_METERS =
  CELL_HALF_METERS + RECTIFY_POSITION_BUFFER_METERS + RECTIFY_WALK_SPEED_MPS * RECTIFY_MAX_TRAIL_SECONDS

export type DirtyObservation = { id: number; reporterWorkerId: number | null }
export type RecordedFix = GpsFix

export type RectifyWithheld = 'no_dirty_observation' | 'own_report' | 'not_at_location'
export type RectifyDecision = { award: true; observationId: number } | { award: false; reason: RectifyWithheld }

/** Reach beyond the cell centre a fix of this age gets, metres. */
export function reachForAge(ageMs: number): number {
  const ageS = Math.min(Math.max(ageMs, 0) / 1000, RECTIFY_MAX_TRAIL_SECONDS)
  return CELL_HALF_METERS + RECTIFY_POSITION_BUFFER_METERS + RECTIFY_WALK_SPEED_MPS * ageS
}

/** Whether a fix taken `ageMs` before now puts the worker at the cell. */
export function fixWithinCell(fix: { lat: number; lng: number }, cellId: string, ageMs = 0): boolean {
  const center = cellCenter(cellId)
  if (!Number.isFinite(center.lat) || !Number.isFinite(center.lng)) return false
  const reach = reachForAge(ageMs)
  return (
    Math.abs(fix.lat - center.lat) * METERS_PER_DEG_LAT <= reach &&
    Math.abs(fix.lng - center.lng) * METERS_PER_DEG_LNG <= reach
  )
}

/** Parses the optional `fix: {lat, lng, capturedAt}` a rectify request carries. */
export function parseRequestFix(value: unknown): RecordedFix | null {
  if (!value || typeof value !== 'object') return null
  const v = value as { lat?: unknown; lng?: unknown; capturedAt?: unknown }
  const lat = Number(v.lat)
  const lng = Number(v.lng)
  const t = typeof v.capturedAt === 'string' ? Date.parse(v.capturedAt) : NaN
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Number.isNaN(t)) return null
  return { lat, lng, capturedAtMs: t }
}

/**
 * Whether the request's own fix can stand for "where the worker is now". It
 * must be fresh (≤ 2 min old, not from the future); the worker's newest
 * recorded fix must also be ≤ 2 min old; and the two must be within
 * 8 m/s × |Δt| + 30 m of each other. Distance is always checked — a request
 * that copies a recorded fix's timestamp (Δt = 0) must also be within 30 m of
 * it. Without a recent recorded fix to check against, a bare client claim is
 * not accepted.
 */
export function requestFixPlausible(fix: RecordedFix, latestRecorded: RecordedFix | null, nowMs: number): boolean {
  if (fix.capturedAtMs > nowMs + MAX_FUTURE_SKEW_MS) return false
  if (nowMs - fix.capturedAtMs > RECTIFY_REQUEST_FIX_MAX_AGE_MS) return false
  if (!latestRecorded) return false
  if (nowMs - latestRecorded.capturedAtMs > RECTIFY_REQUEST_FIX_MAX_AGE_MS) return false
  const dtS = Math.abs(fix.capturedAtMs - latestRecorded.capturedAtMs) / 1000
  return fixDistanceMeters(latestRecorded, fix) <= MAX_PLAUSIBLE_SPEED_MPS * dtS + RECTIFY_REQUEST_FIX_SLACK_METERS
}

export function decideRectification(input: {
  cellId: string
  rectifierId: number
  dirty: DirtyObservation[]
  /** Recorded fixes near the cell from the window, newest first. */
  recentFixes: RecordedFix[]
  /** The worker's newest recorded fix anywhere (for the request-fix check). */
  latestRecorded: RecordedFix | null
  requestFix: RecordedFix | null
  nowMs: number
}): RectifyDecision {
  const { cellId, rectifierId, dirty, recentFixes, latestRecorded, requestFix, nowMs } = input
  if (dirty.length === 0) return { award: false, reason: 'no_dirty_observation' }
  const others = dirty.filter((o) => o.reporterWorkerId == null || o.reporterWorkerId !== rectifierId)
  if (others.length === 0) return { award: false, reason: 'own_report' }
  const fromRequest =
    requestFix != null && requestFixPlausible(requestFix, latestRecorded, nowMs) && fixWithinCell(requestFix, cellId, 0)
  const fromRecorded = recentFixes.some((fix) => fixWithinCell(fix, cellId, nowMs - fix.capturedAtMs))
  if (!fromRequest && !fromRecorded) return { award: false, reason: 'not_at_location' }
  return { award: true, observationId: others[0].id }
}
