import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { eq, ne, and, isNotNull } from 'drizzle-orm'
import intersect from '@turf/intersect'
import difference from '@turf/difference'
import area from '@turf/area'
import { featureCollection, polygon as turfPolygon } from '@turf/helpers'
import {
  commitPublicDataMutation,
  type PublicDataInvalidator,
} from '@/lib/public-data/invalidation'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ blockId: string }> }

type GeoPolygon = { type: 'Polygon'; coordinates: number[][][] }
type GeoMultiPolygon = { type: 'MultiPolygon'; coordinates: number[][][][] }

export function commitAffectedAreaMutation<T>(
  mutation: () => Promise<T>,
  invalidate?: PublicDataInvalidator,
): Promise<T> {
  return commitPublicDataMutation('affected-area', mutation, invalidate)
}

/**
 * PUT /api/admin/blocks/[blockId]/boundaries
 * Body: { locationBoundary?: Polygon, innerBoundary?: MultiPolygon }
 *
 * The save endpoint behind the admin boundary editor (route-v2). Exterior
 * boundaries must tile without overlapping: when the edited block's new
 * outer boundary overlaps a neighbor, the NEIGHBOR is automatically thinned
 * (the overlap is subtracted from it) — "expanding into a neighbor squeezes
 * the neighbor", per product direction. A neighbor is never thinned below
 * [MIN_NEIGHBOR_AREA_RATIO] of its current area; past that the save is
 * rejected so a block can't be silently swallowed.
 */
const MIN_NEIGHBOR_AREA_RATIO = 0.4

export async function PUT(request: NextRequest, context: RouteContext) {
  const admin = await requireAdmin(request)
  if (admin instanceof NextResponse) return admin

  const { blockId: blockIdParam } = await context.params
  const blockId = parseInt(blockIdParam, 10)
  if (Number.isNaN(blockId)) {
    return NextResponse.json({ success: false, error: 'Invalid block ID' }, { status: 400 })
  }

  const body = await request.json().catch(() => null)
  const newOuter = body?.locationBoundary as GeoPolygon | undefined
  const newInner = body?.innerBoundary as GeoMultiPolygon | null | undefined

  if (newOuter && (newOuter.type !== 'Polygon' || !isValidRing(newOuter.coordinates?.[0]))) {
    return NextResponse.json({ success: false, error: 'locationBoundary must be a valid GeoJSON Polygon' }, { status: 400 })
  }
  if (newInner && (newInner.type !== 'MultiPolygon' || !Array.isArray(newInner.coordinates) ||
      newInner.coordinates.some((poly) => !isValidRing(poly?.[0])))) {
    return NextResponse.json({ success: false, error: 'innerBoundary must be a valid GeoJSON MultiPolygon' }, { status: 400 })
  }
  if (!newOuter && newInner === undefined) {
    return NextResponse.json({ success: false, error: 'Nothing to update' }, { status: 400 })
  }

  const [target] = await db.select().from(blocks).where(eq(blocks.id, blockId)).limit(1)
  if (!target) {
    return NextResponse.json({ success: false, error: 'Block not found' }, { status: 404 })
  }

  const thinned: Array<{ blockId: number; newBoundary: unknown }> = []

  if (newOuter) {
    const edited = turfPolygon(newOuter.coordinates)
    const neighbors = await db
      .select({ id: blocks.id, locationBoundary: blocks.locationBoundary })
      .from(blocks)
      .where(and(ne(blocks.id, blockId), isNotNull(blocks.locationBoundary)))

    for (const neighbor of neighbors) {
      const nb = neighbor.locationBoundary as GeoPolygon
      if (nb?.type !== 'Polygon' || !isValidRing(nb.coordinates?.[0])) continue
      const neighborPoly = turfPolygon(nb.coordinates)
      const overlap = intersect(featureCollection([edited, neighborPoly]))
      if (!overlap || area(overlap) < 1) continue // <1m² = shared edge noise

      // Thin the neighbor: subtract the edited block's claim from it.
      const remainder = difference(featureCollection([neighborPoly, edited]))
      if (!remainder) {
        return NextResponse.json(
          { success: false, error: `Edit would completely swallow block ${neighbor.id}` },
          { status: 422 }
        )
      }
      const keptRatio = area(remainder) / area(neighborPoly)
      if (keptRatio < MIN_NEIGHBOR_AREA_RATIO) {
        return NextResponse.json(
          {
            success: false,
            error: `Edit would thin block ${neighbor.id} below ${Math.round(MIN_NEIGHBOR_AREA_RATIO * 100)}% of its area — pull the boundary back`,
          },
          { status: 422 }
        )
      }
      // Keep the largest piece if the subtraction split the neighbor.
      const geom = remainder.geometry
      const newBoundary: GeoPolygon = geom.type === 'MultiPolygon'
        ? { type: 'Polygon', coordinates: largestPolygon(geom.coordinates) }
        : (geom as GeoPolygon)
      thinned.push({ blockId: neighbor.id, newBoundary })
    }
  }

  // Apply: neighbor thinning first, then the edited block.
  for (const t of thinned) {
    await commitAffectedAreaMutation(() =>
      db.update(blocks).set({ locationBoundary: t.newBoundary }).where(eq(blocks.id, t.blockId)),
    )
  }
  const updates: Record<string, unknown> = {}
  if (newOuter) updates.locationBoundary = newOuter
  if (newInner !== undefined) updates.innerBoundary = newInner
  const updateTarget = () => db.update(blocks).set(updates).where(eq(blocks.id, blockId))
  if (newOuter) {
    await commitAffectedAreaMutation(updateTarget)
  } else {
    await updateTarget()
  }

  return NextResponse.json({
    success: true,
    blockId,
    thinnedNeighbors: thinned.map((t) => t.blockId),
  })
}

function isValidRing(ring: unknown): boolean {
  return Array.isArray(ring) && ring.length >= 4 &&
    ring.every((p) => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1]))
}

function largestPolygon(multi: number[][][][]): number[][][] {
  let best = multi[0]
  let bestArea = -1
  for (const poly of multi) {
    const a = area(turfPolygon(poly))
    if (a > bestArea) { bestArea = a; best = poly }
  }
  return best
}
