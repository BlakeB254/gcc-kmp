import { unstable_cache } from 'next/cache'
import { and, eq, isNotNull, sql } from 'drizzle-orm'

import { db } from '@/db'
import { blocks, serviceAreas, transactions } from '@/db/schema'
import { GCC_CACHE_TAGS } from '@/lib/public-data/cache-tags'
import { isFundedBlockRow } from '@/lib/sponsor-funded'

const AFFECTED_AREA_CACHE_SECONDS = 45

type AffectedBlockStatus = 'available' | 'funded' | 'in_progress' | 'completed'

const STATUS_ORDER: Record<AffectedBlockStatus, number> = {
  available: 0,
  funded: 1,
  in_progress: 2,
  completed: 3,
}

export type AffectedAreaScope = {
  serviceAreaId: string | null
  minStatus: number | null
}

export type AffectedAreaFeature = {
  type: 'Feature'
  geometry: {
    type: 'Polygon'
    coordinates: number[][][]
  }
  properties: {
    blockId: number
    code: string | null
    name: string | null
    status: string
    intensity: number
    scheduledCleaningAt: string | null
    sponsorCount: number
    fundingCurrent: number
    fundingGoal: number
    completedAt: string | null
    serviceAreaName: string | null
    centerLat: number | null
    centerLng: number | null
  }
}

export type AffectedAreasGeoJson = {
  type: 'FeatureCollection'
  features: AffectedAreaFeature[]
  generatedAt: string
  count: number
}

export type AffectedAreasQuery = (
  scope: AffectedAreaScope,
) => Promise<AffectedAreasGeoJson>

export function affectedAreaCacheKey(scope: AffectedAreaScope): readonly string[] {
  const normalized = normalizeAffectedAreaScope(scope)
  return [
    'affected-areas',
    'full-snapshot',
    `service-area:${JSON.stringify(normalized.serviceAreaId)}`,
    `min-status:${normalized.minStatus}`,
  ]
}

export function createInitialAffectedAreasLoader(
  query: AffectedAreasQuery,
): AffectedAreasQuery {
  return async (scope) => {
    const normalized = normalizeAffectedAreaScope(scope)
    const cachedQuery = unstable_cache(
      () => query(normalized),
      [...affectedAreaCacheKey(normalized)],
      {
        tags: [GCC_CACHE_TAGS.affectedAreaSnapshots],
        revalidate: AFFECTED_AREA_CACHE_SECONDS,
      },
    )

    return cachedQuery()
  }
}

export async function queryAffectedAreas(
  scope: AffectedAreaScope,
): Promise<AffectedAreasGeoJson> {
  const normalized = normalizeAffectedAreaScope(scope)
  // Sponsor count + earliest scheduled cleaning date per block, computed
  // in SQL to avoid N+1 lookups on the app side.
  const sponsorSub = db
    .select({
      blockId: transactions.blockId,
      sponsorCount: sql<number>`count(*)`.as('sponsor_count'),
      nextScheduledAt: sql<string | null>`min(${transactions.preferredCleaningDate})`.as(
        'next_scheduled_at',
      ),
    })
    .from(transactions)
    .where(
      and(
        eq(transactions.status, 'completed'),
        // Funded rows only: a reservation keeps its block_id but funded nothing.
        isFundedBlockRow(),
      ),
    )
    .groupBy(transactions.blockId)
    .as('sponsor_sub')

  const conditions = [isNotNull(blocks.locationBoundary)]
  if (normalized.serviceAreaId) {
    conditions.push(eq(blocks.serviceAreaId, Number(normalized.serviceAreaId)))
  }

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

  const minStatus = normalized.minStatus ?? STATUS_ORDER.available
  const features = rows
    .filter((row) => {
      const statusIdx = STATUS_ORDER[(row.status ?? 'available') as AffectedBlockStatus] ?? 0
      return statusIdx >= minStatus
    })
    .map((row): AffectedAreaFeature => {
      const center = row.locationCenter as
        | { type: 'Point'; coordinates: [number, number] }
        | null
      const boundary = row.locationBoundary as
        | { type: 'Polygon'; coordinates: number[][][] }
        | null

      const status = row.status ?? 'available'
      const intensity = computeIntensity({
        status,
        fundingCurrent: Number(row.fundingCurrent) || 0,
        fundingGoal: Number(row.fundingGoal) || 10_000,
        sponsorCount: Number(row.sponsorCount) || 0,
        completedAt: row.completedAt,
      })

      return {
        type: 'Feature',
        geometry: boundary ?? { type: 'Polygon', coordinates: [] },
        properties: {
          blockId: row.id,
          code: row.code,
          name: row.name,
          status,
          intensity,
          scheduledCleaningAt: row.preferredCleaningDate
            ? new Date(row.preferredCleaningDate).toISOString()
            : row.nextScheduledAt
              ? new Date(row.nextScheduledAt).toISOString()
              : null,
          sponsorCount: Number(row.sponsorCount) || 0,
          fundingCurrent: Number(row.fundingCurrent) || 0,
          fundingGoal: Number(row.fundingGoal) || 10_000,
          completedAt: row.completedAt
            ? new Date(row.completedAt).toISOString()
            : null,
          serviceAreaName: row.serviceAreaName ?? null,
          centerLat: center?.coordinates?.[1] ?? null,
          centerLng: center?.coordinates?.[0] ?? null,
        },
      }
    })

  return {
    type: 'FeatureCollection',
    features,
    generatedAt: new Date().toISOString(),
    count: features.length,
  }
}

export const loadInitialAffectedAreas = createInitialAffectedAreasLoader(queryAffectedAreas)

function normalizeAffectedAreaScope(scope: AffectedAreaScope): AffectedAreaScope {
  const serviceAreaId = scope.serviceAreaId
    ? String(parseInt(scope.serviceAreaId))
    : null

  return {
    serviceAreaId,
    minStatus: scope.minStatus ?? STATUS_ORDER.available,
  }
}

/** Compose the existing 0..1 heatmap intensity score from block state signals. */
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

  const sponsorBoost = Math.min(0.2, Math.log10(sponsorCount + 1) * 0.15)
  const fundingBoost = fundingPct * 0.1

  let decay = 1
  if (status === 'completed' && completedAt) {
    const ageDays = (Date.now() - new Date(completedAt).getTime()) / 86_400_000
    decay = Math.max(0.4, 1 - ageDays / 60)
  }

  return Math.max(0, Math.min(1, (base + sponsorBoost + fundingBoost) * decay))
}
