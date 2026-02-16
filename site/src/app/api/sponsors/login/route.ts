import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'
import { verifyPassword, createToken } from '@/lib/auth'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  let step = 'init'
  try {
    step = 'parse-body'
    const body = await request.json()
    const password = body.password
    // Normalize email to lowercase
    const email = body.email?.toLowerCase?.().trim?.()

    if (!email || !password) {
      return NextResponse.json(
        { error: 'Email and password are required' },
        { status: 400 }
      )
    }

    step = 'find-sponsor'
    // Find sponsor by email using raw SQL
    const result = await db.execute(sql`
      SELECT id, name, email, type, hash, salt, email_verified
      FROM sponsors
      WHERE email = ${email}
      LIMIT 1
    `)

    console.log('[sponsor-login] Looking up email:', email)

    const rows = result.rows as any[]
    console.log('[sponsor-login] Found rows:', rows.length)

    if (!rows || rows.length === 0) {
      return NextResponse.json(
        { error: 'No account found with this email. Please check the email address or sign up.' },
        { status: 401 }
      )
    }

    const sponsor = rows[0]

    step = 'check-email-verified'
    // Check if email is verified
    if (!sponsor.email_verified) {
      console.log('[sponsor-login] Email not verified for sponsor:', sponsor.id)
      return NextResponse.json(
        {
          error: 'Please verify your email before logging in. Check your inbox for the verification link.',
          code: 'EMAIL_NOT_VERIFIED',
          sponsorId: sponsor.id,
        },
        { status: 401 }
      )
    }

    step = 'check-password-set'
    if (!sponsor.hash || !sponsor.salt) {
      console.log('[sponsor-login] No hash/salt for sponsor:', sponsor.id, 'hash:', !!sponsor.hash, 'salt:', !!sponsor.salt)
      return NextResponse.json(
        { error: 'Your account password has not been set. Please contact support or reset your password.' },
        { status: 401 }
      )
    }

    step = 'verify-password'
    // Verify password
    const validPassword = verifyPassword(password, sponsor.hash, sponsor.salt)
    console.log('[sponsor-login] Password valid:', validPassword)

    if (!validPassword) {
      return NextResponse.json(
        { error: 'Invalid email or password. Please try again.' },
        { status: 401 }
      )
    }

    step = 'create-token'
    // Create JWT token
    const token = await createToken({
      id: sponsor.id,
      email: sponsor.email,
      type: 'sponsor',
    })

    step = 'build-response'

    // Create response with auth cookie
    const response = NextResponse.json({
      success: true,
      sponsor: {
        id: sponsor.id,
        name: sponsor.name,
        email: sponsor.email,
        type: sponsor.type,
      },
    })

    // Set auth cookie
    response.cookies.set('sponsor-token', token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 90, // 90 days
    })

    return response
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error'
    console.error(`[sponsor-login] Error at step [${step}]:`, errorMessage)
    console.error('[sponsor-login] Full error:', error)

    return NextResponse.json(
      {
        error: 'Something went wrong. Please try again or contact support if the problem persists.',
        debug: process.env.NODE_ENV === 'development' ? `Step: ${step}, Error: ${errorMessage}` : undefined,
      },
      { status: 500 }
    )
  }
}
