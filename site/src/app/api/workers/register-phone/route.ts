import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { createToken } from '@/lib/auth'

export const runtime = 'nodejs'

/**
 * Register a new worker after phone verification
 * POST /api/workers/register-phone
 * Body: { name, email, addressStreet, addressZip, phone, registrationToken }
 */
export async function POST(request: NextRequest) {
  try {
    const {
      name,
      email,
      addressStreet,
      addressZip,
      phone,
      registrationToken,
    } = await request.json()

    // Validate required fields
    if (!name || !email || !addressStreet || !addressZip || !phone || !registrationToken) {
      return NextResponse.json(
        { error: 'All fields are required' },
        { status: 400 }
      )
    }

    // Validate registration token
    try {
      const tokenData = JSON.parse(Buffer.from(registrationToken, 'base64').toString())

      if (tokenData.phone !== phone) {
        return NextResponse.json(
          { error: 'Invalid registration token' },
          { status: 400 }
        )
      }

      if (tokenData.exp < Date.now()) {
        return NextResponse.json(
          { error: 'Registration session expired. Please verify your phone again.' },
          { status: 400 }
        )
      }

      if (!tokenData.verified) {
        return NextResponse.json(
          { error: 'Phone not verified' },
          { status: 400 }
        )
      }
    } catch {
      return NextResponse.json(
        { error: 'Invalid registration token' },
        { status: 400 }
      )
    }

    // Check if email already exists
    const [existingEmail] = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    if (existingEmail) {
      return NextResponse.json(
        { error: 'An account with this email already exists' },
        { status: 400 }
      )
    }

    // Check if phone already exists
    const [existingPhone] = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.phone, phone))
      .limit(1)

    if (existingPhone) {
      return NextResponse.json(
        { error: 'An account with this phone number already exists' },
        { status: 400 }
      )
    }

    // Determine if address is in North Lawndale
    const isNorthLawndale = ['60623', '60624'].includes(addressZip)

    // Create worker (createdAt/updatedAt have defaultNow())
    const [worker] = await db
      .insert(workers)
      .values({
        name,
        email,
        phone,
        addressStreet,
        addressCity: 'Chicago',
        addressState: 'IL',
        addressZip,
        addressIsNorthLawndale: isNorthLawndale,
        emailVerified: false, // Still need email verification for password reset etc
        verificationStatus: 'pending',
      })
      .returning()

    // Create JWT token
    const token = await createToken({
      id: worker.id,
      email: worker.email,
      type: 'worker',
    })

    // Calculate expiration (90 days from now)
    const exp = Math.floor(Date.now() / 1000) + (90 * 24 * 60 * 60)

    // Build worker response object
    const workerResponse = {
      id: String(worker.id),
      name: worker.name,
      email: worker.email,
      phone: worker.phone,
      phoneVerified: true,
      emailVerified: false,
      verification: {
        status: worker.verificationStatus || 'pending',
        governmentIdStatus: 'pending',
        proofOfAddressStatus: 'pending',
      },
      stats: {
        jobsCompleted: 0,
        totalEarnings: 0,
        averageRating: null,
        currentStreak: 0,
        longestStreak: 0,
      },
      address: {
        street: worker.addressStreet,
        city: worker.addressCity,
        state: worker.addressState,
        zip: worker.addressZip,
        isNorthLawndale: worker.addressIsNorthLawndale || false,
      },
      paymentSetup: {
        stripeAccountId: undefined,
        onboardingStatus: 'not_started',
        payoutsEnabled: false,
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

    // Set HTTP-only cookie for web clients
    response.cookies.set('worker-token', token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 90, // 90 days
    })

    return response
  } catch (error) {
    console.error('Registration error:', error)
    return NextResponse.json(
      { error: 'Registration failed. Please try again.' },
      { status: 500 }
    )
  }
}
