/**
 * DB reads/writes for the coverage GPS plausibility filter. Rules live in
 * lib/gps-plausibility.ts (pure); this file only fetches and applies. Takes
 * the executor so tests can drive it with the recording pg-proxy db.
 */
import { and, desc, eq, inArray, sql } from 'drizzle-orm'
import { routeScans, scanCoverageEvents } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'
import {
  GPS_DROP_REASONS,
  GPS_SUSPECT_DROPPED_SHARE,
  GPS_SUSPECT_MIN_DROPPED,
  type GpsFix,
  type GpsStatsDelta,
} from '@/lib/gps-plausibility'

/**
 * The device's most recent recorded fix — where its trajectory continues
 * from in the next batch. Null when the device has none (or the row is not
 * a usable fix).
 */
export async function loadDeviceAnchor(
  exec: DbExecutor,
  workerId: number,
  deviceId: string,
): Promise<GpsFix | null> {
  const [row] = await exec
    .select({ lat: scanCoverageEvents.lat, lng: scanCoverageEvents.lng, capturedAt: scanCoverageEvents.capturedAt })
    .from(scanCoverageEvents)
    .where(and(eq(scanCoverageEvents.workerId, workerId), eq(scanCoverageEvents.deviceId, deviceId)))
    .orderBy(desc(scanCoverageEvents.capturedAt))
    .limit(1)
  if (!row) return null
  const lat = Number(row.lat)
  const lng = Number(row.lng)
  const capturedAtMs = Date.parse(String(row.capturedAt))
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Number.isNaN(capturedAtMs)) return null
  return { lat, lng, capturedAtMs }
}

/**
 * Which of these capture times this device already has on record (ms
 * precision, as epoch ms). Used for fixes OLDER than the stored anchor — a
 * replay of history — so rows written before 0053 (no dedupe_key yet) are
 * refused too. Concurrent duplicates of new fixes are caught by the unique
 * dedupe_key; this read only has to see rows that are already committed.
 */
export async function loadRecordedCaptureTimes(
  exec: DbExecutor,
  workerId: number,
  deviceId: string,
  capturedAt: string[],
): Promise<Set<number>> {
  if (capturedAt.length === 0) return new Set()
  const rows = await exec
    .select({ capturedAt: scanCoverageEvents.capturedAt })
    .from(scanCoverageEvents)
    .where(
      and(
        eq(scanCoverageEvents.workerId, workerId),
        eq(scanCoverageEvents.deviceId, deviceId),
        inArray(scanCoverageEvents.capturedAt, capturedAt),
      ),
    )
  return new Set(rows.map((r) => Date.parse(String(r.capturedAt))).filter((t) => !Number.isNaN(t)))
}

/**
 * Adds one batch's kept/dropped counts to the scan's running totals and
 * re-derives the suspect flag from the new totals, in one UPDATE so
 * concurrent batches cannot lose counts. Once suspect, a scan stays suspect.
 */
export async function recordScanGpsStats(exec: DbExecutor, scanId: number, delta: GpsStatsDelta): Promise<void> {
  if (delta.kept === 0 && delta.dropped === 0) return
  const reasonPairs = GPS_DROP_REASONS.map(
    (reason) =>
      sql`${reason}::text, coalesce((${routeScans.gpsDropReasons}->>${reason})::int, 0) + ${delta.reasons[reason] ?? 0}::int`,
  )
  const newKept = sql`${routeScans.gpsPointsKept} + ${delta.kept}::int`
  const newDropped = sql`${routeScans.gpsPointsDropped} + ${delta.dropped}::int`
  await exec
    .update(routeScans)
    .set({
      gpsPointsKept: sql`${newKept}`,
      gpsPointsDropped: sql`${newDropped}`,
      gpsDropReasons: sql`jsonb_build_object(${sql.join(reasonPairs, sql`, `)})`,
      gpsSuspect: sql`${routeScans.gpsSuspect} OR (
        ${newDropped} >= ${GPS_SUSPECT_MIN_DROPPED}::int
        AND (${newDropped})::float8 / greatest(${newKept} + ${newDropped}, 1) >= ${GPS_SUSPECT_DROPPED_SHARE}::float8
      )`,
    })
    .where(eq(routeScans.id, scanId))
}
