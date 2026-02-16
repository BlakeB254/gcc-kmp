import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyPassword, createToken } from '@/lib/auth'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const { email, password } = await request.json()

    if (!email || !password) {
      return NextResponse.json(
        { error: 'Email and password are required' },
        { status: 400 }
      )
    }

    // Find worker by email
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    if (!worker) {
      return NextResponse.json(
        { error: 'No account found with this email. Please check your email or sign up.' },
        { status: 401 }
      )
    }

    if (!worker.hash || !worker.salt) {
      // Worker hasn't set their password yet
      return NextResponse.json(
        {
          error: 'You haven\'t set your password yet. Please check your email for the verification link to complete your account setup.',
          code: 'PASSWORD_NOT_SET',
          workerId: worker.id,
        },
        { status: 401 }
      )
    }

    // Verify password
    const validPassword = verifyPassword(password, worker.hash, worker.salt)
    if (!validPassword) {
      return NextResponse.json(
        { error: 'Invalid email or password' },
        { status: 401 }
      )
    }

    // Check email verification
    if (!worker.emailVerified) {
      return NextResponse.json({
        error: 'Please verify your email before logging in.',
        code: 'EMAIL_NOT_VERIFIED',
        workerId: worker.id,
      }, { status: 403 })
    }

    // Check document verification status
    const verificationStatus = worker.verificationStatus || 'pending'

    if (verificationStatus === 'rejected') {
      return NextResponse.json({
        error: 'Your application was not approved.',
        code: 'VERIFICATION_REJECTED',
        reason: worker.verificationReviewNotes || 'Please contact support for more information.',
      }, { status: 403 })
    }

    // Create JWT token
    const token = await createToken({
      id: worker.id,
      email: worker.email,
      type: 'worker',
    })

    // Create response
    const response = NextResponse.json({
      success: true,
      user: {
        id: worker.id,
        name: worker.name,
        email: worker.email,
        phone: worker.phone,
        emailVerified: worker.emailVerified,
        verificationStatus,
        verificationNotes: verificationStatus === 'needs_info' ? worker.verificationReviewNotes : null,
        stripeOnboardingStatus: worker.stripeOnboardingStatus || 'not_started',
        stats: {
          jobsCompleted: Number(worker.statsJobsCompleted) || 0,
          totalEarnings: Number(worker.statsTotalEarnings) || 0,
          averageRating: worker.statsAverageRating ? Number(worker.statsAverageRating) : null,
        },
        address: {
          street: worker.addressStreet,
          city: worker.addressCity,
          state: worker.addressState,
          zip: worker.addressZip,
          isNorthLawndale: worker.addressIsNorthLawndale,
        },
        pendingAddressChange: worker.pendingAddressChangeStatus === 'pending' ? {
          street: worker.pendingAddressChangeStreet,
          city: worker.pendingAddressChangeCity,
          state: worker.pendingAddressChangeState,
          zip: worker.pendingAddressChangeZip,
          requestedAt: worker.pendingAddressChangeRequestedAt,
        } : null,
      },
    })

    // Set auth cookie
    response.cookies.set('worker-token', token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 90, // 90 days
    })

    return response
  } catch (error) {
    console.error('Login error:', error)
    return NextResponse.json(
      { error: 'Something went wrong. Please try again or contact support if the problem persists.' },
      { status: 500 }
    )
  }
}
