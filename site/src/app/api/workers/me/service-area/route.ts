import { db } from '@/db'
import { blocks } from '@/db/schema'
import { and, eq, sql } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

export async function GET() {
  const serviceAreaId = 1
  const [countResult] = await db
    .select({ count: sql<number>`count(*)` })
    .from(blocks)
    .where(and(
      eq(blocks.serviceAreaId, serviceAreaId),
      sql`lower(${blocks.name}) <> 'test'`,
    ))
  const totalBlocks = Number(countResult?.count) || 0

  // Return North Lawndale as default service area wrapped in ApiResponse
  return Response.json({
    data: {
      id: serviceAreaId,
      name: 'North Lawndale',
      city: 'Chicago',
      state: 'IL',
      mapCenter: { lng: -87.7228, lat: 41.8603 },
      mapZoom: 13.2,
      status: 'active',
      totalBlocks,
      activeWorkers: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
    success: true,
  })
}
