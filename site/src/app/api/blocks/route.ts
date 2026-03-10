import { NextRequest } from 'next/server'
import { db } from '@/db'
import { blocks, serviceAreas, communityReports } from '@/db/schema'
import { eq, desc, sql, and, isNotNull } from 'drizzle-orm'

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
      locationCenter: blocks.locationCenter,
      streetName: blocks.streetName,
      fromCorner: blocks.fromCorner,
      toCorner: blocks.toCorner,
      side: blocks.side,
      includesAlley: blocks.includesAlley,
      checkpoints: blocks.checkpoints,
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

  // Fetch community reports that have photos to match with blocks by proximity
  let reportsWithMedia: { id: number; mediaIds: unknown; coordinates: unknown }[] = []
  try {
    reportsWithMedia = await db
      .select({
        id: communityReports.id,
        mediaIds: communityReports.mediaIds,
        coordinates: communityReports.coordinates,
      })
      .from(communityReports)
      .where(isNotNull(communityReports.mediaIds))
      .limit(200)
    // Filter to only reports that actually have media URLs
    reportsWithMedia = reportsWithMedia.filter((r) => {
      const ids = r.mediaIds as string[] | null
      return ids && ids.length > 0
    })
  } catch {
    // Non-fatal — blocks still work without images
  }

  // Match reports to blocks by proximity (~200m from block center)
  const PROXIMITY_DEG = 0.002 // ~200m at Chicago latitude
  function getBlockDisplayImages(blockCenter: any): string[] {
    if (!blockCenter?.coordinates) return []
    const [blockLng, blockLat] = blockCenter.coordinates as [number, number]
    const images: string[] = []
    for (const report of reportsWithMedia) {
      const raw = report.coordinates as any
      let rLng: number, rLat: number
      if (Array.isArray(raw)) {
        rLng = raw[0]; rLat = raw[1]
      } else if (raw && typeof raw === 'object') {
        rLng = raw.lng ?? raw.lon ?? 0; rLat = raw.lat ?? 0
      } else continue
      if (Math.abs(rLng - blockLng) < PROXIMITY_DEG && Math.abs(rLat - blockLat) < PROXIMITY_DEG) {
        const mediaUrls = report.mediaIds as string[]
        if (mediaUrls) images.push(...mediaUrls)
      }
    }
    return images
  }

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
    // Block metadata
    streetName: block.streetName,
    fromCorner: block.fromCorner,
    toCorner: block.toCorner,
    side: block.side,
    includesAlley: block.includesAlley,
    checkpoints: block.checkpoints,
    // Funding
    funding: {
      goal: Number(block.fundingGoal) || 10000,
      current: Number(block.fundingCurrent) || 0,
    },
    fundedById: block.fundedById,
    fundedAt: block.fundedAt,
    // Cleaning schedule (set by sponsor)
    schedule: {
      preferredDate: block.preferredCleaningDate,
      preferredTimeStart: block.preferredCleaningTimeStart,
      preferredTimeEnd: block.preferredCleaningTimeEnd,
    },
    completedById: block.completedById,
    completedAt: block.completedAt,
    createdAt: block.createdAt,
    updatedAt: block.updatedAt,
    // Display images from nearby community reports
    displayImages: getBlockDisplayImages(block.locationCenter),
  }))

  return Response.json({
    blocks: formattedBlocks,
    total,
    page,
    totalPages,
  })
}
