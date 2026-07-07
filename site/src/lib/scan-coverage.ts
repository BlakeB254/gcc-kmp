import { db } from '@/db'
import { scanCoverageEvents, cellCoverageSummary, pointsLedger } from '@/db/schema'
import { gridCell } from '@/lib/world-observations'
import { sql, eq, and, gte, lte, inArray } from 'drizzle-orm'
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
  quality: 'seen' | 'grazed' | 'close'
  capturedAt: string
}

type CoverageCategory =
  | 'new_area'
  | 'new_area_grazed'
  | 'close_upgrade'
  | 'stale_rescan'
  | 'fresh_rescan'
  | 'seen_only'

/**
 * Raw-SQL CASE expression ranking two boolean conditions (whichever passes
 * for the highest tier wins): pass `isClose`/`isGrazed` SQL fragments that
 * evaluate true when a close/grazed record is present; the result is
 * 'close' | 'grazed' | 'seen'. Shared by both the live upsert's conflict-
 * resolution and the retraction recompute's aggregate, so the tier ranking
 * (close > grazed > seen) can never drift apart between the two paths.
 */
function qualityRankCase(isClose: any, isGrazed: any) {
  return sql<string>`CASE WHEN ${isClose} THEN 'close' WHEN ${isGrazed} THEN 'grazed' ELSE 'seen' END`
}

/**
 * Classifies against the cell's PRIOR state (read before any write this call
 * makes). Quality split (spec §3): grazed first-scan pays partial, a
 * grazed→close upgrade on a fresh cell pays the remainder, straight-to-close
 * pays full. Stale rescans pay the flat stale rate at either quality.
 *
 * SEEN never pays (Pillar 7 Global Constraints: "sight isn't work") — it
 * short-circuits to its own category regardless of prior state. A prior
 * bestQuality of 'seen' reads as UNWORKED here: seeing a cell from across
 * the street doesn't discount the reward for later actually walking it.
 */
function categorizeCoverageEvent(
  priorLastScannedAt: string | null,
  priorBestQuality: string | null,
  capturedAt: string,
  quality: 'seen' | 'grazed' | 'close'
): CoverageCategory {
  if (quality === 'seen') return 'seen_only'
  const priorWorked = priorLastScannedAt !== null && priorBestQuality !== 'seen'
  if (!priorWorked) return quality === 'close' ? 'new_area' : 'new_area_grazed'
  const ageMs = new Date(capturedAt).getTime() - new Date(priorLastScannedAt!).getTime()
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
        // lastScannedAt is the staleness clock categorizeCoverageEvent reads
        // for the NEXT event on this cell — a 'seen' event (sight isn't
        // work) must not refresh it, or a stale rescan could land as
        // fresh_rescan (0 pts) just because someone glanced at the cell
        // from across the street in between.
        lastScannedAt: sql`CASE WHEN EXCLUDED.best_quality = 'seen'
          THEN ${cellCoverageSummary.lastScannedAt}
          ELSE GREATEST(${cellCoverageSummary.lastScannedAt}, EXCLUDED.last_scanned_at) END`,
        firstScannedAt: sql`LEAST(${cellCoverageSummary.firstScannedAt}, EXCLUDED.first_scanned_at)`,
        totalDistanceMeters: sql`${cellCoverageSummary.totalDistanceMeters} + EXCLUDED.total_distance_meters`,
        scanCount: sql`${cellCoverageSummary.scanCount} + 1`,
        bestQuality: qualityRankCase(
          sql`${cellCoverageSummary.bestQuality} = 'close' OR EXCLUDED.best_quality = 'close'`,
          sql`${cellCoverageSummary.bestQuality} = 'grazed' OR EXCLUDED.best_quality = 'grazed'`,
        ),
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
      category === 'seen_only' ? 0 : // sight isn't work (Global Constraints)
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

const MAX_RETRACT_WINDOW_MS = 10 * 60 * 1000

export interface RetractCoverageInput {
  deviceId: string
  workerId: number
  fromCapturedAt: string
  toCapturedAt: string
}

/**
 * Undo a device's coverage events in a bounded time window (INDOOR-flip
 * retraction, spec §2/§3): claw back the points those events awarded, delete
 * the events, and recompute each touched cell's summary from the surviving
 * event log (deleting the summary row when nothing survives). The event log
 * is the source of truth; the summary is always derivable — which is what
 * makes retraction safe.
 */
export async function retractCoverageWindow(input: RetractCoverageInput) {
  const fromMs = new Date(input.fromCapturedAt).getTime()
  const toMs = new Date(input.toCapturedAt).getTime()
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || toMs <= fromMs) {
    return { deletedEvents: 0, cellsRecomputed: 0, error: 'invalid window' as const }
  }
  if (toMs - fromMs > MAX_RETRACT_WINDOW_MS) {
    return { deletedEvents: 0, cellsRecomputed: 0, error: 'window too large' as const }
  }

  const deleted = await db
    .delete(scanCoverageEvents)
    .where(and(
      eq(scanCoverageEvents.deviceId, input.deviceId),
      eq(scanCoverageEvents.workerId, input.workerId),
      gte(scanCoverageEvents.capturedAt, input.fromCapturedAt),
      lte(scanCoverageEvents.capturedAt, input.toCapturedAt),
    ))
    .returning({ id: scanCoverageEvents.id, locationCellId: scanCoverageEvents.locationCellId })

  if (deleted.length === 0) return { deletedEvents: 0, cellsRecomputed: 0 }

  await db
    .delete(pointsLedger)
    .where(and(
      eq(pointsLedger.referenceType, 'scan_coverage_event'),
      inArray(pointsLedger.referenceId, deleted.map((d) => d.id)),
    ))

  const cellIds = [...new Set(deleted.map((d) => d.locationCellId))]
  for (const cellId of cellIds) {
    const [agg] = await db
      .select({
        firstScannedAt: sql<string | null>`MIN(${scanCoverageEvents.capturedAt})`,
        lastScannedAt: sql<string | null>`MAX(${scanCoverageEvents.capturedAt})`,
        totalDistanceMeters: sql<number>`COALESCE(SUM(${scanCoverageEvents.distanceMeters}), 0)`,
        scanCount: sql<number>`COUNT(*)::int`,
        // Ranked the same way the live upsert ranks it — see qualityRankCase.
        bestQuality: qualityRankCase(
          sql`BOOL_OR(${scanCoverageEvents.quality} = 'close')`,
          sql`BOOL_OR(${scanCoverageEvents.quality} = 'grazed')`,
        ),
        lastCloseScannedAt: sql<string | null>`MAX(${scanCoverageEvents.capturedAt}) FILTER (WHERE ${scanCoverageEvents.quality} = 'close')`,
      })
      .from(scanCoverageEvents)
      .where(eq(scanCoverageEvents.locationCellId, cellId))

    if (!agg || agg.scanCount === 0 || agg.firstScannedAt === null || agg.lastScannedAt === null) {
      await db.delete(cellCoverageSummary).where(eq(cellCoverageSummary.locationCellId, cellId))
    } else {
      await db
        .update(cellCoverageSummary)
        .set({
          firstScannedAt: agg.firstScannedAt,
          lastScannedAt: agg.lastScannedAt,
          totalDistanceMeters: agg.totalDistanceMeters,
          scanCount: agg.scanCount,
          bestQuality: agg.bestQuality,
          lastCloseScannedAt: agg.lastCloseScannedAt,
        })
        .where(eq(cellCoverageSummary.locationCellId, cellId))
    }
  }

  return { deletedEvents: deleted.length, cellsRecomputed: cellIds.length }
}
