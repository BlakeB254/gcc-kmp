import { NextRequest } from 'next/server'
import { db } from '@/db'
import { blocks, serviceAreas } from '@/db/schema'
import { eq, desc, sql, and } from 'drizzle-orm'

export const runtime = 'nodejs'

/**
 * GET /api/blocks
 * List available blocks with optional status filter
 *
 * Query params:
 *   - status: 'available' | 'funded' | 'in_progress' | 'completed'
 *   - serviceAreaId: number
 *   - limit: number (default 50)
 *   - page: number (default 1)
 *
 * Returns: { blocks: Block[], total: number }
 */
export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams
  const status = searchParams.get('status') as
    | 'available'
    | 'funded'
    | 'in_progress'
    | 'completed'
    | null
  const serviceAreaId = searchParams.get('serviceAreaId')
  const limit = parseInt(searchParams.get('limit') || '50')
  const page = parseInt(searchParams.get('page') || '1')
  const offset = (page - 1) * limit

  // Build query conditions
  const conditions = []
  if (status) conditions.push(eq(blocks.status, status))
  if (serviceAreaId) conditions.push(eq(blocks.serviceAreaId, parseInt(serviceAreaId)))

  const whereClause = conditions.length > 0
    ? conditions.length === 1 ? conditions[0] : and(...conditions)
    : undefined

  // Fetch blocks with service area info
  const blockList = await db
    .select({
      id: blocks.id,
      code: blocks.code,
      name: blocks.name,
      description: blocks.description,
      status: blocks.status,
      serviceAreaId: blocks.serviceAreaId,
      serviceAreaName: serviceAreas.name,
      locationNeighborhood: blocks.locationNeighborhood,
      locationBoundary: blocks.locationBoundary,
      innerBoundary: blocks.innerBoundary,
      locationCenter: blocks.locationCenter,
      displayImage: blocks.displayImage,
      imageLocationLat: blocks.imageLocationLat,
      imageLocationLng: blocks.imageLocationLng,
      streetName: blocks.streetName,
      fromCorner: blocks.fromCorner,
      toCorner: blocks.toCorner,
      side: blocks.side,
      includesAlley: blocks.includesAlley,
      fundingGoal: blocks.fundingGoal,
      fundingCurrent: blocks.fundingCurrent,
      fundedById: blocks.fundedById,
      fundedAt: blocks.fundedAt,
      // Cleaning schedule
      preferredCleaningDate: blocks.preferredCleaningDate,
      preferredCleaningTimeStart: blocks.preferredCleaningTimeStart,
      preferredCleaningTimeEnd: blocks.preferredCleaningTimeEnd,
      completedById: blocks.completedById,
      completedAt: blocks.completedAt,
      createdAt: blocks.createdAt,
      updatedAt: blocks.updatedAt,
    })
    .from(blocks)
    .leftJoin(serviceAreas, eq(blocks.serviceAreaId, serviceAreas.id))
    .where(whereClause)
    .orderBy(blocks.code) // Order by block code for consistent display
    .limit(limit)
    .offset(offset)

  // Get total count
  const [countResult] = await db
    .select({ count: sql<number>`count(*)` })
    .from(blocks)
    .where(whereClause)

  const total = Number(countResult?.count) || 0
  const totalPages = Math.ceil(total / limit)

  // Transform to API format with full geometry
  const formattedBlocks = blockList.map((block) => ({
      id: String(block.id),
      code: block.code,
      name: block.name,
      description: block.description,
      status: block.status,
      serviceAreaId: block.serviceAreaId,
      serviceAreaName: block.serviceAreaName,
      // Location with full geometry
      location: {
        neighborhood: block.locationNeighborhood,
        boundary: block.locationBoundary,
        center: block.locationCenter,
      },
      // Authored exclusion zones — the main map renders these; without them
      // clients fall back to the synthetic 8m-inset ring (the 'mirror').
      innerBoundary: block.innerBoundary ?? null,
      // Display image (Vercel Blob URL)
      displayImage: block.displayImage || null,
      imageLocationLat: block.imageLocationLat ?? null,
      imageLocationLng: block.imageLocationLng ?? null,
      // Block metadata
      streetName: block.streetName,
      fromCorner: block.fromCorner,
      toCorner: block.toCorner,
      side: block.side,
      includesAlley: block.includesAlley,
      // Funding
      funding: {
        goal: Number(block.fundingGoal) || 10000,
        current: Number(block.fundingCurrent) || 0,
      },
      fundedById: block.fundedById,
      fundedAt: block.fundedAt ? new Date(block.fundedAt).toISOString() : null,
      // Cleaning schedule (set by sponsor)
      schedule: {
        preferredDate: block.preferredCleaningDate ? new Date(block.preferredCleaningDate).toISOString() : null,
        preferredTimeStart: block.preferredCleaningTimeStart,
        preferredTimeEnd: block.preferredCleaningTimeEnd,
      },
      completedById: block.completedById,
      completedAt: block.completedAt ? new Date(block.completedAt).toISOString() : null,
      createdAt: new Date(block.createdAt).toISOString(),
      updatedAt: new Date(block.updatedAt).toISOString(),
    }))

  return Response.json({
    data: formattedBlocks,
    success: true,
    message: null,
    total,
    page,
    totalPages,
  })
}
