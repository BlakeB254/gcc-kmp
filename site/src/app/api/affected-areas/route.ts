import { NextRequest } from 'next/server'
import { db } from '@/db'
import { blocks, transactions, serviceAreas } from '@/db/schema'
import { and, eq, isNotNull, sql } from 'drizzle-orm'

export const runtime = 'nodejs'
export const revalidate = 60

/**
 * GET /api/affected-areas
 *
 * GeoJSON FeatureCollection of every block in any non-trivial state —
 * funded, scheduled, in-progress, or completed. Consumed by the mobile
 * app's dynamic heatmap. `available` blocks are included so the client
 * can surface "fundable" polygons alongside active ones.
 *
 * Query params:
 *   - serviceAreaId: number (default: all)
 *   - minStatus: 'available' | 'funded' | 'in_progress' | 'completed'
 *                Omits blocks with status weaker than this. Default 'available'.
 *
 * Response: GeoJSON FeatureCollection. Each Feature's geometry is the
 * block's polygon (from blocks.locationBoundary), with properties:
 *   - blockId, code, name, status (block lifecycle)
 *   - intensity: 0..1, computed from funding progress + sponsor count +
 *     recency of last cleaning — drives the heatmap alpha / color.
 *   - scheduledCleaningAt: ISO string, nullable
 *   - sponsorCount: how many transactions have funded this block
 *   - fundingCurrent / fundingGoal: raw numbers so the client can render
 *     progress UI without refetching.
 *   - centerLat / centerLng: convenience scalar extraction from center
 *     GeoJSON (simplifies map marker placement).
 *   - serviceAreaName: for filtering on the client.
 */
export async function GET(request: NextRequest) {
  const { searchParams } = request.nextUrl
  const serviceAreaId = searchParams.get('serviceAreaId')
  const minStatus = (searchParams.get('minStatus') ?? 'available') as
    | 'available'
    | 'funded'
    | 'in_progress'
    | 'completed'

  const STATUS_ORDER: Record<typeof minStatus, number> = {
    available: 0,
    funded: 1,
    in_progress: 2,
    completed: 3,
  }
  const minIdx = STATUS_ORDER[minStatus]

  // Sponsor count + earliest scheduled cleaning date per block, computed
  // in SQL to avoid N+1 lookups on the app side.
  const sponsorSub = db
    .select({
      blockId: transactions.blockId,
      sponsorCount: sql<number>`count(*)`.as('sponsor_count'),
      nextScheduledAt: sql<string | null>`min(${transactions.preferredCleaningDate})`.as(
        'next_scheduled_at'
      ),
    })
    .from(transactions)
    .where(
      and(
        eq(transactions.status, 'completed'),
        isNotNull(transactions.blockId)
      )
    )
    .groupBy(transactions.blockId)
    .as('sponsor_sub')

  const conditions = [isNotNull(blocks.locationBoundary)]
  if (serviceAreaId) {
    conditions.push(eq(blocks.serviceAreaId, parseInt(serviceAreaId)))
  }

  // drizzle's inferred type on status is `string | null`; we only filter
  // rows with a non-null status below so this assignment is safe.
  const rows = await db
    .select({
      id: blocks.id,
      code: blocks.code,
      name: blocks.name,
      status: blocks.status,
      serviceAreaName: serviceAreas.name,
      locationBoundary: blocks.locationBoundary,
      locationCenter: blocks.locationCenter,
      fundingGoal: blocks.fundingGoal,
      fundingCurrent: blocks.fundingCurrent,
      preferredCleaningDate: blocks.preferredCleaningDate,
      completedAt: blocks.completedAt,
      sponsorCount: sql<number>`coalesce(${sponsorSub.sponsorCount}, 0)`,
      nextScheduledAt: sponsorSub.nextScheduledAt,
    })
    .from(blocks)
    .leftJoin(serviceAreas, eq(blocks.serviceAreaId, serviceAreas.id))
    .leftJoin(sponsorSub, eq(sponsorSub.blockId, blocks.id))
    .where(and(...conditions))
    .orderBy(blocks.code)

  const features = rows
    .filter((r) => {
      const statusIdx = STATUS_ORDER[(r.status ?? 'available') as typeof minStatus] ?? 0
      return statusIdx >= minIdx
    })
    .map((r) => {
      const center = r.locationCenter as
        | { type: 'Point'; coordinates: [number, number] }
        | null
      const boundary = r.locationBoundary as
        | { type: 'Polygon'; coordinates: number[][][] }
        | null

      const status = r.status ?? 'available'
      const intensity = computeIntensity({
        status,
        fundingCurrent: Number(r.fundingCurrent) || 0,
        fundingGoal: Number(r.fundingGoal) || 10000,
        sponsorCount: Number(r.sponsorCount) || 0,
        completedAt: r.completedAt,
      })

      return {
        type: 'Feature' as const,
        geometry: boundary ?? { type: 'Polygon', coordinates: [] },
        properties: {
          blockId: r.id,
          code: r.code,
          name: r.name,
          status,
          intensity,
          scheduledCleaningAt: r.preferredCleaningDate
            ? new Date(r.preferredCleaningDate).toISOString()
            : r.nextScheduledAt
            ? new Date(r.nextScheduledAt).toISOString()
            : null,
          sponsorCount: Number(r.sponsorCount) || 0,
          fundingCurrent: Number(r.fundingCurrent) || 0,
          fundingGoal: Number(r.fundingGoal) || 10000,
          completedAt: r.completedAt ? new Date(r.completedAt).toISOString() : null,
          serviceAreaName: r.serviceAreaName ?? null,
          centerLat: center?.coordinates?.[1] ?? null,
          centerLng: center?.coordinates?.[0] ?? null,
        },
      }
    })

  return Response.json({
    type: 'FeatureCollection',
    features,
    generatedAt: new Date().toISOString(),
    count: features.length,
  })
}

/**
 * Compose a 0..1 intensity score from block state signals.
 *
 * Intent: the heatmap should show *activity* — completed blocks glow
 * warm, in-progress blocks glow warmer, funded blocks are warm but
 * unlit, available blocks are faint. Intensity decays over time for
 * completed blocks so the heatmap reflects recent activity rather than
 * lifetime history.
 */
function computeIntensity(input: {
  status: string
  fundingCurrent: number
  fundingGoal: number
  sponsorCount: number
  completedAt: string | null
}): number {
  const { status, fundingCurrent, fundingGoal, sponsorCount, completedAt } = input
  const fundingPct = Math.min(1, fundingCurrent / Math.max(1, fundingGoal))

  let base = 0
  switch (status) {
    case 'in_progress':
      base = 0.95
      break
    case 'completed':
      base = 0.75
      break
    case 'funded':
      base = 0.55
      break
    case 'available':
    default:
      base = 0.2
  }

  // Sponsor-count boost diminishes: 1 sponsor = +0.05, 3 = +0.10, 10 = +0.20
  const sponsorBoost = Math.min(0.2, Math.log10(sponsorCount + 1) * 0.15)

  // Funding progress boosts available/funded blocks toward the "warm" zone
  const fundingBoost = fundingPct * 0.1

  // Completed-block decay: fresh completion is bright, 30d+ fades to ~0.5x
  let decay = 1
  if (status === 'completed' && completedAt) {
    const ageDays = (Date.now() - new Date(completedAt).getTime()) / 86400_000
    decay = Math.max(0.4, 1 - ageDays / 60) // linear decay over 60d, floor at 0.4
  }

  return Math.max(0, Math.min(1, (base + sponsorBoost + fundingBoost) * decay))
}
