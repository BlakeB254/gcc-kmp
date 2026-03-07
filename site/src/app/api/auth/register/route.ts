import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { hashPassword, createMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { name, email, phone, password } = body

    if (!name || !email || !password) {
      return Response.json({ error: 'Email already registered', status: 409 }, { status: 409 })
    }

    // Check existing
    const existing = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    if (existing.length > 0) {
      return Response.json({ error: 'Email already registered', status: 409 }, { status: 409 })
    }

    // Hash password
    const { hash, salt } = hashPassword(password)
    const now = new Date().toISOString()

    // Insert worker
    const [inserted] = await db
      .insert(workers)
      .values({
        name,
        email,
        phone: phone || '',
        salt,
        hash,
        emailVerified: false,
        addressStreet: '',
        addressCity: 'Chicago',
        addressState: 'IL',
        addressZip: '',
        verificationStatus: 'pending',
        verificationGovernmentIdStatus: 'pending',
        verificationProofOfAddressStatus: 'pending',
        stripeOnboardingStatus: 'not_started',
        stripeInstantPayoutsEnabled: false,
        stripeDefaultPayoutMethod: 'standard',
        statsJobsCompleted: '0',
        statsTotalEarnings: '0',
        earlyAdopterTier: 'standard',
        referralCode: `GCC-${name.substring(0, 2).toUpperCase()}${Math.floor(1000 + Math.random() * 9000)}`,
        notificationsPushEnabled: false,
        notificationsSmsEnabled: true,
        notificationsEmailEnabled: true,
        createdAt: now,
        updatedAt: now,
      })
      .returning()

    // Generate JWT
    const { token, exp } = await createMobileToken(inserted.id, email)

    // Map to mobile format
    const worker = mapWorkerToMobile(inserted)

    return Response.json({ data: { token, worker, exp } }, { status: 201 })
  } catch (error) {
    console.error('Register error:', error)
    return Response.json(
      { error: 'Registration failed', status: 500 },
      { status: 500 }
    )
  }
}
