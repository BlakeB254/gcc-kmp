import { NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers, verificationStatusEnum } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { searchParams } = new URL(request.url)
    const limit = parseInt(searchParams.get('limit') || '50', 10)
    const verificationStatusParam = searchParams.get('verificationStatus')
    const verificationStatus = verificationStatusParam as (typeof verificationStatusEnum.enumValues)[number] | null

    const workerList = verificationStatus
      ? await db.select().from(workers).where(eq(workers.verificationStatus, verificationStatus)).limit(limit)
      : await db.select().from(workers).limit(limit)

    const result = workerList.map((w) => ({
      id: w.id,
      name: w.name,
      email: w.email,
      phone: w.phone,
      emailVerified: w.emailVerified ?? false,
      address: {
        street: w.addressStreet,
        city: w.addressCity,
        state: w.addressState,
        zip: w.addressZip,
        neighborhood: w.addressNeighborhood,
        isNorthLawndale: w.addressIsNorthLawndale,
      },
      verification: {
        status: w.verificationStatus || 'pending',
        governmentIdStatus: w.verificationGovernmentIdStatus || 'pending',
        proofOfAddressStatus: w.verificationProofOfAddressStatus || 'pending',
        reviewNotes: w.verificationReviewNotes,
      },
      stripe: {
        accountId: w.stripeAccountId,
        onboardingStatus: w.stripeOnboardingStatus || 'not_started',
        instantPayoutsEnabled: w.stripeInstantPayoutsEnabled ?? false,
        defaultPayoutMethod: w.stripeDefaultPayoutMethod || 'standard',
      },
      stats: {
        jobsCompleted: parseInt(w.statsJobsCompleted || '0'),
        totalEarnings: parseInt(w.statsTotalEarnings || '0'),
        averageRating: w.statsAverageRating ? parseFloat(w.statsAverageRating) : null,
        lastJobAt: w.statsLastJobAt ? new Date(w.statsLastJobAt).toISOString() : null,
      },
      earlyAdopterTier: w.earlyAdopterTier || 'standard',
      role: 'worker',
      referralCode: w.referralCode,
      notifications: {
        pushEnabled: w.notificationsPushEnabled ?? false,
        smsEnabled: w.notificationsSmsEnabled ?? true,
        emailEnabled: w.notificationsEmailEnabled ?? true,
      },
      createdAt: new Date(w.createdAt).toISOString(),
      updatedAt: new Date(w.updatedAt).toISOString(),
    }))

    return NextResponse.json({ data: result })
  } catch (error) {
    console.error('Error fetching admin workers:', error)
    return NextResponse.json({ error: 'Failed to fetch workers' }, { status: 500 })
  }
}
