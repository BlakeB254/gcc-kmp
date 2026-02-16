import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'
import { createToken } from '@/lib/auth'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const { token, code, sponsorId } = await request.json()

    if (!sponsorId || (!token && !code)) {
      return NextResponse.json(
        { error: 'Invalid verification link. Please use the link from your email or enter your 6-digit code.' },
        { status: 400 }
      )
    }

    // Get the sponsor
    const result = await db.execute(sql`
      SELECT id, name, email, email_verification_token, email_verification_expires, email_verified
      FROM sponsors
      WHERE id = ${sponsorId}
      LIMIT 1
    `)

    const rows = result.rows as any[]
    if (!rows || rows.length === 0) {
      return NextResponse.json({ error: 'We couldn\'t find your account. Please check your verification link or sign up again.' }, { status: 404 })
    }

    const sponsor = rows[0]

    // Check if already verified
    if (sponsor.email_verified) {
      return NextResponse.json({
        success: true,
        message: 'Email already verified',
        alreadyVerified: true,
      })
    }

    // Check if verification has expired
    if (new Date(sponsor.email_verification_expires) < new Date()) {
      return NextResponse.json(
        { error: 'Verification link has expired. Please sign up again.' },
        { status: 400 }
      )
    }

    // Parse stored token (format: "token:code")
    const [storedToken, storedCode] = (sponsor.email_verification_token || '').split(':')

    // Verify token or code
    const tokenValid = token && storedToken && token === storedToken
    const codeValid = code && storedCode && code === storedCode

    if (!tokenValid && !codeValid) {
      return NextResponse.json(
        { error: 'The verification code you entered is incorrect. Please check your email and try again.' },
        { status: 400 }
      )
    }

    // Mark as verified
    await db.execute(sql`
      UPDATE sponsors
      SET email_verified = true,
          email_verified_at = ${new Date().toISOString()},
          email_verification_token = NULL
      WHERE id = ${sponsorId}
    `)

    // Create JWT token for auto-login
    const jwtToken = await createToken({
      id: sponsor.id,
      email: sponsor.email,
      type: 'sponsor',
    })

    // Create response with auth cookie
    const response = NextResponse.json({
      success: true,
      message: 'Email verified successfully!',
      sponsor: {
        id: sponsor.id,
        name: sponsor.name,
        email: sponsor.email,
      },
    })

    // Set auth cookie to log them in
    response.cookies.set('sponsor-token', jwtToken, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 90, // 90 days
    })

    return response
  } catch (error) {
    console.error('Sponsor email verification error:', error)
    return NextResponse.json(
      { error: 'Verification failed. Please try again.' },
      { status: 500 }
    )
  }
}
