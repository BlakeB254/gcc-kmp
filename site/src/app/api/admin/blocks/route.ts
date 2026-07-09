import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, serviceAreas } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const blockList = await db
      .select()
      .from(blocks)
      .leftJoin(serviceAreas, eq(blocks.serviceAreaId, serviceAreas.id))

    const result = blockList.map(({ blocks: b, service_areas: sa }) => ({
      id: b.id,
      code: b.code,
      name: b.name,
      serviceAreaId: b.serviceAreaId,
      routeId: b.routeId,
      status: b.status || 'available',
      boundary: b.locationBoundary,
      innerBoundary: b.innerBoundary,
      center: b.locationCenter,
      fundingGoal: b.fundingGoal ? parseInt(b.fundingGoal) : 10000,
      fundingCurrent: b.fundingCurrent ? parseInt(b.fundingCurrent) : 0,
      sponsorId: b.fundedById,
      createdAt: new Date(b.createdAt).toISOString(),
      updatedAt: new Date(b.updatedAt).toISOString(),
    }))

    return NextResponse.json({ data: result })
  } catch (error) {
    console.error('Error fetching admin blocks:', error)
    return NextResponse.json({ error: 'Failed to fetch blocks' }, { status: 500 })
  }
}
