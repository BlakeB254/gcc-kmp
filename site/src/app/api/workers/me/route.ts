import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

// Authenticate via cookie (web) or Bearer token (mobile)
async function getAuthenticatedWorkerId(request: NextRequest): Promise<number | null> {
  // Try Bearer token first (mobile app)
  const authHeader = request.headers.get('authorization')
  if (authHeader?.startsWith('Bearer ')) {
    const token = authHeader.substring(7)
    const result = await verifyMobileToken(token)
    if (result) return result.workerId
  }

  // Fall back to cookie auth (web dashboard)
  const token = request.cookies.get('worker-token')?.value
  if (!token) return null

  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'worker') return null

  return payload.id
}

export async function GET(request: NextRequest) {
  try {
    const workerId = await getAuthenticatedWorkerId(request)

    if (!workerId) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    // Check if request is from mobile app (Bearer token)
    const isMobile = request.headers.get('authorization')?.startsWith('Bearer ')

    if (isMobile) {
      // Mobile format: ApiResponse<Worker>
      return NextResponse.json({
        data: mapWorkerToMobile(worker as unknown as Record<string, unknown>),
        success: true,
      })
    }

    // Web format (keep backwards compatible)
    return NextResponse.json({
      success: true,
      user: {
        id: worker.id,
        name: worker.name,
        email: worker.email,
        phone: worker.phone,
        emailVerified: worker.emailVerified,
        emailVerifiedAt: worker.emailVerifiedAt,
        verificationStatus: worker.verificationStatus || 'pending',
        verificationNotes: worker.verificationReviewNotes,
        address: {
          street: worker.addressStreet,
          city: worker.addressCity,
          state: worker.addressState,
          zip: worker.addressZip,
          isNorthLawndale: worker.addressIsNorthLawndale,
        },
        pendingAddressChange:
          worker.pendingAddressChangeStatus === 'pending'
            ? {
                street: worker.pendingAddressChangeStreet,
                city: worker.pendingAddressChangeCity,
                state: worker.pendingAddressChangeState,
                zip: worker.pendingAddressChangeZip,
                requestedAt: worker.pendingAddressChangeRequestedAt,
              }
            : null,
        lastAddressChangeAt: worker.lastAddressChangeAt,
        stripeOnboardingStatus: worker.stripeOnboardingStatus || 'not_started',
        signupRankings: {
          global: worker.signupRankingsGlobal ? Number(worker.signupRankingsGlobal) : null,
          country: worker.signupRankingsCountry ? Number(worker.signupRankingsCountry) : null,
          state: worker.signupRankingsState ? Number(worker.signupRankingsState) : null,
          city: worker.signupRankingsCity ? Number(worker.signupRankingsCity) : null,
          neighborhood: worker.signupRankingsNeighborhood ? Number(worker.signupRankingsNeighborhood) : null,
        },
        earlyAdopterTier: worker.earlyAdopterTier,
        referralCode: worker.referralCode,
        stats: {
          jobsCompleted: Number(worker.statsJobsCompleted) || 0,
          totalEarnings: Number(worker.statsTotalEarnings) || 0,
          averageRating: worker.statsAverageRating ? Number(worker.statsAverageRating) : null,
          lastJobAt: worker.statsLastJobAt,
        },
        notifications: {
          pushEnabled: worker.notificationsPushEnabled,
          smsEnabled: worker.notificationsSmsEnabled,
          emailEnabled: worker.notificationsEmailEnabled,
        },
        createdAt: worker.createdAt,
      },
    })
  } catch (error) {
    console.error('Get worker profile error:', error)
    return NextResponse.json({ error: 'Failed to get profile' }, { status: 500 })
  }
}
