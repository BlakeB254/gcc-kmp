import { db } from '@/db'
import { scanCoverageEvents, cellCoverageSummary, pointsLedger } from '@/db/schema'
import { gridCell } from '@/lib/world-observations'
import { sql, eq } from 'drizzle-orm'
import { POINTS_PER_METER, STALE_AFTER_MS } from '@/lib/points-config'

export interface RecordCoverageEventInput {
  deviceId: string
  platform: 'ios' | 'android'
  workerId?: number
  lat: number
  lng: number
  distanceMeters: number
  scanMode: 'route' | 'spot'
  deviceTier: 'basic' | 'scanner' | 'pro_scanner'
  capturedAt: string
}

type CoverageCategory = 'new_area' | 'stale_rescan' | 'fresh_rescan'

/**
 * Classifies a coverage event against the cell's PRIOR state (read before
 * any write this call makes) — 'new_area' if the cell has never been
 * scanned, 'stale_rescan' if the last scan was more than 24h ago,
 * 'fresh_rescan' otherwise.
 */
function categorizeCoverageEvent(
  priorLastScannedAt: string | null,
  capturedAt: string
): CoverageCategory {
  if (priorLastScannedAt === null) return 'new_area'
  const ageMs = new Date(capturedAt).getTime() - new Date(priorLastScannedAt).getTime()
  return ageMs > STALE_AFTER_MS ? 'stale_rescan' : 'fresh_rescan'
}

/**
 * Records one coverage event (append-only) and upserts the per-cell rollup
 * summary used for cheap reads (map tile freshness, points calculation,
 * AR guidance). The event log is the source of truth; the summary is a
 * derived cache maintained on every write, never the other way around.
 *
 * Also computes and awards distance-tracked points (design spec §5) —
 * skipped when the event has no workerId, since pointsLedger.workerId is
 * NOT NULL and an unauthenticated/deviceId-only event can't earn points.
 */
export async function recordCoverageEvent(input: RecordCoverageEventInput) {
  const cellId = gridCell(input.lat, input.lng)

  const [priorSummary] = await db
    .select({ lastScannedAt: cellCoverageSummary.lastScannedAt })
    .from(cellCoverageSummary)
    .where(eq(cellCoverageSummary.locationCellId, cellId))
    .limit(1)

  const category = categorizeCoverageEvent(priorSummary?.lastScannedAt ?? null, input.capturedAt)

  const [row] = await db
    .insert(scanCoverageEvents)
    .values({
      deviceId: input.deviceId,
      platform: input.platform,
      workerId: input.workerId ?? null,
      locationCellId: cellId,
      lat: input.lat,
      lng: input.lng,
      distanceMeters: input.distanceMeters,
      scanMode: input.scanMode,
      deviceTier: input.deviceTier,
      capturedAt: input.capturedAt,
    })
    .returning()

  await db
    .insert(cellCoverageSummary)
    .values({
      locationCellId: cellId,
      firstScannedAt: input.capturedAt,
      lastScannedAt: input.capturedAt,
      totalDistanceMeters: input.distanceMeters,
      scanCount: 1,
    })
    .onConflictDoUpdate({
      target: cellCoverageSummary.locationCellId,
      set: {
        lastScannedAt: sql`GREATEST(${cellCoverageSummary.lastScannedAt}, EXCLUDED.last_scanned_at)`,
        firstScannedAt: sql`LEAST(${cellCoverageSummary.firstScannedAt}, EXCLUDED.first_scanned_at)`,
        totalDistanceMeters: sql`${cellCoverageSummary.totalDistanceMeters} + EXCLUDED.total_distance_meters`,
        scanCount: sql`${cellCoverageSummary.scanCount} + 1`,
      },
    })

  if (input.workerId != null) {
    const perMeter =
      category === 'new_area' ? POINTS_PER_METER.NEW_AREA :
      category === 'stale_rescan' ? POINTS_PER_METER.STALE_RESCAN :
      POINTS_PER_METER.FRESH_RESCAN
    const points = Math.round(perMeter * input.distanceMeters)

    if (points > 0) {
      const action = category === 'new_area' ? 'scan_coverage_new_area' : 'scan_coverage_stale'
      await db.insert(pointsLedger).values({
        workerId: input.workerId,
        action,
        points,
        referenceType: 'scan_coverage_event',
        referenceId: row.id,
        createdAt: input.capturedAt,
      })
    }
  }

  return row
}
