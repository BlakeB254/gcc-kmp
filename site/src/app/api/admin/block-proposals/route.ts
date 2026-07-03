import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blockProposals } from '@/db/schema'
import { eq, desc } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { searchParams } = new URL(request.url)
    const status = searchParams.get('status')

    const query = db.select().from(blockProposals).orderBy(desc(blockProposals.createdAt))
    const rows = status ? await query.where(eq(blockProposals.status, status as 'pending' | 'approved' | 'rejected')) : await query

    return NextResponse.json({
      data: rows.map((r) => ({
        id: r.id,
        status: r.status,
        codeHint: r.codeHint,
        name: r.name,
        locationNeighborhood: r.locationNeighborhood,
        boundary: r.locationBoundary,
        center: r.locationCenter,
        streetName: r.streetName,
        fromCorner: r.fromCorner,
        toCorner: r.toCorner,
        side: r.side,
        exclusionZoneCount: Array.isArray(r.exclusionZones) ? r.exclusionZones.length : 0,
        exclusionZones: r.exclusionZones,
        source: r.source,
        generationBatch: r.generationBatch,
        reviewedByAdminId: r.reviewedByAdminId,
        reviewedAt: r.reviewedAt,
        rejectionReason: r.rejectionReason,
        publishedBlockId: r.publishedBlockId,
        createdAt: r.createdAt,
      })),
    })
  } catch (error) {
    console.error('Error fetching block proposals:', error)
    return NextResponse.json({ error: 'Failed to fetch block proposals' }, { status: 500 })
  }
}
