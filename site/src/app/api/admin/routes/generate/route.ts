import { NextResponse } from 'next/server'
import { db } from '@/db'
import { routes } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { generateRouteGrid } from '@/lib/route-grid-generator'

export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const body = await request.json()
    const { communityAreaNumber, serviceAreaId, blockWidth, blockHeight } = body

    if (!communityAreaNumber) {
      return NextResponse.json({ error: 'communityAreaNumber is required' }, { status: 400 })
    }

    // Get existing routes to avoid overlaps
    const existingRoutes = await db.select({
      code: routes.code,
      geometry: routes.geometry,
    }).from(routes)

    const existingCodes = existingRoutes.map(r => r.code)
    const existingGeometries = existingRoutes
      .filter(r => r.geometry)
      .map(r => r.geometry as { type: string; coordinates: [number, number][] })

    const proposals = generateRouteGrid({
      communityAreaNumber,
      blockWidth: blockWidth || 130,
      blockHeight: blockHeight || 200,
      existingCodes,
      existingGeometries,
    })

    return NextResponse.json({ proposals, total: proposals.length })
  } catch (error) {
    console.error('Error generating routes:', error)
    return NextResponse.json({ error: 'Failed to generate routes' }, { status: 500 })
  }
}
