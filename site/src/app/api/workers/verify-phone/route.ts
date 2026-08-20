import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyCode, normalizePhone, createPhoneRegistrationToken } from '@/lib/phone-verification'
import { createToken } from '@/lib/auth'
import { createTwoFactorChallengeToken } from '@/lib/mobile-auth'
import { clientIp, isRateLimited } from '@/lib/rate-limit'

export const runtime = 'nodejs'

/**
 * Verify phone with code and return auth token
 * POST /api/workers/verify-phone
 * Body: { phone: string, code: string }
 */
export async function POST(request: NextRequest) {
  try {
    if (await isRateLimited(`verify-phone:${clientIp(request)}`)) {
      return NextResponse.json(
        { error: 'Too many requests. Please try again later.' },
        { status: 429 }
      )
    }

    const { phone, code } = await request.json()

    if (!phone || !code) {
      return NextResponse.json(
        { error: 'Phone and code are required' },
        { status: 400 }
      )
    }

    // Verify the code
    const { isValid, error } = await verifyCode(phone, code)

    if (!isValid) {
      return NextResponse.json(
        { error: error || 'Invalid verification code' },
        { status: 401 }
      )
    }

    // Normalize phone for database lookup
    const normalizedPhone = normalizePhone(phone)

    // Find worker by phone
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.phone, normalizedPhone))
      .limit(1)

    if (!worker) {
      // No worker found - they need to register first
      // Return a special response for new users
      return NextResponse.json({
        success: true,
        isNewUser: true,
        phone: normalizedPhone,
        message: 'Phone verified. Please complete your registration.',
        // Signed, short-lived, phone-bound token for registration (F-02)
        registrationToken: await createPhoneRegistrationToken(normalizedPhone),
      })
    }

    // Phone is verified — but if the worker has TOTP enabled, don't issue a
    // session here (that bypassed 2FA entirely — audit F-04). Mirror the
    // password-login flow: return a challenge and let /api/auth/verify-2fa
    // issue the real session after a valid authenticator/recovery code.
    if (worker.twoFactorEnabled && worker.totpSecret) {
      const twoFactorToken = await createTwoFactorChallengeToken(
        worker.id,
        worker.email || `phone:${normalizedPhone}`,
      )
      return NextResponse.json({
        success: true,
        requiresTwoFactor: true,
        twoFactorToken,
        token: null,
        worker: null,
        exp: 0,
      })
    }

    // Create JWT token
    const token = await createToken({
      id: worker.id,
      email: worker.email || `phone:${normalizedPhone}`,
      type: 'worker',
    })

    // Calculate expiration (90 days from now)
    const exp = Math.floor(Date.now() / 1000) + (90 * 24 * 60 * 60)

    // Build worker response object (matching mobile app's Worker type)
    const workerResponse = {
      id: String(worker.id),
      name: worker.name || '',
      email: worker.email || '',
      phone: worker.phone,
      phoneVerified: true,
      verification: {
        status: worker.verificationStatus || 'pending',
        governmentIdStatus: worker.verificationGovernmentIdStatus || 'pending',
        proofOfAddressStatus: worker.verificationProofOfAddressStatus || 'pending',
      },
      stats: {
        jobsCompleted: Number(worker.statsJobsCompleted) || 0,
        totalEarnings: Number(worker.statsTotalEarnings) || 0,
        averageRating: worker.statsAverageRating ? Number(worker.statsAverageRating) : null,
        currentStreak: 0,
        longestStreak: 0,
      },
      address: worker.addressStreet ? {
        street: worker.addressStreet,
        city: worker.addressCity,
        state: worker.addressState,
        zip: worker.addressZip,
        isNorthLawndale: worker.addressIsNorthLawndale || false,
      } : undefined,
      paymentSetup: {
        stripeAccountId: worker.stripeAccountId || undefined,
        onboardingStatus: worker.stripeOnboardingStatus || 'not_started',
        payoutsEnabled: worker.stripeInstantPayoutsEnabled || false,
      },
      createdAt: worker.createdAt || new Date().toISOString(),
      updatedAt: worker.updatedAt || new Date().toISOString(),
    }

    // Create response with token
    const response = NextResponse.json({
      token,
      worker: workerResponse,
      exp,
    })

    // Also set HTTP-only cookie for web clients
    response.cookies.set('worker-token', token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 90, // 90 days
    })

    return response
  } catch (error) {
    console.error('Phone verification error:', error)
    return NextResponse.json(
      { error: 'Verification failed. Please try again.' },
      { status: 500 }
    )
  }
}
