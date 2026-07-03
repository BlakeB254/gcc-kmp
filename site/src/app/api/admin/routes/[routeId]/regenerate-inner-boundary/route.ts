import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { eq } from 'drizzle-orm'
import { generateInnerBoundaryFromRing } from '@/lib/inner-boundary-generator'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ routeId: string }> }

/**
 * Regenerates a route's inner boundary (private-property cutoff) so it
 * conforms to REAL building frontages. See generateInnerBoundaryFromRing
 * in @/lib/inner-boundary-generator for the algorithm — shared with the
 * block-proposal approve flow so both paths produce identical geometry.
 */
export async function POST(request: NextRequest, context: RouteContext) {
  const admin = await requireAdmin(request)
  if (admin instanceof NextResponse) return admin

  const { routeId: routeIdParam } = await context.params
  const routeId = parseInt(routeIdParam, 10)
  if (Number.isNaN(routeId)) {
    return NextResponse.json({ success: false, error: 'Invalid route ID' }, { status: 400 })
  }

  const body = await request.json().catch(() => ({}))
  const setbackMeters = Number(body?.setbackMeters) || 8

  const [block] = await db.select().from(blocks).where(eq(blocks.routeId, routeId)).limit(1)
  const boundary = block?.locationBoundary as { coordinates?: number[][][] } | null
  const ring = boundary?.coordinates?.[0]
  if (!block || !ring || ring.length < 4) {
    return NextResponse.json({ success: false, error: 'Route has no block boundary' }, { status: 404 })
  }

  let result
  try {
    result = await generateInnerBoundaryFromRing(ring, { setbackMeters })
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unknown error'
    if (message.startsWith('overpass')) {
      console.error('[inner-boundary] Overpass fetch failed:', message)
      return NextResponse.json(
        { success: false, error: 'Building data source unavailable — try again shortly' },
        { status: 502 }
      )
    }
    return NextResponse.json({ success: false, error: message }, { status: 422 })
  }

  await db.update(blocks).set({ innerBoundary: result.multiPolygon }).where(eq(blocks.id, block.id))
  return NextResponse.json({ success: true, innerBoundary: result.multiPolygon, stats: result.stats })
}
