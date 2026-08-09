import { NextRequest } from 'next/server'

import { publicCacheControl } from '@/lib/http/cache-policy'
import {
  loadInitialAffectedAreas,
  type AffectedAreaScope,
} from '@/lib/affected-areas/cached-initial-load'

export const runtime = 'nodejs'
export const revalidate = 45

const CACHE_HEADERS = {
  'Cache-Control': publicCacheControl({
    browserMaxAgeSeconds: 45,
    sharedMaxAgeSeconds: 45,
    staleWhileRevalidateSeconds: 45,
  }),
}

const STATUS_ORDER = {
  available: 0,
  funded: 1,
  in_progress: 2,
  completed: 3,
} as const

/**
 * GET /api/affected-areas
 *
 * GeoJSON FeatureCollection of every block in any non-trivial state —
 * funded, scheduled, in-progress, or completed. Consumed by the mobile
 * app's dynamic heatmap. `available` blocks are included so the client
 * can surface "fundable" polygons alongside active ones.
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
  // Existing affected-area requests are full snapshots and accept exactly:
  //   - serviceAreaId: number-like string (default: all)
  //   - minStatus: available | funded | in_progress | completed
  //                (default: available)
  // This route has no delta parameter. Mobile's authoritative delta routes
  // are /api/map/coverage and /api/map/observations: both use `bbox` plus the
  // optional `since` cursor and remain force-dynamic. Their server-owned item
  // timestamps are `lastScannedAt` and `capturedAt`, respectively. This route
  // preserves its required top-level server timestamp as `generatedAt`.
  const { searchParams } = request.nextUrl
  const serviceAreaId = searchParams.get('serviceAreaId')
  const minStatusParam = searchParams.get('minStatus') ?? 'available'
  const minStatus = STATUS_ORDER[minStatusParam as keyof typeof STATUS_ORDER] ?? 4
  const scope: AffectedAreaScope = { serviceAreaId, minStatus }

  const collection = await loadInitialAffectedAreas(scope)
  return Response.json(collection, { headers: CACHE_HEADERS })
}
