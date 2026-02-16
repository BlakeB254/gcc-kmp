import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyMagicLink } from '@/lib/magic-link'
import { createToken } from '@/lib/auth'

export const runtime = 'nodejs'

/**
 * Verify magic link and return auth token
 * POST /api/auth/verify-magic-link
 * Body: { token: string }
 */
export async function POST(request: NextRequest) {
  try {
    const { token } = await request.json()

    if (!token) {
      return NextResponse.json(
        { error: 'Token is required' },
        { status: 400 }
      )
    }

    // Verify the magic link token
    const { isValid, email, error } = verifyMagicLink(token)

    if (!isValid || !email) {
      return NextResponse.json(
        { error: error || 'Invalid token' },
        { status: 401 }
      )
    }

    // Find worker by email
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    if (!worker) {
      // No worker found - they need to register
      return NextResponse.json({
        success: true,
        isNewUser: true,
        email,
        message: 'Email verified. Please complete your registration.',
        registrationToken: Buffer.from(JSON.stringify({
          email,
          verified: true,
          exp: Date.now() + 30 * 60 * 1000, // 30 minutes
        })).toString('base64'),
      })
    }

    // Mark email as verified if not already
    if (!worker.emailVerified) {
      await db
        .update(workers)
        .set({
          emailVerified: true,
          emailVerifiedAt: new Date().toISOString(),
        })
        .where(eq(workers.id, worker.id))
    }

    // Create JWT token
    const jwtToken = await createToken({
      id: worker.id,
      email: worker.email,
      type: 'worker',
    })

    // Calculate expiration (90 days)
    const exp = Math.floor(Date.now() / 1000) + (90 * 24 * 60 * 60)

    // Build worker response
    const workerResponse = {
      id: String(worker.id),
      name: worker.name || '',
      email: worker.email,
      phone: worker.phone || '',
      emailVerified: true,
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
      success: true,
      token: jwtToken,
      worker: workerResponse,
      exp,
    })

    // Set HTTP-only cookie for web clients
    response.cookies.set('worker-token', jwtToken, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 90, // 90 days
    })

    return response

  } catch (error) {
    console.error('Magic link verification error:', error)
    return NextResponse.json(
      { error: 'Verification failed. Please try again.' },
      { status: 500 }
    )
  }
}
