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
  quality: 'grazed' | 'close'
  capturedAt: string
}

type CoverageCategory = 'new_area' | 'new_area_grazed' | 'close_upgrade' | 'stale_rescan' | 'fresh_rescan'

/**
 * Classifies against the cell's PRIOR state (read before any write this call
 * makes). Quality split (spec §3): grazed first-scan pays partial, a
 * grazed→close upgrade on a fresh cell pays the remainder, straight-to-close
 * pays full. Stale rescans pay the flat stale rate at either quality.
 */
function categorizeCoverageEvent(
  priorLastScannedAt: string | null,
  priorBestQuality: string | null,
  capturedAt: string,
  quality: 'grazed' | 'close'
): CoverageCategory {
  if (priorLastScannedAt === null) return quality === 'close' ? 'new_area' : 'new_area_grazed'
  const ageMs = new Date(capturedAt).getTime() - new Date(priorLastScannedAt).getTime()
  if (ageMs > STALE_AFTER_MS) return 'stale_rescan'
  if (quality === 'close' && priorBestQuality !== 'close') return 'close_upgrade'
  return 'fresh_rescan'
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
    .select({
      lastScannedAt: cellCoverageSummary.lastScannedAt,
      bestQuality: cellCoverageSummary.bestQuality,
    })
    .from(cellCoverageSummary)
    .where(eq(cellCoverageSummary.locationCellId, cellId))
    .limit(1)

  const category = categorizeCoverageEvent(
    priorSummary?.lastScannedAt ?? null,
    priorSummary?.bestQuality ?? null,
    input.capturedAt,
    input.quality
  )

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
      quality: input.quality,
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
      bestQuality: input.quality,
      lastCloseScannedAt: input.quality === 'close' ? input.capturedAt : null,
    })
    .onConflictDoUpdate({
      target: cellCoverageSummary.locationCellId,
      set: {
        lastScannedAt: sql`GREATEST(${cellCoverageSummary.lastScannedAt}, EXCLUDED.last_scanned_at)`,
        firstScannedAt: sql`LEAST(${cellCoverageSummary.firstScannedAt}, EXCLUDED.first_scanned_at)`,
        totalDistanceMeters: sql`${cellCoverageSummary.totalDistanceMeters} + EXCLUDED.total_distance_meters`,
        scanCount: sql`${cellCoverageSummary.scanCount} + 1`,
        bestQuality: sql`CASE WHEN ${cellCoverageSummary.bestQuality} = 'close' OR EXCLUDED.best_quality = 'close' THEN 'close' ELSE 'grazed' END`,
        lastCloseScannedAt: sql`CASE WHEN EXCLUDED.best_quality = 'close'
          THEN GREATEST(COALESCE(${cellCoverageSummary.lastCloseScannedAt}, EXCLUDED.last_scanned_at), EXCLUDED.last_scanned_at)
          ELSE ${cellCoverageSummary.lastCloseScannedAt} END`,
      },
    })

  if (input.workerId != null) {
    const perMeter =
      category === 'new_area' ? POINTS_PER_METER.NEW_AREA :
      category === 'new_area_grazed' ? POINTS_PER_METER.NEW_AREA_GRAZED :
      category === 'close_upgrade' ? POINTS_PER_METER.CLOSE_UPGRADE :
      category === 'stale_rescan' ? POINTS_PER_METER.STALE_RESCAN :
      POINTS_PER_METER.FRESH_RESCAN
    const points = Math.round(perMeter * input.distanceMeters)

    if (points > 0) {
      const action =
        category === 'close_upgrade' ? 'scan_coverage_close_upgrade' :
        category === 'stale_rescan' ? 'scan_coverage_stale' :
        'scan_coverage_new_area'
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
