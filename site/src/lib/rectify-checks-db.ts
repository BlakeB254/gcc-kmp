/**
 * DB reads for the rectification bonus rules (lib/rectify-checks.ts). Takes
 * the executor so tests can use the recording db.
 */
import { and, asc, desc, eq, gte, lte, sql } from 'drizzle-orm'
import { communityReports, routeScans, scanCoverageEvents, scanHotspots, worldObservations } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'
import { cellCenter } from '@/lib/grid-cell-client'
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'
import { MAX_FUTURE_SKEW_MS } from '@/lib/gps-plausibility'
import {
  RECTIFY_MAX_REACH_METERS,
  RECTIFY_POSITION_WINDOW_MS,
  type DirtyObservation,
  type RecordedFix,
} from '@/lib/rectify-checks'

/** Upper bound on rows read per check; a cell holds a handful of items. */
const ROW_LIMIT = 20

/**
 * Current dirty observations in the cell, each with its reporter: the
 * stamped reporter_worker_id, else (legacy rows) the worker of the route
 * scan its hotspot came from, else the worker who filed its community
 * report. Null when none of those is known. Ordered so reports by someone
 * other than the rectifier come first (then newest), so the row limit can
 * never hide the report that qualifies.
 */
export async function loadDirtyObservations(
  exec: DbExecutor,
  cellId: string,
  rectifierId: number,
): Promise<DirtyObservation[]> {
  const reporter = sql<number | null>`coalesce(
    ${worldObservations.reporterWorkerId},
    ${routeScans.workerId},
    case when ${communityReports.reporterType} = 'worker' then ${communityReports.reporterId} end
  )`
  const rows = await exec
    .select({ id: worldObservations.id, reporterWorkerId: reporter })
    .from(worldObservations)
    .leftJoin(scanHotspots, eq(scanHotspots.id, worldObservations.scanHotspotId))
    .leftJoin(routeScans, eq(routeScans.id, scanHotspots.scanId))
    .leftJoin(communityReports, eq(communityReports.id, worldObservations.communityReportId))
    .where(
      and(
        eq(worldObservations.locationCellId, cellId),
        eq(worldObservations.state, 'dirty'),
        eq(worldObservations.isCurrent, true),
      ),
    )
    .orderBy(asc(sql`(${reporter}) is not distinct from ${rectifierId}::int`), desc(worldObservations.capturedAt), desc(worldObservations.id))
    .limit(ROW_LIMIT)
  return rows.map((r) => ({ id: r.id, reporterWorkerId: r.reporterWorkerId == null ? null : Number(r.reporterWorkerId) }))
}

function rowsOf(res: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(res)) return res as Array<Record<string, unknown>>
  const rows = (res as { rows?: unknown[] } | null)?.rows
  return Array.isArray(rows) ? (rows as Array<Record<string, unknown>>) : []
}

/**
 * Atomically takes the dirty observation the bonus is for AND resolves the
 * community report (311 / spot pin) behind it, in ONE statement (neon-http
 * runs each statement as its own transaction):
 *   - the UPDATE only succeeds while the row is still current, so of two
 *     concurrent rectify requests only one gets the row back and is paid;
 *   - the claimed row is no longer current, so recordWorldObservation's
 *     supersede pass (which only sees current rows) would never resolve its
 *     report — the second CTE does it here, with the same rule as
 *     world-observations.ts resolveSupersededReports (pending/verified →
 *     resolved, off the map; idempotent).
 */
export async function claimDirtyObservation(exec: DbExecutor, observationId: number): Promise<boolean> {
  const res = await exec.execute(sql`
    with claimed as (
      update world_observations set is_current = false
      where id = ${observationId}::int and state = 'dirty' and is_current = true
      returning id, community_report_id
    ), resolved as (
      update community_reports set status = 'resolved', show_on_map = false
      where id in (select community_report_id from claimed where community_report_id is not null)
        and status in ('pending', 'verified')
      returning id
    )
    select (select count(*) from claimed)::int as claimed, (select count(*) from resolved)::int as resolved
  `)
  return Number(rowsOf(res)[0]?.claimed ?? 0) > 0
}

function toFix(r: { lat: unknown; lng: unknown; capturedAt: unknown }): RecordedFix | null {
  const lat = Number(r.lat)
  const lng = Number(r.lng)
  const capturedAtMs = Date.parse(String(r.capturedAt))
  return Number.isFinite(lat) && Number.isFinite(lng) && !Number.isNaN(capturedAtMs) ? { lat, lng, capturedAtMs } : null
}

/**
 * The rectifier's recorded fixes near the cell (inside the largest reach any
 * fix can get) from the last RECTIFY_POSITION_WINDOW_MS, newest first. The
 * time window and the bounding box are both in SQL, so a busy stream cannot
 * push the qualifying fix out of a row limit.
 */
export async function loadRecentFixes(exec: DbExecutor, workerId: number, cellId: string, now: string): Promise<RecordedFix[]> {
  const nowMs = Date.parse(now)
  const since = new Date(nowMs - RECTIFY_POSITION_WINDOW_MS).toISOString()
  const until = new Date(nowMs + MAX_FUTURE_SKEW_MS).toISOString()
  const c = cellCenter(cellId)
  const dLat = RECTIFY_MAX_REACH_METERS / METERS_PER_DEG_LAT
  const dLng = RECTIFY_MAX_REACH_METERS / METERS_PER_DEG_LNG
  const rows = await exec
    .select({ lat: scanCoverageEvents.lat, lng: scanCoverageEvents.lng, capturedAt: scanCoverageEvents.capturedAt })
    .from(scanCoverageEvents)
    .where(
      and(
        eq(scanCoverageEvents.workerId, workerId),
        gte(scanCoverageEvents.capturedAt, since),
        lte(scanCoverageEvents.capturedAt, until),
        sql`${scanCoverageEvents.lat} between ${c.lat - dLat} and ${c.lat + dLat}`,
        sql`${scanCoverageEvents.lng} between ${c.lng - dLng} and ${c.lng + dLng}`,
      ),
    )
    .orderBy(desc(scanCoverageEvents.capturedAt))
    .limit(50)
  return rows.map(toFix).filter((f): f is RecordedFix => f != null)
}

/** The worker's newest recorded fix in the window, anywhere — to judge a request-carried fix. */
export async function loadLatestFix(exec: DbExecutor, workerId: number, now: string): Promise<RecordedFix | null> {
  const nowMs = Date.parse(now)
  const [row] = await exec
    .select({ lat: scanCoverageEvents.lat, lng: scanCoverageEvents.lng, capturedAt: scanCoverageEvents.capturedAt })
    .from(scanCoverageEvents)
    .where(
      and(
        eq(scanCoverageEvents.workerId, workerId),
        gte(scanCoverageEvents.capturedAt, new Date(nowMs - RECTIFY_POSITION_WINDOW_MS).toISOString()),
        lte(scanCoverageEvents.capturedAt, new Date(nowMs + MAX_FUTURE_SKEW_MS).toISOString()),
      ),
    )
    .orderBy(desc(scanCoverageEvents.capturedAt))
    .limit(1)
  return row ? toFix(row) : null
}
