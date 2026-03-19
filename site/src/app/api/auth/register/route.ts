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
      return Response.json({ data: null, success: false, message: 'Name, email, and password are required' }, { status: 400 })
    }

    // Check existing
    const [existing] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    // If user exists with a password already, reject
    if (existing && existing.hash) {
      return Response.json({ data: null, success: false, message: 'Email already registered' }, { status: 409 })
    }

    // If user exists without a password (magic-link user), set their password
    if (existing && !existing.hash) {
      const { hash: newHash, salt: newSalt } = hashPassword(password)
      await db
        .update(workers)
        .set({ hash: newHash, salt: newSalt, updatedAt: new Date().toISOString() })
        .where(eq(workers.id, existing.id))

      const { token, exp } = await createMobileToken(existing.id, email)
      const worker = mapWorkerToMobile({ ...existing, hash: newHash, salt: newSalt })
      return Response.json({
        data: { token, worker, exp, requiresTwoFactor: false, twoFactorToken: null },
        success: true,
        message: null,
      })
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

    return Response.json({
      data: { token, worker, exp, requiresTwoFactor: false, twoFactorToken: null },
      success: true,
      message: null,
    }, { status: 201 })
  } catch (error) {
    console.error('Register error:', error)
    return Response.json(
      { data: null, success: false, message: 'Registration failed' },
      { status: 500 }
    )
  }
}
