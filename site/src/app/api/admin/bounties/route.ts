import { NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const reports = await db.select().from(communityReports).limit(100)

    // Map community reports to Bounty format expected by the iOS admin panel
    const result = reports.map((r) => {
      const coords = r.coordinates as { lat?: number; lng?: number } | [number, number] | null
      const lat = Array.isArray(coords) ? coords[1] : coords?.lat ?? 0
      const lng = Array.isArray(coords) ? coords[0] : coords?.lng ?? 0

      // Map report status to bounty status (lowercase to match Kotlin @SerialName)
      const statusMap: Record<string, string> = {
        pending: 'open',
        new: 'open',
        verified: 'open',
        acknowledged: 'in_progress',
        in_progress: 'in_progress',
        cleaned: 'completed',
        resolved: 'completed',
        rejected: 'expired',
        dismissed: 'expired',
      }

      return {
        id: r.id,
        posterType: r.reporterType === 'sponsor' ? 'sponsor' : 'worker',
        posterId: r.reporterId || 0,
        walletId: 0,
        title: r.title || r.description || `${r.type} report`,
        description: r.description,
        amount: 0,
        status: statusMap[r.status] || 'open',
        locationLat: lat,
        locationLng: lng,
        radiusMeters: 25,
        city: 'Chicago',
        communityArea: r.locationNeighborhood || 'North Lawndale',
        neighborhood: r.locationNeighborhood || 'North Lawndale',
        triggerReportId: null,
        observerId: null,
        firstRefusalExpiresAt: null,
        claimedBy: null,
        jobId: r.resolvedByJobId,
        mediaUrls: (r.mediaIds as string[]) || [],
        completionMediaUrls: null,
        aiAnalysis: null,
        expiresAt: r.mapExpiresAt,
        pledgeCount: 1,
        spotterTipAmount: null,
        spotterTipPaidAt: null,
        createdAt: new Date(r.createdAt).toISOString(),
        updatedAt: new Date(r.updatedAt).toISOString(),
      }
    })

    return NextResponse.json({ data: result })
  } catch (error) {
    console.error('Error fetching admin bounties:', error)
    return NextResponse.json({ error: 'Failed to fetch bounties' }, { status: 500 })
  }
}
