import { NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

interface AcceptedProposal {
  routeCode: string
  blockCode: string
  displayName: string
  geometry: { type: string; coordinates: [number, number][] }
  blockPolygon: [number, number][][]
  center: { lng: number; lat: number }
  distanceMeters: number
  estimatedMinutes: number
  side: string
}

export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const body = await request.json()
    const { serviceAreaId, proposals } = body as { serviceAreaId: number; proposals: AcceptedProposal[] }

    if (!serviceAreaId || !proposals?.length) {
      return NextResponse.json({ error: 'serviceAreaId and proposals array required' }, { status: 400 })
    }

    // Insert routes first (need IDs for blocks)
    const insertedRoutes = await db.insert(routes).values(
      proposals.map((p) => ({
        code: p.routeCode,
        displayName: p.displayName,
        serviceAreaId,
        geometry: p.geometry,
        lengthMeters: p.distanceMeters.toFixed(1),
        estimatedMinutes: p.estimatedMinutes.toString(),
        status: 'draft' as const,
        payoutAmount: '10000',
        createdById: auth.workerId,
      }))
    ).returning({ id: routes.id })

    // Batch insert blocks using route IDs
    const blockValues = proposals.map((p, i) => ({
      code: p.blockCode,
      name: p.displayName,
      serviceAreaId,
      routeId: insertedRoutes[i].id,
      locationBoundary: { type: 'Polygon', coordinates: p.blockPolygon },
      locationCenter: { type: 'Point', coordinates: [p.center.lng, p.center.lat] },
      side: p.side,
      status: 'funded' as const,
    }))

    await db.insert(blocks).values(blockValues)

    const createdRouteIds = insertedRoutes.map((r) => r.id)
    return NextResponse.json({ created: createdRouteIds.length, routes: createdRouteIds }, { status: 201 })
  } catch (error) {
    console.error('Error accepting proposals:', error)
    return NextResponse.json({ error: 'Failed to accept proposals' }, { status: 500 })
  }
}
